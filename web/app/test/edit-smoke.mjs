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
// the page's own pixel of a place: the map's canvas starts below the top bar
const px = (lon, lat) => page.evaluate(([lo, la]) => { const r = window.__map.getCanvas().getBoundingClientRect();
  const q = window.__map.project([lo, la]); return [r.left + q.x, r.top + q.y]; }, [lon, lat]);
const clickAt = async (lon, lat) => { const p = await px(lon, lat); await page.waitForFunction(() => !document.querySelector('.waitveil') && window.__map?.areTilesLoaded() !== false, null, { timeout: 60_000 }).catch(() => {}); await page.mouse.click(p[0], p[1]); };
const popups = () => page.evaluate(() => [...document.querySelectorAll('.maplibregl-popup')].map((x) => x.textContent.replace(/\s+/g, ' ').trim()));
await clickAt(11.1600, 46.6766);
await page.waitForSelector('.pointmenu'); // first click: New route – Start here · Go here
await page.click('.pointmenu button:first-of-type'); await page.waitForTimeout(300);
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 1);
// empty map (no POI symbol within reach) and a POI beside it
const c2 = [11.1265, 46.6880], c3 = [11.1425, 46.7000]; // c3: near equal both on straight lines and on the paths (the hold asks)
// the POI part needs the map cells' POIs (a cell store as 2nd argument / the server's data); CI's short run has only
// the routing data – then it is skipped, the back-to-back check below runs either way
const hasPois = await page.waitForFunction(() => window.__map.queryRenderedFeatures({ layers: window.__map.getStyle().layers.map((l) => l.id)
  .filter((id) => id.startsWith('poi-') && !id.endsWith('-name')) }).some((f) => f.properties.name === 'Ruster - Restaurant'), null, { timeout: 30_000 }).then(() => true, () => false);
const ruster = hasPois ? await page.evaluate(() => window.__map.queryRenderedFeatures({ layers: window.__map.getStyle().layers.map((l) => l.id)
  .filter((id) => id.startsWith('poi-') && !id.endsWith('-name')) }).find((f) => f.properties.name === 'Ruster - Restaurant').geometry.coordinates) : null;
if (!ruster) console.log('skip: no map-cell POIs here – the POI-then-map click check needs a cell store');
// clicks fired back to back are decided one after the other, each on what the one before left (2026-11-17): a click on
// the POI, then at once one on the empty map – as two slow clicks: the POI's menu opens, the next click closes it,
// nothing is added (it was: the 2nd click added a point while the POI's menu was still being built, which opened after)
if (ruster) {
  const a = await px(...ruster), b = await px(...c2);
  await page.waitForFunction(() => !document.querySelector('.waitveil') && window.__map?.areTilesLoaded() !== false, null, { timeout: 60_000 }).catch(() => {}); await page.mouse.click(a[0], a[1]); await page.mouse.click(b[0], b[1]);
  await page.waitForTimeout(2500);
  const pins = await page.evaluate(() => document.querySelectorAll('.pin').length), open = await popups();
  check(pins === 1 && open.length === 0, `a POI clicked, then the map at once: the POI's menu closed again, nothing added (${pins} points, menus ${JSON.stringify(open)})`);
}
// the 2nd and 3rd point back to back: the 3rd is decided on the plan with the 2nd in it – both detours near equal there
// (straight +1.9 / 1.8 km; on the paths +4.7 / 4.1 km, whichever the engine can measure yet), so
// the hold menu asks (the app's hold rule, F-PLAN-15) – and it stays open: the 3rd press came after the first route's fit
// was asked, so the map is not moved away under it (the app's lastMapTouch)
{
  const a = await px(...c2), b = await px(...c3);
  await page.waitForFunction(() => !document.querySelector('.waitveil') && window.__map?.areTilesLoaded() !== false, null, { timeout: 60_000 }).catch(() => {}); await page.mouse.click(a[0], a[1]); await page.mouse.click(b[0], b[1]);
  await page.waitForSelector('.holdaddpopup button', { timeout: 15_000 });
  await page.waitForFunction(() => document.getElementById('summary')?.querySelector('.big') && !document.getElementById('status').textContent.includes('Planning'), null, { timeout: 120_000 });
  await page.waitForTimeout(2000); // the first route's fit would come 500 ms after the route
  const open = await popups();
  check(await page.evaluate(() => document.querySelectorAll('.pin').length) === 2 && open.length === 1 && /Set as destination/.test(open[0]),
    `2nd and 3rd click back to back: the hold menu asks with the 2nd point in the plan and stays open (${JSON.stringify(open)})`);
}
// its first row, „New destination“
const two = await summary();
await page.click('.holdaddpopup button:first-of-type');
await planned(two);
check(await page.evaluate(() => document.querySelectorAll('.pin').length) === 3, '„New destination“: 3 points');
const hike = await summary();
console.log('hike:', hike);
// the route shows whole (the first route's own fit was left out above: the user pressed the map after it was asked;
// the fit itself: parity-smoke)
await page.waitForTimeout(1500);
const inView = await page.evaluate(() => { const m = window.__map, b = m.getBounds();
  const pts = m.getSource('planned-route').serialize().data.features.flatMap((f) => f.geometry.coordinates);
  return { n: pts.length, out: pts.filter(([lo, la]) => !b.contains([lo, la])).length }; });
