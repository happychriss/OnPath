// "Zuletzt" in Meine Touren – the app's drafts (owner 2026-11-06: "Nicht gespeichert" and every "Plan speichern?" /
// "Plan ersetzen?" question go, like Komoot's recent plans; app ui/RouteRules.kt Drafts, AppViewModel.keepAsDraft,
// Screens.kt RECENT_SHOWN / DraftActions). A plan worth keeping (≥ 2 points) that leaves the map while not saved as it
// is (Neu, another plan / link / GPX opened) is kept here – no question. The same points twice make no second entry;
// the newest [MAX] stay. Like the app's drafts they are never synced (this browser only) and get no backup.
// A plan opened from Zuletzt still belongs to its entry: leaving it again updates that entry; saving it moves it to the
// saved plans (the entry goes, "✓ Gerade gespeichert" on the new row).

/** The app's Drafts.MAX. */
export const MAX = 25;
/** The app's RECENT_SHOWN: Zuletzt shows the newest three, the rest behind „Alle N anzeigen ›“. */
export const SHOWN = 3;

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
 * entry's id, or null when the same points are kept already.
 */
export function keepRecent(code: string, title: string, points: string, replaces: string | null = null): string | null {
  const list = read();
  if (list.some((r) => r.points === points && r.id !== replaces)) return null;
  const id = replaces && list.some((r) => r.id === replaces) ? replaces : 'r' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  write([{ id, code, title, points, at: Date.now() }, ...list.filter((r) => r.id !== id)].sort((a, b) => b.at - a.at));
  return id;
}

/** Zuletzt → Löschen (or saved): the entry goes. */
export function dropRecent(id: string) { write(read().filter((r) => r.id !== id)); }

export function recentById(id: string): RecentPlan | null { return read().find((r) => r.id === id) ?? null; }

/** Zuletzt → ⋮ → Umbenennen: the entry keeps its place, only its name changes. */
export function renameRecent(id: string, title: string) {
  write(read().map((r) => (r.id === id ? { ...r, title } : r)));
}
