// Field bug 2026-11-12 (owner's Pixel, re-pairing; docs/design/web-sync.md §8 pacing): phone and browser pushed ~110
// plans each at once from one home IP into the host's per-IP limit (SYNC_RATE 120/min) → 429 / 500, the phone said
// „nicht angenommen“, and the open planner tab kept the IP over the limit (the phone went through only after the tab
// was closed). The browser against the host's real mailbox code (web/host/src/sync.js) on a fake D1, with a fake
// SYNC_RATE that counts every request of this "household" (one IP) like Cloudflare's simple limit. Proves:
//   1. right after pairing, 110 waiting routes go up in batches (≤ the engine's writes per round, one round per
//      pace.batchMs – shortened here), never above the limit, and all arrive;
//   2. a 429 is quiet (no red line, no error status) and the browser does NOT ask again before the back-off is over –
//      however often the tick, Sync now or the tab coming back asks; after it, it goes on by itself.
// Needs no map data. Usage: node test/sync-rate-smoke.mjs [segmentsDir]
import path from 'node:path';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { handleSync } from '../../host/src/sync.js';
import { fakeD1 } from '../../host/test/fake-d1.js';

const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (PlanCodeTest)
const here = path.dirname(fileURLToPath(import.meta.url));
const segArg = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const segments = fs.existsSync(segArg) ? segArg : here;

// the household's one IP: the host's limit a minute (wrangler.toml SYNC_RATE), denied ones count too; [deny] = over it now
const RATE = 300;
const rate = { window: 0, n: 0, deny: false, tooMany: 0 };
const log = []; // every request: { t, method, web, status }
const env = {
  SYNC: fakeD1(),
  SYNC_RATE: { limit: async () => {
    const w = Math.floor(Date.now() / 60_000);
    if (w !== rate.window) { rate.window = w; rate.n = 0; }
    rate.n++;
    const ok = !rate.deny && rate.n <= RATE;
    if (!ok) rate.tooMany++;
    return { success: ok };
  } },
};
const handler = async (r) => {
  const res = await handleSync(r, env);
  log.push({ t: Date.now(), method: r.method, web: r.headers.get('x-onpath-client') === 'web', status: res.status });
  return res;
};
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, sync: handler });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function open(ctx, hash = '') {
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error' || m.text().startsWith('[Problems]')) errors.push(m.text()); });
  await page.goto(url + 'index.html' + hash);
  await page.waitForFunction(() => window.__sync?.status && document.body.dataset.engine === 'ready', null, { timeout: 120_000 }).catch(() => {});
  await page.waitForFunction(() => document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 60_000 });
  return page;
}
const status = (P) => P.evaluate(() => ({ ...window.__sync.status }));

// ---- the fake phone: the shared Kotlin in the engine of an idle page (as sync-smoke.mjs), one IP with the browser ----
const ctxP = await browser.newContext();
const enginePage = await open(ctxP);
const call = (fn, ...args) => enginePage.evaluate(([f, a]) => window.__sync.engine.call(f, ...a), [fn, args]);
const rnd = (n) => crypto.randomBytes(n).toString('base64url');
async function box(method, mailbox, token, { path: p = '', body, base, since } = {}) {
  const q = since != null ? `?since=${since}` : '';
  const r = await handler(new Request(`http://phone.test/sync/v1/${mailbox}${p}${q}`, { method, body,
    headers: { authorization: 'Bearer ' + token, 'user-agent': 'Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8)', ...(base != null ? { 'x-onpath-base': String(base) } : {}) } }));
  return { status: r.status, json: await r.json().catch(() => ({})) };
}
const phone = { name: 'Pixel 8', ledger: '', pair: null, device: await call('syncNewDevice', rnd(8)) };
const phoneKeys = async () => JSON.parse(await call('syncKeys', phone.pair));
async function phoneRound() { // hello + pull (the phone has no routes of its own here)
  const k = await phoneKeys();
  const since = JSON.parse(await call('syncState', phone.ledger)).since;
  let r = await box('GET', k.mailbox, k.token, { since });
  if (r.status === 404) { await box('POST', k.mailbox, k.token); r = await box('GET', k.mailbox, k.token, { since }); }
  if (r.status !== 200) return r.status;
  const out = await call('syncApply', phone.ledger, phone.pair, r.json.items.map((i) => `${i.id} ${i.rev} ${i.blob}`).join('\n'), r.json.rev);
  phone.ledger = out.slice(0, out.lastIndexOf('#bad '));
  for (const line of (await call('syncOutbox', phone.ledger, phone.pair, rnd(12 * 4))).split('\n').filter(Boolean)) {
    const [id, base, blob, sent] = line.split(' ');
    const w = await box('PUT', k.mailbox, k.token, { path: '/' + id, body: Buffer.from(blob, 'base64url'), base });
    if (w.status === 200) phone.ledger = await call('syncAcked', phone.ledger, sent, w.json.rev);
  }
  return 200;
}
const phonePlans = async () => (JSON.parse(await call('syncList', phone.ledger, phone.device))).length;

