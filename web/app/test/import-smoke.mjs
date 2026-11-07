// The app's long-route question and GPX import in the browser (docs/design/web-planner.md §15):
// 1. a plan ~600 km long is asked about first ("This route is very long", the app's words) – no routing data is
//    fetched until it is answered; Cancel of a link's plan keeps the points unrouted ("Plan anyway"), Cancel of a
//    clicked point takes that point back (PlanStatus.onCancel);
//    "Far from your route" (web): an empty plan + a far search result as start is never asked; a single point left in
//    Algund + Hilden "As start" → the question names the point, "Start a new route here" (undo brings it back) /
//    "Add to the route" (= Plan anyway); "1 point · Algund" in the small step; "Your route is outside the map · Show";
//    Delete (Clear route) in the Points head + undo;
// 2. ☰ → Import GPX with a Komoot hike: the track panel, Activity set to Hike T2 from the file, "Plan a route from it"
//    (TrackToPlan) → a plan with few points along the track;
// 3. a .gpx dropped on the map (route + waypoints, <type>MTB</type>), Close track;
// 4. Export GPX ⇄ Import GPX keeps the sport and the line;
// 5. a point the route does not reach: the app's PlanGaps red line + Show.
// Needs only the rd5 (no map data, CI). Usage: node test/import-smoke.mjs [segmentsDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const fixture = (n) => path.join(here, 'fixtures', n);
const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (pinned, named), "Waalweg"
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, acceptDownloads: true });
await ctx.addInitScript(() => { if (!sessionStorage.getItem('seeded')) { localStorage.setItem('onpath.activity', 'BIKE_TOURING'); sessionStorage.setItem('seeded', '1'); } });
// Photon (online search) answers "Hilden" with one town – no network in the test
await ctx.route('https://photon.komoot.io/**', (r) => r.fulfill({ contentType: 'application/json', body: JSON.stringify({ type: 'FeatureCollection', features: [
  { type: 'Feature', geometry: { type: 'Point', coordinates: [6.9353, 51.1693] }, properties: { name: 'Hilden', osm_key: 'place', osm_value: 'town', type: 'city', country: 'Deutschland', state: 'Nordrhein-Westfalen' } }] }) }));
const page = await ctx.newPage();
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
let rd5Requests = 0;
page.on('request', (r) => { if (/\/segments\//.test(r.url()) && !/E10_N45/.test(r.url())) rd5Requests++; }); // the start view's own prefetch (E10_N45) does not count
const pins = () => page.evaluate(() => document.querySelectorAll('.pin').length);
const planned = () => page.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1' && !document.getElementById('status').textContent.includes('Planning'), null, { timeout: 120_000 });
// project() gives canvas pixels, the mouse clicks page pixels – the canvas starts below the header (the plan bar
// changed its height; robust-smoke had the same 56 px miss)
const clickAt = async (lon, lat) => {
  const p = await page.evaluate(([lo, la]) => { const r = window.__map.getCanvas().getBoundingClientRect();
    const q = window.__map.project([lo, la]); return [q.x + r.left, q.y + r.top]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1]);
};

await page.goto(url + 'index.html');
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });

// 1a. a link with two points ~600 km apart: asked first, nothing fetched meanwhile
const far = await page.evaluate(() => window.__engine.encode({ points: [[46.6700, 11.1600], [52.0500, 11.1600]], activity: 'HIKE', sac: 2, pinned: [], names: {}, title: null }));
await page.goto(url + 'index.html#p=' + far);
await page.waitForSelector('dialog.longroute', { timeout: 30_000 });
rd5Requests = 0; // only requests started while the question is open count (not the start view's prefetch)
const q = await page.textContent('dialog.longroute');
check(/This route is very long/.test(q) && /days on foot\. Calculating and loading its maps takes long\./.test(q), `long plan asked first: "${q.replace(/\s+/g, ' ').trim()}"`);
await page.waitForTimeout(1500);
check(rd5Requests === 0, `no routing data fetched while the question is open (${rd5Requests} requests)`);
await page.click('#lrCancel');
await page.waitForSelector('#planAnyway');
check((await page.textContent('#summary .subline')).includes('Route not calculated – very long plan.'), 'Cancel (a loaded plan): points kept, "Route not calculated – very long plan." + Plan anyway');
check(await pins() === 2, 'both points still there');
// 1b. Plan anyway: no second question, the planner runs (no routing data up north here: its plain error)
await page.click('#planAnyway');
await page.waitForFunction(() => !document.querySelector('dialog.longroute') && (/failed|could not|Planning/.test(document.getElementById('status').textContent) || !!document.querySelector('#summary .big')), null, { timeout: 120_000 });
check(!(await page.$('dialog.longroute')), 'Plan anyway: routed without asking again');
// 1b'. Clear route while that long plan may still run in the engine's one worker: the page answers at once (as the app
// cancels its plan) and the plan's late end brings no route back
const clearAt = Date.now();
await page.click('#newPlan'); if (await page.locator('dialog.discard[open]').count()) await page.click('#discardOk');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 0 && document.body.dataset.planning !== '1', null, { timeout: 5_000 }).catch(() => {});
const clearMs = Date.now() - clearAt;
check(await pins() === 0 && clearMs < 5_000, `Clear route during a long plan: the points go at once (${clearMs} ms)`);
await page.evaluate(() => window.__engine.call('historyState')); // the worker is free again: the long plan has ended
await page.waitForTimeout(300);
check(await pins() === 0 && !(await page.$('#summary .big')), 'the long plan\'s late result is dropped: no route comes back');

