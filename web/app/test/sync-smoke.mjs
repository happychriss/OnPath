// Plan sync with folders (owner order A-017, docs/design/web-sync.md §14) end to end: two browser contexts (A = desktop,
// L = laptop) and fake phones against the host's real mailbox code (web/host/src/sync.js) on a fake D1 (Node's SQLite).
// A fake phone is the app's own shared sync code (com.onpath.app.sync in the Wasm engine of a third, idle page) driven
// from Node with the real crypto: its hello says role 1, it writes routes, renames, deletes, the JOIN redirect (§5.1)
// and "Unpair?" (DELETE) – what the phone app does. Proves: pairing opens the phone's folder, "Without phone" stays and
// is not uploaded; unsaved edits survive a reload and are not sent; the header's Save sends them; the phone's changes
// arrive marked; a conflict keeps "(copy)"; delete both ways; GPX goes with Save; "Copy to ‹phone›"; a friend's PC (two
// folders); the same phone again brings its folder back with unsaved changes; a second browser via JOIN (transitional
// mailbox deleted); unpair on the phone; mailbox full (507) → amber hint, nothing lost; the server holds nothing readable.
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
/** The header's phone entry: its label, title, whether the dot shows and its colour. */
const entry = (P) => P.evaluate(() => { const b = document.getElementById('syncBadge'), d = b.querySelector('.syncdot');
  return { label: b.querySelector('.lbl').textContent.trim(), title: b.title, dot: !d.hidden, kind: d.dataset.kind }; });
/** The header's Save: bold (unsaved), the hint beside it. */
const saveBtn = (P) => P.evaluate(() => { const b = document.getElementById('menuSavePlan'), h = document.getElementById('saveHint');
  return { unsaved: b.classList.contains('unsaved'), weight: getComputedStyle(b).fontWeight, disabled: b.disabled, hint: h.hidden ? '' : h.textContent, warn: h.classList.contains('warning') }; });
/** Pair from the Phone window: a new code each time; returns the code (the part after #pair=) and the check digits. */
async function showCode(P, other = false) {
  await menu(P, '#syncBadge');
  await P.click(other ? 'dialog.phone #pairOther' : 'dialog.phone #pairPhone');
  await P.waitForSelector('dialog.pair[open] #pairLink', { timeout: 30_000 });
  return { code: (await P.inputValue('#pairLink')).split('#pair=')[1], check: await P.textContent('#checkDigits') };
}
/** Saves the shown route under [name] through My routes (into the open folder only – nothing is sent). */
async function saveAs(P, name) {
  await menu(P, '#menuPlans');
  await P.click('#saveCurrent');
  const choice = await P.waitForSelector('dialog.savechoice #saveAsNew, dialog.savename #saveTitle', { timeout: 10_000 });
  if (await choice.evaluate((e) => e.id === 'saveAsNew')) await P.click('#saveAsNew');
  await P.fill('#saveTitle', name);
  await P.click('#savePlan');
  await until(P, async (n) => (await window.__sync.list()).some((p) => p.title === n), name);
}
const openRoute = async (P, hash) => { await P.evaluate((h) => { location.hash = h; }, hash); await P.waitForFunction(() => document.getElementById('points')?.textContent?.includes('Vellau'), null, { timeout: 30_000 }); };
const listRow = (P, name) => P.evaluate((n) => { const li = [...document.querySelectorAll('#planList li')].find((l) => l.querySelector('.name')?.textContent === n);
  return li ? { mark: li.querySelector('.mark')?.textContent, bold: getComputedStyle(li.querySelector('.name')).fontWeight, kind: li.dataset.kind } : null; }, name);

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

// ---- 1. before pairing: a route in Without phone; the header's Save saves here only, no hint ----
const ctxA = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const A = await open(ctxA, '#p=' + CODE);
await A.waitForFunction(() => document.getElementById('points')?.textContent?.includes('Vellau'), null, { timeout: 60_000 });
let e = await entry(A);
check(e.label === 'Pair phone' && !e.dot, `not paired: header entry "Pair phone", no dot (${JSON.stringify(e)})`);
await saveAs(A, 'Altroute');
let sb = await saveBtn(A);
check(!sb.unsaved && sb.hint === '', `Without phone: Save saves here, not bold, no hint (${JSON.stringify(sb)})`);
check((await status(A)).folder.key === 'none' && (await status(A)).folder.name === 'Without phone', 'the open folder is "Without phone"');

