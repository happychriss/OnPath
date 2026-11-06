// A cell whose map files fail (docs/design/web-planner.md §17 "a cell whose map fails"; owner report 2026-10-30: Hilden
// showed POIs on beige, no streets, the page froze). A fake cell (fake-package.mjs writeFakeCell: water over every tile)
// is served like the R2 bucket, with faults injected per case. For each case: no request storm (bounded tries with
// backoff), no console errors, the overview's cover off the cell while its map does not draw (never a blank map), the
// map strip says it in plain words, and the problem log (window.__diag) has it once.
// A. healthy: the cell draws, the cover is on, no strip line.
// B. base.pmtiles 404 (file gone): current.json read once more (same version) → red "could not be loaded – reload",
//    the cover off, base.pmtiles asked at most twice while the user pans.
// C. base.pmtiles 503 twice (server busy): grey "Map server not reachable – trying again." → tried again after 5 s →
//    the cell draws, the line goes, the cover is back.
// D. base.pmtiles 429 always (the host's per-IP limit): grey line, the next try waits a minute (≤ 2 reads in 30 s).
// E. a new generation while the page is open (current.json → v2, the v1 folder gone): the page reads current.json
//    again once and draws from v2, no line.
// F. current.json 503 for 8 s: grey line, current.json not asked on every tile (≤ 4 reads), then the cell draws.
// G. every /data answer 2 s slow: "Loading the map for your area" with a spinner, then the cell draws, nothing failed.
// H. ?debug shows "Copy diagnostics"; window.__diag() returns the report with the problem lines.
// Usage: node test/cells-smoke.mjs [segmentsDir]
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { writeFakeCell } from './fake-package.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const dist = path.resolve(here, '../dist');
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const store = fs.mkdtempSync(path.join(os.tmpdir(), 'onpath-cells-'));
const [CX, CY] = [531, 342]; // Hilden (the owner's report)
writeFakeCell(store, CX, CY, 'test-s1');
writeFakeCell(store, CX, CY, 'test-s2', false);
const CELL = `cells/f2/10/${CX}/${CY}/`;
const AT = '6.93,51.16,12.5'; // inside the cell, its neighbours not built

const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
let fault = () => false; // (key, req, res) => true when it answered
const hits = [];
const { server, url } = await serve({ dist, segments, dataDir: store, intercept: (req, res) => {
  const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  if (!p.startsWith('/data/')) return false;
  const key = p.slice(6);
  hits.push({ key, t: Date.now() });
  return fault(key, req, res);
} });
const count = (re, since = 0) => hits.filter((h) => h.t >= since && re.test(h.key)).length;
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });

async function open(query = '') {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error' && !/status of (404|429|503)/.test(m.text())) errors.push(m.text()); });
  await page.addInitScript(() => { window.__gap = 0; let last = performance.now(); setInterval(() => { const n = performance.now(); window.__gap = Math.max(window.__gap, n - last - 100); last = n; }, 100); });
  await page.goto(url + `index.html?lang=en&at=${AT}${query}`);
  await page.waitForFunction(() => window.__map?.isStyleLoaded(), null, { timeout: 60_000 });
  return page;
}
const idle = (page) => page.evaluate(() => new Promise((r) => { const m = window.__map; m.once('idle', () => setTimeout(r, 300)); m.triggerRepaint(); setTimeout(r, 15_000); }));
const strip = (page) => page.evaluate(() => document.getElementById('mapnotes').innerText);
const waitStrip = (page, text, ms = 20_000) => page.waitForFunction((t) => document.getElementById('mapnotes').innerText.includes(t), text, { timeout: ms }).then(() => true, () => false);
const baseDrawn = (page) => page.evaluate(() => window.__map.queryRenderedFeatures().filter((f) => f.source === 'base').length);
const waitBase = (page, ms = 30_000) => page.waitForFunction(() => window.__map.queryRenderedFeatures().some((f) => f.source === 'base'), null, { timeout: ms, polling: 500 }).then(() => true, () => false);
/** True when the overview's cover lies on the cell (only with the overview style, else null). */
const covered = (page) => page.evaluate(() => {
  const s = window.__map.getSource('detail-cover');
  if (!s) return null;
  return (s.serialize().data.features ?? []).some((f) => f.geometry.coordinates[0].some(([x]) => Math.abs(x - 6.68) < 0.01)); // the cell's west edge
});
/** Small pans: new tiles asked for, as a user looking around. */
async function pan(page, n = 4) {
  for (let i = 0; i < n; i++) { await page.evaluate((d) => window.__map.panBy([d, d / 2], { duration: 0 }), (i % 2 ? -1 : 1) * 300); await page.waitForTimeout(700); }
}
const diag = (page) => page.evaluate(() => window.__diag());
const MSG_FAILED = 'could not be loaded';
const MSG_BUSY = 'Map server not reachable';

