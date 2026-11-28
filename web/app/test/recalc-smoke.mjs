// Stitched plans (detour points, detour-stitch.md): a point put in on a routed long leg keeps its detour points through
// settling, reload and Save. „Ganz neu berechnen“ is gone from the plan menu without replacement (owner 2026-11-28 13:53).
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

await page.goto(url + 'index.html');
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
const code = await page.evaluate((pts) => window.__engine.encode({ points: pts, activity: 'BIKE_TOURING', sac: 0, pinned: [], names: {}, title: null }),
  [[50.9375, 6.9603], [51.9607, 7.6261]]);
await page.goto(url + 'index.html#p=' + code);
await idle();
await page.waitForTimeout(500);
// „Ganz neu berechnen“ is gone without replacement (owner 2026-11-28 13:53, measurement: 1 of 10 edit sequences 0.3 % longer, unseen)
const noRow = () => page.evaluate(() => !document.getElementById('menuRecalc')
  && !/Ganz neu berechnen|Recalculate from scratch/.test(document.getElementById('mainMenu')?.textContent ?? ''));
check(await noRow(), 'the plan menu has no "Ganz neu berechnen" row');
// a leg routed over 150 km gets support points (PlanAnchors.SUPPORT_LEG_M, owner 2026-11-27) – those are no detour points
const plainKm = await page.evaluate(() => window.__state.plan.distanceM / 1000);
check(plainKm > 150 || await page.evaluate(() => window.__engine.anchors) === '', `a plain plan has no detour points (${Math.round(plainKm)} km)`);

// a point put in on the routed line of the long leg: stitched from the taken run's line
const lp = await page.evaluate(() => window.__state.plan.legs[0].points);
const at = lp[Math.floor(lp.length / 4)];
await page.waitForTimeout(2500); // the map's fit after the first route
const xy = await page.evaluate(([la, lo]) => { const r = window.__map.getCanvas().getBoundingClientRect(); const q = window.__map.project([lo, la]); return [r.left + q.x, r.top + q.y]; }, [at[0] + 0.004, at[1]]);
await page.mouse.click(xy[0], xy[1]);
await page.waitForFunction(() => window.__state.points.length === 3, null, { timeout: 30_000 });
await idle();
await page.waitForTimeout(500);
check(await page.evaluate(() => window.__engine.anchors) !== '', 'the plan carries detour points');
check(await noRow(), 'a stitched plan: still no "Ganz neu berechnen" row');

// the idle settling (WebApi.settleStep – the app's PlanSettle, one leg a step): the taken run's legs routed on their own, the line shown again quietly
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

// the route saved (a folder of this browser): "Saved", the header's Save light
const saveHead = () => page.evaluate(() => document.getElementById('menuSavePlan').dataset.state);
const savedText = () => page.evaluate(() => document.getElementById('savedLine').textContent.trim());
const markedUnsaved = () => page.waitForFunction(() => document.getElementById('menuSavePlan').dataset.state === 'dark' && /changed, not saved/.test(document.getElementById('savedLine').textContent), null, { timeout: 30_000 }).then(() => true, () => false);
const settleSaveMark = (want) => page.waitForFunction((w) => document.getElementById('menuSavePlan').dataset.state === w, want, { timeout: 30_000 }).then(() => true, () => false);
await page.click('#menuSavePlan');
await page.waitForSelector('dialog.savename[open] #saveTitle', { timeout: 10_000 });
await page.click('#saveOk');
check(await settleSaveMark('done'), 'the route is saved: the header says Saved (' + await saveHead() + ')');

// no reload between the edits (review 2026-11-26 #1): after a run that snapped the new point, the engine's chain holds the
// points the page took (6 decimals) – the next point put in keeps the detour points there are (before the fix they were
// dropped or moved: the chain's points were the router's unrounded ones)
const putOnLeg = async (leg, n) => {
  await page.waitForFunction(() => !document.querySelector('.waitveil') && document.body.dataset.planning !== '1', null, { timeout: 240_000 });
  const pts = await page.evaluate((l) => window.__state.plan.legs[l].points, leg);
  const k = Math.floor(pts.length / 4), p = pts[k], u = pts[Math.max(0, k - 10)], v = pts[Math.min(pts.length - 1, k + 10)];
  // ~65 m off the line, across its way there: the router snaps it onto a way (BRouter's unrounded coordinates), and it is
  // no point on the line (set at once, never stitched)
  const dy = v[0] - u[0], dx = (v[1] - u[1]) * Math.cos(p[0] * Math.PI / 180), len = Math.hypot(dx, dy) || 1;
  const off = [p[0] + 0.0006 * dx / len, p[1] - 0.0006 * dy / len / Math.cos(p[0] * Math.PI / 180)];
  await page.evaluate(([la, lo]) => window.__map.jumpTo({ center: [lo, la], zoom: 16 }), off); // the spot in view, the line 40 px away
  await page.waitForTimeout(300);
  const at = await page.evaluate(([la, lo]) => { const r = window.__map.getCanvas().getBoundingClientRect(); const q = window.__map.project([lo, la]); return [r.left + q.x, r.top + q.y]; }, off);
  await page.mouse.click(at[0], at[1]);
  await page.waitForFunction((k) => window.__state.points.length === k, n, { timeout: 30_000 });
  await idle();
  await page.waitForTimeout(500);
};
const entries = (a) => (a ? JSON.parse(a).entries : []).map((e) => JSON.stringify([e.b, e.a]));
const n0 = await page.evaluate(() => window.__state.points.length);
await putOnLeg(await page.evaluate(() => window.__state.plan.legs.length - 1), n0 + 1);
const a1 = await page.evaluate(() => window.__engine.anchors);
console.log('points after the run: ' + await page.evaluate(() => JSON.stringify(window.__state.points)));
check(!!a1 && JSON.parse(a1).entries.some((e) => e.i === n0 - 1), 'a point put in on the last leg: stitched (' + a1 + ')');
await putOnLeg(0, n0 + 2);
const a2 = await page.evaluate(() => window.__engine.anchors);
// (the new point itself is not stitched on this data – the same before the review's fix; the engine test covers the stitch)
check(entries(a2).includes(entries(a1)[0]), 'no reload, the next point on the first leg: the detour points stay as they were (' + a2 + ')');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