check(inView.n > 2 && inView.out === 0, `the route shows whole (${inView.n} line points, ${inView.out} outside the view)`);

// „Markierten Wegen folgen“ (the app's Settings → Planen, owner 2026-11-27): a row of three in the activity drop-down,
// Standard by default; Mehr reaches the engine (another routing fingerprint) and is kept; the list stays open
const fpBefore = await page.evaluate(() => window.__engine.call('routingInfo'));
await page.click('#activityChip');
await page.waitForSelector('#trailsRow [data-trails]', { timeout: 5_000 }).catch(() => {});
const trailsRow = await page.$$eval('#trailsRow [data-trails]', (b) => b.map((x) => `${x.textContent}${x.getAttribute('aria-checked') === 'true' ? '*' : ''}`));
check(trailsRow.length === 3 && /\*$/.test(trailsRow[1]), `the drop-down has „Markierten Wegen folgen“, Standard checked (${trailsRow.join(' · ')})`);
await page.click('#trailsRow [data-trails="2"]');
await page.waitForFunction((fp) => window.__engine.call('routingInfo').then((x) => x !== fp), fpBefore, { timeout: 10_000 }).catch(() => {});
const fpMore = await page.evaluate(() => window.__engine.call('routingInfo'));
check(fpMore !== fpBefore && !(await page.isHidden('#activityMenu')) && await page.evaluate(() => localStorage.getItem('onpath.markedTrails')) === '2',
  `Mehr reaches the engine (fingerprint ${fpBefore.slice(0, 16)} → ${fpMore.slice(0, 16)}), is kept, the list stays open`);
await page.click('#trailsRow [data-trails="1"]'); // back to the app's default for the rest of this smoke
await page.waitForFunction((fp) => window.__engine.call('routingInfo').then((x) => x === fp), fpBefore, { timeout: 10_000 }).catch(() => {});
await page.click('#activityChip'); // closes the drop-down
await planned(null).catch(() => {}); // a re-plan with Standard again (the same route as before)

// activity switch re-plans
await page.click('#activityChip');
await page.click('[data-act=BIKE_TOURING]');
check(await page.isHidden('#activityMenu'), 'picking an activity closes the drop-down (no Done)');
await planned(hike);
const bike = await summary();
console.log('bike:', bike);
check(bike !== hike, 'switching Hike → Bike plans again (summary changed)');
check((await page.textContent('#activityChip')).includes('Bike'), 'chip shows Bike');
// the app's "New route for … – check it" only after a change the user did not make (RouteWarnings.saysChanged, owner
// 2026-11-07): the user's own activity change in Plan says nothing – the planner never shows it
const changed = await page.evaluate(() => { const c = document.getElementById('changedLine'); return c.hidden ? null : c.textContent; });
check(changed === null, `after the user's own Hike → Bike: no "New route for …" line (${changed})`);

