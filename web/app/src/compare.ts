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
  (window as unknown as { __results: unknown }).__results = { engineLoadMs: cfg.stats?.engineLoadMs, cases: out };
})();
