// The app's screen-state slices on the page (rebuild phase 2, docs/design/umbau-phase-2.md §2.2): the web-ready files of
// :state (state/web-ready.txt – RoutesState, SaveDialog, the pure rules of WebRules) compiled as they are into the Wasm engine and driven here, in
// the wait window's instance on the page's own thread (waitengine.ts; the routing worker is blocked while it routes).
// The page keeps its storage and its drawing; the decisions are the app's (web/engine/…/WebState.kt). Robust first:
// until the instance is there, or when it cannot load, every call returns null – the caller keeps what it has and
// loses nothing – and a failed call is reported, never thrown at the page.
import { problem } from './diag';
import { sliceExports, waitEngineLoaded } from './waitengine';

interface Slices {
  routesKeep(tours: string, recent: number, now: number, points: string, unsaved: boolean, savedId: number, newId: number): string;
  routesAfterSaved(tours: string, recent: number, now: number, savedId: number, fromId: number, points: string): string;
  routesTakePlace(tours: string, recent: number, now: number, id: number): string;
  routesToBin(tours: string, recent: number, now: number, id: number): string;
  routesRestore(tours: string, recent: number, now: number, id: number): string;
  routesTrim(tours: string, recent: number, now: number): string;
  routesRenameDraft(tours: string, recent: number, id: number, dated: string, proposed: string): string;
  routesRow(tours: string, recent: number, livePoints: number, liveLinked: number, liveUnsaved: boolean): string;
  routesOpen(fresh: boolean): string;
  routesTapSort(key: string): string;
  saveRequest(savedId: number, savedName: string, plannedIds: string, dated: string, taken: string): string;
  saveRequestCopy(name: string, id: number, taken: string): string;
  saveProposed(no: number, proposal: string): string;
  saveCancel(): void;
  saveConfirm(input: string, savedId: number): string;
  /** The app's LongRouteCancelCamera.after (WebRules): `[[lat, lon], …]` to fit, or null = the map stays. */
  cancelCamera(points: string, routeLine: string): string;
  /** The app's WholeRoute.SETTLE_MS (WebRules). */
  wholeRouteSettleMs(): string;
  /** The app's RoutesTab.search (WebRoutesOrder): the indices of the names (one per line) the query finds. */
  routesSearch(names: string, query: string): string;
  /** The app's RouteSort.sort: the rows' ("rank\tat\tm\ts\tup") indices in the order of the sort ("LENGTH:down"). */
  routesSort(rows: string, sort: string): string;
  /** The app's RouteSort.decode: `{ key, biggestFirst }`. */
  routesSortChoice(sort: string): string;
  /** RouteSort.Key in its order: `[{ key, dir }]`. */
  routesSortKeys(): string;
}

/** The slices are there (loaded) or will never be (failed): resolves true / false. */
export const slicesLoaded = waitEngineLoaded;
/** [slicesLoaded], but never waits longer than [ms] (a stalled download must not hold My routes or Save): false then. */
export const slicesReady = (ms = 8_000): Promise<boolean> =>
  Promise.race([slicesLoaded, new Promise<boolean>((r) => setTimeout(() => r(false), ms))]);

/** Calls slice function [fn]; its JSON answer parsed. Null = no instance, or the call failed (reported). */
export function slice<T>(fn: keyof Slices, ...args: (string | number | boolean)[]): T | null {
  const s = sliceExports() as Slices | null;
  if (!s) { problem('routes', `the app's slices are not loaded – ${fn} skipped (nothing changed)`); return null; }
  try {
    const out = (s[fn] as (...a: unknown[]) => string | void)(...args);
    return (typeof out === 'string' ? JSON.parse(out) : true) as T;
  } catch (e) {
    problem('routes', `slice call ${fn} failed – nothing changed`, e);
    return null;
  }
}

let settle: number | null = null;
/**
 * The app's WholeRoute.SETTLE_MS (read once). Until the instance is there – the first route can come before it – the
 * same 500 ms, unreported: nothing is lost by it, the fit only waits.
 */
export function settleMs(): number {
  if (settle == null && sliceExports()) settle = slice<number>('wholeRouteSettleMs');
  return settle ?? 500;
}

// ---- ids: the slices know tours by number; the page's plans have string ids. One number per plan for this page ----
const numbers = new Map<string, number>();
const names: string[] = [''];
/** The number of the stored plan [key] ("d:<id>" a Zuletzt draft, "s:<id>" a saved route), the same all page long. */
export function numOf(key: string): number {
  let n = numbers.get(key);
  if (n == null) { n = names.length; names.push(key); numbers.set(key, n); }
  return n;
}
/** Back from [numOf]; null = none (-1) or unknown. */
export const keyOf = (n: number): string | null => (n > 0 && n < names.length ? names[n] : null);

/** One plan as the slice's store lists it (WebState.parseTours): a line. Tabs / line ends in a name become spaces. */
export function tourLine(key: string, draft: boolean, deletedAt: number | null | undefined, at: number, name: string, points: string): string {
  return [numOf(key), draft ? 1 : 0, deletedAt ?? -1, Math.round(at), name.replace(/[\t\n\r]/g, ' '), points].join('\t');
}

/** What a My routes action of the slice wrote: [writes] for the page's store, [recent] the Zuletzt place now. */
export interface RoutesAnswer<R> { result: R; recent: number; writes: ({ op: 'bin'; id: number; ms: number } | { op: 'unbin' | 'gone'; id: number }
  | { op: 'rename'; id: number; name: string } | { op: 'draft'; id: number; draft: boolean })[] }

/** Save's name dialog as the slice holds it: [no] = which opening, [kind] NEW / OVERWRITE / COPY. */
export interface SaveAsk { no: number; kind: 'NEW' | 'OVERWRITE' | 'COPY'; name: string; routeId: number }
/** What the dialog's Save decided (SaveFlow.Write). */
export interface SaveWrite { write: 'NEW' | 'OVERWRITE' | 'COPY'; routeId: number; name: string }
