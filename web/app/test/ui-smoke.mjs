// Clicks two points on the planner page in headless Chromium and checks that a route + profile appear.
// Usage: node test/ui-smoke.mjs [segmentsDir] [mapStoreDir (cells/f2/…, like the R2 bucket)] [screenshot.png]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = process.argv[3] ? path.resolve(process.argv[3]) : null;
const shot = process.argv[4] ?? path.join(here, '../../engine/build/planner.png');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url + 'index.html');
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
// Meran town centre → Schenna, one click each (map centred on 11.16 / 46.67 at z12)
const clickAt = async (lon, lat) => {
  const p = await page.evaluate(([lo, la]) => { const m = window.__map; const q = m.project([lo, la]); return [q.x, q.y]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1]);
};
await clickAt(11.1600, 46.6700);
// the first click on an empty plan asks like the app's first hold: Start here · Go here
await page.waitForSelector('.pointmenu');
await page.click('.pointmenu button:first-of-type');
await page.waitForTimeout(300);
await clickAt(11.1900, 46.6900);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
await page.waitForTimeout(1500);
const summary = await page.textContent('#summary');
const status = await page.textContent('#status');
await page.screenshot({ path: shot });
// ---- terrain view (owner 2026-11-05, terrainview.ts): A – the profile's cursor and the big profile linked to the map ----
const terrainFails = [];
const tcheck = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) terrainFails.push(what); };
const cursorNow = () => page.evaluate(() => window.__profileCursor);
// wide screens (plan sheet A, owner 2026-11-07): the profile lies along the map's bottom edge for good – no ⤢, no ✕;
// the route stays plan blue (the steepness is read in the profile)
tcheck(await page.locator('#bigProfile').isVisible() && !(await page.locator('#bigProfile .bpclose').isVisible()) && !(await page.locator('#profile .profplot').isVisible()),
  'the profile lies along the bottom of the map for good (no ✕), not in the panel');
const small = await page.locator('#bigProfile .profplot > svg').boundingBox();
await page.mouse.move(small.x + small.width * 0.5, small.y + small.height * 0.5);
await page.waitForTimeout(200);
const c1 = await cursorNow();
const label1 = await page.locator('#bigProfile .proflabel').textContent();
tcheck(c1 && c1.at && c1.m > 0, `bottom profile: pointer → cursor on the route (${JSON.stringify(c1)})`);
tcheck(/\d+ m · .+ · -?\d+ %/.test(label1 ?? '') && await page.locator('#bigProfile .proflabel').isVisible(), `bottom profile label "${label1}"`);
// the dot is a DOM marker on the map (no map redraw per move – 3D + big profile were slow, owner 2026-11-06)
const dot = () => page.evaluate(() => { const d = document.querySelector('.profdot'); return !!d && !d.hidden && d.getBoundingClientRect().width > 0; });
tcheck(await dot(), 'the dot on the map follows the profile');
await page.mouse.move(5, 400);
await page.waitForTimeout(200);
tcheck(await cursorNow() == null && !(await dot()), 'pointer off the profile: cursor and dot gone');
const axis = await page.locator('#bigProfile .axis span').allTextContents();
tcheck(axis.length === 2 && /km$/.test(axis[1]), `profile axis "0 … total km" (${JSON.stringify(axis)})`);
const steep = await page.evaluate(() => window.__map.getLayoutProperty('profile-steep', 'visibility') ?? 'none');
tcheck(steep !== 'visible', `the route stays plan blue beside the bottom profile (${steep})`);
// the app's steepness colours everywhere – green for flat, never the route's blue (owner review 2026-11-05)
const colours = await page.evaluate(async () => ({
  legend: [...document.querySelectorAll('#bigProfile .bplegend i')].map((i) => getComputedStyle(i).backgroundColor),
  line: [...new Set((await window.__map.getSource('profile-steep').getData()).features.map((f) => f.properties.colour))],
  fills: [...new Set([...document.querySelectorAll('#bigProfile .profplot > svg path')].map((p) => p.getAttribute('fill')))] }));
const APP = ['#7fb77e', '#e3b23c', '#e07b39', '#c0392b'];
tcheck(JSON.stringify(colours.legend) === JSON.stringify(['rgb(127, 183, 126)', 'rgb(227, 178, 60)', 'rgb(224, 123, 57)', 'rgb(192, 57, 43)'])
  && colours.line.every((c) => APP.includes(c)) && colours.fills.every((c) => APP.includes(c)), `steepness colours = the app's (${JSON.stringify(colours)})`);
