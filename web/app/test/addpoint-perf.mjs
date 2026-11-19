// Measurement only (no checks): where the time of one „add a point" goes in a long multi-day plan in the browser.
// München → Brenner → Venedig (a 6-day tour, opened from a link), then a click 2 km beside the middle of the long leg
// (Brenner → Venedig, ~320 km). Timeline from the page's worker messages (every request / reply / progress, tapped at
// Worker.postMessage), the plan's own stats (download, rd5 write, engine), each routed leg's time from the progress
// replies, the page's long tasks, the IndexedDB write of the new legs, and the CPU time of all browser processes.
// Each rep a fresh browser context (empty IndexedDB, cold worker). AddPointPerfTest.kt is the JVM side.
// Usage: node test/addpoint-perf.mjs [segmentsDir] [acts=BIKE_TOURING,HIKE] [reps=3] [offM=2000]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const acts = (process.argv[3] ?? 'BIKE_TOURING,HIKE').split(',');
const reps = Number(process.argv[4] ?? 3);
const offM = Number(process.argv[5] ?? 2000);
const BASE = [[48.1374, 11.5755], [47.0030, 11.5060], [45.4380, 12.3190]];

/** CPU seconds (user + system) of every process below this one (the browser and its renderers / GPU / utility). */
function childCpu() {
  const ticks = Number(process.env.CLK_TCK ?? 100);
  const all = fs.readdirSync('/proc').filter((d) => /^\d+$/.test(d)).map((d) => {
    try { const s = fs.readFileSync(`/proc/${d}/stat`, 'utf8'); const f = s.slice(s.lastIndexOf(')') + 2).split(' ');
      return { pid: Number(d), ppid: Number(f[1]), cpu: (Number(f[11]) + Number(f[12])) / ticks }; } catch { return null; }
  }).filter(Boolean);
  const mine = new Set([process.pid]);
  for (let grew = true; grew;) { grew = false; for (const p of all) if (!mine.has(p.pid) && mine.has(p.ppid)) { mine.add(p.pid); grew = true; } }
  return all.filter((p) => p.pid !== process.pid && mine.has(p.pid)).reduce((s, p) => s + p.cpu, 0);
}
const load = () => fs.readFileSync('/proc/loadavg', 'utf8').split(' ').slice(0, 3).join(' ');

const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });

const TAP = () => {
  const log = (window.__wlog = []);
  const post = Worker.prototype.postMessage;
  Worker.prototype.postMessage = function (m, ...r) {
    try { log.push({ t: performance.now(), dir: 'out', id: m?.id, type: m?.type, fn: m?.fn }); } catch { /* expected: a message without fields */ }
    return post.call(this, m, ...r);
  };
  const W = window.Worker;
  window.Worker = class extends W {
    constructor(...a) {
      super(...a);
      log.push({ t: performance.now(), dir: 'spawn' });
      this.addEventListener('message', (e) => { const d = e.data;
        log.push({ t: performance.now(), dir: 'in', id: d?.id, ok: d?.ok, progress: d?.progress ? JSON.stringify(d.progress) : undefined,
          stats: d?.stats ? JSON.stringify(d.stats) : undefined, planMs: d?.plan?.ms, legs: d?.plan?.legs?.length }); });
    }
  };
  window.__long = [];
  try { new PerformanceObserver((l) => window.__long.push(...l.getEntries().map((e) => ({ t: e.startTime, d: e.duration })))).observe({ entryTypes: ['longtask'] }); }
  catch { /* expected: no long task timing */ }
};

async function legCount(page) {
  return page.evaluate(() => new Promise((res) => { const r = indexedDB.open('onpath-legs');
    r.onsuccess = () => { try { const q = r.result.transaction('legs').objectStore('legs').count(); q.onsuccess = () => { res(q.result); r.result.close(); }; q.onerror = () => res(-1); } catch { res(-1); } };
    r.onerror = () => res(-1); }));
}

const settled = (page) => page.waitForFunction(() => document.body.dataset.planning !== '1' && !!document.querySelector('#summary .big')
  && window.__engine?.lastPlanStats, null, { timeout: 1_800_000 });

