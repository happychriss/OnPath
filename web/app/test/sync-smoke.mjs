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
/** Pairing lives in My routes → "Pair with phone" (owner 2026-10-30; no header entry): Pair phone opens the QR code. */
const openPair = async (P) => { await menu(P, '#menuPlans'); await P.click('dialog.plans .syncsec #pairPhone'); };
const A = await open(ctxA, '#p=' + CODE);
await A.waitForFunction(() => document.getElementById('points')?.textContent?.includes('Vellau'), null, { timeout: 60_000 });

// 1. A shows the pairing code; B opens the link (as the phone app would after scanning) and pairs
check(await A.isHidden('#syncBadge'), 'not paired: no status in the header');
await openPair(A);
await A.waitForSelector('#pairLink', { timeout: 30_000 });
const link = await A.inputValue('#pairLink');
const checkA = await A.textContent('#checkDigits');
check(/#pair=[A-Za-z0-9_-]{44}$/.test(link), 'pairing link with a 44-character code');
check(/^\d{3} \d{3}$/.test(checkA), 'six check digits shown');
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
// the header shows the pairing: a phone with a green dot beside My routes; a click opens My routes at "Pair with phone"
await A.waitForSelector('#syncBadge:not([hidden])', { timeout: 10_000 }).catch(() => {});
check(await A.isVisible('#syncBadge') && (await A.getAttribute('#syncBadge', 'title')).startsWith('Paired with your phone'), 'header: paired status beside My routes (' + await A.getAttribute('#syncBadge', 'title') + ')');
await A.click('#syncBadge');
await A.waitForSelector('dialog.plans[open] .syncsec');
await A.waitForFunction(() => !!document.activeElement?.closest('.syncsec'), null, { timeout: 10_000 }).catch(() => {}); // focus moves there once the list is drawn
check(await A.evaluate(() => !!document.activeElement?.closest('.syncsec')), 'a click opens My routes at Pair with phone');
await A.click('#closePlans');

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
await A.click('#planList li:has-text("Phone name (copy)") .del');
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
await menu(B, '#menuPlans');
await B.waitForSelector('dialog.plans #unpair:not([hidden])', { timeout: 30_000 });
check((await B.textContent('dialog.plans .syncsec')).includes('Pair with phone') && (await B.textContent('#plansStatus')).includes('Paired with')
  && await B.isVisible('dialog.plans #syncNow') && await B.isHidden('dialog.plans #pairPhone'), 'My routes → Pair with phone: "Paired with …", Sync now, Unpair');
await B.click('dialog.plans #unpair');
await B.click('dialog.plans .confirm .danger');
await B.waitForFunction(() => document.getElementById('plansStatus')?.textContent?.includes('Unpaired'), null, { timeout: 30_000 }).catch(() => {});
check((await B.textContent('#plansStatus')).includes('Unpaired') && await B.isVisible('dialog.plans #pairPhone'), 'unpair on the phone side (in My routes; Pair phone offered again)');
await B.click('#closePlans');
check(await B.isHidden('#syncBadge'), 'after unpairing: no paired status in the header');
await syncNow(A);
const sa = await status(A);
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
