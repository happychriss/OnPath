// How smooth the terrain view stays (owner 2026-11-06: "3D + großes Höhenprofil – Performance-Probleme?").
// Plans Meran → Schenna, then measures frames and main-thread time while the pointer sweeps the big profile / the map
// for 5 s – flat vs 3D, with and without the big profile, and the flyover. CPU throttled 4× (a modest laptop).
// Usage: [PERF_GPU=1] [PERF_PROFILE=<part of a name>] node test/perf-terrain.mjs segmentsDir [mapStoreDir] [throttle=4]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2]);
const dataDir = process.argv[3] ? path.resolve(process.argv[3]) : null;
const throttle = Number(process.argv[4] ?? 4);
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
// the machine's GPU when PERF_GPU=1 (a person's browser), else SwiftShader like the smokes (CPU drawing: 3D far slower)
const browser = await chromium.launch({ args: process.env.PERF_GPU ? ['--use-gl=angle', '--use-angle=gl', '--ignore-gpu-blocklist']
  : ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
await page.goto(url + 'index.html');
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
const clickAt = async (lon, lat) => {
  const p = await page.evaluate(([lo, la]) => { const r = window.__map.getCanvas().getBoundingClientRect();
    const q = window.__map.project([lo, la]); return [q.x + r.left, q.y + r.top]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1]);
};
await clickAt(11.1600, 46.6700);
await page.waitForSelector('.pointmenu');
await page.click('.pointmenu button:first-of-type');
await page.waitForTimeout(300);
await clickAt(11.1900, 46.6900);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
await page.waitForTimeout(1500);
console.log('route vertices:', await page.evaluate(() => window.__state.plan.legs.reduce((n, l) => n + (l.points?.length ?? l.length ?? 0), 0)));

const cdp = await page.context().newCDPSession(page);
await cdp.send('Performance.enable');
await cdp.send('Emulation.setCPUThrottlingRate', { rate: throttle });
const metric = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((m) => [m.name, m.value]));

/** Sweeps the pointer over [box] for [ms] while counting frames in the page. */
async function measure(name, box, ms = 5000) {
  const prof = process.env.PERF_PROFILE && name.includes(process.env.PERF_PROFILE);
  if (prof) { await cdp.send('Profiler.enable'); await cdp.send('Profiler.setSamplingInterval', { interval: 200 }); await cdp.send('Profiler.start'); }
  await page.evaluate(() => { window.__frames = []; let last = performance.now();
    const tick = (t) => { window.__frames.push(t - last); last = t; if (window.__frames.length < 100000 && !window.__stop) requestAnimationFrame(tick); };
    window.__stop = false; requestAnimationFrame(tick); });
  const m0 = await metric();
  const t0 = Date.now();
  let dir = 1, i = 0;
  while (Date.now() - t0 < ms) {
    const f = (i % 50) / 50; const x = box.x + box.width * (dir > 0 ? 0.05 + 0.9 * f : 0.95 - 0.9 * f);
    await page.mouse.move(x, box.y + box.height * 0.5);
    i++; if (i % 50 === 0) dir = -dir;
  }
  const m1 = await metric();
  if (prof) { const { profile } = await cdp.send('Profiler.stop'); const self = new Map(); const byId = new Map(profile.nodes.map((n) => [n.id, n]));
    const dt = profile.timeDeltas; const counts = new Map(); profile.samples.forEach((id, k) => counts.set(id, (counts.get(id) ?? 0) + (dt[k] ?? 0)));
    for (const [id, us] of counts) { const n = byId.get(id); const f = n.callFrame; const key = `${f.functionName || '(anon)'} ${path.basename(f.url)}:${f.lineNumber}`; self.set(key, (self.get(key) ?? 0) + us); }
    console.log('PROFILE ' + name); [...self].sort((a, b) => b[1] - a[1]).slice(0, 25).forEach(([k, us]) => console.log(`  ${(us / 1000).toFixed(0).padStart(6)} ms  ${k}`)); }
  const fr = await page.evaluate(() => { window.__stop = true; return window.__frames.slice(1); });
  fr.sort((a, b) => a - b);
  const secs = (Date.now() - t0) / 1000;
  const r = { name, moves: i, fps: +(fr.length / secs).toFixed(1), p50: +fr[Math.floor(fr.length * 0.5)]?.toFixed(1),
    p95: +fr[Math.floor(fr.length * 0.95)]?.toFixed(1), max: +Math.max(...fr).toFixed(0),
    taskMsPerS: +(((m1.TaskDuration - m0.TaskDuration) * 1000) / secs).toFixed(0),
    scriptMsPerS: +(((m1.ScriptDuration - m0.ScriptDuration) * 1000) / secs).toFixed(0) };
  console.log(JSON.stringify(r));
  return r;
}
const settle = async () => { await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 30_000 }).catch(() => {}); await page.waitForTimeout(1500); };
const mapBox = async () => { const r = await page.locator('#map').boundingBox(); return { x: r.x + r.width * 0.2, y: r.y + r.height * 0.2, width: r.width * 0.6, height: r.height * 0.4 }; };
const routeSweep = async () => { // across the route on the map (hover → the cursor)
  const pts = await page.evaluate(() => { const r = window.__map.getCanvas().getBoundingClientRect();
    const a = window.__map.project([11.16, 46.67]), b = window.__map.project([11.19, 46.69]); return [a.x + r.left, a.y + r.top, b.x + r.left, b.y + r.top]; });
  return { x: Math.min(pts[0], pts[2]), y: (pts[1] + pts[3]) / 2, width: Math.abs(pts[2] - pts[0]), height: 1 };
};

const out = [];
await settle();
out.push(await measure('flat · idle pointer over map (no route)', await mapBox()));
out.push(await measure('flat · pointer along the route on the map', await routeSweep()));
if (await page.isVisible('#profileBig')) await page.click('#profileBig'); await settle();
const big = async () => page.locator('#bigProfile .profplot > svg').boundingBox();
out.push(await measure('flat · big profile sweep', await big()));
if (await page.isVisible('#bigProfile .bpclose')) await page.click('#bigProfile .bpclose'); await settle();
await page.click('#terrain3d'); await settle(); await page.waitForTimeout(3000);
out.push(await measure('3D · idle pointer over map', await mapBox()));
out.push(await measure('3D · pointer along the route on the map', await routeSweep()));
if (await page.isVisible('#profileBig')) await page.click('#profileBig'); await settle();
out.push(await measure('3D · big profile sweep', await big()));
if (await page.isVisible('#bigProfile .bpclose')) await page.click('#bigProfile .bpclose'); await settle();
if (await page.locator('#flyStart').count()) {
  await page.click('#flyStart'); await page.waitForTimeout(500);
  out.push(await measure('3D · flyover (pointer still)', { x: 5, y: 790, width: 1, height: 1 }));
  await page.locator('#flyStop').click().catch(() => {});
}
console.log('SUMMARY ' + JSON.stringify(out));
await browser.close(); server.close();
