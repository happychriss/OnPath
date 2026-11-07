// The route draws itself (the app's map/RouteReveal, owner 2026-11-06 "polish 2"): a NEW route for the plan runs from start
// to goal in [DURATION_MS], slowing towards the goal. The source gets the first part of the legs each frame (progressive
// coordinates – the casing and the dashed straight legs follow by themselves). Not the first route drawn after the page
// loaded (a restored plan is not news), not the same route drawn again, not a line longer than [MAX_POINTS], not with
// "reduce motion". Automated browsers (the smokes) get the whole line at once unless a test asks for it (window.__reveal),
// so no check reads half a line.

export interface RevealLeg { straight: boolean; pts: [number, number][] } // [lon, lat]

export const DURATION_MS = 500;
export const MAX_POINTS = 4_000;
/** The app's RouteReveal.ease: slows down towards the goal. */
export const ease = (t: number) => { const c = Math.min(1, Math.max(0, t)); return 1 - (1 - c) * (1 - c); };

/** The legs up to [fraction] of their whole length (the app's RouteReveal.Line.cut): whole legs before, the cut one ends between two samples. */
export function cut(legs: RevealLeg[], fraction: number): RevealLeg[] {
  const d = (a: [number, number], b: [number, number]) => {
    const k = Math.cos(((a[1] + b[1]) / 2) * Math.PI / 180);
    return Math.hypot((b[0] - a[0]) * k, b[1] - a[1]);
  };
  const cum = legs.map((l) => { const c = [0]; for (let i = 1; i < l.pts.length; i++) c.push(c[i - 1] + d(l.pts[i - 1], l.pts[i])); return c; });
  const lens = cum.map((c) => c[c.length - 1] ?? 0), total = lens.reduce((a, b) => a + b, 0);
  if (fraction >= 1 || total <= 0) return legs;
  let at = Math.max(0, fraction) * total;
  const out: RevealLeg[] = [];
  for (let k = 0; k < legs.length; k++) {
    const l = legs[k], c = cum[k];
    if (l.pts.length < 2) continue;
    if (lens[k] <= at) { out.push(l); at -= lens[k]; continue; }
    let hi = 1;
    while (hi < c.length && c[hi] < at) hi++;
    const lo = hi - 1, f = (at - c[lo]) / Math.max(1e-12, c[hi] - c[lo]);
    const a = l.pts[lo], b = l.pts[hi];
    const pts = [...l.pts.slice(0, hi), [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f] as [number, number]];
    if (pts.length >= 2) out.push({ straight: l.straight, pts });
    break;
  }
  return out;
}

export class RouteReveal {
  private last: unknown = null;
  private drewOne = false;
  private raf = 0;
  constructor(private set: (legs: RevealLeg[]) => void) {}

  private enabled(): boolean {
    const w = window as unknown as { __reveal?: boolean };
    if (navigator.webdriver && !w.__reveal) return false;
    try { return !matchMedia('(prefers-reduced-motion: reduce)').matches; } catch { return true; } // expected: old browsers – animate
  }

  /** Draws [legs] of [plan] (null = no route): a new plan's route draws itself, everything else at once. */
  draw(plan: unknown, legs: RevealLeg[]) {
    if (plan && plan === this.last) { if (!this.raf) this.set(legs); return; } // the same route again: never restarted
    cancelAnimationFrame(this.raf); this.raf = 0;
    this.last = plan;
    const n = legs.reduce((a, l) => a + l.pts.length, 0);
    const first = !this.drewOne;
    if (plan) this.drewOne = true;
    if (!plan || first || n < 2 || n > MAX_POINTS || !this.enabled()) { this.set(legs); return; }
    const start = performance.now();
    const frame = (now: number) => {
      const t = (now - start) / DURATION_MS;
      if (t >= 1) { this.raf = 0; this.set(legs); return; }
      this.set(cut(legs, ease(t)));
      this.raf = requestAnimationFrame(frame);
    };
    this.set([]);
    this.raf = requestAnimationFrame(frame);
  }
}