// 1c. a clicked point that makes a short plan very long: Cancel takes it back (PlanStatus.onCancel → UNDO)
await page.goto(url + 'index.html#p=' + CODE);
await planned();
check(await pins() === 2, 'short plan from a link: 2 points, routed, no question');
// the user takes the map first (a short drag), as a person does: it cancels the route's pending whole-route fit
// (WholeRoute, 500 ms after its plan) that could otherwise move the map between the projection and the click
{ const r = await page.locator('#map').boundingBox(), x = r.x + r.width * 0.6, y = r.y + r.height * 0.4;
  await page.mouse.move(x, y); await page.mouse.down(); await page.mouse.move(x + 40, y + 20, { steps: 5 }); await page.mouse.up(); }
await page.evaluate(() => window.__map.jumpTo({ center: [11.16, 51.2], zoom: 5.5, bearing: 0, pitch: 0, padding: { top: 0, bottom: 0, left: 0, right: 0 } }));
await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 10_000 }).catch(() => {});
await clickAt(11.16, 52.05);
await page.waitForSelector('dialog.longroute', { timeout: 30_000 });
await page.click('#lrCancel');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 2, null, { timeout: 60_000 }).catch(() => {});
await page.waitForTimeout(500); await planned().catch(() => {});
console.log('after cancel:', await pins(), await page.textContent('#summary'), await page.textContent('#status'));
check(await pins() === 2 && !!(await page.$('#summary .big')), 'Cancel (a clicked point): the point is taken back, the short route stays');

// 1d. "Far from your route" (owner 2026-10-30: "war in Meran, suche nach Hilden, gebe als Start" – a point left in
// Algund made Hilden's "As start" a 35 h plan). A fake Photon answer for "Hilden"; the map jumps there first.
const toHilden = async () => {
  await page.evaluate(() => window.__map.jumpTo({ center: [6.93, 51.17], zoom: 12 }));
  await page.waitForTimeout(800);
};
const hildenAs = async (button) => {
  await page.fill('#q', 'Hilden');
  await page.waitForSelector('#results .hit:has-text("Hilden")', { timeout: 30_000 });
  await page.click('#results .hit:has-text("Hilden")');
  await page.waitForSelector(`${button}:not([disabled])`);
  await page.click(button);
};
const summaryText = () => page.textContent('#summary .subline');
// the planner brings the last open route back when opened without #p= (review 2026-11-01, web H3): forget it for an empty plan
const emptyPlan = async () => { await page.evaluate(() => window.__sync.setDraft(null)); await page.goto(url + 'index.html'); };
// (a) an empty plan + a far search result as start: never the question
await emptyPlan();
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
await toHilden();
await hildenAs('#cadd'); // empty plan: the main button is "Set as start" (app 0.13), no small As start beside it
await page.waitForTimeout(1500);
check(!(await page.$('dialog.longroute')) && await pins() === 1 && (await summaryText()).includes('1 point · Hilden'),
  `empty plan + Hilden as start: no question, "1 point · Hilden" (${await summaryText()})`);
