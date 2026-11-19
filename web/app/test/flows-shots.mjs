// The planner's flow pictures (docs/design/website-screens.md, flows B and the planner steps of C – round "Meran 0.17",
// owner 2026-11-07): B01–B14, C01, C02, C06 at 1440 × 900 (deviceScaleFactor 2), DE + EN, against a local map store
// (cells) and BRouter segments. The hike: the A-019 plan link Vellau (Korblift) → Leiteralm, Wandern T2. The pairing is
// real (the fake phone "Pixel 8" of sync-smoke against the host's mailbox code on a fake D1); the code is blurred anyway.
// B09 / B10: Partschins → Partschinser Wasserfall → Tablander Alm. B11–B14 (multi-day tours): the Meraner Höhenweg as a
// ring from Gasthof Hochmuth, 5 days (München–Venedig, mehrtage's scenario, lies outside the local map store).
// Every shot first checks the words the picture must show (in the page's text) and stops when one is missing.
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
const PARTSCHINS = 'AQUCA-DvuQSQl4cBhA-tJtYSug4AAgsAUGFydHNjaGlucwIYAVBhcnRzY2hpbnNlciBXYXNzZXJmYWxsAg4CVGFibGFuZGVyIEFsbQ'; // Partschins → Wasserfall → Tablander Alm (Hike T2)
// the Meraner Höhenweg, a ring from Gasthof Hochmuth over Hochganghaus, Giggelberg, Unterstell, Katharinaberg, Eishof,
// Stettiner Hütte, Pfelders, Ulfas (Hike T3, ~94 km, title „Meraner Höhenweg“) – the multi-day pictures B11–B14
const HOEHENWEG = 'AQUDDPiLugSE8YcBogbZH94K8yedLO898Q7VHK0ZuUWwL99M6GK6N7AHsFyWPLJgDOyUAauSAdlUAAABEU1lcmFuZXIgSMO2aGVud2VnAhEAR2FzdGhvZiBIb2NobXV0aAIKAUxlaXRlcmFsbQINAkhvY2hnYW5naGF1cwILA0dpZ2dlbGJlcmcCCgRIb2NoZm9yY2gCCwVVbnRlcnN0ZWxsAg4GS2F0aGFyaW5hYmVyZwIHB0Vpc2hvZgIRCFN0ZXR0aW5lciBIw7x0dGUCCQlQZmVsZGVycwIGClVsZmFzAhELR2FzdGhvZiBIb2NobXV0aA';
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
async function planPage(ctx, lang, code = PLAN) {
  const page = await ctx.newPage();
  await page.goto(url + `index.html?lang=${lang}#p=` + code);
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

/** The words the picture must show, checked in the page's text (DE / EN) – a missing word stops the run. Nothing half
 *  on screen either: no wait box or routing message left over. */
async function expectWords(page, words, name) {
  // a map note, the wait window (form A) or a wait box goes by itself: wait for it, then none may be left
  const notes = () => [...document.querySelectorAll('#mapnotes .mapnote, #msgcards .msgcard.busy, #waitwin')].filter((c) => (c.offsetParent || c.id === 'waitwin') && !c.hidden)
    .map((c) => c.innerText.replace(/\s+/g, ' '));
  await page.waitForFunction(`(${notes})().length === 0`, null, { timeout: 60_000 }).catch(() => {});
  const left = await page.evaluate(notes);
  if (left.length) throw new Error(`${name}: a note / wait box is on screen: ${left.join(' | ')}`);
  const missing = await page.evaluate((ws) => { const txt = [document.body.innerText, ...[...document.querySelectorAll('input')].filter((i) => i.offsetParent)
      .map((i) => i.value || i.placeholder)].join(' ').replace(/\s+/g, ' ');
    return ws.filter((w) => !txt.includes(w)); }, words);
  if (missing.length) throw new Error(`${name}: missing on screen: ${missing.join(' | ')}`);
  const cards = await page.evaluate(() => [...document.querySelectorAll('#msgcards .msgcard')].filter((c) => c.offsetParent)
    .map((c) => ({ busy: c.classList.contains('busy'), text: c.innerText.replace(/\s+/g, ' ') })));
  if (cards.some((c) => c.busy)) throw new Error(`${name}: a wait box is on screen: ${cards.map((c) => c.text).join(' | ')}`);
  if (cards.length) console.log(`  (${name}: message on screen: ${cards.map((c) => c.text).join(' | ')})`);
}
const shot = async (page, name, lang, words = null) => { if (words) await expectWords(page, words[lang], name); await page.waitForTimeout(400); await page.screenshot({ path: path.join(out, `${name}-${lang}.png`), timeout: 180_000 }); console.log(`  ${name}-${lang}.png`); };

// the words each picture must show (DE / EN) – the app's strings word for word
const W = {
  B01: { de: ['Orte oder Adressen suchen', 'Meine Touren', 'Mehrtagestour planen'], en: ['Search places or addresses', 'My tours', 'Plan a multi-day tour'] },
  B02: { de: ['Vellau Korblift (Talstation)', 'Leiteralm', '4,5 km', 'Wandern · T2', 'Höhenprofil'], en: ['Vellau Korblift (Talstation)', 'Leiteralm', '4.5 km', 'Hike · T2', 'Elevation profile'] },
  B04: { de: ['Abfliegen', '3D-Ansicht – nur zum Anschauen'], en: ['Fly along', '3D view – just for looking'] },
  B06: { de: ['Teilen'], en: ['Share'] },
  B07: { de: ['Meine Touren', 'In Bearbeitung', 'Waalweg', 'Vellau Korblift (Talstation) → Leiteralm'], en: ['My tours', 'In progress', 'Waalweg', 'Vellau Korblift (Talstation) → Leiteralm'] },
  B08: { de: ['4,5 km', 'Abfliegen'], en: ['4.5 km', 'Fly along'] },
  B09: { de: ['Partschins', 'Tablander Alm', 'Abfliegen', 'Höhenprofil'], en: ['Partschins', 'Tablander Alm', 'Fly along', 'Elevation profile'] },
  B10: { de: ['Partschins', 'Tablander Alm'], en: ['Partschins', 'Tablander Alm'] },
  B11: { de: ['Mehrtagestour planen', 'Meraner Höhenweg', 'Noch etwas anhängen:', 'GPX-Track laden', 'Gespeicherte Route öffnen', 'Weiter: Tage festlegen'],
    en: ['Plan a multi-day tour', 'Meraner Höhenweg', 'Append more:', 'Load GPX track', 'Open saved route', 'Next: set the days'] },
  B12: { de: ['Als Mehrtagestour planen', 'Gasthof Hochmuth', 'Pfelders'], en: ['Plan as multi-day tour', 'Gasthof Hochmuth', 'Pfelders'] },
  B13: { de: ['Tage festlegen', 'Meraner Höhenweg', 'pro Tag', 'Fertig'], en: ['Set the days', 'Meraner Höhenweg', 'per day', 'Done'] },
  B14: { de: ['Meraner Höhenweg', '5 Tage', 'Eishof', 'Pfelders'], en: ['Meraner Höhenweg', '5 days', 'Eishof', 'Pfelders'] },
  C01: { de: ['Handy verbinden', 'Wie funktioniert das?'], en: ['Pair phone', 'How does it work?'] },
  C02: { de: ['Handy verbinden', 'Prüfe:', 'die App zeigt dieselben Ziffern'], en: ['Pair phone', 'Check:', 'the app shows the same digits'] },
  C06: { de: ['Pixel 8', 'Gespeichert'], en: ['Pixel 8', 'Saved'] },
};
const tick = async (page) => { await page.waitForTimeout(1500); await settle(page); };
const fit = async (page) => { await page.click('#fitRoute').catch(() => {}); await tick(page); };
/** The flyover started, held once for a sharp picture, then flying again (the bar shows Pause while flying). */
async function flyHeld(page, ms) {
  await page.click('#flyStart');
  await page.waitForTimeout(ms);
  await page.click('#flyPause'); await tick(page);
  await page.click('#flyResume'); await page.waitForTimeout(150);
}
const flyStop = async (page) => { await page.click('#flyStop').catch(() => {}); await page.waitForTimeout(1500); };

for (const [lang, locale] of [['de', 'de-DE'], ['en', 'en-GB']]) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, locale });
  await ctx.route('https://photon.komoot.io/**', (r) => r.abort());

  if (want('B01')) { // the planner opened: map of Meran, empty plan
    const page = await ctx.newPage();
    await page.goto(url + `index.html?lang=${lang}`);
    await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
    await quiet(page);
    await page.evaluate((v) => window.__map.jumpTo(v), MERAN);
    await tick(page);
    await shot(page, 'B01-web-start-leer', lang, W.B01);
    await page.close();
  }

  const page = await planPage(ctx, lang);
  if (want('B02')) await shot(page, 'B02-web-route-fertig', lang, W.B02); // route, length · ↑ · ↓, points, profile along the bottom
  if (want('B03')) { // the big profile with the cursor linked to the map (pointer over the profile, ~40 % along)
    const plot = page.locator('#bigProfile .bpplot svg').first();
    const b = await plot.boundingBox();
    if (b) { await page.mouse.move(b.x + b.width * 0.42, b.y + b.height * 0.5); await page.waitForTimeout(600); }
    await shot(page, 'B03-web-grosses-profil', lang, { de: [...W.B02.de, ' · 1,9 km · '], en: [...W.B02.en, ' · 1.9 km · '] });
    await page.mouse.move(5, 5);
  }
  if (want('B04') || want('B05')) {
    await page.click('#terrain3d');
    await page.waitForTimeout(2500); await settle(page);
    if (want('B04')) await shot(page, 'B04-web-3d-gelaende', lang, W.B04); // the route in 3D, „▶ Abfliegen“ beside it
    if (want('B05')) { // during the flyover: Pause / Stopp, 1× / 2×
      await flyHeld(page, 9000);
      await shot(page, 'B05-web-3d-abfliegen', lang);
      await flyStop(page);
    }
    await page.click('#terrain3d'); await page.waitForTimeout(1500);
    await fit(page); // back to the whole route (3D leaves the camera where it was)
  }
  if (want('B06')) { // Teilen with the plan's QR code (the public plan link – harmless)
    await page.click('#share');
    await page.waitForSelector('dialog.share[open]'); await page.waitForTimeout(500);
    await shot(page, 'B06-web-teilen-qr', lang, W.B06);
    await page.click('#closeShare');
  }
  if (want('C01')) { // the Phone window, not paired, „Wie funktioniert das?“ open
    await page.click('#phoneMore');
    await page.click('#phoneMenu [role=menuitem], #phoneMenu button').catch(() => {});
    await page.waitForSelector('dialog.phone[open] #howToggle', { timeout: 10_000 });
    await page.click('dialog.phone #howToggle'); await page.waitForTimeout(400);
    await shot(page, 'C01-web-handy-fenster', lang, W.C01);
    await page.keyboard.press('Escape'); await page.waitForTimeout(300);
  }
  let phone = null;
  if (want('C02') || want('C06') || want('B07')) {
    // C02: „Aufs Handy“ unpaired opens „Handy verbinden“: QR, the way in the app, check digits – the code blurred
    await page.click('#syncBadge');
    await page.waitForSelector('dialog.pair[open] #pairLink', { timeout: 30_000 });
    const code = (await page.inputValue('#pairLink')).split('#pair=')[1];
    await page.addStyleTag({ content: 'dialog.pair .qr svg, #pairLink { filter: blur(9px); }' });
    if (want('C02')) await shot(page, 'C02-web-koppel-code', lang, W.C02);
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
    if (want('C06')) await shot(page, 'C06-web-aufs-handy-sichern', lang, W.C06);
  }
  if (want('B07')) { // Meine Touren with route pictures, „In Bearbeitung“ (no large overview in the planner, owner 2026-11-13)
    // a second saved tour (opened once here, so its row has its route picture), then the hike on the map again
    await page.evaluate((c) => { location.hash = 'p=' + c; }, WAAL); await planned(page); await page.waitForTimeout(1200);
    await page.click('#menuSavePlan');
    await page.waitForSelector('dialog.savename[open] #saveOk', { timeout: 10_000 }).then(async () => { await page.waitForTimeout(1500); await page.click('dialog.savename #saveOk'); }).catch(() => {});
    await page.waitForTimeout(1500);
    await page.evaluate((c) => { location.hash = 'p=' + c; }, PLAN); await planned(page); await page.waitForTimeout(1500); await settle(page);
    await page.click('#menuPlans');
    await page.waitForSelector('dialog.plans[open] #planList li', { timeout: 15_000 });
    await page.waitForTimeout(1200);
    await shot(page, 'B07-web-meine-touren', lang, W.B07);
    await page.click('#closePlans').catch(() => {});
  }
  await page.close();

  if (want('B08')) { // 3D showcase: the hike above Algund, Meran, Dorf Tirol and Schenna behind (looking east-south-east)
    const p = await planPage(ctx, lang);
    await p.click('#bigProfile .bpclose').catch(() => {});
    await p.click('#terrain3d'); await p.waitForTimeout(2500); await settle(p);
    await p.evaluate(() => window.__map.jumpTo({ center: [11.135, 46.692], zoom: 12.8, pitch: 66, bearing: 72, padding: { left: 560, top: 0, right: 0, bottom: 0 } }));
    await p.waitForTimeout(3000); await settle(p);
    await shot(p, 'B08-web-3d-route-ueber-meran', lang, W.B08);
    await p.click('#profileBig'); // the big profile open again (closing it is remembered) for the pictures after this
    await p.waitForSelector('#bigProfile:not([hidden]) .bpplot svg', { timeout: 20_000 });
    await p.close();
  }
  if (want('B09') || want('B10')) { // Partschins → Partschinser Wasserfall → Tablander Alm in 3D, the big profile below
    const p = await planPage(ctx, lang, PARTSCHINS);
    await p.click('#terrain3d'); await p.waitForTimeout(3000); await settle(p);
    if (want('B09')) {
      const b = await p.locator('#bigProfile .bpplot svg').first().boundingBox();
      if (b) { await p.mouse.move(b.x + b.width * 0.55, b.y + b.height * 0.5); await p.waitForTimeout(600); }
      await shot(p, 'B09-web-3d-partschins-grosses-profil', lang, W.B09);
      await p.mouse.move(5, 5);
    }
    if (want('B10')) { // during the flyover: Pause · Stopp · 1×, the dot on the route and in the profile
      await flyHeld(p, 12_000);
      await shot(p, 'B10-web-3d-abfliegen-partschins', lang, W.B10);
      await flyStop(p);
    }
    await p.close();
  }
  if (['B11', 'B12', 'B13', 'B14'].some(want)) await multiDayShots(ctx, lang);
  await ctx.close();
}

