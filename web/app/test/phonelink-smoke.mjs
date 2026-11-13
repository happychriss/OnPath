// A shared plan link opened on a phone (owner 2026-11-13 field report): the person who gets a link sees the route on the
// map, not the plan sheet. While the map of the new place loads: the sheet peeks (collapsed, the facts only) and the one
// wait is the load overlay at the top of the map (owner 2026-11-13: „als Overlay … mit einer prozentualen
// Fortschrittsangabe“) – a short line, a percent that only rises, to 100 %, then the card goes by itself; no box over the
// map, no second wait line. Once routed the route lies whole in the map room above the peek. Wide screens: the side panel
// as before, the same overlay (with Cancel) instead of the wait box.
// Usage: node test/phonelink-smoke.mjs [segmentsDir] [cellStoreDir] [screenshotDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (pinned, named), "Waalweg"
const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = process.argv[3] && process.argv[3] !== '-' ? path.resolve(process.argv[3]) : null;
const shots = process.argv[4] ? path.resolve(process.argv[4]) : null;
if (shots) fs.mkdirSync(shots, { recursive: true });
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];

/**
 * In the page from its first moment: every [every] ms (default 50) the wait texts a person sees (the load overlay, the box over the map,
 * the strip's spinners, the sheet's line, the sheet's busy status line) and the overlay's percent – one entry per change.
 */
function watchWaits(every = 50) {
  const seen = (el) => !!el && !el.hidden && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';
  const tx = (el) => el.innerText.replace(/\s+/g, ' ').trim();
  window.__waits = () => {
    const out = [];
    const load = document.querySelector('#mapnotes .mapnote.load');
    if (seen(load)) out.push('overlay: ' + tx(load.querySelector('.title')));
    const card = document.querySelector('#msgcards .msgcard[data-id="routing"]');
    if (seen(card)) out.push('box: ' + tx(card));
    // a wait = a busy strip; an info strip (e.g. no detailed map for this area on the web yet) is no wait
    for (const n of document.querySelectorAll('#mapnotes .mapnote.busy:not(.load)')) if (seen(n)) out.push('strip: ' + tx(n));
    const sub = document.querySelector('#summary .subline');
    if (seen(sub) && document.body.dataset.planning === '1' && tx(sub)) out.push('sheet: ' + tx(sub));
    const st = document.getElementById('status');
    if (seen(st) && st.classList.contains('busy') && tx(st)) out.push('status: ' + tx(st));
    return out;
  };
  window.__log = { pct: [], many: [], maxWaits: 0, sessions: 0 };
  let was = false;
  setInterval(() => {
    if (!document.body) return;
    const w = window.__waits();
    const L = window.__log;
    L.maxWaits = Math.max(L.maxWaits, w.length);
    if (w.length > 1) { L.overlaps = (L.overlaps ?? 0) + 1; if (L.many.length < 5) L.many.push(w); }
    const load = document.querySelector('#mapnotes .mapnote.load');
    if (load && !was) L.sessions++;
    was = !!load;
    if (load) {
      const p = Number(load.dataset.pct);
      const last = L.pct.at(-1);
      if (!last || last.s !== L.sessions || last.p !== p) L.pct.push({ s: L.sessions, p, text: load.querySelector('.pct')?.textContent ?? '' });
    }
  }, every);
}

/** Percent shown by the first overlay: rises, never back, ends at 100. */
function checkPercent(log, who) {
  const first = log.pct.filter((x) => x.s === 1).map((x) => x.p);
  const back = log.pct.some((x, i) => i > 0 && log.pct[i - 1].s === x.s && x.p < log.pct[i - 1].p);
  check(first.length >= 3, `${who}: the percent moved through several steps (${first.join(' → ')})`);
  check(!back, `${who}: the percent never goes back (${log.pct.map((x) => x.s + ':' + x.p).join(' ')})`);
  check(first.at(-1) === 100, `${who}: the percent reaches 100`);
  check(log.pct.every((x) => /^\d{1,3}\s?%$/.test(x.text)), `${who}: the percent reads "45 %" (${log.pct.at(-1)?.text})`);
}

