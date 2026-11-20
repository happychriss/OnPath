// "Zuletzt" in Meine Touren – the app's one-plan rule (owner 2026-11-12: "exactly one plan under Zuletzt – the one
// worked on last, saved or not"), decided by the app's own slice: state/…/ui/routes/RoutesState.kt (keepDraft,
// takePlace, afterSaved, toBin, restore, trimDrafts, renameDraft) and :core's LivePlanRows, run on the page
// (slices.ts, WebState.kt). This file is the slice's store (the port RoutesStore): the drafts in this browser's
// localStorage, the Zuletzt place (the app's prefs.zuletztId), the one-time trim mark (prefs.zuletztTrimmed) and the
// recycle bin's [BIN_DAYS] days (the app's RecycleBin.DAYS). Each action hands the slice the stored plans as lines
// and writes back what it decided. Like the app's drafts these are never synced (this browser only) and get no backup.
// A plan opened from Zuletzt still belongs to its entry: leaving it again updates that entry (the store writes the
// draft under the entry's id); saving it moves it to the saved plans.
import { slice, slicesReady, numOf, keyOf, tourLine, type RoutesAnswer } from './slices';
import { problem } from './diag';

/** Days a draft stays in the recycle bin (the app's RecycleBin.DAYS), then the next page start removes it. */
export const BIN_DAYS = 30;
const DAY_MS = 24 * 3600 * 1000;
const KEEP_MS = BIN_DAYS * DAY_MS;
/** A safety cap on the stored entries (localStorage is small): beyond it the oldest bin entries go, reported. */
const STORE_CAP = 400;

export interface RecentPlan {
  id: string;
  /** The plan code (engine.encode – the same as a saved plan's). */
  code: string;
  /** The name it was kept under (the Save dialog's proposal, else the date name). */
  title: string;
  /** Points as "lat,lon;…" – the "same points" check. */
  points: string;
  at: number;
  /** In the recycle bin since (ms); absent = under Zuletzt. */
  deletedAt?: number;
}

/** What holds the Zuletzt place while the map is empty (the app's RoutesState.recentId): a draft or a saved route. */
export type Place = { kind: 'draft'; id: string } | { kind: 'saved'; id: string };

const KEY = 'onpath.recent';
const PLACE = 'onpath.zuletzt';
const TRIMMED = 'onpath.zuletztTrimmed';

const warn = (what: string, e?: unknown) => console.warn(`[Problems] WARN plan: ${what}`, e ?? '');

function read(): RecentPlan[] {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) ?? '[]');
    return Array.isArray(v) ? v.filter((x) => x && typeof x.code === 'string' && typeof x.id === 'string') : [];
  } catch (e) {
    warn('Zuletzt could not be read – starts empty', e);
    return [];
  }
}
function write(list: RecentPlan[]) {
  let keep = list;
  if (keep.length > STORE_CAP) {
    // only bin entries go, oldest deletion first – a draft under Zuletzt is never cut
    const bin = keep.filter((r) => r.deletedAt != null).sort((a, b) => b.deletedAt! - a.deletedAt!);
    const drop = new Set(bin.slice(Math.max(0, bin.length - (keep.length - STORE_CAP))).map((r) => r.id));
    warn(`recycle bin full – ${drop.size} oldest plan(s) removed`);
    keep = keep.filter((r) => !drop.has(r.id));
  }
  try { localStorage.setItem(KEY, JSON.stringify(keep)); }
  catch (e) { warn('Zuletzt could not be stored (storage blocked or full)', e); }
}

const newId = () => 'r' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

/** The drafts under Zuletzt (not in the bin), newest first. */
export function recentPlans(): RecentPlan[] { return read().filter((r) => r.deletedAt == null).sort((a, b) => b.at - a.at); }

/** The recycle bin, newest deletion first. */
export function binnedPlans(): RecentPlan[] {
  return read().filter((r) => r.deletedAt != null).sort((a, b) => b.deletedAt! - a.deletedAt!);
}

export function zuletztPlace(): Place | null {
  try {
    const v = JSON.parse(localStorage.getItem(PLACE) ?? 'null');
    return v && (v.kind === 'draft' || v.kind === 'saved') && typeof v.id === 'string' ? v : null;
  } catch (e) { warn('the Zuletzt place could not be read', e); return null; }
}