// ---- 1. 110 routes planned without a phone, then paired: they go up in paced batches, all arrive, no 429 ----
const ctxA = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const A = await open(ctxA);
const N = 110;
await A.evaluate(async ([code, n]) => { for (let i = 0; i < n; i++) await window.__sync.save(code, 'Route ' + i); }, [CODE, N]);
check((await A.evaluate(async () => (await window.__sync.list()).length)) === N, `${N} routes saved without a phone`);
// the test's minute is short: one batch every 1.5 s, back-off 1 s doubling up to 3 s (the engine's writes per round stay)
const pace = await A.evaluate(() => { const p = window.__sync.pace; if (p) Object.assign(p, { batchMs: 1500, retryMs: 1000, maxRetryMs: 3000 }); return p ? { ...p } : null; });
const W = pace?.writes ?? 30;
check(pace && pace.writes > 0 && pace.writes <= 40, `the page paces with the engine's writes per round (${JSON.stringify(pace)})`);

// pairing as the planner does it: the page's pairing code, the phone scans it (hello), the page's next round
const code = await A.evaluate(async () => { const r = await window.__sync.pairing(location.href); return r.link?.split('#pair=')[1] ?? null; });
check(!!code, 'a pairing code');
phone.pair = code;
phone.ledger = await call('syncRebase', phone.ledger, 0);
phone.ledger = await call('syncHelloAs', phone.ledger, phone.device, phone.name, Date.now(), 1);
await phoneRound();
const t0 = Date.now();
await A.evaluate(() => window.__sync.syncNow());
const firstPuts = log.filter((r) => r.web && r.method === 'PUT' && r.t >= t0).length;
check(firstPuts <= W + 1, `the first round after pairing sends one batch: ${firstPuts} PUTs (≤ ${W} + the hello), not all ${N} at once`);
const end = Date.now() + 40_000;
while (Date.now() < end && (await status(A)).pending > 0) await sleep(250);
let st = await status(A);
check(st.paired && st.pending === 0, `all ${N} routes went up by themselves in ${Math.round((Date.now() - t0) / 1000)} s (pending ${st.pending})`);
await phoneRound();
check(await phonePlans() === N, `the phone holds all ${N} (${await phonePlans()})`);
// no 1.5-s stretch (the test's "minute") with more than one batch of the browser's writes
const puts = log.filter((r) => r.web && r.method === 'PUT' && r.t >= t0).map((r) => r.t);
const worst = Math.max(0, ...puts.map((t) => puts.filter((u) => u >= t && u < t + 1400).length));
check(worst <= W + 1, `never more than one batch per pace interval (worst ${worst})`);
check(rate.tooMany === 0, `the household never hit the limit (429s: ${rate.tooMany})`);
check(st.kind !== 'error', `no red line (${JSON.stringify([st.kind, st.message])})`);

// ---- 2. the limit is reached (another device of the household): quiet, and no asking again before the back-off ----
rate.deny = true; rate.n = 0;
const before = log.length;
await A.evaluate(() => window.__sync.syncNow());
st = await status(A);
const firstAsk = log.length - before;
check(firstAsk >= 1 && log.slice(before).some((r) => r.status === 429), `the round met the 429 (${firstAsk} request(s))`);
check(st.kind !== 'error' && !/did not work|nicht/.test(st.message), `a 429 is quiet: no red line (${JSON.stringify([st.kind, st.message])})`);
const mark = log.length;
for (let i = 0; i < 5; i++) {
  await A.evaluate(() => window.__sync.syncNow());
  await A.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
}
await sleep(300);
check(log.length === mark, `during the back-off the page asks nothing more (Sync now ×5, tab back ×5: ${log.length - mark} requests)`);
rate.deny = false; rate.n = 0; // the next minute: the limit has room again (the denied requests counted too)
const backAt = Date.now();
await A.evaluate(() => { window.__mark = window.__sync.status.lastSync ?? 0; });
const ok = await A.waitForFunction(() => (window.__sync.status.lastSync ?? 0) > window.__mark, null, { timeout: 15_000 }).then(() => true).catch(() => false);
st = await status(A);
check(ok && st.kind !== 'error', `after the back-off it goes on by itself (${Math.round((Date.now() - backAt) / 1000)} s, ${JSON.stringify([st.kind, st.message])})`);

await ctxA.close(); await ctxP.close();
await browser.close(); server.close();
const real = errors.filter((e) => !/status of (404|409|410|429|507)/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
console.log(fails.length ? `FAILED: ${fails.length}` : 'all ok');
process.exit(fails.length ? 1 : 0);
