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
  check(await page.$eval('#tourGuide', (g) => g.hidden), 'the guide closes with Fertig');
  await shot('3-overview');

  // 5 · a tap on the route → Übernachtung hier (R1: N stays, a grey one goes)
  if (viewport.width <= 700) { // a phone: the sheet small, the whole route above it
    for (let k = 0; k < 3 && await page.$eval('#sheet', (s) => s.dataset.step) !== 'small'; k++) await page.click('#sheetStep');
  }
  await page.click('#fitRoute');
  await page.waitForTimeout(1200);
  const spot = await page.evaluate(() => { const pts = window.__days.h.routedPlan().legs.flatMap((l) => l.points); return pts[Math.floor(pts.length * 0.48)]; });
  await clickLngLat(spot[1], spot[0]);
  await menuItem(lang === 'de' ? 'Übernachtung hier' : 'Overnight stop here');
  await waitView(() => window.__days.lastView?.ends.some((e) => e.kind === 'OVERNIGHT'), 'a blue moon');
  let v = await view();
  check(v.days === 3 && v.ends.filter((k) => k === 'PROPOSAL').length === 1, `Übernachtung hier: N stays 3, one grey left (${v.ends})`);
  check(await page.locator('.pin.night .daysym.blue').count() === 1, 'the night point is drawn as a blue moon');

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
