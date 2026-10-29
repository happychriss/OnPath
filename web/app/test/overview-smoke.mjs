// The world overview below the cells (docs/design/web-planner.md §16): with the overview files deployed next to the
// page (build with ONPATH_OVERVIEW_DIR), zoom 5 over Europe and zoom 8 at Meran draw the overview (never blank), and at
// zoom 12 in a cell the detailed map draws with the cover over the overview's continuation. Skipped (exit 0) when
// the build has no overview files. Usage: node test/overview-smoke.mjs [segmentsDir] [mapStoreDir] [screenshotDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const dist = path.resolve(here, '../dist');
if (!fs.existsSync(path.join(dist, 'overview/base.pmtiles'))) { console.log('skipped: this build has no overview files (ONPATH_OVERVIEW_DIR)'); process.exit(0); }
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = process.argv[3] ? path.resolve(process.argv[3]) : null;
const shots = process.argv[4] ?? path.join(here, '../../engine/build');
const { server, url } = await serve({ dist, segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const features = (source) => page.evaluate((src) => window.__map.queryRenderedFeatures().filter((f) => f.source === src).length, source);
const idle = () => page.evaluate(() => new Promise((r) => { const m = window.__map; if (m.loaded() && !m.isMoving()) setTimeout(r, 300); m.once('idle', () => setTimeout(r, 300)); setTimeout(r, 20_000); }));

await page.goto(url + 'index.html?at=10.5,48.5,5');
await page.waitForFunction(() => window.__map?.isStyleLoaded(), null, { timeout: 60_000 });
await idle();
const ids = await page.evaluate(() => window.__map.getStyle().layers.map((l) => l.id));
check(ids.some((i) => i.endsWith('~overview')) && ids.includes('detail-cover') && ids.includes('hillshade~overview'), 'the style has the app\'s overview layers (Overview.merge)');
const z5 = await features('overview');
check(z5 > 20, `zoom 5 over Europe: ${z5} overview features drawn (coast, borders, names)`);
await page.screenshot({ path: path.join(shots, 'overview-z5.png') });
await page.evaluate(() => window.__map.jumpTo({ center: [11.16, 46.67], zoom: 8 }));
await idle();
const z8 = await features('overview');
check(z8 > 0, `zoom 8 at Meran: ${z8} overview features (no blank map below the cells)`);
await page.screenshot({ path: path.join(shots, 'overview-z8.png') });
if (dataDir) {
  await page.evaluate(() => window.__map.jumpTo({ center: [11.16, 46.67], zoom: 12.5 }));
  await idle(); await idle();
  const base = await features('base');
  const cover = await page.evaluate(() => window.__map.getSource('detail-cover').serialize().data.features.length);
  check(base > 0 && cover > 0, `zoom 12.5 in a cell: the detailed map (${base} features), cover over ${cover} cells`);
  await page.screenshot({ path: path.join(shots, 'overview-z12.png') });
}
await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real.slice(0, 5)); fails.push('page errors'); }
console.log(fails.length ? 'FAILED' : 'all ok');
process.exit(fails.length ? 1 : 0);