// (b) the owner's case: a single point "Algund" left from earlier, Hilden as start
const algund = await page.evaluate(() => window.__engine.encode({ points: [[46.6773, 11.1302]], activity: 'BIKE_TOURING', sac: 2, pinned: [0], names: { 0: 'Algund' }, title: null }));
await page.goto(url + 'index.html#p=' + algund);
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 1, null, { timeout: 60_000 });
// wide: no steps any more (plan sheet A) – the facts name the one point
// (the old step click here waited by accident; wait for the facts and the camera instead)
await page.waitForFunction(() => /1 point · Algund/.test(document.querySelector('#summary .subline')?.textContent ?? ''), null, { timeout: 30_000 }).catch(() => {});
await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 15_000 }).catch(() => {});
await page.waitForTimeout(600);
check((await summaryText()).includes('1 point · Algund') && await page.isVisible('#summary .onepoint'), 'one point: "1 point · Algund" in the facts');
check(!(await page.isVisible('#offmap')), 'the point in view: no off-map line');
await toHilden();
await page.waitForSelector('#offmap:not([hidden])', { timeout: 5000 }).catch(() => {});
check((await page.textContent('#offmap')).includes('Your route is outside the map') && await page.isVisible('#showPlan'), 'map far away: "Your route is outside the map · Show"');
await page.click('#showPlan');
await page.waitForFunction(() => window.__map.getBounds().contains([11.1302, 46.6773]) && !window.__map.isMoving(), null, { timeout: 15_000 }).catch(() => {});
check(await page.evaluate(() => window.__map.getBounds().contains([11.1302, 46.6773])) && !(await page.isVisible('#offmap')), 'Show: the plan in view, the line gone');
await toHilden();
await hildenAs('#cstart');
await page.waitForSelector('dialog.longroute.far', { timeout: 30_000 });
const farText = (await page.textContent('dialog.longroute')).replace(/\s+/g, ' ').trim();
const order = await page.$$eval('dialog.longroute button', (b) => b.map((x) => x.id));
check(/Far from your route/.test(farText) && /Your point Algund is about \d+ hours by bike from here\./.test(farText) &&
  order.join() === 'lrFresh,lrPlan,lrCancel' && !/very long/.test(farText), `a single far point: "${farText}" (${order.join(' · ')})`);
