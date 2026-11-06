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
const small = await page.locator('#profile .profplot > svg').boundingBox();
await page.mouse.move(small.x + small.width * 0.5, small.y + small.height * 0.5);
await page.waitForTimeout(200);
const c1 = await cursorNow();
const label1 = await page.locator('#profile .proflabel').textContent();
tcheck(c1 && c1.at && c1.m > 0, `sheet profile: pointer → cursor on the route (${JSON.stringify(c1)})`);
tcheck(/\d+ m · .+ · -?\d+ %/.test(label1 ?? '') && await page.locator('#profile .proflabel').isVisible(), `sheet profile label "${label1}"`);
const dot = () => page.evaluate(async () => (await window.__map.getSource('profile-cursor').getData())?.type === 'Feature');
tcheck(await dot(), 'the dot on the map follows the profile');
await page.mouse.move(5, 400);
await page.waitForTimeout(200);
tcheck(await cursorNow() == null && !(await dot()), 'pointer off the profile: cursor and dot gone');
// ⤢ sits beside the heights, never on the drawing (owner review 2026-11-05); the total distance at the axis' right end
const bigBtn = await page.locator('#profileBig').boundingBox();
tcheck(bigBtn && (bigBtn.y + bigBtn.height <= small.y + 1 || bigBtn.x >= small.x + small.width), `⤢ clear of the profile drawing (${JSON.stringify(bigBtn)} vs ${JSON.stringify(small)})`);
const axis = await page.locator('#profile .axis span').allTextContents();
tcheck(axis.length === 2 && /km$/.test(axis[1]), `profile axis "0 … total km" (${JSON.stringify(axis)})`);
await page.click('#profileBig');
await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 30_000 });
await page.waitForTimeout(400);
tcheck(await page.locator('#bigProfile').isVisible(), 'big profile opens below the map');
const steep = await page.evaluate(async () => ({ vis: window.__map.getLayoutProperty('profile-steep', 'visibility'),
  n: (await window.__map.getSource('profile-steep').getData())?.features?.length ?? 0 }));
tcheck(steep.vis === 'visible' && steep.n > 0, `route coloured by steepness while open (${JSON.stringify(steep)})`);
// the app's steepness colours everywhere – green for flat, never the route's blue (owner review 2026-11-05)
const colours = await page.evaluate(async () => ({
  legend: [...document.querySelectorAll('#bigProfile .bplegend i')].map((i) => getComputedStyle(i).backgroundColor),
  line: [...new Set((await window.__map.getSource('profile-steep').getData()).features.map((f) => f.properties.colour))],
  fills: [...new Set([...document.querySelectorAll('#bigProfile .profplot > svg path')].map((p) => p.getAttribute('fill')))] }));
const APP = ['#7fb77e', '#e3b23c', '#e07b39', '#c0392b'];
tcheck(JSON.stringify(colours.legend) === JSON.stringify(['rgb(127, 183, 126)', 'rgb(227, 178, 60)', 'rgb(224, 123, 57)', 'rgb(192, 57, 43)'])
  && colours.line.every((c) => APP.includes(c)) && colours.fills.every((c) => APP.includes(c)), `steepness colours = the app's (${JSON.stringify(colours)})`);
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
await page.click('#bigProfile .bpclose');
await page.waitForTimeout(200);
tcheck(!(await page.locator('#bigProfile').isVisible()) &&
  await page.evaluate(() => window.__map.getLayoutProperty('profile-steep', 'visibility')) === 'none', 'big profile closes, plain route again');
// ---- C – 3D: terrain on demand, tilted, view only; off = flat again ----
tcheck(!(await page.locator('#terrain3d').isDisabled()), '3D button enabled (the cells\' terrain source is there)');
await page.click('#terrain3d');
await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 30_000 });
const on3d = await page.evaluate(() => ({ terrain: window.__map.getTerrain()?.source ?? null, pitch: window.__map.getPitch(),
  pressed: document.getElementById('terrain3d').getAttribute('aria-pressed'), note: !document.getElementById('threeDNote').hidden }));
tcheck(on3d.terrain === 'terrain' && on3d.pitch > 55 && on3d.pressed === 'true' && on3d.note, `3D on: ${JSON.stringify(on3d)}`);
const nBefore = await page.evaluate(() => window.__state.points.length);
const mapBox = await page.locator('#map').boundingBox();
await page.mouse.click(mapBox.x + mapBox.width * 0.75, mapBox.y + mapBox.height * 0.5);
await page.waitForTimeout(500);
tcheck(await page.evaluate(() => window.__state.points.length) === nBefore && await page.locator('.pointmenu').count() === 0, '3D is view only: a click adds no point');
await page.click('#terrain3d');
await page.waitForTimeout(300);
const off3d = await page.evaluate(() => ({ terrain: window.__map.getTerrain(), pitch: window.__map.getPitch(), max: window.__map.getMaxPitch(),
  note: !document.getElementById('threeDNote').hidden }));
tcheck(!off3d.terrain && off3d.pitch === 0 && off3d.max === 0 && !off3d.note, `3D off: flat again ${JSON.stringify(off3d)}`);
if (terrainFails.length) errors.push('terrain view: ' + terrainFails.join('; '));
// one green (owner 2026-10-29): header, buttons and icons use the app's green (OnPathColors.Green, #245F46 since 0.13),
// and green text keeps WCAG AA (4.5:1; icons 3:1) on its background in light and dark
const greens = () => page.evaluate(() => {
  const rgb = (c) => (c.match(/[\d.]+/g) ?? []).map(Number);
  const lum = ([r, g, b]) => { const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
  const bgOf = (el) => { for (let e = el; e; e = e.parentElement) { const c = rgb(getComputedStyle(e).backgroundColor); if (c.length >= 3 && (c[3] ?? 1) > 0.9) return c; } return [255, 255, 255]; };
  const out = [];
  for (const [sel, min] of [['.product', 4.5], ['.textbtn', 4.5], ['button.primary', 4.5], ['.badge', 4.5], ['.chip .ic', 3], ['.nav button .ic', 3]]) {
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
const appGreen = 'rgb(36, 95, 70)';
for (const sel of ['.product', '.nav button .ic', '.chip .ic']) {
  const g = light.find((x) => x.sel === sel);
  if (g && g.color !== appGreen) contrastFails.push(`${sel} is ${g.color}, not the app's green`);
}
const prim = light.find((x) => x.sel === 'button.primary');
if (prim && prim.fill !== appGreen) contrastFails.push(`button.primary fill ${prim.fill}, not the app's green`);
for (const x of light) if (x.ratio < x.min) contrastFails.push(`${x.sel} ${x.color} contrast ${x.ratio.toFixed(2)} < ${x.min}`);
// always light like the app (owner 2026-10-30): a dark system setting changes nothing
await page.emulateMedia({ colorScheme: 'dark' });
const dark = await greens();
const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
if (bg !== 'rgb(255, 255, 255)') contrastFails.push(`dark system setting: page background ${bg}, not light`);
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
