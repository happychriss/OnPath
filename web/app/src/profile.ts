// Elevation profile as an SVG. Distances and gradient classes come from the engine – the app's own
// profile data + RouteSummary.steepness (100 m window) – drawn like the phone's (ui/ElevationProfile, variant A
// owner 2026-11-12): the area in the route colour fading out, steeper a bit stronger, steepness colours only on the line.
// [mountProfile] adds the app's cursor (ElevationProfile: touch / slide → a line, "954 m · 3.2 km" and a dot on the map)
// plus the gradient there; the big profile below the map (owner 2026-11-05, terrain view A) is the same drawing, wider.
import type { PlanResult } from './engine/types';
import { t, number, distance } from './i18n';

export type Profile = NonNullable<PlanResult['profile']>;
/** The route's colour (OnPathColors.Route): the profile's fill and the line where it is flatter than 5 %. */
export const ROUTE_COLOUR = '#1E3FAE';
/** The line's colours per gradient class – the app's steepColor: < 5 % the route colour, then amber, orange, red. */
export const STEEP_COLOURS = [ROUTE_COLOUR, '#F2B33D', '#EF7C1A', '#D32F2F']; // flat, moderate, steep, very steep

/**
 * The app's ProfileFill (owner 2026-11-06, polish 8; variant A for the planner, owner 2026-11-12): the fill is
 * BASE_ALPHA of the line's colour at the top fading to nothing, and where the way is steep up to MAX_EXTRA_ALPHA
 * more – the slope over WINDOW_M around each point in LEVELS fine steps from 5 % (nothing) to 15 % (all).
 */
export const ProfileFill = {
  BASE_ALPHA: 0.30, MAX_EXTRA_ALPHA: 0.22, LEVELS: 8, WINDOW_M: 300, FROM_PCT: 5, TO_PCT: 15,
  alpha(level: number): number { return ProfileFill.MAX_EXTRA_ALPHA * level / (ProfileFill.LEVELS - 1); },
  /** Per point its level 0..LEVELS-1 (the app's ProfileFill.levels, line for line). */
  levels(dist: number[], ele: number[]): number[] {
    const W = ProfileFill.WINDOW_M;
    const n = Math.min(dist.length, ele.length), out = new Array<number>(n).fill(0);
    if (n < 2) return out;
    let a = 0, b = 0;
    for (let i = 0; i < n; i++) {
      while (dist[a] < dist[i] - W / 2 && a < i) a++;
      while (b + 1 < n && dist[b + 1] <= dist[i] + W / 2) b++;
      if (b < i) b = i;
      let lo = a, hi = b;
      // at the ends the window is too short: widen it to the full width where the profile allows
      while (dist[hi] - dist[lo] < W && (lo > 0 || hi < n - 1)) { if (lo > 0) lo--; if (hi < n - 1) hi++; }
      const run = dist[hi] - dist[lo];
      if (run <= 0) continue;
      const pct = Math.abs(ele[hi] - ele[lo]) / run * 100;
      const share = Math.min(1, Math.max(0, (pct - ProfileFill.FROM_PCT) / (ProfileFill.TO_PCT - ProfileFill.FROM_PCT)));
      out[i] = Math.round(share * (ProfileFill.LEVELS - 1));
    }
    return out;
  },
};

/** The app's columnIndices: per pixel column the first, lowest, highest and last point (a long route stays light). */
export function columnIndices(dist: number[], ele: number[], total: number, columns: number): number[] {
  const n = dist.length;
  if (n <= 4 * columns || total <= 0) return dist.map((_, i) => i);
  const col = (m: number) => Math.min(columns - 1, Math.max(0, Math.floor(m / total * columns)));
  const out: number[] = [];
  let i = 0;
  while (i < n) {
    const c = col(dist[i]);
    let lo = i, hi = i, j = i;
    while (j + 1 < n && col(dist[j + 1]) === c) { j++; if (ele[j] < ele[lo]) lo = j; if (ele[j] > ele[hi]) hi = j; }
    for (const k of [i, Math.min(lo, hi), Math.max(lo, hi), j]) if (!out.length || out[out.length - 1] < k) out.push(k);
    i = j + 1;
  }
  return out;
}

let gradIds = 0;

/**
 * The drawing, as the app's ElevationProfile: the area in [colour] fading to nothing, steeper stretches a bit stronger
 * in the same colour (layered, so it shades without seams), and on top the thin line – with [gradient] coloured by
 * steepness (STEEP_COLOURS), else in [colour].
 */
