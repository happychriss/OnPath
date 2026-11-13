// Meine Touren → Zuletzt = the app's one-plan rule (app 2923a3e7 / 6f4b47d3, owner 2026-11-12 / laptop 2026-11-13):
// exactly one plan under Zuletzt – the one worked on last, saved or not. (1) recent.ts on its own (Node, a storage
// stand-in): the place, the bin, the one-time trim, restore, the app's LivePlanRows choice. (2) The page: an older
// planner's 3 drafts → 1 under Zuletzt + 2 in the Papierkorb; Wiederherstellen swaps; + Neu over an unsaved plan keeps
// it (the draft before → bin); Löschen → bin; the plan on the map is the row („In Bearbeitung“, + „Gespeichert“ when
// saved as it is), the saved route under Gespeichert as a plain row; a saved route left as it is holds the place
// („Gespeichert“, under Gespeichert too) across a reload; a draft never hides behind it; nothing is ever lost.
// Usage: node test/zuletzt-smoke.mjs [segmentsDir]
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const A = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Meran → Vellau
const B = 'AQUCAvCBugSuzocBqhD8AgMCHQBWZWxsYXUgS29yYmxpZnQgKFRhbHN0YXRpb24pAgoBTGVpdGVyYWxt'; // Vellau Korblift → Leiteralm
const C = 'AQACAujkuQSU6YcBi2X0MAMCBwBBbGd1bmQCBQFMYW5h'; // Algund → Lana
const here = path.dirname(fileURLToPath(import.meta.url));
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };

