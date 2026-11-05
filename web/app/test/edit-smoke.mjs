// Plan edits with the app's code (PlanEdits, PlanPoints) and the activity switch: two points → Bike → the plan is
// routed again with the Bike profile; Round trip → loop shown once with ↺; point menu "Start & finish here";
// Reverse. Usage: node test/edit-smoke.mjs [segmentsDir] [mapStoreDir] [screenshot.png]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = process.argv[3] ? path.resolve(process.argv[3]) : null;
const shot = process.argv[4] ?? path.join(here, '../../engine/build/edits.png');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const summary = async () => (await page.textContent('#summary')).replace(/\s+/g, ' ').trim();
const planned = async (before) => {
  await page.waitForFunction((b) => { const s = document.querySelector('#summary'); return s?.querySelector('.big') && s.textContent.replace(/\s+/g, ' ').trim() !== b && !document.getElementById('status').textContent.includes('Planning'); }, before, { timeout: 120_000 });
  await page.waitForTimeout(300);
};

await page.goto(url + 'index.html');
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
const clickAt = async (lon, lat) => {
  const p = await page.evaluate(([lo, la]) => { const q = window.__map.project([lo, la]); return [q.x, q.y]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1]);
};
await clickAt(11.1600, 46.6700);
await page.waitForSelector('.pointmenu'); // first click: New route – Start here · Go here
await page.click('.pointmenu button:first-of-type'); await page.waitForTimeout(300);
await clickAt(11.1350, 46.6840); await page.waitForTimeout(300);
await clickAt(11.1500, 46.6950);
await planned('');
const hike = await summary();
console.log('hike:', hike);
// the plan's first route is shown whole (the app's WholeRoute.firstRoute / fitFirstRoute, after a short settle)
await page.waitForTimeout(1500);
const inView = await page.evaluate(() => { const m = window.__map, b = m.getBounds();
  const pts = m.getSource('planned-route').serialize().data.features.flatMap((f) => f.geometry.coordinates);
  return { n: pts.length, out: pts.filter(([lo, la]) => !b.contains([lo, la])).length }; });
check(inView.n > 2 && inView.out === 0, `the first route is fitted whole (${inView.n} line points, ${inView.out} outside the view)`);

// activity switch re-plans
await page.click('#activityChip');
await page.click('[data-act=BIKE_TOURING]');
check(await page.isHidden('#activityMenu'), 'picking an activity closes the drop-down (no Done)');
await planned(hike);
const bike = await summary();
console.log('bike:', bike);
check(bike !== hike, 'switching Hike → Bike plans again (summary changed)');
check((await page.textContent('#activityChip')).includes('Bike'), 'chip shows Bike');
// the app's "New route for Bike – check it" (RouteWarnings.changedNoticeably: 10 % length / ascent or 10 % of the line
// moved > 50 m) – a grey line; a click: it goes and the whole route shows
const changed = await page.evaluate(() => { const c = document.getElementById('changedLine'); return c.hidden ? null : c.textContent; });
console.log('changed line:', changed);
if (changed !== null) {
  check(/^New route for Bike – check it$/.test(changed), `after Hike → Bike: "${changed}" (the app's plan_route_changed)`);
  await page.click('#changedLine');
  check(await page.isHidden('#changedLine'), 'a click on the line: it goes (the map shows the whole route)');
}

// round trip: loop shown once with ↺
await page.click('#roundTrip');
await planned(bike);
check((await page.textContent('#points')).includes('↺'), 'round trip: start shown with ↺ (PlanPoints rows)');
check(await page.evaluate(() => document.querySelectorAll('.pin').length) === 3, 'round trip: 3 markers (closing point not drawn twice)');
check(await page.isDisabled('#roundTrip'), 'Round trip greyed out once it is one');

