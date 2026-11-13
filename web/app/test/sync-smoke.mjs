// Plan sync with folders (owner order A-017, docs/design/web-sync.md §14; what the page shows: §14.6) end to end: two
// browser contexts (A = desktop, L = laptop) and fake phones against the host's real mailbox code (web/host/src/sync.js)
// on a fake D1 (Node's SQLite). A fake phone is the app's own shared sync code (com.onpath.app.sync in the Wasm engine
// of a third, idle page) driven from Node with the real crypto: its hello says role 1, it writes routes, renames,
// deletes, the JOIN redirect (§5.1) and "Unpair?" (DELETE) – what the phone app does. Proves: the plan bar's Save is
// always "Save" (paired it also sends), dark while anything is to save, light ✓ "Saved" when all is saved; routes
// planned without a phone join the next paired phone as waiting (bold, Save dark, nothing sent until the click → 🖥 📱);
// unsaved edits survive a reload and are not sent; My routes is one plain list (🖥 / 📱, ⋮ Rename / Export GPX /
// Delete, the open route light green, the phone's "New" / "Changed" tags, no heading, no Save box); a conflict keeps "(copy)"; delete both ways;
// GPX goes with Save; a friend's PC (another phone's folder is not visible, Save not dark); the same phone again brings
// its folder back with unsaved changes; a second browser via JOIN (transitional mailbox deleted); unpair on the phone;
// mailbox full (507) → amber Save, nothing lost; the server holds nothing readable.
// Needs no map data (routing may fail, the plan's points are enough). Usage: node test/sync-smoke.mjs [segmentsDir]
import path from 'node:path';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { handleSync, LIMITS } from '../../host/src/sync.js';
import { fakeD1 } from '../../host/test/fake-d1.js';

const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (PlanCodeTest)
const here = path.dirname(fileURLToPath(import.meta.url));
const segArg = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const segments = fs.existsSync(segArg) ? segArg : here;
const env = { SYNC: fakeD1() };
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, sync: (r) => handleSync(r, env) });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];

async function open(ctx, hash = '') {
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(url + 'index.html' + hash);
  await page.waitForFunction(() => window.__sync?.status && document.body.dataset.engine === 'ready', null, { timeout: 120_000 }).catch(() => {});
  await page.waitForFunction(() => document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 60_000 });
  return page;
}
/** Polls [fn] in the page until it is truthy (async predicates – waitForFunction does not wait for their promise). */
async function until(P, fn, arg = null, opts = {}) {
  const end = Date.now() + (opts.timeout ?? 30_000);
  while (Date.now() < end) { if (await P.evaluate(fn, arg).catch(() => false)) return true; await new Promise((r) => setTimeout(r, 250)); }
  return false;
}
const titles = (P, folder) => P.evaluate(async (f) => (await window.__sync.list(f ?? undefined)).map((x) => x.title).sort(), folder ?? null);
const syncNow = (P) => P.evaluate(() => window.__sync.syncNow());
const status = (P) => P.evaluate(() => ({ ...window.__sync.status }));
/** A plan bar entry: inline on a wide screen, behind Plan ▾ at phone width. */
const menu = async (P, id) => { if (await P.isVisible('#menuBtn')) await P.click('#menuBtn'); await P.click(id); };
/** The Phone window: Aufs Handy ▾ on a wide screen, Plan ▾ → Phone at phone width. */
// the Phone window: To phone ▾ (not paired) or the 📱 status itself (paired, owner 2026-11-06); phone width through Plan ▾
const phoneWin = async (P) => {
  if (await P.isVisible('#menuBtn')) { await P.click('#menuBtn'); await P.click(await P.isVisible('#menuPhone') ? '#menuPhone' : '#syncBadge'); }
  else await P.click(await P.isVisible('#phoneMore') ? '#phoneMore' : '#syncBadge');
};
/** "To phone" (the plan bar): its label (paired: the phone's name), the second line ("synced …"), title, dot. */
const entry = (P) => P.evaluate(() => { const b = document.getElementById('syncBadge'), d = b.querySelector('.syncdot'), sub = b.querySelector('.sub');
  const line = sub.hidden ? '' : sub.textContent;
  const l = b.querySelector('.lbl');
  return { label: l.textContent.trim(), shown: l.getClientRects().length > 0, sub: line, phone: b.classList.contains('paired') ? l.textContent.trim() : '', title: b.title, dot: !d.hidden, kind: d.dataset.kind,
    more: !!document.getElementById('phoneMore')?.getClientRects().length }; });
/** The header's Save (§14.6): its state (dark / done / empty / full), label, whether it is filled, and that no hint is beside it. */
const saveBtn = (P) => P.evaluate(() => { const b = document.getElementById('menuSavePlan');
  return { state: b.dataset.state, label: b.textContent.trim(), phone: !!b.querySelector('.savephone'), unsaved: b.classList.contains('unsaved'), filled: getComputedStyle(b).backgroundColor === 'rgb(51, 90, 133)',
    disabled: b.disabled, title: b.title, hint: !!document.getElementById('saveHint') || /Not saved on the phone/.test(document.getElementById('header').textContent) }; });
/** Pair from the Phone window: a new code each time; returns the code (the part after #pair=) and the check digits. */
async function showCode(P, other = false) {
  await phoneWin(P);
  await P.click(other ? 'dialog.phone #pairOther' : 'dialog.phone #pairPhone');
  await P.waitForSelector('dialog.pair[open] #pairLink', { timeout: 30_000 });
  return { code: (await P.inputValue('#pairLink')).split('#pair=')[1], check: await P.textContent('#checkDigits') };
}
/** Save's name dialog (owner 2026-10-31: every Save asks): waits for it, types [name] (null = keep the proposal), Save. */
async function answerSave(P, name) {
  await P.waitForSelector('dialog.savename[open] #saveTitle', { timeout: 10_000 });
  if (name != null) await P.fill('#saveTitle', name);
  await P.click('#saveOk');
}
/** Renames the open saved route: Save, the dialog shows its name, [name], Save (overwrite + rename). */
async function renameOpen(P, name) {
  await P.click('#menuSavePlan');
  await answerSave(P, name);
  await until(P, async (n) => (await window.__sync.list()).some((p) => p.title === n), name);
}
/** The shown (new) route saved as [name]: Save, the dialog, [name], Save (paired, that also sends). */
/** Save → the phone is done: a sync round ended after [sendMark] and nothing waits any more. Waiting for pending === 0
 *  alone passed too early – the page's status still showed the count from before the save (CI 2026-11-05). */
const sendMark = (P) => P.evaluate(() => { window.__sendMark = window.__sync.status.lastSync ?? 0; });
const sent = (P) => P.waitForFunction(() => (window.__sync.status.lastSync ?? 0) > window.__sendMark && window.__sync.status.pending === 0
  && !window.__sync.status.full, null, { timeout: 30_000 }).catch(async () => console.log('  (no send round seen after Save:', JSON.stringify(await P.evaluate(() => window.__sync.status)) + ')'));
async function saveAs(P, name) {
  const n = (await P.evaluate(async () => (await window.__sync.list()).length));
  await P.click('#menuSavePlan');
  await answerSave(P, name);
  await until(P, async (k) => (await window.__sync.list()).length > k, n);
}
const openRoute = async (P, hash) => { await P.evaluate((h) => { location.hash = h; }, hash); await P.waitForFunction(() => document.getElementById('points')?.textContent?.includes('Vellau'), null, { timeout: 30_000 }); };
/** A row of My routes: where it lies (browser / phone / browser+phone, from the 🖥 📱 symbols), bold, the line below, open. */
// the saved row (under Saved – a row with an id; the plan on the map is its own Recent row, [liveRow])
const listRow = (P, name) => P.evaluate((n) => { const li = [...document.querySelectorAll('#planList li[data-id]:not([data-zuletzt])')].find((l) => l.querySelector('.name')?.textContent === n);
  if (!li) return null;
  const where = [...li.querySelectorAll('.where .w')].map((w) => (w.classList.contains('browser') ? 'browser' : 'phone') + ':' + w.getAttribute('aria-label')).join(' ');
  return { where, bold: Number(getComputedStyle(li.querySelector('.name')).fontWeight) >= 700, sub: li.querySelector('.sub').textContent, kind: li.dataset.kind,
    current: li.classList.contains('current'), tag: li.querySelector('.tag')?.textContent ?? '', text: li.textContent.replace(/\s+/g, ' ').trim() }; }, name);