// ---------------- (1) recent.ts in Node ----------------
{
  const mem = new Map();
  globalThis.localStorage = { getItem: (k) => (mem.has(k) ? mem.get(k) : null), setItem: (k, v) => mem.set(k, String(v)), removeItem: (k) => mem.delete(k) };
  const quiet = console.info; console.info = () => {};
  const R = await import('../src/recent.ts');
  const DAY = 86_400_000, T0 = 1_800_000_000_000;
  // an older planner's 3 drafts, no trim mark
  mem.set('onpath.recent', JSON.stringify([
    { id: 'a', code: A, title: 'Alt A', points: '1,1;2,2', at: T0 - 3000 },
    { id: 'b', code: B, title: 'Alt B', points: '3,3;4,4', at: T0 - 1000 },
    { id: 'c', code: C, title: 'Alt C', points: '5,5;6,6', at: T0 - 2000 }]));
  let r = R.startUp(T0);
  check(r.trimmed === 2 && R.recentPlans().map((x) => x.id).join() === 'b' && R.binnedPlans().length === 2, `unit: the one-time trim keeps the newest, 2 to the bin (${JSON.stringify(r)})`);
  mem.set('onpath.recent', JSON.stringify([...JSON.parse(mem.get('onpath.recent')), { id: 'd', code: A, title: 'D', points: '7,7;8,8', at: T0 }]));
  r = R.startUp(T0 + 1);
  check(r.trimmed === 0 && R.recentPlans().length === 2, 'unit: the trim runs once only (the mark)');
  // keep: a new draft takes the place, every other draft → bin
  const id = R.keepRecent(A, 'Neu', '9,9;10,10', null, T0 + 10);
  check(R.recentPlans().map((x) => x.id).join() === id && R.binnedPlans().length === 4 && R.zuletztPlace()?.id === id, 'unit: a kept plan is the one draft, the drafts before it in the bin');
  // the same points again: no second entry
  check(R.keepRecent(A, 'Neu 2', '9,9;10,10', null, T0 + 20) === null && R.recentPlans().length === 1, 'unit: the same points twice – one entry');
  // restore: the binned one takes the place, the one there → bin; nothing lost
  const total = () => R.recentPlans().length + R.binnedPlans().length;
  const n0 = total();
  check(R.restoreRecent('b', T0 + 30) && R.recentPlans().map((x) => x.id).join() === 'b' && R.zuletztPlace()?.id === 'b' && total() === n0, 'unit: Restore swaps – the restored draft is the Zuletzt plan, the other in the bin, nothing lost');
  // to the bin, no place left
  check(R.toBin('b', T0 + 40) && R.recentPlans().length === 0 && total() === n0, 'unit: Löschen → bin');
  // a saved route takes the place; a draft never hides behind it
  R.takePlace({ kind: 'saved', id: 'S1' }, T0 + 50);
  let row = R.recentRow(null, R.recentPlans(), new Set(['S1']), R.zuletztPlace());
  check(row && 'savedId' in row && row.savedId === 'S1', 'unit: an empty map – the saved route holding the place is the row');
  check(R.recentRow(null, [], new Set(), R.zuletztPlace()) === null, 'unit: the saved route gone (deleted) – no row');
  R.restoreRecent('a', T0 + 60);
  row = R.recentRow(null, R.recentPlans(), new Set(['S1']), { kind: 'saved', id: 'S1' });
  check(row && 'draft' in row && row.draft.id === 'a', 'unit: a draft outside the bin wins over a saved route holding the place (a draft never hides)');
  check(JSON.stringify(R.recentRow({ savedHere: true, unsaved: false }, R.recentPlans(), new Set(), null)) === '{"live":true,"saved":true}'
    && JSON.stringify(R.recentRow({ savedHere: true, unsaved: true }, [], new Set(), null)) === '{"live":true,"saved":false}', 'unit: the plan on the map is the row; „Gespeichert“ only when saved as it is');
  // saved from the draft: the draft goes for good, the saved route holds the place
  R.afterSaved('S2', 'a', null, T0 + 70);
  check(!R.recentById('a') && R.zuletztPlace()?.id === 'S2' && R.zuletztPlace()?.kind === 'saved', 'unit: saved from Zuletzt – the draft goes, the saved route holds the place');
  // 30 days in the bin, then removed at a start; the days left
  const old = R.binnedPlans().at(-1);
  check(R.daysLeft(old.deletedAt, old.deletedAt) === 30 && R.daysLeft(old.deletedAt, old.deletedAt + 29.5 * DAY) === 0, 'unit: the days left (30 → 0 = at the next start)');
  const before = R.binnedPlans().length;
  r = R.startUp(old.deletedAt + 30 * DAY);
  check(r.removed >= 1 && R.binnedPlans().length < before, `unit: after 30 days a start removes them (${r.removed})`);
  // storage blocked: nothing throws
  globalThis.localStorage = { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); } };
  const warn = console.warn; console.warn = () => {};
  let threw = false;
  try { R.startUp(T0); R.keepRecent(A, 'x', '1,2;3,4'); R.recentRow(null, R.recentPlans(), new Set(), R.zuletztPlace()); } catch { threw = true; }
  console.warn = warn; console.info = quiet;
  check(!threw, 'unit: storage blocked – nothing throws');
  delete globalThis.localStorage;
}

// ---------------- (2) the page ----------------
const segArg = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const segments = fs.existsSync(segArg) ? segArg : here;
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const errors = [];
const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 }, locale: 'en-GB' });
await ctx.addInitScript(({ A, B, C }) => {
  if (sessionStorage.getItem('seeded')) return;
  sessionStorage.setItem('seeded', '1');
  // an older planner's Zuletzt: three drafts, no trim mark yet
  const now = Date.now();
  localStorage.setItem('onpath.recent', JSON.stringify([
    { id: 'old1', code: B, title: 'Old Leiteralm', points: '46.68,11.15;46.69,11.16', at: now - 3 * 3600_000 },
    { id: 'old2', code: C, title: 'Old Lana', points: '46.66,11.13;46.6,11.14', at: now - 2 * 3600_000 },
    { id: 'old3', code: A, title: 'Old Waalweg', points: '46.1,11.1;46.2,11.2', at: now - 3600_000 }]));
}, { A, B, C });
const page = await ctx.newPage();
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const ready = () => page.waitForFunction(() => document.body.dataset.engine === 'ready' && document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 120_000 });
const planned = () => page.waitForFunction(() => !!document.querySelector('#summary .big') && document.body.dataset.planning !== '1', null, { timeout: 120_000 });
const store = () => page.evaluate(() => { const l = JSON.parse(localStorage.getItem('onpath.recent') ?? '[]');
  return { drafts: l.filter((r) => r.deletedAt == null).map((r) => r.title), bin: l.filter((r) => r.deletedAt != null).map((r) => r.title), place: JSON.parse(localStorage.getItem('onpath.zuletzt') ?? 'null') }; });