// a marked range (owner 2026-11-06): press and drag in the big profile → length · time · climb, the stretch on the map;
// a click clears it
const bigSvg = await page.locator('#bigProfile .profplot > svg').boundingBox();
await page.mouse.move(bigSvg.x + bigSvg.width * 0.2, bigSvg.y + bigSvg.height * 0.5);
await page.mouse.down();
await page.mouse.move(bigSvg.x + bigSvg.width * 0.45, bigSvg.y + bigSvg.height * 0.5, { steps: 5 });
await page.mouse.move(bigSvg.x + bigSvg.width * 0.7, bigSvg.y + bigSvg.height * 0.5, { steps: 5 });
await page.mouse.up();
await page.waitForFunction(() => !document.querySelector('#bigProfile .bprange')?.hidden, null, { timeout: 10_000 }).catch(() => {});
// the range lands every 40 ms while dragging (markSoon): wait for the drag's last one, not an earlier
await page.waitForFunction(() => { const r = window.__profileRange, t = window.__state.plan?.distanceM ?? 0; return !!r && Math.max(r[0], r[1]) > t * 0.65; }, null, { timeout: 10_000 }).catch(() => {});
const marked = await page.evaluate(async () => ({ text: document.querySelector('#bigProfile .bprange')?.textContent ?? '',
  shade: !document.querySelector('#bigProfile .profrange')?.hidden, total: window.__state.plan.distanceM, r: window.__profileRange,
  line: (await window.__map.getSource('profile-range').getData())?.geometry?.coordinates?.length ?? 0 }));
const m = /^([\d.,]+) (k?m) · (.+) · ↑ (\d+) m ↓ (\d+) m$/.exec(marked.text);
tcheck(!!m && parseFloat(m[1].replace(',', '.')) > 0 && /\d/.test(m[3]) && marked.shade && marked.line >= 2 && marked.r
  && Math.abs(Math.abs(marked.r[1] - marked.r[0]) - marked.total * 0.5) < marked.total * 0.05,
  `marked range: "${marked.text}", shaded, on the map (${JSON.stringify({ ...marked, text: undefined })})`);
await page.mouse.click(bigSvg.x + bigSvg.width * 0.5, bigSvg.y + bigSvg.height * 0.5);
await page.waitForTimeout(200);
const cleared = await page.evaluate(async () => ({ label: document.querySelector('#bigProfile .bprange')?.hidden,
  shade: document.querySelector('#bigProfile .profrange')?.hidden, line: (await window.__map.getSource('profile-range').getData())?.features?.length ?? -1 }));
tcheck(cleared.label && cleared.shade && cleared.line === 0, `a click clears the mark (${JSON.stringify(cleared)})`);
await page.screenshot({ path: process.env.RANGE_SHOT || shot.replace(/\.png$/, '-range.png') }).catch(() => {});
// the pointer on the route (a vertex in the middle) → the big profile shows the cursor there
const onRoute = await page.evaluate(() => {
  const legs = window.__state.plan.legs.flatMap((l) => l.points);
  const v = legs[Math.floor(legs.length / 2)];
  const p = window.__map.project([v[1], v[0]]); const r = window.__map.getContainer().getBoundingClientRect();
  return { x: p.x + r.left, y: p.y + r.top };
});
await page.mouse.move(onRoute.x, onRoute.y);
await page.waitForTimeout(300);
const c2 = await cursorNow();
tcheck(c2 && c2.m > 0 && await page.locator('#bigProfile .proflabel').isVisible(), `pointer on the route → cursor in the big profile (${JSON.stringify(c2)})`);
await page.mouse.move(5, 400);
// ---- C – 3D: terrain on demand, tilted, view only; off = flat again ----
tcheck(!(await page.locator('#terrain3d').isDisabled()), '3D button enabled (the cells\' terrain source is there)');
await page.click('#terrain3d');
await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 30_000 });
const on3d = await page.evaluate(() => ({ terrain: window.__map.getTerrain()?.source ?? null, pitch: window.__map.getPitch(),
  pressed: document.getElementById('terrain3d').getAttribute('aria-pressed'), note: !document.getElementById('threeDNote').hidden }));
