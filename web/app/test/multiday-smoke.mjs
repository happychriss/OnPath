// Multi-day tours in the planner (docs/design/multi-day-tours.md §3, stages 2 + 3; session „mehrtage“ 2026-11-17):
// Mehrtagestour planen → GPX-Track laden → trim (the profile's start bar) → Route daraus planen → Noch etwas anhängen →
// GPX-Track laden → An die Route anhängen (+ undo) → Weiter: Tage festlegen (limits propose N, + / −) → Fertig →
// the overview with grey moons → a tap on the route → Übernachtung hier → Hotel hinterlegen (a pasted Google Maps link)
// → Hotel entfernen → N − / + (− not possible without a grey one) → a day row highlights → Speichern → the saved code
// carries the days and the night. Needs only the rd5 (E10_N45). Usage: node test/multiday-smoke.mjs [segmentsDir] [shotsDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const shots = process.argv[3] ? path.resolve(process.argv[3]) : null;
const gpx = path.join(here, 'fixtures', 'komoot-hike.gpx');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };

async function run(lang, viewport, shotPrefix) {
  const ctx = await browser.newContext({ viewport, locale: lang === 'de' ? 'de-DE' : 'en-US' });
  await ctx.route('https://photon.komoot.io/**', (r) => r.fulfill({ contentType: 'application/json', body: '{"type":"FeatureCollection","features":[]}' }));
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource|\[Problems\] (INFO|TRACE)/.test(m.text())) errors.push(m.text()); });
  const shot = async (name) => { if (shots && shotPrefix) await page.screenshot({ path: path.join(shots, `${shotPrefix}-${name}.png`) }); };
  const settled = () => page.waitForFunction(() => document.body.dataset.planning !== '1' && !!document.querySelector('#summary .big'), null, { timeout: 120_000 });
  const pins = () => page.evaluate(() => document.querySelectorAll('.pin').length);
  const view = () => page.evaluate(() => { const v = window.__days.lastView; return v ? { days: v.days, ends: v.ends.map((e) => e.kind), canRemove: v.canRemove } : null; });
  const waitView = (f, what) => page.waitForFunction(f, null, { timeout: 60_000 }).catch(() => check(false, 'waited for ' + what));
  const clickLngLat = async (lon, lat) => {
    const p = await page.evaluate(([lo, la]) => { const r = window.__map.getCanvas().getBoundingClientRect();
      const q = window.__map.project([lo, la]); return [q.x + r.left, q.y + r.top]; }, [lon, lat]);
    await page.mouse.click(p[0], p[1]);
  };
  const menuItem = async (label) => { await page.waitForSelector('.holdpopup .pointmenu', { timeout: 10_000 });
    await page.locator('.holdpopup .pointmenu button', { hasText: label }).first().click(); };

  await page.goto(url + `index.html?lang=${lang}&waitMs=0`);
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
  const T = await page.evaluate(() => window.__texts ?? null);

  // 1 · the way in
  await page.click(viewport.width <= 700 ? '#menuBtn' : '#menuMultiDay');
  if (viewport.width <= 700) await page.click('#menuMultiDay');
  await page.waitForSelector('#tourGuide:not([hidden])');
  check(await page.$eval('#tgNext', (b) => b.disabled), 'the guide: step 2 grey until there is a route');
  await shot('1-way-in');

  // 2 · GPX-Track laden → trim → Route daraus planen
  const [fc] = await Promise.all([page.waitForEvent('filechooser'), page.click('#tgGpx')]);
  await fc.setFiles(gpx);
  await page.waitForSelector('#trackPanel .trimbar.from');
  check(await page.locator('.trimhandle').count() === 2, 'two trim handles on the track line');
  const bar = await page.locator('#trackPanel .trimbar.from').boundingBox();
  const plot = await page.locator('#trackPanel .profplot').boundingBox();
  await page.mouse.move(bar.x + bar.width / 2, bar.y + bar.height / 2);
  await page.mouse.down();
  await page.mouse.move(plot.x + plot.width * 0.2, bar.y + bar.height / 2, { steps: 5 });
  await page.mouse.up();
  const trim = await page.evaluate(() => window.__trim);
  check(trim && trim.from > 0.1 * trim.total && trim.to === trim.total, `the profile's start bar trims the track (${trim && Math.round(trim.from)} of ${trim && Math.round(trim.total)} m)`);
  await page.click('#planFromTrack');
  await settled();
  await page.waitForFunction(() => !document.getElementById('tgNext')?.disabled, null, { timeout: 60_000 });
  check(await page.$eval('#tourGuide', (g) => !g.hidden && !!g.querySelector('.tgnum.done')), 'step 1 shows the route with ✓, step 2 active');
  const firstPts = await pins();

  // 3 · Noch etwas anhängen → GPX-Track laden → An die Route anhängen; undo takes it back
  const [fc2] = await Promise.all([page.waitForEvent('filechooser'), page.click('#tgGpxMore')]);
  await fc2.setFiles(gpx);
  await page.waitForSelector('#appendTrack');
  await page.click('#appendTrack');
  await page.waitForFunction((n) => document.querySelectorAll('.pin').length > n, firstPts, { timeout: 120_000 });
  await settled();
  check(await pins() > firstPts, `appended: ${firstPts} → ${await pins()} points`);
  await page.click('#undo');
  await page.waitForFunction((n) => document.querySelectorAll('.pin').length === n, firstPts, { timeout: 30_000 });
  await settled();
  check(await pins() === firstPts, 'undo takes the append back');

  // 4 · Weiter: Tage festlegen → the days box
  await page.waitForFunction(() => !document.getElementById('tgNext')?.disabled, null, { timeout: 60_000 });
  await page.click('#tgNext');
  await page.waitForSelector('dialog.daysbox[open]');
  await page.fill('#dbHours', '0.5');
  await page.waitForFunction(() => /\d/.test(document.getElementById('dbSuggest')?.textContent ?? ''), null, { timeout: 20_000 }).catch(() => {});
  const sug = await page.textContent('#dbSuggest');
  check(/\d/.test(sug), `the limits propose the days: "${sug}"`);
  await page.fill('#dbHours', '');
  await page.waitForFunction(() => document.getElementById('dbPer')?.textContent?.includes('≈'), null, { timeout: 20_000 });
  // three days by hand
  for (let k = 0; k < 4 && !/^3\b/.test(await page.textContent('#dbN')); k++) await page.click(/^[12]\b/.test(await page.textContent('#dbN')) ? '#dbPlus' : '#dbMinus');
  check(/^3\b/.test(await page.textContent('#dbN')), 'N set to 3 with − / +');
  await page.waitForFunction(() => document.getElementById('dbPer')?.textContent?.includes('≈'), null, { timeout: 20_000 });
  await shot('2-days-box');
  await page.click('#dbDone');
  await waitView(() => window.__days.lastView?.days === 3, 'the overview with 3 days');
  check(await page.locator('#tourOverview .today').count() === 3, 'the overview: one row per day');
  check(await page.locator('.dayend.proposal').count() === 2, 'two grey moons on the map');
  // the day rows (owner 2026-11-21, variant A): bold „Tag 1“, the day's km · ↑ m · time on its right, a grey „von – nach“
  // under it only when both places are known – never a km position, never „Start“ / „Ziel“, no third line
  const dayRows = () => page.$$eval('#tourOverview .today', (ls) => ls.map((l) => ({ day: l.querySelector('.dn')?.textContent ?? '',
    vals: l.querySelector('.dv')?.textContent ?? '', places: l.querySelector('.dpl')?.textContent ?? null, lines: l.querySelectorAll('.txt > span').length })));
  const rowsNow = await dayRows();
  console.log('day rows:', JSON.stringify(rowsNow));
  check(rowsNow.map((r) => r.day).join('|') === (lang === 'de' ? 'Tag 1|Tag 2|Tag 3' : 'Day 1|Day 2|Day 3'), `the rows say „${lang === 'de' ? 'Tag' : 'Day'} n“ (${rowsNow.map((r) => r.day).join('|')})`);
  check(rowsNow.every((r) => /^[\d.,]+ k?m · ↑ [\d.,]+ m · .+/.test(r.vals)), `each row: km · ↑ m · time (${rowsNow.map((r) => r.vals).join(' | ')})`);
  check(rowsNow.every((r) => r.lines <= 2 && (r.places == null || (/ – /.test(r.places) && !/\bkm \d|^(Start|Ziel|Destination) –|– (Start|Ziel|Destination)$/.test(r.places)))),
    'at most the grey places line under it, never a km position or Start / Ziel');
  // each day end says which day of how many ends there (owner 2026-11-20): „Ende Tag 1 von 3“, „Ende Tag 2 von 3“
  const endDays = (sel) => page.$$eval(sel, (els) => els.map((e) => {
    const m = /(\d+)\D+(\d+)/.exec(e.title ?? ''); return m && (e.getAttribute('aria-label') ?? '').includes(m[0]) ? [Number(m[1]), Number(m[2])] : null; }));
  const greyDays = await endDays('.dayend.proposal');
  check(JSON.stringify(greyDays) === '[[1,3],[2,3]]', `the grey moons' title / aria-label: end of day 1 and 2 of 3 (${JSON.stringify(greyDays)})`);
  const word = lang === 'de' ? 'Ende Tag 1 von 3' : 'End of day 1 of 3';
  check(await page.$eval('.dayend.proposal', (e, w) => e.title.startsWith(w), word), `the words: „${word}“`);
  check(await page.$eval('#tourGuide', (g) => g.hidden), 'the guide closes with Fertig');
  await shot('3-overview');

  // 5 · a tap on the route → Übernachtung hier (R1: N stays, a grey one goes)
  if (viewport.width <= 700) { // a phone: the sheet small, the whole route above it
    for (let k = 0; k < 3 && await page.$eval('#sheet', (s) => s.dataset.step) !== 'small'; k++) await page.click('#sheetStep');
  }
  await page.click('#fitRoute');
  await page.waitForTimeout(1200);
  const spot = await page.evaluate(() => { const pts = window.__days.h.routedPlan().legs.flatMap((l) => l.points); return pts[Math.floor(pts.length * 0.48)]; });
  // the night lies ON the route (owner 2026-11-19): nothing routed – the line, distance, climb and time stay the same,
  // only a day border more, where the click was
  const lineOf = () => page.evaluate(() => { const p = window.__engine.lastPlan;
    const line = p.legs.flatMap((l) => l.points).filter((q, k, a) => !k || q[0] !== a[k - 1][0] || q[1] !== a[k - 1][1]);
    return { d: p.distanceM, up: p.ascentM, down: p.descentM, t: p.timeS, line: JSON.stringify(line), totalM: window.__days.lastView?.totalM }; });
  const onLine = await lineOf();
  const pointsBefore = await page.evaluate(() => window.__state.points.length);
  await clickLngLat(spot[1], spot[0]);
  await menuItem(lang === 'de' ? 'Übernachtung hier' : 'Overnight stop here');
  await waitView(() => window.__days.lastView?.ends.some((e) => e.kind === 'OVERNIGHT'), 'a blue moon');
  await settled();
  let v = await view();
  check(v.days === 3 && v.ends.filter((k) => k === 'PROPOSAL').length === 1, `Übernachtung hier: N stays 3, one grey left (${v.ends})`);
  check(await page.locator('.pin.night .daysym.blue').count() === 1, 'the night point is drawn as a blue moon');
  await page.waitForFunction(() => /\d/.test(document.querySelector('.pin.night')?.getAttribute('aria-label') ?? ''), null, { timeout: 10_000 }).catch(() => {});
  const blueAt = v.ends.indexOf('OVERNIGHT') + 1;
  const dayEnds = [...await endDays('.dayend.proposal'), ...await endDays('.pin.night')].map((x) => x && x[0]).sort();
  check(JSON.stringify((await endDays('.pin.night'))) === JSON.stringify([[blueAt, 3]]) && JSON.stringify(dayEnds) === '[1,2]',
    `the blue moon says its day too: end of day ${blueAt} of 3 (${JSON.stringify(dayEnds)})`);
  if (viewport.width > 700) { // the grey one's click: the same words in the menu's head (a phone: the tap)
    await page.click('.dayend.proposal');
    await page.waitForSelector('.holdpopup .menuhead', { timeout: 10_000 });
    const head = await page.textContent('.holdpopup .menuhead');
    check(head.includes(lang === 'de' ? 'von 3' : 'of 3'), `the grey moon's menu head says the day: "${head}"`);
    // a grey moon not wanted (owner 2026-11-20: one left between two blue ones): „Ein Tag weniger“ right there (R5)
    const greyRows = await page.$$eval('.holdpopup .pointmenu button', (bs) => bs.map((b) => b.textContent.trim()));
    check(greyRows.some((r) => r.includes(lang === 'de' ? 'Ein Tag weniger' : 'One day fewer')), `the grey moon's menu offers one day fewer (${greyRows.join(' | ')})`);
    await shot('3b-grey-menu');
    await page.keyboard.press('Escape');
  }
  const split = await lineOf();
  const legs5 = await page.evaluate(() => window.__engine.lastPlanStats?.legs);
  check(await page.evaluate(() => window.__state.points.length) === pointsBefore + 1 && legs5?.routed === 0,
    `a night on the route: a point more, no leg routed (${JSON.stringify(legs5)})`);
  check(split.d === onLine.d && split.up === onLine.up && split.down === onLine.down && split.t === onLine.t && split.line === onLine.line
    && split.totalM === onLine.totalM, `the same route: ${Math.round(onLine.d)} m ↑${Math.round(onLine.up)} → ${Math.round(split.d)} m ↑${Math.round(split.up)}, the same line`);
  const along = await page.evaluate(([la, lo]) => { const line = window.__engine.lastPlan.legs.flatMap((l) => l.points);
    const m = (a, b) => { const r = Math.PI / 180, x = (b[1] - a[1]) * r * Math.cos(((a[0] + b[0]) / 2) * r), y = (b[0] - a[0]) * r; return Math.sqrt(x * x + y * y) * 6371000; };
    let s = 0; for (let k = 1; k < line.length; k++) { if (line[k - 1][0] === la && line[k - 1][1] === lo) return s; s += m(line[k - 1], line[k]); } return -1; }, [spot[0], spot[1]]);
  const nightAt = await page.evaluate(() => window.__days.lastView.ends.find((e) => e.kind === 'OVERNIGHT').alongM);
  check(along >= 0 && Math.abs(nightAt - along) < 30, `the day border is where the click was (${Math.round(nightAt)} m, the spot ${Math.round(along)} m)`);

  // 6 · Hotel hinterlegen with a pasted Google Maps link (R3 → green bed, the route leads there)
  const night = await page.evaluate(() => { const e = window.__days.lastView.ends.find((x) => x.kind === 'OVERNIGHT'); return [e.lat, e.lon]; });
  await page.click('.pin.night');
  await menuItem(lang === 'de' ? 'Hotel hinterlegen' : 'Add hotel');
  await page.waitForSelector('dialog.hoteldlg[open]');
  await page.fill('#hotelQ', `Gasthof Test\nhttps://www.google.com/maps/place/Gasthof+Test/@${(night[0] + 0.0015).toFixed(5)},${(night[1] + 0.001).toFixed(5)},17z`);
  await page.waitForSelector('#hotelRes button', { timeout: 10_000 });
  await page.click('#hotelRes button');
  await waitView(() => window.__days.lastView?.ends.some((e) => e.kind === 'HOTEL'), 'a green bed');
  await settled();
  check(await page.locator('.pin.night .daysym.green').count() === 1, 'Hotel: the night is a green bed');
  check(await page.evaluate(() => Object.values(window.__days.h.tour().nights)[0]?.hotel?.name === 'Gasthof Test'), 'the hotel keeps its name');
  const hotelRows = await dayRows();
  console.log('day rows with the hotel:', JSON.stringify(hotelRows));
  check(hotelRows.every((r) => r.places == null || r.places.split(' – ').length === 2), 'the hotel day: its places line is „von – nach“ or none');

  // 7 · Hotel entfernen (R4: the night stays, blue)
  await page.click('.pin.night');
  await menuItem(lang === 'de' ? 'Hotel entfernen' : 'Remove hotel');
  await waitView(() => window.__days.lastView?.ends.some((e) => e.kind === 'OVERNIGHT') && !window.__days.lastView?.ends.some((e) => e.kind === 'HOTEL'), 'blue again');
  check(await page.locator('.pin.night .daysym.blue').count() === 1, 'Hotel entfernen: the night stays as a blue moon');

  // 8 · N − / + (R5): − takes the last grey one, then − is not possible
  await settled();
  if (viewport.width <= 700) { // the overview is in the full sheet
    for (let k = 0; k < 3 && await page.$eval('#sheet', (s) => s.dataset.step) !== 'points'; k++) await page.click('#sheetStep');
  }
  await page.click('#toMinus');
  await waitView(() => window.__days.lastView?.days === 2, '2 days');
  v = await view();
  check(v.days === 2 && !v.canRemove && await page.$eval('#toMinus', (b) => b.disabled), 'N − : 2 days, − greyed (no grey moon left)');
  await page.click('#toPlus');
  await waitView(() => window.__days.lastView?.days === 3, '3 days again');
  check((await view()).days === 3, 'N + : 3 days again');

  // 9 · a day row highlights that day
  await page.click('#tourOverview .today[data-day="2"]');
  const range = await page.evaluate(() => window.__profileRange ?? null);
  check(await page.locator('#tourOverview .today.sel').count() === 1, 'a day row is selected (its part marked on the map and in the profile)');
  void range;

  // 10 · Speichern as every plan: the saved code carries the days and the night
  await page.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'dark', null, { timeout: 30_000 }).catch(() => {});
  if (viewport.width <= 700) await page.click('#menuBtn'); // a phone: Save is in Plan ▾
  await page.click('#menuSavePlan');
  // the first Save of a new route asks for its name (the proposal is there)
  await page.waitForSelector('dialog.savename[open] #saveOk', { timeout: 10_000 }).then(async () => { await page.waitForTimeout(1000); await page.click('dialog.savename #saveOk'); }).catch(() => {});
  await page.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'done', null, { timeout: 30_000 }).catch(() => {});
  check(await page.$eval('#menuSavePlan', (b) => b.dataset.state) === 'done', 'Speichern: saved');
  const back = await page.evaluate(() => window.__engine.parse(location.href));
  check(back?.tour?.days === 3 && Object.keys(back.tour.nights).length === 1, `the plan in the address carries the tour (${JSON.stringify(back?.tour)?.slice(0, 80)})`);
  // a night change after Save asks for Save again
  await page.click('#toPlus');
  await page.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'dark', null, { timeout: 30_000 }).catch(() => {});
  check(await page.$eval('#menuSavePlan', (b) => b.dataset.state) === 'dark', 'a day more after Save: Speichern again');
  await page.evaluate(() => document.getElementById('undo').click()); // (a phone: the full sheet may cover the undo pill)
  await waitView(() => window.__days.lastView?.days === 3, 'undo of N +');
  check((await view()).days === 3, 'undo takes N + back');

  // 11 · a normal point's menu (owner 2026-11-18): „Übernachtung hier“ first – not on the start; the point turns blue
  await page.keyboard.press('Escape');
  if (viewport.width <= 700) {
    for (let k = 0; k < 3 && await page.$eval('#sheet', (s) => s.dataset.step) !== 'small'; k++) await page.click('#sheetStep');
  }
  const overnight = lang === 'de' ? 'Übernachtung hier' : 'Overnight stop here';
  const menuRows = () => page.$$eval('.holdpopup .pointmenu button', (bs) => bs.map((b) => b.textContent.trim()));
  const pressPin = (k) => page.evaluate((k) => document.querySelectorAll('.pin')[k].dispatchEvent(new MouseEvent('click', { bubbles: true })), k);
  await pressPin(0);
  await page.waitForSelector('.holdpopup .pointmenu', { timeout: 10_000 });
  check(!(await menuRows()).some((r) => r.includes(overnight)), 'the start point\'s menu has no „Übernachtung hier“');
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => !document.querySelector('.holdpopup .pointmenu'), null, { timeout: 5_000 }).catch(() => {});
  // a plan point in the middle that is no night yet: one put in on the route (a click on it → Einfügen)
  if (await page.evaluate(() => document.querySelectorAll('.pin:not(.night)').length) < 3) {
    const n0 = await pins();
    await page.click('#fitRoute');
    await page.waitForTimeout(1200);
    const at = await page.evaluate(() => { const pts = window.__days.h.routedPlan().legs.flatMap((l) => l.points); return pts[Math.floor(pts.length * 0.8)]; });
    await clickLngLat(at[1], at[0]);
    await menuItem(lang === 'de' ? 'Einfügen' : 'Insert');
    await page.waitForFunction((n) => document.querySelectorAll('.pin').length > n, n0, { timeout: 30_000 }).catch(() => {});
    await settled();
    await waitView(() => window.__days.lastView?.ends.length === 2, 'the overview after Einfügen');
  }
  const mid = await page.evaluate(() => { const ps = [...document.querySelectorAll('.pin')];
    const cands = ps.map((p, k) => [p, k]).filter(([p, k]) => k > 0 && k < ps.length - 1 && !p.classList.contains('night'));
    return cands[Math.floor(cands.length / 2)][1]; });
  await pressPin(mid);
  await page.waitForSelector('.holdpopup .pointmenu', { timeout: 10_000 });
  const rows = await menuRows();
  check(rows[0]?.includes(overnight), `a normal point's menu starts with „Übernachtung hier“ (${rows.join(' | ')})`);
  if (viewport.width > 700) await shot('4-point-menu');
  await menuItem(overnight);
  await waitView(() => window.__days.lastView?.ends.filter((e) => e.kind === 'OVERNIGHT').length === 2, 'a second blue moon');
  v = await view();
  check(v.days === 3 && v.ends.filter((k) => k === 'OVERNIGHT').length === 2 && !v.ends.includes('PROPOSAL'),
    `the point is an Übernachtung: N stays 3, the grey one goes (${v.ends})`);
  check(await page.locator('.pin.night .daysym.blue').count() === 2, 'the point is drawn as a blue moon');
  // the grey one goes from the map too (owner 2026-11-20: a grey moon left between two blue ones) – no stale marker
  check(await page.locator('.dayend.proposal').count() === 0, 'no grey moon left on the map');
  await page.evaluate(() => document.getElementById('undo').click());
  await waitView(() => window.__days.lastView?.ends.filter((e) => e.kind === 'OVERNIGHT').length === 1, 'undo of the point\'s night');
  await settled();

  // 12 · a far point in a multi-day tour (owner 2026-11-18): no „This route is very long“ question; the wait window
  // (form A, owner 2026-11-19) shows ONE bar 0–100 over the legs, never back, at most 3 rows, and Cancel; undo takes the point back
  const before = await pins();
  const last = await page.evaluate(() => window.__state.points.at(-1));
  let far = null;
  for (const d of [1, 2, 3]) {
    const pts = [...(await page.evaluate(() => window.__state.points)), [last[0] + d, last[1]]];
    const enc = pts.map(([a, b]) => `${a.toFixed(6)},${b.toFixed(6)}`).join(';');
    const ask = await page.evaluate(([e]) => window.__engine.call('longRoute', e, window.__state.activity, false), [enc]);
    if (JSON.parse(ask).ask) { far = [last[0] + d, last[1]]; break; }
  }
  check(!!far, 'a far point a normal plan would be asked about');
  await page.evaluate(() => { window.__progSeen = []; new MutationObserver(() => { const w = document.getElementById('waitwin');
    // each window its own session (the last wait's full bar may still show for a moment when the next begins)
    if (w && !w.dataset.sess) w.dataset.sess = String((window.__wwSess = (window.__wwSess ?? 0) + 1));
    const s = w && `${w.dataset.pct}|${w.querySelectorAll('.wwsteps li').length}|${w.dataset.sess}`;
    if (s && window.__progSeen.at(-1) !== s) window.__progSeen.push(s); }).observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true }); });
  await page.evaluate(([la, lo]) => window.__map.jumpTo({ center: [lo, (la + window.__state.points[0][0]) / 2], zoom: 6.5 }), far);
  await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 10_000 }).catch(() => {});
  await clickLngLat(far[1], far[0]);
  await page.waitForSelector('dialog.longroute, .holdaddpopup button, #waitwin', { timeout: 30_000 }).catch(() => {});
  if (await page.locator('.holdaddpopup').count()) await page.click('.holdaddpopup button:first-of-type');
  await page.waitForSelector('dialog.longroute, #waitwin', { timeout: 60_000 }).catch(() => {});
  check(!(await page.$('dialog.longroute')), 'a far point in a multi-day tour: no long-route question');
  const t0 = Date.now();
  if (viewport.width > 700) await shot('5-wait-box');
  check(await page.locator('#waitwin .wwbar i').count() === 1, 'the wait window: one bar');
  check(await page.locator('#waitwin .wwcancel', { hasText: lang === 'de' ? 'Abbrechen' : 'Cancel' }).count() === 1, 'the wait window keeps Cancel');
  await settled();
  const waited = Date.now() - t0;
  // what the window said while it waited: its percent never back, never more than 3 rows, moving while it waits
  const seen = (await page.evaluate(() => window.__progSeen)).map((s) => s.split('|').map(Number));
  console.log('wait window seen (percent|rows|window):', seen.slice(0, 12).map((x) => x.join('|')).join(' '), `(${waited} ms)`);
  check(seen.length > 0, 'the wait window showed');
  check(seen.every(([p, , w], k) => !k || w !== seen[k - 1][2] || p >= seen[k - 1][0]), 'its percent never goes back (within one window)');
  check(seen.every(([, r]) => r <= 3), 'never more than 3 step rows');
  check(waited < 3000 || new Set(seen.map(([p]) => p)).size > 1, `the percent moves while it waits (${seen.map(([p]) => p).join(',')})`);
  // back as before: undo the far point
  await page.evaluate(() => document.getElementById('undo').click());
  await page.waitForFunction((n) => document.querySelectorAll('.pin').length === n, before, { timeout: 30_000 }).catch(() => {});
  check(await pins() === before, 'undo takes the far point back');

  // 13 · Meine Touren (app 73e83067, its TourCard): a multi-day row's facts start with „3 Tage · “ – the saved route,
  // then (a day more, unsaved, + Neu) the Zuletzt draft with „4 Tage · “
  const daysRe = (n) => new RegExp('^' + n + (lang === 'de' ? ' Tage' : ' days') + '( · |$)');
  const openPlans = async () => {
    if (viewport.width <= 700) await page.click('#menuBtn');
    await page.click('#menuPlans');
    await page.waitForSelector('dialog.plans[open] #planList li', { timeout: 60_000 });
    await page.waitForTimeout(300);
  };
  const statsOf = (sel) => page.$$eval(sel, (ls) => ls.map((l) => l.querySelector('.stats')?.textContent ?? ''));
  await openPlans();
  // (the imported GPX tracks are saved routes too – one day, no „N Tage“)
  const savedStats = await statsOf('#planList li.saved:not([data-zuletzt])');
  check(savedStats.filter((s) => daysRe(3).test(s)).length === 1 && savedStats.filter((s) => !daysRe(3).test(s)).every((s) => !/ (Tage|days)/.test(s)), `the saved multi-day route's row: „3 ${lang === 'de' ? 'Tage' : 'days'} · …“ (${savedStats.join(' | ')})`);
  if (viewport.width > 700) await shot('6-my-routes');
  await page.click('#closePlans');
  if (viewport.width <= 700) { // the overview is in the full sheet (step 11 left it small)
    for (let k = 0; k < 3 && await page.$eval('#sheet', (s) => s.dataset.step) !== 'points'; k++) await page.click('#sheetStep');
  }
  await page.click('#toPlus');
  await waitView(() => window.__days.lastView?.days === 4, '4 days before + New');
  if (viewport.width <= 700) await page.click('#menuBtn'); // a phone: Neu is in Plan ▾
  await page.click('#newPlan');
  await page.waitForFunction(() => document.querySelectorAll('.pin').length === 0, null, { timeout: 10_000 }).catch(() => {});
  await page.waitForTimeout(300);
  await openPlans();
  const draftStats = await statsOf('#planList li.draft');
  check(draftStats.length === 1 && daysRe(4).test(draftStats[0]), `the Zuletzt draft of a multi-day tour: „4 ${lang === 'de' ? 'Tage' : 'days'} · …“ (${draftStats.join(' | ')})`);
  await page.click('#closePlans');

  check(errors.length === 0, `no page errors (${errors.slice(0, 6).join(' | ')})`);
  void T;
  await ctx.close();
}

try {
  await run('en', { width: 1280, height: 800 }, null);
  if (shots) {
    fs.mkdirSync(shots, { recursive: true });
    await run('de', { width: 1440, height: 900 }, 'desktop-de');
    await run('de', { width: 390, height: 844 }, 'narrow-de');
  }
} finally {
  await browser.close();
  server.close();
}
console.log(fails.length ? `\n${fails.length} FAILED` : '\nall ok');
process.exit(fails.length ? 1 : 0);
