// Never a blank map, every wait said, map areas on demand (docs/design/web-planner.md §17).
// A. No key on the host (owner step open): the owner's report reproduced – Locate jumps to a spot without cells. The
//    overview (outside any region package: Natural Earth water at the Bodensee) and a region package's relief (fake
//    package, solid magenta, Munich) stay visible at zoom 11–15; the strip says that the detailed map is not available
//    on the web yet. Nothing hangs. (C: a page opened on a spot without cells says "Overview only – set a point here …" and orders nothing.)
// B. Key + a fake doorman behind the host's real proxy (web/host/src/maps.js): Locate → "Finding your position…" →
//    the own area is requested once (reason point) → the wait window (form A, owner 2026-11-19) says it with the app's
//    words: "Map for your area", one bar with its percent, the row "being prepared", "about 5 minutes left"; the doorman's
//    40 % moves the bar → ready: current.json appears in the store, the page reloads the cell sources (requests go to
//    the new version folder) and the window closes by itself.
// C. Location denied → a red line that says how to allow it.
// D. Planning: the sheet says "Loading the routing engine…"; the wait window "Map for your area · 45 %" (routing data)
//    and "Calculating the route · 80 %" – one bar, a percent all the way.
// E. The map areas along a loaded route load beside the map: the sign (form B) – one percent, tap → details, Cancel all.
// Usage: node test/areas-smoke.mjs [segmentsDir]
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { writeFakePackage, writeFakeCell } from './fake-package.mjs';
import { handleMaps } from '../../host/src/maps.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const dist = path.resolve(here, '../dist');
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const overview = fs.existsSync(path.join(dist, 'overview/base.pmtiles'));
const store = fs.mkdtempSync(path.join(os.tmpdir(), 'onpath-areas-'));
writeFakePackage(store, 68, 44); // Munich, Augsburg: magenta relief z7–9
const MUNICH = { latitude: 48.137, longitude: 11.575 };
const cellX = Math.floor(((MUNICH.longitude + 180) / 360) * 1024);
const cellY = Math.floor(((1 - Math.log(Math.tan((MUNICH.latitude * Math.PI) / 180) + 1 / Math.cos((MUNICH.latitude * Math.PI) / 180)) / Math.PI) / 2) * 1024);
const CELL = `10-${cellX}-${cellY}`;

const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
async function open(ctxOpts, at, serverOpts) {
  const log = [];
  const { server, url } = await serve({ dist, segments, dataDir: store, log, ...serverOpts });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, ...ctxOpts });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); if (process.env.DEBUG_AREAS && !/404/.test(m.text())) console.log('console', m.text().slice(0, 200)); });
  await page.addInitScript(() => {
    window.__statusLog = []; window.__winLog = []; window.__beside = [];
    addEventListener('DOMContentLoaded', () => new MutationObserver(() => {
      const s = document.getElementById('status');
      if (s?.textContent) window.__statusLog.push((s.classList.contains('busy') ? '[busy] ' : '') + s.textContent);
      // the wait window (form A, owner 2026-11-19): its title and its percent – the map of a new place and the route
      const o = document.getElementById('waitwin');
      // one message at a time: no map note beside the wait window (owner 2026-11-19 – „Nur Übersicht …“ while that map loads)
      const note = [...document.querySelectorAll('#mapnotes .mapnote')].find((n) => n.getClientRects().length && getComputedStyle(n).display !== 'none');
      if (o && note && window.__beside.length < 5) window.__beside.push(note.textContent);
      const ot = o && `[busy] ${o.querySelector('.wwtitle')?.textContent} · ${o.querySelector('.wwpct')?.textContent}`;
      if (ot && window.__statusLog.at(-1) !== ot) window.__statusLog.push(ot);
      if (!o && window.__statusLog.length && window.__statusLog.at(-1) !== '[gone]' && window.__statusLog.at(-1).includes(' %')) window.__statusLog.push('[gone]');
      const rows = o && [...o.querySelectorAll('.wwsteps li')].map((l) => l.className + ':' + l.textContent).join(', ') + ' | ' + o.querySelector('.wwline')?.textContent;
      if (rows && window.__winLog.at(-1) !== rows) window.__winLog.push(rows);
    }).observe(document.body, { subtree: true, childList: true, characterData: true }));
  });
  await page.goto(url + 'index.html?waitMs=0&at=' + at); // waitMs=0: the route's wait box at once (no 1 s delay)
  await page.waitForFunction(() => window.__map?.isStyleLoaded() && document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
  return { page, ctx, server, log };
}
const strip = (page) => page.evaluate(() => document.getElementById('mapnotes').innerText);
const waitStrip = (page, text, ms = 20_000) => page.waitForFunction((t) => document.getElementById('mapnotes').innerText.includes(t), text, { timeout: ms }).then(() => true, () => false);
const idle = (page) => page.evaluate(() => new Promise((r) => { const m = window.__map; m.once('idle', () => setTimeout(r, 300)); m.triggerRepaint(); setTimeout(r, 15_000); }));
/** RGB at the map centre (+[dx], [dy]) as the user sees it. */
async function pixel(page, dx = 150, dy = 120) {
  const r = await page.locator('#map').boundingBox();
  const shot = await page.screenshot({ clip: { x: Math.round(r.x + r.width / 2 + dx), y: Math.round(r.y + r.height / 2 + dy), width: 1, height: 1 } });
  return page.evaluate(async (b64) => {
    const img = new Image(); img.src = 'data:image/png;base64,' + b64; await img.decode();
    const c = new OffscreenCanvas(1, 1); const g = c.getContext('2d'); g.drawImage(img, 0, 0);
    return [...g.getImageData(0, 0, 1, 1).data.slice(0, 3)];
  }, shot.toString('base64'));
}
const magenta = ([r, g, b]) => r - g > 50 && b - g > 40;