/**
 * The routing data held back until [release](), then slow: each answer a bit later than the one before, so the bytes
 * come in steps (the parts' sizes are known from the files' index – the percent must follow them).
 */
async function slowSegments(page) {
  let release; const held = new Promise((ok) => { release = ok; });
  let n = 0;
  await page.route(/\/segments\//, async (r) => {
    await held;
    const k = n++;
    await new Promise((ok) => setTimeout(ok, 250 * k));
    await r.continue().catch(() => {});
  });
  return () => release();
}

/** Share of the route line's points that lie in the free map room: below the top bar, above the sheet, inside the width. */
const routeInView = (page) => page.evaluate(() => {
  const plan = window.__state.plan; if (!plan) return 0;
  const map = window.__map, stage = document.getElementById('stage').getBoundingClientRect();
  const top = document.getElementById('topbar').getBoundingClientRect().bottom, bottom = document.getElementById('sheet').getBoundingClientRect().top;
  const pts = plan.legs.flatMap((l) => l.points);
  const inside = pts.filter(([la, lo]) => {
    const p = map.project([lo, la]); const x = p.x + stage.left, y = p.y + stage.top;
    return x >= stage.left && x <= stage.right && y >= top && y <= bottom;
  });
  return inside.length / Math.max(1, pts.length);
});
/**
 * Every point row of the open sheet is there to see (field 2026-11-13, Pixel 7: a two-point plan from a link listed
 * only „1 Start“ + „+ Add destination“ – the list squeezed to one row): none clipped by the list's own box, each inside
 * the sheet.
 */
const pointRows = (page) => page.evaluate(() => {
  const list = document.getElementById('points'), sheet = document.getElementById('sheet').getBoundingClientRect();
  const box = list.getBoundingClientRect();
  return [...list.querySelectorAll(':scope > li')].map((li) => {
    const r = li.getBoundingClientRect();
    return { text: li.innerText.replace(/\s+/g, ' ').trim(), shown: r.height > 0 && r.top >= box.top - 1 && r.bottom <= box.bottom + 1 && r.top >= sheet.top - 1 && r.bottom <= sheet.bottom + 1 };
  });
});
const overlayGone = (page) => page.waitForFunction(() => !document.querySelector('#mapnotes .mapnote.load'), null, { timeout: 30_000 }).then(() => true, () => false);