// ---------------- the store the slice reads and writes ----------------

const draftKey = (id: string) => 'd:' + id;
const savedKey = (id: string) => 's:' + id;
/** The Zuletzt place as the slice's number (-1 = none known). */
const placeNum = (p = zuletztPlace()) => (p ? numOf(p.kind === 'draft' ? draftKey(p.id) : savedKey(p.id)) : -1);

/** A saved route as the store shows it to the slice: [points] its key (empty = never the same points), [deletedAt] in the bin. */
interface SavedLine { id: string; points?: string; deletedAt?: number | null; at?: number }

/**
 * The store's list: the saved routes first (a saved twin wins over a draft – never a draft beside it), then the drafts
 * newest first. [blank] = a draft whose points never count as the same (the entry a plan was opened from: leaving it
 * again updates it, the app's keepDraft is not asked to find it).
 */
function lines(list: RecentPlan[], saved: SavedLine[] = [], blank: string | null = null): string {
  const drafts = [...list].sort((a, b) => b.at - a.at);
  return [
    ...saved.map((s) => tourLine(savedKey(s.id), false, s.deletedAt, s.at ?? 0, '', s.points ?? '')),
    ...drafts.map((r) => tourLine(draftKey(r.id), true, r.deletedAt, r.at, r.title, r.id === blank ? '' : r.points)),
  ].join('\n');
}

/** Writes what the slice decided: the drafts' writes (the saved routes are the sync's – written by the caller) and the place. */
function apply<R>(a: RoutesAnswer<R>, list = read()): RecentPlan[] {
  let out = list;
  for (const w of a.writes) {
    const k = keyOf(w.id);
    if (!k?.startsWith('d:')) continue;
    const id = k.slice(2);
    if (w.op === 'gone') out = out.filter((r) => r.id !== id);
    else out = out.map((r) => {
      if (r.id !== id) return r;
      if (w.op === 'bin') return { ...r, deletedAt: w.ms };
      if (w.op === 'unbin') { const back = { ...r }; delete back.deletedAt; return back; }
      if (w.op === 'rename') return { ...r, title: w.name };
      return r;
    });
  }
  if (a.writes.length) write(out);
  const k = keyOf(a.recent);
  const place: Place | null = k ? { kind: k.startsWith('d:') ? 'draft' : 'saved', id: k.slice(2) } : null;
  const was = zuletztPlace();
  if (place && (place.kind !== was?.kind || place.id !== was?.id)) {
    try { localStorage.setItem(PLACE, JSON.stringify(place)); } catch (e) { warn('the Zuletzt place could not be stored', e); }
  }
  return out;
}

/**
 * The plan leaves the map (the app's RoutesState.keepDraft): [savedId] = the saved route it is as it is ([saved]
 * 'there' / 'binned' / 'gone' – the caller looked), [twin] = a saved route with exactly its points, [replaces] = the
 * Zuletzt entry it was opened from (updated, not doubled – back out of the bin when another plan took the place
 * meanwhile). Returns the draft's id when one was written, else null (the place went to an existing plan). Without the
 * slice the plan is still kept as a draft (never lost), nothing else changes.
 */
export function keepPlan(p: { code: string; title: string; points: string; replaces?: string | null; savedId?: string | null;
  saved?: 'there' | 'binned' | 'gone'; twin?: string | null }, now = Date.now()): string | null {
  const list = read();
  const id = p.replaces && list.some((r) => r.id === p.replaces) ? p.replaces : newId();
  const savedLines: SavedLine[] = [];
  if (p.savedId && p.saved === 'there') savedLines.push({ id: p.savedId });
  if (p.savedId && p.saved === 'binned') savedLines.push({ id: p.savedId, deletedAt: now });
  if (p.twin) savedLines.push({ id: p.twin, points: p.points });
  const entry = (): RecentPlan => ({ id, code: p.code, title: p.title, points: p.points, at: now });
  const a = slice<RoutesAnswer<number>>('routesKeep', lines(list, savedLines, p.replaces ?? null), placeNum(), now, p.points,
    !p.savedId, p.savedId ? numOf(savedKey(p.savedId)) : -1, numOf(draftKey(id)));
  if (!a) {
    if (p.points.split(';').length < 2 || (p.savedId && p.saved !== 'gone')) return null;
    write([entry(), ...list.filter((r) => r.id !== id)]); // robust first: kept, the bin and the place wait for the slice
    return id;
  }
  const out = apply(a, list);
  if (a.result === numOf(draftKey(id))) {
    write([entry(), ...out.filter((r) => r.id !== id)]);
    return id;
  }
  // the same points as the draft there: that one is the plan worked on last (its date moves up, as the store's touch)
  const k = keyOf(a.recent);
  if (k?.startsWith('d:')) write(out.map((r) => (r.id === k.slice(2) ? { ...r, at: now } : r)));
  return null;
}