tcheck(on3d.terrain === 'terrain' && on3d.pitch > 55 && on3d.pressed === 'true' && on3d.note, `3D on: ${JSON.stringify(on3d)}`);
tcheck(/rechte Maustaste|right mouse button/.test(await page.locator('#threeDNote .turnhint').innerText()), '3D note says how to turn and tilt (mouse)');
const nBefore = await page.evaluate(() => window.__state.points.length);
const mapBox = await page.locator('#map').boundingBox();
await page.mouse.click(mapBox.x + mapBox.width * 0.75, mapBox.y + mapBox.height * 0.5);
await page.waitForTimeout(500);
tcheck(await page.evaluate(() => window.__state.points.length) === nBefore && await page.locator('.pointmenu').count() === 0, '3D is view only: a click adds no point');
// the flyover (owner 2026-11-06, D): ▶ Abfliegen → the camera moves along the route with a calm direction and the
// profile cursor; Pause holds the camera; Stopp frames the route in 3D again
tcheck(await page.locator('#flyStart').isVisible() && !(await page.locator('#flyStart').isDisabled()), 'flyover: ▶ Fly along shown in 3D');
await page.click('#flyStart');
const flyFrom = await page.evaluate(() => ({ d: window.__fly.status.d, c: window.__map.getCenter().toArray() }));
// slow software GL in CI: wait for the flight to get somewhere, not a fixed time
await page.waitForFunction((d0) => window.__fly.status.d > d0 + 300 && window.__fly.trace.length >= 4, flyFrom.d, { timeout: 60_000 }).catch(() => {});
const f1 = await page.evaluate(() => ({ ...window.__fly.status, c: window.__map.getCenter().toArray(), p: window.__map.getPitch(),
  cur: window.__profileCursor?.m ?? null, trace: window.__fly.trace.slice() }));
if (process.env.FLY_SHOT) await page.screenshot({ path: process.env.FLY_SHOT }).catch(() => {});
// calm (owner 2026-11-07: "ruhiger"): the direction never turns faster than 20° a second (MAX_TURN; 25 leaves room for rounding)
const maxTurn = Math.max(0, ...f1.trace.slice(1).map((s, i) => Math.abs(((s.b - f1.trace[i].b + 540) % 360) - 180) / Math.max(0.05, (s.t - f1.trace[i].t) / 1000)));
tcheck(f1.state === 'flying' && f1.d > flyFrom.d + 300 && (f1.c[0] !== flyFrom.c[0] || f1.c[1] !== flyFrom.c[1]) && f1.p > 50 && maxTurn < 25
  && f1.cur != null && Math.abs(f1.cur - f1.d) < 1, `flyover: the camera moves along, calm turns, cursor along (${JSON.stringify({ d0: flyFrom.d, d1: f1.d, maxTurn, p: f1.p, cur: f1.cur, frames: f1.trace.length })})`);
// the controls appear where the fly button was – right beside the toolbar's 3D (owner 2026-11-07)
const near = await page.evaluate(() => { const a = document.getElementById('terrain3d').getBoundingClientRect(), b = document.getElementById('flyPause').getBoundingClientRect();
  return { dx: Math.round(b.left - a.right), dy: Math.round(Math.abs((b.top + b.bottom) / 2 - (a.top + a.bottom) / 2)), inBar: !!document.getElementById('flyBar').closest('#toolbar') }; });
tcheck(near.inBar && near.dx >= 0 && near.dx < 24 && near.dy < 6, `flyover: Pause / Stop / 1× sit right beside 3D in the toolbar ${JSON.stringify(near)}`);
await page.click('#flyPause');
await page.waitForTimeout(250);
const p0 = await page.evaluate(() => ({ ...window.__fly.status, c: window.__map.getCenter().toArray() }));
await page.waitForTimeout(700);
const p1 = await page.evaluate(() => ({ ...window.__fly.status, c: window.__map.getCenter().toArray() }));
tcheck(p0.state === 'paused' && p1.d === p0.d && p1.c[0] === p0.c[0] && p1.c[1] === p0.c[1] && await page.locator('#flyResume').isVisible(),
  `flyover: Pause holds the camera (${JSON.stringify({ p0: p0.d, p1: p1.d })})`);
await page.click('#flyStop');
await page.waitForTimeout(300);
await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 30_000 });
const stopped = await page.evaluate(() => ({ state: window.__fly.status.state, pitch: window.__map.getPitch(), cur: window.__profileCursor }));
tcheck(stopped.state === 'idle' && stopped.pitch > 55 && stopped.cur == null && await page.locator('#flyStart').isVisible(),
  `flyover: Stop frames the route in 3D again (${JSON.stringify(stopped)})`);
