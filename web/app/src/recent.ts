// "Zuletzt" in Meine Touren – the app's one-plan rule (app 2923a3e7 / 6f4b47d3, owner 2026-11-12: "exactly one plan
// under Zuletzt – the one worked on last, saved or not"; RouteRules.kt Drafts.MAX = 1, routes/RoutesState.kt,
// domain/plan/LivePlan.kt LivePlanRows). No "Plan speichern?" question (owner 2026-11-06): a plan worth keeping (≥ 2
// points) that leaves the map while not saved as it is (Neu, another plan / link / GPX opened) is kept here as the one
// draft; a plan saved as it is takes the place as that saved route (the "Gespeichert" tag – it stays under Gespeichert
// too). Whatever takes the place sends every other draft to this browser's recycle bin – never gone for good (robust
// first); the bin keeps them [BIN_DAYS] days like the app's (RecycleBin.DAYS), Wiederherstellen brings one back as the
// Zuletzt plan. The place survives a reload (the app's prefs.zuletztId); once, the drafts an older planner collected
// beyond the newest [MAX] go to the bin (the app's trimDrafts / prefs.zuletztTrimmed). Like the app's drafts these are
// never synced (this browser only) and get no backup.
// A plan opened from Zuletzt still belongs to its entry: leaving it again updates that entry; saving it moves it to the
// saved plans (the entry goes for good – the saved route holds it – "✓ Gerade gespeichert" on the new row).

/** How many drafts stay outside the recycle bin (the app's Drafts.MAX). */
export const MAX = 1;
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

export function recentById(id: string): RecentPlan | null { return read().find((r) => r.id === id) ?? null; }

export function zuletztPlace(): Place | null {
  try {
    const v = JSON.parse(localStorage.getItem(PLACE) ?? 'null');
    return v && (v.kind === 'draft' || v.kind === 'saved') && typeof v.id === 'string' ? v : null;
  } catch (e) { warn('the Zuletzt place could not be read', e); return null; }
}

/**
 * [place] takes the Zuletzt place (the app's RoutesState.takePlace): every other draft goes to the recycle bin – the
 * replaced plan stays recoverable; a saved route is never touched. Returns how many drafts went.
 */
export function takePlace(place: Place, now = Date.now()): number {
  const list = read();
  let gone = 0;
  const out = list.map((r) => {
    if (r.deletedAt != null || (place.kind === 'draft' && r.id === place.id)) return r;
    gone++;
    return { ...r, deletedAt: now };
  });
  if (gone) { write(out); console.info(`[Problems] TRACE routes: Zuletzt: ${gone} plan(s) replaced by ${place.kind} ${place.id} – into the recycle bin`); }
  try { localStorage.setItem(PLACE, JSON.stringify(place)); } catch (e) { warn('the Zuletzt place could not be stored', e); }
  return gone;
}

/**
 * Keeps a plan as the Zuletzt draft ([replaces]: the entry it was opened from – updated, not doubled, back out of the
 * bin when another plan took the place meanwhile). The same points as another draft: that one becomes the plan there,
 * name and all, and null is returned; else the entry's id. Either way it takes the place – the draft before it goes to
 * the bin.
 */
export function keepRecent(code: string, title: string, points: string, replaces: string | null = null, now = Date.now()): string | null {
  const list = read();
  const same = list.find((r) => r.deletedAt == null && r.points === points && r.id !== replaces);
  if (same) {
    write(list.map((r) => (r.id === same.id ? { ...r, at: now } : r)));
    takePlace({ kind: 'draft', id: same.id }, now);
    return null;
  }
  const id = replaces && list.some((r) => r.id === replaces) ? replaces : newId();
  write([{ id, code, title, points, at: now }, ...list.filter((r) => r.id !== id)]);
  takePlace({ kind: 'draft', id }, now);
  return id;
}

/** Saved (Zuletzt → Speichern, or the plan saved from it): the entry goes for good – the saved route holds it now. */
export function dropRecent(id: string) { write(read().filter((r) => r.id !== id)); }

/**
 * A plan was saved as the route [savedId] with the points [points] (the app's RoutesState.afterSaved): the draft it
 * came from ([fromId]) and any draft with the same points go for good – never a second entry – and the saved route
 * takes the Zuletzt place.
 */
export function afterSaved(savedId: string, fromId: string | null, points: string | null, now = Date.now()) {
  const list = read();
  write(list.filter((r) => !(r.deletedAt == null && (r.id === fromId || (points != null && r.points === points)))));
  takePlace({ kind: 'saved', id: savedId }, now);
}

/** Zuletzt → Löschen: into the recycle bin at once, no question (the app's RoutesState.toBin). */
export function toBin(id: string, now = Date.now()): boolean {
  const list = read();
  if (!list.some((r) => r.id === id && r.deletedAt == null)) return false;
  write(list.map((r) => (r.id === id ? { ...r, deletedAt: now } : r)));
  console.info(`[Problems] TRACE plan: Zuletzt → recycle bin: "${list.find((r) => r.id === id)!.title}"`);
  return true;
}

