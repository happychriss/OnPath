// The route's shape in Meine Touren (owner 2026-11-07, the app's RoutePreview / RouteShape): a small preview tile on every
// row and the large overview a row tap opens. The sync record holds only the plan code (points) – no line – so, like the
// numbers (planstats.ts), the page keeps a route's line in this browser whenever it has it anyway: a plan routed here, an
// imported or opened track. Kept small: at most [MAX_POINTS] points, each as 12 + 12 bits inside the line's box. Never
// routes anything for the list: a route this browser has not routed yet shows the kind's sign instead (the app's
// placeholder while a line loads).
import { t } from './i18n';

export type Seg = [number, number][];
/** Fitted into a box: x/y pairs per segment, the start and the goal (pixels). */
export interface Shape { lines: number[][]; sx: number; sy: number; ex: number; ey: number }

/** The app's RouteShape.MAX_POINTS. */
export const MAX_POINTS = 200;
/** The app's RouteShape.MIN_EXTENT_DEG: smaller is one spot (no shape). */
const MIN_EXTENT_DEG = 0.00001;
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

/** [segs] thinned to at most [MAX_POINTS] points in all (every n-th, the ends kept – the app's stride). */
function thin(segs: Seg[]): Seg[] {
  const all = segs.reduce((n, s) => n + s.length, 0);
  const stride = Math.max(1, Math.ceil(all / MAX_POINTS));
  return segs.map((s) => s.filter((_, i) => i % stride === 0 || i === s.length - 1)).filter((s) => s.length >= 2);
}

function boxOf(segs: Seg[]): [number, number, number, number] | null {
  let s = 90, n = -90, w = 180, e = -180;
  for (const seg of segs) for (const [la, lo] of seg) { s = Math.min(s, la); n = Math.max(n, la); w = Math.min(w, lo); e = Math.max(e, lo); }
  if (s > n || (n - s < MIN_EXTENT_DEG && e - w < MIN_EXTENT_DEG)) return null;
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
 * The app's RouteShape.of: [segs] fitted into [w] × [h] with [pad] on every side, the aspect kept (lon scaled by
 * cos(latitude)), centred. [corner] > 0: nothing drawn in the bottom-right corner of that size (the ⤢ badge) – fitted
 * beside or above it, whichever keeps it larger.
 */
export function fitShape(segs: Seg[], w: number, h: number, pad: number, corner = 0): Shape | null {
  const kept = thin(segs);
  const b = boxOf(kept);
  if (!b) return null;
  const fit = (l: number, tp: number, r: number, bt: number): Shape => {
    const [s, west, n, e] = b, k = Math.cos(((s + n) / 2) * Math.PI / 180);
    const ex = (e - west) * k, ey = n - s;
    const rw = Math.max(1, r - l), rh = Math.max(1, bt - tp);
    const scale = Math.min(ex > 0 ? rw / ex : Infinity, ey > 0 ? rh / ey : Infinity);
    const ox = l + (rw - ex * scale) / 2, oy = tp + (rh - ey * scale) / 2;
    const x = (lo: number) => ox + (lo - west) * k * scale, y = (la: number) => oy + (n - la) * scale;
    const lines = kept.map((seg) => seg.flatMap(([la, lo]) => [x(lo), y(la)]));
    const first = kept[0][0], last = kept[kept.length - 1][kept[kept.length - 1].length - 1];
    return { lines, sx: x(first[1]), sy: y(first[0]), ex: x(last[1]), ey: y(last[0]) };
  };
  const full = fit(pad, pad, w - pad, h - pad);
  if (corner <= 0 || !hits(full, w - corner, h - corner)) return full;
  const beside = fit(pad, pad, w - Math.max(pad, corner), h - pad), above = fit(pad, pad, w - pad, h - Math.max(pad, corner));
  return span(beside) >= span(above) ? beside : above;
}

function hits(s: Shape, x0: number, y0: number): boolean {
  if ((s.sx >= x0 && s.sy >= y0) || (s.ex >= x0 && s.ey >= y0)) return true;
  return s.lines.some((l) => { for (let i = 0; i < l.length; i += 2) if (l[i] >= x0 && l[i + 1] >= y0) return true; return false; });
}

function span(s: Shape): number {
  let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
  for (const l of s.lines) for (let i = 0; i < l.length; i += 2) { x0 = Math.min(x0, l[i]); x1 = Math.max(x1, l[i]); y0 = Math.min(y0, l[i + 1]); y1 = Math.max(y1, l[i + 1]); }
  return Math.max(x1 - x0, y1 - y0);
}

const f = (v: number) => v.toFixed(1);
const path = (l: number[]) => { let d = ''; for (let i = 0; i + 1 < l.length; i += 2) d += (i ? 'L' : 'M') + f(l[i]) + ' ' + f(l[i + 1]); return d; };

/**
 * The shape as SVG: the line (plan blue, [pale] = a draft's paler line), the start dot on a paper ring, the goal in the
 * map's dark red ([flag] = the large overview's goal flag: a white flag on a dark red disc).
 */
export function shapeSvg(s: Shape, w: number, h: number, o: { width: number; dot: number; pale?: boolean; flag?: boolean }): string {
  const lines = s.lines.map((l) => `<path d="${path(l)}" fill="none" stroke="var(--plan)" stroke-width="${o.width}" stroke-linecap="round" stroke-linejoin="round"${o.pale ? ' opacity="0.45"' : ''}/>`).join('');
  const start = `<circle cx="${f(s.sx)}" cy="${f(s.sy)}" r="${f(o.dot * 1.6)}" fill="var(--paper)"/><circle cx="${f(s.sx)}" cy="${f(s.sy)}" r="${f(o.dot)}" fill="var(--plan)"/>`;
  const goal = o.flag
    ? `<g transform="translate(${f(s.ex - 12)},${f(s.ey - 12)})"><circle cx="12" cy="12" r="12" fill="var(--goal)"/><path transform="translate(4.5,4.5) scale(0.625)" fill="#fff" d="M14.4 6 14 4H5v17h2v-7h5.6l.4 2h7V6z"/></g>`
    : `<circle cx="${f(s.ex)}" cy="${f(s.ey)}" r="${f(o.dot * 1.6)}" fill="var(--paper)"/><circle cx="${f(s.ex)}" cy="${f(s.ey)}" r="${f(o.dot)}" fill="var(--goal)"/>`;
  return `<svg class="routeshape" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" role="img" aria-label="${t('routes_overview_show')}">${lines}${start}${goal}</svg>`;
}
