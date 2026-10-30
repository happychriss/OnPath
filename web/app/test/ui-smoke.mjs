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
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 60_000 });
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
