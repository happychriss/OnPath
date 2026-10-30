// Plan sync with folders (owner order A-017, docs/design/web-sync.md §14; what the page shows: §14.6) end to end: two
// browser contexts (A = desktop, L = laptop) and fake phones against the host's real mailbox code (web/host/src/sync.js)
// on a fake D1 (Node's SQLite). A fake phone is the app's own shared sync code (com.onpath.app.sync in the Wasm engine
// of a third, idle page) driven from Node with the real crypto: its hello says role 1, it writes routes, renames,
// deletes, the JOIN redirect (§5.1) and "Unpair?" (DELETE) – what the phone app does. Proves: the header's Save is
// "Save" before pairing and "Save to phone" after, dark while anything is to save, light ✓ when all is saved; routes
// planned without a phone join the next paired phone as waiting (bold, Save dark, nothing sent until the click → 🖥 📱);
// unsaved edits survive a reload and are not sent; My routes is one plain list (🖥 / 📱, ⋮ Rename / Export GPX /
// Delete, the open route light green, no marks, no heading, no Save box); a conflict keeps "(copy)"; delete both ways;
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
  await page.waitForFunction(() => window.__sync?.status && document.getElementById('status')?.textContent?.includes('engine ready'), null, { timeout: 120_000 }).catch(() => {});
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
/** A header entry: inline on a wide screen, behind ☰ at phone width. */
const menu = async (P, id) => { if (await P.isVisible('#menuBtn')) await P.click('#menuBtn'); await P.click(id); };
/** The header's Phone button: its name, the second line, title, whether the dot shows and its colour. */
const entry = (P) => P.evaluate(() => { const b = document.getElementById('syncBadge'), d = b.querySelector('.syncdot'), sub = b.querySelector('.sub');
  return { label: b.querySelector('.lbl').textContent.trim(), sub: sub.hidden ? '' : sub.textContent, title: b.title, dot: !d.hidden, kind: d.dataset.kind }; });
/** The header's Save (§14.6): its state (dark / done / empty / full), label, whether it is filled, and that no hint is beside it. */
const saveBtn = (P) => P.evaluate(() => { const b = document.getElementById('menuSavePlan');
  return { state: b.dataset.state, label: b.textContent.trim(), unsaved: b.classList.contains('unsaved'), filled: getComputedStyle(b).backgroundColor === 'rgb(36, 95, 70)',
    disabled: b.disabled, title: b.title, hint: !!document.getElementById('saveHint') || /Not saved on the phone/.test(document.getElementById('header').textContent) }; });
/** Pair from the Phone window: a new code each time; returns the code (the part after #pair=) and the check digits. */
async function showCode(P, other = false) {
  await menu(P, '#syncBadge');
  await P.click(other ? 'dialog.phone #pairOther' : 'dialog.phone #pairPhone');
  await P.waitForSelector('dialog.pair[open] #pairLink', { timeout: 30_000 });
  return { code: (await P.inputValue('#pairLink')).split('#pair=')[1], check: await P.textContent('#checkDigits') };
}
/** Saves the shown route under [name] with the header's Save (§14.6 rule 5: the field under the button, Enter) – paired,
 *  that also sends everything waiting. */
