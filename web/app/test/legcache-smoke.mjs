// The planner keeps finished legs in the browser (owner 2026-11-18: München–Venedig opened again routed every leg for
// minutes; WebLegStore.kt + legstore.ts, IndexedDB). A multi-leg plan opened from a link routes its legs; the page reloaded
// opens it with no router run and the very same plan as a fresh browser routes; new routing data (a newer rd5 build) or
// other profiles (a new routing fingerprint) route every leg again.
// Usage: node test/legcache-smoke.mjs [segmentsDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

// Meraner Höhenweg, Hike T3, 12 points (WebPlanTotalsTest)
const CODE = 'AQUDDPiLugSE8YcBogbZH94K8yedLO898Q7VHK0ZuUWwL99M6GK6N7AHsFyWPLJgDOyUAauSAdlUAAABEU1lcmFuZXIgSMO2aGVud2VnAhEAR2FzdGhvZiBIb2NobXV0aAIKAUxlaXRlcmFsbQINAkhvY2hnYW5naGF1cwILA0dpZ2dlbGJlcmcCCgRIb2NoZm9yY2gCCwVVbnRlcnN0ZWxsAg4GS2F0aGFyaW5hYmVyZwIHB0Vpc2hvZgIRCFN0ZXR0aW5lciBIw7x0dGUCCQlQZmVsZGVycwIGClVsZmFzAhELR2FzdGhvZiBIb2NobXV0aA';
const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const TILE = 'E10_N45.rd5';
if (!fs.existsSync(path.join(segments, TILE))) { console.log(`skip: no ${TILE} in ${segments}`); process.exit(0); }

// faults on demand: other profiles (a comment added to hiking-mountain.brf), a newer build of the tile (its tail's
// creation time + 1 ms – what the engine reads as the routing data's version)
const fault = { profile: false, newerData: false };
const fd = fs.openSync(path.join(segments, TILE), 'r');
const index = Buffer.alloc(200); fs.readSync(fd, index, 0, 200, 0); fs.closeSync(fd);
const tailStart = Number(index.readBigUInt64BE(24 * 8) & 0xffffffffffffn);
const intercept = (req, res) => {
  const url = new URL(req.url, 'http://x').pathname;
  if (fault.profile && url.endsWith('/brouter/hiking-mountain.brf')) {
    const f = path.resolve(here, '../dist/brouter/hiking-mountain.brf');
    res.writeHead(200, { 'Content-Type': 'application/octet-stream' }).end(Buffer.concat([fs.readFileSync(f), Buffer.from('\n# changed\n')]));
    return true;
  }
  const m = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range ?? '');
  if (fault.newerData && url.endsWith('/' + TILE) && m && Number(m[1]) === tailStart) {
    const all = fs.readFileSync(path.join(segments, TILE));
    const tail = Buffer.from(all.subarray(tailStart));
    tail.writeBigInt64BE(tail.readBigInt64BE(0) + 1n, 0);
    res.writeHead(206, { 'Content-Type': 'application/octet-stream', 'Content-Range': `bytes ${tailStart}-${all.length - 1}/${all.length}`, 'Content-Length': tail.length }).end(tail);
    return true;
  }
  return false;
};
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, mapDir: null, intercept });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
const watch = (page) => {
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
};

/** The page's plan once routed: the engine's stats and the plan (without timings), the address. */
async function planned(page) {
  await page.waitForFunction(() => window.__engine?.lastPlanStats?.legs && document.querySelector('#summary .big'), null, { timeout: 180_000 });
  await page.waitForTimeout(1500); // snapped points into the address, the legs into IndexedDB
  return page.evaluate(() => {
    const strip = (o) => JSON.parse(JSON.stringify(o, (k, v) => (k === 'ms' ? undefined : v)));
    return { stats: window.__engine.lastPlanStats, plan: strip(window.__engine.lastPlan), hash: location.hash, wall: performance.now() };
  });
}
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const nums = (p) => `${(p.distanceM / 1000).toFixed(2)} km ↑${Math.round(p.ascentM)} ↓${Math.round(p.descentM)}`;

const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const page = await ctx.newPage(); watch(page);

// 1. the plan from a link: every leg routed
let t0 = Date.now();
await page.goto(url + 'index.html#p=' + CODE);
const first = await planned(page);
const firstMs = Date.now() - t0;
const n = first.stats.legs.routed + first.stats.legs.cached;
check(n >= 10 && first.stats.legs.routed === n, `opened from a link: all ${n} legs routed (${JSON.stringify(first.stats.legs)}), ${firstMs} ms`);

// 2. reload: the legs come from the browser – no router run, and the address's (snapped) points find them
t0 = Date.now();
await page.reload();
const again = await planned(page);
const againMs = Date.now() - t0;
check(again.stats.legs.routed === 0 && again.stats.legs.stored === n,
  `reload: no leg routed, ${again.stats.legs.stored} of ${n} from the browser (read ${again.stats.legs.read} in ${again.stats.legs.readMs} ms); plan ${Math.round(again.stats.routeMs)} ms (routed: ${Math.round(first.stats.routeMs)} ms), download ${Math.round(again.stats.fetchMs)} ms, page ${againMs} ms`);

// 3. the same address in a fresh browser (nothing stored): the very same plan – numbers, line, way types, everything
const fresh = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const fp = await fresh.newPage(); watch(fp);
await fp.goto(url + 'index.html' + again.hash);
const routed = await planned(fp);
check(routed.stats.legs.routed === n, `a fresh browser routes all ${n} legs of the same address`);
check(same(routed.plan, again.plan), `stored legs give the same plan as routed ones (${nums(again.plan)} vs ${nums(routed.plan)}, ${again.plan.legs?.reduce((s, l) => s + l.points.length, 0)} line points)`);
await fresh.close();

// 4. a newer build of the routing data under the legs: every leg routed again (then stored afresh)
fault.newerData = true;
await page.reload();
const newer = await planned(page);
check(newer.stats.legs.routed === n && newer.stats.legs.stored === 0, `newer routing data: all legs routed again (${JSON.stringify(newer.stats.legs)})`);
check(same(newer.plan, again.plan), 'the same data content: the same plan');
await page.reload();
const newer2 = await planned(page);
check(newer2.stats.legs.routed === 0, 'then kept for the newer data: a reload routes none');

// 5. other profiles (a new routing fingerprint): every leg routed again
fault.profile = true;
await page.reload();
const prof = await planned(page);
check(prof.stats.legs.routed === n && prof.stats.legs.stored === 0, `other profiles: all legs routed again (${JSON.stringify(prof.stats.legs)})`);

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