/** The plan on the map's row under Recent: its name and tags („In progress“, + „Saved“ when saved as it is), a Save on it. */
const liveRow = (P) => P.evaluate(() => { const li = document.querySelector('#planList li[data-live]');
  return li ? { name: li.querySelector('.name').textContent, tags: [...li.querySelectorAll('.tag')].map((t) => t.textContent),
    save: [...li.querySelectorAll('button')].some((b) => /^(Save|Speichern)$/.test(b.textContent.trim())) } : null; });
const HERE = 'browser:In this browser', PHONE = 'phone:On the phone', BOTH = HERE + ' ' + PHONE;
/** My routes → ⋮ of [name] → [item] (rename / export / del). */
async function rowMenu(P, name, item) {
  await P.click(`#planList li:has(.name:text-is("${name}")) .more`);
  await P.click(`#planList li:has(.name:text-is("${name}")) .rowmenu .${item}`);
}

// ---- the fake phone: the shared Kotlin in the engine of an idle page, HTTP straight to the host ----
const ctxP = await browser.newContext();
const enginePage = await open(ctxP);
const call = (fn, ...args) => enginePage.evaluate(([f, a]) => window.__sync.engine.call(f, ...a), [fn, args]);
const rnd = (n) => crypto.randomBytes(n).toString('base64url');
async function box(method, mailbox, token, { path: p = '', body, base, since } = {}, now = Date.now()) {
  const q = since != null ? `?since=${since}` : '';
  const r = await handleSync(new Request(`http://phone.test/sync/v1/${mailbox}${p}${q}`, { method, body,
    headers: { authorization: 'Bearer ' + token, 'user-agent': 'Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8)', ...(base != null ? { 'x-onpath-base': String(base) } : {}) } }), env, now);
  return { status: r.status, json: await r.json().catch(() => ({})) };
}
class Phone {
  constructor(name) { this.name = name; this.ledger = ''; this.pair = null; }
  async init() { this.device = await call('syncNewDevice', rnd(8)); return this; }
  async keys(pair = this.pair) { return JSON.parse(await call('syncKeys', pair)); }
  /** Not paired yet: scans a browser's code → that mailbox is the phone's (hello role 1, then its routes). */
  async pairDirect(code) { this.pair = code; this.ledger = await call('syncRebase', this.ledger, 0); this.ledger = await call('syncHelloAs', this.ledger, this.device, this.name, Date.now(), 1); await this.round(); }
  /** Already paired: scans another browser's code → one JOIN with its own code into that browser's fresh mailbox. */
  async redirect(code) {
    const k = await this.keys(code);
    await box('POST', k.mailbox, k.token);
    const [id, blob] = (await call('syncJoinBlob', code, this.device, this.pair, Date.now(), rnd(12))).split(' ');
    return (await box('PUT', k.mailbox, k.token, { path: '/' + id, body: Buffer.from(blob, 'base64url'), base: 0 })).status;
  }
  async round() {
    const k = await this.keys();
    for (let pass = 0; pass < 4; pass++) {
      const since = JSON.parse(await call('syncState', this.ledger)).since;
      let r = await box('GET', k.mailbox, k.token, { since });
      if (r.status === 404) { await box('POST', k.mailbox, k.token); r = await box('GET', k.mailbox, k.token, { since }); }
      if (r.status !== 200) return r.status;
      const out = await call('syncApply', this.ledger, this.pair, r.json.items.map((i) => `${i.id} ${i.rev} ${i.blob}`).join('\n'), r.json.rev);
      this.ledger = out.slice(0, out.lastIndexOf('#bad '));
      let conflict = false;
      for (const line of (await call('syncOutbox', this.ledger, this.pair, rnd(12 * 64))).split('\n').filter(Boolean)) {
        const [id, base, blob, sent] = line.split(' ');
        const w = await box('PUT', k.mailbox, k.token, { path: '/' + id, body: Buffer.from(blob, 'base64url'), base });
        if (w.status === 409) { conflict = true; continue; }
        if (w.status !== 200) return w.status;
        this.ledger = await call('syncAcked', this.ledger, sent, w.json.rev);
      }
      if (!conflict) return 200;
    }
    return 200;
  }
  async list() { return JSON.parse(await call('syncList', this.ledger, this.device)); }
  async titles() { return (await this.list()).map((x) => x.title).sort(); }
  /** [routing] / [routingData] = sync fields 7 / 8 as the phone writes them (web-sync.md §5.2), '' = an older app. */
  async save(title, plan = CODE, routing = '', routingData = '') {
    const id = await call('syncNewId', rnd(16));
    this.ledger = await call('syncSave', this.ledger, id, plan, title, Date.now(), this.device, routing, routingData);
    return id;
  }
  async rename(id, title) { this.ledger = await call('syncRename', this.ledger, id, title, Date.now(), this.device); }
  async remove(id) { this.ledger = await call('syncDelete', this.ledger, id, Date.now(), this.device); }
  idOf = async (title) => (await this.list()).find((x) => x.title === title)?.id;
  /** "Unpair?" on the phone: the mailbox ends for every browser. */
  async end() { const k = await this.keys(); return (await box('DELETE', k.mailbox, k.token)).status; }
}
const phone = await new Phone('Pixel 8').init();

// ---- 1. a fresh browser, no phone: "Save" (dark for the new route, light ✓ once saved); two routes, 🖥 only ----
const ctxA = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const A = await open(ctxA, '#p=' + CODE);
await A.waitForFunction(() => document.getElementById('points')?.textContent?.includes('Vellau'), null, { timeout: 60_000 });
let e = await entry(A);
check(e.label === 'To phone' && !e.dot && e.sub === '' && e.title.startsWith('Pair your phone'), `not paired: "To phone" without dot and second line, its title says it pairs (${JSON.stringify(e)})`);
await A.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'dark', null, { timeout: 30_000 }).catch(() => {});
let sb = await saveBtn(A);
check(sb.state === 'dark' && sb.filled && sb.label === 'Save' && !sb.hint, `not paired, a new route: "Save", dark (filled), no hint beside it (${JSON.stringify(sb)})`);
// Save as the app (2026-10-31, "Save always asks for the name"): the dialog proposes the plan link's title "Waalweg",
// fully selected; Cancel saves nothing; Save again shows the saved name and renames the same route; a copy is My routes
// → ⋮ → Save as copy
await A.click('#menuSavePlan');
await A.waitForSelector('dialog.savename[open] #saveTitle');
const rf = await A.evaluate(() => { const i = document.getElementById('saveTitle'); return { value: i.value, selected: i.selectionEnd - i.selectionStart === i.value.length }; });
check(rf.value === 'Waalweg' && rf.selected, `Save: the name dialog proposes the link's title, selected (${JSON.stringify(rf)})`);
await A.click('#saveCancel');
await A.waitForTimeout(300);
check((await titles(A)).length === 0, 'Cancel saves nothing');
await A.click('#menuSavePlan');
await answerSave(A, null);
await until(A, async () => (await window.__sync.list()).length === 1);
await A.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'done', null, { timeout: 30_000 }).catch(() => {});
sb = await saveBtn(A);
check(JSON.stringify(await titles(A)) === '["Waalweg"]' && sb.state === 'done' && !sb.filled && sb.label === 'Saved' && await A.locator('dialog[open]').count() === 0,
  `Save → "Waalweg"; light "✓ Saved" (${JSON.stringify(sb)})`);
await renameOpen(A, 'Altroute');
check(JSON.stringify(await titles(A)) === '["Altroute"]' && (await A.textContent('#savedLine')).includes('Saved as Altroute'), 'Save with a new name renames the open route: "Altroute", one route');
// a copy, then opened: the open route from then on
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
await rowMenu(A, 'Altroute', 'copy');
await A.waitForSelector('dialog.savename[open] #saveTitle');
check(await A.inputValue('#saveTitle') === 'Altroute (2)', 'My routes ⋮ → Save as copy proposes "Altroute (2)"');
await answerSave(A, 'Zweitroute');
await until(A, async () => (await window.__sync.list()).length === 2);
await A.waitForSelector('dialog.plans[open] #planList li[data-id]');
await A.click('#planList li:has(.name:text-is("Zweitroute")) .open');
check(!(await A.locator('dialog.overview[open]').isVisible()), 'a row tap opens the plan at once – no overview in between (owner 2026-11-13)');
await A.waitForFunction(() => document.getElementById('savedLine')?.textContent?.includes('Saved as Zweitroute'), null, { timeout: 30_000 }).catch(() => {});
check((await status(A)).folder.key === 'none' && JSON.stringify(await titles(A)) === '["Altroute","Zweitroute"]', 'the copy "Zweitroute" is a second route, "Altroute" stays (Without phone)');
check((await A.textContent('#savedLine')).includes('Saved as Zweitroute'), 'the copy opened: the open route');
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
let row = await listRow(A, 'Zweitroute');
let lv = await liveRow(A);
check(row?.where === HERE && !row.bold && !row.current && /^Plan · \d+ \w+ \d{4}$/.test(row.sub) && lv?.name === 'Zweitroute' && JSON.stringify(lv.tags) === '["In progress","Saved"]',
  `not paired: a row shows 🖥 only, not bold, "Plan · date" (the app's words); the open one is the Recent row „In progress“ + „Saved“ (${JSON.stringify({ row, lv })})`);
