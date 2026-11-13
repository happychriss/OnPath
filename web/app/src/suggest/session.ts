// One suggestion run: a mechanism's candidates → finished suggestions, the same way for every mechanism
// (types.ts). Newest wins: starting a run cancels the one before; a cancelled run says nothing more.
import type { EngineClient } from '../engine/client';
import type { Progress } from '../engine/types';
import { ProviderMessage, type Candidate, type LatLon, type SuggestEvent, type SuggestProvider, type Suggestion, type TourWish } from './types';

/** The few engine calls the session needs – the planner's own plan computation, nothing new in the engine. */
export type SessionEngine = Pick<EngineClient, 'plan' | 'planFromTrack' | 'dropPlans'>;

export interface SessionTexts {
  searching: string;
  /** "Runde %1$s von %2$s wird berechnet" */
  computing(n: number, of: number): string;
  loadingMap: string;
  shorter: string;
  longer: string;
  failed: string;
  lang: string;
}

/** A length this far off the wished time gets the honest hint (shorter / longer than wished). */
export const HINT_OFF = 0.15;
const key = ([la, lo]: LatLon) => `${la.toFixed(5)},${lo.toFixed(5)}`;

export class SuggestSession {
  private run = 0;
  private abort: AbortController | null = null;

  constructor(private engine: SessionEngine, private texts: () => SessionTexts) {}

  /** A run is going. */
  get busy(): boolean { return this.abort != null; }

  /** Stops the running request (Abbrechen, ✕, a newer request). Nothing more is reported for it. */
  cancel() {
    if (!this.abort) return;
    this.run++;
    this.abort.abort();
    this.abort = null;
    this.engine.dropPlans(); // a suggestion's plan still routing must not hold the one engine worker
  }

  /**
   * Asks [provider] for [count] suggestions for [wish]; every event goes to [on] unless the run was superseded.
   * Never throws: a failure is one 'error' event, the plan on the page is never touched here.
   */
  async start(provider: SuggestProvider, wish: TourWish, count: number, on: (e: SuggestEvent) => void): Promise<void> {
    this.cancel();
    const mine = ++this.run;
    const abort = new AbortController();
    this.abort = abort;
    const live = () => mine === this.run;
    const tx = this.texts();
    const say = (e: SuggestEvent) => { if (live()) on(e); };
    let found = 0;
    say({ type: 'progress', text: tx.searching, found, of: count });

    // candidates come whenever the mechanism has them; they are planned one after another, in their order
    const queue: Candidate[] = [];
    let wake: () => void = () => {};
    let ended = false, failure: unknown = null;
    const produced = provider.run(wish, { count, signal: abort.signal, lang: tx.lang, progress: (text) => say({ type: 'progress', text, found, of: count }) },
      (c) => { queue.push(c); wake(); })
      .catch((e) => { failure = e; })
      .finally(() => { ended = true; wake(); });

    const seen = new Set<string>();
    try {
      while (live() && found < count) {
        if (!queue.length) {
          if (ended) break;
          await new Promise<void>((r) => { wake = r; });
          continue;
        }
        const c = queue.shift()!;
        say({ type: 'progress', text: tx.computing(found + 1, count), found, of: count });
        const s = await this.finish(c, wish, provider.id, (p) => {
          if (p.phase === 'download') say({ type: 'progress', text: tx.loadingMap, found, of: count });
        });
        if (!live()) return;
        if (!s) continue; // not routable here: the next candidate
        const sig = s.points.map(key).join(';');
        if (seen.has(sig)) continue; // the same loop twice says nothing new
        seen.add(sig);
        found++;
        say({ type: 'suggestion', suggestion: s });
      }
      await (found >= count ? Promise.resolve() : produced);
      if (!live()) return;
      if (failure && found === 0) {
        console.warn('[suggest] the mechanism failed', failure);
        // a mechanism's own plain words ("Rechner nicht erreichbar") are shown as they are, anything else as the general line
        say({ type: 'error', message: failure instanceof ProviderMessage ? failure.message : tx.failed, detail: String((failure as Error)?.message ?? failure) });
      } else say({ type: 'done', count: found });
    } catch (e) {
      console.warn('[suggest] the run failed', e);
      say({ type: 'error', message: tx.failed, detail: String((e as Error)?.message ?? e) });
    } finally {
      if (live()) { abort.abort(); this.abort = null; } // found enough: the mechanism may stop
    }
  }

  /** A candidate → the planner's plan of it. Null when it cannot be planned (no route, an engine failure). */
  private async finish(c: Candidate, wish: TourWish, via: string, onProgress: (p: Progress) => void): Promise<Suggestion | null> {
    let points = c.points ?? null;
    if (!points && c.track && c.track.length >= 2) {
      try {
        // the app's TrackToPlan: the few route points that make the router follow this line
        const r = JSON.parse(await this.engine.planFromTrack(c.track, wish.activity, wish.sac)) as { points?: LatLon[]; error?: string };
        points = r.points && r.points.length >= 2 ? r.points : null;
      } catch (e) { console.warn('[suggest] a line could not be turned into plan points', e); points = null; }
    }
    if (!points || points.length < 2) return null;
    points = closed(points, wish.start);
    const reply = await this.engine.plan(points, wish.activity, wish.sac, [], onProgress);
    const plan = reply.plan;
    if (!reply.ok || !plan || plan.error || plan.failed || plan.notice || !plan.legs?.length || plan.legs.some((l) => l.straight)) return null;
    const tx = this.texts();
    const hints = c.hints.slice();
    const wished = wish.hours * 3600;
    if (plan.timeS < wished * (1 - HINT_OFF)) hints.push(tx.shorter);
    else if (plan.timeS > wished * (1 + HINT_OFF)) hints.push(tx.longer);
    // the points as the plan snapped them (what the page would adopt after its own plan run anyway)
    const snapped = plan.points?.length === points.length ? plan.points : points;
    return { id: c.id, title: c.title, reasons: c.reasons.slice(0, 3), hints, points: closed(snapped, snapped[0]), plan, via };
  }
}

/** A loop's point list: starts at the wish's start and ends on its first point (a round trip for the planner). */
export function closed(points: LatLon[], start: LatLon): LatLon[] {
  const p = points.map(([la, lo]) => [Number(la.toFixed(6)), Number(lo.toFixed(6))] as LatLon);
  const near = (a: LatLon, b: LatLon) => Math.abs(a[0] - b[0]) < 0.0003 && Math.abs(a[1] - b[1]) < 0.0004; // ~30 m
  if (!near(p[0], start)) p.unshift([Number(start[0].toFixed(6)), Number(start[1].toFixed(6))]);
  if (p.length > 2 && near(p[p.length - 1], p[0])) p[p.length - 1] = p[0];
  else p.push(p[0]);
  return p;
}