const openList = async () => { await page.click('#menuPlans'); await page.waitForSelector('dialog.plans[open] #planList'); await page.waitForTimeout(150); };
/** Zuletzt's rows (between the RECENT head and the next head) and Gespeichert's. */
const sections = () => page.evaluate(() => {
  const out = { recent: [], saved: [], heads: [] };
  let cur = null;
  for (const li of document.querySelectorAll('#planList > li')) {
    if (li.classList.contains('sectionhead')) { out.heads.push(li.textContent); cur = out.heads.length === 1 && li.textContent === 'RECENT' ? 'recent' : 'saved'; continue; }
    if (!li.classList.contains('tourrow')) continue;
    (out[cur ?? 'saved']).push({ name: li.querySelector('.name')?.textContent, tags: [...li.querySelectorAll('.tag')].map((t) => t.textContent),
      live: li.dataset.live === '1', draft: li.classList.contains('draft'), chev: !!li.querySelector('.chev'), save: !!li.querySelector('.r-save'), more: !!li.querySelector('.more') });
  }
  return out;
});
const recentNames = async () => (await sections()).recent.map((r) => r.name);

await page.goto(url + 'index.html?lang=en');
await ready();
let s = await store();
check(s.drafts.join() === 'Old Waalweg' && s.bin.length === 2, `start: the one-time trim – the newest stays, 2 older in the bin (${JSON.stringify(s)})`);
await openList();
let sec = await sections();
check(sec.recent.length === 1 && sec.recent[0].name === 'Old Waalweg' && sec.recent[0].draft, `Meine Touren: one row under Recent – the newest draft (${JSON.stringify(sec.recent)})`);
check(await page.isVisible('#plansBin') && (await page.textContent('#plansBin')).trim() === 'Recycle bin', 'the Recycle bin button shows while the bin holds a plan');
await page.click('#plansBin');
const bin = await page.evaluate(() => ({ title: document.getElementById('plansTitle').textContent, keep: document.querySelector('#planList .binkeep')?.textContent,
  rows: [...document.querySelectorAll('#planList .binrow')].map((li) => ({ name: li.querySelector('.name').textContent, when: li.querySelector('.binwhen').textContent, btn: li.querySelector('.r-restore').textContent.trim() })) }));
check(bin.title === 'Recycle bin' && bin.keep === 'Deleted tours and plans stay here for 30 days, then they are removed.' && bin.rows.length === 2
  && bin.rows.every((r) => /^Deleted \d+ \w+ \d{4} · (30|29) days left$/.test(r.when) && r.btn === 'Restore'), `the bin: the app's words – title, keep line, "Deleted … · 30 days left", Restore (${JSON.stringify(bin)})`);
await page.click('#planList .binrow:has(.name:text-is("Old Lana")) .r-restore');
await page.waitForTimeout(150);
s = await store();
check(s.drafts.join() === 'Old Lana' && s.bin.length === 2 && s.bin.includes('Old Waalweg'), `Restore: "Old Lana" is the Zuletzt plan, "Old Waalweg" went into the bin – nothing lost (${JSON.stringify(s)})`);
await page.click('#binBack');
await page.waitForTimeout(200);
check(JSON.stringify(await recentNames()) === '["Old Lana"]' && !(await page.isVisible('#binBack')), 'Back: the list, the restored plan under Recent');
await page.click('#closePlans');