await page.locator('dialog.longroute').screenshot({ path: path.join(here, '../../engine/build/far-from-route.png') }).catch(() => {});
await page.click('#lrFresh');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 1, null, { timeout: 10_000 }).catch(() => {});
check(!(await page.$('dialog.longroute')) && await pins() === 1 && (await summaryText()).includes('1 point · Hilden'), 'Start a new route here: only Hilden');
await page.click('#undo');
await page.waitForFunction(() => document.getElementById('summary').textContent.includes('Algund'), null, { timeout: 10_000 }).catch(() => {});
check(await pins() === 1 && (await summaryText()).includes('1 point · Algund') && !(await page.$('dialog.longroute')), 'undo: the point in Algund is back, no question');
// (c) a route in Meran (2 points, Hike) + Hilden as start: the question names it; "Add to the route" = the old Plan anyway
await page.goto(url + 'index.html#p=' + CODE);
await planned();
await toHilden();
await hildenAs('#cstart');
await page.waitForSelector('dialog.longroute.far', { timeout: 30_000 });
const farRoute = (await page.textContent('dialog.longroute')).replace(/\s+/g, ' ').trim();
check(/Your route near Vellau is about \d+ (hours|days) on foot from here\./.test(farRoute), `a far route: "${farRoute}"`);
await page.click('#lrPlan');
await page.waitForFunction(() => !document.querySelector('dialog.longroute') && (/failed|Planning|Calculating/.test(document.getElementById('status').textContent) || !!document.querySelector('#summary .big')), null, { timeout: 120_000 }).catch(() => {});
check(!(await page.$('dialog.longroute')) && await pins() === 3, 'Add to the route: three points, planned without asking again');
// (d) + New in the plan bar (owner 2026-11-06: the one way – the plan row has no ✕ any more): no question any more (the
// app's Zuletzt, owner 2026-11-06) – the unsaved plan goes under Meine Touren → Recent; undo brings it back
await page.goto(url + 'index.html#p=' + CODE);
await planned();
check(await page.locator('#planActions #clear').count() === 0 && await page.isEnabled('#newPlan'), 'plan row without ✕ New plan; the plan bar\'s + New is active');
const recentN = () => page.evaluate(() => JSON.parse(localStorage.getItem('onpath.recent') ?? '[]').length);
await page.evaluate(() => localStorage.removeItem('onpath.recent')); // the same link plan went under Recent in 1. already (same points = one entry)
const recent0 = await recentN();
await page.click('#newPlan');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 0, null, { timeout: 10_000 }).catch(() => {});
check(await pins() === 0 && !(await page.$('dialog[open]')) && await recentN() === recent0 + 1, '+ New: no question, the route is gone and kept under Recent');
await page.click('#undo');
await planned().catch(() => {});
check(await pins() === 2 && !!(await page.$('#summary .big')), 'undo: the route is back');
// Meine Touren like the app (owner 2026-11-07): the plan on the map first under RECENT, tagged "In progress", its line
// on the preview tile, no ⋮ and no ⤢ (a tap goes back to it); the same plan under Recent is not shown twice
await page.click('#menuPlans');
await page.waitForSelector('dialog.plans[open] li[data-live]');
const liveRow = await page.evaluate(() => { const li = document.querySelector('dialog.plans li[data-live]');
  return { name: li.querySelector('.name').textContent, tag: li.querySelector('.tag')?.textContent, line: !!li.querySelector('.tile svg path'),
    more: !!li.querySelector('.more'), enlarge: !!li.querySelector('.enlarge'), drafts: document.querySelectorAll('dialog.plans li.draft').length }; });
check(liveRow.tag === 'In progress' && liveRow.line && !liveRow.more && !liveRow.enlarge && liveRow.drafts === 0,
  `My tours: the plan on the map first, "In progress", its line on the tile, no ⋮ / ⤢, not twice (${JSON.stringify(liveRow)})`);
await page.click('dialog.plans li[data-live] .open');
check(!(await page.$('dialog.plans[open]')) && await pins() === 2, 'a tap on the plan on the map closes My tours, the plan stays');
await page.click('#newPlan');
check(await recentN() === recent0 + 1, 'the same points again: no second Recent entry');
// Meine Touren: RECENT on top – the draft's line pale on its tile with ⤢, Plan · Save · Delete on the card, ⋮ Rename ·
// Export GPX (the app's RouteRowActions); a tap opens the large overview (shape, start, goal flag, Cancel · Plan)
await page.click('#menuPlans');
await page.waitForSelector('dialog.plans[open] li.draft');
const rec = await page.evaluate(() => ({ head: document.querySelector('dialog.plans li.sectionhead')?.textContent,
  pale: !!document.querySelector('dialog.plans li.draft .tile svg path[opacity]'), enlarge: !!document.querySelector('dialog.plans li.draft .enlarge'),
  btns: [...document.querySelectorAll('dialog.plans li.draft .rowacts button')].map((b) => b.textContent.trim()),
  menu: [...document.querySelectorAll('dialog.plans li.draft .rowmenu button')].map((b) => b.textContent.trim()) }));
check(rec.head === 'RECENT' && rec.pale && rec.enlarge && JSON.stringify(rec.btns) === '["Plan","Save","Delete"]' && JSON.stringify(rec.menu) === '["Rename","Export GPX"]',
  `My tours: RECENT, the draft's pale line with ⤢, Plan · Save · Delete, ⋮ Rename · Export GPX (${JSON.stringify(rec)})`);