// ---- 2. pairing opens the phone's folder; Without phone stays and is not uploaded ----
await phone.save('Handweg');
const p1 = await showCode(A);
check(/^[A-Za-z0-9_-]{44}$/.test(p1.code) && /^\d{3} \d{3}$/.test(p1.check), 'Pair phone: a new 44-character code and six check digits');
await phone.pairDirect(p1.code);
await A.waitForFunction(() => document.getElementById('pairBody')?.textContent?.includes('Paired with Pixel 8'), null, { timeout: 30_000 }).catch(() => {});
check((await A.textContent('#pairBody')).includes('Paired with Pixel 8'), 'the browser says "Paired with Pixel 8" (the phone\'s hello, role 1)');
await A.click('#closePair');
let st = await status(A);
check(st.paired && st.folder.key === phone.device && st.phone === 'Pixel 8', `the phone's folder is open (key = its device id) ${JSON.stringify(st.folder)}`);
check(JSON.stringify(await titles(A)) === '["Handweg"]', 'the folder shows only the phone\'s routes ' + JSON.stringify(await titles(A)));
check(JSON.stringify(await titles(A, 'none')) === '["Altroute"]', 'Without phone keeps its route');
await phone.round();
check(!(await phone.titles()).includes('Altroute'), 'nothing of Without phone went to the phone ' + JSON.stringify(await phone.titles()));
check(!(await A.textContent('#points')).includes('Vellau'), 'the open route stayed with its folder (Without phone): the phone\'s folder opens with an empty map');
await menu(A, '#menuPlans');
await A.waitForFunction(() => document.getElementById('planHeadText')?.textContent && document.querySelector('#planList li'), null, { timeout: 60_000 }).catch(() => {});
check((await A.textContent('#planHeadText')) === 'Your planned routes · Pixel 8', 'My routes: heading with the folder name ' + await A.textContent('#planHeadText'));
check((await listRow(A, 'Handweg'))?.mark?.includes('New') !== true, 'the first fill of a folder is not marked "New from the phone"');
check(await A.isVisible('#switchFolder') && (await A.textContent('#switchFolder')) === 'Without phone (1)', 'paired: a quiet "Without phone (1)" looks into the routes from before');
await A.click('#closePlans');

// ---- 3. phone window: no unpair question, no "Show code again"; three states from appSeen ----
await A.click('#syncBadge');
await A.waitForSelector('dialog.phone[open] #unpair:not([hidden])');
const buttons = await A.evaluate(() => [...document.querySelectorAll('#phoneRow button')].filter((b) => !b.hidden).map((b) => b.textContent));
check(buttons.join() === 'Sync now,Pair another phone,Unpair', 'the Phone window: Sync now · Pair another phone · Unpair ' + JSON.stringify(buttons));
check((await A.textContent('#phoneWhy')) === 'Paired with Pixel 8', 'the Phone window names the folder\'s phone');
await A.click('#closePhone');
await box('GET', (await phone.keys()).mailbox, (await phone.keys()).token, { since: 0 }, Date.now() - 3 * 86_400_000);
await syncNow(A);
e = await entry(A);
check(e.kind === 'stale' && e.title.startsWith('Paired · phone last 3 days ago'), `phone last seen 3 days ago: amber (${JSON.stringify(e)})`);
await phone.round();
await syncNow(A);
check((await entry(A)).kind === 'fresh', 'the phone syncs again → green');