// ---- A: no key – the owner's report: Locate → a spot without cells ----
{
  const { page, ctx, server } = await open({ geolocation: { ...MUNICH, accuracy: 20 }, permissions: ['geolocation'] }, '11.16,46.67,12',
    { maps: (req) => handleMaps(req, {}) });
  await page.click('#locate');
  await page.waitForFunction(() => window.__map.getZoom() >= 14.9 && Math.abs(window.__map.getCenter().lat - 48.137) < 0.01, null, { timeout: 20_000 });
  await idle(page);
  check(await waitStrip(page, 'not available on the web yet', 15_000), 'no key on the host: the strip says the detailed map is not available on the web yet (nothing hangs)');
  if (overview) {
    const px = await pixel(page);
    check(magenta(px), `zoom 15 in a region package without cells: its relief goes on, magnified – never blank (pixel ${px})`);
    await page.screenshot({ path: path.join(here, '../../engine/build/areas-nokey.png') });
    await page.evaluate(() => window.__map.jumpTo({ center: [9.4, 47.6], zoom: 11 }));
    await idle(page);
    const under = await page.evaluate(() => window.__map.queryRenderedFeatures().filter((f) => f.layer.id.endsWith('~under')).length);
    check(under > 0, `zoom 11 at the Bodensee (no cell, no package): the overview draws (${under} features)`);
    const cover = await page.evaluate(() => window.__map.getSource('detail-cover')?.serialize().data.features.length ?? 0);
    check(cover === 0, `no cover where no cell draws (${cover})`);
  } else console.log('skip  overview pixels: this build has no overview files (ONPATH_OVERVIEW_DIR)');
  await ctx.close(); server.close();
}