// point menu on point 2 → "Start & finish here" rotates the loop
const loopBefore = await page.textContent('#points');
await page.locator('.pin').nth(1).click();
await page.waitForSelector('.pointmenu');
const items = await page.$$eval('.pointmenu button .txt > span:first-child', (b) => b.map((x) => x.textContent));
check(items.join('|') === 'Start & finish here|Delete', 'point menu of a loop point: Start & finish here · Delete');
await page.click('.pointmenu button:first-of-type');
await page.waitForFunction((b) => document.getElementById('points').textContent !== b, loopBefore, { timeout: 10_000 }).catch(() => {});
check((await page.textContent('#points')) !== loopBefore, 'Start & finish here rotated the loop');
await page.screenshot({ path: shot });

// way types & surfaces (the app's RouteBreakdown, rules from routing/BreakdownRules): one line, a click → two bars
// (in the sheet's profile step – the Points step stays the list)
for (let i = 0; i < 3 && await page.evaluate(() => document.getElementById('sheet').dataset.step) !== 'profile'; i++) await page.click('#sheetStep');
const bd = await page.evaluate(() => { const b = document.getElementById('breakdown'); return { shown: !b.hidden, title: b.querySelector('.bdhead .muted')?.textContent, line: b.querySelector('.bdline')?.textContent ?? '' }; });
check(bd.shown && bd.title === 'Way types & surfaces' && /^[A-Z][\w ]+ \d+ % · [A-Z][\w ]+ \d+ %$/.test(bd.line), `"Way types & surfaces": "${bd.line}" (the app's summary line)`);
await page.click('#breakdown .bdhead');
const bars = await page.evaluate(() => [...document.querySelectorAll('#breakdown .bdbar')].map((b) => b.children.length));
check(bars.length === 2 && bars.every((n) => n >= 1) && await page.isVisible('#breakdown .bdlegend'), `a click shows the two bars with their legend (${bars})`);
await page.click('#breakdown .bdhead');

// the app's six search chips (SearchKinds.Category): under the search field when it is focused; a click picks, again unpicks
await page.click('#q');
await page.waitForSelector('#results .searchchips .chip', { timeout: 10_000 }).catch(() => {});
const chips = await page.$$eval('#results .searchchips .chip', (b) => b.map((x) => x.textContent));
check(chips.join('|') === 'Places|Food|Sights|Water|Transit|Parking', `search chips as in the app: ${chips.join(' · ')}`);
await page.click('#results .chip[data-cat="PARKING"]');
await page.waitForFunction(() => document.querySelector('#results .chip[data-cat="PARKING"]')?.getAttribute('aria-pressed') === 'true', null, { timeout: 10_000 }).catch(() => {});
const on = await page.getAttribute('#results .chip[data-cat="PARKING"]', 'aria-pressed');
await page.click('#results .chip[data-cat="PARKING"]');
await page.waitForFunction(() => document.querySelector('#results .chip[data-cat="PARKING"]')?.getAttribute('aria-pressed') === 'false', null, { timeout: 10_000 }).catch(() => {});
check(on === 'true' && await page.getAttribute('#results .chip[data-cat="PARKING"]', 'aria-pressed') === 'false', 'a chip click picks "Parking", a second click unpicks it');
await page.keyboard.press('Escape');
await page.mouse.click(640, 500);

// Points list ✕ on the loop's start (the app's PlanPoints.deleteAt): the start goes, the loop closes at the next point –
// still a round trip with ↺, one marker less (the web used to drop start and end: the loop opened)
for (let i = 0; i < 3 && await page.evaluate(() => document.getElementById('sheet').dataset.step) !== 'points'; i++) await page.click('#sheetStep');
const pinsBefore = await page.evaluate(() => document.querySelectorAll('.pin').length);
const hashBefore = await page.evaluate(() => location.hash);
await page.click('#points li:first-child .del');
await page.waitForFunction((h) => location.hash !== h, hashBefore, { timeout: 10_000 }).catch(() => {});
await page.waitForTimeout(500);
const pinsAfter = await page.evaluate(() => document.querySelectorAll('.pin').length);
check(pinsAfter === pinsBefore - 1 && (await page.textContent('#points')).includes('↺') && await page.isDisabled('#roundTrip'),
  `✕ on a round trip's start: the loop stays closed (${pinsBefore} → ${pinsAfter} markers, ↺ still there)`);
