// The planner's pictures for onpath.earth (docs/design/website-screens.md, "Browser planner"), DE + EN:
// web-plan (= web-wandern), web-teilen (= web-qr), web-handy at 1440 × 900, the film picture at 2560 × 1440, and the
// pairing help W1–W6 (captain 2026-11-01, ordered by cloud-web), 1440 × 900: a real pairing
// with the fake phone "Pixel 8" of sync-smoke.mjs against the host's mailbox code on a fake D1 – no real mailbox, and
// the pairing code (QR + link) blurred anyway. Plan: the A-019 link Vellau Korblift → Leiteralm (Hike T2).
// W1 header unpaired (Save + Pair phone) · W2 pairing dialog (QR, check digits) · W3 header paired (Save to phone dark,
// phone pill "synced 2 min ago") · W4 after the click (✓ On the phone) · W5 My routes paired (🖥 📱, one waiting in
// bold, one "New" from the phone) · W6 the Phone window paired (name + dot, Sync now, red Unpair).
// Re-run after a UI change (only the rows whose files changed are needed – the table names them).
// Usage: node test/pair-shots.mjs <segmentsDir> <mapFolder (format 1)> <outDir>
import path from 'node:path';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { handleSync } from '../../host/src/sync.js';
import { fakeD1 } from '../../host/test/fake-d1.js';

const PLAN = 'AQUCAvCBugSuzocBqhD8AgMCHQBWZWxsYXUgS29yYmxpZnQgKFRhbHN0YXRpb24pAgoBTGVpdGVyYWxt'; // Vellau Korblift → Leiteralm
const OTHER = 'AQACAujkuQSU6YcBi2X0MAMCBwBBbGd1bmQCBQFMYW5h'; // Algund → Lana (the waiting row)
const here = path.dirname(fileURLToPath(import.meta.url));
const [segments, mapDir, out] = process.argv.slice(2).map((a) => path.resolve(a));
fs.mkdirSync(out, { recursive: true });
const env = { SYNC: fakeD1() };
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, mapDir, sync: (r) => handleSync(r, env) });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page0 = '?map=folder';

// ---- the fake phone (as in sync-smoke.mjs): the app's shared sync code in an idle page's engine, HTTP to the host ----
const ctxP = await browser.newContext();
const enginePage = await ctxP.newPage();
await enginePage.goto(url + 'index.html' + page0);
await enginePage.waitForFunction(() => window.__sync?.status && document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
const call = (fn, ...args) => enginePage.evaluate(([f, a]) => window.__sync.engine.call(f, ...a), [fn, args]);
const rnd = (n) => crypto.randomBytes(n).toString('base64url');
async function box(method, mailbox, token, { path: p = '', body, base, since } = {}) {
  const q = since != null ? `?since=${since}` : '';
  const r = await handleSync(new Request(`http://phone.test/sync/v1/${mailbox}${p}${q}`, { method, body,
    headers: { authorization: 'Bearer ' + token, 'user-agent': 'Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8)', ...(base != null ? { 'x-onpath-base': String(base) } : {}) } }), env, Date.now());
  return { status: r.status, json: await r.json().catch(() => ({})) };
}
class Phone {
  constructor(name) { this.name = name; this.ledger = ''; this.pair = null; }
  async init() { this.device = await call('syncNewDevice', rnd(8)); return this; }
  async keys() { return JSON.parse(await call('syncKeys', this.pair)); }
  async pairDirect(code) { this.pair = code; this.ledger = await call('syncHelloAs', '', this.device, this.name, Date.now(), 1); await this.round(); }
  async round() {
    const k = await this.keys();
    for (let pass = 0; pass < 4; pass++) {
      const since = JSON.parse(await call('syncState', this.ledger)).since;
      let r = await box('GET', k.mailbox, k.token, { since });
      if (r.status === 404) { await box('POST', k.mailbox, k.token); r = await box('GET', k.mailbox, k.token, { since }); }
      if (r.status !== 200) return;
      const o = await call('syncApply', this.ledger, this.pair, r.json.items.map((i) => `${i.id} ${i.rev} ${i.blob}`).join('\n'), r.json.rev);
      this.ledger = o.slice(0, o.lastIndexOf('#bad '));
      let conflict = false;
      for (const line of (await call('syncOutbox', this.ledger, this.pair, rnd(12 * 64))).split('\n').filter(Boolean)) {
        const [id, base, blob, sent] = line.split(' ');
        const w = await box('PUT', k.mailbox, k.token, { path: '/' + id, body: Buffer.from(blob, 'base64url'), base });
        if (w.status === 409) { conflict = true; continue; }
        if (w.status === 200) this.ledger = await call('syncAcked', this.ledger, sent, w.json.rev);
      }
      if (!conflict) return;
    }
  }
  async save(title, plan) {
    const id = await call('syncNewId', rnd(16));
    this.ledger = await call('syncSave', this.ledger, id, plan, title, Date.now(), this.device, '', '');
  }
}

/** The plan opened, planned, the sheet at [step]; the page's own sync timer off (the pictures choose when it syncs). */
async function planPage(ctx, step) {
  const page = await ctx.newPage();
  await page.goto(url + 'index.html' + page0 + '#p=' + PLAN);
  await page.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1'
    && document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 180_000 });
  for (let i = 0; i < 3 && (await page.getAttribute('#sheet', 'data-step')) !== step; i++) { await page.click('#sheetStep'); await page.waitForTimeout(300); }
  await page.click('#fitRoute');
  await page.waitForTimeout(2500); // the camera and the map tiles
  await page.evaluate(() => clearInterval(window.__sync.timer));
  return page;
}

