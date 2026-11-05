// Elevation profile as an SVG. Distances and gradient classes come from the engine – the app's own
// profile data + RouteSummary.steepness (100 m window) – so the colours match the phone (ui/ElevationProfile).
// [mountProfile] adds the app's cursor (ElevationProfile: touch / slide → a line, "954 m · 3.2 km" and a dot on the map)
// plus the gradient there; the big profile below the map (owner 2026-11-05, terrain view A) is the same drawing, wider.
import type { PlanResult } from './engine/types';
import { t, number, distance } from './i18n';

export type Profile = NonNullable<PlanResult['profile']>;
export const STEEP_COLOURS = ['#7fb77e', '#e3b23c', '#e07b39', '#c0392b']; // flat, moderate, steep, very steep

function bars(p: Profile, W: number, H: number, flat: string): string {
  const { dist: d, ele, steep } = p;
  const total = d[d.length - 1] || 1;
  const lo = Math.min(...ele), hi = Math.max(...ele), span = Math.max(hi - lo, 50);
  const x = (m: number) => ((m / total) * W).toFixed(1), y = (e: number) => (H - 6 - ((e - lo) / span) * (H - 16)).toFixed(1);
  let s = '';
  for (let i = 1; i < d.length; i++) {
    if (d[i] <= d[i - 1]) continue;
    const c = steep[i - 1] ? STEEP_COLOURS[steep[i - 1]] : flat;
    s += `<path d="M${x(d[i - 1])},${H} L${x(d[i - 1])},${y(ele[i - 1])} L${x(d[i])},${y(ele[i])} L${x(d[i])},${H} Z" fill="${c}" stroke="${c}" stroke-width="0.4"/>`;
  }
  return s;
}

export function profileSvg(p: Profile, W = 300, H = 90, flat = STEEP_COLOURS[0]): string {
  const { dist: d, ele } = p;
  if (d.length < 2) return '';
  const total = d[d.length - 1] || 1;
  const lo = Math.min(...ele), hi = Math.max(...ele);
  return `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="${t('plan_elevation_profile')}">${bars(p, W, H, flat)}</svg>
    <div class="axis muted"><span>${Math.round(lo)} m</span><span>${number(total / 1000, 1)} km</span><span>${Math.round(hi)} m</span></div>`;
}

/** The gradient in % around kept point [i] over about 100 m (the window RouteSummary.steepness uses). */
export function gradeAt(p: Profile, i: number): number {
  const { dist: d, ele } = p;
  let a = i, b = i;
  while (a > 0 && d[i] - d[a - 1] <= 50) a--;
  while (b < d.length - 1 && d[b + 1] - d[i] <= 50) b++;
  if (b === a) { if (b < d.length - 1) b++; else if (a > 0) a--; }
  const run = d[b] - d[a];
  return run > 0 ? ((ele[b] - ele[a]) / run) * 100 : 0;
}

/** The kept point nearest to [m] metres along the line. */
export function indexAt(p: Profile, m: number): number {
  const d = p.dist;
  let lo = 0, hi = d.length - 1;
  while (hi - lo > 1) { const mid = (lo + hi) >> 1; if (d[mid] <= m) lo = mid; else hi = mid; }
  return m - d[lo] <= d[hi] - m ? lo : hi;
}

export interface ProfileView {
  /** Show the cursor at [m] metres along the line (from the map), or hide it (null). Reports nothing back. */
  show(m: number | null): void;
}

/**
 * Draws [p] into [host] with the app's cursor: pointer over / touch-drag → the line, "954 m · 3.2 km · 8 %" and
 * [onCursor] with the distance (the page puts the dot on the map there); leaving → [onCursor](null).
 */
