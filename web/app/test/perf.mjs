// Where the time goes when the browser plans (docs/design/web-planner.md §11.2): page → engine ready, first plan
// (routing data download + storing it + BRouter), a second plan with one more point, a switch of activity, and the
// worker round trip of a small call. Prints one table; nothing is checked (numbers for the doc).
// Usage:
//   node test/perf.mjs normal [segmentsDir]          the normal build (web/app/dist), rd5 via HTTP range
//   node test/perf.mjs packed <previewFolder>        the preview build copied into a preview folder (base64 files)
// LOOK=<ms> waits that long after "ready" before the first plan (the user looks at the map; the page prefetches).
// NET=slow emulates a home line (50 Mbit/s down, 40 ms latency) instead of localhost.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const mode = process.argv[2] ?? 'normal';
const folder = path.resolve(mode === 'packed' ? process.argv[3] : path.join(here, '../dist'));
const segments = mode === 'packed' ? path.join(folder, 'segments') : path.resolve(process.argv[3] ?? '/home/user/build/routing-test/segments');
// Meran (normal test data) or Hilden (the preview's area)
const pts = mode === 'packed'
  ? { a: [[51.1690, 6.9300], [51.1600, 6.9500]], b: [[51.1690, 6.9300], [51.1750, 6.9450], [51.1600, 6.9500]] }
  : { a: [[46.6700, 11.1600], [46.6840, 11.1350]], b: [[46.6700, 11.1600], [46.6950, 11.1500], [46.6840, 11.1350]] };

const { server, url } = await serve({ dist: folder, segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
if (process.env.NET === 'slow') {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 40, downloadThroughput: 50e6 / 8, uploadThroughput: 10e6 / 8 });
}
const t0 = Date.now();
await page.goto(url + 'index.html');
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 300_000 });
const readyMs = Date.now() - t0;
const rows = [{ step: 'page → engine ready', wallMs: readyMs }];
if (process.env.LOOK) await page.waitForTimeout(Number(process.env.LOOK));
const plan = async (step, points, activity) => {
  const r = await page.evaluate(async ([p, a]) => {
    const s = performance.now();
    const x = await window.__engine.plan(p, a, 2);
    return { wall: performance.now() - s, stats: x.stats, ok: x.ok, km: x.plan?.distanceM, error: x.error ?? x.plan?.error };
  }, [points, activity]);
  rows.push({ step, wallMs: Math.round(r.wall), downloadMs: Math.round(r.stats?.fetchMs ?? 0),
    storeMs: Math.round(r.stats?.storeMs ?? 0), routeMs: Math.round(r.stats?.routeMs ?? 0),
    dataMB: +((r.stats?.bytesFetched ?? 0) / 1e6).toFixed(1), km: r.km ? +(r.km / 1000).toFixed(2) : null });
  if (r.error) console.log(step, "→", r.error);
};
await plan('plan 2 points (Hike)', pts.a, 'HIKE');
await plan('add a via point (Hike)', pts.b, 'HIKE');
await plan('same plan again (Hike)', pts.b, 'HIKE');
await plan('switch to Bike', pts.b, 'BIKE_TOURING');
const rt = await page.evaluate(async (p) => {
  const s = performance.now();
  for (let i = 0; i < 20; i++) await window.__engine.rows(p, '');
  return (performance.now() - s) / 20;
}, pts.b);
rows.push({ step: 'small call round trip (rows), avg', wallMs: +rt.toFixed(1) });
console.log(`${mode}${process.env.NET === 'slow' ? ' · 50 Mbit/s, 40 ms' : ' · localhost'}${process.env.LOOK ? ` · first plan ${process.env.LOOK} ms after ready` : ''}`);
console.table(rows);
await browser.close(); server.close();
