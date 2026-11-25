// "Ganz neu berechnen" / "Recalculate from scratch" (plan menu, picture B – owner 2026-11-25; the app's PlanEditState.recalcRow):
// a plain plan has no row; a point put in on a routed long leg (stitched: detour points, detour-stitch.md) shows it; a tap clears
// the detour points and routes the plan again (the row goes); Undo brings them back (the row too).
// The run's end feeds the app's PlanRecalcState.taken – without it there are no lines and no stitch (WebPlanChain.end).
// Usage: node test/recalc-smoke.mjs [segmentsDir]  (needs the NRW routing tile E5_N50: Köln → Münster, 150 km by bike)
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
const idle = () => page.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1', null, { timeout: 240_000 });
const rowShown = (want) => page.waitForFunction((w) => !document.getElementById('menuRecalc').hidden === w, want, { timeout: 30_000 }).then(() => true, () => false);

await page.goto(url + 'index.html');
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
const code = await page.evaluate((pts) => window.__engine.encode({ points: pts, activity: 'BIKE_TOURING', sac: 0, pinned: [], names: {}, title: null }),
  [[50.9375, 6.9603], [51.9607, 7.6261]]);
await page.goto(url + 'index.html#p=' + code);
await idle();
await page.waitForTimeout(500);
check(await page.isHidden('#menuRecalc'),
  'a plain plan: no "Ganz neu berechnen" row');
check(await page.evaluate(() => window.__engine.anchors) === '', 'a plain plan has no detour points');

// a point put in on the routed line of the long leg: stitched from the taken run's line
const lp = await page.evaluate(() => window.__state.plan.legs[0].points);
const at = lp[Math.floor(lp.length / 4)];
await page.waitForTimeout(2500); // the map's fit after the first route
const xy = await page.evaluate(([la, lo]) => { const r = window.__map.getCanvas().getBoundingClientRect(); const q = window.__map.project([lo, la]); return [r.left + q.x, r.top + q.y]; }, [at[0] + 0.004, at[1]]);
await page.mouse.click(xy[0], xy[1]);
await page.waitForFunction(() => window.__state.points.length === 3, null, { timeout: 30_000 });
await idle();
await page.waitForTimeout(500);
check(await rowShown(true), 'a point put in on a routed leg: the stitched plan shows the row');
check(await page.evaluate(() => window.__engine.anchors) !== '', 'the plan carries detour points');
const label = await page.textContent('#menuRecalc');
check(/Ganz neu berechnen|Recalculate from scratch/.test(label), `the row says "${label.trim()}" (the app's string)`);
check(await page.evaluate(() => document.querySelector('#newPlan').nextElementSibling.id) === 'menuRecalc', 'the row sits right after Neu planen');
let before = await page.evaluate(() => JSON.stringify(window.__state.points));

// the idle settling (WebApi.settleRun – the app's PlanSettle): the taken run's legs routed on their own, the line shown again quietly
const settleDone = (id) => page.waitForFunction((i) => /:(shown|done)$/.test(document.body.dataset.settle ?? '') && document.body.dataset.settle.startsWith(i ? '' : ''), id, { timeout: 600_000 });
await settleDone();
const settledAs = await page.evaluate(() => document.body.dataset.settle.split(':')[1]);
const line1 = await page.evaluate(() => window.__state.plan.distanceM + '/' + window.__state.plan.legs.map((l) => l.points.length).join(','));
console.log('settled (' + settledAs + '): ' + line1);
check(settledAs === 'shown' || settledAs === 'done', 'the plan with detour points was settled (' + settledAs + ')');
check(await page.evaluate(() => window.__engine.anchors) !== '', 'the settling left the detour points');

// a reload: the plan from the address, its own settled legs from the browser store: the same line
await page.reload();
await idle();
await page.waitForFunction(() => /:(shown|done)$/.test(document.body.dataset.settle ?? ''), null, { timeout: 600_000 }).catch(() => {});
const line2 = await page.evaluate(() => window.__state.plan.distanceM + '/' + window.__state.plan.legs.map((l) => l.points.length).join(','));
check(line2 === line1, 'reload: the same line (' + line2 + ')');
before = await page.evaluate(() => JSON.stringify(window.__state.points));

// tap: the detour points go, the plan is routed again
await page.click('#menuRecalc');
await page.waitForFunction(() => window.__engine.anchors === '', null, { timeout: 30_000 }).catch(() => {});
await idle();
await page.waitForTimeout(500);
check(await page.evaluate(() => window.__engine.anchors) === '', 'tap: the detour points are gone');
check(await rowShown(false), 'tap: the row goes');
check(await page.evaluate(() => JSON.stringify(window.__state.points)) === before, 'tap: the same visible points');
check(await page.isEnabled('#undo') || await page.isVisible('#undoPill'), 'tap is one undo step');
const cold = await page.evaluate(() => window.__state.plan.distanceM + '/' + window.__state.plan.legs.map((l) => l.points.length).join(','));
console.log('cold line: ' + cold);

// Undo: the stitched plan is back
await page.waitForFunction(() => !document.querySelector('.waitveil') && document.body.dataset.planning !== '1', null, { timeout: 240_000 });
await page.click('#undo');
check(await rowShown(true), 'Undo: the row is back');
check(await page.evaluate(() => window.__engine.anchors) !== '', 'Undo: the detour points are back');
await idle();
await page.waitForFunction(() => /:(shown|done)$/.test(document.body.dataset.settle ?? ''), null, { timeout: 600_000 }).catch(() => {});
const back = await page.evaluate(() => window.__state.plan.distanceM + '/' + window.__state.plan.legs.map((l) => l.points.length).join(','));
check(back === line1, 'Undo: the stitched, settled line is back (' + back + ')');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
