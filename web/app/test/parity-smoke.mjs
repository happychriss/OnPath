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
  const p = await page.evaluate(([lo, la]) => { const q = window.__map.project([lo, la]); return [q.x, q.y]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1], { button });
};
const menuItems = () => page.$$eval('.pointmenu button .txt > span:first-child', (b) => b.map((x) => x.textContent));

await page.goto(url + 'index.html');
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 60_000 });

// 1. first click on an empty plan: "New route" – Start here · Go here (MapGestures.FirstPointMenu)
await clickAt(11.1600, 46.6700);
await page.waitForSelector('.pointmenu');
check((await page.textContent('.menuhead')).includes('New route') && (await menuItems()).join('|') === 'Start here|Go here', 'first click: New route – Start here · Go here');
check(await page.isDisabled('#undo'), 'no undo before the first change (greyed in the toolbar)');
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
check(await page.isEnabled('#undo') && await page.isDisabled('#redo'), 'undo possible, redo greyed');
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
await clickAt(11.1500, 46.6950); await planned(); // a third point (new destination or a via, as the app decides)
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

// 6. Export GPX (the app's GpxWriter)
await planned();
await page.click('#sheetMenuBtn');
const [dl] = await Promise.all([page.waitForEvent('download'), page.click('#exportGpx')]);
const gpx = await (await import('node:fs')).promises.readFile(await dl.path(), 'utf8');
check(dl.suggestedFilename().endsWith('.gpx') && gpx.includes('<trkpt') && gpx.includes('<type>'), 'Export GPX downloads a track with the sport type');

// 6b. activity drop-down at the chip: one click picks and closes; Hike's T level in the same list
await page.click('#activityChip');
const am = await page.locator('#activityMenu').boundingBox(), chip = await page.locator('#activityChip').boundingBox();
check(await page.isVisible('#activityMenu') && Math.abs(am.y - (chip.y + chip.height)) < 12 && Math.abs(am.x - chip.x) < 12, 'activity list opens under the chip (no dialog)');
await page.click('#activityMenu [data-act=BIKE_TOURING]');
check(await page.isHidden('#activityMenu') && (await page.textContent('#activityChip')).includes('Bike'), 'picking Bike closes the list at once');
await planned();
await page.click('#activityChip');
await page.click('#activityMenu [data-sac="4"]');
check(await page.isHidden('#activityMenu') && (await page.textContent('#activityChip')).replace(/\s+/g, ' ').includes('Hike · T4'), 'a T level picks Hike T4 and closes');

// 6c. pace (the app's Settings → Pace) in the same list: a setting – the list stays open, the sheet's time follows
// the sheet shows the engine's time for these points + Hike T4 (the plans of the two picks above may still arrive)
const engineTime = () => page.evaluate(async () => { const s = window.__state; const t = await window.__engine.call('planTime', s.points.map(([a, o]) => a.toFixed(6) + ',' + o.toFixed(6)).join(';'), s.activity, s.sac); return t && t !== 'null' ? JSON.parse(t).time : null; });
const settled = async () => { for (let i = 0; i < 240; i++) { const t = await engineTime(); if (t && t === await page.textContent('#summary .big').catch(() => '')) return t; await page.waitForTimeout(500); } return null; };
await settled();
const bigTime = () => page.textContent('#summary .big');
const avgTime = await bigTime();
await page.click('#activityChip');
await page.waitForSelector('#paceRow [data-pace]', { timeout: 10_000 }).catch(() => {});
const levels = await page.$$eval('#paceRow [data-pace]', (b) => b.map((x) => x.textContent.trim()));
check(levels.join('|') === 'Relaxed|Easy|Average|Fit|Very fit' && (await page.textContent('#paceRow')).includes('Hiking') &&
  await page.getAttribute('#paceRow [data-pace=AVERAGE]', 'aria-checked') === 'true', `pace section: the app's five levels, Hiking, Average chosen (${levels.join(', ')})`);
check((await page.textContent('#paceMeaning')).includes('4 km/h · 300 m up per hour'), "pace: what Average means (the app's DIN line)");
await page.click('#activityMenu [data-pace=VERY_FIT]');
await page.waitForFunction((t) => document.querySelector('#summary .big')?.textContent !== t, avgTime, { timeout: 10_000 }).catch(() => {});
const fitTime = await bigTime();
check(await page.isVisible('#activityMenu') && fitTime === await engineTime() && fitTime !== avgTime &&
  await page.evaluate(() => localStorage.getItem('onpath.hikePaceLevel')) === 'VERY_FIT',
  `Very fit: list stays open, the time is the engine's (${avgTime} → ${fitTime}), remembered in this browser`);