// 1. phone, first visit, the routing data slow: the sheet peeks, the load overlay is the one wait, no box over the map
{
  const ctx = await browser.newContext({ viewport: { width: 412, height: 839 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'en-US' });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(watchWaits);
  const release = await slowSegments(page);
  await page.goto(url + 'index.html?waitMs=0&lang=en#p=' + CODE);
  const came = await page.waitForSelector('#mapnotes .mapnote.load', { timeout: 60_000 }).then(() => true, () => false);
  check(came, 'phone, link loading: the load overlay appears over the map');
  await page.waitForFunction(() => document.body.dataset.planning === '1', null, { timeout: 60_000 }).catch(() => {});
  await page.waitForTimeout(1500);
  check(await page.getAttribute('#sheet', 'data-step') === 'small', 'phone, link loading: the sheet peeks (collapsed)');
  const w = await page.evaluate(() => window.__waits());
  check(w.length === 1 && w[0].startsWith('overlay:'), `phone, link loading: one wait – the overlay (${JSON.stringify(w)})`);
  check(w.every((s) => s.replace(/^\w+: /, '').length <= 40), 'phone, link loading: its line is short (≤ 40 characters)');
  const card = await page.evaluate(() => {
    const c = document.querySelector('#mapnotes .mapnote.load'); if (!c) return null;
    const r = c.getBoundingClientRect(), sheet = document.getElementById('sheet').getBoundingClientRect();
    return { pct: c.querySelector('.pct')?.textContent, bar: !!c.querySelector('.bar[role="progressbar"] > i'), top: r.top, bottom: r.bottom, sheetTop: sheet.top };
  });
  check(!!card && card.bar && /\d+\s?%/.test(card.pct ?? ''), `phone, link loading: a bar and a percent ("${card?.pct}")`);
  check(!!card && card.bottom < card.sheetTop && card.top < 839 / 3, 'phone, link loading: the card sits at the top of the map, clear of the sheet');
  const peek = await page.evaluate(() => document.getElementById('sheet').getBoundingClientRect().height / innerHeight);
  check(peek < 0.35, `phone, link loading: the peek leaves the map the main thing (${Math.round(peek * 100)} % of the height)`);
  if (shots) await page.screenshot({ path: path.join(shots, 'phone-link-loading.png') });
  release();
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  check(await overlayGone(page), 'phone, link routed: the overlay goes by itself');
  await page.waitForTimeout(1500);
  await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 20_000 }).catch(() => {});
  const log = await page.evaluate(() => window.__log);
  checkPercent(log, 'phone');
  check(log.maxWaits <= 1, `phone: never more than one wait at a time (${JSON.stringify(log.many)})`);

  // 2. once routed: still collapsed (length · ↑ ↓ in the peek), the whole route in the map room above it
  check(await page.getAttribute('#sheet', 'data-step') === 'small', 'phone, link routed: the sheet stays collapsed');
  check(await page.isVisible('#summary .big.dist') && await page.isVisible('#summary .big.updown'), 'phone, link routed: the peek shows km and ↑ ↓');
  check(!await page.isVisible('#pointsSection'), 'phone, link routed: the points list waits behind the handle');
  const share = await routeInView(page);
  check(share >= 0.98, `phone, link routed: the route lies in the map above the peek (${Math.round(share * 100)} %)`);
  // the user pulls it up
  await page.click('#handle');
  check(await page.getAttribute('#sheet', 'data-step') === 'points' && await page.isVisible('#pointsSection'), 'phone: the handle pulls the sheet up');
  const rows = await pointRows(page);
  check(rows.length === 2 && rows.every((r) => r.shown), `phone: the open sheet lists start and goal (${JSON.stringify(rows)})`);
  // a reload is the user's own plan in the address, no link from someone: the step they chose stays
  await page.reload();
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  check(await page.getAttribute('#sheet', 'data-step') === 'points', 'phone: a reload keeps the step the user chose');
  await ctx.close();
}

// 2b. a Pixel 7 in Chrome (412 × 760 CSS px under the address bar), German: the link routed, the sheet pulled up to its
// points step – both rows „1 Start“ and „Vellau · Ziel“ are there, then „+ Ziel hinzufügen“ (field 2026-11-13: the goal
// row was missing – the points list squeezed to its 96 px minimum, one row, by a sheet taller than the screen)
for (const height of [760, 640]) {
  const ctx = await browser.newContext({ viewport: { width: 412, height }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true, locale: 'de-DE' });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(url + 'index.html?waitMs=0&lang=de#p=' + CODE);
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  await page.waitForTimeout(800);
  await page.click('#handle');
  await page.waitForTimeout(400);
  const rows = await pointRows(page);
  check(rows.length === 2 && rows.every((r) => r.shown) && /Vellau/.test(rows[1]?.text ?? ''),
    `Pixel 7 412×${height}, link pulled up: start and the goal Vellau both listed (${JSON.stringify(rows)})`);
  const add = await page.evaluate(() => { const a = document.getElementById('addDest').getBoundingClientRect(), last = document.querySelector('#points > li:last-child').getBoundingClientRect(); return a.top >= last.bottom - 1; });
  check(add, `Pixel 7 412×${height}: „+ Ziel hinzufügen“ comes after the goal row`);
  if (shots) await page.screenshot({ path: path.join(shots, `pixel7-${height}-points.png`) });
  await ctx.close();
}