const dialogText = await A.textContent('dialog.plans');
check(!/Your planned routes|Save current route|Saved as|Without phone|Remove routes/.test(dialogText) && await A.locator('dialog.plans .rename.iconbtn, dialog.plans h3').count() === 0,
  'My routes is one plain list: no heading line, no Save box, no folder lines, no ✎ / 🗑 in the row');
await A.click('#closePlans');

// ---- 2. pairing: the routes planned without a phone join its folder and go up to it at once – no Save needed
// (owner field 2026-11-12: "erst Speichern drücken ist keine gute Idee") ----
const hw = await phone.save('Handweg');
const p1 = await showCode(A);
check(/^[A-Za-z0-9_-]{44}$/.test(p1.code) && /^\d{3} \d{3}$/.test(p1.check), 'Pair phone: a new 44-character code and six check digits');
await sendMark(A);
await phone.pairDirect(p1.code);
await A.waitForFunction(() => document.getElementById('pairBody')?.textContent?.includes('Paired with Pixel 8'), null, { timeout: 30_000 }).catch(() => {});
check((await A.textContent('#pairBody')).includes('Paired with Pixel 8'), 'the browser says "Paired with Pixel 8" (the phone\'s hello, role 1)');
check((await A.textContent('#pairBody')).includes('What you save in the browser comes to your phone – everything is kept there.') && (await A.textContent('#closePair')) === 'OK',
  'after pairing: the app\'s sentence (sync_paired_plan) with OK');
await A.click('#closePair');
let st = await status(A);
check(st.paired && st.folder.key === phone.device && st.phone === 'Pixel 8', `the phone's folder is open (key = its device id) ${JSON.stringify(st.folder)}`);
check(JSON.stringify(await titles(A)) === '["Altroute","Handweg","Zweitroute"]' && (await titles(A, 'none')).length === 0,
  'the routes planned without a phone joined the phone\'s folder (Without phone is empty) ' + JSON.stringify(await titles(A)));
check((await A.textContent('#points')).includes('Vellau'), 'the open route stays open (it joined the phone\'s folder too)');
await sent(A);
await phone.round();
check(JSON.stringify(await phone.titles()) === '["Altroute","Handweg","Zweitroute"]', 'right after the pairing the waiting routes went to the phone by themselves – no Save ' + JSON.stringify(await phone.titles()));
await A.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'done', null, { timeout: 30_000 }).catch(() => {});
let sb2 = await saveBtn(A);
check(sb2.state === 'done' && !sb2.filled && sb2.label === 'Saved' && (await status(A)).pending === 0, `all on the phone: light "✓ Saved", nothing waits (${JSON.stringify(sb2)})`);
check(sb2.phone && sb2.title === 'Everything is on Pixel 8', `paired: Save carries a small phone, its tooltip says everything is on the phone (owner 2026-11-06) (${JSON.stringify(sb2)})`);
sb = sb2;
e = await entry(A);
check(e.shown && !e.more && e.phone === 'Pixel 8' && /^synced /.test(e.sub) && e.dot && e.kind === 'fresh' && /^Pixel 8 · synced /.test(e.title), `paired: To phone is the phone's status "📱 Pixel 8 🟢 / synced …" (owner 2026-11-07: big enough to see), no ▾ (${JSON.stringify(e)})`);
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
for (const n of ['Altroute', 'Zweitroute']) {
  row = await listRow(A, n);
  check(row?.where === BOTH && !row.bold, `"${n}" sent: 🖥 📱, not bold (${JSON.stringify(row)})`);
}
row = await listRow(A, 'Handweg');
check(row?.where === PHONE && !row.bold && !/New from the phone/.test(row.text), `the phone's route: 📱 only, not bold, no "New from the phone" mark (${JSON.stringify(row)})`);
await A.click('#closePlans');

// ---- 3. the Phone window: state + one sentence, Sync now · Unpair (no question), "How does it work?", small lines ----
await phoneWin(A);
await A.waitForSelector('dialog.phone[open] #unpair:not([hidden])');
const buttons = await A.evaluate(() => [...document.querySelectorAll('#phoneRow button')].filter((b) => !b.hidden).map((b) => b.textContent));
check(buttons.join() === 'Sync now', 'the Phone window: Sync now in the button row ' + JSON.stringify(buttons));
const unp = await A.evaluate(() => { const u = document.getElementById('unpair'), s = document.getElementById('syncNow');
  return { shown: !u.hidden, red: getComputedStyle(u).color === 'rgb(198, 40, 40)', below: u.getBoundingClientRect().top - s.getBoundingClientRect().bottom,
    focused: document.activeElement === u, text: u.textContent }; });
check(unp.shown && unp.red && unp.below > 40 && !unp.focused && unp.text === 'Unpair',
  `Unpair: a red text button at the bottom, away from Sync now, never focused (${JSON.stringify(unp)})`);
check((await A.textContent('#phoneWhy')) === 'Paired with Pixel 8', 'the Phone window names the folder\'s phone');
check(await A.isHidden('#howBox') && await A.isVisible('#pairOther'), 'the explanation is behind "How does it work?"; "Pair another phone" small at the bottom');
await A.click('#howToggle');
check(await A.isVisible('#howBox') && /End-to-end encrypted/.test(await A.textContent('#howBox')) && await A.getAttribute('#howToggle', 'aria-expanded') === 'true',
  '"How does it work?" opens the explanation and the privacy line in place');
await A.click('#closePhone');
// field 2026-11-12 (owner): the phone had lost its pairing (it showed "Pair with browser"), the browser still said
// "Paired · phone last 4 days ago" – and "Sync now" showed nothing. Owner's rule ("like WhatsApp Web"): a click on
// Sync now answers in a short line; silent for 3 days and more the phone turns yellow, and its window says it in one
// sentence with one button "Pair again" (unpair + a new code in one step; the plans stay in the browser).
const byHand = async (P) => {
  await phoneWin(P);
  await P.waitForSelector('dialog.phone[open] #syncNow:not([hidden])');
  await P.evaluate(() => { document.getElementById('phoneStatus').textContent = ''; });
  await P.click('#syncNow');
  await P.waitForFunction(() => !!document.getElementById('phoneStatus')?.textContent, null, { timeout: 15_000 }).catch(() => {});
  const text = await P.textContent('#phoneStatus');
  await P.click('#closePhone');
  return text;
};
const keysP = await phone.keys();
await box('GET', keysP.mailbox, keysP.token, { since: 0 }, Date.now() - 2 * 86_400_000);
await syncNow(A);
e = await entry(A);
check(e.kind === 'fresh' && e.sub === 'synced 2 days ago', `phone away 2 days: still green (${JSON.stringify(e)})`);
let said = await byHand(A);
check(said === 'Synced – nothing new from the phone.', `Sync now with the phone away a while: a short "nothing new" line (${JSON.stringify(said)})`);
await box('GET', keysP.mailbox, keysP.token, { since: 0 }, Date.now() - 4 * 86_400_000);
await syncNow(A);
e = await entry(A);
check(e.kind === 'stale' && e.sub === 'not connected for 4 days' && e.phone === 'Pixel 8', `phone silent 4 days: yellow, "not connected for 4 days" (${JSON.stringify(e)})`);
const before4 = JSON.stringify(await titles(A));
await phoneWin(A);
await A.waitForSelector('dialog.phone[open] #pairAgain:not([hidden])', { timeout: 10_000 }).catch(() => {});
const away = await A.evaluate(() => ({ head: document.getElementById('phoneHead').textContent, why: document.getElementById('phoneWhy').textContent,
  buttons: [...document.querySelectorAll('#phoneRow button')].filter((b) => !b.hidden).map((b) => b.textContent),
  other: !document.getElementById('pairOther').hidden, removes: document.querySelectorAll('dialog.phone .removefolder').length,
  kind: document.getElementById('phoneState').dataset.kind }));