// ---- 4. unsaved edits stay over a reload and are NOT sent; Save sends them ----
await openRoute(A, '#p=' + CODE);
await saveAs(A, 'Waalweg');
sb = await saveBtn(A);
check(sb.unsaved && Number(sb.weight) >= 700 && sb.hint === 'Not saved on the phone yet', `unsaved: Save bold + "Not saved on the phone yet" (${JSON.stringify(sb)})`);
await syncNow(A);
await phone.round();
check(!(await phone.titles()).includes('Waalweg'), 'a pull does not send: the phone has no "Waalweg" yet');
await A.reload();
await A.waitForFunction(() => document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 60_000 });
await A.waitForFunction(() => document.getElementById('menuSavePlan')?.classList.contains('unsaved'), null, { timeout: 30_000 }).catch(() => {});
await A.waitForFunction(() => document.getElementById('savedLine')?.textContent?.includes('Saved as'), null, { timeout: 60_000 }).catch(() => {});
sb = await saveBtn(A);
check((await titles(A)).includes('Waalweg') && sb.unsaved && sb.hint === 'Not saved on the phone yet', `after a reload: still there, Save still bold (${JSON.stringify(sb)})`);
check((await A.textContent('#savedLine')).includes('Saved as Waalweg'), 'after a reload the shown route is still "Saved as Waalweg" (its folder\'s draft)');
await phone.round();
check(!(await phone.titles()).includes('Waalweg'), 'still nothing on the phone');
await A.click('#menuSavePlan');
await A.waitForFunction(() => !document.getElementById('menuSavePlan').classList.contains('unsaved'), null, { timeout: 30_000 }).catch(() => {});
sb = await saveBtn(A);
check(!sb.unsaved && sb.hint === '', `Save: the button is normal again, the hint gone (${JSON.stringify(sb)})`);
await phone.round();
check((await phone.titles()).includes('Waalweg'), 'Save sent it: the phone has "Waalweg"');

// ---- 5. the phone's changes arrive marked until opened ----
await phone.save('Vom Handy');
await phone.rename(await phone.idOf('Handweg'), 'Handweg neu');
await phone.round();
await A.evaluate(() => document.dispatchEvent(new Event('visibilitychange'))); // the tab is visible again → pull
await until(A, async () => (await window.__sync.list()).some((p) => p.title === 'Vom Handy'), null, { timeout: 30_000 }).catch(() => {});
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
let row = await listRow(A, 'Vom Handy');
check(row?.mark === 'New from the phone' && Number(row.bold) >= 700, `"New from the phone", bold (${JSON.stringify(row)})`);
row = await listRow(A, 'Handweg neu');
check(row?.mark === 'Changed on the phone', `renamed on the phone: "Changed on the phone" (${JSON.stringify(row)})`);
await A.click('#planList li:has-text("Vom Handy") .open');
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
row = await listRow(A, 'Vom Handy');
check(row && row.mark !== 'New from the phone' && Number(row.bold) < 700, `opened: the mark is gone (${JSON.stringify(row)})`);
await A.click('#closePlans');

// ---- 6. conflict: both kept, the older as "(copy)" ----
const wid = await phone.idOf('Waalweg');
await phone.rename(wid, 'Phone name');
await phone.round();
await A.waitForTimeout(20);
await A.evaluate((i) => window.__sync.rename(i, 'Browser name'), wid);
await syncNow(A);
const want = '["Browser name","Handweg neu","Phone name (copy)","Vom Handy"]';
check(JSON.stringify(await titles(A)) === want, 'conflict: the newer wins, the older stays as a copy ' + JSON.stringify(await titles(A)));
await A.click('#menuSavePlan');
await A.waitForFunction(() => window.__sync.status.pending === 0, null, { timeout: 30_000 }).catch(() => {});
await phone.round();
check(JSON.stringify(await phone.titles()) === want, 'after Save the phone has the same two ' + JSON.stringify(await phone.titles()));

// ---- 7. delete both ways ----
await menu(A, '#menuPlans');
await A.click('#planList li:has-text("Phone name (copy)") .del');
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
check((await saveBtn(A)).unsaved, 'the imported track is unsaved: Save bold');
await A.click('#menuSavePlan');
await A.waitForFunction(() => window.__sync.status.pending === 0, null, { timeout: 30_000 }).catch(() => {});
await phone.round();
check((await phone.list()).some((x) => x.kind === 'track' && x.title === 'Meran 2000 & Ifinger Hütte' && x.track.length > 100), 'Save sent the track (kind TRACK) to the phone');

