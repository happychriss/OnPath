// The order and the search of Meine Touren (the app's RouteSort / RoutesTab.search + RouteSortChips, owner 2026-11-12
// variant C; owner 2026-11-13: "Der Planer braucht auch dieselben Suchen und Sortieren-Einstellungen wie das Handy").
// The order (keys, ties, the text form "LENGTH:down") and the search (every word, case / accents / ß folded) are the
// app's own code on the page (WebState.kt WebRoutesOrder, slices.ts) – nothing of it is copied here. The tap rule and
// „Neueste zuerst“ at each opening are the app's slice too (RoutesState.sort / open). One addition the app does not
// need, decided there as well: a plan this browser never routed has no numbers (planstats.ts) – it goes after those
// with numbers, in either direction, newest first among themselves. What stays here is the chip row's look (the app's
// RouteSortChips, web/parity.json). Robust first: without the slices the list keeps the stored order and shows no sort
// chips; the search then filters nothing – nothing is lost.
import { t } from './i18n';
import { icon } from './icons';
import { slice } from './slices';

export type SortKey = 'NEWEST' | 'OLDEST' | 'LENGTH' | 'DURATION' | 'ASCENT';
/** The sort as the slice holds it: RouteSort.encode's form ("" = the default). */
export type SortText = string;
interface SortChoice { key: SortKey; biggestFirst: boolean }

/** The chips' labels (RouteSortChips' strings) by RouteSort.Key. */
const LABELS: Record<SortKey, string> = {
  NEWEST: 'routes_sort_newest', OLDEST: 'routes_sort_oldest', LENGTH: 'routes_sort_length', DURATION: 'routes_sort_duration',
  ASCENT: 'routes_sort_ascent',
};

/** What the sort reads of a row: its date (the list's date), id and numbers (null = not known in this browser). */
export interface SortFacts { at: number; id: string; m: number | null; s: number | null; up: number | null }

const num = (v: number | null) => (v == null ? '' : String(v));

/** RouteSort.sort: [items] in the order of [sort] (a new list); null from the slice = the order given. */
export function sortRoutes<T>(items: T[], sort: SortText, facts: (x: T) => SortFacts): T[] {
  if (items.length < 2) return [...items];
  const fs = items.map(facts);
  // ties go by id (the higher first): the slice gets each id's place among them
  const rank = new Map([...new Set(fs.map((f) => f.id))].sort().map((id, i) => [id, i + 1] as const));
  const rows = fs.map((f) => [rank.get(f.id), Math.round(f.at), num(f.m), num(f.s), num(f.up)].join('\t')).join('\n');
  const order = slice<number[]>('routesSort', rows, sort);
  return order && order.length === items.length ? order.map((i) => items[i]) : [...items];
}

/** RoutesTab.search: every word of [query] is in the name; blank = everything (the list's order kept). */
export function searchNames<T>(items: T[], query: string, name: (x: T) => string): T[] {
  if (!query.trim() || !items.length) return items;
  const hits = slice<number[]>('routesSearch', items.map((x) => name(x).replace(/[\n\r]/g, ' ')).join('\n'), query);
  return hits ? hits.map((i) => items[i]) : items;
}

/** RouteSort.decode of [sort]; null without the slices. */
export const sortChoice = (sort: SortText): SortChoice | null => slice<SortChoice>('routesSortChoice', sort);

let keys: { key: SortKey; dir: boolean }[] | null = null;

const esc = (s: string) => s.replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]!);

/**
 * RouteSortChips: one row that scrolls sideways, the chosen chip green with ✓ and, for Länge / Dauer / Anstieg, an arrow
 * for its direction (↓ biggest first, ↑ smallest first). Each chip says what it does (the app's TalkBack texts). The
 * keys and their order are RouteSort.Key's (from the slice); none without it.
 */
export function sortChipsHtml(sort: SortText): string {
  keys ??= slice<{ key: SortKey; dir: boolean }[]>('routesSortKeys');
  const c = keys && sortChoice(sort);
  if (!keys || !c) return '';
  return keys.map(({ key, dir }) => {
    const chosen = key === c.key, name = t(LABELS[key]);
    const direction = chosen && dir ? t(key === 'ASCENT' ? (c.biggestFirst ? 'routes_sort_most' : 'routes_sort_least')
      : c.biggestFirst ? 'routes_sort_longest' : 'routes_sort_shortest') : null;
    const spoken = direction != null ? t('routes_sort_chip_flip', name, direction) : chosen ? t('routes_sort_chip_on', name) : t('routes_sort_chip', name);
    return `<button type="button" class="sortchip${chosen ? ' on' : ''}" data-key="${key}" aria-pressed="${chosen}" aria-label="${esc(spoken)}" title="${esc(spoken)}">` +
      (chosen ? icon('check', 18) : '') + `<span>${esc(name)}</span>` +
      (direction != null ? `<span class="dir" data-dir="${c.biggestFirst ? 'down' : 'up'}">${icon(c.biggestFirst ? 'arrowDownward' : 'arrowUpward', 18)}</span>` : '') + '</button>';
  }).join('');
}