check(away.kind === 'stale' && away.head === 'Your phone has not connected for 4 days. Not syncing any more? Then just pair again.' && !away.why
  && JSON.stringify(away.buttons) === '["Pair again"]' && !away.other && away.removes === 0,
  `the Phone window, phone silent: one sentence, one button "Pair again" (${JSON.stringify(away)})`);
if (process.env.SHOT) await A.screenshot({ path: process.env.SHOT });
await A.click('dialog.phone #pairAgain');
await A.waitForSelector('dialog.pair[open] #pairLink', { timeout: 30_000 }).catch(() => {});
const again = await A.evaluate(() => ({ link: document.getElementById('pairLink')?.value ?? '', paired: window.__sync.status.paired }));
check(/#pair=[A-Za-z0-9_-]{44}$/.test(again.link) && !again.paired, `"Pair again": unpaired and a new code shown in one step (${JSON.stringify(again)})`);
await phone.pairDirect(again.link.split('#pair=')[1]);
await A.waitForFunction(() => document.getElementById('pairBody')?.textContent?.includes('Paired with Pixel 8'), null, { timeout: 30_000 }).catch(() => {});
await A.click('#closePair').catch(() => {});
st = await status(A);
check(st.paired && st.folder.key === phone.device && JSON.stringify(await titles(A)) === before4,
  `paired again: the phone's folder is back with its plans (${JSON.stringify([st.folder, await titles(A), before4])})`);
await phone.round();
await syncNow(A);
check((await entry(A)).kind === 'fresh', 'the phone syncs again → green');
said = await byHand(A);
check(said === 'Synced – everything is up to date.', `Sync now with the phone near: a short "synced" line (${JSON.stringify(said)})`);

// ---- 4. an unsaved change (⋮ Rename) stays over a reload and is NOT sent; Save sends it at once (no field) ----
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
A.once('dialog', (d) => d.accept('Waalweg'));
await rowMenu(A, 'Altroute', 'rename');
await A.waitForFunction(() => [...document.querySelectorAll('#planList .name')].some((n) => n.textContent === 'Waalweg'), null, { timeout: 10_000 }).catch(() => {});
await A.click('#closePlans');
sb = await saveBtn(A);
check(sb.unsaved && sb.state === 'dark', `unsaved rename: Save dark (${JSON.stringify(sb)})`);
check(sb.phone && sb.title === 'Save – to the phone too (Pixel 8)', `paired, something waits: Save's tooltip says it goes to the phone too (owner 2026-11-06) (${JSON.stringify(sb)})`);
await syncNow(A);
await phone.round();
check(!(await phone.titles()).includes('Waalweg'), 'a pull does not send: the phone has no "Waalweg" yet');
await A.reload();
await A.waitForFunction(() => document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 60_000 });
await A.waitForFunction(() => document.getElementById('menuSavePlan')?.classList.contains('unsaved'), null, { timeout: 30_000 }).catch(() => {});
await A.waitForFunction(() => document.getElementById('savedLine')?.textContent?.includes('Saved as'), null, { timeout: 60_000 }).catch(() => {});
sb = await saveBtn(A);
check((await titles(A)).includes('Waalweg') && sb.unsaved, `after a reload: still there, Save still dark (${JSON.stringify(sb)})`);
check((await A.textContent('#savedLine')).includes('Saved as Zweitroute'), 'after a reload the shown route is still "Saved as Zweitroute" (its folder\'s draft)');
await phone.round();
check(!(await phone.titles()).includes('Waalweg'), 'still nothing on the phone');
await A.click('#menuSavePlan');
check(await A.locator('dialog.savename[open]').count() === 0, 'Save with only a waiting rename: no name dialog');
await A.waitForFunction(() => !document.getElementById('menuSavePlan').classList.contains('unsaved'), null, { timeout: 30_000 }).catch(() => {});
sb = await saveBtn(A);
check(!sb.unsaved && sb.state === 'done', `Save: the button is light again (${JSON.stringify(sb)})`);
await phone.round();
check((await phone.titles()).includes('Waalweg'), 'Save sent it: the phone has "Waalweg"');
// sync fields 7 / 8 (web-sync.md §5.2): the browser's Save writes its routing fingerprint and the rd5 day it routed with
const waal = (await phone.list()).find((x) => x.title === 'Waalweg');
const ownFp = (await A.evaluate(() => window.__diag())).match(/routing: (r1-[0-9a-f]{12})/)?.[1];
check(/^r1-[0-9a-f]{12}$/.test(waal?.routing ?? '') && waal.routing === ownFp && /^E10_N45:\d{5}$/.test(waal?.routingData ?? ''),
  `Save writes field 7 = the page's fingerprint, field 8 = the rd5 day (${waal?.routing} = ${ownFp}, ${waal?.routingData})`);

// ---- 5. the phone's changes arrive (📱, a "New" / "Changed" tag as in the app); a rename here via ⋮ is bold until Save
// sends it back (🖥 📱) ----
await phone.save('Vom Handy', CODE, 'r1-0123456789ab', 'E10_N45:20700'); // planned on the phone with other routing
await phone.rename(hw, 'Handweg neu');
await phone.round();
await A.evaluate(() => document.dispatchEvent(new Event('visibilitychange'))); // the tab is visible again → pull
await until(A, async () => (await window.__sync.list()).some((p) => p.title === 'Vom Handy'), null, { timeout: 30_000 }).catch(() => {});
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
row = await listRow(A, 'Vom Handy');
check(row?.where === PHONE && !row.bold && !/from the phone|on the phone/i.test(row.sub) && row.tag === 'New',
  `new from the phone: 📱, not bold, the tag "New" after the name (the app's RouteTag) (${JSON.stringify(row)})`);
const tagLook = await A.evaluate(() => { const g = getComputedStyle(document.querySelector('#planList .tag')); return [g.backgroundColor, g.color, g.borderRadius]; });
check(tagLook[0] === 'rgb(221, 229, 238)' && tagLook[1] === 'rgb(30, 53, 80)', `the tag's look = the app's TagContainer / TagInk (${tagLook})`);
row = await listRow(A, 'Handweg neu');
check(row?.where === PHONE && row.tag === 'Changed' && !/Changed on the phone/.test(row.text), `renamed on the phone: 📱, the tag "Changed" (${JSON.stringify(row)})`);
A.once('dialog', (d) => d.accept('Handweg hier'));
await rowMenu(A, 'Handweg neu', 'rename');
await A.waitForFunction(() => [...document.querySelectorAll('#planList .name')].some((n) => n.textContent === 'Handweg hier'), null, { timeout: 10_000 }).catch(() => {});
row = await listRow(A, 'Handweg hier');
check(row?.where === BOTH && row.bold && (await saveBtn(A)).state === 'dark', `⋮ → Rename: bold, 🖥 📱, Save dark (${JSON.stringify(row)})`);
await A.click('#closePlans');
await sendMark(A);
await A.click('#menuSavePlan');
await sent(A);
await phone.round();
check((await phone.titles()).includes('Handweg hier'), 'Save to phone sent the new name back');
await menu(A, '#menuPlans');
await A.click('#planList li:has-text("Vom Handy") .open');
check(!(await A.locator('dialog.overview[open]').isVisible()), 'a plan from the phone never opened here (no line known) opens at once – no empty overview (owner 2026-11-13)');
await A.waitForFunction(() => document.getElementById('points')?.textContent?.includes('Vellau'), null, { timeout: 30_000 }).catch(() => {});
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
row = await listRow(A, 'Vom Handy');
lv = await liveRow(A);
check(lv?.name === 'Vom Handy' && JSON.stringify(lv.tags) === '["In progress","Saved"]' && row && !row.tag, `a click on a row opens it: the open route is the Recent row „In progress“ + „Saved“ (the app's LivePlanRows), its New / Changed tag gone (${JSON.stringify({ lv, row })})`);
await A.click('#closePlans');
const note = await A.evaluate(() => { const n = document.getElementById('routingNote'); return n && !n.hidden ? n.textContent : ''; });
check(note === 'Planned on the phone with other routing – the route may differ slightly',
  `a plan from the phone with another fingerprint: one grey line ("${note}")`);

