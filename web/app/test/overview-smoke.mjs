// The world overview below the cells (docs/design/web-planner.md §16): with the overview files deployed next to the
// page (build with ONPATH_OVERVIEW_DIR), zoom 5 over Europe and zoom 8 at Meran draw the overview (never blank), and at
// zoom 12 in a cell the detailed map draws with the cover over the overview's continuation. Skipped (exit 0) when
// the build has no overview files. Region packages (z7–9, map-cells.md §20): a fake package (fake-package.mjs, solid
// magenta relief) north of the Alps is served like the R2 bucket; at zoom 8 there its relief covers the overview and
// the overview's labels are left out, while Meran (no package) keeps the overview.
// Usage: node test/overview-smoke.mjs [segmentsDir] [mapStoreDir] [screenshotDir]
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { writeFakePackage } from './fake-package.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const dist = path.resolve(here, '../dist');
if (!fs.existsSync(path.join(dist, 'overview/base.pmtiles'))) { console.log('skipped: this build has no overview files (ONPATH_OVERVIEW_DIR)'); process.exit(0); }
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = process.argv[3] ? path.resolve(process.argv[3]) : null;
const shots = process.argv[4] ?? path.join(here, '../../engine/build');
// the store the page reads at /data/: the fake package (z7 tile 68/44: Munich, Augsburg) + the real cells if given
const store = fs.mkdtempSync(path.join(os.tmpdir(), 'onpath-store-'));
const PKG = [68, 44];
const box = writeFakePackage(store, ...PKG);
if (dataDir) fs.symlinkSync(path.join(dataDir, 'cells/f2/10'), path.join(store, 'cells/f2/10'));
const { server, url } = await serve({ dist, segments, dataDir: store });
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
/** RGB of the map at [dx, dy] px from its centre (from a screenshot, so what the user sees). */
const pixel = async (dx = 120, dy = 60) => {
  const r = await page.locator('#map').boundingBox();
  const shot = await page.screenshot({ clip: { x: Math.round(r.x + r.width / 2 + dx), y: Math.round(r.y + r.height / 2 + dy), width: 1, height: 1 } });
  return page.evaluate(async (b64) => {
    const img = new Image(); img.src = 'data:image/png;base64,' + b64; await img.decode();
    const c = new OffscreenCanvas(1, 1); const g = c.getContext('2d'); g.drawImage(img, 0, 0);
    return [...g.getImageData(0, 0, 1, 1).data.slice(0, 3)];
  }, shot.toString('base64'));
};
const magenta = ([r, g, b]) => r - g > 50 && b - g > 40; // the relief paint (contrast, brightness) softens it
const meranPx = await pixel();
check(!magenta(meranPx), `zoom 8 at Meran (no region package): no package relief (pixel ${meranPx})`);
const meranLabels = await page.evaluate(() => window.__map.queryRenderedFeatures().filter((f) => f.layer.id.endsWith('~outside')).length);
check(meranLabels > 0, `zoom 8 at Meran: the overview's labels outside the packages draw (${meranLabels})`);

// region packages (z7–9): the style merged for packages, the fake package found, drawn, overview labels left out there
check(ids.includes('region-relief') && ids.some((i) => i.endsWith('~outside')), "the style is the one merged for region packages (Overview.merge packages): region-relief, labels '~outside'");
await page.evaluate(([w, s, e, n]) => window.__map.jumpTo({ center: [(w + e) / 2, (s + n) / 2], zoom: 8 }), box);
await idle(); await idle();
const pkgPx = await pixel();
check(magenta(pkgPx), `zoom 8 in the region package ${PKG.join('/')}: its relief covers the overview (pixel ${pkgPx})`);
const geo = await page.evaluate(() => JSON.stringify(window.__map.getFilter(window.__map.getStyle().layers.find((l) => l.id.endsWith('~outside')).id)));
check(geo.includes(String(box[0])) && !geo.includes('-150'), "the overview labels' within filter holds the package's box (not the placeholder)");
const inside = await page.evaluate(([w, s, e, n]) => window.__map.queryRenderedFeatures().filter((f) => f.layer.id.endsWith('~outside') &&
  f.geometry.type === 'Point' && f.geometry.coordinates[0] > w && f.geometry.coordinates[0] < e && f.geometry.coordinates[1] > s && f.geometry.coordinates[1] < n).length, box);
check(inside === 0, `no overview label inside the package (${inside})`);
await page.screenshot({ path: path.join(shots, 'overview-package-z8.png') });
if (dataDir) {
  await page.evaluate(() => window.__map.jumpTo({ center: [11.16, 46.67], zoom: 12.5 }));
  await idle(); await idle();
  const base = await features('base');
  const cover = await page.evaluate(() => window.__map.getSource('detail-cover').serialize().data.features.length);
  check(base > 0 && cover > 0, `zoom 12.5 in a cell: the detailed map (${base} features), cover over ${cover} cells`);
  await page.screenshot({ path: path.join(shots, 'overview-z12.png') });
}
await browser.close(); server.close();
fs.rmSync(store, { recursive: true, force: true });
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real.slice(0, 5)); fails.push('page errors'); }
console.log(fails.length ? 'FAILED' : 'all ok');
process.exit(fails.length ? 1 : 0);
