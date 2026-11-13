// Tour suggestions framework (src/suggest/, docs/design/tour-suggestions.md "Planner framework") in headless Chromium.
// (a) switch OFF (the default): the first-tap menu has its two rows, nothing of the feature is on the page, no storage
//     key is written;  (b) switch ON (?suggest=1 on 127.0.0.1) with the in-browser stand-in mechanism `ring`:
//     "Rundtour von hier" → the wish → Abbrechen restores the empty plan exactly; OK → cards; a second request
//     supersedes the first; Übernehmen → a normal round-trip plan that can be undone, shared and saved; a failing
//     mechanism → one calm message, the plan untouched;  (c) phone width: the wish is a window with Abbrechen · OK.
// Usage: node test/suggest-smoke.mjs [segmentsDir] [mapStoreDir] [screenshotDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = process.argv[3] && process.argv[3] !== '-' ? path.resolve(process.argv[3]) : null;
const shots = process.argv[4] ? path.resolve(process.argv[4]) : null;
if (shots) fs.mkdirSync(shots, { recursive: true });
const log = [];
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir, log });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
const START = [11.1600, 46.6700]; // Meran

async function open(query, viewport = { width: 1280, height: 800 }) {
  const ctx = await browser.newContext({ viewport });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(url + 'index.html' + query);
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
  await page.waitForTimeout(500);
  return page;
}
const clickAt = async (page, lon, lat) => {
  const p = await page.evaluate(([lo, la]) => { const q = window.__map.project([lo, la]); const r = window.__map.getCanvas().getBoundingClientRect(); return [q.x + r.left, q.y + r.top]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1]);
};
/** A free spot of the map (not under the sheet): the map's centre, moved right of the sheet on a wide page. */
const freeSpot = (page) => page.evaluate(() => { const m = window.__map, c = m.getCanvas().getBoundingClientRect(), s = document.getElementById('sheet').getBoundingClientRect();
  const wide = !matchMedia('(max-width: 700px)').matches;
  const x = wide ? (s.right + c.right) / 2 : c.left + c.width / 2, y = wide ? c.top + c.height / 2 : c.top + (s.top - c.top) * 0.55;
  const ll = m.unproject([x - c.left, y - c.top]); return [ll.lng, ll.lat]; });
const menuRows = (page) => page.$$eval('.pointmenu button', (bs) => bs.map((b) => b.querySelector('.txt > span')?.textContent ?? ''));
const points = (page) => page.evaluate(() => window.__state.points.length);
const suggestKeys = (page) => page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('onpath.suggest')));
const shot = async (page, name) => { if (shots) await page.screenshot({ path: path.join(shots, name + '.png') }); };

// ---- (a) the switch is off ----
{
  const page = await open('?lang=de');
  const before = await page.evaluate(() => document.getElementById('sheet').innerText);
  const at = await freeSpot(page);
  await clickAt(page, ...at);
  await page.waitForSelector('.pointmenu');
  const rows = await menuRows(page);
  check(rows.length === 2 && rows[0] === 'Hier starten' && rows[1] === 'Hierhin', `switch off: the first-tap menu has its two rows (${JSON.stringify(rows)})`);
  check(await page.evaluate(() => !document.body.dataset.suggestReady && !document.getElementById('suggestPanel') && !document.querySelector('.suggeststart')), 'switch off: nothing of the feature on the page');
  check((await suggestKeys(page)).length === 0, 'switch off: no storage key written');
  await page.keyboard.press('Escape');
  await clickAt(page, ...at); await page.waitForTimeout(300); // a second click closes the menu
  check(await page.evaluate((b) => document.getElementById('sheet').innerText === b, before), 'switch off: the sheet reads as before');
  // the address alone cannot turn it on with a wrong value
  const p2 = await open('?lang=de&suggest=0');
  await clickAt(p2, ...(await freeSpot(p2))); await p2.waitForSelector('.pointmenu');
  check((await menuRows(p2)).length === 2, 'suggest=0: two rows');
  await p2.context().close();
  await page.context().close();
}
check(!log.some((u) => u.includes('/poc')), 'no request to the POC (the built page has no such mechanism)');