// ---- A: healthy ----
{
  fault = () => false;
  const page = await open();
  check(await waitBase(page), 'A healthy: the cell\'s base map draws');
  await idle(page);
  const c = await covered(page);
  check(c !== false, `A healthy: the overview's cover lies on the cell (${c})`);
  check(!(await strip(page)).includes(MSG_FAILED) && !(await strip(page)).includes(MSG_BUSY), 'A healthy: no failure line in the map strip');
  await page.close();
}

// ---- B: base.pmtiles gone ----
{
  fault = (key, req, res) => { if (key.endsWith('test-s1/base.pmtiles')) { res.writeHead(404).end(); return true; } return false; };
  const page = await open();
  check(await waitStrip(page, MSG_FAILED), 'B base 404: red line "The detailed map for this area could not be loaded … Reload the page"');
  check(await page.evaluate(() => !!document.querySelector('#mapnotes .mapnote.error')), 'B base 404: the line is an error (red)');
  const since = Date.now();
  await pan(page);
  await idle(page);
  check(count(/test-s1\/base\.pmtiles$/, since) === 0, `B base 404: no more reads of the broken file while panning (${count(/test-s1\/base\.pmtiles$/, since)})`);
  check(count(/current\.json$/) <= 40, `B base 404: current.json not asked over and over (${count(/current\.json$/)} reads, most for the empty neighbours)`);
  check((await covered(page)) !== true, 'B base 404: the overview\'s cover is off the cell (the overview shows)');
  check(await baseDrawn(page) === 0, 'B base 404: nothing drawn from the broken file');
  const d = await diag(page);
  check(/ERROR \[cells\] map cell 531\/342: base\.pmtiles not readable/.test(d), 'B base 404: the problem log has the broken file, once');
  check((d.match(/base\.pmtiles not readable/g) ?? []).length === 1, 'B base 404: … only once');
  console.log(`     max main-thread gap ${Math.round(await page.evaluate(() => window.__gap))} ms`);
  await page.close();
}

// ---- C: busy twice, then fine ----
{
  let n = 0;
  fault = (key, req, res) => { if (key.endsWith('test-s1/base.pmtiles') && n < 2) { n++; res.writeHead(503).end(); return true; } return false; };
  const page = await open();
  check(await waitStrip(page, MSG_BUSY), 'C busy: grey line "Map server not reachable – trying again."');
  check(await waitBase(page, 40_000), 'C busy: tried again after the backoff – the cell draws');
  check(await page.waitForFunction((t) => !document.getElementById('mapnotes').innerText.includes(t), MSG_BUSY, { timeout: 10_000 }).then(() => true, () => false), 'C busy: the line goes when the map is back');
  await idle(page);
  check((await covered(page)) !== false, 'C busy: the cover is back on the cell');
  await page.close();
}