function drawing(p: Profile, W: number, H: number, colour: string, gradient: boolean): string {
  const { dist: d, ele, steep } = p;
  const total = d[d.length - 1] || 1;
  const lo = Math.min(...ele), hi = Math.max(...ele);
  const range = Math.max(hi - lo, 20), minE = lo - range * 0.08, maxE = hi + range * 0.08;
  const top = 3, bottom = H;
  const x = (m: number) => (m / total) * W, y = (e: number) => bottom - ((e - minE) / (maxE - minE)) * (bottom - top);
  const f = (v: number) => v.toFixed(1);
  const keep = columnIndices(d, ele, total, Math.max(1, Math.round(W)));
  const pt = (i: number) => `${f(x(d[i]))},${f(y(ele[i]))}`;
  const id = `pf${++gradIds}`;
  const grad = (name: string, a: number, b: number) =>
    `<linearGradient id="${name}" x1="0" y1="${top}" x2="0" y2="${bottom}" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="${colour}" stop-opacity="${a.toFixed(3)}"/><stop offset="1" stop-color="${colour}" stop-opacity="${b.toFixed(3)}"/></linearGradient>`;
  let defs = grad(`${id}a`, ProfileFill.BASE_ALPHA, 0);
  let s = `<path class="pfarea" d="M${keep.map(pt).join(' L')} L${f(W)},${bottom} L0,${bottom} Z" fill="url(#${id}a)"/>`;
  if (gradient) {
    // the steeper shading: layer k covers every span of level >= k, so neighbouring steps share the lower layers and
    // meet without a seam; each layer's strength makes the stack add up to the app's ProfileFill.alpha(level)
    const level = ProfileFill.levels(d, ele);
    const span = (q: number) => Math.max(level[keep[q]], level[keep[q + 1]]);
    for (let k = 1; k < ProfileFill.LEVELS; k++) {
      const a = 1 - (1 - ProfileFill.alpha(k)) / (1 - ProfileFill.alpha(k - 1));
      let path = '';
      let j = 0;
      while (j < keep.length - 1) {
        if (span(j) < k) { j++; continue; }
        let e = j + 1;
        while (e < keep.length - 1 && span(e) >= k) e++;
        path += `M${f(x(d[keep[j]]))},${bottom} L${keep.slice(j, e + 1).map(pt).join(' L')} L${f(x(d[keep[e]]))},${bottom} Z`;
        j = e;
      }
      if (!path) continue;
      defs += grad(`${id}s${k}`, a, a * 0.4);
      s += `<path class="pfsteep" d="${path}" fill="url(#${id}s${k})"/>`;
    }
  }
  // the line: per kept span the steepest class of the segments in it (short steep bits stay visible), one path per class
  const byClass: string[] = STEEP_COLOURS.map(() => '');
  const lastEnd: number[] = STEEP_COLOURS.map(() => -1);
  for (let j = 0; j < keep.length - 1; j++) {
    const a = keep[j], b = keep[j + 1];
    let k = 0;
    if (gradient) for (let i = a; i < Math.min(b, steep.length); i++) if (steep[i] > k) k = steep[i];
    k = Math.min(k, STEEP_COLOURS.length - 1);
    if (lastEnd[k] !== a) byClass[k] += `M${pt(a)}`;
    byClass[k] += ` L${pt(b)}`;
    lastEnd[k] = b;
  }
  byClass.forEach((path, k) => {
    if (path) s += `<path class="pfline" d="${path}" fill="none" stroke="${gradient ? STEEP_COLOURS[k] : colour}" stroke-width="${gradient ? 2.5 : 2}" stroke-linecap="round" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>`;
  });
  return `<defs>${defs}</defs>${s}`;
}

/**
 * The profile: heights above it ("300 – 578 m", [extra] beside them – the sheet's ⤢), the drawing, below it the
 * distance axis "0" … total at the right end (owner review 2026-11-05: the total sat in the middle).
 */
export function profileSvg(p: Profile, W = 300, H = 90, extra = '', inPlot = '', colour = ROUTE_COLOUR, gradient = true): string {
  const { dist: d, ele } = p;
  if (d.length < 2) return '';
  const total = d[d.length - 1] || 1;
  const lo = Math.min(...ele), hi = Math.max(...ele);
  // the app's ProfileAxis.labels (owner's Pixel 2026-11-06, "47 m" over "46 m"): heights that read the same → only the top
  const range = Math.round(lo) === Math.round(hi) ? `${Math.round(hi)} m` : `${Math.round(lo)} – ${Math.round(hi)} m`;
  return `<div class="profhead muted"><span>${range}</span>${extra}</div>
    <div class="profplot"><svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="${t('plan_elevation_profile')}">${drawing(p, W, H, colour, gradient)}</svg>${inPlot}</div>
    <div class="axis muted"><span>0</span><span>${number(total / 1000, 1)} km</span></div>`;
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
  /** Shade the marked range [from, to] metres (or none). */
  range(r: [number, number] | null): void;
}