async function saveAs(P, name) {
  await P.click('#menuSavePlan');
  await P.waitForSelector('.savepop #savePopName', { timeout: 10_000 });
  await P.fill('#savePopName', name);
  await P.press('#savePopName', 'Enter');
  await until(P, async (n) => (await window.__sync.list()).some((p) => p.title === n), name);
}
/** The Save field: open?, its name. */
const field = (P) => P.evaluate(() => { const i = document.getElementById('savePopName'); return i ? { open: true, value: i.value, selected: i.selectionEnd - i.selectionStart === i.value.length } : { open: false }; });
const openRoute = async (P, hash) => { await P.evaluate((h) => { location.hash = h; }, hash); await P.waitForFunction(() => document.getElementById('points')?.textContent?.includes('Vellau'), null, { timeout: 30_000 }); };
/** A row of My routes: where it lies (browser / phone / browser+phone, from the 🖥 📱 symbols), bold, the line below, open. */
const listRow = (P, name) => P.evaluate((n) => { const li = [...document.querySelectorAll('#planList li')].find((l) => l.querySelector('.name')?.textContent === n);
  if (!li) return null;
  const where = [...li.querySelectorAll('.where .w')].map((w) => (w.classList.contains('browser') ? 'browser' : 'phone') + ':' + w.getAttribute('aria-label')).join(' ');
  return { where, bold: Number(getComputedStyle(li.querySelector('.name')).fontWeight) >= 700, sub: li.querySelector('.sub').textContent, kind: li.dataset.kind,
    current: li.classList.contains('current'), text: li.textContent.replace(/\s+/g, ' ').trim() }; }, name);
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
  async save(title, plan = CODE) { const id = await call('syncNewId', rnd(16)); this.ledger = await call('syncSave', this.ledger, id, plan, title, Date.now(), this.device); return id; }
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
check(e.label === 'Pair phone' && !e.dot && e.sub === '', `not paired: the Phone button says "Pair phone", no dot (${JSON.stringify(e)})`);
await A.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'dark', null, { timeout: 30_000 }).catch(() => {});
let sb = await saveBtn(A);
check(sb.state === 'dark' && sb.filled && sb.label === 'Save' && !sb.hint, `not paired, a new route: "Save", dark (filled), no hint beside it (${JSON.stringify(sb)})`);
await saveAs(A, 'Altroute');
await A.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'done', null, { timeout: 30_000 }).catch(() => {});
sb = await saveBtn(A);
check(sb.state === 'done' && !sb.filled && sb.label === 'Saved' && !sb.hint && await A.locator('dialog[open]').count() === 0,
  `saved without a dialog; saved and unchanged: light "✓ Saved" (${JSON.stringify(sb)})`);
// rule 5, as in Word: the field shows the open route's name (selected); the same name overwrites it, Escape / a click
// beside it cancel, a new name is a new route ("Save as") and the old one stays
await A.click('#menuSavePlan');
await A.waitForSelector('.savepop #savePopName');
let f = await field(A);
check(f.value === 'Altroute' && f.selected, `Save opens the field under the button: "Altroute", selected (${JSON.stringify(f)})`);
await A.press('#savePopName', 'Escape');
check(!(await field(A)).open, 'Escape closes the field, nothing saved');
await A.click('#menuSavePlan');
await A.waitForSelector('.savepop #savePopName');
await A.mouse.click(900, 28); // the header's empty middle
check(!(await field(A)).open, 'a click beside the field closes it');
await A.click('#menuSavePlan');
await A.waitForSelector('.savepop #savePopName');
await A.press('#savePopName', 'Enter');
await A.waitForTimeout(500);
check(JSON.stringify(await titles(A)) === '["Altroute"]' && await A.locator('dialog[open]').count() === 0, 'the same name overwrites the open route – no question, no second route');
await saveAs(A, 'Zweitroute'); // a new name: "Save as"
check((await status(A)).folder.key === 'none' && JSON.stringify(await titles(A)) === '["Altroute","Zweitroute"]', 'a new name makes a second route, "Altroute" stays (Without phone)');
check((await A.textContent('#savedLine')).includes('Saved as Zweitroute'), 'the new one is the open route');
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
let row = await listRow(A, 'Zweitroute');
check(row?.where === HERE && !row.bold && row.current && /^Planned · \d+ \w+ \d{4}$/.test(row.sub), `not paired: a row shows 🖥 only, not bold, the open one marked, "Planned · date" (${JSON.stringify(row)})`);
const dialogText = await A.textContent('dialog.plans');
check(!/Your planned routes|Save current route|Saved as|Without phone|Remove routes/.test(dialogText) && await A.locator('dialog.plans .rename.iconbtn, dialog.plans h3').count() === 0,
  'My routes is one plain list: no heading line, no Save box, no folder lines, no ✎ / 🗑 in the row');
await A.click('#closePlans');

