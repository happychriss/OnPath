// Clicks two points on the planner page in headless Chromium and checks that a route + profile appear.
// Usage: node test/ui-smoke.mjs [segmentsDir] [mapDataDir] [screenshot.png]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const mapDir = process.argv[3] ? path.resolve(process.argv[3]) : null;
const shot = process.argv[4] ?? path.join(here, '../../engine/build/planner.png');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, mapDir });
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
await page.waitForTimeout(300);
await clickAt(11.1900, 46.6900);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
await page.waitForTimeout(1500);
const summary = await page.textContent('#summary');
const status = await page.textContent('#status');
await page.screenshot({ path: shot });
await browser.close(); server.close();
console.log('summary:', summary.replace(/\s+/g, ' ').trim());
console.log('status:', status);
if (errors.length) console.log('errors:', errors);
// Fail on page errors and console errors, except missing map files (404, no map data in CI) and the app's own
// Problems log at INFO / WARN (the known reflection warning, docs/design/web-planner.md §7 finding 2).
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN) /.test(e));
if (real.length) console.log('FAILED on:', real);
process.exit(real.length ? 1 : 0);
