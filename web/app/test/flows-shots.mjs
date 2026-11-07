// The planner's flow pictures (docs/design/website-screens.md, flows B and the planner steps of C – round "Meran 0.17",
// owner 2026-11-07): B01–B07, C01, C02, C06 at 1440 × 900 (deviceScaleFactor 2), DE + EN, against a local map store
// (cells) and BRouter segments. The hike: the A-019 plan link Vellau (Korblift) → Leiteralm, Wandern T2. The pairing is
// real (the fake phone "Pixel 8" of sync-smoke against the host's mailbox code on a fake D1); the code is blurred anyway.
// With --film it also records film scene 1 (`film-1-browser-planen`, 2560 × 1440, WebM → MP4 with ffmpeg when present).
// With --probe it only prints the route's facts and bounds (check the route before taking pictures).
// Re-run after a UI change – only the steps whose files changed are needed (the table in website-screens.md names them).
//   node test/flows-shots.mjs <segmentsDir> <cellStore> <outDir> [--probe] [--film <filmDir>] [--only B02,C06]
import path from 'node:path';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { handleSync } from '../../host/src/sync.js';
import { fakeD1 } from '../../host/test/fake-d1.js';

const PLAN = 'AQUCAvCBugSuzocBqhD8AgMCHQBWZWxsYXUgS29yYmxpZnQgKFRhbHN0YXRpb24pAgoBTGVpdGVyYWxt'; // A-019: Vellau Korblift → Leiteralm, Hike T2
const WAAL = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // a second saved tour: Meran → Vellau „Waalweg“ (Hike T3)
const MERAN = { center: [11.135, 46.675], zoom: 12.6 }; // the empty start (B01): Meran, Algund, Vellau
const here = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const flag = (f) => argv.includes(f);
const opt = (f) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : null; };
const [segments, dataDir, out] = argv.filter((a, i) => !a.startsWith('--') && !(i > 0 && argv[i - 1].startsWith('--') && ['--film', '--only'].includes(argv[i - 1]))).slice(0, 3).map((a) => path.resolve(a));
const only = opt('--only')?.split(',');
const want = (s) => !only || only.includes(s);
fs.mkdirSync(out, { recursive: true });
const env = { SYNC: fakeD1() };
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir, sync: (r) => handleSync(r, env) });
// the machine's graphics card when there is one (3D at 2× and the film are too slow in software); FLOWS_SOFT=1 = SwiftShader
const browser = await chromium.launch({ args: process.env.FLOWS_SOFT ? ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader']
  : ['--use-gl=angle', '--use-angle=gl', '--ignore-gpu-blocklist'] });
const settle = (page) => page.waitForFunction(() => !window.__map.isMoving() && window.__map.areTilesLoaded(), null, { timeout: 60_000 }).catch(() => {});
const planned = (page) => page.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1'
  && document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 180_000 });
const quiet = async (page) => { // no first-visit hints or map notes over the picture
  await page.click('#appHintLine .x').catch(() => {});
  await page.waitForFunction(() => !document.querySelector('.mapnote:not([hidden])'), null, { timeout: 20_000 }).catch(() => {});
};

// ---- the fake phone (as in sync-smoke.mjs / pair-shots.mjs): the shared sync code in an idle page's engine ----
const ctxP = await browser.newContext();
const enginePage = await ctxP.newPage();
await enginePage.goto(url + 'index.html');
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
}

/** The plan link opened and planned, hints gone, the page's own sync timer off (the pictures choose when it syncs). */
async function planPage(ctx, lang) {
  const page = await ctx.newPage();
  await page.goto(url + `index.html?lang=${lang}#p=` + PLAN);
  await planned(page);
  await page.evaluate(() => clearInterval(window.__sync.timer));
  await quiet(page);
  await page.waitForTimeout(1500); await settle(page);
  return page;
}

if (flag('--probe')) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'de-DE' });
  const page = await planPage(ctx, 'de');
  const facts = await page.evaluate(() => {
    const src = window.__map.getSource('planned-route');
    const fc = src?._data ?? src?.serialize?.().data;
    const pts = (fc?.features ?? []).flatMap((f) => f.geometry.type === 'LineString' ? f.geometry.coordinates : f.geometry.type === 'MultiLineString' ? f.geometry.coordinates.flat() : []);
    const lo = pts.map((p) => p[0]), la = pts.map((p) => p[1]);
    return { summary: document.getElementById('summary').innerText.replace(/\s+/g, ' '), points: window.__state.points,
      bounds: pts.length ? [Math.min(...lo), Math.min(...la), Math.max(...lo), Math.max(...la)] : null, n: pts.length,
      warnings: document.getElementById('warning')?.innerText ?? '' };
  });
  console.log(JSON.stringify(facts, null, 1));
  await page.screenshot({ path: path.join(out, 'probe-route.png') });
  await browser.close(); server.close(); process.exit(0);
}