await page.click('#undo');
await page.waitForFunction((h) => location.hash === h, hashBefore, { timeout: 10_000 }).catch(() => {});
check(await page.evaluate(() => location.hash) === hashBefore, 'undo brings the deleted start back');

// reverse
const beforeRev = await page.evaluate(() => location.hash);
await page.click('#reverse');
await page.waitForFunction((b) => location.hash !== b, beforeRev, { timeout: 10_000 }).catch(() => {});
check(await page.evaluate(() => location.hash) !== beforeRev, 'Reverse changed the plan');

// the time limit (owner 2026-11-03, onpath.private#21 B, routing/SlowPlan): the engine's {slow: rounds} → the app's FRAGE
// box "This route is long and takes a while. Keep waiting?" – Keep waiting = the run again with one more period,
// Cancel = like Cancel of the wait. The engine's reply is faked once (a real time limit takes 15 s+).
await page.waitForFunction(() => document.body.dataset.planning !== '1', null, { timeout: 120_000 });
await page.evaluate(() => {
  const e = window.__engine, plan = e.plan.bind(e);
  window.__rounds = [];
  window.__slowOnce = true;
  e.plan = (pts, a, sac, pinned, prog, names, rounds) => {
    window.__rounds.push(rounds);
    if (window.__slowOnce) { window.__slowOnce = false; return Promise.resolve({ id: 0, ok: true, plan: { slow: rounds, legs: [] } }); }
    return plan(pts, a, sac, pinned, prog, names, rounds);
  };
});
const slowBox = () => page.evaluate(() => { const c = document.querySelector('.msgcard.frage'); return c ? c.textContent : ''; });
const beforeSlow = await page.evaluate(() => location.hash);
await page.click('#reverse');
await page.waitForFunction(() => /Keep waiting/.test(document.querySelector('.msgcard.frage')?.textContent ?? ''), null, { timeout: 30_000 }).catch(() => {});
const box1 = await slowBox();
check(/long and takes a while/.test(box1) && /Keep waiting\?/.test(box1), `time limit → the FRAGE box "Keep waiting?" ("${box1}")`);
await page.click('.msgcard.frage .msgbuttons button:last-child');
await page.waitForFunction(() => window.__rounds.length >= 2 && document.body.dataset.planning !== '1' && document.querySelector('#summary .big'),
  null, { timeout: 120_000 }).catch(() => {});
const r1 = await page.evaluate(() => window.__rounds);
check(r1[0] === 1 && r1[1] === 2 && !(await slowBox()), `Keep waiting → the run again with 2 periods, box gone (rounds ${JSON.stringify(r1)})`);
await page.evaluate(() => { window.__slowOnce = true; });
const afterWait = await page.evaluate(() => location.hash);
await page.click('#reverse');
await page.waitForFunction(() => !!document.querySelector('.msgcard.frage'), null, { timeout: 30_000 }).catch(() => {});
await page.click('.msgcard.frage .msgbuttons button:first-child');
await page.waitForTimeout(800);
const cancelled = await page.evaluate(() => ({ hash: location.hash, planning: document.body.dataset.planning, notRouted: window.__state.notRouted }));
check(!(await slowBox()) && cancelled.planning !== '1' && (cancelled.hash === afterWait || cancelled.notRouted),
  `Cancel → the box goes, the reverse is taken back or "not calculated" (${JSON.stringify({ ...cancelled, back: cancelled.hash === afterWait })})`);
check(beforeSlow !== afterWait, 'the reverse under the question was kept after Keep waiting');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