for (const [lang, locale] of [['de', 'de-DE'], ['en', 'en-GB']]) {
  // the film picture (marketing/video-aufnahmen-uebergabe.md §2): 2560 × 1440, the profile step, no dialog
  const big = await browser.newContext({ viewport: { width: 2560, height: 1440 }, locale });
  await big.route('https://photon.komoot.io/**', (r) => r.abort());
  const film = await planPage(big, 'profile');
  await film.screenshot({ path: path.join(out, `web-plan-vellau-leiteralm-${lang}.png`) });
  await big.close();

  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale });
  await ctx.route('https://photon.komoot.io/**', (r) => r.abort());
  // web-plan (= the home page's web-wandern): the route, time, profile and Points
  const pp = await planPage(ctx, 'points');
  await pp.screenshot({ path: path.join(out, `web-plan-${lang}.png`) });
  // web-teilen (= web-qr): Share with the QR code of the plan link (public, harmless)
  await pp.click('#share');
  await pp.waitForSelector('dialog.share[open]');
  await pp.waitForTimeout(500);
  await pp.screenshot({ path: path.join(out, `web-teilen-${lang}.png`) });
  await pp.click('#closeShare');
  // web-handy: the Phone window, not paired, "How does it work?" open
  await pp.click('#syncBadge');
  await pp.waitForSelector('dialog.phone[open] #howToggle');
  await pp.click('#howToggle');
  await pp.waitForTimeout(400);
  await pp.screenshot({ path: path.join(out, `web-handy-${lang}.png`) });
  await pp.close();

  await ctx.route('https://photon.komoot.io/**', (r) => r.abort());
  const page = await ctx.newPage();
  const shot = async (n) => { await page.waitForTimeout(400); await page.screenshot({ path: path.join(out, `web-w${n}-${lang}.png`) }); };
  await page.goto(url + 'index.html' + page0 + '#p=' + PLAN);
  await page.waitForFunction(() => document.querySelector('#summary .big') && document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 180_000 });
  await page.waitForTimeout(2500); // the map tiles and the first-route fit
  // the page's own timer off – the pictures choose when it syncs (Save / Sync now still work)
  await page.evaluate(() => clearInterval(window.__sync.timer));

  // W1: not paired – "Save" (dark, the route is new here) + "Pair phone"
  await shot(1);

  // W2: Pair phone → the QR code and the check digits; the code (QR + link) blurred
  await page.click('#syncBadge');
  await page.waitForSelector('dialog.phone[open] #pairPhone');
  await page.click('#pairPhone');
  await page.waitForSelector('dialog.pair[open] #pairLink', { timeout: 30_000 });
  const code = (await page.inputValue('#pairLink')).split('#pair=')[1];
  await page.addStyleTag({ content: 'dialog.pair .qr svg, #pairLink { filter: blur(9px); }' });
  await shot(2);

  // the phone scans it: its hello opens its folder – "Paired with Pixel 8"
  const phone = await new Phone('Pixel 8').init();
  await phone.pairDirect(code);
  await page.waitForFunction(() => document.getElementById('pairBody')?.textContent?.includes('Pixel 8'), null, { timeout: 30_000 });
  await page.click('#closePair');

  // W3: paired, the route not on the phone yet – "Save to phone" dark; the phone pill "synced 2 min ago"
  const seen = () => page.evaluate(() => { const s = window.__sync; s.status.phoneSeen = Date.now() - 2 * 60_000; s.changed(); });
  await page.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'dark', null, { timeout: 30_000 });
  await seen();
  await shot(3);

  // W4: the click – Save's name dialog, Save → "✓ On the phone"
  await page.click('#menuSavePlan');
  await page.waitForSelector('dialog.savename[open] #saveOk');
  await page.waitForTimeout(1500); // the place proposal "Vellau … → Leiteralm"
  await page.click('#saveOk');
  await page.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'done', null, { timeout: 30_000 });
  await seen();
  await shot(4);

  // W5: My routes paired – the saved route (🖥 📱), one from the phone ("New"), one waiting here (bold, 🖥 only)
  await phone.save(lang === 'de' ? 'Ifinger Hütte' : 'Ifinger hut', OTHER);
  await phone.round();
  await page.evaluate(() => window.__sync.syncNow());
  await page.evaluate(([c, t]) => window.__sync.save(c, t), [OTHER, 'Algund → Lana']);
  await seen();
  await page.click('#menuPlans');
  await page.waitForSelector('dialog.plans[open] #planList li[data-id]');
  await shot(5);
  await page.click('#closePlans');

  // W6: the Phone window paired – the phone's name and dot, Sync now, red Unpair at the bottom
  await seen();
  await page.click('#syncBadge');
  await page.waitForSelector('dialog.phone[open] #unpair:not([hidden])');
  await shot(6);
  await ctx.close();
  console.log(`${lang}: web-w1…w6-${lang}.png`);
}
await browser.close(); server.close();