// ---- B: key + fake doorman behind the real proxy ----
{
  const asked = [], ids = [];
  let polls = 0;
  let page = null, answeredAt = null; // the server's first estimate leaves at [answeredAt] (the page's clock)
  const doorman = { fetch: async (req) => {
    const u = new URL(req.url);
    if (req.headers.get('x-onpath-key') !== 'k') return Response.json({ error: 'unknown app key' }, { status: 401 });
    if (u.pathname === '/v1/cells/request') {
      const b = await req.json(); asked.push(b);
      // the server's answer a moment late: the window is up before any estimate (the „3 minutes“ flash, 2026-11-19)
      await new Promise((r) => setTimeout(r, 1500));
      answeredAt = await page.evaluate(() => performance.now()); // on the page's clock, before the answer leaves
      return Response.json({ cells: b.cells.map((id) => ({ id, state: 'queued', position: 1, eta_s: 290 })), poll_s: 5 });
    }
    const ids = u.searchParams.get('ids').split(',');
    polls++;
    if (polls >= 2) { // built: the bucket gets the cell's current.json (the page reads the files in place)
      // with real (fake) files: an empty version would rightly be reported as "could not be loaded" (cells.ts)
      writeFakeCell(store, cellX, cellY, 'web-test-s7');
      return Response.json({ cells: ids.map((id) => ({ id, state: 'ready', version: 'web-test-s7' })), poll_s: 60 });
    }
    return Response.json({ cells: ids.map((id) => ({ id, state: 'building', step: 'base', progress: 0.4, eta_s: 110 })), poll_s: 5 });
  } };
  const opened = await open({ geolocation: { ...MUNICH, accuracy: 20 }, permissions: ['geolocation'] }, '11.16,46.67,12',
    { maps: (req) => { if (req.method === 'POST') ids.push(req.headers.get('x-onpath-browser')); return handleMaps(req, { MAP_KEY: 'k', DOORMAN: doorman }); } });
  page = opened.page;
  const { ctx, server, log } = opened;
  // the window's line every 50 ms from its first appearance (the „3 minutes“ flash before the server's estimate)
  await page.evaluate(() => { window.__lines = []; window.__lTimer = setInterval(() => {
    const w = document.getElementById('waitwin');
    if (w || window.__lines.length) window.__lines.push({ t: performance.now(), line: w?.querySelector('.wwline')?.textContent ?? '',
      rows: w ? [...w.querySelectorAll('.wwsteps li')].map((l) => l.className + ':' + l.textContent).join(', ') + ' ' + w.dataset.pct + ' %' : '' });
  }, 50); });
  await page.click('#locate');
  // what the page did meanwhile, for a failure message (CI: the window late on two cores)
  await page.evaluate(() => { window.__bLog = []; const t0 = performance.now(); window.__bTimer = setInterval(() => {
    const w = document.getElementById('waitwin');
    const s = `win=${!!w} rows=${w?.querySelectorAll('.wwsteps li').length ?? 0} title=${w?.querySelector('.wwtitle')?.textContent ?? ''} engine=${window.__wait.ready()}/${window.__wait.loadMs()} strip=${document.getElementById('mapnotes').innerText.slice(0, 40)} moving=${window.__map.isMoving()}`;
    if (window.__bLog.at(-1)?.s !== s) window.__bLog.push({ t: Math.round(performance.now() - t0), s }); }, 100); });
  const win = () => page.evaluate(() => { const w = document.getElementById('waitwin'); return w ? { title: w.querySelector('.wwtitle').textContent, pct: +w.dataset.pct,
    rows: [...w.querySelectorAll('.wwsteps li')].map((l) => l.className + ':' + l.textContent), line: w.querySelector('.wwline').textContent } : null; });
  // this area's window, by its own first row: the page's first map may still have a window of its own that is closing
  // (CI 2026-11-21: a window was there, one call later none, then this one)
  const came = await page.waitForFunction(() => { const r = document.querySelector('#waitwin .wwsteps li'); return r?.className === 'now' && r.textContent === 'being prepared'; }, null, { timeout: 30_000 }).then(() => true, () => false);
  const w1 = await win();
  check(came && w1?.title === 'Map for your area' && w1.rows[0] === 'now:being prepared', `the own area is requested: the wait window ${JSON.stringify(w1)}`);
  const bLog = await page.evaluate(() => { clearInterval(window.__bTimer); return window.__bLog; });
  if (!came) console.log('(page: ' + bLog.map((x) => `${x.t} ${x.s}`).join('\n       ') + ')');
  check(!(await strip(page)).includes('Preparing map'), 'one wait: no strip line beside the window');
  for (let i = 0; i < 100 && answeredAt == null; i++) await page.waitForTimeout(100); // the server's first answer
  const left = await page.waitForFunction(() => /minutes? left/.test(document.querySelector('#waitwin .wwline')?.textContent ?? ''), null, { timeout: 10_000 }).then(() => true, () => false);
  check(left, `the time left from the server's estimate ("${(await win())?.line}")`);
  {
    const at = answeredAt;
    const lines = await page.evaluate(() => { clearInterval(window.__lTimer); return window.__lines; });
    // once up, this area's window stays: no gap in it (what a first map's closing window must not be mistaken for)
    const ownAt = lines.findIndex((x) => x.rows.startsWith('now:being prepared'));
    const gaps = ownAt < 0 ? -1 : lines.slice(ownAt).filter((x) => !x.rows).length;
    check(gaps === 0, `the area's window stays up once it is there (${gaps} samples without it, of ${lines.length - ownAt})`);
    // no time text (a stalled bar may say „taking a bit longer…“ – that is no number; CI 2026-11-20 on two cores: the
    // page's first map still loading said „about 5 seconds left“ by its tiles' assumed size)
    const early = lines.filter((x) => at != null && x.t < at && /left/.test(x.line));
    check(at != null && lines.some((x) => x.t < at) && early.length === 0,
      `no time before the server's first estimate (${lines.filter((x) => at != null && x.t < at).length} samples before it; ${[...new Set(early.map((x) => x.line + ' [' + x.rows + ']'))].join(' · ')})`);
  }
  check(asked.length === 1 && asked[0].reason === 'point' && asked[0].cells.join() === CELL && asked[0].formats.join() === '2',
    `one request through the proxy: ${JSON.stringify(asked)}`);
  check(ids.length === 1 && /^[0-9a-f]{32}$/.test(ids[0] ?? ''), `the request carries the browser's random id (quotas): ${ids[0]}`);
  const p0 = (await win())?.pct ?? 0;
  const moved = await page.waitForFunction((a) => +(document.getElementById('waitwin')?.dataset.pct ?? 0) > a, p0, { timeout: 15_000 }).then(() => true, () => false);
  check(moved, `polling: the doorman's 40 % moves the bar (from ${p0} %)`);
  const gone = await page.waitForFunction(() => !document.getElementById('waitwin') && document.getElementById('mapnotes').hidden, null, { timeout: 20_000 }).then(() => true, () => false);
  check(gone, 'ready: the window closes by itself (no "done" message)');
  // the window of the own area (the last one – the page's first map may have had its own before)
  const all = await page.evaluate(() => window.__statusLog);
  const lastWin = all.slice(all.lastIndexOf('[gone]', all.length - 2) + 1);
  const pcts = lastWin.map((x) => /· (\d+) %$/.exec(x)?.[1]).filter(Boolean).map(Number);
  check(pcts.length > 1 && pcts.every((v, i) => !i || v >= pcts[i - 1]) && pcts.at(-1) === 100, `never back, 100 at the end (${pcts.join(' ')})`);
  await idle(page);
  check(log.some((u) => u.includes(`/data/cells/f2/10/${cellX}/${cellY}/web-test-s7/`)), 'ready: the page reloads the cell sources and reads the new version in place');
  await page.evaluate(() => window.__map.jumpTo({ center: [11.6, 48.15], zoom: 14 })); // moves and rests inside the same area again
  await page.waitForTimeout(5000);
  check(asked.length === 1, `the area is asked for once (dedupe; ${asked.length} requests)`);
  await ctx.close(); server.close();
}

