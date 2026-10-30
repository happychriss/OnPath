// Test page for web/test/browser-compare.mjs: runs every case of cases.json in the real browser engine and puts the
// results on window.__results (one per case, same JSON as the JVM side).
import { EngineClient } from './engine/client';
import type { Activity } from './engine/types';

const params = new URLSearchParams(location.search);
const engine = new EngineClient();
const out: unknown[] = [];
(async () => {
  const cfg = await engine.configure(new URL('./', location.href).href, params.get('segments') ?? new URL('segments/', location.href).href);
  const cases: { name: string; points: string; activity: Activity; sac: number }[] = await (await fetch(params.get('cases') ?? 'cases.json')).json();
  for (const c of cases) {
    const pts = c.points.split(';').map((p) => p.split(',').map(Number) as [number, number]);
    const r = await engine.route(pts, c.activity, c.sac);
    out.push({ name: c.name, activity: c.activity, ...r });
    document.body.insertAdjacentText('beforeend', `${c.name} ${c.activity}: ${r.result?.error ?? r.result?.distanceM ?? r.error}\n`);
  }
  // pace setting (WebPace): the same runs as the JVM's paceRun (JvmRun.kt) – Hike, a bike, an e-bike
  const pace: unknown[] = [];
  const levels = ['RELAXED', 'EASY', 'AVERAGE', 'FIT', 'VERY_FIT'];
  for (const a of ['HIKE', 'BIKE_TREKKING', 'EBIKE'] as Activity[]) {
    const c = cases.find((k) => k.activity === a);
    if (!c) continue;
    const pts = c.points.split(';').map((p) => p.split(',').map(Number) as [number, number]);
    await engine.call('paceSet', 'AVERAGE', 'AVERAGE');
    await engine.plan(pts, a, c.sac);
    const byLevel: Record<string, unknown> = {};
    for (const l of levels) {
      await engine.call('paceSet', l, l);
      byLevel[l] = JSON.parse(await engine.call('planTime', c.points, a, c.sac));
    }
    const fit = await engine.plan(pts, a, c.sac);
    await engine.call('paceSet', 'AVERAGE', 'AVERAGE');
    pace.push({ name: c.name, activity: a, levels: byLevel, planVeryFitS: fit.plan?.timeS ?? null });
  }
  (window as unknown as { __results: unknown }).__results = { engineLoadMs: cfg.stats?.engineLoadMs, cases: out, pace };
})();