// ---- 6. conflict: both kept – the version that reached the mailbox first keeps the route, the other is a "(copy)" ----
const wid = await phone.idOf('Waalweg');
await phone.rename(wid, 'Phone name');
await phone.round();
await A.waitForTimeout(20);
await A.evaluate((i) => window.__sync.rename(i, 'Browser name'), wid);
await syncNow(A);
const want = '["Browser name (copy)","Handweg hier","Phone name","Vom Handy","Zweitroute"]';
check(JSON.stringify(await titles(A)) === want, 'conflict (owner 2026-11-12: the version seen decides, not the clock): the phone reached the mailbox first and keeps the route, the browser’s rename it never saw stays as a copy ' + JSON.stringify(await titles(A)));
await sendMark(A);
await A.click('#menuSavePlan');
await sent(A);
await phone.round();
check(JSON.stringify(await phone.titles()) === want, 'after Save the phone has the same two ' + JSON.stringify(await phone.titles()));
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
check(/^Plan · /.test((await listRow(A, 'Browser name (copy)'))?.sub ?? ''), 'the copy is a row like any other');
await A.click('#closePlans');

// ---- 7. delete both ways ----
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
await rowMenu(A, 'Browser name (copy)', 'del'); // ⋮ → Delete (no 🗑 in the row)
// in the phone's folder no question any more (the app, owner 2026-11-06: the phone's recycle bin is the net)
await A.waitForFunction(() => ![...document.querySelectorAll('#planList .name')].some((n) => n.textContent === 'Browser name (copy)'), null, { timeout: 10_000 }).catch(() => {});
check(await A.locator('dialog.plans .confirm').count() === 0 && !(await A.evaluate(() => [...document.querySelectorAll('#planList .name')].some((n) => n.textContent === 'Browser name (copy)'))),
  'delete in the phone\'s folder: no question, the row goes (the phone\'s recycle bin is the net)');
await A.click('#closePlans');
await phone.round();
check((await phone.titles()).includes('Browser name (copy)'), 'deleted in the browser, not saved yet: still on the phone');
await sendMark(A);
await A.click('#menuSavePlan');
await sent(A);
await phone.round();
check(!(await phone.titles()).includes('Browser name (copy)'), 'deleted + Save → gone on the phone (its app moves it to the recycle bin)');
await phone.remove(await phone.idOf('Vom Handy'));
await phone.round();
await syncNow(A);
check(!(await titles(A)).includes('Vom Handy'), 'deleted on the phone → gone in the browser');

// ---- 8. GPX goes with Save ----
await A.click('#newPlan'); if (await A.locator('dialog.discard[open]').count()) await A.click('#discardOk'); // an empty map: only the track is new
await A.setInputFiles('#gpxFile', path.join(here, 'fixtures/komoot-hike.gpx'));
await until(A, async () => (await window.__sync.list()).some((p) => p.kind === 'track'), null, { timeout: 120_000 }).catch(() => {});
check((await A.evaluate(async () => (await window.__sync.list()).filter((p) => p.kind === 'track').map((p) => p.title))).join() === 'Meran 2000 & Ifinger Hütte', 'an imported GPX is kept in the folder as a track');
check((await saveBtn(A)).unsaved, 'the imported track is unsaved: Save dark');
await A.click('#closeTrack');
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-kind="track"]');
let tr = await listRow(A, 'Meran 2000 & Ifinger Hütte');
check(tr?.kind === 'track' && /^Imported GPX · /.test(tr.sub) && tr.bold && tr.where === HERE, `the track's row: "Imported GPX · date" (the app's words), bold, 🖥 (${JSON.stringify(tr)})`);
const dl = A.waitForEvent('download', { timeout: 30_000 }).catch(() => null);
// the name the page gives (headless Chromium reports non-ASCII download names as "download")
await A.evaluate(() => { const c = HTMLAnchorElement.prototype.click; HTMLAnchorElement.prototype.click = function () { window.__dlName = this.download; return c.call(this); }; });
await rowMenu(A, 'Meran 2000 & Ifinger Hütte', 'export');
const file = await dl;
const gpx = file ? fs.readFileSync(await file.path(), 'utf8') : '';
const dlName = await A.evaluate(() => window.__dlName);
check(dlName === 'Meran 2000 _ Ifinger Hütte.gpx' && /<trkpt /.test(gpx), `⋮ → Export GPX: the track as a GPX file, named as the app names it (${dlName}, ${gpx.length} bytes)`);
await sendMark(A);
await A.click('#menuSavePlan');
await sent(A);
await phone.round();
check((await phone.list()).some((x) => x.kind === 'track' && x.title === 'Meran 2000 & Ifinger Hütte' && x.track.length > 100), 'Save sent the track (kind TRACK) to the phone');

