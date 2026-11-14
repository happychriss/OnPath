// The order of Meine Touren (the app's ui/routes/RouteSort + RouteSortChips, owner 2026-11-12 variant C; owner
// 2026-11-13: "Der Planer braucht auch dieselben Suchen und Sortieren-Einstellungen wie das Handy"). Mirrored – the app's
// RouteSort works on its database Tour, which the engine does not compile (web/parity.json). Same chips in the same
// order with the app's strings, the same tap rule, the same ties, the same text form ("LENGTH:down"). Not kept any more
// (app 82167d11, owner 2026-11-13): Meine Touren opens with Neueste zuerst every time, a choice holds while it is open
// (plans.ts). One addition the app does not need: a plan this browser never routed
// has no numbers (planstats.ts) – it goes after those with numbers, in either direction, newest first among themselves.
import { t } from './i18n';
import { icon } from './icons';

export type SortKey = 'NEWEST' | 'OLDEST' | 'LENGTH' | 'DURATION' | 'ASCENT';
/** RouteSort.Key, in its order; [dir] = hasDirection (a tap on the chosen chip turns it round). */
export const SORT_KEYS: readonly { key: SortKey; dir: boolean; label: string }[] = [
  { key: 'NEWEST', dir: false, label: 'routes_sort_newest' },
  { key: 'OLDEST', dir: false, label: 'routes_sort_oldest' },
  { key: 'LENGTH', dir: true, label: 'routes_sort_length' },
  { key: 'DURATION', dir: true, label: 'routes_sort_duration' },
  { key: 'ASCENT', dir: true, label: 'routes_sort_ascent' },
];
export interface SortChoice { key: SortKey; biggestFirst: boolean }
export const SORT_DEFAULT: SortChoice = { key: 'NEWEST', biggestFirst: true };
const hasDir = (k: SortKey) => SORT_KEYS.some((s) => s.key === k && s.dir);

/** RouteSort.tap: the chosen one with a direction turns round, any other is chosen biggest first. */
export function tapSort(c: SortChoice, key: SortKey): SortChoice {
  if (key !== c.key) return { key, biggestFirst: true };
  return hasDir(key) ? { key, biggestFirst: !c.biggestFirst } : c;
}

/** What the sort reads of a row: its date (the list's date), id and numbers (null = not known in this browser). */
export interface SortFacts { at: number; id: string; m: number | null; s: number | null; up: number | null }

/** RouteSort.sort: [items] in the order of [c] (a new list). Ties: the newest first, then the higher id. */
export function sortRoutes<T>(items: T[], c: SortChoice, facts: (x: T) => SortFacts): T[] {
  const f = new Map(items.map((x) => [x, facts(x)]));
  const idCmp = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
  const newest = (a: SortFacts, b: SortFacts) => b.at - a.at || idCmp(b.id, a.id);
  const value = (x: SortFacts): number | null => (c.key === 'LENGTH' ? x.m : c.key === 'DURATION' ? x.s : x.up);
  const cmp = (a: SortFacts, b: SortFacts): number => {
    if (c.key === 'NEWEST') return newest(a, b);
    if (c.key === 'OLDEST') return a.at - b.at || idCmp(a.id, b.id);
    const va = value(a), vb = value(b);
    if (va == null || vb == null) return va == null && vb == null ? newest(a, b) : va == null ? 1 : -1;
    return (c.biggestFirst ? vb - va : va - vb) || newest(a, b);
  };
  return [...items].sort((a, b) => cmp(f.get(a)!, f.get(b)!));
}

/** RouteSort.encode: "LENGTH:down" (biggest first) / "LENGTH:up". */
export const encodeSort = (c: SortChoice) => `${c.key}:${c.biggestFirst ? 'down' : 'up'}`;
/** RouteSort.decode: nothing stored or unknown → the default. */
export function decodeSort(s: string | null | undefined): SortChoice {
  if (!s) return SORT_DEFAULT;
  const [k, d] = s.split(':');
  return SORT_KEYS.some((x) => x.key === k) ? { key: k as SortKey, biggestFirst: d !== 'up' } : SORT_DEFAULT;
}


const esc = (s: string) => s.replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]!);

/**
 * RouteSortChips: one row that scrolls sideways, the chosen chip green with ✓ and, for Länge / Dauer / Anstieg, an arrow
 * for its direction (↓ biggest first, ↑ smallest first). Each chip says what it does (the app's TalkBack texts).
 */
export function sortChipsHtml(c: SortChoice): string {
  return SORT_KEYS.map(({ key, dir, label }) => {
    const chosen = key === c.key, name = t(label);
    const direction = chosen && dir ? t(key === 'ASCENT' ? (c.biggestFirst ? 'routes_sort_most' : 'routes_sort_least')
      : c.biggestFirst ? 'routes_sort_longest' : 'routes_sort_shortest') : null;
    const spoken = direction != null ? t('routes_sort_chip_flip', name, direction) : chosen ? t('routes_sort_chip_on', name) : t('routes_sort_chip', name);
    return `<button type="button" class="sortchip${chosen ? ' on' : ''}" data-key="${key}" aria-pressed="${chosen}" aria-label="${esc(spoken)}" title="${esc(spoken)}">` +
      (chosen ? icon('check', 18) : '') + `<span>${esc(name)}</span>` +
      (direction != null ? `<span class="dir" data-dir="${c.biggestFirst ? 'down' : 'up'}">${icon(c.biggestFirst ? 'arrowDownward' : 'arrowUpward', 18)}</span>` : '') + '</button>';
  }).join('');
}
