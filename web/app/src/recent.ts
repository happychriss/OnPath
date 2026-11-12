// "Zuletzt" in Meine Touren – the app's drafts (owner 2026-11-06: "Nicht gespeichert" and every "Plan speichern?" /
// "Plan ersetzen?" question go, like Komoot's recent plans; app ui/RouteRules.kt Drafts, AppViewModel.keepAsDraft,
// Screens.kt DraftActions). A plan worth keeping (≥ 2 points) that leaves the map while not saved as it
// is (Neu, another plan / link / GPX opened) is kept here – no question. The same points twice make no second entry
// (the entry with them becomes the newest). Meine Touren shows exactly one plan under Zuletzt (app 2923a3e7, owner
// 2026-11-12): the plan on the map, else the newest entry. The app moves the drafts it replaces to its recycle bin; the
// web has none yet, so the older entries stay stored here (the newest [MAX]) and come back when the one shown is saved
// or deleted – nothing is lost. Like the app's drafts they are never synced (this browser only) and get no backup.
// A plan opened from Zuletzt still belongs to its entry: leaving it again updates that entry; saving it moves it to the
// saved plans (the entry goes, "✓ Gerade gespeichert" on the new row).

/** How many entries this browser keeps (the app keeps one, its recycle bin holds the rest – the web has no bin yet). */
export const MAX = 25;

export interface RecentPlan {
  id: string;
  /** The plan code (engine.encode – the same as a saved plan's). */
  code: string;
  /** The name it was kept under (the Save dialog's proposal, else the date name). */
  title: string;
  /** Points as "lat,lon;…" – the "same points" check. */
  points: string;
  at: number;
}

const KEY = 'onpath.recent';

function read(): RecentPlan[] {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) ?? '[]');
    return Array.isArray(v) ? v.filter((x) => x && typeof x.code === 'string' && typeof x.id === 'string') : [];
  } catch (e) {
    console.warn('[Problems] WARN plan: Zuletzt could not be read – starts empty', e);
    return [];
  }
}
function write(list: RecentPlan[]) {
  try { localStorage.setItem(KEY, JSON.stringify(list.slice(0, MAX))); }
  catch (e) { console.warn('[Problems] WARN plan: Zuletzt could not be stored (storage blocked or full)', e); }
}

/** Newest first. */
export function recentPlans(): RecentPlan[] { return read().sort((a, b) => b.at - a.at); }

/**
 * Keeps a plan under Zuletzt ([replaces]: the entry it was opened from – it is updated, not doubled). Returns the
 * entry's id, or null when the same points are kept already – that entry then becomes the newest (the one Meine Touren
 * shows), name and all as it was.
 */
export function keepRecent(code: string, title: string, points: string, replaces: string | null = null): string | null {
  const list = read();
  const same = list.find((r) => r.points === points && r.id !== replaces);
  if (same) {
    write([{ ...same, at: Date.now() }, ...list.filter((r) => r.id !== same.id)]);
    return null;
  }
  const id = replaces && list.some((r) => r.id === replaces) ? replaces : 'r' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  write([{ id, code, title, points, at: Date.now() }, ...list.filter((r) => r.id !== id)].sort((a, b) => b.at - a.at));
  return id;
}

/** Zuletzt → Löschen (or saved): the entry goes. */
export function dropRecent(id: string) { write(read().filter((r) => r.id !== id)); }

export function recentById(id: string): RecentPlan | null { return read().find((r) => r.id === id) ?? null; }

/**
 * The place name for an entry kept under its date name (the app's RoutesState.renameDraft, 21b4a7b1): only while the
 * entry is still there under [from] – never over a user rename, a save (the entry is gone) or a delete meanwhile.
 */
export function renameRecentIf(id: string, from: string, to: string): boolean {
  const list = read(), e = list.find((r) => r.id === id);
  if (!e || e.title !== from) return false;
  write(list.map((r) => (r.id === id ? { ...r, title: to } : r)));
  return true;
}

/** Zuletzt → ⋮ → Umbenennen: the entry keeps its place, only its name changes. */
export function renameRecent(id: string, title: string) {
  write(read().map((r) => (r.id === id ? { ...r, title } : r)));
}
