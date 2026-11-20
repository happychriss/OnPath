// A stop pinned on a POI is the plain numbered point in the planner (owner 2026-11-20: a station picked as a point was
// drawn very big – „ein normaler Punkt reicht“; the app keeps the POI's symbol in a blue ring, MapController.poiMarker):
// (a) a POI tapped on the map and added from its menu: the plain ①, the POI's name in its title; (b) a plan from a link
// with a pinned middle stop on a POI: the plain ②, its menu still shows the POI's details (kind · elevation);
// (c) a dragged-away POI stop stays a plain dot.
// Usage: node test/poipin-smoke.mjs [segmentsDir] [mapStoreDir] [screenshot.png]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = path.resolve(process.argv[3] ?? '/home/user/build/cell/store');
const shot = process.argv[4] ?? path.join(here, '../../engine/build/poipin.png');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2 });
await ctx.route('https://photon.komoot.io/**', (r) => r.abort());
const page = await ctx.newPage();
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const VIEW = '11.1450,46.6880,16'; // Algund / Vellau: inns and a few huts

// With software GL (SwiftShader, the tests' headless Chromium) the map's GPU work can lag the page by half a minute on a
// busy machine. A reload (Chrome destroys the old WebGL context before the new page commits) and a screenshot wait for
// it and would spend their own 30 s on it – drained first: a 1-pixel read waits for every frame drawn before it.
const drainGpu = () => page.evaluate(() => {
  const gl = window.__map?.getCanvas().getContext('webgl2');
  gl?.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(4));
}).catch(() => {}); // expected: about:blank before the first load – no map
const ready = async (hash = '') => {
  await drainGpu();
  await page.goto(url + 'index.html?at=' + VIEW + hash);
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
};
/** A POI drawn on the map with its own symbol, near the middle of the view: { kind, lat, lon, x, y }. */
const findPoi = (pg = page) => pg.waitForFunction(() => {
  const map = window.__map;
  if (!map.loaded()) return null;
  const layers = map.getStyle().layers.filter((l) => l.id.startsWith('poi-') && !l.id.endsWith('-name')).map((l) => l.id);
  const c = map.getCanvas(), w = c.clientWidth, h = c.clientHeight;
  const fs = map.queryRenderedFeatures([[w * 0.25, h * 0.25], [w * 0.75, h * 0.75]], { layers })
    .filter((f) => f.geometry.type === 'Point' && f.properties?.kind && f.properties.kind !== 'bus' && map.hasImage('poi-' + f.properties.kind));
  if (!fs.length) return null;
  const [lon, lat] = fs[0].geometry.coordinates, p = map.project([lon, lat]);
  return { kind: String(fs[0].properties.kind), lat, lon, x: p.x, y: p.y };
}, null, { timeout: 60_000, polling: 500 }).then((h) => h.jsonValue());
/** Each drawn point: what makes it the plain numbered dot (no POI image, no badge, its number, the normal size). */
const pinInfo = () => page.$$eval('.pin', (els) => els.map((e) => { const r = e.getBoundingClientRect();
  return { plain: !e.classList.contains('poipin') && !e.classList.contains('night') && !e.querySelector('img, .badge') && !e.dataset.kind, text: e.textContent, title: e.title,
    w: Math.round(r.width), h: Math.round(r.height) }; }));
const plainSize = (p) => p.w <= 30 && p.h <= 30;

// (a) tapped on the map → its menu → the first add entry (an empty plan: "Start here")
await ready();
const poi = await findPoi();
console.log('POI:', poi);
const box = await page.locator('#map canvas').first().boundingBox();
await page.mouse.click(box.x + poi.x, box.y + poi.y);
await page.waitForSelector('.holdpopup button', { timeout: 15_000 });
await page.click('.holdpopup button');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 1, null, { timeout: 15_000 });
let pins = await pinInfo();
const poiName = await page.evaluate(() => window.__state.meta[0]?.name ?? null);
check(pins[0].plain && pins[0].text === '1' && plainSize(pins[0]), `tapped POI (${poi.kind}) as start: the plain ① (${JSON.stringify(pins[0])})`);
check(!!poiName && pins[0].title.includes(poiName), `its title names the POI („${poiName}“: ${pins[0].title})`);
check(await page.locator('.pin.poipin').count() === 0, 'no POI ring or badge');