await page.click('dialog.plans li.draft .open');
await page.waitForSelector('dialog.overview[open] .ovpic svg', { timeout: 10_000 });
const ov = await page.evaluate(() => ({ title: document.querySelector('dialog.overview .ovtitle').textContent, facts: document.querySelector('dialog.overview .ovfacts')?.textContent ?? '',
  line: !!document.querySelector('dialog.overview .ovpic svg path'), flag: !!document.querySelector('dialog.overview .ovpic svg g'),
  btns: [...document.querySelectorAll('dialog.overview .ovbuttons button')].map((b) => b.textContent.trim()), pins: document.querySelectorAll('.pin').length }));
check(ov.line && ov.flag && /km · ↑ \d+ m ↓ \d+ m/.test(ov.facts) && JSON.stringify(ov.btns) === '["Cancel","Plan"]' && ov.pins === 0,
  `a draft's tap: the large overview – shape, goal flag, facts, Cancel · Plan, nothing on the map yet (${JSON.stringify(ov)})`);
await page.click('dialog.overview .ovplan');
await planned().catch(() => {});
check(!(await page.$('dialog.overview[open]')) && await pins() === 2, 'overview → Plan: the plan is on the map');
// a new route draws itself in 0.5 s (the app's RouteReveal): Reverse → the line grows from start to goal, then whole
{
  await page.evaluate(() => { window.__reveal = true; });
  const counts = page.evaluate(() => new Promise((res) => {
    const out = [], t0 = performance.now();
    const n = () => { const d = window.__map.getSource('planned-route').serialize().data; return (d.features ?? []).reduce((a, f) => a + f.geometry.coordinates.length, 0); };
    const tick = () => { out.push(n()); if (performance.now() - t0 < 4000) requestAnimationFrame(tick); else res(out); };
    tick();
  }));
  await page.click('#reverse');
  const c = await counts;
  await page.evaluate(() => { window.__reveal = false; });
  const last = c[c.length - 1], partial = c.some((x) => x > 1 && x < last);
  check(last > 2 && partial, `Reverse: the new route draws itself (${c.length} frames, a partial line seen, ${last} points at the end)`);
  await planned().catch(() => {});
}
await page.click('#newPlan'); // back under Recent (the same entry)
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 0, null, { timeout: 10_000 }).catch(() => {});
await page.click('#menuPlans');
await page.waitForSelector('dialog.plans[open] li.draft');
const savedBefore = await page.evaluate(async () => (await window.__sync.list()).filter((p) => p.kind === 'plan').length);
await page.click('dialog.plans li.draft .r-save');
await page.waitForSelector('dialog.plans[open] .justsavedline', { timeout: 10_000 });
const savedAfter = await page.evaluate(async () => (await window.__sync.list()).filter((p) => p.kind === 'plan').length);
check(savedAfter === savedBefore + 1 && await recentN() === recent0 && (await page.textContent('dialog.plans .justsavedline')) === '✓ Just saved',
  `Recent → Save: a saved plan (${savedBefore} → ${savedAfter}), out of Recent, "✓ Just saved"`);
// the saved row: the clean list – its line with ⤢, ›, every action in ⋮ with Plan first (the app's RouteRowActions)
const sv = await page.evaluate(() => { const li = document.querySelector('dialog.plans li.tourrow.saved');
  return { line: !!li.querySelector('.tile svg path'), chev: !!li.querySelector('.chev'), acts: li.querySelectorAll('.rowacts button').length,
    menu: [...li.querySelectorAll('.rowmenu button')].map((b) => b.textContent.trim()) }; });
check(sv.line && sv.chev && sv.acts === 0 && JSON.stringify(sv.menu) === '["Plan","Rename","Save as copy","Export GPX","Share plan","Delete"]',
  `a saved row: line, ›, no buttons on the row, ⋮ Plan · Rename · Save as copy · Export GPX · Share plan · Delete (${JSON.stringify(sv)})`);
await page.click('#closePlans');

