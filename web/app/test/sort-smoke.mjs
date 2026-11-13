// Meine Touren's sort chips and search like the app's (ui/routes/RouteSort + RouteSortChips, RoutesTab.search; owner
// 2026-11-13: "dieselben Suchen und Sortieren-Einstellungen wie das Handy"): the five chips in the app's order with its
// words (EN + DE), the chosen one ✓ (and ↓ / ↑), a second tap turns Länge / Dauer / Anstieg round, the app's ties, a row
// this browser has no numbers for goes last, the choice kept in localStorage (onpath.routesSort, the app's stored form)
// over a reload, the search (every word, case / accents / ß folded) with the order kept, the app's empty texts.
// Usage: node test/sort-smoke.mjs [segmentsDir] [shot.png]
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { handleSync } from '../../host/src/sync.js';
import { fakeD1 } from '../../host/test/fake-d1.js';

const A = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Meran → Vellau (PlanCodeTest)
const B = 'AQUCAvCBugSuzocBqhD8AgMCHQBWZWxsYXUgS29yYmxpZnQgKFRhbHN0YXRpb24pAgoBTGVpdGVyYWxt'; // Vellau Korblift → Leiteralm
const C = 'AQACAujkuQSU6YcBi2X0MAMCBwBBbGd1bmQCBQFMYW5h'; // Algund → Lana
const here = path.dirname(fileURLToPath(import.meta.url));
const segArg = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const segments = fs.existsSync(segArg) ? segArg : here;
const shot = process.argv[3] ?? null;
const env = { SYNC: fakeD1() };
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, sync: (r) => handleSync(r, env) });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];

async function open(ctx, q = '') {
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(url + 'index.html' + q);
  await page.waitForFunction(() => window.__sync?.status && document.body.dataset.engine === 'ready', null, { timeout: 120_000 }).catch(() => {});
  await page.waitForFunction(() => document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 60_000 });
  return page;
}
const openList = async (P) => { await P.click('#menuPlans'); await P.waitForSelector('dialog.plans[open] #planList', { timeout: 10_000 }); };
const closeList = (P) => P.click('#closePlans');
const names = (P) => P.$$eval('#planList li[data-id] .name', (els) => els.map((e) => e.textContent));
const chips = (P) => P.$$eval('#planSort .sortchip', (els) => els.map((e) => ({ key: e.dataset.key, text: e.textContent.trim(), on: e.classList.contains('on'),
  pressed: e.getAttribute('aria-pressed'), label: e.getAttribute('aria-label'), check: !!e.querySelector('.ic:first-child') && e.classList.contains('on'),
  dir: e.querySelector('.dir')?.dataset.dir ?? null })));
const tap = async (P, key) => { await P.click(`#planSort .sortchip[data-key="${key}"]`); await P.waitForTimeout(150); };
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

// --- a list with numbers: three plans (one never routed here – no numbers) and a track, saved oldest → newest
const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 }, locale: 'en-GB' });
let P = await open(ctx);
await P.evaluate(async ({ A, B, C }) => {
  const s = window.__sync, st = window.__planStats, wait = () => new Promise((r) => setTimeout(r, 20));
  await s.save(A, 'Waalweg Köln'); st.keepStats(st.planKey(A), { m: 12000, up: 800, down: 300, s: 14400 }); await wait();
  await s.save(B, 'Leiteralm'); st.keepStats(st.planKey(B), { m: 5000, up: 900, down: 50, s: 7200 }); await wait();
  await s.save(C, 'Algund Lana'); await wait(); // never routed in this browser: no numbers
  const id = await s.saveTrack([[[46.67, 11.15, 300], [46.68, 11.16, 400]]], [], 'HIKE', 2, 'Straße am Berg');
  st.keepStats(st.trackKey(id), { m: 8000, up: 100, down: 100, s: 10000 });
}, { A, B, C });
await openList(P);
const NEWEST = ['Straße am Berg', 'Algund Lana', 'Leiteralm', 'Waalweg Köln'];
let c = await chips(P);
check(same(c.map((x) => x.key), ['NEWEST', 'OLDEST', 'LENGTH', 'DURATION', 'ASCENT']), 'the five chips in the app\'s order');
check(same(c.map((x) => x.text), ['Newest first', 'Oldest first', 'Length', 'Duration', 'Ascent']), 'the app\'s words: ' + c.map((x) => x.text).join(' · '));
check(c[0].on && c[0].pressed === 'true' && c[0].check && c[0].dir == null && c.slice(1).every((x) => !x.on && x.pressed === 'false'),
  'nothing chosen yet: Newest first, ✓, no arrow');
check(c[0].label === 'Sorted: Newest first' && c[2].label === 'Sort: Length', 'the app\'s spoken texts (' + c[0].label + ' / ' + c[2].label + ')');
check(await P.isVisible('#planSearch') && (await P.evaluate(() => { const s = document.querySelector('.plansearch').getBoundingClientRect(), r = document.getElementById('planSort').getBoundingClientRect(); return r.top >= s.bottom - 1; })),
  'the chips sit under the search');
check(same(await names(P), NEWEST), 'newest first: ' + (await names(P)).join(', '));