// ---- C: a page opened on a spot without cells, then location denied ----
{
  let calls = 0;
  const { page, ctx, server } = await open({}, '11.16,46.67,12', { maps: (req) => { calls++; return handleMaps(req, {}); } });
  check(await waitStrip(page, 'Overview only – set a point here to load the detailed map', 15_000), `opened at zoom 12 without detailed map: "${(await strip(page)).split('\n')[0]}"`);
  await page.waitForTimeout(6000);
  check(calls === 0 && (await strip(page)).includes('Overview only'), `the map resting orders nothing (public page, A-011): ${calls} map-area calls`);
  await page.click('#locate');
  check(await waitStrip(page, 'Location is not allowed for this page', 10_000), `denied: "${(await strip(page)).replace(/\s+/g, ' ').slice(0, 90)}…"`);
  check(await page.evaluate(() => document.querySelector('.mapnote.error') != null), 'denied is a red line (ERROR) with a way out');
  await ctx.close(); server.close();
}

// ---- C2: the host's quota for new map areas reached (A-011): a plain grey line with the way on, nothing more asked ----
{
  let calls = 0;
  // Vienna: no cell there (B built Munich's)
  const { page, ctx, server } = await open({ geolocation: { latitude: 48.2082, longitude: 16.3738, accuracy: 20 }, permissions: ['geolocation'] }, '11.16,46.67,12',
    { maps: () => { calls++; return Response.json({ error: 'quota reached', quota: 'browser' }, { status: 429 }); } });
  await page.click('#locate');
  check(await waitStrip(page, 'Enough new map areas for today in this browser', 15_000), `quota: "${(await strip(page)).split('\n')[0]}"`);
  check(await page.evaluate(() => document.querySelector('.mapnote.info') != null && !document.querySelector('.mapnote.error')), 'quota is a grey line (INFO), not an error');
  await page.waitForTimeout(6000);
  check(calls === 1, `one request, then nothing more is asked (${calls} calls)`);
  await ctx.close(); server.close();
}

