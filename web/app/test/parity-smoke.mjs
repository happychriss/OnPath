// The phone's Plan screen features in the browser (docs/design/web-planner.md §11): first-point menu (Start here ·
// Go here from my position), undo / redo (buttons and keys – the app's PlanHistory), the eye (hold hides route and
// markers), the compass (turned map → north up), the point menu's "Open in Google Maps" (a new tab with the app's
// link), Export GPX, Points delete, route warnings, and the bottom sheet at phone width. Web layout (§13): toolbar on
// top, direction arrows, activity drop-down, Points drag, Map content, Save / Save as new. Needs only the rd5 (CI).
// Usage: node test/parity-smoke.mjs [segmentsDir]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (pinned, named), "Waalweg"
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, permissions: ['geolocation'],
  geolocation: { latitude: 46.6705, longitude: 11.1605, accuracy: 10 } });
await ctx.route('https://www.google.com/**', (r) => r.fulfill({ body: 'Google Maps stand-in', contentType: 'text/html' })); // never the real site
const page = await ctx.newPage();
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const pins = () => page.evaluate(() => document.querySelectorAll('.pin').length);
const waitPins = (n) => page.waitForFunction((k) => document.querySelectorAll('.pin').length === k, n, { timeout: 15_000 }).catch(() => {});
const planned = () => page.waitForFunction(() => document.querySelector('#summary .big') && !document.getElementById('status').textContent.includes('Planning'), null, { timeout: 120_000 });
const clickAt = async (lon, lat, button = 'left') => {
  // the camera at rest (the first route is fitted whole a moment after it is there – the app's WholeRoute), and the spot
  // in the free map room (not under the sheet or the search bar, on screen) – else the map is centred on it first
  await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 10_000 }).catch(() => {});
  const free = await page.evaluate(([lo, la]) => {
    const m = window.__map, q = m.project([lo, la]), c = m.getContainer().getBoundingClientRect();
    const sheet = document.getElementById('sheet').getBoundingClientRect(), bar = document.querySelector('.topbar').getBoundingClientRect();
    const x = c.left + q.x, y = c.top + q.y;
    return x > sheet.right + 10 && x < c.right - 60 && y > bar.bottom + 10 && y < c.bottom - 40;
  }, [lon, lat]);
  if (!free) {
    await page.evaluate(([lo, la]) => window.__map.jumpTo({ center: [lo, la] }), [lon, lat]);
    await page.waitForTimeout(200);
  }
  const p = await page.evaluate(([lo, la]) => { const q = window.__map.project([lo, la]); return [q.x, q.y]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1], { button });
};
const menuItems = () => page.$$eval('.pointmenu button .txt > span:first-child', (b) => b.map((x) => x.textContent));

await page.goto(url + 'index.html');
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });

// 1. first click on an empty plan: "New route" – Start here · Go here (MapGestures.FirstPointMenu)
await clickAt(11.1600, 46.6700);
await page.waitForSelector('.pointmenu');
check((await page.textContent('.menuhead')).includes('New plan') && (await menuItems()).join('|') === 'Start here|Round trip from here|Go here', 'first click: New plan – Start here · Round trip from here · Go here (round trips on for everyone, 72a66e39)');
check(await page.isHidden('#undoPill') && await page.isHidden('#undo'), 'no undo before the first change (the undo pill is hidden – the app\'s UndoPill)');
check(await page.$('#toolbar #undo, #toolbar #redo') === null, 'undo / redo are not in the tool row (owner 2026-11-08: ↶ ↷ there read as „turn the map“)');
const tb = await page.locator('#toolbar').boundingBox(), sb = await page.locator('#searchbox').boundingBox();
const bar = await page.locator('#topbar').boundingBox();
check(tb.y < 100 && tb.x >= sb.x + sb.width && Math.abs(tb.y - sb.y) < 2 && Math.abs(tb.height - sb.height) < 2 &&
  tb.x + tb.width <= bar.x + bar.width + 1 && sb.x >= bar.x, 'one top bar: search field left, the tools right in it, same height');
check(await page.evaluate(() => { const s = getComputedStyle(document.getElementById('topbar')); const t = getComputedStyle(document.getElementById('toolbar'));
  return s.boxShadow !== 'none' && t.boxShadow === 'none'; }), 'the bar has one shared background and shadow');