// ---- 2. pairing: the routes planned without a phone join its folder as waiting – bold, Save dark, nothing sent ----
const hw = await phone.save('Handweg');
const p1 = await showCode(A);
check(/^[A-Za-z0-9_-]{44}$/.test(p1.code) && /^\d{3} \d{3}$/.test(p1.check), 'Pair phone: a new 44-character code and six check digits');
await phone.pairDirect(p1.code);
await A.waitForFunction(() => document.getElementById('pairBody')?.textContent?.includes('Paired with Pixel 8'), null, { timeout: 30_000 }).catch(() => {});
check((await A.textContent('#pairBody')).includes('Paired with Pixel 8'), 'the browser says "Paired with Pixel 8" (the phone\'s hello, role 1)');
await A.click('#closePair');
let st = await status(A);
check(st.paired && st.folder.key === phone.device && st.phone === 'Pixel 8', `the phone's folder is open (key = its device id) ${JSON.stringify(st.folder)}`);
check(JSON.stringify(await titles(A)) === '["Altroute","Handweg","Zweitroute"]' && (await titles(A, 'none')).length === 0,
  'the routes planned without a phone joined the phone\'s folder (Without phone is empty) ' + JSON.stringify(await titles(A)));
await phone.round();
check(JSON.stringify(await phone.titles()) === '["Handweg"]', 'nothing went to the phone by itself ' + JSON.stringify(await phone.titles()));
check((await A.textContent('#points')).includes('Vellau'), 'the open route stays open (it joined the phone\'s folder too)');
await A.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'dark', null, { timeout: 30_000 }).catch(() => {});
sb = await saveBtn(A);
check(sb.state === 'dark' && sb.filled && sb.label === 'Save to phone' && st.pending === 2, `paired, two routes waiting: "Save to phone", dark (${JSON.stringify(sb)}, pending ${st.pending})`);
e = await entry(A);
check(e.label === 'Pixel 8' && e.dot && e.kind === 'fresh' && /^synced /.test(e.sub), `paired: the Phone button "Pixel 8" with the green dot and "synced …" below (${JSON.stringify(e)})`);
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
for (const n of ['Altroute', 'Zweitroute']) {
  row = await listRow(A, n);
  check(row?.where === HERE && row.bold, `"${n}" waits for the phone: 🖥, bold (${JSON.stringify(row)})`);
}
row = await listRow(A, 'Handweg');
check(row?.where === PHONE && !row.bold && !/New from the phone/.test(row.text), `the phone's route: 📱 only, not bold, no "New from the phone" mark (${JSON.stringify(row)})`);
await A.click('#closePlans');
await A.click('#menuSavePlan'); // one click sends everything waiting – no field (nothing new on the shown route)
check(!(await field(A)).open, 'only waiting routes: Save sends at once, no field');
await A.waitForFunction(() => window.__sync.status.pending === 0, null, { timeout: 30_000 }).catch(() => {});
await phone.round();
check(JSON.stringify(await phone.titles()) === '["Altroute","Handweg","Zweitroute"]', 'one click on "Save to phone": both routes are on the phone ' + JSON.stringify(await phone.titles()));
await A.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'done', null, { timeout: 30_000 }).catch(() => {});
sb = await saveBtn(A);
check(sb.state === 'done' && !sb.filled && sb.label === 'On the phone', `all on the phone: light "✓ On the phone" (${JSON.stringify(sb)})`);
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
for (const n of ['Altroute', 'Zweitroute']) {
  row = await listRow(A, n);
  check(row?.where === BOTH && !row.bold, `"${n}" sent: 🖥 📱, not bold (${JSON.stringify(row)})`);
}
await A.click('#closePlans');

// ---- 3. the Phone window: state + one sentence, Sync now · Unpair (no question), "How does it work?", small lines ----
await A.click('#syncBadge');
await A.waitForSelector('dialog.phone[open] #unpair:not([hidden])');
const buttons = await A.evaluate(() => [...document.querySelectorAll('#phoneRow button')].filter((b) => !b.hidden).map((b) => b.textContent));
check(buttons.join() === 'Sync now,Unpair', 'the Phone window: Sync now · Unpair ' + JSON.stringify(buttons));
check((await A.textContent('#phoneWhy')) === 'Paired with Pixel 8', 'the Phone window names the folder\'s phone');
check(await A.isHidden('#howBox') && await A.isVisible('#pairOther'), 'the explanation is behind "How does it work?"; "Pair another phone" small at the bottom');
await A.click('#howToggle');
check(await A.isVisible('#howBox') && /End-to-end encrypted/.test(await A.textContent('#howBox')) && await A.getAttribute('#howToggle', 'aria-expanded') === 'true',
  '"How does it work?" opens the explanation and the privacy line in place');
