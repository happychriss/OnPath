// Plan sync, phase 2 (docs/design/web-sync.md): two browser contexts act as "browser" (A) and "phone" (B) against the
// host's real mailbox code (web/host/src/sync.js) on a fake D1 (Node's SQLite). Proves: pairing by link + check
// digits, save → other side, rename back, offline edit + conflict (newer wins, older stays as a copy), delete,
// reload keeps plans, the server holds no readable content, unpair ends it on both sides (plans stay).
// Needs no map data (routing may fail, the plan's points are enough). Usage: node test/sync-smoke.mjs [segmentsDir]
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { handleSync } from '../../host/src/sync.js';
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
const ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36';

async function open(ctx, hash = '') {
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(url + 'index.html' + hash);
  await page.waitForFunction(() => window.__sync?.status && document.getElementById('status')?.textContent?.includes('engine ready'), null, { timeout: 120_000 }).catch(() => {});
  await page.waitForFunction(() => window.__sync.list().then(() => true), null, { timeout: 60_000 });
  return page;
}
const plans = (p) => p.evaluate(async () => (await window.__sync.list()).map((x) => x.title).sort());
const syncNow = (p) => p.evaluate(() => window.__sync.syncNow());
const status = (p) => p.evaluate(() => ({ ...window.__sync.status }));

const ctxA = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const ctxB = await browser.newContext({ viewport: { width: 400, height: 800 }, userAgent: ANDROID });
/** A header entry: inline on a wide screen, behind ☰ at phone width. */
const menu = async (P, id) => { if (await P.isVisible('#menuBtn')) await P.click('#menuBtn'); await P.click(id); };
/** Pairing from the header's phone entry → the Phone window → Pair phone opens the QR code. */
const openPair = async (P) => { await menu(P, '#syncBadge'); await P.click('dialog.phone #pairPhone'); };
/** The header's phone entry (owner 2026-10-30, always there): its label, title, whether the dot shows and its colour. */
const entry = (P) => P.evaluate(() => { const b = document.getElementById('syncBadge'), d = b.querySelector('.syncdot');
  return { label: b.querySelector('.lbl').textContent.trim(), title: b.title, dot: !d.hidden, kind: d.dataset.kind, shown: !b.hidden }; });
/** The Phone window's state: dot kind, headline, the line why. */
const phoneWin = (P) => P.evaluate(() => ({ kind: document.getElementById('phoneState')?.dataset.kind, head: document.getElementById('phoneHead')?.textContent,
  why: document.getElementById('phoneWhy')?.textContent, list: !!document.querySelector('dialog.phone #planList, dialog.phone #saveCurrent') }));
/** The phone app reaching the mailbox at [when] (ms), as the app does: its own user agent, no x-onpath-client header. */
async function appVisit(P, when) {
  const k = await P.evaluate(async () => { const s = window.__sync; return s.keys(await s.store.get('pair')); });
  const r = await handleSync(new Request(`http://phone.test/sync/v1/${k.mailbox}?since=0`, {
    headers: { authorization: 'Bearer ' + k.token, 'user-agent': 'Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8)' } }), env, when);
  if (!r.ok) throw new Error('app visit answered ' + r.status);
}
const A = await open(ctxA, '#p=' + CODE);
await A.waitForFunction(() => document.getElementById('points')?.textContent?.includes('Vellau'), null, { timeout: 60_000 });

// 1. A shows the pairing code; B opens the link (as the phone app would after scanning) and pairs
// not paired: the header entry "Pair phone" (grey phone, no dot) opens the Phone window (owner 2026-10-30: its own
// window, not My routes): state 1 with one line why, and Pair phone → the pairing dialog
let e = await entry(A);
check(e.shown && e.label === 'Pair phone' && !e.dot && e.title === 'Pair phone – Your planned routes appear on the phone and here.' && await A.isVisible('#header #syncBadge'),
  `not paired: header entry "Pair phone", no dot, the tooltip says why (${JSON.stringify(e)})`);
