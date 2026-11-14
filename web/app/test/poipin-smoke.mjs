// A stop pinned on a POI keeps the POI's symbol (owner 2026-11-10, variant A – the app's MapController.poiMarker):
// the symbol in a route-blue ring, the number on a small badge. (a) a POI tapped on the map and added from its menu;
// (b) a plan from a link with a pinned middle stop on a POI – the kind is looked up on the map (PinnedPoiKinds);
// (c) a point that is not pinned stays the plain dot; a dragged-away POI stop becomes a plain dot.
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
const findPoi = () => page.waitForFunction(() => {
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
const pinInfo = () => page.$$eval('.pin', (els) => els.map((e) => ({ poi: e.classList.contains('poipin'), kind: e.dataset.kind ?? null,
  badge: e.querySelector('.badge')?.textContent ?? null, ring: getComputedStyle(e).borderTopColor,
  round: (() => { const b = e.querySelector('.badge')?.getBoundingClientRect(); return !b || Math.abs(b.width - b.height) < 0.5; })() })));

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
check(pins[0].poi && pins[0].kind === poi.kind, `tapped POI as start: its own symbol stays (${poi.kind}, ${JSON.stringify(pins[0])})`);
check(pins[0].badge === '1', 'the badge says ① (the order)');
check(pins.every((p) => p.round), 'the badge is a circle, not an oval (owner 2026-11-13, Pixel 8)');
check(/rgb\(30, 63, 174\)/.test(pins[0].ring), `the ring is route blue (${pins[0].ring})`);

// (b) a plan from a link: a held point → the POI (pinned) → a held point; the POI's kind comes from the map
const a = [poi.lat - 0.004, poi.lon - 0.004], b = [poi.lat + 0.004, poi.lon + 0.005];
await ready();
const code = await page.evaluate(([pts]) => window.__engine.encode({ points: pts, activity: 'HIKE', sac: 2, pinned: [1], names: {}, title: null }),
  [[a, [poi.lat, poi.lon], b]]);
await ready('#p=' + code);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
await page.waitForFunction(() => document.querySelectorAll('.pin.poipin').length === 1, null, { timeout: 30_000 }).catch(() => {});
pins = await pinInfo();
check(pins.length === 3 && pins[1].poi && pins[1].kind === poi.kind && pins[1].badge === '2',
  `plan from a link: the pinned stop shows the ${poi.kind} symbol with ② (${JSON.stringify(pins.map((p) => p.kind ?? p.badge))})`);
check(!pins[0].poi && !pins[2].poi, '(c) the points that are not pinned stay plain dots');
// the first route of a plan is framed whole 0.5 s after it came (WholeRoute.SETTLE_MS, main.ts fitRoute): the camera has
// to be at rest – the pin still for a second – before its place is read, or the drag below grabs the map, not the pin
await page.waitForFunction(() => {
  const el = document.querySelector('.pin.poipin'), w = window, now = performance.now();
  if (!el || w.__map.isMoving()) { w.__still = null; return false; }
  const r = el.getBoundingClientRect(), at = `${Math.round(r.x)},${Math.round(r.y)}`;
  if (w.__still?.at !== at) { w.__still = { at, since: now }; return false; }
  return now - w.__still.since >= 1000;
}, null, { timeout: 30_000, polling: 100 });
const el = page.locator('.pin.poipin');
const pb = await el.boundingBox();
await drainGpu();
await page.screenshot({ path: shot, clip: { x: pb.x - 140, y: pb.y - 100, width: pb.width + 280, height: pb.height + 200 } });
console.log('screenshot:', shot);

// (c) dragged away: a new place – no longer pinned, the plain dot
await page.mouse.move(pb.x + pb.width / 2, pb.y + pb.height / 2);
await page.mouse.down();
await page.mouse.move(pb.x + pb.width / 2 + 60, pb.y + pb.height / 2 + 40, { steps: 8 });
await page.mouse.up();
await page.waitForFunction(() => document.querySelectorAll('.pin.poipin').length === 0, null, { timeout: 30_000 }).catch(() => {});
check(await page.locator('.pin.poipin').count() === 0, '(c) the stop dragged off the POI is a plain dot again');

// as search-smoke: a missing map file (404) or a dropped request is no fault of this page
const real = errors.filter((e) => !/status of 404/.test(e) && !/ERR_FAILED|net::|CORS policy/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
check(!real.length, `no page errors (${real.slice(0, 3).join(' | ')})`);
await browser.close();
server.close();
if (fails.length) { console.log(`\n${fails.length} FAILED`); process.exit(1); }
console.log('\npoipin smoke: all ok');
