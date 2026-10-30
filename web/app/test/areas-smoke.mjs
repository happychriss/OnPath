// Never a blank map, every wait said, map areas on demand (docs/design/web-planner.md §17).
// A. No key on the host (owner step open): the owner's report reproduced – Locate jumps to a spot without cells. The
//    overview (outside any region package: Natural Earth water at the Bodensee) and a region package's relief (fake
//    package, solid magenta, Munich) stay visible at zoom 11–15; the strip says that the detailed map is not available
//    on the web yet. Nothing hangs. (C: a page opened on a spot without cells says "Overview only …" first.)
// B. Key + a fake doorman behind the host's real proxy (web/host/src/maps.js): Locate → "Finding your position…" →
//    the own area is requested once (reason point) → the strip shows the app's texts ("Preparing map for this area –
//    about 5 min", "Your position · waiting – about 5 min", then "being prepared – 40 %") → ready: current.json appears
//    in the store, the page reloads the cell sources (requests go to the new version folder) and the strip closes.
// C. Location denied → a red line that says how to allow it.
// D. Planning: the sheet says "Loading the routing engine…", "Loading the map for your area · about N MB" (routing data)
//    and "Calculating the route…" with a spinner.
// Usage: node test/areas-smoke.mjs [segmentsDir]
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { writeFakePackage } from './fake-package.mjs';
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
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(() => {
    window.__statusLog = [];
    addEventListener('DOMContentLoaded', () => new MutationObserver(() => {
      const s = document.getElementById('status');
      if (s?.textContent) window.__statusLog.push((s.classList.contains('busy') ? '[busy] ' : '') + s.textContent);
    }).observe(document.body, { subtree: true, childList: true, characterData: true }));
  });
  await page.goto(url + 'index.html?at=' + at);
  await page.waitForFunction(() => window.__map?.isStyleLoaded() && document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 60_000 });
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
  const asked = [];
  let polls = 0;
  const doorman = { fetch: async (req) => {
    const u = new URL(req.url);
    if (req.headers.get('x-onpath-key') !== 'k') return Response.json({ error: 'unknown app key' }, { status: 401 });
    if (u.pathname === '/v1/cells/request') {
      const b = await req.json(); asked.push(b);
      return Response.json({ cells: b.cells.map((id) => ({ id, state: 'queued', position: 1, eta_s: 290 })), poll_s: 5 });
    }
    const ids = u.searchParams.get('ids').split(',');
    polls++;
    if (polls >= 2) { // built: the bucket gets the cell's current.json (the page reads the files in place)
      const dir = path.join(store, 'cells/f2/10', String(cellX), String(cellY));
      fs.mkdirSync(path.join(dir, 'web-test-s7'), { recursive: true });
      fs.writeFileSync(path.join(dir, 'current.json'), JSON.stringify({ version: 'web-test-s7' }));
      return Response.json({ cells: ids.map((id) => ({ id, state: 'ready', version: 'web-test-s7' })), poll_s: 60 });
    }
    return Response.json({ cells: ids.map((id) => ({ id, state: 'building', step: 'base', progress: 0.4, eta_s: 110 })), poll_s: 5 });
  } };
  const { page, ctx, server, log } = await open({ geolocation: { ...MUNICH, accuracy: 20 }, permissions: ['geolocation'] }, '11.16,46.67,12',
    { maps: (req) => handleMaps(req, { MAP_KEY: 'k', DOORMAN: doorman }) });
  await page.click('#locate');
  check(await waitStrip(page, 'Preparing map for this area – about 5 min', 15_000), `the own area is requested: "${(await strip(page)).split('\n')[0]}"`);
  check((await strip(page)).includes('Your position · waiting – about 5 min'), 'row: Your position · waiting – about 5 min (the app\'s texts)');
  check(asked.length === 1 && asked[0].reason === 'point' && asked[0].cells.join() === CELL && asked[0].formats.join() === '2',
    `one request through the proxy: ${JSON.stringify(asked)}`);
  check(await waitStrip(page, 'being prepared – 40 %, about 2 min', 15_000), 'polling: "being prepared – 40 %, about 2 min"');
  const gone = await page.waitForFunction(() => document.getElementById('mapnotes').hidden, null, { timeout: 20_000 }).then(() => true, () => false);
  check(gone, 'ready: the strip closes by itself (no "done" message)');
  await idle(page);
  check(log.some((u) => u.includes(`/data/cells/f2/10/${cellX}/${cellY}/web-test-s7/`)), 'ready: the page reloads the cell sources and reads the new version in place');
  await page.evaluate(() => window.__map.jumpTo({ center: [11.6, 48.15], zoom: 14 })); // moves and rests inside the same area again
  await page.waitForTimeout(5000);
  check(asked.length === 1, `the area is asked for once (dedupe; ${asked.length} requests)`);
  await ctx.close(); server.close();
}

// ---- C: a page opened on a spot without cells, then location denied ----
{
  const { page, ctx, server } = await open({}, '11.16,46.67,12', { maps: (req) => handleMaps(req, {}) });
  check(await waitStrip(page, 'Overview only', 3000), `opened at zoom 12 without detailed map: "${(await strip(page)).split('\n')[0]}"`);
  check(await waitStrip(page, 'not available on the web yet', 10_000), 'after the map rested: not available on the web yet (no key)');
  await page.click('#locate');
  check(await waitStrip(page, 'Location is not allowed for this page', 10_000), `denied: "${(await strip(page)).replace(/\s+/g, ' ').slice(0, 90)}…"`);
  check(await page.evaluate(() => document.querySelector('.mapnote.error') != null), 'denied is a red line (ERROR) with a way out');
  await ctx.close(); server.close();
}

// ---- D: the sheet's waits while planning (zoom 8: no prefetch, so the plan downloads its routing data) ----
{
  const { page, ctx, server } = await open({}, '11.25,46.6,8', {});
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
  check(seen.some((t) => /^\[busy\] Loading the map for your area · about \d+ MB$/.test(t)), `routing data: "${seen.find((t) => t.includes('about')) ?? 'not seen'}"`);
  check(seen.includes('[busy] Calculating the route…'), 'routing: "Calculating the route…" (the app\'s plan_calculating) with a spinner');
  check(!(await page.evaluate(() => document.getElementById('status').classList.contains('busy'))), 'after the plan: no spinner left');
  await ctx.close(); server.close();
}

await browser.close();
fs.rmSync(store, { recursive: true, force: true });
const real = errors.filter((e) => !/status of 404/.test(e) && !/status of 503/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real.slice(0, 5)); fails.push('page errors'); }
console.log(fails.length ? 'FAILED' : 'all ok');
process.exit(fails.length ? 1 : 0);