const shot = async (page, name, lang) => { await page.waitForTimeout(400); await page.screenshot({ path: path.join(out, `${name}-${lang}.png`), timeout: 180_000 }); console.log(`  ${name}-${lang}.png`); };

for (const [lang, locale] of [['de', 'de-DE'], ['en', 'en-GB']]) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, locale });
  await ctx.route('https://photon.komoot.io/**', (r) => r.abort());

  if (want('B01')) { // the planner opened: map of Meran, empty plan
    const page = await ctx.newPage();
    await page.goto(url + `index.html?lang=${lang}`);
    await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
    await quiet(page);
    await page.evaluate((v) => window.__map.jumpTo(v), MERAN);
    await page.waitForTimeout(1500); await settle(page);
    await shot(page, 'B01-web-start-leer', lang);
    await page.close();
  }

  const page = await planPage(ctx, lang);
  if (want('B02')) await shot(page, 'B02-web-route-fertig', lang); // route, length · ↑ · ↓, points, profile along the bottom
  if (want('B03')) { // the big profile with the cursor linked to the map (pointer over the profile, ~40 % along)
    const plot = page.locator('#bigProfile .bpplot svg').first();
    const b = await plot.boundingBox();
    if (b) { await page.mouse.move(b.x + b.width * 0.42, b.y + b.height * 0.5); await page.waitForTimeout(600); }
    await shot(page, 'B03-web-grosses-profil', lang);
    await page.mouse.move(5, 5);
  }
  if (want('B04') || want('B05')) {
    await page.click('#terrain3d');
    await page.waitForTimeout(2500); await settle(page);
    if (want('B04')) await shot(page, 'B04-web-3d-gelaende', lang); // the route in 3D, „▶ Abfliegen“ beside it
    if (want('B05')) { // during the flyover: Pause / Stopp, 1× / 2×
      await page.click('#flyStart');
      await page.waitForTimeout(9000);
      await page.evaluate(() => document.querySelector('#flyBar .flybtn')?.click()); // ❚❚ – hold the camera for a sharp picture
      await page.waitForTimeout(1500); await settle(page);
      await page.evaluate(() => document.querySelector('#flyBar .flybtn')?.click()); // ▶ again: the bar shows Pause while flying
      await page.waitForTimeout(150);
      await shot(page, 'B05-web-3d-abfliegen', lang);
      await page.evaluate(() => [...document.querySelectorAll('#flyBar .flybtn')].find((b) => /■/.test(b.textContent))?.click());
      await page.waitForTimeout(1500);
    }
    await page.click('#terrain3d'); await page.waitForTimeout(1500); await settle(page);
  }
  if (want('B06')) { // Teilen with the plan's QR code (the public plan link – harmless)
    await page.click('#share');
    await page.waitForSelector('dialog.share[open]'); await page.waitForTimeout(500);
    await shot(page, 'B06-web-teilen-qr', lang);
    await page.click('#closeShare');
  }
  if (want('C01')) { // the Phone window, not paired, „Wie funktioniert das?“ open
    await page.click('#phoneMore');
    await page.click('#phoneMenu [role=menuitem], #phoneMenu button').catch(() => {});
    await page.waitForSelector('dialog.phone[open] #howToggle', { timeout: 10_000 });
    await page.click('dialog.phone #howToggle'); await page.waitForTimeout(400);
    await shot(page, 'C01-web-handy-fenster', lang);
    await page.keyboard.press('Escape'); await page.waitForTimeout(300);
  }
  let phone = null;
  if (want('C02') || want('C06') || want('B07')) {
    // C02: „Aufs Handy“ unpaired opens „Handy verbinden“: QR, the way in the app, check digits – the code blurred
    await page.click('#syncBadge');
    await page.waitForSelector('dialog.pair[open] #pairLink', { timeout: 30_000 });
    const code = (await page.inputValue('#pairLink')).split('#pair=')[1];
    await page.addStyleTag({ content: 'dialog.pair .qr svg, #pairLink { filter: blur(9px); }' });
    if (want('C02')) await shot(page, 'C02-web-koppel-code', lang);
    phone = await new Phone('Pixel 8').init();
    await phone.pairDirect(code);
    await page.waitForFunction(() => document.getElementById('pairBody')?.textContent?.includes('Pixel 8'), null, { timeout: 30_000 });
    await page.click('#closePair').catch(() => {});
    await page.waitForTimeout(1000);
    // after the pairing „Aufs Handy“ sends the open plan: a never-saved one asks for its name first
    if (await page.locator('dialog.savename[open] #saveOk').count()) { await page.waitForTimeout(1500); await page.click('dialog.savename #saveOk'); }
    await page.waitForTimeout(800);
    // paired: Save (with the small phone) sends; the 📱 status says „abgeglichen vor 2 Min.“
    const seen = () => page.evaluate(() => { const s = window.__sync; s.status.phoneSeen = Date.now() - 2 * 60_000; s.changed(); });
    if ((await page.getAttribute('#menuSavePlan', 'data-state')) !== 'done') {
      await page.click('#menuSavePlan');
      await page.waitForSelector('dialog.savename[open] #saveOk', { timeout: 10_000 }).then(async () => { await page.waitForTimeout(1500); await page.click('#saveOk'); }).catch(() => {});
      await page.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'done', null, { timeout: 30_000 }).catch(() => {});
    }
    await phone.round();
    await page.evaluate(() => window.__sync.syncNow());
    await page.waitForTimeout(800);
    await seen(); await settle(page);
    if (want('C06')) await shot(page, 'C06-web-aufs-handy-sichern', lang);
  }
  if (want('B07')) { // Meine Touren with route pictures, „In Bearbeitung“, then the large overview
    // a second saved tour (opened once here, so its row has its route picture), then the hike on the map again
    await page.evaluate((c) => { location.hash = 'p=' + c; }, WAAL); await planned(page); await page.waitForTimeout(1200);
    await page.click('#menuSavePlan');
    await page.waitForSelector('dialog.savename[open] #saveOk', { timeout: 10_000 }).then(async () => { await page.waitForTimeout(1500); await page.click('dialog.savename #saveOk'); }).catch(() => {});
    await page.waitForTimeout(1500);
    await page.evaluate((c) => { location.hash = 'p=' + c; }, PLAN); await planned(page); await page.waitForTimeout(1500); await settle(page);
    await page.click('#menuPlans');
    await page.waitForSelector('dialog.plans[open] #planList li', { timeout: 15_000 });
    await page.waitForTimeout(1200);
    await shot(page, 'B07-web-meine-touren', lang);
    const row = page.locator('dialog.plans li.tourrow.saved .open').last();
    if (await row.count()) {
      await row.click();
      await page.waitForSelector('dialog.overview .ovpic svg, dialog.overview .ovpic p', { timeout: 10_000 }).catch(() => {});
      await page.waitForTimeout(1200);
      await shot(page, 'B07b-web-meine-touren-uebersicht', lang);
    }
  }
  await ctx.close();
}