// round trip: loop shown once with ↺
await page.click('#roundTrip');
await planned(bike);
check((await page.textContent('#points')).includes('↺'), 'round trip: start shown with ↺ (PlanPoints rows)');
check(await page.evaluate(() => document.querySelectorAll('.pin').length) === 3, 'round trip: 3 markers (closing point not drawn twice)');
check(!(await page.isDisabled('#roundTrip')) && await page.evaluate(() => document.getElementById('roundTrip').classList.contains('on'))
  && await page.getAttribute('#roundTrip', 'aria-pressed') === 'true',
  'Round trip is a switch: on (filled, ✓) once it is one, still clickable (onpath.private#40)');

// point menu on point 2 → „Set as start“ (same round trip, starts here) rotates the loop
const loopBefore = await page.textContent('#points');
await page.locator('.pin').nth(1).click();
await page.waitForSelector('.pointmenu');
const items = await page.$$eval('.pointmenu button .txt > span:first-child', (b) => b.map((x) => x.textContent));
check(items.join('|') === 'Set as start|Delete', 'point menu of a loop point: Set as start (same round trip) · Delete');
await page.click('.pointmenu button:first-of-type');
await page.waitForFunction((b) => document.getElementById('points').textContent !== b, loopBefore, { timeout: 10_000 }).catch(() => {});
check((await page.textContent('#points')) !== loopBefore, 'Start & finish here rotated the loop');
// a stop on the way before the round trip (PlanEdits.loopStart = 1): the loop's ① reads "Start here" (drops the stop),
// its later points "Start & finish here" – the app's MapGestures.pointMenu(loopStart)
{
  const way = '46.670000,11.160000;46.680000,11.150000;46.690000,11.150000;46.690000,11.170000;46.680000,11.150000';
  const menu = (i) => page.evaluate(([i, w]) => window.__engine.call('pointMenu', i, 5, false, true, w, false), [i, way]).then(JSON.parse);
  const [m1, m2] = [await menu(1), await menu(2)];
  check(m1.join() === 'MAKE_START,DELETE' && m2.join() === 'LOOP_START,DELETE', `stop on the way: ① of the loop "Start here", ② "Start & finish here" (${m1} / ${m2})`);
}
await page.screenshot({ path: shot });

// way types & surfaces (the app's RouteBreakdown, rules from routing/BreakdownRules): one line, a click → two bars
// (wide: the panel shows everything, no steps – plan sheet A, owner 2026-11-07; phone: the open step)
for (let i = 0; i < 2 && await page.isVisible('#sheetStep') && await page.evaluate(() => document.getElementById('sheet').dataset.step) !== 'points'; i++) await page.click('#sheetStep');
const bd = await page.evaluate(() => { const b = document.getElementById('breakdown'); return { shown: !b.hidden, title: b.querySelector('.bdhead .muted')?.textContent, line: b.querySelector('.bdline')?.textContent ?? '' }; });
check(bd.shown && bd.title === 'Way types & surfaces' && /^[A-Z][\w ]+ \d+ % · [A-Z][\w ]+ \d+ %$/.test(bd.line), `"Way types & surfaces": "${bd.line}" (the app's summary line)`);
await page.click('#breakdown .bdhead');
const bars = await page.evaluate(() => [...document.querySelectorAll('#breakdown .bdbar')].map((b) => b.children.length));
check(bars.length === 2 && bars.every((n) => n >= 1) && await page.isVisible('#breakdown .bdlegend'), `a click shows the two bars with their legend (${bars})`);
await page.click('#breakdown .bdhead');