// (b) a plan from a link: a held point → the POI (pinned) → a held point; the POI's kind comes from the map
const a = [poi.lat - 0.004, poi.lon - 0.004], b = [poi.lat + 0.004, poi.lon + 0.005];
await ready();
const code = await page.evaluate(([pts]) => window.__engine.encode({ points: pts, activity: 'HIKE', sac: 2, pinned: [1], names: {}, title: null }),
  [[a, [poi.lat, poi.lon], b]]);
await ready('#p=' + code);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 3, null, { timeout: 30_000 }).catch(() => {});
// the POIs are in (a pinned stop's kind used to come late and redraw it): still the plain ②
await page.waitForFunction(() => window.__map.loaded(), null, { timeout: 30_000 }).catch(() => {});
pins = await pinInfo();
check(pins.length === 3 && pins.every((p) => p.plain && plainSize(p)) && pins[1].text === '2',
  `plan from a link: the stop pinned on the ${poi.kind} is the plain ② like the others (${JSON.stringify(pins.map((p) => [p.text, p.w]))})`);
// the first route of a plan is framed whole 0.5 s after it came (WholeRoute.SETTLE_MS, main.ts fitRoute): the camera has
// to be at rest – the pin still for a second – before its place is read, or the drag below grabs the map, not the pin
await page.waitForFunction(() => {
  const el = document.querySelectorAll('.pin')[1], w = window, now = performance.now();
  if (!el || w.__map.isMoving()) { w.__still = null; return false; }
  const r = el.getBoundingClientRect(), at = `${Math.round(r.x)},${Math.round(r.y)}`;
  if (w.__still?.at !== at) { w.__still = { at, since: now }; return false; }
  return now - w.__still.since >= 1000;
}, null, { timeout: 30_000, polling: 100 });
const el = page.locator('.pin').nth(1);
const pb = await el.boundingBox();
await drainGpu();
await page.screenshot({ path: shot, clip: { x: pb.x - 140, y: pb.y - 100, width: pb.width + 280, height: pb.height + 200 } });
console.log('screenshot:', shot);
// its menu still says which POI it sits on (kind · elevation, the engine's poiMenu)
await el.click();
await page.waitForSelector('.holdpopup .menuhead', { timeout: 15_000 });
check(await page.locator('.holdpopup .menuhead .poidetails').count() === 1, `the ② menu shows the POI's details (${await page.textContent('.holdpopup .menuhead')})`);
await page.keyboard.press('Escape');
await page.waitForFunction(() => !document.querySelector('.holdpopup'), null, { timeout: 5_000 }).catch(() => {});

// (c) dragged away: a new place – no longer pinned, the plain dot
await page.mouse.move(pb.x + pb.width / 2, pb.y + pb.height / 2);
await page.mouse.down();
await page.mouse.move(pb.x + pb.width / 2 + 60, pb.y + pb.height / 2 + 40, { steps: 8 });
await page.mouse.up();
await page.waitForFunction(() => !window.__state.meta[1]?.pinned, null, { timeout: 30_000 }).catch(() => {});
pins = await pinInfo();
check(await page.evaluate(() => !window.__state.meta[1]?.pinned) && pins[1]?.plain && pins[1].text === '2', '(c) the stop dragged off the POI: no longer pinned, the plain ②');