// 2. ☰ → Import GPX (Komoot hike) while the activity is Bike
await page.evaluate(() => { localStorage.setItem('onpath.activity', 'BIKE_TOURING'); });
await emptyPlan();
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
// the plan bar (owner 2026-11-06, A): wide Open → its Import GPX; phone width Plan ▾ → Import GPX
let importBtn = '#menuImportGpx';
if (await page.isVisible('#menuBtn')) await page.click('#menuBtn'); else { await page.click('#menuPlans'); importBtn = '#plansImportGpx'; }
check(await page.isVisible(importBtn) && (await page.textContent(importBtn)).includes('Import GPX'), 'Open (wide) / Plan ▾ (phone) has Import GPX');
const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.click(importBtn)]);
await chooser.setFiles(fixture('komoot-hike.gpx'));
await page.waitForSelector('#trackPanel', { timeout: 30_000 });
const tp = await page.textContent('#trackPanel');
console.log('track panel:', tp.replace(/\s+/g, ' ').trim());
check(tp.includes('Meran 2000 & Ifinger Hütte') && /2[.,]4 km/.test(tp) && tp.includes('1 h 09 min'), 'track panel: name, distance, the file\'s own moving time (main 6460dc98: stops left out)');
check(tp.includes('Activity set to Hike T2 from the file'), 'Komoot <type>hike</type> → Hike T2, said in one grey line');
check((await page.textContent('#activityChip')).includes('T2') && (await page.getAttribute('#activityChip', 'aria-label')).includes('Hike'), 'the activity chip shows Hike · T2');
const drawn = await page.waitForFunction(() => (window.__map.getSource('gpx-track')?.serialize().data.features.length ?? 0) >= 1, null, { timeout: 15_000 }).then(() => true).catch(() => false);
check(drawn, 'the track is drawn (gpx-track)');
// My routes keeps the track's length and climb for its row (owner 2026-11-06, planstats.ts – kept when imported)
let trackStats = null;
for (let i = 0; i < 60 && !trackStats; i++) {
  trackStats = await page.evaluate(async () => {
    const tr = (await window.__sync.list()).find((p) => p.kind === 'track');
    return (tr && JSON.parse(localStorage.getItem('onpath.planStats') ?? '{}')['t' + tr.id]) ?? null;
  });
  if (!trackStats) await page.waitForTimeout(250);
}
check(!!trackStats && trackStats.m > 2300 && trackStats.m < 2500 && trackStats.up > 0, `imported track: My routes keeps its length and climb (${JSON.stringify(trackStats)})`);
// "Plan a route from it" shows the app's modal box with Cancel; Cancel keeps the track and the plan as they were
const beforePins = await pins();
const box = await page.evaluate(async () => {
  document.getElementById('planFromTrack').click();
  document.querySelector('dialog.discard[open] #discardOk')?.click(); // Discard: the plan may go
  let d = null;
  for (let i = 0; i < 200 && !d; i++) { d = document.querySelector('dialog.trackbusy[open]'); if (!d) await new Promise((r) => setTimeout(r, 0)); }
  const text = d?.textContent ?? '';
  document.getElementById('cancelTrackPlan')?.click(); return text; });
check(/Making a plan from the tour/.test(box) && /Cancel/.test(box), `Plan a route from it: the modal box with Cancel ("${box.replace(/\s+/g, ' ').trim().slice(0, 60)}…")`);
await page.waitForFunction(() => !document.getElementById('planFromTrack')?.disabled, null, { timeout: 120_000 }).catch(() => {});
await page.waitForTimeout(500);
check(!!(await page.$('#trackPanel')) && await pins() === beforePins && !(await page.$('dialog.trackbusy')), 'Cancel: the track stays, the plan is not replaced, the box is gone');
// … "Plan a route from it": the app's TrackToPlan (Discard when "Save plan?" asks)
const planFrom = async () => {
  await page.click('#planFromTrack');
  const a = await page.waitForSelector('dialog.discard[open]', { timeout: 1_500 }).catch(() => null);
  if (a) await page.click('dialog.discard #discardOk');
};
await planFrom();
await page.waitForFunction(() => !document.getElementById('trackPanel'), null, { timeout: 120_000 });
await planned();
const n = await pins();
const sum = await page.textContent('#summary');
check(n >= 2 && n <= 8 && /2[.,][345] km/.test(sum), `Plan a route from it: ${n} route points, ${sum.match(/[\d.,]+ km/)?.[0]} along the track`);
check(await page.evaluate(() => window.__map.getSource('gpx-track').serialize().data.features.length) === 0, 'the track is gone once it is a plan');