// ---- D: 429 always ----
{
  fault = (key, req, res) => { if (key.endsWith('test-s1/base.pmtiles')) { res.writeHead(429, { 'retry-after': '60' }).end('too many'); return true; } return false; };
  const since = Date.now();
  const page = await open();
  check(await waitStrip(page, MSG_BUSY), 'D 429: grey line "Map server not reachable – trying again."');
  await pan(page, 6);
  await page.waitForTimeout(Math.max(0, 30_000 - (Date.now() - since)));
  const n = count(/test-s1\/base\.pmtiles$/, since);
  check(n <= 2, `D 429: no request storm – base.pmtiles asked ${n}× in 30 s (the next try waits a minute)`);
  check((await covered(page)) !== true, 'D 429: the overview\'s cover is off the cell while it waits');
  await page.close();
}

// ---- E: new generation mid-session ----
{
  let switched = false;
  fault = (key, req, res) => {
    if (!switched) return false;
    if (key === CELL + 'current.json') { res.writeHead(200, { 'content-type': 'application/json' }).end('{"version":"test-s2"}'); return true; }
    if (key.startsWith(CELL + 'test-s1/')) { res.writeHead(404).end(); return true; }
    return false;
  };
  const page = await open();
  check(await waitBase(page), 'E generation: v1 draws first');
  switched = true;
  const since = Date.now();
  await page.evaluate(() => window.__map.jumpTo({ zoom: 14.2 }));
  await pan(page, 2);
  for (let i = 0; i < 60 && count(/test-s2\/base\.pmtiles$/, since) === 0; i++) await page.waitForTimeout(500);
  await idle(page);
  check(count(/test-s2\/base\.pmtiles$/, since) > 0 && (await baseDrawn(page)) > 0, 'E generation: current.json read again, the cell draws from the new version');
  check(count(new RegExp(CELL + 'current\\.json$'), since) <= 2, `E generation: current.json read again once (${count(new RegExp(CELL + 'current\\.json$'), since)})`);
  check(!(await strip(page)).includes(MSG_FAILED), 'E generation: no failure line');
  check(/new version test-s2/.test(await diag(page)), 'E generation: the switch is in the problem log');
  await page.close();
}

// ---- F: current.json busy for 8 s ----
{
  const until = Date.now() + 8_000;
  fault = (key, req, res) => { if (key === CELL + 'current.json' && Date.now() < until) { res.writeHead(503).end(); return true; } return false; };
  const since = Date.now();
  const page = await open();
  check(await waitStrip(page, MSG_BUSY), 'F current.json busy: grey line');
  check(await waitBase(page, 45_000), 'F current.json busy: the cell draws once it answers');
  const n = count(new RegExp(CELL + 'current\\.json$'), since);
  check(n <= 5, `F current.json busy: not asked on every tile (${n} reads)`);
  await page.close();
}

// ---- G: slow answers ----
{
  fault = (key, req, res) => {
    if (req.__late) return false;
    req.__late = true;
    setTimeout(() => server.emit('request', req, res), 2000);
    return true;
  };
  const page = await open();
  await page.evaluate(() => window.__map.jumpTo({ zoom: 13.5 }));
  check(await waitStrip(page, 'Loading the map', 10_000), 'G slow: "Loading the map for your area" while the tiles come');
  check(await waitBase(page, 45_000), 'G slow: the cell draws');
  const d = await diag(page);
  check(!/\[cells\]/.test(d), 'G slow: nothing failed (no cells problem)');
  await page.close();
}

// ---- H: diagnostics ----
{
  fault = () => false;
  const page = await open('&debug');
  check(await page.locator('#diagCopy').isVisible(), 'H ?debug: "Copy diagnostics" on the page');
  const d = await diag(page);
  check(/OnPath planner diagnostics/.test(d) && /cells: root/.test(d) && /problems \(/.test(d), 'H window.__diag(): page, map, cells and problems in the report');
  await page.close();
}

check(errors.length === 0, `no page errors (${errors.slice(0, 3).join(' | ')})`);
await browser.close();
server.close();
fs.rmSync(store, { recursive: true, force: true });
if (fails.length) { console.log(`${fails.length} check(s) failed`); process.exit(1); }
console.log('cells smoke ok');