await A.click('#closePhone');
await box('GET', (await phone.keys()).mailbox, (await phone.keys()).token, { since: 0 }, Date.now() - 3 * 86_400_000);
await syncNow(A);
e = await entry(A);
check(e.kind === 'stale' && e.sub === 'last 3 days ago', `phone last seen 3 days ago: amber dot, "last 3 days ago" (${JSON.stringify(e)})`);
await phone.round();
await syncNow(A);
check((await entry(A)).kind === 'fresh', 'the phone syncs again → green');

// ---- 4. an unsaved change (⋮ Rename) stays over a reload and is NOT sent; Save sends it at once (no field) ----
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
A.once('dialog', (d) => d.accept('Waalweg'));
await rowMenu(A, 'Altroute', 'rename');
await A.waitForFunction(() => [...document.querySelectorAll('#planList .name')].some((n) => n.textContent === 'Waalweg'), null, { timeout: 10_000 }).catch(() => {});
await A.click('#closePlans');
sb = await saveBtn(A);
check(sb.unsaved && sb.state === 'dark', `unsaved rename: Save dark (${JSON.stringify(sb)})`);
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
check(!(await field(A)).open, 'Save with only a waiting rename: no field');
await A.waitForFunction(() => !document.getElementById('menuSavePlan').classList.contains('unsaved'), null, { timeout: 30_000 }).catch(() => {});
sb = await saveBtn(A);
check(!sb.unsaved && sb.state === 'done', `Save: the button is light again (${JSON.stringify(sb)})`);
await phone.round();
check((await phone.titles()).includes('Waalweg'), 'Save sent it: the phone has "Waalweg"');

// ---- 5. the phone's changes arrive (📱, no marks); a rename here via ⋮ is bold until Save sends it back (🖥 📱) ----
await phone.save('Vom Handy');
await phone.rename(hw, 'Handweg neu');
await phone.round();
await A.evaluate(() => document.dispatchEvent(new Event('visibilitychange'))); // the tab is visible again → pull
await until(A, async () => (await window.__sync.list()).some((p) => p.title === 'Vom Handy'), null, { timeout: 30_000 }).catch(() => {});
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
row = await listRow(A, 'Vom Handy');
check(row?.where === PHONE && !row.bold && !/from the phone|on the phone/i.test(row.sub), `new from the phone: 📱, not bold, no mark (${JSON.stringify(row)})`);
row = await listRow(A, 'Handweg neu');
check(row?.where === PHONE && !/Changed/.test(row.text), `renamed on the phone: 📱, no "Changed on the phone" (${JSON.stringify(row)})`);
A.once('dialog', (d) => d.accept('Handweg hier'));
await rowMenu(A, 'Handweg neu', 'rename');
await A.waitForFunction(() => [...document.querySelectorAll('#planList .name')].some((n) => n.textContent === 'Handweg hier'), null, { timeout: 10_000 }).catch(() => {});
row = await listRow(A, 'Handweg hier');
check(row?.where === BOTH && row.bold && (await saveBtn(A)).state === 'dark', `⋮ → Rename: bold, 🖥 📱, Save dark (${JSON.stringify(row)})`);
await A.click('#closePlans');
await A.click('#menuSavePlan');
await A.waitForFunction(() => window.__sync.status.pending === 0, null, { timeout: 30_000 }).catch(() => {});
await phone.round();
check((await phone.titles()).includes('Handweg hier'), 'Save to phone sent the new name back');
await menu(A, '#menuPlans');
await A.click('#planList li:has-text("Vom Handy") .open');
await A.waitForFunction(() => document.getElementById('points')?.textContent?.includes('Vellau'), null, { timeout: 30_000 }).catch(() => {});
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
check((await listRow(A, 'Vom Handy'))?.current, 'a click on a row opens it: the open route is marked (light green)');
await A.click('#closePlans');