// 4. Export GPX ⇄ Import GPX: the sport and the line come back
await page.click('#share');
const [dl] = await Promise.all([page.waitForEvent('download'), page.click('dialog.share #exportGpx')]);
await page.click('#closeShare');
const exported = await dl.path();
const gpx = fs.readFileSync(exported, 'utf8');
check(gpx.includes('<type>hike</type>'), 'Export GPX writes <type>hike</type>');
// the app's plan export (gpx/PlanGpx): the route points as <wpt>, the file "<name> - plan.gpx"
const wpts = (gpx.match(/<wpt /g) ?? []).length;
check(wpts === n && / - plan\.gpx$/.test(dl.suggestedFilename()), `Export GPX = the app's plan GPX: ${wpts} <wpt> for ${n} route points, "${dl.suggestedFilename()}"`);
const planHash = await page.evaluate(() => location.hash);
const planDist = sum.match(/[\d.,]+ km/)?.[0];
await page.evaluate(async (text) => {
  const f = new File([text], 'exported.gpx', { type: 'application/gpx+xml' });
  await window.__gpx.importFile(f);
}, gpx);
await page.waitForSelector('#trackPanel');
const tp2 = await page.textContent('#trackPanel');
check(tp2.includes(planDist) && !tp2.includes('Activity set to'), `re-import of the export: same ${planDist}, activity unchanged (no grey line)`);
// over the plan made from the track (not saved), "Plan a route from it" asks nothing any more (the app, owner 2026-11-06):
// the plan goes under Recent and the track becomes the plan
const recentT = await recentN();
await planFrom();
await page.waitForFunction(() => !document.getElementById('trackPanel'), null, { timeout: 60_000 });
check(await recentN() === recentT + 1, 'Plan a route from it over an unsaved plan: no question, the plan is kept under Recent');
// "Plan a route from it" on an OnPath plan GPX: its <wpt> come back as the plan, exactly (PlanGpx.stopsOf) – no fitting
await planned();
check(await pins() === n && await page.evaluate(() => location.hash) === planHash, `re-import → plan: the same ${n} route points (the plan GPX's <wpt>, no fitting)`);
// pinned points (a POI / place, passed exactly) go out as <wpt><type>pinned</type> and come back pinned (PlanGpx, 23f64bc3)
const pinCode = await page.evaluate(() => window.__engine.encode({ points: [[46.677, 11.161], [46.6879, 11.1111]], activity: 'HIKE', sac: 2,
  pinned: [1], names: { 1: 'Leiteralm' }, title: 'Pin test' }));
await page.goto(url + 'index.html#p=' + pinCode); // a hash change only: the page opens the link without a reload
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
// wait for the link's plan itself – the old plan's summary is still there while the new one is planned
await page.waitForFunction(() => window.__state?.meta?.[1]?.pinned === true && document.body.dataset.planning !== '1', null, { timeout: 120_000 })
  .catch(async (e) => { // what the page shows when the link's plan does not arrive (CI only, 2026-11-06)
    console.log('state at timeout:', JSON.stringify(await page.evaluate(() => ({ hash: location.hash, meta: window.__state?.meta, points: window.__state?.points,
      planning: document.body.dataset.planning, status: document.getElementById('status')?.textContent, wait: document.querySelector('.wait-card, #waitCard')?.textContent }))));
    console.log('page errors so far:', JSON.stringify(errors));
    throw e;
  });
await planned();
await page.click('#share');
const [dl2] = await Promise.all([page.waitForEvent('download'), page.click('dialog.share #exportGpx')]);
await page.click('#closeShare');
const gpx2 = fs.readFileSync(await dl2.path(), 'utf8');
check((gpx2.match(/<type>pinned<\/type>/g) ?? []).length === 1, 'Export GPX: the pinned point as <wpt><type>pinned</type>');
await page.evaluate(async (text) => { await window.__gpx.importFile(new File([text], 'pins.gpx', { type: 'application/gpx+xml' })); }, gpx2);
await page.waitForSelector('#trackPanel');
await planFrom();
await page.waitForFunction(() => !document.getElementById('trackPanel'), null, { timeout: 60_000 });
await planned();
const pinsBack = await page.evaluate(() => window.__state.meta.map((m) => !!m.pinned));
check(JSON.stringify(pinsBack) === '[false,true]', `re-import → plan: the pinned point is pinned again (${JSON.stringify(pinsBack)})`);