// (d) a POI's name is no target of its own (core feature C19, owner 2026-11-15, Hilden: a bus stop's bare name opened the
// place menu – the app's PoiHit: a hit only through the icon). Since 2026-11-17 (app 475a7268, owner) the style draws POI
// names only from z17, beside their icon in one symbol (`poi-<x>-named`) – so at z17+ a click on the name, away from its
// icon, is a plain map click (on the empty plan the first-point menu „New plan“, not the POI's menu), and the pointer
// over it is no hand (a name alone does nothing). (A click on a drawn symbol opens its menu: (a).)
// a fresh page: an empty plan (this browser's Zuletzt would bring the plan above back)
const dctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2 });
await dctx.route('https://photon.komoot.io/**', (r) => r.abort());
const dp = await dctx.newPage();
dp.on('pageerror', (e) => errors.push(String(e)));
await dp.goto(url + 'index.html?lang=en&at=' + VIEW);
await dp.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
const seen = await findPoi(dp);
// closer, on a POI of the view: names are drawn from z17 only
await dp.evaluate(([lo, la]) => window.__map.jumpTo({ center: [lo, la], zoom: 17.5 }), [seen.lon, seen.lat]);
await dp.waitForFunction(() => window.__map.loaded() && !window.__map.isMoving(), null, { timeout: 30_000 }).catch(() => {});
await dp.waitForTimeout(1000);
const named = await dp.evaluate(() => window.__map.getStyle().layers.filter((l) => l.id.startsWith('poi-') && l.id.endsWith('-named')).length);
check(named > 0, `(d) the planner's style has the app's POI layers with names from z17 („-named“: ${named})`);
/** A pixel on a POI's drawn name with no POI icon in a click's reach: { name, x, y, dx, dy } or null. */
const onName = () => dp.evaluate(() => {
  const map = window.__map, c = map.getCanvas(), w = c.clientWidth, h = c.clientHeight;
  const layers = map.getStyle().layers.filter((l) => l.id.startsWith('poi-') && !l.id.endsWith('-name')).map((l) => l.id);
  const namedLayers = layers.filter((id) => id.endsWith('-named'));
  const named = map.queryRenderedFeatures([[w * 0.2, h * 0.2], [w * 0.8, h * 0.8]], { layers: namedLayers }).filter((f) => f.properties?.name && f.geometry.type === 'Point');
  for (const f of named) {
    const p = map.project(f.geometry.coordinates);
    for (let dy = -24; dy <= 30; dy += 2) for (let dx = -120; dx <= 120; dx += 3) {
      const x = p.x + dx, y = p.y + dy;
      const hit = map.queryRenderedFeatures([x, y], { layers: namedLayers }).some((g) => g.properties?.name === f.properties.name);
      // no POI point within a click's reach (10 px box + the 11 px icon half, a little more) – else that POI is the target
      const icons = map.queryRenderedFeatures([[x - 26, y - 26], [x + 26, y + 26]], { layers })
        .filter((g) => { const q = map.project(g.geometry.coordinates); return Math.abs(q.x - x) <= 26 && Math.abs(q.y - y) <= 26; });
      if (hit && !icons.length) return { name: String(f.properties.name), x, y, dx, dy };
    }
  }
  return null;
});
const clickName = async (n) => {
  const cb = await dp.locator('#map canvas').first().boundingBox();
  await dp.mouse.click(cb.x + n.x, cb.y + n.y);
  await dp.waitForSelector('.holdpopup .menuhead', { timeout: 15_000 }).catch(() => {});
  return dp.evaluate(() => ({ head: document.querySelector('.holdpopup .menuhead')?.textContent ?? null,
    rows: [...document.querySelectorAll('.holdpopup .pointmenu button .txt > span:first-child')].map((b) => b.textContent) }));
};
const bare = await onName();
check(!!bare, '(d) a POI name drawn beside its icon at z17+ found in the view');
if (bare) {
  const cb = await dp.locator('#map canvas').first().boundingBox();
  await dp.mouse.move(cb.x + bare.x, cb.y + bare.y, { steps: 3 });
  await dp.waitForTimeout(300);
  const cursor = await dp.evaluate(() => window.__map.getCanvas().style.cursor);
  check(cursor !== 'pointer', `(d) the pointer over the name „${bare.name}“ is no hand (${JSON.stringify(cursor)})`);
  const m = await clickName(bare);
  check(m.head != null && !m.head.includes(bare.name) && m.rows.join('|') === 'Start here|Round trip from here|Go here',
    `(d) a click on the name „${bare.name}“ (${bare.dx}, ${bare.dy} px from its icon) is a plain map click, no POI menu: ${JSON.stringify(m)}`);
  await dp.keyboard.press('Escape');
}
await dctx.close();

// as search-smoke: a missing map file (404) or a dropped request is no fault of this page
const real = errors.filter((e) => !/status of 404/.test(e) && !/ERR_FAILED|net::|CORS policy/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
check(!real.length, `no page errors (${real.slice(0, 3).join(' | ')})`);
await browser.close();
server.close();
if (fails.length) { console.log(`\n${fails.length} FAILED`); process.exit(1); }
console.log('\npoipin smoke: all ok');