const rows = [];
for (const act of acts) for (let rep = 0; rep < reps; rep++) {
  const sac = act === 'HIKE' ? 2 : 0;
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'en-US' });
  await ctx.route('https://**', (r) => r.fulfill({ status: 404, body: '' })); // nothing from outside (fonts, Photon)
  await ctx.addInitScript(TAP);
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log('pageerror', String(e)));
  await page.goto(url + 'index.html?lang=en&waitMs=0');
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 300_000 });
  const code = await page.evaluate(([pts, a, s]) => window.__engine.encode({ points: pts, activity: a, sac: s, pinned: [], names: {}, title: 'München – Venedig',
    tour: { days: 6, limits: { timeMin: null, ascentM: null }, nights: {}, description: null } }), [BASE, act, sac]);
  // the base plan from its link: download, write, every leg routed
  let c0 = childCpu(), w0 = Date.now();
  await page.goto(url + 'index.html?lang=en&waitMs=0#p=' + code);
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 300_000 });
  await settled(page);
  const baseStats = await page.evaluate(() => ({ s: window.__engine.lastPlanStats, km: window.__engine.lastPlan.distanceM / 1000, err: window.__engine.lastPlan.error }));
  const baseRow = { act, rep, step: 'base plan (link)', wallMs: Date.now() - w0, cpuS: +(childCpu() - c0).toFixed(1), load: load(),
    fetchMs: Math.round(baseStats.s.fetchMs), storeMs: Math.round(baseStats.s.storeMs), engineMs: Math.round(baseStats.s.routeMs), MB: +(baseStats.s.bytesFetched / 1e6).toFixed(1),
    km: +baseStats.km.toFixed(1), legs: JSON.stringify(baseStats.s.legs) };
  console.log(JSON.stringify(baseRow)); rows.push(baseRow);
  await page.waitForTimeout(3000); // the legs into IndexedDB, the page calm
  const idbBefore = await legCount(page);

  // the spot: 2 km left of the long leg's middle (+3 km along per rep, as AddPointPerfTest)
  const spot = await page.evaluate(([rep, off]) => {
    const leg = window.__engine.lastPlan.legs[1].points;
    const m = (a, b) => { const r = Math.PI / 180, x = (b[1] - a[1]) * r * Math.cos(((a[0] + b[0]) / 2) * r), y = (b[0] - a[0]) * r; return Math.sqrt(x * x + y * y) * 6371000; };
    let total = 0; for (let i = 1; i < leg.length; i++) total += m(leg[i - 1], leg[i]);
    const want = total / 2 + rep * 3000; let s = 0, i = 1;
    for (; i < leg.length; i++) { const d = m(leg[i - 1], leg[i]); if (s + d >= want) break; s += d; }
    const a = leg[Math.max(0, i - 20)], b = leg[Math.min(leg.length - 1, i + 20)], p = leg[i];
    const k = Math.cos(p[0] * Math.PI / 180), dx = (b[1] - a[1]) * k, dy = b[0] - a[0], n = Math.hypot(dx, dy);
    return { lat: p[0] + (dx / n) * off / 111320, lon: p[1] + (-dy / n) * off / (111320 * k), legKm: total / 1000 };
  }, [rep, offM]);
  await page.evaluate(([la, lo]) => window.__map.jumpTo({ center: [lo, la], zoom: 11 }), [spot.lat, spot.lon]);
  await page.waitForTimeout(2500);
  // small call round trip (page → worker → page)
  const rt = await page.evaluate(async () => { const p = window.__state.points; const s = performance.now();
    for (let i = 0; i < 10; i++) await window.__engine.rows(p, ''); return (performance.now() - s) / 10; });
  const mark = await page.evaluate(() => { window.__long.length = 0; return { n: window.__wlog.length, t: performance.now() }; });
  c0 = childCpu(); w0 = Date.now();
  const xy = await page.evaluate(([lo, la]) => { const r = window.__map.getCanvas().getBoundingClientRect(); const q = window.__map.project([lo, la]); return [q.x + r.left, q.y + r.top]; }, [spot.lon, spot.lat]);
  await page.mouse.click(xy[0], xy[1]);
  // the hold answer: at once a stop, or the small menu (then „Add as stop“)
  const asked = await Promise.race([
    page.waitForSelector('.holdaddpopup button', { timeout: 60_000 }).then(() => true),
    page.waitForFunction(() => document.body.dataset.planning === '1', null, { timeout: 60_000 }).then(() => false),
  ]);
  let menuAt = null;
  if (asked) {
    menuAt = await page.evaluate(() => performance.now());
    const btns = page.locator('.holdaddpopup button');
    const texts = await btns.allInnerTexts();
    const k = texts.findIndex((x) => /stop|insert|einf/i.test(x));
    await btns.nth(k < 0 ? 0 : k).click();
  }
  await page.waitForFunction((n) => window.__wlog.length > n && document.body.dataset.planning === '1', mark.n, { timeout: 60_000 }).catch(() => {});
  await settled(page);
  const settledAt = await page.evaluate(() => new Promise((res) => requestAnimationFrame(() => requestAnimationFrame(() => res(performance.now())))));
  const wallMs = Date.now() - w0, cpuS = childCpu() - c0;
  // the new legs in IndexedDB
  const tIdb0 = Date.now(); let idbAfter = idbBefore;
  while (Date.now() - tIdb0 < 30_000) { idbAfter = await legCount(page); if (idbAfter >= idbBefore + 2) break; await page.waitForTimeout(100); }
  const idbMs = Date.now() - tIdb0;
  await page.waitForTimeout(1500);
  const r = await page.evaluate(([m, settledAt, menuAt]) => {
    const log = window.__wlog.slice(m.n).map((e) => ({ ...e, t: e.t - m.t }));
    const reqs = new Map();
    for (const e of log) {
      if (e.dir === 'out') reqs.set(e.id, { what: e.fn ?? e.type, sent: e.t, prog: [] });
      else if (e.dir === 'in' && reqs.has(e.id)) { const q = reqs.get(e.id); if (e.progress) q.prog.push([Math.round(e.t), e.progress]); else { q.done = e.t; q.stats = e.stats; q.planMs = e.planMs; } }
    }
    const spawns = log.filter((e) => e.dir === 'spawn').length;
    const list = [...reqs.values()].map((q) => ({ what: q.what, at: Math.round(q.sent), ms: q.done == null ? null : Math.round(q.done - q.sent),
      planMs: q.planMs, stats: q.stats ? JSON.parse(q.stats) : undefined, prog: q.prog.length ? q.prog : undefined }));
    const long = window.__long.filter((l) => l.t >= m.t);
    return { list, spawns, settled: Math.round(settledAt - m.t), menuAt: menuAt == null ? null : Math.round(menuAt - m.t),
      longN: long.length, longMs: Math.round(long.reduce((s, l) => s + l.d, 0)), longMax: Math.round(Math.max(0, ...long.map((l) => l.d))),
      legs: window.__engine.lastPlanStats?.legs, km: window.__engine.lastPlan.distanceM / 1000, err: window.__engine.lastPlan.error,
      planJsonKB: Math.round(JSON.stringify(window.__engine.lastPlan).length / 1024) };
  }, [mark, settledAt, menuAt]);
  const plan = r.list.find((q) => q.what === 'plan' && q.stats);
  const hold = r.list.filter((q) => q.what === 'holdOnMap');
  // each routed leg: from the „route" phase (or the previous leg's end) to the progress reply that counts it done
  const legTimes = [];
  if (plan?.prog) { let last = null; for (const [t, p] of plan.prog) { const j = JSON.parse(p); if (j.phase === 'route' || (j.phase === 'legs' && j.done === 0)) last = t; else if (j.phase === 'legs' && last != null) { legTimes.push(t - last); last = t; } } }
  const planEnd = plan ? plan.at + plan.ms : 0;
  const after = r.list.filter((q) => q.at >= planEnd - 1 && q.what !== 'plan');
  const row = { act, rep, step: `add point ${offM / 1000} km off (leg ${spot.legKm.toFixed(0)} km)`, wallMs, cpuS: +cpuS.toFixed(1), load: load(),
    rtMs: +rt.toFixed(1), holdMs: hold.map((q) => q.ms).join('+'), menuAt: r.menuAt, planSentAt: plan?.at, planReplyMs: plan?.ms,
    fetchMs: plan && Math.round(plan.stats.fetchMs), storeMs: plan && Math.round(plan.stats.storeMs), engineMs: plan && Math.round(plan.stats.routeMs),
    engineJsonMs: plan?.planMs, legIdbReadMs: plan?.stats.legs?.readMs, MB: plan && +(plan.stats.bytesFetched / 1e6).toFixed(1), legTimes: legTimes.join('+'),
    afterPlan: after.map((q) => `${q.what}:${q.ms}`).join(' '), settledMs: r.settled, redrawMs: plan ? r.settled - planEnd : null,
    idbWriteMs: idbMs, idbLegs: `${idbBefore}→${idbAfter}`, longTasks: `${r.longN}/${r.longMs}ms max ${r.longMax}`, spawns: r.spawns,
    km: +r.km.toFixed(1), legs: JSON.stringify(r.legs), planKB: r.planJsonKB, err: r.err };
  console.log(JSON.stringify(row)); rows.push(row);
  if (process.env.DUMP) console.log(JSON.stringify(r.list));
  await ctx.close();
}
console.table(rows.map(({ act, rep, step, wallMs, cpuS, fetchMs, storeMs, engineMs, legTimes, redrawMs, holdMs }) => ({ act, rep, step, wallMs, cpuS, fetchMs, storeMs, engineMs, legTimes, redrawMs, holdMs })));
await browser.close(); server.close();