// a plan from a link, unsaved; + New keeps it as the one draft, the draft before → bin
await page.evaluate((c) => { location.hash = 'p=' + c; }, A);
await planned();
await openList();
sec = await sections();
check(sec.recent.length === 1 && sec.recent[0].live && sec.recent[0].tags.join() === 'In progress' && sec.recent[0].save, `the plan on the map is the one Recent row, "In progress", Save (${JSON.stringify(sec.recent)})`);
await page.click('#closePlans');
await page.click('#newPlan');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 0, null, { timeout: 10_000 });
await page.waitForTimeout(300);
s = await store();
check(s.drafts.length === 1 && s.drafts[0] !== 'Old Lana' && s.bin.includes('Old Lana') && s.bin.length === 3 && s.place?.kind === 'draft', `+ New: the link plan is the one draft, "Old Lana" in the bin (${JSON.stringify(s)})`);
const kept = s.drafts[0];
// Löschen on the draft: into the bin, no question
await openList();
await page.click('#planList li.draft .r-delete');
await page.waitForTimeout(200);
s = await store();
check(s.drafts.length === 0 && s.bin.includes(kept) && s.bin.length === 4 && !(await page.$('#planList li.draft')), `Delete on the Recent row: into the bin, no question (${JSON.stringify(s)})`);
// back out of the bin
await page.click('#plansBin');
await page.click(`#planList .binrow:has(.name:text-is("${kept}")) .r-restore`);
await page.click('#binBack');
await page.waitForTimeout(200);
check(JSON.stringify(await recentNames()) === JSON.stringify([kept]), 'restored from the bin: it is the Recent row again');
// open it, save it: the saved route is the plan on the map – Recent: „In progress“ + „Saved“, Gespeichert: the plain row
await page.click('#planList li.draft .open');
await planned();
await page.click('#menuSavePlan');
await page.waitForSelector('dialog.savename[open] #saveTitle', { timeout: 10_000 });
await page.fill('#saveTitle', 'My Waalweg');
await page.click('#saveOk');
await page.waitForFunction(async () => (await window.__sync.list()).some((p) => p.title === 'My Waalweg'), null, { timeout: 10_000 });
await page.waitForFunction(() => JSON.parse(localStorage.getItem('onpath.zuletzt') ?? 'null')?.kind === 'saved', null, { timeout: 10_000 }).catch(() => {});
s = await store();
check(s.drafts.length === 0 && s.place?.kind === 'saved', `saved from Recent: the draft goes (the saved route holds it), the place is the saved route (${JSON.stringify(s)})`);
await openList();
sec = await sections();
check(sec.recent.length === 1 && sec.recent[0].live && JSON.stringify(sec.recent[0].tags) === '["In progress","Saved"]' && !sec.recent[0].save,
  `the open saved route: the Recent row with "In progress" + "Saved", no Save (${JSON.stringify(sec.recent)})`);
check(sec.saved.some((r) => r.name === 'My Waalweg' && r.chev && r.more && !r.tags.includes('In progress')), `… and under Saved its plain row (›, ⋮) (${JSON.stringify(sec.saved)})`);
await page.click('#closePlans');
// + New on the saved route as it is: no draft – the saved route holds the place („Saved“, also under Saved)
await page.click('#newPlan');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 0, null, { timeout: 10_000 });
await page.waitForTimeout(300);
s = await store();
check(s.drafts.length === 0 && s.place?.kind === 'saved', `+ New on a saved route as it is: no draft, the saved route holds Recent (${JSON.stringify(s)})`);
const placeRow = async () => {
  await openList();
  const x = await sections();
  await page.click('#closePlans');
  return x;
};
sec = await placeRow();
check(sec.recent.length === 1 && sec.recent[0].name === 'My Waalweg' && JSON.stringify(sec.recent[0].tags) === '["Saved"]' && !sec.recent[0].live && sec.recent[0].chev
  && sec.saved.some((r) => r.name === 'My Waalweg'), `empty map: Recent = the saved route with "Saved", under Saved too (${JSON.stringify(sec)})`);