const filmDir = opt('--film');
if (filmDir) { // film scene 1: plan link opens, route draws itself, 3D + Abfliegen 8–10 s (2560 × 1440)
  fs.mkdirSync(filmDir, { recursive: true });
  const ctx = await browser.newContext({ viewport: { width: 2560, height: 1440 }, locale: 'de-DE', recordVideo: { dir: filmDir, size: { width: 2560, height: 1440 } } });
  await ctx.route('https://photon.komoot.io/**', (r) => r.abort());
  const page = await ctx.newPage();
  await page.goto(url + 'index.html?lang=de');
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
  await quiet(page);
  await page.evaluate((v) => window.__map.jumpTo(v), MERAN);
  await page.waitForTimeout(2500); await settle(page);
  await page.evaluate((c) => { location.hash = 'p=' + c; }, PLAN); // the plan link opens in the planner
  await planned(page); await quiet(page);
  await page.waitForTimeout(3500); await settle(page);
  await page.click('#terrain3d');
  await page.waitForTimeout(3000); await settle(page);
  await page.click('#flyStart');
  await page.waitForTimeout(10_000);
  const video = page.video();
  await ctx.close();
  const webm = await video.path();
  const mp4 = path.join(filmDir, 'film-1-browser-planen.mp4');
  try {
    execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', webm, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '20', mp4]);
    fs.rmSync(webm); console.log(`film: ${mp4}`);
  } catch (e) { const keep = path.join(filmDir, 'film-1-browser-planen.webm'); fs.renameSync(webm, keep); console.log(`film: ${keep} (no ffmpeg: ${e.message.split('\n')[0]})`); }
}
await browser.close(); server.close();