// ---- 6. conflict: both kept, the older as "(copy)" ----
const wid = await phone.idOf('Waalweg');
await phone.rename(wid, 'Phone name');
await phone.round();
await A.waitForTimeout(20);
await A.evaluate((i) => window.__sync.rename(i, 'Browser name'), wid);
await syncNow(A);
const want = '["Browser name","Handweg hier","Phone name (copy)","Vom Handy","Zweitroute"]';
check(JSON.stringify(await titles(A)) === want, 'conflict: the newer wins, the older stays as a copy ' + JSON.stringify(await titles(A)));
await A.click('#menuSavePlan');
await A.waitForFunction(() => window.__sync.status.pending === 0, null, { timeout: 30_000 }).catch(() => {});
await phone.round();
check(JSON.stringify(await phone.titles()) === want, 'after Save the phone has the same two ' + JSON.stringify(await phone.titles()));
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
check(/^Planned · /.test((await listRow(A, 'Phone name (copy)'))?.sub ?? ''), 'the copy is a row like any other');
await A.click('#closePlans');

// ---- 7. delete both ways ----
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
await rowMenu(A, 'Phone name (copy)', 'del'); // ⋮ → Delete (no 🗑 in the row)
check(/recycle bin/.test(await A.textContent('dialog.plans .confirm')), 'delete in the phone\'s folder: the question names the phone\'s recycle bin');
await A.click('dialog.plans .confirm .danger');
await A.click('#closePlans');
await phone.round();
check((await phone.titles()).includes('Phone name (copy)'), 'deleted in the browser, not saved yet: still on the phone');
await A.click('#menuSavePlan');
await A.waitForFunction(() => window.__sync.status.pending === 0, null, { timeout: 30_000 }).catch(() => {});
await phone.round();
check(!(await phone.titles()).includes('Phone name (copy)'), 'deleted + Save → gone on the phone (its app moves it to the recycle bin)');
await phone.remove(await phone.idOf('Vom Handy'));
await phone.round();
await syncNow(A);
check(!(await titles(A)).includes('Vom Handy'), 'deleted on the phone → gone in the browser');

// ---- 8. GPX goes with Save ----
await A.click('#sheetMenuBtn'); await A.click('#clear'); // an empty map: only the track is new
await A.setInputFiles('#gpxFile', path.join(here, 'fixtures/komoot-hike.gpx'));
await until(A, async () => (await window.__sync.list()).some((p) => p.kind === 'track'), null, { timeout: 120_000 }).catch(() => {});
check((await A.evaluate(async () => (await window.__sync.list()).filter((p) => p.kind === 'track').map((p) => p.title))).join() === 'Meran 2000 & Ifinger Hütte', 'an imported GPX is kept in the folder as a track');
check((await saveBtn(A)).unsaved, 'the imported track is unsaved: Save dark');
await A.click('#closeTrack');
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-kind="track"]');
let tr = await listRow(A, 'Meran 2000 & Ifinger Hütte');
check(tr?.kind === 'track' && /^GPX · /.test(tr.sub) && tr.bold && tr.where === HERE, `the track's row: "GPX · date", bold, 🖥 (${JSON.stringify(tr)})`);
const dl = A.waitForEvent('download', { timeout: 30_000 }).catch(() => null);
// the name the page gives (headless Chromium reports non-ASCII download names as "download")
await A.evaluate(() => { const c = HTMLAnchorElement.prototype.click; HTMLAnchorElement.prototype.click = function () { window.__dlName = this.download; return c.call(this); }; });
await rowMenu(A, 'Meran 2000 & Ifinger Hütte', 'export');
const file = await dl;
const gpx = file ? fs.readFileSync(await file.path(), 'utf8') : '';
const dlName = await A.evaluate(() => window.__dlName);
check(dlName === 'Meran 2000 _ Ifinger Hütte.gpx' && /<trkpt /.test(gpx), `⋮ → Export GPX: the track as a GPX file, named as the app names it (${dlName}, ${gpx.length} bytes)`);
await A.click('#menuSavePlan');
await A.waitForFunction(() => window.__sync.status.pending === 0, null, { timeout: 30_000 }).catch(() => {});
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
await saveAs(L, 'Laptopweg'); // paired: the field's Save also sends
await L.waitForFunction(() => window.__sync.status.pending === 0, null, { timeout: 30_000 }).catch(() => {});
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
await L.click('#syncBadge');
await L.click('dialog.phone #unpair');
await L.waitForFunction(() => window.__sync.status.folder.key === 'none' && document.querySelectorAll('dialog.phone .removefolder').length === 2, null, { timeout: 30_000 }).catch(() => {});
check(await L.locator('dialog.phone .confirm').count() === 0 && (await status(L)).folder.key === 'none', 'Unpair: no question, the folder closes (Without phone)');
await L.waitForFunction(() => document.getElementById('phoneStatus')?.textContent?.startsWith('Unpaired. The routes of Pixel 8'), null, { timeout: 10_000 }).catch(() => {}); // CI draws it a moment later
const quiet = await L.textContent('#phoneStatus');
check(quiet.startsWith('Unpaired. The routes of Pixel 8'), 'the quiet line says the routes stay ' + quiet);
const removes = await L.evaluate(() => [...document.querySelectorAll('dialog.phone .removefolder')].map((b) => b.textContent));
check(removes.length === 2 && removes.every((r) => r.startsWith('Remove routes of Pixel 8 · paired')), 'the Phone window offers removing each closed folder ' + JSON.stringify(removes));
await L.click(`dialog.phone .removefolder[data-folder="${friend.device}"]`);
await L.waitForFunction(() => window.__sync.status.closed.length === 1, null, { timeout: 10_000 }).catch(() => {});
check((await status(L)).closed.length === 1 && (await titles(L, friend.device)).length === 0, 'removed: only the local copy of the friend\'s folder');
await friend.round();
check((await friend.titles()).includes('Freundesweg'), 'nothing on the friend\'s phone or mailbox was touched');
await L.click('#closePhone');
// the same phone again (it is still paired, so it redirects): its folder is back with the unsaved change
const pL2 = await showCode(L);
await phone.redirect(pL2.code);
await L.waitForFunction((d) => window.__sync.status.folder.key === d, phone.device, { timeout: 30_000 }).catch(() => {});
await L.click('#closePair').catch(() => {});
await L.waitForFunction(() => document.getElementById('menuSavePlan')?.classList.contains('unsaved'), null, { timeout: 30_000 }).catch(() => {});
check((await titles(L)).includes('Laptop unsaved') && (await saveBtn(L)).unsaved, 'the same phone paired again: its folder is back with the unsaved change (Save dark)');