await page.reload();
await ready();
sec = await placeRow();
check(sec.recent.length === 1 && sec.recent[0].name === 'My Waalweg' && JSON.stringify(sec.recent[0].tags) === '["Saved"]', 'after a reload: the saved route still holds Recent');
// open it, change it (Reverse), + New: the changed plan is the one draft – a draft never hides behind the saved route
await openList();
await page.click('#planList li[data-zuletzt] .open');
await planned();
await page.click('#reverse');
await planned();
await page.waitForTimeout(500);
await page.click('#newPlan');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 0, null, { timeout: 10_000 });
await page.waitForTimeout(300);
s = await store();
sec = await placeRow();
check(s.drafts.length === 1 && s.place?.kind === 'draft' && sec.recent.length === 1 && sec.recent[0].draft && !sec.recent[0].tags.includes('Saved'),
  `a saved route changed and left: the changed plan is the one draft under Recent (${JSON.stringify({ s, recent: sec.recent })})`);
// every plan ever kept is still somewhere: under Recent, in the bin or saved
const all = await page.evaluate(async () => [...JSON.parse(localStorage.getItem('onpath.recent')).map((r) => r.title), ...(await window.__sync.list()).map((p) => p.title)]);
check(['Old Leiteralm', 'Old Lana', 'Old Waalweg', 'My Waalweg'].every((n) => all.includes(n)), `nothing lost: every plan is still under Recent, in the bin or saved (${JSON.stringify(all)})`);
await ctx.close();

// German: the app's words
{
  const de = await browser.newContext({ viewport: { width: 412, height: 860 }, isMobile: true, hasTouch: true, locale: 'de-DE' });
  await de.addInitScript(() => {
    if (sessionStorage.getItem('seeded')) return; sessionStorage.setItem('seeded', '1');
    const now = Date.now();
    localStorage.setItem('onpath.recent', JSON.stringify([{ id: 'x', code: 'AQACAujkuQSU6YcBi2X0MAMCBwBBbGd1bmQCBQFMYW5h', title: 'Alt', points: '1,1;2,2', at: now - 5000, deletedAt: now - 1000 }]));
    localStorage.setItem('onpath.zuletztTrimmed', '1');
  });
  const P = await de.newPage();
  P.on('pageerror', (e) => errors.push(String(e)));
  await P.goto(url + 'index.html?lang=de');
  await P.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
  if (await P.isVisible('#menuBtn')) await P.click('#menuBtn');
  await P.click('#menuPlans');
  await P.waitForSelector('dialog.plans[open] #plansBin:not([hidden])');
  await P.click('#plansBin');
  const t = await P.evaluate(() => ({ btn: document.getElementById('binBack').textContent.trim(), title: document.getElementById('plansTitle').textContent,
    keep: document.querySelector('.binkeep').textContent, when: document.querySelector('.binwhen').textContent, restore: document.querySelector('.r-restore').textContent.trim() }));
  check(t.title === 'Papierkorb' && t.keep === 'Gelöschte Touren und Pläne bleiben 30 Tage hier, dann werden sie entfernt.' && /^Gelöscht am \d+\. \S+ \d{4} · noch (30|29) Tage$/.test(t.when)
    && t.restore === 'Wiederherstellen' && t.btn === 'Zurück', `German: Papierkorb, the keep line, „Gelöscht am … · noch 30 Tage“, Wiederherstellen, Zurück (${JSON.stringify(t)})`);
  await de.close();
}

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
console.log(fails.length ? `zuletzt-smoke: ${fails.length} FAILED` : 'zuletzt-smoke: all ok');
process.exit(fails.length ? 1 : 0);