// ---- D: the sheet's waits while planning (zoom 8: no prefetch, so the plan downloads its routing data) ----
{
  const { page, ctx, server } = await open({}, '11.25,46.6,8', {});
  // the window comes 400 ms into a map's wait and a faster route rightly shows none (no flash): the routing data takes
  // a moment here, as it does on the network – on a fast machine this server's answer alone can beat the 400 ms
  await page.route(/\.rd5/, async (r) => { await new Promise((f) => setTimeout(f, 700)); await r.continue().catch(() => {}); });
  const clickAt = async (lon, lat) => {
    const p = await page.evaluate(([lo, la]) => { const q = window.__map.project([lo, la]); return [q.x, q.y]; }, [lon, lat]);
    await page.mouse.click(p[0], p[1]);
  };
  await clickAt(11.16, 46.67);
  await page.waitForSelector('.pointmenu');
  await page.click('.pointmenu button:first-of-type');
  await page.waitForTimeout(300);
  await clickAt(11.35, 46.5);
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  const seen = await page.evaluate(() => window.__statusLog);
  check(seen.includes('[busy] Loading the routing engine…'), 'engine start: "Loading the routing engine…" with a spinner');
  const beside = await page.evaluate(() => window.__beside);
  check(beside.length === 0, `no map note while the wait window shows – it comes back after if still true (${JSON.stringify(beside)})`);
  // the routing data from this test server comes in a moment: the window may first show at its route step already
  check(seen.some((t) => /^\[busy\] (Map for your area|Calculating the route) · \d+ %$/.test(t)), `the plan's wait: the window with a percent ("${seen.find((t) => t.includes('%')) ?? 'not seen'}")`);
  console.log(`(the window's rows: ${(await page.evaluate(() => window.__winLog)).slice(0, 6).join(' ; ')})`);
  const lastWin = seen.slice(seen.lastIndexOf('[gone]', seen.length - 2) + 1);
  const pcts = lastWin.map((x) => /· (\d+) %$/.exec(x)?.[1]).filter(Boolean).map(Number);
  check(pcts.every((v, i) => !i || v >= pcts[i - 1]), `the window's percent never goes back (${pcts.join(' ')})`);
  check(seen.filter((t) => / %$/.test(t)).every((t) => /^\[busy\] (Map for your area|Calculating the route) · \d+ %$/.test(t)),
    `one window: the map's title, then the route's – a percent all the way (${JSON.stringify([...new Set(seen.filter((t) => t.includes('%')).map((t) => t.replace(/ · \d+ %/, '')))])})`);
  await page.waitForFunction(() => !document.getElementById('waitwin'), null, { timeout: 10_000 }).catch(() => {});
  check(!(await page.evaluate(() => document.getElementById('status').classList.contains('busy') || !!document.getElementById('waitwin'))), 'after the plan: no spinner, no window left');
  await ctx.close(); server.close();
}