// north up in 3D turns the view only – it stays tilted (owner 2026-11-06: "ich wollte in der 3D-Ansicht bleiben")
await page.evaluate(() => window.__map.jumpTo({ bearing: 70 }));
await page.click('#compass');
await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 30_000 });
const north3d = await page.evaluate(() => ({ bearing: window.__map.getBearing(), pitch: window.__map.getPitch(), terrain: window.__map.getTerrain()?.source ?? null }));
tcheck(Math.abs(north3d.bearing) < 1 && north3d.pitch > 55 && north3d.terrain === 'terrain', `3D: north up keeps the tilt ${JSON.stringify(north3d)}`);
await page.click('#flyStart'); // flying when 3D is switched off below → it stops
await page.waitForTimeout(400);
await page.click('#terrain3d');
await page.waitForTimeout(300);
const off3d = await page.evaluate(() => ({ terrain: window.__map.getTerrain(), pitch: window.__map.getPitch(), max: window.__map.getMaxPitch(),
  note: !document.getElementById('threeDNote').hidden }));
tcheck(!off3d.terrain && off3d.pitch === 0 && off3d.max === 0 && !off3d.note, `3D off: flat again ${JSON.stringify(off3d)}`);
tcheck(await page.evaluate(() => window.__fly.status.state) === 'idle' && await page.locator('#flyBar').isHidden(), 'flyover: leaving 3D stops it and hides its buttons');
if (terrainFails.length) errors.push('terrain view: ' + terrainFails.join('; '));
// colours (owner 2026-10-29, colour rule 2026-11-06): see below; text keeps WCAG AA (4.5:1; icons 3:1) on its background in light and dark
const greens = () => page.evaluate(() => {
  const rgb = (c) => (c.match(/[\d.]+/g) ?? []).map(Number);
  const lum = ([r, g, b]) => { const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
  const bgOf = (el) => { for (let e = el; e; e = e.parentElement) { const c = rgb(getComputedStyle(e).backgroundColor); if (c.length >= 3 && (c[3] ?? 1) > 0.9) return c; } return [255, 255, 255]; };
  const out = [];
  for (const [sel, min] of [['.product', 4.5], ['.textbtn', 4.5], ['button.primary', 4.5], ['.badge', 4.5], ['.chip .ic', 3], ['#menuPlans .ic', 3]]) {
    const el = [...document.querySelectorAll(sel)].find((e) => e.getClientRects().length);
    if (!el) continue;
    const fg = rgb(getComputedStyle(el).color), bg = bgOf(el);
    const [a, b] = [lum(fg), lum(bg)];
    out.push({ sel, min, color: getComputedStyle(el).color, fill: getComputedStyle(el).backgroundColor, ratio: (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05) });
  }
  return out;
});
const contrastFails = [];
const light = await greens();
// the app's colour rule (b873458a, owner 2026-11-06): the brand's word stays the navigation green, plain buttons and icons
// are walnut (the theme's primary)
const appGreen = 'rgb(36, 95, 70)', walnut = 'rgb(74, 63, 55)';
for (const [sel, want] of [['.product', appGreen], ['#menuPlans .ic', walnut], ['.chip .ic', walnut]]) {
  const g = light.find((x) => x.sel === sel);
  if (g && g.color !== want) contrastFails.push(`${sel} is ${g.color}, not ${want}`);
}
const prim = light.find((x) => x.sel === 'button.primary');
if (prim && prim.fill !== walnut) contrastFails.push(`button.primary fill ${prim.fill}, not walnut`);
for (const x of light) if (x.ratio < x.min) contrastFails.push(`${x.sel} ${x.color} contrast ${x.ratio.toFixed(2)} < ${x.min}`);
// always light like the app (owner 2026-10-30): a dark system setting changes nothing
await page.emulateMedia({ colorScheme: 'dark' });
const dark = await greens();
const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
if (bg !== 'rgb(245, 243, 235)') contrastFails.push(`dark system setting: page background ${bg}, not the light paper`);
for (const x of dark) { const l = light.find((y) => y.sel === x.sel); if (l && l.color !== x.color) contrastFails.push(`dark system setting changed ${x.sel}: ${x.color}`); }
console.log('greens:', light.map((x) => `${x.sel} ${x.ratio.toFixed(1)}`).join(' · '));
if (contrastFails.length) errors.push('one green / contrast: ' + contrastFails.join('; '));
await browser.close(); server.close();
console.log('summary:', summary.replace(/\s+/g, ' ').trim());
console.log('status:', status);
if (errors.length) console.log('errors:', errors);
// Fail on page errors and console errors, except missing map files (404, no map data in CI) and the app's own
// Problems log at INFO / WARN (the known reflection warning, docs/design/web-planner.md §7 finding 2).
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) console.log('FAILED on:', real);
process.exit(real.length ? 1 : 0);