// ---- 10. a second browser (laptop) via the phone's JOIN: since = 0, transitional mailbox deleted ----
const ctxL = await browser.newContext({ viewport: { width: 1100, height: 760 } });
const L = await open(ctxL);
const pL = await showCode(L);
const kL = await phone.keys(pL.code);
check(await phone.redirect(pL.code) === 200, 'the phone writes one JOIN into the laptop\'s fresh mailbox');
await L.waitForFunction(() => document.getElementById('pairBody')?.textContent?.includes('Paired with Pixel 8'), null, { timeout: 30_000 }).catch(() => {});
check((await L.textContent('#pairBody')).includes('Paired with Pixel 8'), 'the laptop: "Paired with Pixel 8"');
await L.click('#closePair');
st = await status(L);
check(st.folder.key === phone.device && JSON.stringify(await titles(L)) === JSON.stringify(await phone.titles()), 'the laptop opened the phone\'s folder and read its mailbox from the start ' + JSON.stringify(await titles(L)));
await L.waitForTimeout(300);
check((await box('GET', kL.mailbox, kL.token, { since: 0 })).status === 410, 'the laptop\'s transitional mailbox is deleted');
check(st.check === (await phone.keys()).check, 'the laptop now uses the phone\'s pairing');
await L.evaluate(() => { location.hash = '#p=AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; });
await L.waitForFunction(() => document.getElementById('points')?.textContent?.includes('Vellau'), null, { timeout: 30_000 });
await sendMark(L);
await saveAs(L, 'Laptopweg'); // paired: the field's Save also sends
await sent(L);
await phone.round();
check((await phone.titles()).includes('Laptopweg'), 'Save on the laptop → on the phone');
await syncNow(A);
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
row = await listRow(A, 'Laptopweg');
check(row?.where === PHONE && !row.bold, `the desktop sees the laptop's route as on the phone: 📱, not bold (${JSON.stringify(row)})`);
await A.click('#closePlans');

// ---- 11. a friend's PC: another phone opens its folder, the first stays untouched; the same phone again ----
await L.evaluate((i) => window.__sync.rename(i, 'Laptop unsaved'), await L.evaluate(async () => (await window.__sync.list()).find((p) => p.title === 'Laptopweg').id));
const friend = await new Phone('Pixel 8').init();
await friend.save('Freundesweg');
const pF = await showCode(L, true);
await friend.pairDirect(pF.code);
await L.waitForFunction((d) => window.__sync.status.folder.key === d, friend.device, { timeout: 30_000 }).catch(() => {});
await L.click('#closePair').catch(() => {});
st = await status(L);
check(st.folder.key === friend.device && JSON.stringify(await titles(L)) === '["Freundesweg"]', 'pairing another phone opens its folder ' + JSON.stringify(await titles(L)));
check((await titles(L, phone.device)).includes('Laptop unsaved'), 'the first phone\'s folder is untouched, its unsaved change kept');
await L.waitForTimeout(500);
sb = await saveBtn(L);
check(sb.state !== 'dark' && !(await L.textContent('#points')).includes('Vellau'), `the friend's phone: none of the first phone's routes is seen, Save not dark (${JSON.stringify(sb)})`);
await phone.round();
check(!(await phone.titles()).includes('Laptop unsaved') && !(await phone.titles()).includes('Freundesweg'), 'nothing crossed to the first phone');
check(st.closed.length === 1 && /^Pixel 8 · paired /.test(st.closed[0].name) && /^Pixel 8 · paired /.test(st.phone), 'two phones named "Pixel 8": the pairing date is added ' + JSON.stringify([st.phone, st.closed]));
// unpair in the browser: no question, the folder closes, the quiet "Remove routes of …" lines
await phoneWin(L);
await L.click('dialog.phone #unpair');
await L.waitForFunction(() => window.__sync.status.folder.key === 'none' && document.getElementById('unpair')?.hidden, null, { timeout: 30_000 }).catch(() => {});
check(await L.locator('dialog.phone .confirm').count() === 0 && (await status(L)).folder.key === 'none', 'Unpair: no question, the folder closes (Without phone)');
await L.waitForFunction(() => document.getElementById('phoneStatus')?.textContent?.startsWith('Unpaired. The plans of Pixel 8'), null, { timeout: 10_000 }).catch(() => {}); // CI draws it a moment later
const quiet = await L.textContent('#phoneStatus');
check(quiet.startsWith('Unpaired. The plans of Pixel 8'), 'the quiet line says the routes stay ' + quiet);
// old phones' folders stay quietly in this browser (owner 2026-11-12, "like WhatsApp Web"): no "Remove plans of …" lines
check(await L.locator('dialog.phone .removefolder').count() === 0 && (await status(L)).closed.length === 2
  && (await titles(L, phone.device)).includes('Laptop unsaved'), 'no remove lines for old phones; their folders and plans stay');
await L.click('#closePhone');
// the same phone again (it is still paired, so it redirects): its folder is back with the unsaved change
const pL2 = await showCode(L);
await phone.redirect(pL2.code);
await L.waitForFunction((d) => window.__sync.status.folder.key === d, phone.device, { timeout: 30_000 }).catch(() => {});
await L.click('#closePair').catch(() => {});
await L.waitForFunction(() => window.__sync.status.pending === 0 && !document.getElementById('menuSavePlan')?.classList.contains('unsaved'), null, { timeout: 30_000 }).catch(() => {});
await phone.round();
check((await titles(L)).includes('Laptop unsaved') && !(await saveBtn(L)).unsaved && (await phone.titles()).includes('Laptop unsaved'),
  'the same phone paired again: its folder is back and the unsaved change went to the phone at once (no Save)');

// ---- 12. mailbox full (507): Save amber (its tooltip says why), nothing lost ----
LIMITS.items = 1; // the test's limit in the host
await L.click('#newPlan'); if (await L.locator('dialog.discard[open]').count()) await L.click('#discardOk');
await openRoute(L, '#p=' + CODE); // a new route on the map
await saveAs(L, 'Neuweg'); // a new item – the host counts items, a rename of one it has is fine
await L.waitForFunction(() => window.__sync.status.full, null, { timeout: 30_000 }).catch(() => {});
sb = await saveBtn(L);
check(sb.state === 'full' && sb.title.startsWith('Mailbox full') && !sb.hint && (await status(L)).pending >= 1, `507: Save amber, tooltip "Mailbox full – …", the change kept (${JSON.stringify(sb)})`);
LIMITS.items = 1000;
await sendMark(L);
await L.click('#menuSavePlan');
await sent(L);
await phone.round();
check((await phone.titles()).includes('Laptop unsaved') && (await saveBtn(L)).state !== 'full', 'room again: Save sends it, no longer amber');

// ---- 13. the server holds nothing readable ----
const rows = env.SYNC.sqlite.prepare('SELECT blob FROM item').all();
const raw = rows.map((r) => Buffer.from(r.blob, 'base64url').toString('latin1')).join('|');
check(rows.length >= 5 && !/Waalweg|Browser name|Phone name|Pixel|Chrome|Altroute/.test(raw), `server stores ${rows.length} blobs, no route or name readable`);

// ---- 14. "Unpair?" on the phone: every browser closes the folder, keeps the routes, the quiet line ----
check(await phone.end() === 200, 'the phone ends the pairing (DELETE)');
await syncNow(A);
await syncNow(L);
for (const [P, n] of [[A, 'desktop'], [L, 'laptop']]) {
  const s = await status(P);
  check(!s.paired && s.folder.key === 'none' && s.message === 'The connection was ended on the phone.' && (await titles(P, phone.device)).length >= 3,
    `${n}: the folder closed, the routes stay, "The connection was ended on the phone." (${s.message})`);
}
e = await entry(A);
check(!e.dot && e.sub === '', 'the plan bar follows: "To phone" without the phone\'s line');
await A.reload();
await A.waitForFunction(() => document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 60_000 });
check((await titles(A, 'none')).length === 0 && (await titles(A, phone.device)).includes('Zweitroute') && (await saveBtn(A)).state !== 'dark',
  'Without phone opens empty (its routes went to the phone\'s folder, kept here); Save not dark');

// ---- 14b. two tabs of one browser (incident 2026-10-31): a pairing / unpairing in one tab is seen in the other at once
// (BroadcastChannel + the folders read again in every locked step) – the other tab never writes into the old folder ----
env.SYNC_NEW_PER_IP_DAY = '50'; // two more pairings below (the test host's 50 a day; the default 5 is reached by now)
const ctxT = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const T1 = await open(ctxT);
const T2 = await open(ctxT);
const tabPhone = await new Phone('Tab phone').init();
const pT = await showCode(T1);
await tabPhone.pairDirect(pT.code);
await T1.waitForFunction((d) => window.__sync.status.folder.key === d, tabPhone.device, { timeout: 30_000 }).catch(() => {});
await T1.click('#closePair').catch(() => {});
await T2.waitForFunction((d) => window.__sync.status.folder.key === d && window.__sync.status.paired, tabPhone.device, { timeout: 10_000 }).catch(() => {});
// the header's pill draws a moment after the status (CI is slower than the status check)
await T2.waitForFunction(() => document.querySelector('#syncBadge.paired .lbl')?.textContent?.startsWith('Tab phone'), null, { timeout: 10_000 }).catch(() => {});
const t2 = await status(T2);
check(t2.paired && t2.folder.key === tabPhone.device && (await entry(T2)).phone === 'Tab phone',
  `paired in tab 1 → tab 2 shows it without a reload (${JSON.stringify(t2.folder)}, "${(await entry(T2)).sub}")`);
await phoneWin(T1);
await T1.click('dialog.phone #unpair');
await T1.waitForFunction(() => window.__sync.status.folder.key === 'none', null, { timeout: 30_000 }).catch(() => {});
await T1.click('#closePhone').catch(() => {});
await T2.waitForFunction(() => window.__sync.status.folder.key === 'none' && !window.__sync.status.paired, null, { timeout: 10_000 }).catch(() => {});
await T2.waitForFunction(() => document.querySelector('#syncBadge .sub')?.hidden, null, { timeout: 10_000 }).catch(() => {});
check(!(await status(T2)).paired && !(await entry(T2)).dot && (await entry(T2)).sub === '', 'unpaired in tab 1 → tab 2 shows "To phone" without the phone, without a reload');
await openRoute(T2, '#p=' + CODE);
await T2.evaluate(() => window.__sync.save(location.hash.slice(3), 'Zweiter Tab'));
await syncNow(T2);
await tabPhone.round();
check(!(await tabPhone.titles()).includes('Zweiter Tab') && (await titles(T2, 'none')).includes('Zweiter Tab') && !(await titles(T2, tabPhone.device)).includes('Zweiter Tab'),
  'tab 2 saves after the unpairing: into Without phone, nothing written back to the phone\'s folder or mailbox');
await ctxT.close();

// ---- 14c. the phone unpairs itself and only deletes its hello (the app's guard b585cbc): the browser closes the folder ----
const ctxH = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const H1 = await open(ctxH);
const helloPhone = await new Phone('Hello phone').init();
const pH = await showCode(H1);
await helloPhone.pairDirect(pH.code);
await H1.waitForFunction((d) => window.__sync.status.folder.key === d, helloPhone.device, { timeout: 30_000 }).catch(() => {});
await H1.click('#closePair').catch(() => {});
helloPhone.ledger = await call('syncDelete', helloPhone.ledger, await call('syncHelloId', helloPhone.device), Date.now(), helloPhone.device);
await helloPhone.round();
await syncNow(H1);
const hs = await status(H1);
check(!hs.paired && hs.folder.key === 'none' && hs.message === 'The connection was ended on the phone.' && !(await entry(H1)).dot,
  `the phone's hello deleted: the folder closes, "The connection was ended on the phone.", no phone dot (${hs.message})`);
await ctxH.close();

// ---- 14d. after Unpair: the old phone's folder stays quietly in this browser (owner 2026-11-12, no remove lines) ----
const ctxR = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const R = await open(ctxR);
const restPhone = await new Phone('Rest phone').init();
await restPhone.save('Handyroute');
const pR = await showCode(R);
await restPhone.pairDirect(pR.code);
await R.waitForFunction((d) => window.__sync.status.folder.key === d, restPhone.device, { timeout: 30_000 }).catch(() => {});
await R.click('#closePair').catch(() => {});
await until(R, async () => (await window.__sync.list()).some((p) => p.title === 'Handyroute'));
await R.evaluate((c) => window.__sync.save(c, 'Nie gesendet'), CODE); // saved here, never sent (no Save to the phone)
await phoneWin(R);
await R.click('dialog.phone #unpair');
await R.waitForFunction(() => window.__sync.status.folder.key === 'none' && window.__sync.status.closed.length === 1, null, { timeout: 30_000 }).catch(() => {});
await R.click('#closePhone');
await menu(R, '#menuPlans');
await R.waitForSelector('#planList li', { timeout: 10_000 });
check(!(await R.locator('#planList li.oldphone').count()) && (await R.textContent('#planList')).includes('No plans yet.'),
  'after Unpair My tours is plain: no line about the old phone (owner 2026-11-12: old folders stay quietly)');
check((await status(R)).closed.length === 1 && (await titles(R, restPhone.device)).includes('Nie gesendet'), 'the old phone\'s folder and its plans stay in this browser');
await R.click('#closePlans');
await ctxR.close();

// ---- 14e. "Pair again" with a reinstalled phone (owner field 2026-11-12, robust first): the phone comes back with a NEW
// device id (no backup of device.txt) – the tours of the folder that "Pair again" closed come along into the new
// pairing and go up at once (owner 2026-11-12: no Save needed); an older closed folder stays where it is ----
const ctxW = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const W = await open(ctxW);
const older = await new Phone('Old S10').init();
const pO = await showCode(W);
await older.pairDirect(pO.code);
await W.waitForFunction((d) => window.__sync.status.folder.key === d, older.device, { timeout: 30_000 }).catch(() => {});
await W.click('#closePair').catch(() => {});
await W.evaluate((c) => window.__sync.save(c, 'S10-Weg'), CODE);
const firstPixel = await new Phone('Pixel 8').init();
await firstPixel.save('Handytour');
const pB = await showCode(W, true);
await firstPixel.pairDirect(pB.code);
await W.waitForFunction((d) => window.__sync.status.folder.key === d, firstPixel.device, { timeout: 30_000 }).catch(() => {});
await W.click('#closePair').catch(() => {});
await until(W, async () => (await window.__sync.list()).some((p) => p.title === 'Handytour'));
await W.evaluate((c) => window.__sync.save(c, 'Browsertour'), CODE);
await W.evaluate(() => window.__sync.sendAll());
await firstPixel.round();
check(JSON.stringify(await firstPixel.titles()) === '["Browsertour","Handytour"]', 'set-up: two tours on the first Pixel ' + JSON.stringify(await firstPixel.titles()));
const kB = await firstPixel.keys();
await box('GET', kB.mailbox, kB.token, { since: 0 }, Date.now() - 4 * 86_400_000);
await W.evaluate(() => window.__sync.syncNow());
await phoneWin(W);
await W.waitForSelector('dialog.phone[open] #pairAgain:not([hidden])', { timeout: 10_000 }).catch(() => {});
await W.click('dialog.phone #pairAgain');
await W.waitForSelector('dialog.pair[open] #pairLink', { timeout: 30_000 }).catch(() => {});
const reinstalled = await new Phone('Pixel 8').init(); // the same phone after a reinstall: a new device id, no tours
check(reinstalled.device !== firstPixel.device, 'the reinstalled phone has a new device id');
await reinstalled.pairDirect((await W.inputValue('#pairLink')).split('#pair=')[1]);
await W.waitForFunction((d) => window.__sync.status.folder.key === d, reinstalled.device, { timeout: 30_000 }).catch(() => {});
await W.click('#closePair').catch(() => {});
await until(W, async () => window.__sync.status.pending === 0 && (await window.__sync.list()).length === 2);
const carried = await W.evaluate(async () => (await window.__sync.list()).map((p) => [p.title, p.dirty]).sort());
check((await status(W)).folder.key === reinstalled.device && JSON.stringify(carried) === '[["Browsertour",false],["Handytour",false]]',
  `"Pair again" + a new device id: the closed folder's tours came along and were sent at once – no Save (${JSON.stringify(carried)})`);
const leftW = await W.evaluate(() => window.__sync.status.closed.map((f) => f.name));
check((await titles(W, firstPixel.device)).length === 0 && (await titles(W, older.device)).includes('S10-Weg') && !(await titles(W)).includes('S10-Weg'),
  `only the folder "Pair again" closed came along; the older S10 folder stays as it was (${JSON.stringify(leftW)})`);
await reinstalled.round();
check(JSON.stringify(await reinstalled.titles()) === '["Browsertour","Handytour"]', 'the reinstalled phone has both tours without any Save ' + JSON.stringify(await reinstalled.titles()));
await ctxW.close();

// ---- 15. the host's cap on new mailboxes per IP and day ----
env.SYNC_NEW_PER_IP_DAY = '1';
await phoneWin(A);
await A.click('dialog.phone #pairPhone');
await A.waitForSelector('#pairBody .status.error', { timeout: 30_000 }).catch(() => {});
check(((await A.textContent('#pairBody').catch(() => '')) ?? '').includes('Too many new pairings from this network today'), 'over the new-mailbox cap: the page says so');

// ---- 16. the owner's two scenarios with the plan bar (owner 2026-11-06, A): + Neu · Öffnen · Speichern ▾ · Aufs Handy ----
// (1) first visit: empty, not paired → a plan → Speichern keeps it in this browser → after a reload Öffnen lists it →
//     Aufs Handy pairs the phone and, once paired, the plan goes onto it; (2) second visit: Öffnen → the search finds
//     it → open → change → Speichern sends; Speichern ▾ → Speichern unter makes a second plan
env.SYNC_NEW_PER_IP_DAY = '1000';
const ownerPhone = await new Phone('Owner phone').init();
const ctxO = await browser.newContext({ viewport: { width: 1280, height: 800 } });
let O = await open(ctxO);
check(await O.isDisabled('#newPlan') && await O.isDisabled('#menuSavePlan') && await O.isEnabled('#menuPlans') && await O.isEnabled('#syncBadge'),
  'scenario 1: an empty first visit – New and Save greyed, Open and To phone ready');
await openRoute(O, '#p=' + CODE);
// the route planned (and its points snapped) before saving – a snap after Save would read as "changed"
await O.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1', null, { timeout: 120_000 }).catch(() => {});
await O.waitForTimeout(1500);
await O.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'dark', null, { timeout: 30_000 }).catch(() => {});
await O.click('#menuSavePlan');
await answerSave(O, 'Meine Runde');
await until(O, async () => (await window.__sync.list()).some((p) => p.title === 'Meine Runde'));
await O.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'done', null, { timeout: 30_000 }).catch(() => {});
check((await O.getAttribute('#menuSavePlan', 'data-state')) === 'done' && (await O.textContent('#menuSavePlan')).trim() === 'Saved', 'scenario 1: Save keeps it in this browser – "✓ Saved"');
await O.reload();
await O.waitForFunction(() => document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 60_000 });
await O.click('#menuPlans');
await O.waitForSelector('#planList li[data-id]', { timeout: 10_000 }).catch(() => {});
check(!!(await listRow(O, 'Meine Runde')) && await O.isVisible('#planSearch') && await O.isVisible('#plansImportGpx'),
  'scenario 1: after a reload Open lists "Meine Runde" (with the search field and Import GPX)');