// ---- E: the map areas along a loaded route (T4) load beside the map (form B, owner 2026-11-19 „B: einfach nur
//      Prozent, muss aber passen für die gesamte Aktion“) while the view has its map: one small sign – a ring and ONE
//      percent over all of them, never back – no window over the map; tap → the details (title, the bar, what loads, Cancel all · Close); Cancel all
//      takes the sign away ----
{
  const doorman = { fetch: async (req) => {
    const u = new URL(req.url);
    const ids = u.pathname === '/v1/cells/request' ? (await req.json()).cells : u.searchParams.get('ids').split(',');
    calls++;
    return Response.json({ cells: ids.map((id) => ({ id, state: 'building', step: 'base', progress: Math.min(0.9, 0.1 * calls), eta_s: 240 })), poll_s: 1 });
  } };
  let calls = 0;
  const { page, ctx, server } = await open({}, '11.14,46.67,9', { maps: (req) => handleMaps(req, { MAP_KEY: 'k', DOORMAN: doorman }) });
  // a loaded plan of four points (more than three new at once: only the route's areas are asked for, like the app)
  const code = await page.evaluate(() => window.__engine.encode({ points: [[46.6700, 11.1600], [46.6773, 11.1302], [46.6400, 11.1500], [46.6879, 11.1111]],
    activity: 'BIKE_TOURING', sac: 2, pinned: [], names: {}, title: null }));
  await page.evaluate((c) => { location.hash = 'p=' + c; }, code);
  // the route's fit zooms in where no detailed map is yet: the user then waits for it (form A, the app's
  // MapLoadWait – a job while the view has no detailed map); zoomed out the view has its map – A turns into B by itself
  await page.waitForFunction(() => window.__state.plan && document.body.dataset.planning !== '1', null, { timeout: 120_000 });
  // the route's fit first (it may come late on a slow machine – CI's two cores), then out to zoom 9, and stay there
  await page.waitForFunction(() => window.__map.getZoom() >= 10 && !window.__map.isMoving(), null, { timeout: 30_000 }).catch(() => {});
  for (let k = 0; k < 5; k++) {
    await page.evaluate(() => window.__map.jumpTo({ zoom: 9 }));
    await page.waitForTimeout(1500);
    if (await page.evaluate(() => window.__map.getZoom() < 10 && !window.__map.isMoving())) break;
  }
  const came = await page.waitForFunction(() => window.__wait.beside() >= 0, null, { timeout: 60_000 }).then(() => true, () => false);
  const pcts = [];
  for (let k = 0; k < 12; k++) { pcts.push(await page.evaluate(() => window.__wait.beside())); await page.waitForTimeout(1000); } // the page polls every 5 s at the most
  const sign = await page.evaluate(() => { const b = document.getElementById('besidesign'), r = b.getBoundingClientRect(), m = document.getElementById('map').getBoundingClientRect();
    return { text: b.textContent, label: b.getAttribute('aria-label'), w: r.width, h: r.height, inMap: r.left >= m.left && r.right <= m.right && r.top >= m.top && r.bottom <= m.bottom }; });
  check(came && /^\d{1,3} %$/.test(sign.text) && sign.inMap, `the route's areas: the sign beside the map – a ring and one percent (${JSON.stringify(sign)})`);
  check(pcts.every((v, i) => !i || v >= pcts[i - 1]) && pcts.at(-1) > pcts[0], `its percent moves on, never back (${pcts.join(' ')})`);
  check(!(await page.evaluate(() => !!document.getElementById('waitwin'))) && !(await strip(page)).includes('Preparing'), 'no window and no strip line for them (they load beside)');
  // a late answer about a spot the map has already left (CI 2026-11-21 on two cores): at zoom 11 the page asks whether
  // this spot has a detailed map; the view is back at zoom 9 before the answer comes. The old answer must not turn the
  // sign into a window over a view that has its map
  let held = 0;
  await page.route(/current\.json/, async (r) => { held++; await new Promise((f) => setTimeout(f, 1500)); await r.continue().catch(() => {}); });
  await page.evaluate(() => { const m = window.__map; m.jumpTo({ center: [11.9, 47.2], zoom: 11 }); m.jumpTo({ zoom: 9 }); });
  await page.waitForTimeout(3500);
  await page.unroute(/current\.json/);
  const late = await page.evaluate(() => ({ win: !!document.getElementById('waitwin'), sign: window.__wait.beside() }));
  check(held > 0 && !late.win && late.sign >= 0, `a late answer about a spot already left: the sign stays, no window (${JSON.stringify({ held, ...late })})`);
  if (process.env.SHOTS) await page.screenshot({ path: process.env.SHOTS + '-sign.png' });
  await page.click('#besidesign');
  const d = await page.evaluate(() => { const x = document.querySelector('.besidedetails'); return x && { title: x.querySelector('.bdtitle').textContent,
    rows: [...x.querySelectorAll('.bdrows li')].map((l) => l.textContent), pct: x.querySelector('.pct').textContent,
    buttons: [...x.querySelectorAll('button')].map((b) => b.textContent) }; });
  if (process.env.SHOTS) await page.screenshot({ path: process.env.SHOTS + '-details.png' });
  check(!!d && d.title === 'Loading in the background' && d.rows.length > 0 && /^\d+ %$/.test(d.pct) && d.buttons.join() === 'Cancel all,Close',
    `tap: the details – title, the bar, what loads, Cancel all · Close (${JSON.stringify(d)})`);
  await page.click('.besidedetails .bdcancel');
  check(await page.waitForFunction(() => window.__wait.beside() < 0 && !document.querySelector('.besidedetails'), null, { timeout: 5_000 }).then(() => true, () => false),
    'Cancel all: the sign goes');
  await ctx.close(); server.close();
}

await browser.close();
fs.rmSync(store, { recursive: true, force: true });
const real = errors.filter((e) => !/status of 404/.test(e) && !/status of 503/.test(e) && !/status of 429/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real.slice(0, 5)); fails.push('page errors'); }
console.log(fails.length ? 'FAILED' : 'all ok');
process.exit(fails.length ? 1 : 0);