/** B11–B14, multi-day tours (docs/design/multi-day-tours.md §3): the Meraner Höhenweg (a ring, ~94 km) made a 5-day
 *  tour – the way in from the header and from Speichern ▾, the days box, the overview with the nights on the map. */
async function multiDayShots(ctx, lang) {
  const de = lang === 'de';
  const p = await planPage(ctx, lang, HOEHENWEG);
  await fit(p);
  const menuItem = async (label) => { await p.waitForSelector('.holdpopup .pointmenu', { timeout: 10_000 });
    await p.locator('.holdpopup .pointmenu button', { hasText: label }).first().click(); };
  const pressPin = (name) => p.evaluate((nm) => [...document.querySelectorAll('.pin')].find((e) => e.title.includes(nm))
    ?.dispatchEvent(new MouseEvent('click', { bubbles: true })), name);
  const nights = (kind) => p.evaluate((k) => window.__days.lastView?.ends.filter((e) => e.kind === k).length ?? 0, kind);
  const settled = () => p.waitForFunction(() => document.body.dataset.planning !== '1' && !!document.querySelector('#summary .big'), null, { timeout: 180_000 });

  if (want('B12')) { // Speichern ▾ on any plan: „Als Mehrtagestour planen“
    await p.click('#saveMore');
    await p.waitForSelector('#saveMenu:not([hidden]) #planAsTourItem');
    await p.waitForTimeout(400);
    await shot(p, 'B12-web-als-mehrtagestour-planen', lang, W.B12);
    await p.keyboard.press('Escape'); await p.waitForTimeout(300);
    if (await p.locator('#saveMenu:not([hidden])').count()) await p.click('#saveMore');
  }
  // B11: the header's „Mehrtagestour planen“ → the guide: 1 · Route ✓ (the Höhenweg) + „Noch etwas anhängen“, 2 · Tage
  await p.click('#menuMultiDay');
  await p.waitForSelector('#tourGuide:not([hidden])');
  await p.waitForFunction(() => !document.getElementById('tgNext')?.disabled, null, { timeout: 60_000 });
  await tick(p);
  if (want('B11')) await shot(p, 'B11-web-mehrtagestour-planen', lang, W.B11);

  // B13: „Weiter: Tage festlegen“ → the days box: the name, at most 8 h per day, 5 days, „≈ … pro Tag“
  await p.click('#tgNext');
  await p.waitForSelector('dialog.daysbox[open]');
  await p.fill('#dbHours', '8');
  await p.waitForFunction(() => /\d/.test(document.getElementById('dbSuggest')?.textContent ?? ''), null, { timeout: 20_000 }).catch(() => {});
  for (let k = 0; k < 10; k++) {
    const n = Number((await p.textContent('#dbN')).match(/\d+/)?.[0] ?? 0);
    if (n === 5) break;
    await p.click(n < 5 ? '#dbPlus' : '#dbMinus'); await p.waitForTimeout(200);
  }
  await p.waitForFunction(() => /^5\b/.test(document.getElementById('dbN')?.textContent ?? '') && document.getElementById('dbPer')?.textContent?.includes('≈'), null, { timeout: 20_000 });
  await p.evaluate(() => document.activeElement?.blur()); // no focus ring, no spinner in the hours field
  await p.waitForTimeout(400);
  if (want('B13')) await shot(p, 'B13-web-tage-festlegen', lang, W.B13);
  await p.click('#dbDone');
  await p.waitForFunction(() => window.__days.lastView?.days === 5, null, { timeout: 60_000 });

  if (want('B14')) { // the overview: nights at two huts (blue moons), the Eishof with its hotel (a green bed), one night
    // still open (a grey moon between Pfelders and the end: days 4 and 5 even out), 5 day rows
    for (const name of ['Unterstell', 'Eishof', 'Pfelders']) {
      const n0 = await nights('OVERNIGHT');
      await pressPin(name);
      await menuItem(de ? 'Übernachtung hier' : 'Overnight stop here');
      await p.waitForFunction((n) => (window.__days.lastView?.ends.filter((e) => e.kind === 'OVERNIGHT').length ?? 0) > n, n0, { timeout: 60_000 });
      await settled();
    }
    await p.evaluate(() => [...document.querySelectorAll('.pin.night')].find((e) => e.title.includes('Eishof'))
      ?.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    await menuItem(de ? 'Hotel hinterlegen' : 'Add hotel');
    await p.waitForSelector('dialog.hoteldlg[open]');
    await p.fill('#hotelQ', 'Eishof\nhttps://www.google.com/maps/place/Eishof/@46.75241,10.96968,17z');
    await p.waitForSelector('#hotelRes button', { timeout: 10_000 });
    await p.click('#hotelRes button');
    await p.waitForFunction(() => window.__days.lastView?.ends.some((e) => e.kind === 'HOTEL'), null, { timeout: 60_000 });
    await settled();
    const v = await p.evaluate(() => window.__days.lastView.ends.map((e) => e.kind).join(','));
    if (v !== 'OVERNIGHT,HOTEL,OVERNIGHT,PROPOSAL') throw new Error(`B14: the nights are ${v}`);
    await p.keyboard.press('Escape');
    await fit(p); await p.waitForTimeout(1500); await settle(p);
    await shot(p, 'B14-web-mehrtagestour-uebersicht', lang, W.B14);
  }
  await p.close();
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
