// Runs web/test/cases.json in headless Chromium (the Wasm engine) and compares with the JVM run of the same code
// (./gradlew -p web/engine runJvm → web/engine/build/jvm-results.json). Pass = same error text, or same length,
// ascent / descent, time and the same line (every point within 1 m, same count).
// Usage: node browser-compare.mjs [segmentsDir] [jvmResults]   (exit 1 on any difference)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const jvmFile = path.resolve(process.argv[3] ?? path.join(here, '../../engine/build/jvm-results.json'));
const dist = path.resolve(here, '../dist');
const { server, url } = await serve({ dist, segments, extra: { '/cases.json': path.join(here, '../../test/cases.json') } });

const exe = process.env.CHROMIUM_PATH || (fs.existsSync('/opt/pw-browsers/chromium') ? undefined : undefined);
const browser = await chromium.launch(exe ? { executablePath: exe } : {});
const page = await browser.newPage();
page.on('console', (m) => { if (m.type() === 'error') console.log('[browser]', m.text()); });
const t0 = Date.now();
await page.goto(url + 'compare.html');
await page.waitForFunction(() => window.__results, null, { timeout: 600_000, polling: 500 });
const web = await page.evaluate(() => window.__results);
const wallMs = Date.now() - t0;
await browser.close(); server.close();
fs.writeFileSync(path.join(here, '../../engine/build/browser-results.json'), JSON.stringify(web, null, 1));

const jvm = JSON.parse(fs.readFileSync(jvmFile, 'utf8'));
const metres = (a, b) => {
  const k = Math.PI / 180, x = (b[1] - a[1]) * k * Math.cos(((a[0] + b[0]) / 2) * k), y = (b[0] - a[0]) * k;
  return Math.sqrt(x * x + y * y) * 6371000;
};
// a "plan": true case (Engine.plan) has its line in legs; a route case in points
const line = (x) => x.points ?? (x.legs ?? []).flatMap((l) => l.points);
const cases = JSON.parse(fs.readFileSync(path.join(here, '../../test/cases.json'), 'utf8'));
let fails = 0;
const rows = [];
const visits = [];
web.cases.forEach((w, i) => {
  const j0 = jvm[i]; const r0 = w.result ?? { error: w.error };
  const j = j0.error ? j0 : { ...j0, points: line(j0) }; const r = r0.error ? r0 : { ...r0, points: line(r0) };
  // "visits": "lat,lon" = the line must pass this stop within 15 m (Bug 01: a stop on a way is always visited)
  if (cases[i]?.visits && !r.error) {
    const v = cases[i].visits.split(',').map(Number);
    const near = (pts) => Math.min(...pts.map((p) => metres(p, v)));
    const [wm, jm] = [near(r.points), near(j.points)];
    const ok = wm <= 15 && jm <= 15;
    visits.push(`${w.name}: browser ${r.distanceM} m, line ${wm.toFixed(1)} m from stop · JVM ${j.distanceM} m, ${jm.toFixed(1)} m${ok ? '' : ' – FAIL (must be ≤ 15 m)'}`);
    if (!ok) fails++;
  }
  let ok, note;
  if (j.error || r.error) { ok = j.error === r.error; note = ok ? `error: ${r.error}` : `JVM "${j.error}" vs web "${r.error}"`; }
  else {
    const dev = r.points.length === j.points.length ? Math.max(...r.points.map((p, k) => metres(p, j.points[k]))) : Infinity;
    ok = r.distanceM === j.distanceM && r.ascentM === j.ascentM && r.descentM === j.descentM && r.timeS === j.timeS && dev <= 1;
    note = `${(r.distanceM / 1000).toFixed(2)} km +${r.ascentM} m, ${r.points.length} pts, max dev ${dev.toFixed(2)} m` +
      (ok ? '' : ` | JVM ${j.distanceM} m +${j.ascentM} ${j.points.length} pts time ${j.timeS} vs ${r.timeS}`);
  }
  if (!ok) fails++;
  rows.push({ case: `${w.name} / ${w.activity}`, ok: ok ? 'same' : 'DIFF', jvmMs: j.ms, webRouteMs: Math.round(w.stats?.routeMs ?? 0),
    fetchMs: Math.round(w.stats?.fetchMs ?? 0), fetchedKB: Math.round((w.stats?.bytesFetched ?? 0) / 1024), note });
});
console.table(rows);
for (const v of visits) console.log('visits: ' + v);
// pace setting: every level's plan time as on the JVM (JvmRun.paceRun), a new plan at Very fit = planTime's, and the
// levels really change the time (Relaxed > Average > Very fit; an e-bike on the flat is capped, PaceModel)
const jvmPaceFile = path.join(path.dirname(jvmFile), 'jvm-pace.json');
if (!fs.existsSync(jvmPaceFile)) { console.log(`pace: FAIL – ${jvmPaceFile} missing (run ./gradlew -p web/engine runJvm)`); fails++; }
else {
  const jp = JSON.parse(fs.readFileSync(jvmPaceFile, 'utf8'));
  const same = JSON.stringify(jp) === JSON.stringify(web.pace);
  const order = web.pace.every((c) => c.levels.RELAXED.timeS > c.levels.AVERAGE.timeS && c.levels.AVERAGE.timeS >= c.levels.VERY_FIT.timeS && (c.activity === 'EBIKE' || c.levels.AVERAGE.timeS > c.levels.VERY_FIT.timeS) &&
    c.planVeryFitS === c.levels.VERY_FIT.timeS);
  console.log(`pace: ${web.pace.length} cases × 5 levels ${same ? 'same as the JVM' : 'DIFF'}${order ? '' : ', levels do not order the times'} – ` +
    web.pace.map((c) => `${c.activity} ${c.levels.RELAXED.time} / ${c.levels.AVERAGE.time} / ${c.levels.VERY_FIT.time}`).join(' · '));
  if (!same) console.log('JVM', JSON.stringify(jp), '\nweb', JSON.stringify(web.pace));
  if (!same || !order || web.pace.length < 3) fails++;
}
const wasm = fs.statSync(path.join(dist, 'engine/onpath-web-engine.wasm')).size;
console.log(`engine load ${Math.round(web.engineLoadMs)} ms, wasm ${(wasm / 1e6).toFixed(2)} MB, whole run ${wallMs} ms; ${web.cases.length - fails}/${web.cases.length} same`);
fs.writeFileSync(path.join(here, '../../engine/build/compare-summary.json'), JSON.stringify({ rows, engineLoadMs: web.engineLoadMs, wasmBytes: wasm, wallMs }, null, 1));
process.exit(fails ? 1 : 0);