/**
 * Papierkorb → Wiederherstellen (the app's RoutesState.restore): a draft shows only under Zuletzt, so it takes the
 * place – the draft there goes to the bin, a saved route there stays one. False when it is gone.
 */
export function restoreRecent(id: string, now = Date.now()): boolean {
  const list = read();
  if (!list.some((r) => r.id === id && r.deletedAt != null)) return false;
  write(list.map((r) => (r.id === id ? { id: r.id, code: r.code, title: r.title, points: r.points, at: r.at } : r)));
  takePlace({ kind: 'draft', id }, now);
  return true;
}

/** Whole days until a bin entry is removed (0 = at the next start; the app's RecycleBin.daysLeft). */
export function daysLeft(deletedAt: number, now = Date.now()): number {
  return Math.floor(Math.max(0, deletedAt + KEEP_MS - now) / DAY_MS);
}

/**
 * At every page start: bin entries older than [BIN_DAYS] days go (the app removes them at its start); once, the drafts
 * an older planner kept beyond the newest [MAX] go to the bin (the app's trimDrafts, guarded by prefs.zuletztTrimmed).
 * Returns { trimmed, removed }.
 */
export function startUp(now = Date.now()): { trimmed: number; removed: number } {
  let list = read();
  const before = list.length;
  list = list.filter((r) => r.deletedAt == null || now - r.deletedAt < KEEP_MS);
  const removed = before - list.length;
  let trimmed = 0;
  let done = false;
  try { done = localStorage.getItem(TRIMMED) === '1'; } catch { /* expected: storage blocked – the trim is idempotent anyway */ }
  if (!done) {
    const drafts = list.filter((r) => r.deletedAt == null).sort((a, b) => b.at - a.at);
    const gone = new Set(drafts.slice(MAX).map((r) => r.id));
    trimmed = gone.size;
    list = list.map((r) => (gone.has(r.id) ? { ...r, deletedAt: now } : r));
  }
  if (removed || trimmed) write(list);
  if (!done) {
    try { localStorage.setItem(TRIMMED, '1'); } catch (e) { warn('the Zuletzt clean-up mark could not be stored – tried again at the next start', e); }
    console.info(trimmed ? `[Problems] INFO routes: Zuletzt keeps one plan now (owner 2026-11-12): ${trimmed} older plan(s) moved to the recycle bin`
      : '[Problems] TRACE routes: Zuletzt keeps one plan now: nothing older to move');
  }
  if (removed) console.info(`[Problems] TRACE routes: recycle bin: ${removed} plan(s) older than ${BIN_DAYS} days removed`);
  return { trimmed, removed };
}

/**
 * The place name for an entry kept under its date name (the app's RoutesState.renameDraft, 21b4a7b1): only while the
 * entry is still under Zuletzt under [from] – never over a user rename, a save or a delete meanwhile.
 */
export function renameRecentIf(id: string, from: string, to: string): boolean {
  const list = read(), e = list.find((r) => r.id === id);
  if (!e || e.deletedAt != null || e.title !== from) return false;
  write(list.map((r) => (r.id === id ? { ...r, title: to } : r)));
  return true;
}

/** Zuletzt → ⋮ → Umbenennen: the entry keeps its place, only its name changes. */
export function renameRecent(id: string, title: string) {
  write(read().map((r) => (r.id === id ? { ...r, title } : r)));
}

/** The one Zuletzt row (the app's RecentRow): the plan on the map, else a draft or a saved route; null = none. */
export type RecentRow = { live: true; saved: boolean } | { live: false; draft: RecentPlan } | { live: false; savedId: string } | null;

/**
 * The app's LivePlanRows.of: the plan on the map is the row whenever there is one ([saved] = a saved route as it is –
 * "In Bearbeitung" + "Gespeichert"). An empty map: what [place] names, if still there – unless a draft outside the bin
 * is newer than it or the place is a saved route and any draft is there (a draft never hides); no place known → the
 * newest draft; else nothing. [drafts] newest first, [savedIds] = the saved routes in the list shown.
 */
export function recentRow(live: { savedHere: boolean; unsaved: boolean } | null, drafts: RecentPlan[], savedIds: Set<string>, place: Place | null): RecentRow {
  if (live) return { live: true, saved: live.savedHere && !live.unsaved };
  const newest = drafts[0] ?? null;
  const pointedDraft = place?.kind === 'draft' ? drafts.find((r) => r.id === place.id) ?? null : null;
  const pointedSaved = place?.kind === 'saved' && savedIds.has(place.id) ? place.id : null;
  if (newest) {
    // a saved route holding the place gives way to any draft; a pointed draft to a newer one
    if (pointedDraft && pointedDraft.at >= newest.at) return { live: false, draft: pointedDraft };
    return { live: false, draft: newest };
  }
  return pointedSaved ? { live: false, savedId: pointedSaved } : null;
}