// 3. a plain visit on a phone (no link) keeps the remembered / default step – nothing forced
{
  const ctx = await browser.newContext({ viewport: { width: 412, height: 839 }, isMobile: true, hasTouch: true, locale: 'en-US' });
  const page = await ctx.newPage();
  await page.goto(url + 'index.html?lang=en');
  await page.waitForFunction(() => document.getElementById('sheet')?.dataset.step, null, { timeout: 60_000 });
  check(await page.getAttribute('#sheet', 'data-step') === 'points', 'phone, no link: the default step as before');
  await ctx.close();
}

// 4. wide: the side panel as before (points shown); the load overlay with Cancel instead of the wait box
{
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'en-US' });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.addInitScript(watchWaits);
  const release = await slowSegments(page);
  await page.goto(url + 'index.html?waitMs=0&lang=en#p=' + CODE);
  await page.waitForFunction(() => document.body.dataset.planning === '1', null, { timeout: 60_000 }).catch(() => {});
  await page.waitForTimeout(1500);
  const w = await page.evaluate(() => window.__waits());
  check(w.length === 1 && w[0].startsWith('overlay:'), `wide, link loading: one wait – the overlay, no box (${JSON.stringify(w)})`);
  const card = await page.evaluate(() => {
    const c = document.querySelector('#mapnotes .mapnote.load'); if (!c) return null;
    const r = c.getBoundingClientRect(), sheet = document.getElementById('sheet').getBoundingClientRect();
    return { pct: c.querySelector('.pct')?.textContent, cancel: c.querySelector('button.act')?.textContent, left: r.left, sheetRight: sheet.right };
  });
  check(!!card && /\d+\s?%/.test(card.pct ?? '') && card.cancel === 'Cancel', `wide, link loading: percent and Cancel in the card (${JSON.stringify(card)})`);
  check(!!card && card.left > card.sheetRight, 'wide, link loading: the card is on the map beside the side panel');
  if (shots) await page.screenshot({ path: path.join(shots, 'desktop-link-loading.png') });
  release();
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  check(await overlayGone(page), 'wide, link routed: the overlay goes by itself');
  const log = await page.evaluate(() => window.__log);
  checkPercent(log, 'wide');
  check(log.maxWaits <= 1, `wide: never more than one wait at a time (${JSON.stringify(log.many)})`);
  check(await page.isVisible('#pointsSection'), 'wide, link routed: the side panel shows the points as before');
  await ctx.close();
}

// 5. wide, Cancel in the overlay stops the plan like the wait box's Cancel did (the app's cancelRouting)
{
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'en-US' });
  const page = await ctx.newPage();
  const release = await slowSegments(page);
  await page.goto(url + 'index.html?waitMs=0&lang=en#p=' + CODE);
  await page.waitForSelector('#mapnotes .mapnote.load button.act', { timeout: 60_000 }).catch(() => {});
  await page.click('#mapnotes .mapnote.load button.act').catch(() => {});
  const stopped = await page.waitForFunction(() => /calculation stopped/.test(document.getElementById('summary').textContent), null, { timeout: 10_000 }).then(() => true, () => false);
  check(stopped, 'wide: Cancel in the overlay stops – "Route not calculated – calculation stopped."');
  check(await overlayGone(page), 'wide: after Cancel the overlay goes');
  release();
  await ctx.close();
}