await O.click('#closePlans');
await O.click('#syncBadge'); // not paired: the pairing window at once
await O.waitForSelector('dialog.pair[open] #pairLink', { timeout: 30_000 });
// while the pairing moves the open route into the phone's folder (openDraft) the page must keep knowing it is the saved
// "Meine Runde" (its "Saved as" line stays): a moment without it let OK (Save follows the pairing) ask for a name
// again – CI 2026-11-13, "no extra question" red
await O.evaluate(() => { const l = document.getElementById('savedLine'); window.__savedLine = [l.hidden ? 'hidden' : l.textContent];
  new MutationObserver(() => window.__savedLine.push(l.hidden ? 'hidden' : l.textContent)).observe(l, { attributes: true, childList: true, subtree: true, characterData: true }); });
await ownerPhone.pairDirect((await O.inputValue('#pairLink')).split('#pair=')[1]);
await O.waitForFunction(() => document.getElementById('pairBody')?.textContent?.includes('Paired with Owner phone'), null, { timeout: 30_000 }).catch(() => {});
// the owner reads the dialog a moment: the pairing's own first round has sent "Meine Runde" before OK – OK then found
// nothing waiting and Save asked for the name of the saved plan (CI 2026-11-13 run 43396231433: the slow machine's
// timing; here made certain by waiting for that round)
await O.waitForFunction(() => window.__sync.status.paired && window.__sync.status.pending === 0 && window.__sync.status.lastSync, null, { timeout: 30_000 }).catch(() => {});
for (let end = Date.now() + 30_000; Date.now() < end && !(await ownerPhone.titles()).includes('Meine Runde');) { await new Promise((r) => setTimeout(r, 250)); await ownerPhone.round(); }
await sendMark(O);
await O.click('#closePair'); // OK: the plan follows the pairing (Save sends what waits)
await sent(O);
await ownerPhone.round();
const savedLine = await O.evaluate(() => window.__savedLine);
check(savedLine.every((x) => x.includes('Meine Runde')), `scenario 1: the pairing keeps the open route saved as "Meine Runde" throughout (${JSON.stringify([...new Set(savedLine)])})`);
const openDialogs = () => O.evaluate(() => [...document.querySelectorAll('dialog[open]')].map((d) => d.className + ': ' + d.textContent.replace(/\s+/g, ' ').trim().slice(0, 120)));
check((await ownerPhone.titles()).includes('Meine Runde') && (await openDialogs()).length === 0, 'scenario 1: To phone paired the phone and "Meine Runde" arrived on it, no extra question ' + JSON.stringify(await ownerPhone.titles()) + ' ' + JSON.stringify(await openDialogs()));
const e2 = await entry(O);
check(e2.dot && e2.phone === 'Owner phone', `paired: the 📱 status with the dot and the phone's name (${JSON.stringify(e2)})`);
// the saved, unchanged plan on the map is its own saved row („In Bearbeitung“) – no extra „Current plan“ row and no
// Save on it after the pairing moved it into the phone's folder (screenshot round 2026-11-07)
await O.click('#menuPlans');
await O.waitForSelector('#planList li[data-id]', { timeout: 10_000 }).catch(() => {});
const liveRows = await liveRow(O);
check(liveRows?.name === 'Meine Runde' && JSON.stringify(liveRows.tags) === '["In progress","Saved"]' && !liveRows.save,
  `paired, saved and unchanged: the plan on the map is the "Meine Runde" Recent row („In progress“ + „Saved“), no "Current plan", no Save (${JSON.stringify(liveRows)})`);