/**
 * A plan was saved as the route [savedId] with the points [points] (the app's RoutesState.afterSaved): the draft it
 * came from ([fromId]) and any draft with the same points go for good – never a second entry – and the saved route
 * takes the Zuletzt place.
 */
export function afterSaved(savedId: string, fromId: string | null, points: string | null, now = Date.now()) {
  const list = read();
  const a = slice<RoutesAnswer<number>>('routesAfterSaved', lines(list), placeNum(), now, numOf(savedKey(savedId)),
    fromId ? numOf(draftKey(fromId)) : -1, points ?? '');
  if (a) apply(a, list);
}

/** Zuletzt → Löschen: into the recycle bin at once, no question (the app's RoutesState.toBin). */
export function toBin(id: string, now = Date.now()): boolean {
  const list = read();
  const e = list.find((r) => r.id === id && r.deletedAt == null);
  if (!e) return false;
  const a = slice<RoutesAnswer<boolean>>('routesToBin', lines(list), placeNum(), now, numOf(draftKey(id)));
  if (!a?.result) return false;
  apply(a, list);
  console.info(`[Problems] TRACE plan: Zuletzt → recycle bin: "${e.title}"`);
  return true;
}

/**
 * Papierkorb → Wiederherstellen (the app's RoutesState.restore): a draft shows only under Zuletzt, so it takes the
 * place – the draft there goes to the bin, a saved route there stays one. False when it is gone.
 */
export function restoreRecent(id: string, now = Date.now()): boolean {
  const list = read();
  if (!list.some((r) => r.id === id && r.deletedAt != null)) return false;
  const a = slice<RoutesAnswer<boolean>>('routesRestore', lines(list), placeNum(), now, numOf(draftKey(id)));
  if (!a?.result) return false;
  apply(a, list);
  return true;
}

/**
 * A saved route came back from the recycle bin (the sync restored it; the app's RoutesState.restore decides the rest):
 * the drafts [twins] with its points (planned again while it was in the bin) go to the bin – never a draft beside a
 * saved route with the same points – and the saved route takes the Zuletzt place when one of them held it.
 */
export function savedRestored(savedId: string, twins: string[], now = Date.now()) {
  if (!twins.length) return;
  const list = read();
  // the twins' points are the route's (the caller compared them): the route's key = theirs
  const points = list.find((r) => r.id === twins[0])?.points ?? '';
  const marked = list.map((r) => (twins.includes(r.id) ? { ...r, points } : r));
  const a = slice<RoutesAnswer<boolean>>('routesRestore', lines(marked, [{ id: savedId, points, deletedAt: now }]), placeNum(), now,
    numOf(savedKey(savedId)));
  if (!a) return;
  apply(a, list);
  console.info(`[Problems] TRACE routes: recycle bin: a restored route's ${twins.length} twin draft(s) → recycle bin`);
}

/** Whole days until a bin entry is removed (0 = at the next start; the app's RecycleBin.daysLeft). */
export function daysLeft(deletedAt: number, now = Date.now()): number {
  return Math.floor(Math.max(0, deletedAt + KEEP_MS - now) / DAY_MS);
}

let started: Promise<{ trimmed: number; removed: number }> | null = null;
/** The page start's clean-up has run (Meine Touren waits for it). */
export const zuletztReady = () => started ?? Promise.resolve({ trimmed: 0, removed: 0 });