// ---- 12. mailbox full (507): Save amber (its tooltip says why), nothing lost ----
LIMITS.items = 1; // the test's limit in the host
await L.click('#sheetMenuBtn'); await L.click('#clear');
await openRoute(L, '#p=' + CODE); // a new route on the map
await saveAs(L, 'Neuweg'); // a new item – the host counts items, a rename of one it has is fine
await L.waitForFunction(() => window.__sync.status.full, null, { timeout: 30_000 }).catch(() => {});
sb = await saveBtn(L);
check(sb.state === 'full' && sb.title.startsWith('Mailbox full') && !sb.hint && (await status(L)).pending >= 1, `507: Save amber, tooltip "Mailbox full – …", the change kept (${JSON.stringify(sb)})`);
LIMITS.items = 1000;
await L.click('#menuSavePlan');
await L.waitForFunction(() => !window.__sync.status.full && window.__sync.status.pending === 0, null, { timeout: 30_000 }).catch(() => {});
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
check(e.label === 'Pair phone' && !e.dot, 'the header follows: "Pair phone"');
await A.reload();
await A.waitForFunction(() => document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 60_000 });
check((await titles(A, 'none')).length === 0 && (await titles(A, phone.device)).includes('Zweitroute') && (await saveBtn(A)).state !== 'dark',
  'Without phone opens empty (its routes went to the phone\'s folder, kept here); Save not dark');

// ---- 15. the host's cap on new mailboxes per IP and day ----
env.SYNC_NEW_PER_IP_DAY = '1';
await menu(A, '#syncBadge');
await A.click('dialog.phone #pairPhone');
await A.waitForSelector('#pairBody .status.error', { timeout: 30_000 }).catch(() => {});
check(((await A.textContent('#pairBody').catch(() => '')) ?? '').includes('Too many new pairings from this network today'), 'over the new-mailbox cap: the page says so');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of (404|409|410|429|507)/.test(e) && !/ERR_INTERNET_DISCONNECTED|Failed to fetch/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
console.log(fails.length ? `FAILED: ${fails.length}` : 'all ok');
process.exit(fails.length ? 1 : 0);