await O.click('#closePlans');
// (2) second visit
await O.close();
O = await open(ctxO);
await O.click('#menuPlans');
await O.waitForSelector('#planList li[data-id]', { timeout: 10_000 });
await O.fill('#planSearch', 'xyz');
await O.waitForFunction(() => document.getElementById('planList')?.textContent?.includes('Nothing called'), null, { timeout: 5_000 }).catch(() => {});
check((await O.textContent('#planList')).trim() === 'Nothing called “xyz” here', 'scenario 2: the search says when nothing fits (' + (await O.textContent('#planList')).trim() + ')');
await O.fill('#planSearch', 'MEINE runde');
await O.waitForFunction(() => document.querySelectorAll('#planList li[data-id]').length === 1, null, { timeout: 5_000 }).catch(() => {});
check(await O.locator('#planList li[data-id]').count() === 1 && !!(await listRow(O, 'Meine Runde')), 'scenario 2: the search finds "Meine Runde" (every word, any case)');
await O.click('#planSearchClear');
check((await O.inputValue('#planSearch')) === '' && await O.locator('#planList li[data-id]').count() >= 1, 'the search\'s ✕ clears it, the whole list again');
await O.fill('#planSearch', 'runde');
await O.click('#planList li:has(.name:text-is("Meine Runde")) .open');
await O.waitForFunction(() => document.getElementById('savedLine')?.textContent?.includes('Meine Runde'), null, { timeout: 30_000 }).catch(() => {});
const before = (await ownerPhone.list()).find((x) => x.title === 'Meine Runde');
await O.click('#reverse');
await O.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'dark', null, { timeout: 30_000 }).catch(() => {});
await sendMark(O);
await O.click('#menuSavePlan');
await answerSave(O, null);
await sent(O);
await ownerPhone.round();
const after = (await ownerPhone.list()).find((x) => x.title === 'Meine Runde');
check(!!after && JSON.stringify(after) !== JSON.stringify(before) && (await ownerPhone.titles()).filter((x) => x === 'Meine Runde').length === 1,
  'scenario 2: opened, changed, Save – the changed "Meine Runde" is on the phone (still one)');
// Speichern ▾ → Speichern unter: a second plan under a new name, the open one from then on
await O.click('#saveMore');
await O.click('#saveAsItem');
await O.waitForSelector('dialog.savename[open] #saveTitle');
check((await O.textContent('dialog.savename h2')) === 'Save as …' && (await O.inputValue('#saveTitle')) === 'Meine Runde (2)', 'Save ▾ → Save as: its own dialog, "Meine Runde (2)" proposed');
await sendMark(O);
await answerSave(O, 'Andere Runde');
await sent(O);
await ownerPhone.round();
check(JSON.stringify((await ownerPhone.titles()).filter((x) => /Runde/.test(x))) === '["Andere Runde","Meine Runde"]' && (await O.textContent('#savedLine')).includes('Andere Runde'),
  'Save as: "Andere Runde" next to "Meine Runde" (both on the phone), the new one is open');

// ---- 17. 250 tours per phone (owner 2026-11-07, the shared SyncLimits): „N of 250 tours“ in the Phone window, amber
// from 200; a NEW tour beyond 250 is refused before the name is asked (Save amber, why in its tooltip); room again
// after a delete. Seeded by the phone (its tours come over by sync) ----
const fillOf = async (P) => { await phoneWin(P); await P.waitForSelector('dialog.phone #phoneFill:not([hidden])', { timeout: 10_000 }).catch(() => {});
  const f = await P.evaluate(() => { const e = document.getElementById('phoneFill'); return e ? { text: e.textContent.trim(), kind: e.dataset.kind, width: e.querySelector('.fillbar i').style.width } : null; });
  await P.click('#closePhone'); return f; };
const toursAre = async (n) => { // one pull round brings them (a page is 200 items); once more if the first ran before the phone's last write
  for (let i = 0; i < 2 && (await status(O)).tours !== n; i++) {
    await sendMark(O); await O.evaluate(() => window.__sync.syncNow());
    await O.waitForFunction(() => (window.__sync.status.lastSync ?? 0) > window.__sendMark, null, { timeout: 120_000 }).catch(() => {});
  }
};
const seed = async (n) => { // the phone sends its outbox in batches (64 a round): rounds until nothing waits
  for (let i = 0; i < n; i++) await ownerPhone.save(`Seed ${ownerPhone.seeded = (ownerPhone.seeded ?? 0) + 1}`);
  for (let r = 0; r < 10; r++) { await ownerPhone.round(); if (JSON.parse(await call('syncState', ownerPhone.ledger)).pending === 0) break; }
};
await seed(199 - (await ownerPhone.list()).length);
await toursAre(199);
let fl = await fillOf(O);
check(fl?.text === '199 of 250 tours' && fl.kind === 'ok', `250 per phone: the Phone window says "199 of 250 tours" (${JSON.stringify(fl)})`);
await seed(1);
await toursAre(200);
fl = await fillOf(O);
check(fl?.text === '200 of 250 tours' && fl.kind === 'warn' && fl.width === '80%', `from 200 (80 %) the fill is amber (${JSON.stringify(fl)})`);
await seed(50);
await toursAre(250);
await O.click('#newPlan'); if (await O.locator('dialog.discard[open]').count()) await O.click('#discardOk');
await openRoute(O, '#p=' + CODE);
await O.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'dark', null, { timeout: 60_000 }).catch(() => {});
await O.click('#menuSavePlan');
await O.waitForFunction(() => window.__sync.status.full, null, { timeout: 10_000 }).catch(() => {});
sb = await saveBtn(O);
check(sb.state === 'full' && sb.title === '250 tours on the phone – delete some first.' && await O.locator('dialog.savename[open]').count() === 0
  && (await ownerPhone.list()).length === 250, `the 251st tour is refused before its name is asked: Save amber, "250 tours on the phone – …" (${JSON.stringify(sb)})`);
await ownerPhone.remove(await ownerPhone.idOf('Seed 1'));
await ownerPhone.round();
await toursAre(249);
check(!(await status(O)).full && (await saveBtn(O)).state !== 'full', 'a tour deleted on the phone: room again, Save no longer amber');
await O.click('#menuSavePlan');
await answerSave(O, 'Tour 250');
await until(O, async () => (await window.__sync.list()).some((p) => p.title === 'Tour 250'));
await O.waitForFunction(() => window.__sync.status.tours === 250, null, { timeout: 10_000 }).catch(() => {}); // the count follows the save a moment later
check((await status(O)).tours === 250, 'the 250th tour may be saved');
await ctxO.close();

await browser.close(); server.close();
const real = errors.filter((e) => !/status of (404|409|410|429|507)/.test(e) && !/ERR_INTERNET_DISCONNECTED|Failed to fetch/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
console.log(fails.length ? `FAILED: ${fails.length}` : 'all ok');
process.exit(fails.length ? 1 : 0);