// ---- (b) the switch is on, mechanism `ring` ----
{
  const page = await open('?lang=de&suggest=1&suggestVia=ring&at=11.16,46.67,13');
  check(await page.evaluate(() => document.body.dataset.suggestReady === '1'), 'switch on: the feature is set up');
  const emptySheet = await page.evaluate(() => document.getElementById('sheet').innerText);
  await clickAt(page, ...START);
  await page.waitForSelector('.pointmenu');
  const rows = await menuRows(page);
  check(rows.length === 3 && rows[2] === 'Rundtour von hier', `switch on: third row "Rundtour von hier" (${JSON.stringify(rows)})`);
  await shot(page, 'b1-menu');
  await page.click('.pointmenu button:nth-of-type(3)');
  await page.waitForSelector('#suggestPanel');
  check(await page.evaluate(() => document.getElementById('sheet').classList.contains('suggest-wish') && !!document.querySelector('.suggeststart') && !document.querySelector('.pointmenu')),
    'the wish is in the sheet, the start is marked, the menu is closed');
  check(await page.locator('#activityChip').isVisible() && !(await page.locator('#share').isVisible()), 'the wish keeps the activity chip, not Share');
  check((await points(page)) === 0, 'the plan is still empty while the wish is open');
  await shot(page, 'b2-wish');
  // Abbrechen: exactly the empty plan again
  await page.click('#suggestPanel .sgcancel');
  await page.waitForTimeout(200);
  check(await page.evaluate((b) => !document.getElementById('suggestPanel') && !document.querySelector('.suggeststart') && !/suggest/.test(document.getElementById('sheet').className)
    && document.getElementById('sheet').innerText === b && window.__state.points.length === 0 && !window.__state.canUndo && !window.__map.getSource('suggest-lines')
    && !document.body.dataset.suggest, emptySheet), 'Abbrechen: the empty plan exactly as before (sheet, no marker, nothing to undo)');
  check((await suggestKeys(page)).length === 0, 'Abbrechen: nothing remembered');

  // OK with 3 h, then at once "Wunsch ändern" → 1 h → OK: the second request supersedes the first
  await clickAt(page, ...START); await page.waitForSelector('.pointmenu');
  await page.click('.pointmenu button:nth-of-type(3)'); await page.waitForSelector('#suggestPanel .sgok');
  await page.click('#suggestPanel .sg[data-name=hours] button[data-v="3"]');
  await page.click('#suggestPanel .sgok');
  await page.waitForSelector('#msgcards .msgcard[data-id="suggest"]');
  check(await page.evaluate(() => /Runde/.test(document.querySelector('#msgcards .msgcard[data-id="suggest"]').textContent) && !!document.querySelector('#msgcards .msgcard[data-id="suggest"] button')),
    'OK: the wait card with Abbrechen');
  await shot(page, 'b3-wait');
  await page.click('#suggestPanel .sgchange');
  await page.waitForSelector('#suggestPanel .sgok');
  check(await page.evaluate(() => document.querySelector('#suggestPanel .sg[data-name=hours] button.on')?.dataset.v === '3' && !document.querySelector('#msgcards .msgcard[data-id="suggest"]')),
    'Wunsch ändern: the wish again with its values, the wait is gone');
  await page.click('#suggestPanel .sg[data-name=hours] button[data-v="1"]');
  await page.click('#suggestPanel .sgok');
  await page.waitForSelector('#suggestPanel .sgagain', { timeout: 240_000 }); // the run is over
  const cards = await page.$$eval('.sgcard', (cs) => cs.map((c) => ({ title: c.querySelector('b').textContent, facts: c.querySelector('.sgfacts').textContent, hints: c.querySelector('.sghints')?.textContent ?? '' })));
  console.log('cards:', JSON.stringify(cards));
  const kms = cards.map((c) => Number((/([\d.,]+) km/.exec(c.facts) ?? [])[1]?.replace(',', '.') ?? NaN));
  check(cards.length >= 1 && cards.length <= 3 && cards.every((c) => /km · .+ · ↑ \d+ m/.test(c.facts)), `cards with length · time · ascent (${cards.length})`);
  check(kms.every((k) => k > 1 && k < 9), `every card belongs to the second (1 h) request, none to the superseded 3 h one (${kms.join(', ')} km)`);
  check(new Set(cards.map((c) => c.title)).size === cards.length, 'no loop twice');
  check(await page.evaluate((n) => window.__map.getSource('suggest-lines')?.serialize().data.features.length === n && !document.querySelector('#msgcards .msgcard[data-id="suggest"]'), cards.length),
    'one line per card on the map, the wait card is gone');
  check((await points(page)) === 0, 'the plan is still empty while the suggestions show');
  check(JSON.stringify(await suggestKeys(page)) === '["onpath.suggest.wish"]', 'OK remembered the wish (one key)');
  await shot(page, 'b4-cards');
  // a click on the map does not start a plan now
  await clickAt(page, START[0] + 0.02, START[1] + 0.012); await page.waitForTimeout(300);
  check(!(await page.$('.pointmenu')) && (await points(page)) === 0, 'a click on the map while suggestions show: no menu, no point');

  // Übernehmen: an ordinary round-trip plan
  await page.click('.sgcard:first-of-type .sgadopt');
  await page.waitForFunction(() => document.querySelector('#summary .big') && !window.__state.planning, null, { timeout: 120_000 });
  await page.waitForTimeout(800);
  const st = await page.evaluate(() => { const s = window.__state; const p = s.points; return { n: p.length, loop: p.length > 2 && p[0][0] === p[p.length - 1][0] && p[0][1] === p[p.length - 1][1],
    roundTrip: s.roundTrip, canUndo: s.canUndo, panel: !!document.getElementById('suggestPanel'), src: !!window.__map.getSource('suggest-lines'), marker: !!document.querySelector('.suggeststart'),
    hash: location.hash.slice(0, 3), route: window.__map.getSource('planned-route').serialize().data.features.length }; });
  check(st.n >= 3 && st.loop && st.roundTrip, `Übernehmen: a round-trip plan (${st.n} points, round trip ${st.roundTrip})`);
  check(!st.panel && !st.src && !st.marker && st.route > 0, 'Übernehmen: the suggestions are gone, the planned route is drawn');
  check(st.hash === '#p=', 'the plan is in the address (share code)');
  await shot(page, 'b5-adopted');
  // share
  check(await page.locator('#share').isEnabled(), 'Share is enabled');
  await page.click('#share'); await page.waitForSelector('dialog.share[open]');
  check(await page.evaluate(() => { const d = document.querySelector('dialog.share[open]'); return /#p=/.test(d.innerHTML + [...d.querySelectorAll('input, textarea')].map((i) => i.value).join(' ')); }), 'Share: the dialog carries the plan link');
  await page.keyboard.press('Escape'); await page.waitForTimeout(200);
  // save
  check(await page.locator('#menuSavePlan').isEnabled(), 'Save is enabled');
  await page.click('#menuSavePlan'); await page.waitForSelector('#saveTitle');
  await page.click('#saveOk');
  await page.waitForFunction(() => window.__state.savedId != null, null, { timeout: 15_000 }).catch(() => {});
  check(await page.evaluate(() => window.__state.savedId != null), 'Save: the adopted plan is a saved route');
  // undo → the empty plan; redo → the round trip again
  check(st.canUndo, 'Übernehmen can be undone');
  await page.click('#undo');
  await page.waitForFunction(() => window.__state.points.length === 0, null, { timeout: 15_000 }).catch(() => {});
  check((await points(page)) === 0, 'Undo: the empty plan');
  await page.click('#redo');
  await page.waitForFunction((n) => window.__state.points.length === n, st.n, { timeout: 15_000 }).catch(() => {});
  check((await points(page)) === st.n, 'Redo: the round trip again');
  await page.context().close();
}

// ---- a failing mechanism: one calm message, the plan untouched ----
{
  const page = await open('?lang=de&suggest=1&suggestVia=ring&at=11.16,46.67,13');
  await page.evaluate(() => { window.__engine.plan = () => { throw new Error('test: the mechanism fails'); }; });
  await clickAt(page, ...START); await page.waitForSelector('.pointmenu');
  await page.click('.pointmenu button:nth-of-type(3)'); await page.waitForSelector('#suggestPanel .sgok');
  await page.click('#suggestPanel .sgok');
  await page.waitForFunction(() => /keine Vorschläge/.test(document.querySelector('#msgcards .msgcard[data-id="suggest"]')?.textContent ?? ''), null, { timeout: 30_000 }).catch(() => {});
  const m = await page.evaluate(() => ({ text: document.querySelector('#msgcards .msgcard[data-id="suggest"]')?.textContent ?? '', buttons: document.querySelectorAll('#msgcards .msgcard[data-id="suggest"] button').length,
    panel: !!document.getElementById('suggestPanel'), n: window.__state.points.length, undo: window.__state.canUndo, cls: document.getElementById('sheet').className }));
  check(/keine Vorschläge/.test(m.text) && /unverändert/.test(m.text) && m.buttons === 1, `a failure: one calm message with one button ("${m.text.trim()}")`);
  check(!m.panel && m.n === 0 && !m.undo && !/suggest/.test(m.cls), 'a failure: the plan is untouched, the sheet is the plan sheet again');
  await page.click('#msgcards .msgcard[data-id="suggest"] button'); await page.waitForTimeout(200);
  check(!(await page.$('#msgcards .msgcard[data-id="suggest"]')), 'OK takes the message away');
  await page.context().close();
}

// ---- (c) phone width: the wish is a window ----
{
  const page = await open('?lang=de&suggest=1&suggestVia=ring&at=11.16,46.67,13', { width: 412, height: 915 });
  const at = await freeSpot(page);
  await clickAt(page, ...at); await page.waitForSelector('.pointmenu');
  check((await menuRows(page))[2] === 'Rundtour von hier', 'phone: the third row');
  await page.click('.pointmenu button:nth-of-type(3)');
  await page.waitForSelector('dialog.suggestwish[open]');
  const box = await page.evaluate(() => { const d = document.querySelector('dialog.suggestwish'); const r = d.getBoundingClientRect(); const b = [...d.querySelectorAll('.sgbuttons button')].map((x) => x.textContent);
    return { h: r.height, top: r.top, bottom: r.bottom, w: r.width, scroll: d.scrollHeight > d.clientHeight + 1, buttons: b }; });
  check(JSON.stringify(box.buttons) === '["Abbrechen","OK"]', `phone: a window with Abbrechen · OK (${box.buttons.join(' · ')})`);
  check(box.top >= 0 && box.bottom <= 915 && !box.scroll, `phone: the window fits without scrolling (${Math.round(box.h)} px high)`);
  await shot(page, 'c1-phone-wish');
  await page.click('dialog.suggestwish .sgcancel'); await page.waitForTimeout(200);
  check(await page.evaluate(() => !document.querySelector('dialog.suggestwish') && !document.querySelector('.suggeststart') && window.__state.points.length === 0), 'phone: Abbrechen → the empty plan');
  await page.context().close();
}

await browser.close();
server.close();
if (errors.length) console.log('page errors:', errors);
check(errors.length === 0, 'no page errors');
if (fails.length) { console.error(`FAILED (${fails.length}): ` + fails.join(' | ')); process.exit(1); }
console.log('suggest-smoke: green');
