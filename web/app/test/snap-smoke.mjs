// The plan points after routing = the app's (routing/PlanResult, docs/handoff-web-planresult.md): a point clicked in a
// meadow near Algund moves onto the path the route uses (owner: no off-road guidance) – no undo step, the address
// holds the moved point; planning the moved points again moves nothing (stable); undo / redo show the moved points
// (PlanHistory.replacePoints); a named + pinned stop (a POI) keeps its exact place.
// Usage: node test/snap-smoke.mjs [segmentsDir]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const metres = (a, b) => {
  const k = Math.PI / 180, x = (b[1] - a[1]) * k * Math.cos(((a[0] + b[0]) / 2) * k), y = (b[0] - a[0]) * k;
  return Math.sqrt(x * x + y * y) * 6371000;
};
/** The plan in the address (the app's PlanCode, read by the engine). */
const planOf = () => page.evaluate(async () => (location.hash.startsWith('#p=') ? window.__engine.parse(location.hash.slice(1)) : null));
const idle = () => page.waitForFunction(() => document.querySelector('#summary .big') && !document.getElementById('sheet').classList.contains('busy'), null, { timeout: 120_000 });
const settled = async () => { // the plan is done and the address no longer changes
  await idle();
  let h = '';
  for (let i = 0; i < 20; i++) { await page.waitForTimeout(250); const n = await page.evaluate(() => location.hash); if (n === h) break; h = n; }
  return h;
};

// a plan from a link: Algund → a meadow → towards Vellau, Hike (SnapTest.kt's case)
const clicked = [[46.6830, 11.1300], [46.6840, 11.1000], [46.6860, 11.1090]];
await page.goto(url + 'index.html');
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 60_000 });
const code = await page.evaluate((pts) => window.__engine.encode({ points: pts, activity: 'HIKE', sac: 3, pinned: [], names: {}, title: null }), clicked);
await page.goto(url + 'index.html#p=' + code);
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready') || document.querySelector('#summary .big'), null, { timeout: 60_000 });
const h1 = await settled();
const p1 = await planOf();
const moved = p1.points.map((q, i) => metres(q, clicked[i]));
check(h1 !== '#p=' + code && moved.some((m) => m > 3), `the meadow points moved onto the path (${moved.map((m) => m.toFixed(0)).join(' / ')} m) – the address holds them`);
// snapping is no undo step: one undo goes back to before the link (no points), never to the clicked meadow points
await page.click('#undo');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 0, null, { timeout: 10_000 }).catch(() => {});
check(await page.evaluate(() => document.querySelectorAll('.pin').length) === 0, 'snapping is no undo step (one undo: back to before the link)');
await page.click('#redo');
await page.waitForFunction((h) => location.hash === h, h1, { timeout: 15_000 }).catch(() => {});
check(await page.evaluate(() => location.hash) === h1, 'redo: the moved points again (the snapshot was moved too)');
await settled();

// planned again (another T level and back): the moved points stay where they are
await page.click('#activityChip'); await page.click('#activityMenu [data-sac="4"]'); await settled();
await page.click('#activityChip'); await page.click('#activityMenu [data-sac="3"]');
const h2 = await settled();
const p2 = await planOf();
const again = Math.max(...p2.points.map((q, i) => metres(q, p1.points[i])));
check(again < 1.5, `planned again: the moved points stay (${again.toFixed(1)} m)`);

// an edit + undo / redo: the snapshots carry the moved points (PlanHistory.replacePoints)
await page.click('#reverse'); await settled();
await page.click('#undo');
await page.waitForFunction((h) => location.hash === h, h2, { timeout: 15_000 }).catch(() => {});
check(await page.evaluate(() => location.hash) === h2, 'undo after an edit: the moved points, not the clicked ones');

// a named, pinned stop (a POI, as the place card adds it) keeps its exact place
const hut = [46.6995, 11.2430];
const code2 = await page.evaluate((pts) => window.__engine.encode({ points: pts, activity: 'HIKE', sac: 3, pinned: [1], names: { 1: 'Ifinger Hütte' }, title: null }),
  [[46.6870, 11.2350], hut, [46.7006, 11.2585]]);
await page.goto(url + 'index.html#p=' + code2);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 60_000 });
await settled();
const p3 = await planOf();
check(metres(p3.points[1], hut) < 1.5 && p3.names['1'] === 'Ifinger Hütte', `the pinned, named hut stays exactly (${metres(p3.points[1], hut).toFixed(1)} m)`);

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