await A.click('#syncBadge');
await A.waitForSelector('dialog.phone[open] #pairPhone', { timeout: 30_000 });
let w = await phoneWin(A);
check(await A.textContent('dialog.phone ul.how') .then((x) => x.includes('newer change wins')) && w.kind === 'none' && w.head === 'Pair phone' && w.why === 'Your planned routes appear on the phone and here.' && !w.list && !(await A.$('dialog.plans[open]'))
  && await A.isHidden('dialog.phone #unpair'), `state 1 – the Phone window (no route list): "Pair phone", why, the button (${JSON.stringify(w)})`);
await A.click('dialog.phone #pairPhone');
await A.waitForSelector('dialog.pair[open]', { timeout: 30_000 });
await A.waitForSelector('#pairLink', { timeout: 30_000 });
const link = await A.inputValue('#pairLink');
const checkA = await A.textContent('#checkDigits');
check(/#pair=[A-Za-z0-9_-]{44}$/.test(link), 'pairing link with a 44-character code');
check(/^\d{3} \d{3}$/.test(checkA), 'six check digits shown');
check((await A.textContent('#pairBody')).includes('Open OnPath on the phone → ☰ → Pair with browser → scan this code.'), 'the steps: open OnPath → ☰ → Pair with browser → scan');
check(await A.evaluate(() => document.querySelectorAll('dialog.pair .qr svg path, dialog.pair .qr svg rect').length) > 0, 'QR code drawn');
const B = await open(ctxB, '#pair=' + link.split('#pair=')[1]);
check(!(await B.evaluate(() => location.href)).includes('pair='), 'the secret is taken out of the address at once');
await B.waitForSelector('dialog.join #joinOk');
await B.click('#joinOk');
await B.waitForFunction(() => document.getElementById('joinStatus')?.textContent?.includes('Paired'), null, { timeout: 30_000 });
check((await B.textContent('#joinStatus')).includes(checkA), 'the phone side shows the same check digits');
await B.click('#joinCancel');
await A.waitForFunction(() => document.getElementById('pairBody')?.textContent?.includes('Paired with'), null, { timeout: 30_000 }).catch(() => {});
check((await A.textContent('#pairBody')).includes('Chrome on Android'), 'browser says "Paired with Chrome on Android"');
await A.click('#closePair');
// the header shows the pairing: a phone with a green dot beside My routes. B is a browser (no appSeen from the host
// yet): green with when this browser synced – no amber without the phone's time
await A.waitForSelector('#syncBadge:not([hidden])', { timeout: 10_000 }).catch(() => {});
await A.waitForFunction(() => document.querySelector('#syncBadge .lbl')?.textContent === 'Paired', null, { timeout: 10_000 }).catch(() => {});
e = await entry(A);
check(e.label === 'Paired' && e.dot && e.kind === 'fresh' && e.title.startsWith('Paired · this browser synced'), `header after pairing (no reload): "Paired" + green dot (${JSON.stringify(e)})`);
await A.click('#syncBadge');
await A.waitForSelector('dialog.phone[open] #unpair:not([hidden])');
w = await phoneWin(A);
check(!w.list && !(await A.$('dialog.plans[open]')) && await A.isVisible('dialog.phone #syncNow') && await A.isHidden('dialog.phone #pairPhone')
  && w.why === 'Paired with Chrome on Android', `paired: a click opens the Phone window – state, Sync now, Unpair, no route list (${JSON.stringify(w)})`);
const info = await A.evaluate(() => ({ how: [...document.querySelectorAll('dialog.phone ul.how li')].filter((l) => !l.hidden).length,
  privacy: document.querySelector('dialog.phone .privacy')?.textContent, more: document.querySelector('dialog.phone .privacy a')?.getAttribute('href'),
  buttons: [...document.querySelectorAll('#phoneRow button')].filter((b) => !b.hidden).map((b) => b.textContent) }));
check(info.how === 3 && info.privacy.startsWith('End-to-end encrypted, no account') && info.more === 'https://onpath.earth/privacy/'
  && info.buttons.join() === 'Sync now,Show code again,Unpair', `the Phone window explains: How it works (3 lines), privacy + More; Sync now · Show code again · Unpair (${JSON.stringify(info)})`);
await A.click('dialog.phone #codeAgain');
await A.waitForSelector('dialog.pair[open] #checkDigits', { timeout: 30_000 });
check(await A.textContent('#checkDigits') === checkA && (await A.inputValue('#pairLink')) === link, 'Show code again: the same code and check digits (a second device joins the same pairing)');
await A.click('#closePair');
// the three states with the phone's time from the host (appSeen): the app reached the mailbox 2 minutes ago → green
// "Paired · synced 2 min. ago"; 3 days ago → amber "Paired · phone last 3 days ago" + what to do
await appVisit(A, Date.now() - 2 * 60_000);
await syncNow(A);
e = await entry(A);
check(e.kind === 'fresh' && /^Paired · synced 2 min\.? ago – Paired with Chrome on Android$/.test(e.title), `state 2 – phone synced 2 min ago: green dot, "Paired · synced 2 min. ago" (${JSON.stringify(e)})`);
await A.click('#syncBadge');
await A.waitForSelector('dialog.phone[open]');
w = await phoneWin(A);
check(w.kind === 'fresh' && /^Paired · synced 2 min\.? ago$/.test(w.head), `state 2 in the Phone window (${JSON.stringify(w)})`);
await A.click('#closePhone');
await appVisit(A, Date.now() - 3 * 86_400_000);
await syncNow(A);
e = await entry(A);
const STALE_WHY = 'Open OnPath on the phone, then the routes sync. Changes from here wait until then.';
check(e.kind === 'stale' && e.dot && e.title === 'Paired · phone last 3 days ago – ' + STALE_WHY, `state 3 – phone last 3 days ago: amber dot, what to do (${JSON.stringify(e)})`);
check(await A.evaluate(() => getComputedStyle(document.querySelector('#syncBadge .syncdot')).backgroundColor) === 'rgb(249, 168, 37)', 'state 3: the dot is amber');
// phone width: the entries are behind ☰ – the ☰ button carries the same dot, seen without opening it
await syncNow(B);
const menuDot = (P) => P.evaluate(() => { const d = document.querySelector('#menuBtn .menudot'); return { shown: !!d && !d.hidden && d.getClientRects().length > 0, kind: d?.dataset.kind, bg: d && getComputedStyle(d).backgroundColor }; });
let md = await menuDot(B);
check(md.shown && md.kind === 'stale' && md.bg === 'rgb(249, 168, 37)', `phone width: the ☰ button shows the amber dot (${JSON.stringify(md)})`);
check(!(await menuDot(A)).shown, 'wide screen: no ☰ button, no dot there');
await A.click('#syncBadge');
await A.waitForSelector('dialog.phone[open]');
w = await phoneWin(A);
check(w.kind === 'stale' && w.head === 'Paired · phone last 3 days ago' && w.why === STALE_WHY, `state 3 in the Phone window (${JSON.stringify(w)})`);
await A.click('#closePhone');
await appVisit(A, Date.now()); // the phone is back
await syncNow(A);
await syncNow(B);
md = await menuDot(B);
check((await entry(A)).kind === 'fresh' && md.shown && md.kind === 'fresh' && md.bg === 'rgb(46, 125, 50)', `the phone syncs again → green, on the ☰ button too (${JSON.stringify(md)})`);

// 2. save on A → appears on B
await menu(A, '#menuPlans');
await A.click('#saveCurrent'); // My routes → Save the current plan… → the name dialog (requestSave)
await A.fill('#saveTitle', 'Waalweg');
await A.click('#savePlan');
await A.waitForFunction(async () => (await window.__sync.list()).some((p) => p.title === 'Waalweg'));
await A.waitForFunction(() => document.getElementById('savedLine')?.textContent?.includes('Saved as Waalweg'), null, { timeout: 10_000 }).catch(() => {});
check((await A.textContent('#savedLine')).includes('Saved as Waalweg'), 'the sheet says "Saved as Waalweg"');
await syncNow(A);
await syncNow(B);
check(JSON.stringify(await plans(B)) === '["Waalweg"]', 'saved in the browser → on the phone');

// 3. rename on B (UI) → A
await menu(B, '#menuPlans');
await B.waitForSelector('#planList li .rename');
B.once('dialog', (d) => d.accept('Waalweg Algund'));
await B.click('#planList li .rename');
await B.waitForFunction(() => document.getElementById('planList')?.textContent?.includes('Waalweg Algund'));
await B.click('#closePlans');
await syncNow(B);
await syncNow(A);
check(JSON.stringify(await plans(A)) === '["Waalweg Algund"]', 'renamed on the phone → in the browser');

// 4. B offline edits, A edits later (newer) → newer wins, older stays as a copy on both
const id = await A.evaluate(async () => (await window.__sync.list())[0].id);
await ctxB.setOffline(true);
await B.evaluate((i) => window.__sync.rename(i, 'Phone name'), id);
await syncNow(B);
check((await status(B)).message.includes('Offline'), 'offline: plain message, the change waits');
check((await status(B)).pending >= 1, 'offline: the change is kept to send');
await A.waitForTimeout(20);
await A.evaluate((i) => window.__sync.rename(i, 'Browser name'), id);
await syncNow(A);
await ctxB.setOffline(false);
await syncNow(B);
await syncNow(A);
const want = '["Browser name","Phone name (copy)"]';
check(JSON.stringify(await plans(B)) === want, 'conflict on the phone: newer wins, older is a copy ' + JSON.stringify(await plans(B)));
check(JSON.stringify(await plans(A)) === want, 'conflict in the browser: the same two plans');

// 5. open a saved plan on B (empty page → the plan)
await menu(B, '#menuPlans');
await B.click('#planList li:has-text("Browser name") .open');
await B.waitForFunction(() => document.getElementById('points')?.textContent?.includes('Vellau'), null, { timeout: 30_000 }).catch(() => {});
check((await B.textContent('#points')).includes('Vellau'), 'a synced plan opens on the other side');

// 6. delete on A (with the question) → gone on B
await menu(A, '#menuPlans');
const del = A.locator('#planList li:has-text("Phone name (copy)") .del');
await del.waitFor();
const delLook = { svg: await del.locator('svg').count(), text: (await del.textContent()).trim() };
check(delLook.svg === 1 && delLook.text === '', `a route is deleted with the trash can, like the app (no ✕) ${JSON.stringify(delLook)}`);
await del.click();
check(/recycle bin for 30 days/.test(await A.textContent('dialog.plans .confirm')), 'the question says the phone keeps it 30 days in its recycle bin');
await A.click('dialog.plans .confirm .danger');
await A.waitForFunction(() => !document.getElementById('planList')?.textContent?.includes('(copy)'));
await A.click('#closePlans');
await syncNow(A);
await syncNow(B);
check(JSON.stringify(await plans(B)) === '["Browser name"]', 'deleted in the browser → gone on the phone');

// 7. reload keeps plans and pairing (IndexedDB)
await A.reload();
await A.waitForFunction(() => window.__sync?.status?.paired, null, { timeout: 60_000 }).catch(() => {});
check(JSON.stringify(await plans(A)) === '["Browser name"]' && (await status(A)).paired, 'reload keeps the plans and the pairing');

// 8. the server holds nothing readable
const rows = env.SYNC.sqlite.prepare('SELECT blob FROM item').all();
const raw = rows.map((r) => Buffer.from(r.blob, 'base64url').toString('latin1')).join('|');
check(rows.length >= 3 && !/Waalweg|Browser name|Phone name|Android|Chrome/.test(raw), `server stores ${rows.length} blobs, no plan or name readable`);
check(!JSON.stringify(env.SYNC.sqlite.prepare('SELECT * FROM mailbox').all()).includes(link.split('#pair=')[1]), 'server never saw the pairing code');

// 9. unpair on B → A learns it, plans stay on both
await menu(B, '#syncBadge');
await B.waitForSelector('dialog.phone #unpair:not([hidden])', { timeout: 30_000 });
check((await B.textContent('#phoneHead')).startsWith('Paired') && await B.isVisible('dialog.phone #syncNow') && await B.isHidden('dialog.phone #pairPhone'),
  'the Phone window: "Paired …", Sync now, Unpair');
// the question takes the place of "Sync now / Unpair" (owner 2026-10-30: "zu viele Trennen"), above Close; Escape and
// Cancel bring the buttons back, focus on Unpair
await B.click('dialog.phone #unpair');
const ask = await B.evaluate(() => {
  const d = document.querySelector('dialog.phone'), box = d.querySelector('.confirm');
  const close = d.querySelector('#closePhone');
  return { inSection: !!box && box.previousElementSibling?.id === 'phoneRow', aboveClose: !!box && !!(box.compareDocumentPosition(close) & Node.DOCUMENT_POSITION_FOLLOWING),
    rowHidden: d.querySelector('#phoneRow').hidden, unpairs: [...d.querySelectorAll('button')].filter((b) => b.getClientRects().length && b.textContent.trim() === 'Unpair').length,
    text: box?.querySelector('p').textContent, buttons: [...(box?.querySelectorAll('button') ?? [])].map((b) => b.textContent.trim()), focus: document.activeElement?.textContent.trim() };
});
check(ask.inSection && ask.aboveClose && ask.rowHidden && ask.unpairs === 1 && ask.text === 'Routes stop syncing. They stay in this browser and on the phone.'
  && ask.buttons.join() === 'Cancel,Unpair' && ask.focus === 'Cancel', `Unpair asks in place of Sync now / Unpair, above Close, one "Unpair" (${JSON.stringify(ask)})`);
await B.keyboard.press('Escape');
check(await B.isVisible('dialog.phone') && await B.isVisible('dialog.phone #syncNow') && await B.locator('dialog.phone .confirm').count() === 0
  && await B.evaluate(() => document.activeElement?.id) === 'unpair', 'Escape: the question goes, the Phone window stays, Sync now / Unpair back (focus on Unpair)');
await B.click('dialog.phone #unpair');
await B.click('dialog.phone .confirm .keep');
check(await B.isVisible('dialog.phone #syncNow') && await B.locator('dialog.phone .confirm').count() === 0, 'Cancel: Sync now / Unpair back');
await B.click('dialog.phone #unpair');
await B.click('dialog.phone .confirm .danger');
await B.waitForFunction(() => document.getElementById('phoneStatus')?.textContent?.includes('Unpaired'), null, { timeout: 30_000 }).catch(() => {});
check((await B.textContent('#phoneStatus')).includes('Unpaired') && await B.isVisible('dialog.phone #pairPhone') && (await B.textContent('#phoneHead')) === 'Pair phone',
  'unpair on the phone side (in the Phone window; back to state 1, Pair phone offered again)');
await B.click('#closePhone');
e = await entry(B);
check(!(await menuDot(B)).shown, 'not paired: no dot on the ☰ button');
check(e.label === 'Pair phone' && !e.dot && e.title.startsWith('Pair phone'), `after unpairing (no reload): header back to "Pair phone" (${JSON.stringify(e)})`);
await B.click('#menuBtn');
check(await B.isVisible('#mainMenu #syncBadge .lbl') && (await B.textContent('#mainMenu #syncBadge')).trim() === 'Pair phone', 'phone width: the ☰ list shows "Pair phone" with its text');
await B.click('#menuBtn');
await syncNow(A);
const sa = await status(A);
e = await entry(A);
check(e.label === 'Pair phone' && !e.dot, `the browser's header follows the ended pairing: "Pair phone" (${JSON.stringify(e)})`);
check(!sa.paired && sa.message.includes('ended on the other device'), 'the browser learns the pairing was ended');
check(JSON.stringify(await plans(A)) === '["Browser name"]' && JSON.stringify(await plans(B)) === '["Browser name"]', 'plans stay on both sides');
check(env.SYNC.sqlite.prepare('SELECT COUNT(*) AS n FROM item').get().n === 0, 'the mailbox is empty after unpairing');

// 10. the host's cap on new mailboxes per IP and day: a new pairing over it is refused in plain words
env.SYNC_NEW_PER_IP_DAY = '1'; // this run made one mailbox today already
await openPair(A);
await A.waitForSelector('#pairBody .status.error', { timeout: 30_000 }).catch(() => {});
const capText = (await A.textContent('#pairBody').catch(() => '')) ?? '';
check(capText.includes('Too many new pairings from this network today'), 'over the new-mailbox cap: the page says so ' + JSON.stringify(capText.slice(0, 80)));
check(env.SYNC.sqlite.prepare('SELECT COUNT(*) AS n FROM mailbox').get().n === 1, 'no mailbox made over the cap');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of (404|409|410|429)/.test(e) && !/ERR_INTERNET_DISCONNECTED|Failed to fetch/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