await page.click('#activityMenu [data-pace=AVERAGE]');
await page.waitForFunction((t) => document.querySelector('#summary .big')?.textContent === t, avgTime, { timeout: 10_000 }).catch(() => {});
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

// 6d. Save (web-sync.md §14.6): the header's Save – not paired "Save", dark for a new or changed route, light "✓ Saved"
// when saved. Rule 5 ("as in Word"): a click opens a small field under the button with the name (the open route's, or
// the app's proposal), selected; the same name overwrites, a new name is a new route and the old one stays; Enter
// saves, Escape cancels; never a question "overwrite or new". The sheet's ⋯ → Save route opens the same field.
const saveHead = () => page.evaluate(() => { const b = document.getElementById('menuSavePlan'); return { state: b.dataset.state, text: b.textContent.trim(), disabled: b.disabled, title: b.title }; });
const fieldValue = () => page.evaluate(() => document.getElementById('savePopName')?.value ?? null);
const savedTitles = () => page.evaluate(async () => (await window.__sync.list()).map((p) => p.title).sort());
let sh = await saveHead();
check(await page.isVisible('#header #menuSavePlan') && sh.state === 'dark' && !sh.disabled && sh.text === 'Save',
  `header: "Save", dark with a new route (${JSON.stringify(sh)})`);
await page.click('#sheetMenuBtn');
check(await page.isVisible('#sheetMenu #sheetSave') && await page.isEnabled('#sheetSave') && (await page.textContent('#sheetSave')).trim() === 'Save route',
  '⋯: "Save route", active with a route (as in the app)');
await page.click('#sheetSave');
await page.waitForSelector('.savepop #savePopName');
const pop = await page.evaluate(() => { const p = document.querySelector('.savepop').getBoundingClientRect(), b = document.getElementById('menuSavePlan').getBoundingClientRect();
  return { below: p.top >= b.bottom && p.top - b.bottom < 16, dialog: !!document.querySelector('dialog[open]') }; });
check(pop.below && !pop.dialog, `⋯ → Save route: the name field opens right under the header's Save, no dialog (${JSON.stringify(pop)})`);
await page.fill('#savePopName', 'Test loop');
await page.click('#savePopGo');
await page.waitForSelector('#savedLine:not([hidden])', { timeout: 10_000 }).catch(() => {});
check(!(await fieldValue()) && (await page.textContent('#savedLine')).includes('Saved as Test loop'), 'Save closes the field; the sheet says "Saved as Test loop"');
await page.waitForFunction(() => document.getElementById('menuSavePlan').dataset.state === 'done', null, { timeout: 10_000 }).catch(() => {});
sh = await saveHead();
check(sh.state === 'done' && sh.text === 'Saved' && sh.title === 'Everything is saved in this browser', `header: saved and unchanged → light "✓ Saved" (${JSON.stringify(sh)})`);
await page.click('#menuPlans');
await page.waitForSelector('dialog.plans[open] #planList li[data-id]');
const myRow = await page.evaluate(() => { const li = document.querySelector('#planList li[data-id]');
  return { name: li.querySelector('.name').textContent, current: li.classList.contains('current'), where: [...li.querySelectorAll('.where .w')].map((w) => w.getAttribute('aria-label')).join() }; });
check(myRow.name === 'Test loop' && myRow.current && myRow.where === 'In this browser' && await page.locator('#saveCurrent').count() === 0,
  `My routes: the row "Test loop", marked as open, 🖥 – no "Save current route" box (${JSON.stringify(myRow)})`);