// ---- 9. "Copy to Pixel 8" from Without phone ----
await A.click('#closeTrack');
await menu(A, '#menuPlans');
await A.click('#switchFolder');
await A.waitForFunction(() => document.getElementById('planHeadText')?.textContent?.endsWith('Without phone'), null, { timeout: 30_000 }).catch(() => {});
check((await A.textContent('#planHeadText')) === 'Your planned routes · Without phone' && await A.isHidden('#saveCurrent'), 'looking into Without phone (no Save current route there)');
await A.click('#planList li:has-text("Altroute") .open');
await A.waitForSelector('#copyToPhone', { timeout: 30_000 });
check((await A.textContent('#copyToPhone')) === 'Copy to Pixel 8', 'an opened route from Without phone offers "Copy to Pixel 8"');
await A.click('#copyToPhone');
await until(A, async () => (await window.__sync.list()).some((p) => p.title === 'Altroute'), null, { timeout: 10_000 }).catch(() => {});
check((await titles(A)).includes('Altroute') && JSON.stringify(await titles(A, 'none')) === '["Altroute"]', 'copied into the phone\'s folder; the original stays in Without phone');
check((await saveBtn(A)).unsaved, 'the copy is unsaved until Save');
await A.click('#menuSavePlan');
await A.waitForFunction(() => window.__sync.status.pending === 0, null, { timeout: 30_000 }).catch(() => {});
await phone.round();
check((await phone.titles()).includes('Altroute'), 'Save → the copy is on the phone');

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
await saveAs(L, 'Laptopweg');
await L.click('#menuSavePlan');
await L.waitForFunction(() => window.__sync.status.pending === 0, null, { timeout: 30_000 }).catch(() => {});
await phone.round();
check((await phone.titles()).includes('Laptopweg'), 'Save on the laptop → on the phone');
await syncNow(A);
await menu(A, '#menuPlans');
await A.waitForSelector('#planList li[data-id]');
check((await listRow(A, 'Laptopweg'))?.mark === 'New from the phone', 'the desktop sees the laptop\'s route as "New from the phone"');
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
await phone.round();
check(!(await phone.titles()).includes('Laptop unsaved') && !(await phone.titles()).includes('Freundesweg'), 'nothing crossed to the first phone');
check(st.closed.length === 1 && /^Pixel 8 · paired /.test(st.closed[0].name) && /^Pixel 8 · paired /.test(st.phone), 'two phones named "Pixel 8": the pairing date is added ' + JSON.stringify([st.phone, st.closed]));
// unpair in the browser: no question, the folder closes, the quiet "Remove routes of …" lines
await L.click('#syncBadge');
await L.click('dialog.phone #unpair');
await L.waitForFunction(() => window.__sync.status.folder.key === 'none' && document.querySelectorAll('dialog.phone .removefolder').length === 2, null, { timeout: 30_000 }).catch(() => {});
check(await L.locator('dialog.phone .confirm').count() === 0 && (await status(L)).folder.key === 'none', 'Unpair: no question, the folder closes (Without phone)');
check((await L.textContent('#phoneStatus')).startsWith('Unpaired. The routes of Pixel 8'), 'the quiet line says the routes stay ' + await L.textContent('#phoneStatus'));
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
check((await titles(L)).includes('Laptop unsaved') && (await saveBtn(L)).unsaved, 'the same phone paired again: its folder is back with the unsaved change (Save bold)');

// ---- 12. mailbox full (507): amber hint beside Save, nothing lost ----
await saveAs(L, 'Neuweg'); // a new item – the host counts items, a rename of one it has is fine
LIMITS.items = 1; // the test's limit in the host
await L.click('#menuSavePlan');
await L.waitForFunction(() => window.__sync.status.full, null, { timeout: 30_000 }).catch(() => {});
sb = await saveBtn(L);
check(sb.warn && sb.hint.startsWith('Mailbox full') && (await status(L)).pending >= 1, `507: amber "Mailbox full – …" beside Save, the change kept (${JSON.stringify(sb)})`);
LIMITS.items = 1000;
await L.click('#menuSavePlan');
await L.waitForFunction(() => !window.__sync.status.full && window.__sync.status.pending === 0, null, { timeout: 30_000 }).catch(() => {});
await phone.round();
check((await phone.titles()).includes('Laptop unsaved') && !(await saveBtn(L)).warn, 'room again: Save sends it, the hint goes');

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
check(JSON.stringify(await titles(A, 'none')) === '["Altroute"]' && (await A.textContent('#points')).includes('Vellau'), 'Without phone opens again with its route (the folder\'s draft)');

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