// 3. a .gpx dropped on the map: route + waypoints, <type>MTB</type>
const dropText = fs.readFileSync(fixture('mtb-route.gpx'), 'utf8');
await page.evaluate((text) => {
  const dt = new DataTransfer();
  dt.items.add(new File([text], 'mtb-route.gpx', { type: 'application/gpx+xml' }));
  const box = document.getElementById('map');
  box.dispatchEvent(new DragEvent('dragover', { dataTransfer: dt, bubbles: true, cancelable: true }));
  box.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
}, dropText);
await page.waitForSelector('#trackPanel');
const tp3 = await page.textContent('#trackPanel');
check(tp3.includes('Lana – Völlan <MTB>') && tp3.includes('Activity set to MTB from the file') && tp3.includes('Some parts of the file were skipped.'),
  'dropped GPX: its name, MTB from the file, the skipped point said');
check(await page.evaluate(() => window.__map.getSource('gpx-track').serialize().data.features.filter((f) => f.geometry.type === 'Point').length) === 2, 'its two waypoints are drawn');
await page.click('#closeTrack');

// a broken file: the readable part, never an error box
await page.evaluate(async (text) => { await window.__gpx.importFile(new File([text], 'broken.gpx')); }, fs.readFileSync(fixture('broken.gpx'), 'utf8'));
await page.waitForSelector('#trackPanel');
check((await page.textContent('#trackPanel')).includes('Some parts of the file were skipped.'), 'broken GPX: the readable part with the grey line');
await page.click('#closeTrack');
await page.evaluate(async () => { await window.__gpx.importFile(new File(['<gpx></gpx>'], 'empty.gpx')); });
await page.waitForFunction(() => document.getElementById('status').classList.contains('error'), null, { timeout: 10_000 }).catch(() => {});
check((await page.textContent('#status')).includes('Could not import this file: No track, route or waypoint found in this file.'), 'empty GPX: the app\'s plain error');

// 5. a point the route does not reach (the app's PlanGaps): Ifinger summit as Hike T2 – one red line + Show, grey dashed piece
const summit = await page.evaluate(() => window.__engine.encode({ points: [[46.6870, 11.2350], [46.7006, 11.2585]], activity: 'HIKE', sac: 2, pinned: [], names: { 1: 'Ifinger' }, title: null }));
await page.goto(url + 'index.html#p=' + summit);
await planned();
await page.waitForFunction(() => !document.getElementById('notice').hidden, null, { timeout: 30_000 }).catch(() => {});
const red = await page.textContent('#notice');
console.log('red line:', red);
// since 2026-11-07 (the app's PlanReach, owner "Meran → Mutkopf"): the line says what reaches it – "② only reachable from
// T3" / "② not reachable" – else the missing metres as before
const reachLine = /② (only reachable from T[3-5]|not reachable)/.test(red);
check((reachLine || /Last \d+ m to “Ifinger”: no way for Hike/.test(red)) && await page.isVisible('#showGap'), `unreachable summit at T2: the app's red line + Show (${reachLine ? 'what reaches it' : 'missing metres'})`);
check(await page.evaluate(() => window.__map.getSource('plan-gaps').serialize().data.features.length) === 1, 'the missing piece is drawn grey dashed');
// Show: like a steep section – the way that reaches the point drawn amber (PlanReach.way), the map fitted on it
await page.click('#showGap');
await page.waitForTimeout(800);
const reachDrawn = await page.evaluate(() => window.__map.getSource('plan-reach')?.serialize().data.features.length ?? -1);
check(reachLine ? reachDrawn === 1 : reachDrawn === 0, `Show draws the reaching way amber only with a reach line (${reachDrawn})`);

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE|ERROR) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
console.log(fails.length ? `FAILED (${fails.length})` : 'all ok');
process.exit(fails.length ? 1 : 0);