check(await page.evaluate(() => document.fonts.check('600 14px Manrope') && [...document.fonts].some((f) => f.family.replace(/"/g, '') === 'Manrope' && f.status === 'loaded')),
  'Manrope served by the page itself');
await page.click('.pointmenu button:first-of-type');
await waitPins(1);
check(await pins() === 1, 'Start here sets the start');
await page.waitForSelector('#undo', { state: 'visible', timeout: 10_000 }).catch(() => {}); // the state comes back from the engine worker
await page.waitForFunction(() => !document.getElementById('undo').disabled, null, { timeout: 10_000 }).catch(() => {});
check(await page.isEnabled('#undo') && await page.isDisabled('#redo'), 'undo possible, redo greyed');
{
  const pill = await page.locator('#undoPill').boundingBox(), top = await page.locator('#topbar').boundingBox();
  const u = await page.locator('#undo').boundingBox(), r = await page.locator('#redo').boundingBox();
  const vw = page.viewportSize().width;
  const placed = vw >= 1012
    ? pill && Math.abs(pill.y + pill.height / 2 - (top.y + top.height / 2)) < 2 && pill.x >= top.x + top.width + 12 && pill.x + pill.width <= vw
    : pill && pill.y >= top.y + top.height + 12 && Math.abs(pill.x + pill.width - (top.x + top.width)) < 2;
  check(placed && r.x >= u.x + u.width - 1 && Math.abs(r.y - u.y) < 1,
    `the undo pill: ${vw >= 1012 ? 'to the right of the bar, on its height' : 'under the bar, right-aligned'}, set a little apart, ↶ left of ↷ in one row (owner 2026-11-13)`);
}
await clickAt(11.1350, 46.6840);
await planned();
check(await pins() === 2, 'next click adds the destination (no menu)');
check(await page.isEnabled('#eye'), 'eye usable once there is a route');
check(await page.evaluate(() => !!window.__map.getLayer('planned-route-arrows') && window.__map.getLayer('planned-route-arrows').type === 'symbol'),
  'direction arrows along the route (symbol layer, as the app)');

// 2. undo / redo (buttons and keys)
await page.click('#undo'); await waitPins(1);
check(await pins() === 1 && await page.isEnabled('#redo'), 'undo takes the destination back, redo possible');
await page.click('#redo'); await waitPins(2);
check(await pins() === 2, 'redo brings it back');
await page.keyboard.press('Control+z'); await waitPins(1);
check(await pins() === 1, 'Ctrl+Z undoes');
await page.keyboard.press('Control+Shift+z'); await waitPins(2);
check(await pins() === 2, 'Ctrl+Shift+Z redoes');
await planned();

// 3. the eye: held = route and markers hidden, released = back
const box = await page.locator('#eye').boundingBox();
await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
await page.mouse.down();
const held = await page.evaluate(() => ({ line: window.__map.getLayoutProperty('planned-route', 'visibility'),
  pin: getComputedStyle(document.querySelector('.pin')).visibility }));
await page.mouse.up();
const released = await page.evaluate(() => ({ line: window.__map.getLayoutProperty('planned-route', 'visibility'),
  pin: getComputedStyle(document.querySelector('.pin')).visibility }));
check(held.line === 'none' && held.pin === 'hidden', 'eye held: route and markers hidden');
check(released.line !== 'none' && released.pin === 'visible', 'eye released: shown again');

// 4. orientation: a turned map shows it on the compass; a click turns it north up
await page.evaluate(() => window.__map.setBearing(45));
await page.waitForTimeout(100);
check((await page.getAttribute('#compass', 'title')).includes('turned') &&
  (await page.getAttribute('#compass .needle', 'style')).includes('rotate(-45deg)'), 'compass shows the turned map');
await page.click('#compass');
await page.waitForFunction(() => Math.abs(window.__map.getBearing()) < 0.5, null, { timeout: 5_000 }).catch(() => {});
check(Math.abs(await page.evaluate(() => window.__map.getBearing())) < 0.5, 'compass click: north up');

// 5. point menu on a middle point says what Start here removes; Delete via the Points list ✕
// a third point: the hold rule on the real paths decides (section 11) – set at once, or the hold menu asks: its first row
await clickAt(11.1500, 46.6950);
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 3 || document.querySelector('.holdaddpopup button'), null, { timeout: 15_000 });
if (await page.$('.holdaddpopup button')) await page.click('.holdaddpopup button:first-of-type');
await waitPins(3);
await planned();
// the pinned elevation profile lies along the map's bottom edge: a point set low on the map can sit under it (CI's window,
// release run 2026-11-18) – move the map until the point is in the free room, as a user would
await page.evaluate(async () => {
  const pin = document.querySelectorAll('.pin')[1].getBoundingClientRect(), prof = document.getElementById('bigProfile');
  const top = prof && !prof.hidden ? prof.getBoundingClientRect().top : innerHeight;
  if (pin.bottom > top - 20) { window.__map.panBy([0, pin.bottom - top + 80], { duration: 0 }); await new Promise((r) => setTimeout(r, 200)); }
});
await page.locator('.pin').nth(1).click();
await page.waitForSelector('.pointmenu');
const mid = await menuItems();
check(mid[0] === 'Start here' && mid.at(-1) === 'Delete', 'point menu of a middle point: Start here … Delete');
await page.keyboard.press('Escape');
// drag ≡ of the first row below the last: the row follows the pointer, the others move aside; drop = new order
const order = () => page.evaluate(() => location.hash); // the plan's points, in order (rows are named by position)
await page.waitForTimeout(300);
const before = await order();
const g = await page.locator('#points li:first-child .grip').boundingBox();
const lastRow = await page.locator('#points li:last-child').boundingBox();
await page.mouse.move(g.x + g.width / 2, g.y + g.height / 2);
await page.mouse.down();
await page.mouse.move(g.x + g.width / 2, lastRow.y + lastRow.height / 2 + 4, { steps: 8 });
const live = await page.evaluate(() => ({ lifted: document.querySelector('#points li.lifted')?.style.transform ?? '',
  moved: [...document.querySelectorAll('#points li:not(.lifted)')].filter((li) => li.style.transform.includes('-')).length }));
await page.mouse.up();
check(/translateY\(\d/.test(live.lifted) && live.moved === 2, 'dragged row moves with the pointer, the others make room');
await page.waitForFunction((b) => location.hash !== b, before, { timeout: 15_000 }).catch(() => {});
check(await order() !== before && await pins() === 3, 'drop gives the plan the new order (PlanEdits.move)');
await planned();
await page.keyboard.press('Control+z'); await planned(); // back to the order before
await page.waitForFunction((b) => location.hash === b, before, { timeout: 15_000 }).catch(() => {});
check(await order() === before, 'one undo step brings the old order back');
await page.click('#points li:nth-child(2) .del');
await waitPins(2);
check(await pins() === 2, 'Points list ✕ deletes the point');

// 6. Export GPX (the app's GpxWriter) – in Share since the sheet has no ⋯ (owner 2026-10-31)
await planned();
check(await page.locator('#sheetMenuBtn').count() === 0 && await page.locator('#planActions #clear').count() === 0 && await page.isVisible('#planActions #reverse') && await page.isVisible('#planActions #roundTrip'),
  'plan sheet without ⋯: ⇅ Reverse · ↻ Round trip under the summary (no ✕ – the plan bar\'s + New, owner 2026-11-06)');
await page.click('#share');
const [dl] = await Promise.all([page.waitForEvent('download'), page.click('dialog.share #exportGpx')]);
await page.click('#closeShare');
const gpx = await (await import('node:fs')).promises.readFile(await dl.path(), 'utf8');
check(dl.suggestedFilename().endsWith('.gpx') && gpx.includes('<trkpt') && gpx.includes('<type>'), 'Export GPX downloads a track with the sport type');

// 6b. activity drop-down at the chip: one click picks and closes; Hike's T level in the same list
await page.click('#activityChip');
// measured once the pace section is in: it arrives a moment later and makes the list taller - it must still sit
// under the chip (it was pushed up over the chip on an 800 px window, red in CI since 2026-11-08)
await page.waitForSelector('#activityMenu #paceRow');
await page.waitForTimeout(100);
const am = await page.locator('#activityMenu').boundingBox(), chip = await page.locator('#activityChip').boundingBox();
check(await page.isVisible('#activityMenu') && Math.abs(am.y - (chip.y + chip.height)) < 12 && Math.abs(am.x - chip.x) < 12, 'activity list opens under the chip (no dialog)');
await page.click('#activityMenu [data-act=BIKE_TOURING]');
check(await page.isHidden('#activityMenu') && (await page.textContent('#activityChip')).includes('Bike'), 'picking Bike closes the list at once');
await planned();
await page.click('#activityChip');
await page.click('#activityMenu [data-sac="4"]');
// app 2a54f2e4 (owner 2026-11-08, father's feedback): a T chip picks Hike T4 at once, the list stays open with the
// level's line „T4 · …: …“ and the way out says „Done“
check(await page.isVisible('#activityMenu') && (await page.textContent('#activityChip')).replace(/\s+/g, ' ').includes('Hike · T4')
  && /^T4 · .+: .+/.test((await page.textContent('#activityMenu .sacline')).trim()) && (await page.textContent('#actDone')).trim() === 'Done',
  'a T level picks Hike T4 at once, the list stays open with its line and "Done"');
await page.click('#actDone');
check(await page.isHidden('#activityMenu'), '"Done" closes the list');

// 6c. pace (the app's Settings → Pace) in the same list: a setting – the list stays open, the sheet's time follows
// the sheet shows the engine's time for these points + Hike T4 (the plans of the two picks above may still arrive)
const engineTime = () => page.evaluate(async () => { const s = window.__state; const t = await window.__engine.call('planTime', s.points.map(([a, o]) => a.toFixed(6) + ',' + o.toFixed(6)).join(';'), s.activity, s.sac); return t && t !== 'null' ? JSON.parse(t).time : null; });
const settled = async () => { for (let i = 0; i < 240; i++) { const t = await engineTime(); if (t && t === await page.textContent('#summary .time').catch(() => '')) return t; await page.waitForTimeout(500); } return null; };
await settled();
const bigTime = () => page.textContent('#summary .time');
const avgTime = await bigTime();
await page.click('#activityChip');
await page.waitForSelector('#paceRow [data-pace]', { timeout: 10_000 }).catch(() => {});
const levels = await page.$$eval('#paceRow [data-pace]', (b) => b.map((x) => x.textContent.trim()));
check(levels.join('|') === 'Relaxed|Easy|Average|Fit|Very fit' && (await page.textContent('#paceRow')).includes('Hiking') &&
  await page.getAttribute('#paceRow [data-pace=AVERAGE]', 'aria-checked') === 'true', `pace section: the app's five levels, Hiking, Average chosen (${levels.join(', ')})`);
check((await page.textContent('#paceMeaning')).includes('4 km/h · 300 m up per hour'), "pace: what Average means (the app's DIN line)");
await page.click('#activityMenu [data-pace=VERY_FIT]');
await page.waitForFunction((t) => document.querySelector('#summary .time')?.textContent !== t, avgTime, { timeout: 10_000 }).catch(() => {});
const fitTime = await bigTime();
check(await page.isVisible('#activityMenu') && fitTime === await engineTime() && fitTime !== avgTime &&
  await page.evaluate(() => localStorage.getItem('onpath.hikePaceLevel')) === 'VERY_FIT',
  `Very fit: list stays open, the time is the engine's (${avgTime} → ${fitTime}), remembered in this browser`);
await page.click('#activityMenu [data-pace=AVERAGE]');
await page.waitForFunction((t) => document.querySelector('#summary .time')?.textContent === t, avgTime, { timeout: 10_000 }).catch(() => {});
check(await bigTime() === avgTime, 'back to Average: the first time again');
await page.keyboard.press('Escape');
await planned();

// 6c. Map content: the app's POI kinds by group, applied live to the poi- layers (PoiFilter), remembered
await page.click('#mapContent');
await page.waitForSelector('dialog.mapcontent[open] [data-kind=hut]');
check(await page.locator('dialog.mapcontent [data-kind]').count() === 28 && await page.isChecked('[data-kind=hut]') && !(await page.isChecked('[data-kind=parking]')),
  'Map content: 28 kinds, the app\'s defaults (huts on, parking off)');
await page.uncheck('[data-kind=hut]');
const kindsOn = () => page.evaluate(() => { const f = window.__map.getFilter('poi-tier1'); return f?.[0] === 'all' ? f.at(-1)[2][1] : null; });
await page.waitForFunction(() => { const f = window.__map.getFilter('poi-tier1'); return f?.[0] === 'all' && !f.at(-1)[2][1].includes('hut'); }, null, { timeout: 5_000 }).catch(() => {});
const on = await kindsOn();
check(Array.isArray(on) && on.includes('bivouac') && !on.includes('hut'), 'unticking Mountain hut filters it out of the map at once (PoiFilter)');
await page.fill('#kindFilter', 'water');
check(await page.locator('dialog.mapcontent [data-kind]').count() < 28 && await page.isVisible('[data-kind=water]'), 'the filter box narrows the kinds');
await page.uncheck('#mcContours');
check(await page.evaluate(() => window.__map.getLayer('contour-minor') ? window.__map.getLayoutProperty('contour-minor', 'visibility') === 'none' : true), 'contour lines off');
await page.click('#mcReset'); await page.check('#mcContours');
await page.click('#mcClose');
check(await page.evaluate(() => JSON.parse(localStorage.getItem('onpath.mapContent')).hidden.includes('parking')), 'choice remembered in this browser');

// 6d. Save (web-sync.md §14.6, the app's Save since 2026-10-31 "Save always asks for the name"): the header's Save – not
// paired "Save", dark for a new or changed route, light "✓ Saved" when saved. Every Save opens the name dialog (the name
// selected, Cancel · Save): a new route under the typed / proposed name, the open saved route overwritten – renamed when
// the name changed; "Saved as …" is a plain line; Save as copy is My routes → ⋮ (proposes "Name (2)").
const saveHead = () => page.evaluate(() => { const b = document.getElementById('menuSavePlan'); return { state: b.dataset.state, text: b.textContent.trim(), disabled: b.disabled, title: b.title }; });
const savedTitles = () => page.evaluate(async () => (await window.__sync.list()).map((p) => p.title).sort());
/** Save's dialog is open: its field (value, fully selected) – then [name] typed (null = keep) and Save. */
const answerSave = async (name) => {
  await page.waitForSelector('dialog.savename[open] #saveTitle', { timeout: 10_000 });
  const f = await page.evaluate(() => { const i = document.getElementById('saveTitle'); return { value: i.value, selected: i.selectionEnd - i.selectionStart === i.value.length, title: document.querySelector('dialog.savename h2').textContent }; });
  if (name != null) await page.fill('#saveTitle', name);
  await page.click('#saveOk');
  return f;
};
let sh = await saveHead();
check(await page.isVisible('#header #menuSavePlan') && sh.state === 'dark' && !sh.disabled && sh.text === 'Save',
  `header: "Save", dark with a new route (${JSON.stringify(sh)})`);
await page.click('#menuSavePlan');
await page.waitForSelector('dialog.savename[open]', { timeout: 10_000 });
await page.click('#saveCancel');
await page.waitForTimeout(300);
check((await savedTitles()).length === 0 && !(await page.$('dialog[open]')), 'Save → Cancel: nothing saved');
await page.click('#menuSavePlan');
const f1 = await answerSave(null);
await page.waitForSelector('#savedLine:not([hidden])', { timeout: 10_000 }).catch(() => {});
const firstSaved = await savedTitles();
check(f1.title === 'Save plan' && f1.selected && f1.value.length > 0 && JSON.stringify(firstSaved) === JSON.stringify([f1.value]) && (await page.textContent('#savedLine')).includes('Saved as ' + firstSaved[0]) && !(await page.$('#savedLine button')),
  `Save: the dialog proposes "${f1.value}" (selected), Save stores it; "Saved as …" is a plain line (${JSON.stringify(f1)})`);
await page.waitForFunction(() => document.getElementById('menuSavePlan').dataset.state === 'done', null, { timeout: 10_000 }).catch(() => {});
await page.click('#menuSavePlan'); // saved and unchanged: the dialog with its name; a new name renames the same route
const f2 = await answerSave('Test loop');
await page.waitForFunction(() => document.getElementById('savedLine').textContent.includes('Saved as Test loop'), null, { timeout: 10_000 }).catch(() => {});
check(f2.value === firstSaved[0] && JSON.stringify(await savedTitles()) === '["Test loop"]', `Save again: the dialog shows "${f2.value}"; renamed "Test loop", still one route`);
await page.waitForFunction(() => document.getElementById('menuSavePlan').dataset.state === 'done', null, { timeout: 10_000 }).catch(() => {});
sh = await saveHead();
check(sh.state === 'done' && sh.text === 'Saved' && sh.title === 'Everything is saved in this browser', `header: saved and unchanged → light "✓ Saved" (${JSON.stringify(sh)})`);
await page.click('#menuPlans');
await page.waitForSelector('dialog.plans[open] #planList li[data-id]');
const myRow = await page.evaluate(() => { const li = document.querySelector('#planList li[data-id]');
  const live = document.querySelector('#planList li[data-live]');
  return { name: li.querySelector('.name').textContent, live: live?.querySelector('.name').textContent, liveTags: [...(live?.querySelectorAll('.tag') ?? [])].map((t) => t.textContent), where: [...li.querySelectorAll('.where .w')].map((w) => w.getAttribute('aria-label')).join(),
    menu: [...li.querySelectorAll('.rowmenu button')].map((b) => b.textContent.trim()) }; });
// the open saved route is the Recent row („In progress“ + „Saved“, app 2923a3e7); under Saved its plain row, ⋮ with Plan first
check(myRow.name === 'Test loop' && myRow.live === 'Test loop' && JSON.stringify(myRow.liveTags) === '["In progress","Saved"]' && myRow.where === 'In this browser' && await page.locator('#saveCurrent').count() === 0
  && JSON.stringify(myRow.menu) === '["Plan","Rename","Save as copy","Export GPX","Share plan","Delete"]',
  `My routes: the row "Test loop", the open one under Recent ("In progress" + "Saved"), 🖥, ⋮ as the app's (${JSON.stringify(myRow)})`);
// length and climb in the row (owner 2026-11-06, planstats.ts): the sheet's distance, ↑ / ↓ in metres; under the name and the activity line, as the app's L1 row (2026-11-13)
{
  const st = await page.evaluate(() => { const li = document.querySelector('#planList li[data-id]'), s = li.querySelector('.stats');
    const n = li.querySelector('.name').getBoundingClientRect(), r = s?.getBoundingClientRect();
    const sp = (x) => x?.replace(/ /g, ' ') ?? null; // the row keeps each value together with no-break spaces (the app's facts)
    return { text: sp(s?.textContent), nbsp: / /.test(s?.textContent ?? ''), dist: sp(document.querySelector('#summary .dist')?.textContent ?? ''),
      time: sp(document.querySelector('#summary .time')?.textContent ?? ''), below: !!r && r.top > n.bottom - 1 }; });
  // the app's facts (RouteRowActions.facts, laptop 2026-11-15): km · ↑ climb · the model time – the sheet's time, no ↓
  check(st.text !== null && st.text === `${st.dist} · ↑ ${st.text.split(' · ')[1]?.slice(2)} · ${st.time}` && /^↑ \d+ m$/.test(st.text.split(' · ')[1] ?? '')
    && /\d+ min$/.test(st.time) && st.nbsp && st.below,
    `My routes: length, climb and time under the name (the app's L1), "${st.text}" (sheet: ${st.dist}, ${st.time})`);
}
await page.click('#closePlans');
if (await page.isEnabled('#undo')) { // a change → Save dark; Save overwrites; My routes ⋮ → Save as copy makes a copy
  await page.click('#undo');
  await page.waitForFunction(() => /changed, not saved/.test(document.getElementById('savedLine').textContent), null, { timeout: 10_000 }).catch(() => {});
  check((await saveHead()).state === 'dark', 'header: changed since saving → dark');
  await page.click('#menuSavePlan');
  await answerSave(null);
  await page.waitForFunction(() => document.getElementById('menuSavePlan').dataset.state === 'done', null, { timeout: 10_000 }).catch(() => {});
  check((await saveHead()).state === 'done' && JSON.stringify(await savedTitles()) === '["Test loop"]', 'Save: the changed route saved over "Test loop" (its name kept)');
  // unsaved = the points only (the app's RouteRules.isUnsaved, laptop 2026-11-15): another activity is no change – Save
  // stays ✓ „Saved“, the line „Saved as …“, the Zuletzt row keeps its „Saved“ tag; a click on Save still saves (overwrites
  // with the new activity, like the app's Save), then the activity goes back the same way
  {
    const was = await page.evaluate(() => ({ act: window.__state.activity, sac: window.__state.sac }));
    const stored = () => page.evaluate(async () => (await window.__sync.list()).find((p) => p.title === 'Test loop').plan);
    const plan0 = await stored();
    await page.click('#activityChip');
    await page.click(`#activityMenu [data-act=${was.act === 'MTB' ? 'BIKE_TOURING' : 'MTB'}]`);
    await page.waitForTimeout(800); // renderSaved runs after the change (an engine round trip)
    const head = await saveHead(), line = await page.textContent('#savedLine');
    await page.click('#menuPlans');
    await page.waitForSelector('dialog.plans[open] #planList li[data-live]'); // the list filters on the planning activity (now the other one): the live row
    const tags = await page.$$eval('#planList li[data-live] .tag', (els) => els.map((e) => e.textContent.trim()));
    await page.click('#closePlans');
    check(head.state === 'done' && /Saved as Test loop/.test(line) && !/changed, not saved/.test(line) && tags.includes('Saved'),
      `another activity is no change (points only, the app's rule): Save ✓, "${line.trim()}", Zuletzt tags ${JSON.stringify(tags)} (${JSON.stringify(head)})`);
    await page.click('#menuSavePlan'); await answerSave(null); // the overwrite's name dialog, name kept
    for (let i = 0; i < 40 && (await stored()) === plan0; i++) await page.waitForTimeout(250);
    check((await stored()) !== plan0 && (await saveHead()).state === 'done' && JSON.stringify(await savedTitles()) === '["Test loop"]',
      'a click on Save still saves the route with its new activity (overwrites "Test loop", as the app)');
    await page.click('#activityChip');
    await page.click(was.act === 'HIKE' ? `#activityMenu [data-sac="${was.sac}"]` : `#activityMenu [data-act=${was.act}]`);
    if (await page.isVisible('#activityMenu')) await page.click('#actDone');
    await page.waitForTimeout(500);
    await page.click('#menuSavePlan'); await answerSave(null);
    for (let i = 0; i < 40 && (await stored()) !== plan0; i++) await page.waitForTimeout(250);
    check((await stored()) === plan0, 'back to the first activity and saved: the stored plan as before');
  }
  const before = await page.evaluate(async () => (await window.__sync.list()).find((p) => p.title === 'Test loop').plan);
  await page.click('#menuPlans');
  await page.waitForSelector('dialog.plans[open] #planList li[data-id]');
  await page.click('#planList li[data-id] .more'); await page.click('#planList li[data-id] .copy');
  const fc = await answerSave(null);
  for (let i = 0; i < 40 && (await savedTitles()).length < 2; i++) await page.waitForTimeout(250);
  const copy = await page.evaluate(async () => (await window.__sync.list()).find((p) => p.title === 'Test loop (2)'));
  check(fc.title === 'Save as copy' && fc.value === 'Test loop (2)' && copy?.plan === before && JSON.stringify(await savedTitles()) === '["Test loop","Test loop (2)"]'
    && (await page.textContent('#savedLine')).includes('Saved as Test loop'),
    `My routes ⋮ → Save as copy: proposes "Test loop (2)", the same route stored again; the shown plan stays "Test loop" (${JSON.stringify(fc)})`);
  await page.click('#closePlans').catch(() => {});
}

// 6e. the pointing hand over what a click opens: a POI and the route line; the plain map keeps maplibre's cursor
{
  const cursor = async (x, y) => { await page.mouse.move(x, y); await page.waitForTimeout(150); return page.evaluate(() => window.__map.getCanvas().style.cursor); };
  const mapBox = await page.locator('#map').boundingBox();
  const spot = { x: mapBox.x + mapBox.width - 220, y: mapBox.y + 260 };
  const plain = await cursor(spot.x, spot.y);
  await page.evaluate(([x, y]) => {
    const m = window.__map; // a station: drawn from zoom 10, so the camera stays (later steps click by position)
    const r = m.getContainer().getBoundingClientRect(), ll = m.unproject([x - r.left, y - r.top]);
    m.getSource('pois').setData({ type: 'FeatureCollection', features: [{ type: 'Feature', geometry: { type: 'Point', coordinates: [ll.lng, ll.lat] }, properties: { kind: 'train', name: 'Teststation' } }] });
  }, [spot.x, spot.y]);
  await page.waitForFunction(() => window.__map.queryRenderedFeatures({ layers: ['poi-station'] }).length > 0, null, { timeout: 10_000 }).catch(() => {});
  const onPoi = await cursor(spot.x + 1, spot.y);
  check(plain === '' && onPoi === 'pointer', `cursor: pointer over a POI (${JSON.stringify(onPoi)}), none of ours on the plain map (${JSON.stringify(plain)})`);
  const line = await page.evaluate(() => { const m = window.__map, f = m.queryRenderedFeatures({ layers: ['planned-route'] })[0];
    if (!f) return null; const c = f.geometry.type === 'LineString' ? f.geometry.coordinates : f.geometry.coordinates[0];
    // a vertex the map itself shows (on a wide screen the elevation profile lies along the map's bottom edge – plan sheet A)
    const r = m.getContainer().getBoundingClientRect(), order = c.map((_, i) => (Math.floor(c.length / 2) + i) % c.length);
    for (const i of order) { const p = m.project(c[i]), x = p.x + r.left, y = p.y + r.top;
      if (document.elementFromPoint(x, y)?.classList.contains('maplibregl-canvas')) return [x, y]; }
    return null; });
  check(line && await cursor(line[0], line[1]) === 'pointer', 'cursor: pointer over the route line');
  await page.evaluate(() => window.__map.getSource('pois').setData({ type: 'FeatureCollection', features: [] }));
}

// 7. Go here from my position (browser location)
// + New: no question any more (the app, owner 2026-11-06) – an unsaved plan goes under Meine Touren → Zuletzt.
// The plan bar's + New (owner 2026-11-06) is the one way – the plan row has no ✕ any more
check(await page.isEnabled('#newPlan') && (await page.textContent('#newPlan')).trim() === 'New', 'plan bar: + New, active with a plan on the map');
await page.click('#newPlan');
check(await page.locator('dialog[open]').count() === 0, 'New plan: no question (the app: the plan goes under Recent)');
await waitPins(0);
check(await page.isDisabled('#newPlan'), 'plan bar: + New greyed on an empty map');
await clickAt(11.1350, 46.6840);
await page.waitForSelector('.pointmenu');
await page.locator('.pointmenu button', { hasText: 'Go here' }).click();
await planned();
const first = await page.evaluate(() => window.__engine && location.hash);
check(await pins() === 2 && first.length > 10, 'Go here: route from my position to the clicked spot');

// 8. Google Maps link-out from a named point (new tab, the app's PoiLinks link, nothing drawn here)
await page.goto(url + 'index.html#p=' + CODE);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
await page.locator('.pin').nth(1).click(); // Vellau, the named destination
await page.waitForSelector('.pointmenu');
check((await menuItems()).includes('Open in Google Maps'), 'named point: Open in Google Maps in the menu');
const [tab] = await Promise.all([ctx.waitForEvent('page'), page.click('.pointmenu button:has-text("Open in Google Maps")')]);
await tab.waitForURL(/google\.com/, { timeout: 15_000 }).catch(() => {}); // the tab opens blank in the click, then gets the link
console.log('google tab:', tab.url());
check(/^https:\/\/www\.google\.com\/maps\/search\/Vellau\/@46\.\d+,11\.\d+,16z$/.test(tab.url()), 'Google Maps opens in a new tab with the place name near its point');
await tab.close();

// 9. route warnings (the app's RouteWarnings): MTB up the Ifinger (a ford) → amber line, click → "On this route" rows
const steep = await page.evaluate(() => window.__engine.encode({ points: [[46.6870, 11.2350], [46.7006, 11.2585]], activity: 'MTB', sac: 2, pinned: [], names: {}, title: null }));
await page.goto(url + 'index.html#p=' + steep); // only the hash changes: the page opens the plan (hashchange)
await page.waitForFunction(() => !document.getElementById('warning').hidden, null, { timeout: 120_000 }).catch(() => {});
const wl = await page.textContent('#warning');
console.log('warning:', wl);
// MTB: no 'steep' line any more (app's RouteWarnings.isMtb, main 2026-10-27); the ford there stays
// (case-blind: the line starts with a capital since 2026-11-07, "Ford · …")
check(await page.isVisible('#warning') && /ford/i.test(wl) && !/steep/i.test(wl),'route warning line (ford, no steep for MTB) shown');
await page.click('#warning');
check(await page.locator('#spots li').count() > 0, 'click → the spots on this route');

// 10. phone width: bottom sheet; the handle shows the Points
await page.setViewportSize({ width: 412, height: 860 });
// the sheet moves to the bottom with a transition – wait until it has settled (CI: once read mid-way after 300 ms)
// (open on start since 2026-11-07: points and profile shown – the sheet reaches up to below the search bar)
await page.waitForFunction(() => { const r = document.getElementById('sheet').getBoundingClientRect(); return r.top > 120 && r.bottom >= 855; },
  null, { timeout: 5000 }).catch(() => {});
const sheet = await page.locator('#sheet').boundingBox();
check(sheet.y > 120 && sheet.y + sheet.height >= 855, `phone: the plan sheet sits at the bottom (top ${Math.round(sheet.y)}, bottom ${Math.round(sheet.y + sheet.height)})`);
check(await page.isVisible('#menuBtn') && (await page.textContent('#menuBtn')).trim() === 'Plan' && await page.isHidden('#menuPlans') && await page.isHidden('#menuSavePlan'),
  'phone: the plan bar is one "Plan ▾" button');
const ptb = await page.locator('#toolbar').boundingBox(), psb = await page.locator('#searchbox').boundingBox(), pbar = await page.locator('#topbar').boundingBox();
check(ptb.y >= psb.y + psb.height - 1 && ptb.y + ptb.height <= pbar.y + pbar.height + 1 && Math.abs(psb.width - pbar.width) < 2,
  'phone: search full width, the tools as a row below it in the same bar');
check((await Promise.all(['#eye', '#compass', '#locate', '#mapContent'].map((s) => page.isVisible(s)))).every(Boolean),
  'phone: all tools visible (none behind a menu)');
check(await page.evaluate(() => { const p = document.getElementById('undoPill'), b = document.getElementById('topbar').getBoundingClientRect();
  if (p.hidden) return document.getElementById('undo').disabled && document.getElementById('redo').disabled; // nothing to undo → no pill
  const r = p.getBoundingClientRect(), s = document.getElementById('sheet').getBoundingClientRect();
  if (document.body.classList.contains('undo-crowded')) return r.width === 0 && s.top < b.bottom + 76; // the sheet is up: the pill steps back
  return r.top >= b.bottom + 12 && Math.abs(r.right - b.right) < 2 && r.width === 104 && r.height === 52 && r.bottom <= s.top; }),
  'phone: the undo pill under the bar\'s right end, clear of the sheet (or hidden: nothing to undo / the sheet pulled up)');
await page.click('#menuBtn');
const pm = await page.$$eval('#mainMenu .pb', (bs) => bs.filter((b) => b.getClientRects().length > 0).map((b) => b.id || 'link'));
check(JSON.stringify(pm) === JSON.stringify(['newPlan', 'menuPlans', 'menuMultiDay', 'menuPlanAsTour', 'menuImportGpx', 'menuSavePlan', 'menuSaveAs', 'menuExportGpx', 'syncBadge', 'menuPhone', 'menuAboutP', 'link']),
  'phone: Plan ▾ lists New · My tours · Multi-day tour · Plan as multi-day tour · Import GPX · Save · Save as · Export GPX · To phone · Phone connection · Help · the app ' + JSON.stringify(pm));
await page.keyboard.press('Escape');
// open on start (owner 2026-11-07: points and profile shown, the sheet can be folded)
check(await page.isVisible('#points'), 'phone: the Points shown on start');
await page.click('#handle');
check(await page.isHidden('#points'), 'phone: the handle folds them away');
await page.click('#handle');
check(await page.isVisible('#points'), 'phone: the handle shows the Points again');

// 10b. Undo / Redo also in the full points sheet (the app's UndoPill inSheet at „Punkte“, owner 2026-11-18 answer 4): the
// phone's sheet pulled up hides the map's pill – the same ↶ ↷ at the Points title, the one that cannot act greyed
{
  const up = await ctx.newPage();
  up.on('pageerror', (e) => errors.push(String(e)));
  await up.setViewportSize({ width: 412, height: 860 });
  // six points: a list long enough that the pulled-up sheet reaches the map's pill
  await up.goto(url + 'index.html?lang=en');
  await up.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
  const six = await up.evaluate(() => window.__engine.encode({ points: [[46.6705, 11.1605], [46.6740, 11.1560], [46.6780, 11.1520],
    [46.6820, 11.1480], [46.6860, 11.1440], [46.6890, 11.1400]], activity: 'HIKE', sac: 2, pinned: [], names: {}, title: null }));
  await up.goto(url + 'index.html?lang=en#p=' + six);
  await up.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1', null, { timeout: 120_000 });
  // a link opens the phone's sheet small: pulled up to the full points sheet
  if (await up.isHidden('#points')) await up.click('#handle');
  await up.waitForSelector('#points li .del', { state: 'visible', timeout: 10_000 });
  await up.waitForTimeout(500); // the sheet's transition
  const n = () => up.evaluate(() => document.querySelectorAll('#points li').length);
  const before = await n();
  check(await up.isHidden('#sheetUndoPill'), 'full sheet: nothing to undo – no undo at the Points title');
  await up.click('#points li:nth-child(2) .del');
  await up.waitForFunction((k) => document.querySelectorAll('#points li').length < k, before, { timeout: 15_000 }).catch(() => {});
  await up.waitForTimeout(400);
  const st = await up.evaluate(() => ({ crowded: document.body.classList.contains('undo-crowded'),
    head: document.querySelector('#pointsSection .pointshead').getBoundingClientRect().height,
    pill: document.getElementById('sheetUndoPill').getBoundingClientRect(), map: document.getElementById('undoPill').getBoundingClientRect().width }));
  console.log('sheet undo:', JSON.stringify(st));
  check(st.crowded && st.map === 0 && st.head >= 48 && st.pill.width === 96 && st.pill.height === 48,
    'full sheet: the map\'s undo pill steps back, ↶ ↷ (2 × 48 px) at the Points title instead');
  check(!(await up.isDisabled('#sheetUndo')) && await up.isDisabled('#sheetRedo'), 'full sheet: Undo active, Redo greyed');
  const after = await n();
  await up.click('#sheetUndo');
  await up.waitForFunction((k) => document.querySelectorAll('#points li').length === k, before, { timeout: 15_000 }).catch(() => {});
  check(await n() === before && after < before && !(await up.isDisabled('#sheetRedo')), `full sheet ↶: the deleted point is back (${after} → ${await n()}), Redo active`);
  await up.click('#sheetRedo');
  await up.waitForFunction((k) => document.querySelectorAll('#points li').length === k, after, { timeout: 15_000 }).catch(() => {});
  check(await n() === after, 'full sheet ↷: deleted again');
  await up.close();
}

// pace remembered across a reload (localStorage, like the app's Prefs hikePaceLevel)
await page.evaluate(() => { localStorage.setItem('onpath.hikePaceLevel', 'FIT'); localStorage.setItem('onpath.bikePaceLevel', 'FIT'); });
await page.reload();
await page.waitForSelector('#activityChip');
// the pace section needs the engine (paceReady); after a reload on a slow CI machine its start took over 20 s
await page.waitForFunction(() => /ready/.test(document.getElementById('status')?.textContent ?? '') || !!document.querySelector('#summary .big'),
  null, { timeout: 120_000 }).catch(() => {});
await page.click('#activityChip');
await page.waitForSelector('#paceRow [data-pace]', { timeout: 60_000 }).catch(() => {});
const afterReload = await page.evaluate(() => [localStorage.getItem('onpath.hikePaceLevel'), document.getElementById('paceRow')?.outerHTML.slice(0, 300), document.getElementById('status').textContent]);
check(await page.getAttribute('#paceRow [data-pace=FIT]', 'aria-checked').catch(() => null) === 'true', 'pace remembered after a reload (Fit)' + (afterReload[1] ? '' : ' – no pace section'));

// 11. a click on the empty map with 2+ points (the app's hold rule while planning, F-PLAN-15 – owner 2026-11-17 21:40 „die
// echte Routendifferenz … die kürzere gewinnt; nur fast gleich → fragen“ – PlaceAddMenu.planningHold on the real paths,
// in the engine): on the line → into that leg; else appending and inserting measured on the paths: the clearly shorter
// at once (the new goal / a stop), within 20 % of each other the small menu „Einfügen“ / „Neues Ziel“ beside a ring at
// the spot, the cheaper first and tinted; a click elsewhere adds nothing. Spots where the straight lines say otherwise
// show the paths decide (red on a page that decides on straight lines)
{
  const hp = await ctx.newPage();
  hp.on('pageerror', (e) => errors.push(String(e)));
  const LON = 11.16, A = [46.655, LON], B = [46.665, LON]; // on a meridian: „straight on“ is exact
  await hp.goto(url + 'index.html?lang=en');
  await hp.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
  const code = await hp.evaluate(([pts]) => window.__engine.encode({ points: pts, activity: 'HIKE', sac: 2, pinned: [0, 1], names: {}, title: null }), [[A, B]]);
  await hp.goto(url + 'index.html?lang=en#p=' + code);
  const done = () => hp.waitForFunction(() => document.querySelector('#summary .big') && !document.getElementById('status').textContent.includes('Planning'), null, { timeout: 120_000 });
  await done();
  const pts = async () => (await hp.evaluate(() => window.__engine.parse(location.href))).points;
  // n pins drawn and n points in the address (written a moment after the pins: the engine encodes it)
  const count = async (n) => {
    for (const end = Date.now() + 15_000; Date.now() < end; await hp.waitForTimeout(100)) {
      if (await hp.evaluate(async (k) => document.querySelectorAll('.pin').length === k
        && (await window.__engine.parse(location.href))?.points.length === k, n)) return;
    }
  };
  const still = () => hp.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 10_000 }).catch(() => {});
  const canvas = await hp.locator('#map canvas').first().boundingBox();
  // the user takes the map first (a short drag), as a person does before clicking: it cancels the link's pending
  // whole-route fit (WholeRoute.SETTLE_MS after its plan – lastMapTouch). Without it that fit could start between a
  // spot's centring and its click (CI 2909bd34: the click on the line landed elsewhere – on a note's × – and was lost)
  { const x = canvas.x + canvas.width * 0.6, y = canvas.y + canvas.height * 0.4;
    await hp.mouse.move(x, y); await hp.mouse.down(); await hp.mouse.move(x + 40, y + 20, { steps: 5 }); await hp.mouse.up(); }
  await still();
  /** The spot [lat, lon] centred on the map (its longitude exact on a whole pixel column), then clicked. */
  const clickCentred = async ([la, lo]) => {
    await hp.evaluate(([la2, lo2]) => window.__map.jumpTo({ center: [lo2, la2] }), [la, lo]);
    await still(); await hp.waitForTimeout(200);
    const q = await hp.evaluate(([la2, lo2]) => { const p = window.__map.project([lo2, la2]); return [p.x, p.y]; }, [la, lo]);
    // nothing over the spot: on a slow machine a note on the map (the load card, its ×) can stand there for a moment –
    // a click on it is a click on that note, not on the map (parity-smoke on one core: the × closed it, no point added)
    await hp.waitForFunction(([x, y]) => document.elementFromPoint(x, y) === window.__map.getCanvas(), [canvas.x + q[0], canvas.y + q[1]], { timeout: 10_000 }).catch(() => {});
    await hp.mouse.click(canvas.x + q[0], canvas.y + q[1]);
    return q;
  };
  /** The screen distance (px) of [lat, lon] from the drawn route. */
  const offRoute = (la, lo) => hp.evaluate(([la2, lo2]) => {
    const m = window.__map, s = m.project([lo2, la2]);
    const line = m.queryRenderedFeatures({ layers: ['planned-route'] }).flatMap((f) => f.geometry.type === 'LineString' ? f.geometry.coordinates : f.geometry.coordinates.flat());
    return Math.min(...line.map((c) => { const p = m.project(c); return Math.hypot(p.x - s.x, p.y - s.y); }));
  }, [la, lo]);
  const back = async () => { await hp.keyboard.press('Control+z'); await count(2); await done();
    await hp.waitForFunction(async () => (await window.__engine.parse(location.href))?.points.length === 2, null, { timeout: 10_000 }).catch(() => {}); };
  // a click right after a camera move waits for the symbol placement of this camera (settledPoint, up to 5 s on a slow
  // machine) before it is decided – a menu that is expected may take that long; after a point came in, a short look
  const asked = (ms = 8_000) => hp.waitForSelector('.holdaddpopup', { timeout: ms }).then(() => true).catch(() => false);
  const near = (p, q) => Math.abs(p[0] - q[0]) < 1e-5 && Math.abs(p[1] - q[1]) < 1e-5;
  // a clicked point is free: once its plan is there it is snapped onto the way (PlanResult, adoptSnapped) – up to ~50 m
  // here; read before or after that depending on the machine's speed (CI / a loaded laptop: after)
  const nearSnap = (p, q) => Math.hypot(p[0] - q[0], (p[1] - q[1]) * Math.cos((p[0] * Math.PI) / 180)) * 111_320 < 80;

  // on the line: a point of the drawn route → into the leg at once
  const onLine = await hp.evaluate(() => {
    const f = window.__map.queryRenderedFeatures({ layers: ['planned-route'] }).find((x) => x.geometry.type === 'LineString');
    const c = f.geometry.coordinates; return c[Math.floor(c.length / 2)];
  });
  await clickCentred([onLine[1], onLine[0]]);
  await count(3);
  const p0 = await pts(), a0 = await asked(500);
  check(!a0 && p0.length === 3 && Math.hypot(p0[1][0] - onLine[1], p0[1][1] - onLine[0]) < 1e-4, `hold rule: a click on the line goes into the leg at once (no menu) (${a0} ${JSON.stringify(p0)} ${JSON.stringify(onLine)})`);
  await hp.keyboard.press('Escape'); await back();

  const [sA, sB] = await pts();
  const plan = [sA, sB].map((p) => `${p[0].toFixed(6)},${p[1].toFixed(6)}`).join(';');
  /** The engine's answer for [spot]: on the paths (HIKE T2, the plan's) or – activity '' – on straight lines only. */
  const engineSays = (spot, act) => hp.evaluate(async ([pl, la, lo, a]) => {
    const r = JSON.parse(await window.__engine.call('holdOnMap', pl, la, lo, false, 30, a, a ? 2 : 0));
    return r.action === 'ADD' ? 'ADD' + r.index : 'ASK ' + r.main.join('/');
  }, [plan, spot[0], spot[1], act]);

  // straight on past the goal: appending clearly shorter → the new goal at once
  const ahead = [46.671, LON];
  await clickCentred(ahead);
  await count(3);
  const p1 = await pts();
  check(!(await asked(500)) && p1.length === 3 && nearSnap(p1[2], ahead) && near(p1[1], sB),
    `hold rule: a click straight on past the goal → the new goal at once, appended (${JSON.stringify(p1)})`);
  await back();

  // behind the start, beside it: on straight lines inserting is cheaper (+261 m vs 506 m – a stop), on the paths there is
  // no short way into the leg (+1.0 km vs 0.64 km) → the paths decide: the new goal at once, appended
  const behind = [46.662, LON - 0.005];
  const [bStraight, bPaths] = [await engineSays(behind, ''), await engineSays(behind, 'HIKE')];
  check(bStraight === 'ADD1' && bPaths === 'ADD2', `hold rule data: straight lines say a stop, the paths the new goal (${bStraight} / ${bPaths})`);
  await clickCentred(behind);
  await count(3);
  const p5 = await pts();
  check(!(await asked(500)) && p5.length === 3 && nearSnap(p5[2], behind) && near(p5[1], sB),
    `hold rule on the paths: where the straight lines say „stop“ but the paths „new goal“, it is appended (${JSON.stringify(p5)})`);
  await back();

  // beside the leg's middle, off the line: inserting is far cheaper → a stop at once
  let dx = 0.001;
  while (dx < 0.006 && await offRoute(46.66, LON + dx) < 40) dx += 0.0005;
  const aside = [46.66, LON + dx];
  const asideOff = await offRoute(...aside);
  await clickCentred(aside);
  await count(3);
  const p2 = await pts();
  check(asideOff >= 40 && !(await asked(500)) && p2.length === 3 && nearSnap(p2[1], aside), `hold rule: beside the leg (${Math.round(asideOff)} px off) → a stop at once (${JSON.stringify(p2)})`);
  await back();

  /** The hold menu's rows, the tinted ones, the ring and whether the menu leaves the spot free. */
  const holdMenuNow = () => hp.evaluate(() => {
    const pop = document.querySelector('.holdaddpopup');
    const rows = [...pop.querySelectorAll('.pointmenu button')];
    const ring = document.querySelector('.holdring')?.getBoundingClientRect();
    const box = pop.querySelector('.maplibregl-popup-content').getBoundingClientRect();
    const rc = ring ? [ring.left + ring.width / 2, ring.top + ring.height / 2] : null;
    return { rows: rows.map((b) => b.querySelector('.txt > span').textContent), strong: rows.map((b) => b.classList.contains('strong')),
      head: getComputedStyle(pop.querySelector('.menuhead')).display, ring: rc,
      free: !!rc && (rc[0] < box.left || rc[0] > box.right || rc[1] < box.top || rc[1] > box.bottom) };
  });

  // beside the goal: on straight lines appending is clearly shorter (+386 m vs 254 m – the new goal at once), on the paths
  // the two are near equal (+465 m vs 397 m) → the menu, „New destination“ first and tinted
  const besideGoal = [46.666, LON + 0.003];
  const [gStraight, gPaths] = [await engineSays(besideGoal, ''), await engineSays(besideGoal, 'HIKE')];
  check(gStraight === 'ADD2' && gPaths === 'ASK NEW_DESTINATION/ADD_STOP', `hold rule data: straight lines say the new goal, the paths ask (${gStraight} / ${gPaths})`);
  const q1 = await clickCentred(besideGoal);
  const m1 = await asked() ? await holdMenuNow() : null;
  check(m1 && m1.rows.join('|') === 'New destination|Insert' && m1.strong.join() === 'true,false' && m1.head === 'none',
    `hold rule on the paths: near equal detours ask – New destination first and the only tinted row, no title (${JSON.stringify(m1)})`);
  check(m1 && m1.free && Math.abs(m1.ring[0] - (canvas.x + q1[0])) < 2 && Math.abs(m1.ring[1] - (canvas.y + q1[1])) < 2,
    'hold rule: a ring marks the spot, the menu beside it (never over it)');
  // a click elsewhere: closed, nothing added
  await hp.mouse.click(canvas.x + canvas.width - 150, canvas.y + 150);
  await hp.waitForTimeout(500);
  check(await hp.locator('.holdaddpopup').count() === 0 && await hp.locator('.holdring').count() === 0 && (await pts()).length === 2,
    'hold rule: a click elsewhere closes the menu and its ring, nothing added');
  // „Insert“ from the menu: between start and goal (never behind the goal)
  await clickCentred(besideGoal);
  await asked();
  await hp.click('.holdaddpopup button:nth-of-type(2)');
  await count(3);
  const p3 = await pts();
  check(p3.length === 3 && nearSnap(p3[1], besideGoal), `hold menu: Insert puts the spot between start and goal (${JSON.stringify(p3)})`);
  await back();

  // nearer the start than the goal, far out: inserting a little shorter on the paths (+765 m vs 835 m) → the menu,
  // „Insert“ first and tinted
  const farOut = [46.663, LON + 0.00785];
  await clickCentred(farOut);
  const m2 = await asked() ? await holdMenuNow() : null;
  check(m2 && m2.rows.join('|') === 'Insert|New destination' && m2.strong.join() === 'true,false',
    `hold rule: similar detours, inserting cheaper – Insert first and tinted (${JSON.stringify(m2)})`);
  await hp.click('.holdaddpopup button:nth-of-type(2)'); // „New destination“: appended, the old goal stays
  await count(3);
  const p4 = await pts();
  check(p4.length === 3 && nearSnap(p4[2], farOut) && near(p4[1], sB), `hold menu: New destination appends, the old goal stays (${JSON.stringify(p4)})`);
  await hp.close();
}

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