// the app's seven search chips (Lodging since 0.18.11) (SearchKinds.Category): under the search field when it is focused; a click picks, again unpicks
await page.click('#q');
await page.waitForSelector('#results .searchchips .chip', { timeout: 10_000 }).catch(() => {});
const chips = await page.$$eval('#results .searchchips .chip', (b) => b.map((x) => x.textContent));
check(chips.join('|') === 'Places|Food|Lodging|Sights|Water|Transit|Parking', `search chips as in the app: ${chips.join(' · ')}`);
await page.click('#results .chip[data-cat="PARKING"]');
await page.waitForFunction(() => document.querySelector('#results .chip[data-cat="PARKING"]')?.getAttribute('aria-pressed') === 'true', null, { timeout: 10_000 }).catch(() => {});
const on = await page.getAttribute('#results .chip[data-cat="PARKING"]', 'aria-pressed');
await page.click('#results .chip[data-cat="PARKING"]');
await page.waitForFunction(() => document.querySelector('#results .chip[data-cat="PARKING"]')?.getAttribute('aria-pressed') === 'false', null, { timeout: 10_000 }).catch(() => {});
check(on === 'true' && await page.getAttribute('#results .chip[data-cat="PARKING"]', 'aria-pressed') === 'false', 'a chip click picks "Parking", a second click unpicks it');
await page.keyboard.press('Escape');
await page.waitForFunction(() => !document.querySelector('.waitveil') && window.__map?.areTilesLoaded() !== false, null, { timeout: 60_000 }).catch(() => {}); await page.mouse.click(640, 500);

// Points list ✕ on the loop's start (the app's PlanPoints.deleteAt): the start goes, the loop closes at the next point –
// still a round trip with ↺, one marker less (the web used to drop start and end: the loop opened)
for (let i = 0; i < 2 && await page.isVisible('#sheetStep') && await page.evaluate(() => document.getElementById('sheet').dataset.step) !== 'points'; i++) await page.click('#sheetStep');
const pinsBefore = await page.evaluate(() => document.querySelectorAll('.pin').length);
const hashBefore = await page.evaluate(() => location.hash);
await page.click('#points li:first-child .del');
await page.waitForFunction((h) => location.hash !== h, hashBefore, { timeout: 10_000 }).catch(() => {});
await page.waitForTimeout(500);
const pinsAfter = await page.evaluate(() => document.querySelectorAll('.pin').length);
check(pinsAfter === pinsBefore - 1 && (await page.textContent('#points')).includes('↺') && await page.getAttribute('#roundTrip', 'aria-pressed') === 'true',
  `✕ on a round trip's start: the loop stays closed (${pinsBefore} → ${pinsAfter} markers, ↺ still there)`);
await page.click('#undo');
await page.waitForFunction((h) => location.hash === h, hashBefore, { timeout: 10_000 }).catch(() => {});
check(await page.evaluate(() => location.hash) === hashBefore, 'undo brings the deleted start back');

// the round trip switched off again (#40): the way back goes (A → B → C → A → A → B → C), the button is plain again
const loopN = await page.evaluate(() => document.querySelectorAll('.pin').length);
const hashLoop = await page.evaluate(() => location.hash);
await page.click('#roundTrip');
await page.waitForFunction((h) => location.hash !== h, hashLoop, { timeout: 10_000 }).catch(() => {});
await page.waitForFunction(() => document.getElementById('roundTrip').getAttribute('aria-pressed') === 'false', null, { timeout: 10_000 }).catch(() => {});
check(await page.getAttribute('#roundTrip', 'aria-pressed') === 'false' && !(await page.textContent('#points')).includes('↺')
  && await page.evaluate(() => document.querySelectorAll('.pin').length) === loopN && !(await page.isDisabled('#roundTrip')),
  'Round trip off: no ↺ any more, the same markers (the closing point went), the switch is off and can go on again');
// reverse greyed out when backwards is the same plan (the engine's flags, the app's PlanEdits.reverseChanges)
const flags = await page.evaluate(async () => {
  const e = window.__engine, a = [46.67, 11.16], b = [46.68, 11.17];
  const [same, diff] = [await e.rows([a, b, a], ''), await e.rows([a, b], '')];
  return { same: same.canReverse, diff: diff.canReverse, loop: same.canRoundTrip };
});
check(flags.same === false && flags.diff === true && flags.loop === true,
  `Reverse greyed out for A → B → A only, Round trip switchable (${JSON.stringify(flags)})`);

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