export function mountProfile(host: HTMLElement, p: Profile, onCursor: (m: number | null) => void, size: { W: number; H: number; flat?: string } = { W: 300, H: 90 }): ProfileView {
  if (p.dist.length < 2) { host.innerHTML = ''; return { show: () => {} }; }
  host.innerHTML = `<div class="profplot">${profileSvg(p, size.W, size.H, size.flat).replace('</svg>', '</svg><div class="profcursor" hidden></div><div class="proflabel" hidden></div>')}</div>`;
  const plot = host.querySelector<HTMLElement>('.profplot')!;
  const svg = plot.querySelector('svg')!;
  const line = plot.querySelector<HTMLElement>('.profcursor')!, label = plot.querySelector<HTMLElement>('.proflabel')!;
  const total = p.dist[p.dist.length - 1] || 1;
  const draw = (m: number | null) => {
    line.hidden = label.hidden = m == null;
    if (m == null) return;
    const i = indexAt(p, m);
    const f = Math.min(1, Math.max(0, p.dist[i] / total));
    line.style.left = `${(f * 100).toFixed(2)}%`;
    label.textContent = `${Math.round(p.ele[i])} m · ${distance(p.dist[i])} · ${number(gradeAt(p, i), 0)} %`;
    // the label beside the line, on the side with room
    label.style.left = f < 0.6 ? `calc(${(f * 100).toFixed(2)}% + 6px)` : '';
    label.style.right = f < 0.6 ? '' : `calc(${((1 - f) * 100).toFixed(2)}% + 6px)`;
  };
  const at = (e: PointerEvent) => {
    const r = svg.getBoundingClientRect();
    if (r.width <= 0) return;
    const m = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)) * total;
    const i = indexAt(p, m);
    draw(p.dist[i]);
    onCursor(p.dist[i]);
  };
  const off = () => { draw(null); onCursor(null); };
  svg.addEventListener('pointermove', at);
  svg.addEventListener('pointerdown', (e) => { at(e); if (e.pointerType !== 'mouse') svg.setPointerCapture(e.pointerId); });
  svg.addEventListener('pointerleave', (e) => { if (e.pointerType === 'mouse') off(); });
  svg.addEventListener('pointerup', (e) => { if (e.pointerType !== 'mouse') off(); });
  svg.addEventListener('pointercancel', off);
  return { show: draw };
}

/** Route vertices [lat, lon] with their distance along the whole line (the app's Geo.cumulative, haversine). */
export interface Line { pts: [number, number][]; cum: number[] }

export function lineOf(legs: { points: [number, number, number | null][] }[]): Line {
  const pts: [number, number][] = [], cum: number[] = [];
  for (const l of legs) for (const [la, lo] of l.points) {
    const prev = pts[pts.length - 1];
    cum.push(prev ? cum[cum.length - 1] + haversine(prev, [la, lo]) : 0);
    pts.push([la, lo]);
  }
  return { pts, cum };
}

function haversine(a: [number, number], b: [number, number]): number {
  const R = 6371008.8, r = Math.PI / 180;
  const dLa = (b[0] - a[0]) * r, dLo = (b[1] - a[1]) * r;
  const h = Math.sin(dLa / 2) ** 2 + Math.cos(a[0] * r) * Math.cos(b[0] * r) * Math.sin(dLo / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** The point [m] metres along [line] (between its vertices), or null for an empty line. */
export function pointAt(line: Line, m: number): [number, number] | null {
  const { pts, cum } = line;
  if (!pts.length) return null;
  let lo = 0, hi = pts.length - 1;
  if (m <= 0) return pts[0];
  if (m >= cum[hi]) return pts[hi];
  while (hi - lo > 1) { const mid = (lo + hi) >> 1; if (cum[mid] <= m) lo = mid; else hi = mid; }
  const f = cum[hi] > cum[lo] ? (m - cum[lo]) / (cum[hi] - cum[lo]) : 0;
  return [pts[lo][0] + (pts[hi][0] - pts[lo][0]) * f, pts[lo][1] + (pts[hi][1] - pts[lo][1]) * f];
}

/**
 * The route cut into runs of one gradient class (the big profile's coloured line on the map): each vertex takes the
 * class of the profile piece its distance falls in.
 */
export function steepRuns(line: Line, p: Profile): { cls: number; coords: [number, number][] }[] {
  const runs: { cls: number; coords: [number, number][] }[] = [];
  let k = 0;
  line.pts.forEach(([la, lo], j) => {
    const m = line.cum[j];
    while (k < p.dist.length - 2 && p.dist[k + 1] <= m) k++;
    const cls = p.steep[k] ?? 0;
    const last = runs[runs.length - 1];
    if (last && last.cls === cls) last.coords.push([lo, la]);
    else {
      const start: [number, number][] = last ? [last.coords[last.coords.length - 1]] : [];
      runs.push({ cls, coords: [...start, [lo, la]] });
    }
  });
  return runs.filter((r) => r.coords.length > 1);
}