/**
 * At every page start: bin entries older than [BIN_DAYS] days go (the app removes them at its start); once, the drafts
 * an older planner kept beyond the newest one go to the bin (the slice's trimDrafts, guarded by prefs.zuletztTrimmed –
 * tried again at the next start when the slice is not there). Returns { trimmed, removed }.
 */
export function startUp(now = Date.now()): Promise<{ trimmed: number; removed: number }> {
  started = (async () => {
    let list = read();
    const before = list.length;
    list = list.filter((r) => r.deletedAt == null || now - r.deletedAt < KEEP_MS);
    const removed = before - list.length;
    if (removed) {
      write(list);
      console.info(`[Problems] TRACE routes: recycle bin: ${removed} plan(s) older than ${BIN_DAYS} days removed`);
    }
    let done = false;
    try { done = localStorage.getItem(TRIMMED) === '1'; } catch { /* expected: storage blocked – the trim is idempotent anyway */ }
    if (done || !(await slicesReady())) return { trimmed: 0, removed };
    list = read();
    const a = slice<RoutesAnswer<number>>('routesTrim', lines(list), placeNum(), now);
    if (!a) return { trimmed: 0, removed };
    apply(a, list);
    try { localStorage.setItem(TRIMMED, '1'); } catch (e) { warn('the Zuletzt clean-up mark could not be stored – tried again at the next start', e); }
    console.info(a.result ? `[Problems] INFO routes: Zuletzt keeps one plan now (owner 2026-11-12): ${a.result} older plan(s) moved to the recycle bin`
      : '[Problems] TRACE routes: Zuletzt keeps one plan now: nothing older to move');
    return { trimmed: a.result, removed };
  })().catch((e) => { problem('routes', 'Zuletzt: the start-up clean-up failed – tried again at the next start', e); return { trimmed: 0, removed: 0 }; });
  return started;
}

/**
 * The place name for an entry kept under its date name (the app's RoutesState.renameDraft): only while the entry is
 * still under Zuletzt under [from] – never over a user rename, a save or a delete meanwhile.
 */
export function renameRecentIf(id: string, from: string, to: string): boolean {
  const list = read();
  const a = slice<RoutesAnswer<boolean>>('routesRenameDraft', lines(list), placeNum(), numOf(draftKey(id)), from, to);
  if (!a?.result) return false;
  apply(a, list);
  return true;
}

/** Zuletzt → ⋮ → Umbenennen: the entry keeps its place, only its name changes (the store's rename). */
export function renameRecent(id: string, title: string) {
  write(read().map((r) => (r.id === id ? { ...r, title } : r)));
}

/** The one Zuletzt row (the app's RecentRow): the plan on the map, else a draft or a saved route; null = none. */
export type RecentRow = { live: true; saved: boolean } | { live: false; draft: RecentPlan } | { live: false; savedId: string } | null;

/**
 * The app's LivePlanRows.of: the plan on the map is the row whenever there is one ([live.savedId] = the saved route it is,
 * [live.unsaved] = changed since – "Gespeichert" only as it is). An empty map: what the place names, if still there –
 * unless a draft outside the bin is newer, or the place is a saved route and a draft is there (a draft never hides);
 * no place known → the newest draft; else nothing. [drafts] newest first, [savedIds] = the saved routes in the list shown.
 * Without the slice: the plan on the map, else the newest draft.
 */
export function recentRow(live: { savedId: string | null; unsaved: boolean } | null, drafts: RecentPlan[], savedIds: string[]): RecentRow {
  const tours = lines(drafts, savedIds.map((id) => ({ id })));
  const r = slice<{ live: boolean; id: number; saved: boolean }>('routesRow', tours, placeNum(), live ? 1 : 0,
    live?.savedId ? numOf(savedKey(live.savedId)) : -1, !!live?.unsaved);
  if (!r) return live ? { live: true, saved: false } : drafts[0] ? { live: false, draft: drafts[0] } : null;
  if (r.live) return { live: true, saved: r.saved };
  const k = keyOf(r.id);
  if (!k) return null;
  if (k.startsWith('s:')) return { live: false, savedId: k.slice(2) };
  const d = drafts.find((x) => x.id === k.slice(2));
  return d ? { live: false, draft: d } : null;
}