await tap(P, 'LENGTH');
c = await chips(P);
check(same(await names(P), ['Waalweg Köln', 'Straße am Berg', 'Leiteralm', 'Algund Lana']), 'Length: longest first, the row without numbers last – ' + (await names(P)).join(', '));
check(c[2].on && c[2].dir === 'down' && c[2].label === 'Sorted: Length, longest first. Tap to reverse the order.' && !c[0].on, 'Length chosen with ↓ and its spoken text');
await tap(P, 'LENGTH');
c = await chips(P);
check(same(await names(P), ['Leiteralm', 'Straße am Berg', 'Waalweg Köln', 'Algund Lana']), 'a second tap: shortest first, without numbers still last – ' + (await names(P)).join(', '));
check(c[2].dir === 'up' && c[2].label.includes('shortest first'), 'the arrow turns ↑ (shortest first)');
await tap(P, 'ASCENT');
c = await chips(P);
check(same(await names(P), ['Leiteralm', 'Waalweg Köln', 'Straße am Berg', 'Algund Lana']), 'another chip starts biggest first: Ascent most first – ' + (await names(P)).join(', '));
check(c[4].dir === 'down' && c[4].label.includes('most first'), 'Ascent says "most first"');
await tap(P, 'DURATION');
check(same(await names(P), ['Waalweg Köln', 'Straße am Berg', 'Leiteralm', 'Algund Lana']), 'Duration longest first – ' + (await names(P)).join(', '));
await tap(P, 'OLDEST');
c = await chips(P);
check(same(await names(P), [...NEWEST].reverse()), 'Oldest first – ' + (await names(P)).join(', '));
await tap(P, 'OLDEST');
check(same(await names(P), [...NEWEST].reverse()) && (await chips(P))[1].dir == null, 'a date chip has no direction: a second tap keeps it');

// --- the search: every word, any case, accents and ß folded; the order stays the chosen one
await tap(P, 'LENGTH');
await P.fill('#planSearch', 'xyz');
await P.waitForTimeout(150);
check((await P.textContent('#planList')).trim() === 'Nothing called “xyz” here', 'nothing fits: the app\'s text (' + (await P.textContent('#planList')).trim() + ')');
check(await P.isVisible('#planSort'), 'the chips stay while searching');
await P.fill('#planSearch', 'KOLN');
await P.waitForTimeout(150);
check(same(await names(P), ['Waalweg Köln']), '"KOLN" finds "Waalweg Köln"');
await P.fill('#planSearch', 'strasse');
await P.waitForTimeout(150);
check(same(await names(P), ['Straße am Berg']), '"strasse" finds "Straße am Berg"');
await P.fill('#planSearch', 'a');
await P.waitForTimeout(150);
check(same(await names(P), ['Waalweg Köln', 'Straße am Berg', 'Leiteralm', 'Algund Lana']), 'a search keeps the chosen order – ' + (await names(P)).join(', '));
await P.fill('#planSearch', 'alm lei');
await P.waitForTimeout(150);
check(same(await names(P), ['Leiteralm']), 'every word must be in the name ("alm lei")');
await P.click('#planSearchClear');
await P.waitForTimeout(150);
if (shot) await P.locator('dialog.plans').screenshot({ path: shot });

// --- kept over a reload, in the app's stored form
check(await P.evaluate(() => localStorage.getItem('onpath.routesSort')) === 'LENGTH:down', 'kept as the app keeps it: onpath.routesSort = LENGTH:down');
await closeList(P);
await P.close();
P = await open(ctx);
await openList(P);
c = await chips(P);
check(c[2].on && c[2].dir === 'down' && same(await names(P), ['Waalweg Köln', 'Straße am Berg', 'Leiteralm', 'Algund Lana']), 'after a reload: Length longest first again');
await P.evaluate(() => localStorage.setItem('onpath.routesSort', 'DIFFICULTY:up'));
await closeList(P); await P.close();
P = await open(ctx);
await openList(P);
check((await chips(P))[0].on && same(await names(P), NEWEST), 'an unknown stored order (a newer app): newest first');
await P.evaluate(() => localStorage.setItem('onpath.routesSort', 'ASCENT:up'));
await closeList(P); await P.close();
P = await open(ctx);
await openList(P);
c = await chips(P);
check(c[4].on && c[4].dir === 'up' && same(await names(P), ['Straße am Berg', 'Waalweg Köln', 'Leiteralm', 'Algund Lana']), 'ASCENT:up read back: least first');
await P.close();

// --- German: the app's words
const ctxDe = await browser.newContext({ viewport: { width: 1280, height: 860 } });
P = await open(ctxDe, '?lang=de');
await P.evaluate(async (A) => { await window.__sync.save(A, 'Waalweg'); }, A);
await openList(P);
c = await chips(P);
check(same(c.map((x) => x.text), ['Neueste zuerst', 'Älteste zuerst', 'Länge', 'Dauer', 'Anstieg']), 'German: ' + c.map((x) => x.text).join(' · '));
await tap(P, 'ASCENT');
c = await chips(P);
check(c[4].label === 'Sortiert: Anstieg, meiste zuerst. Tippen dreht die Reihenfolge um.', 'German spoken text: ' + c[4].label);
await P.fill('#planSearch', 'xyz');
await P.waitForTimeout(150);
check((await P.textContent('#planList')).trim() === 'Nichts mit „xyz“ hier', 'German: nothing fits');
await ctxDe.close();

// --- nothing saved: no search, no chips, the app's empty text
const ctx0 = await browser.newContext({ viewport: { width: 1280, height: 860 }, locale: 'en-GB' });
P = await open(ctx0);
await openList(P);
check(!(await P.isVisible('#planSearch')) && !(await P.isVisible('#planSort')), 'an empty list: no search, no chips (as the app)');
check((await P.$eval('#planList', (e) => e.innerText.trim())) === 'No plans yet.\nSave a plan or import a GPX file.', 'the app\'s empty text: ' + JSON.stringify(await P.$eval('#planList', (e) => e.innerText.trim())));
await ctx0.close();

await browser.close(); server.close();
const real = errors.filter((e) => !/status of (404|409|410|429|507)/.test(e) && !/ERR_INTERNET_DISCONNECTED|Failed to fetch/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
console.log(fails.length ? `FAILED: ${fails.length}` : 'all ok');
process.exit(fails.length ? 1 : 0);
