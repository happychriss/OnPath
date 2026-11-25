// The planner under the host's security headers (public/_headers – the file Cloudflare sends with every page): the built
// page, the engine worker (Wasm), MapLibre's worker, map cells, routing, place search (Photon stubbed) and the live
// viewer page run with exactly that Content-Security-Policy; any `securitypolicyviolation` event or console line about
// the policy fails the test. Also: the file has the policy and the other headers at all, and the policy really blocks a
// foreign host (the test would otherwise prove nothing).
// Usage: node test/csp-smoke.mjs [segmentsDir] [cellStoreDir] [dist]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const cellArg = path.resolve(process.argv[3] ?? '/home/user/build/cell/store');
const dataDir = fs.existsSync(cellArg) ? cellArg : null;
const dist = path.resolve(process.argv[4] ?? path.join(here, '../dist'));
const A = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (PlanCodeTest)
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };

/** The headers of the `/*` block of a Cloudflare `_headers` file ({} when the file is missing or has none). */
export function readHeaders(file) {
  if (!fs.existsSync(file)) return {};
  const out = {};
  let inStar = false;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    if (/^\s*#/.test(line) || !line.trim()) continue;
    if (!/^\s/.test(line)) { inStar = line.trim() === '/*'; continue; }
    const m = /^\s+([A-Za-z-]+):\s*(.*)$/.exec(line);
    if (inStar && m) out[m[1]] = m[2].trim();
  }
  return out;
}

const headers = readHeaders(path.join(here, '../public/_headers'));
const csp = headers['Content-Security-Policy'] ?? '';
check(/default-src 'self'/.test(csp) && /frame-ancestors 'none'/.test(csp) && /object-src 'none'/.test(csp),
  "public/_headers has a Content-Security-Policy (default-src self, frame-ancestors none)");
check(headers['X-Content-Type-Options'] === 'nosniff', 'X-Content-Type-Options: nosniff');
check(headers['Referrer-Policy'] === 'strict-origin-when-cross-origin', 'Referrer-Policy: strict-origin-when-cross-origin');
check(headers['X-Frame-Options'] === 'DENY', 'X-Frame-Options: DENY');
check(/geolocation=\(self\)/.test(headers['Permissions-Policy'] ?? ''), 'Permissions-Policy limits geolocation to self');
if (fails.length) { console.log('\nFAILED:', fails); process.exit(1); }

const MUC = fs.readFileSync(path.join(here, 'fixtures/photon-muenchen.json'), 'utf8');
const { server, url } = await serve({ dist, segments, dataDir, headers });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, ignoreHTTPSErrors: true });
await ctx.route('https://photon.komoot.io/**', (r) => r.fulfill({ status: 200, contentType: 'application/json',
  headers: { 'access-control-allow-origin': '*' }, body: MUC }));
await ctx.addInitScript(() => {
  window.__csp = [];
  document.addEventListener('securitypolicyviolation', (e) => window.__csp.push(`${e.violatedDirective} ${e.blockedURI}`));
});
const problems = []; // CSP lines of the console (workers report there, not on the page's event)
const watch = (page) => {
  page.on('console', (m) => { if (/Content Security Policy|Refused to (load|connect|execute|create|apply|evaluate)/i.test(m.text())) problems.push(m.text()); });
  page.on('pageerror', (e) => { if (/Content Security Policy|unsafe-eval/i.test(String(e))) problems.push(String(e)); });
};
const violations = (page) => page.evaluate(() => window.__csp);
try {
  const page = await ctx.newPage();
  watch(page);
  const resp = await page.goto(url + 'index.html#p=' + A);
  check((resp.headers()['content-security-policy'] ?? '') === csp, 'the served page carries the policy');
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
  const done = await page.waitForFunction(() => /\d/.test(document.querySelector('#summary .big')?.textContent ?? '') && document.body.dataset.planning !== '1',
    null, { timeout: 120_000 }).then(() => true, () => false);
  check(done, 'a route is planned (engine in its worker, routing data from /segments/)');
  const drawn = await page.waitForSelector('canvas.maplibregl-canvas', { timeout: 30_000 }).then(() => true, () => false);
  check(drawn, 'the map canvas is there (MapLibre worker, style, sprites, fonts)');
  await page.waitForTimeout(3000);
  // place search (Photon, stubbed): needs connect-src to photon.komoot.io
  await page.fill('#q', 'München');
  const hit = await page.waitForSelector('#results .hit', { timeout: 30_000 }).then(() => true, () => false);
  await page.waitForTimeout(1500);
  check(hit, 'place search shows hits (Photon allowed by connect-src)');
  // the policy really is in force: a foreign host is refused
  const foreign = await page.evaluate(() => fetch('https://example.com/x').then(() => 'loaded', () => 'blocked'));
  check(foreign === 'blocked', 'a foreign host is blocked by the policy');
  const seen = (await violations(page)).filter((v) => !/example\.com/.test(v));
  check(!seen.length, 'no securitypolicyviolation in the planner' + (seen.length ? ': ' + seen.join(' | ') : ''));

  // the live tour's viewer page (same host, own engine + MapLibre)
  const live = await ctx.newPage();
  watch(live);
  await live.goto(url + 'live/');
  await live.waitForFunction(() => !!window.__live || document.querySelector('#live')?.textContent?.trim(), null, { timeout: 60_000 }).catch(() => {});
  await live.waitForTimeout(3000);
  const lseen = await violations(live);
  check(!lseen.length, 'no securitypolicyviolation on the live page' + (lseen.length ? ': ' + lseen.join(' | ') : ''));
  const real = problems.filter((p) => !/example\.com/.test(p));
  check(!real.length, 'no CSP line in the consoles' + (real.length ? ': ' + real.join(' | ') : ''));
} catch (e) {
  check(false, 'smoke crashed: ' + e);
}
await browser.close();
server.close();
if (fails.length) { console.log('\nFAILED:', fails); process.exit(1); }
console.log('\nall ok');