await page.click('#closePlans');
await page.click('#menuSavePlan'); // saved and unchanged: the field again, the same name → overwrites, no question
await page.waitForSelector('.savepop #savePopName');
check(await fieldValue() === 'Test loop', 'the field shows the open route\'s name');
await page.press('#savePopName', 'Enter');
await page.waitForTimeout(500);
check(JSON.stringify(await savedTitles()) === '["Test loop"]' && await page.locator('dialog[open]').count() === 0, 'the same name: saved over it, no question, no duplicate');
if (await page.isEnabled('#undo')) { // a change → Save dark; Escape cancels; Enter saves under the same name
  await page.click('#undo');
  await page.waitForFunction(() => /changed, not saved/.test(document.getElementById('savedLine').textContent), null, { timeout: 10_000 }).catch(() => {});
  check((await saveHead()).state === 'dark', 'header: changed since saving → dark');
  await page.click('#menuSavePlan');
  await page.waitForSelector('.savepop #savePopName');
  await page.press('#savePopName', 'Escape');
  check(await fieldValue() === null && (await saveHead()).state === 'dark', 'Escape: the field goes, nothing saved (still dark)');
  await page.click('#menuSavePlan');
  await page.waitForSelector('.savepop #savePopName');
  await page.press('#savePopName', 'Enter');
  await page.waitForFunction(() => document.getElementById('menuSavePlan').dataset.state === 'done', null, { timeout: 10_000 }).catch(() => {});
  check((await saveHead()).state === 'done' && JSON.stringify(await savedTitles()) === '["Test loop"]', 'Enter with the same name: the changed route saved over "Test loop"');
  await page.click('#redo');
  await page.waitForFunction(() => document.getElementById('menuSavePlan').dataset.state === 'dark', null, { timeout: 10_000 }).catch(() => {});
  const before = await page.evaluate(async () => (await window.__sync.list()).find((p) => p.title === 'Test loop').plan);
  await page.click('#menuSavePlan');
  await page.waitForSelector('.savepop #savePopName');
  await page.fill('#savePopName', 'Test loop B');
  await page.press('#savePopName', 'Enter');
  await page.waitForFunction(() => /Saved as Test loop B/.test(document.getElementById('savedLine').textContent), null, { timeout: 10_000 }).catch(() => {});
  const after = await page.evaluate(async () => (await window.__sync.list()).find((p) => p.title === 'Test loop')?.plan);
  check(JSON.stringify(await savedTitles()) === '["Test loop","Test loop B"]' && after === before && (await page.textContent('#savedLine')).includes('Saved as Test loop B'),
    'a new name ("Save as"): a second route, "Test loop" unchanged, the new one is open');
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
    const p = m.project(c[Math.floor(c.length / 2)]), r = m.getContainer().getBoundingClientRect(); return [p.x + r.left, p.y + r.top]; });
  check(line && await cursor(line[0], line[1]) === 'pointer', 'cursor: pointer over the route line');
  await page.evaluate(() => window.__map.getSource('pois').setData({ type: 'FeatureCollection', features: [] }));
}

// 7. Go here from my position (browser location)
await page.click('#sheetMenuBtn'); await page.click('#clear'); await waitPins(0);
await clickAt(11.1350, 46.6840);
await page.waitForSelector('.pointmenu');
await page.click('.pointmenu button:nth-of-type(2)'); // Go here
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
check(await page.isVisible('#warning') && /ford/.test(wl) && !/steep/.test(wl), 'route warning line (ford, no steep for MTB) shown');
await page.click('#warning');
check(await page.locator('#spots li').count() > 0, 'click → the spots on this route');

// 10. phone width: bottom sheet; the handle shows the Points
await page.setViewportSize({ width: 412, height: 860 });
// the sheet moves to the bottom with a transition – wait until it has settled (CI: once read mid-way after 300 ms)
await page.waitForFunction(() => { const r = document.getElementById('sheet').getBoundingClientRect(); return r.top > 300 && r.bottom >= 855; },
  null, { timeout: 5000 }).catch(() => {});
const sheet = await page.locator('#sheet').boundingBox();
check(sheet.y > 300 && sheet.y + sheet.height >= 855, `phone: the plan sheet sits at the bottom (top ${Math.round(sheet.y)}, bottom ${Math.round(sheet.y + sheet.height)})`);
check(await page.isVisible('#menuBtn') && await page.isHidden('#menuPlans'), 'phone: header entries behind ☰');
const ptb = await page.locator('#toolbar').boundingBox(), psb = await page.locator('#searchbox').boundingBox(), pbar = await page.locator('#topbar').boundingBox();
check(ptb.y >= psb.y + psb.height - 1 && ptb.y + ptb.height <= pbar.y + pbar.height + 1 && Math.abs(psb.width - pbar.width) < 2,
  'phone: search full width, the tools as a row below it in the same bar');
check((await Promise.all(['#undo', '#redo', '#eye', '#compass', '#locate', '#mapContent'].map((s) => page.isVisible(s)))).every(Boolean),
  'phone: all tools visible (none behind a menu)');
await page.click('#menuBtn');
check(await page.isVisible('#menuPlans'), 'phone: ☰ shows the entries');
await page.keyboard.press('Escape');
check(await page.isHidden('#points'), 'phone: Points folded away');
await page.click('#handle');
check(await page.isVisible('#points'), 'phone: the handle shows the Points');

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

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