/**
 * Draws [p] into [host] with the app's cursor: pointer over / touch-drag → the line, "954 m · 3.2 km · 8 %" and
 * [onCursor] with the distance (the page puts the dot on the map there); leaving → [onCursor](null).
 */
export function mountProfile(host: HTMLElement, p: Profile, onCursor: (m: number | null) => void, size: { W: number; H: number } = { W: 300, H: 90 }, extra = '',
  onRange?: (r: [number, number] | null) => void): ProfileView {
  if (p.dist.length < 2) { host.innerHTML = ''; return { show: () => {}, range: () => {} }; }
  host.innerHTML = profileSvg(p, size.W, size.H, extra, '<div class="profrange" hidden></div><div class="profcursor" hidden></div><div class="proflabel" hidden></div>');
  const plot = host.querySelector<HTMLElement>('.profplot')!;
  const svg = plot.querySelector<SVGSVGElement>(':scope > svg')!;
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
  // the marked range (big profile, owner 2026-11-06): press and drag (mouse), hold then drag (touch); a click clears
  const shade = plot.querySelector<HTMLElement>('.profrange')!;
  const paint = (r: [number, number] | null) => {
    shade.hidden = r == null;
    if (!r) return;
    const a = Math.min(r[0], r[1]) / total, b = Math.max(r[0], r[1]) / total;
    shade.style.left = `${(a * 100).toFixed(2)}%`; shade.style.width = `${((b - a) * 100).toFixed(2)}%`;
  };
  const mAt = (e: PointerEvent) => {
    const r = svg.getBoundingClientRect();
    return r.width > 0 ? Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)) * total : 0;
  };
  let press: { m: number; x: number; id: number; held: boolean; timer: number } | null = null;
  let marking = false;
  const startMark = () => { marking = true; svg.classList.add('marking'); };
  svg.addEventListener('pointermove', (e) => {
    if (press && onRange && e.pointerId === press.id) {
      if (!marking && e.pointerType === 'mouse' && Math.abs(e.clientX - press.x) > 4) startMark();
      if (!marking && e.pointerType !== 'mouse' && !press.held && Math.abs(e.clientX - press.x) > 8) { clearTimeout(press.timer); press = null; }
      if (marking && press) { paint([press.m, mAt(e)]); onRange([press.m, mAt(e)]); }
    }
    at(e);
  });
  svg.addEventListener('pointerdown', (e) => {
    at(e);
    if (e.pointerType !== 'mouse') svg.setPointerCapture(e.pointerId);
    if (!onRange || (e.pointerType === 'mouse' && e.button !== 0)) return;
    if (e.pointerType === 'mouse') svg.setPointerCapture(e.pointerId);
    const pr = { m: mAt(e), x: e.clientX, id: e.pointerId, held: false, timer: 0 };
    if (e.pointerType !== 'mouse') pr.timer = window.setTimeout(() => { pr.held = true; startMark(); }, 450);
    press = pr;
  });
  const end = (e: PointerEvent) => {
    if (press && e.pointerId === press.id) {
      clearTimeout(press.timer);
      if (marking) { const r: [number, number] = [press.m, mAt(e)]; paint(r); onRange?.(Math.abs(r[1] - r[0]) > 1 ? r : null); }
      else if (onRange && e.type === 'pointerup') { paint(null); onRange(null); } // a plain click clears the range
      press = null; marking = false; svg.classList.remove('marking');
    }
  };
  svg.addEventListener('pointerleave', (e) => { if (e.pointerType === 'mouse' && !press) off(); });
  svg.addEventListener('pointerup', (e) => { end(e); if (e.pointerType !== 'mouse') off(); });
  svg.addEventListener('pointercancel', (e) => { if (press) { clearTimeout(press.timer); press = null; marking = false; svg.classList.remove('marking'); } off(); });
  return { show: draw, range: paint };
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

/** The piece of [line] between [a] and [b] metres along it, as GeoJSON [lon, lat] coordinates (the marked range on the map). */
export function lineBetween(line: Line, a: number, b: number): [number, number][] {
  const from = Math.min(a, b), to = Math.max(a, b);
  const s = pointAt(line, from), e = pointAt(line, to);
  if (!s || !e) return [];
  const out: [number, number][] = [[s[1], s[0]]];
  line.pts.forEach(([la, lo], j) => { if (line.cum[j] > from && line.cum[j] < to) out.push([lo, la]); });
  out.push([e[1], e[0]]);
  return out;
}
