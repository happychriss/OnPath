// The route's shape in Meine Touren (owner 2026-11-07, the app's RoutePreview / RouteShape): a small preview tile on every
// row (the planner has no large overview, owner 2026-11-13). The sync record holds only the plan code (points) – no line – so, like the
// numbers (planstats.ts), the page keeps a route's line in this browser whenever it has it anyway: a plan routed here, an
// imported or opened track. Kept small: at most [STORE_POINTS] points, each as 12 + 12 bits inside the line's box. Never
// routes anything for the list: a route this browser has not routed yet shows the kind's sign instead (the app's
// placeholder while a line loads).
import { slice } from './slices';

export type Seg = [number, number][];
/** Fitted into a box: x/y pairs per segment, the start and the goal (pixels). */
export interface Shape { lines: number[][]; sx: number; sy: number; ex: number; ey: number }

/**
 * Points kept per route in this browser's storage (the page's own storage format, not a drawing rule: the drawing – how
 * many points, the fit, when it is one spot – is the app's RouteShape.of, run in the engine, [fitShape]).
 */
const STORE_POINTS = 200;
const KEY = 'onpath.planShapes';
/** One per tour of a phone's 250 and the newest drafts; older ones go. */
const MAX = 300;
const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

interface Stored { b: [number, number, number, number]; q: string }

function load(): Record<string, Stored> {
  try {
    const j = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    return j && typeof j === 'object' ? j : {};
  } catch { return {}; } // expected: storage blocked or an unreadable old value – the rows show the kind's sign
}

/** [segs] thinned to at most [STORE_POINTS] points in all for storage (every n-th, the ends kept). */
function thin(segs: Seg[]): Seg[] {
  const all = segs.reduce((n, s) => n + s.length, 0);
  const stride = Math.max(1, Math.ceil(all / STORE_POINTS));
  return segs.map((s) => s.filter((_, i) => i % stride === 0 || i === s.length - 1)).filter((s) => s.length >= 2);
}

function boxOf(segs: Seg[]): [number, number, number, number] | null {
  let s = 90, n = -90, w = 180, e = -180;
  for (const seg of segs) for (const [la, lo] of seg) { s = Math.min(s, la); n = Math.max(n, la); w = Math.min(w, lo); e = Math.max(e, lo); }
  if (s > n) return null; // no points; one spot is kept as it is – the engine's RouteShape.of draws no shape for it
  return [s, w, n, e];
}

/** Keeps [segs] ([lat, lon] lists, e.g. a plan's legs or a track's segments) as the shape of [key] (planstats keys). */
export function keepShape(key: string, segs: Seg[]) {
  const kept = thin(segs.map((s) => s.filter((p) => Number.isFinite(p[0]) && Number.isFinite(p[1]))));
  const b = boxOf(kept);
  if (!b) return;
  const [s, w, n, e] = b, dy = n - s || 1e-9, dx = e - w || 1e-9;
  const q = kept.map((seg) => seg.map(([la, lo]) => {
    const y = Math.round(((la - s) / dy) * 4095), x = Math.round(((lo - w) / dx) * 4095);
    return B64[x >> 6] + B64[x & 63] + B64[y >> 6] + B64[y & 63];
  }).join('')).join('~');
  const all = load();
  delete all[key];
  all[key] = { b, q };
  const keys = Object.keys(all);
  for (const k of keys.slice(0, Math.max(0, keys.length - MAX))) delete all[k];
  try { localStorage.setItem(KEY, JSON.stringify(all)); } catch { /* expected: storage blocked or full – the sign stays */ }
}

/** The kept line of [key] as [lat, lon] segments, null = none. */
export function shapeOf(key: string): Seg[] | null {
  const st = load()[key];
  if (!st || !Array.isArray(st.b) || typeof st.q !== 'string') return null;
  const [s, w, n, e] = st.b;
  const v = (c: string) => B64.indexOf(c);
  const segs = st.q.split('~').map((part) => {
    const out: Seg = [];
    for (let i = 0; i + 3 < part.length; i += 4) {
      const x = v(part[i]) * 64 + v(part[i + 1]), y = v(part[i + 2]) * 64 + v(part[i + 3]);
      out.push([s + (y / 4095) * (n - s), w + (x / 4095) * (e - w)]);
    }
    return out;
  }).filter((x) => x.length >= 2);
  return segs.length ? segs : null;
}

/**
 * The app's RouteShape.of, run in the engine (slices.ts – never copied here, 2026-11-27): [segs] fitted into [w] × [h] with
 * [pad] on every side, the aspect kept, centred. Null = no shape (one spot, too few points) or no engine (reported) – the
 * row shows the kind's sign then.
 */
export function fitShape(segs: Seg[], w: number, h: number, pad: number): Shape | null {
  const text = segs.map((seg) => seg.filter(([la, lo]) => Number.isFinite(la) && Number.isFinite(lo))
    .map(([la, lo]) => `${la.toFixed(6)},${lo.toFixed(6)}`).join(';')).filter(Boolean).join('|');
  if (!text) return null;
  const s = slice<Shape>('routeShape', text, w, h, pad);
  return s && Array.isArray(s.lines) ? s : null;
}

const f = (v: number) => v.toFixed(1);
const path = (l: number[]) => { let d = ''; for (let i = 0; i + 1 < l.length; i += 2) d += (i ? 'L' : 'M') + f(l[i]) + ' ' + f(l[i + 1]); return d; };

/**
 * The shape as SVG: the line (plan blue, [pale] = a draft's paler line), the start dot on a paper ring, the goal in the
 * map's dark red. Decoration only: the row's own button names the route.
 */
export function shapeSvg(s: Shape, w: number, h: number, o: { width: number; dot: number; pale?: boolean }): string {
  const lines = s.lines.map((l) => `<path d="${path(l)}" fill="none" stroke="var(--plan)" stroke-width="${o.width}" stroke-linecap="round" stroke-linejoin="round"${o.pale ? ' opacity="0.45"' : ''}/>`).join('');
  const start = `<circle cx="${f(s.sx)}" cy="${f(s.sy)}" r="${f(o.dot * 1.6)}" fill="var(--paper)"/><circle cx="${f(s.sx)}" cy="${f(s.sy)}" r="${f(o.dot)}" fill="var(--plan)"/>`;
  const goal = `<circle cx="${f(s.ex)}" cy="${f(s.ey)}" r="${f(o.dot * 1.6)}" fill="var(--paper)"/><circle cx="${f(s.ex)}" cy="${f(s.ey)}" r="${f(o.dot)}" fill="var(--goal)"/>`;
  return `<svg class="routeshape" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" aria-hidden="true">${lines}${start}${goal}</svg>`;
}