// 6. one wait at a time while a map area is being prepared and the route waits (owner 2026-11-13: „das irritiert den
// Benutzer“ – the field run's „box: Calculating the route… Cancel“ + „strip: Preparing map for this area“). The doorman
// answers late (held), then "queued" (the area stays "preparing" through the whole load), or the host has no map
// service (a late 404: the strip shows a moment, then goes) – phone and desktop, the route held, then slow; sampled
// every 16 ms over the whole load, around Cancel, and around a failed download.
{
  let mapsMode = 'queued', holdMaps = 0;
  const maps = async (req) => {
    await new Promise((ok) => setTimeout(ok, holdMaps));
    if (mapsMode === 'none') return new Response('not found', { status: 404 });
    const u = new URL(req.url);
    const ids = req.method === 'POST' ? (await req.json()).cells : (u.searchParams.get('ids') ?? '').split(',');
    return Response.json({ cells: ids.map((id) => ({ id, state: 'queued', eta_s: 360 })), poll_s: 5 });
  };
  const s2 = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir, maps });
  const viewports = { phone: { viewport: { width: 412, height: 839 }, isMobile: true, hasTouch: true }, wide: { viewport: { width: 1280, height: 800 } } };
  const run = async (who, vp, mode, then) => {
    mapsMode = mode.maps; holdMaps = mode.hold;
    const ctx = await browser.newContext({ ...viewports[vp], locale: 'en-US' });
    const page = await ctx.newPage();
    page.on('pageerror', (e) => errors.push(String(e)));
    await page.addInitScript(watchWaits, 16);
    let release; const held = new Promise((ok) => { release = ok; });
    let n = 0;
    await page.route(/\/segments\//, async (r) => {
      await held;
      if (mode.failData) { await r.abort().catch(() => {}); return; }
      await new Promise((ok) => setTimeout(ok, 150 * n++));
      await r.continue().catch(() => {});
    });
    if (mode.later) {
      // the page open and calm first (engine ready, the map there, no card): then the link – a plain plan run, no link
      // share of the bar to bring the card up early
      await page.goto(s2.url + 'index.html?waitMs=0&lang=en');
      await page.waitForFunction(() => document.body.dataset.engine === 'ready' && window.__map?.areTilesLoaded() && !document.querySelector('#mapnotes .mapnote.load'), null, { timeout: 60_000 }).catch(() => {});
      await page.waitForTimeout(500);
      await page.evaluate((c) => { location.hash = 'p=' + c; }, CODE);
    } else await page.goto(s2.url + 'index.html?waitMs=0&lang=en#p=' + CODE);
    await page.waitForFunction(() => document.body.dataset.planning === '1', null, { timeout: 60_000 }).catch(() => {});
    await page.waitForTimeout(mode.holdFor ?? 1200);
    await then(page, release);
    await page.waitForTimeout(1500);
    const log = await page.evaluate(() => window.__log);
    check(log.maxWaits <= 1, `${who}: never more than one wait at a time, sampled every 16 ms (${log.overlaps ?? 0} overlapping samples ${JSON.stringify(log.many)})`);
    await ctx.close();
  };
  const routed = (page) => page.waitForFunction(() => document.querySelector('#summary .big') || /not calculated|failed|missing|Straight/i.test(document.getElementById('summary').textContent + document.getElementById('status').textContent), null, { timeout: 120_000 }).catch(() => {});
  const cancel = async (page, release) => {
    await page.click('#mapnotes .mapnote.load button.act, #msgcards .msgcard[data-id="routing"] .msgbtn').catch(() => {});
    await page.waitForTimeout(800); release();
  };
  for (const vp of ['phone', 'wide']) for (const later of [false, true]) {
    const who = vp + (later ? ' (link into an open page)' : '');
    // the static host: no map service, its 404 comes after 150 ms – the strip's „Preparing map …“ a moment, the route waiting
    await run(`${who}, no map service (late 404)`, vp, { maps: 'none', hold: 150, holdFor: 300, later }, async (page, release) => { release(); await routed(page); });
    // the area stays „preparing“ (queued) through the whole load, the doorman's first answer late
    await run(`${who}, area being prepared`, vp, { maps: 'queued', hold: 300, later }, async (page, release) => { release(); await routed(page); });
    // Cancel while the area is being prepared and the route waits
    await run(`${who}, Cancel while an area is prepared`, vp, { maps: 'queued', hold: 100, later }, cancel);
    // the routing data fails while the area is prepared
    await run(`${who}, routing data fails`, vp, { maps: 'queued', hold: 100, failData: true, later }, async (page, release) => { release(); await routed(page); });
  }
  s2.server.close();
}

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
