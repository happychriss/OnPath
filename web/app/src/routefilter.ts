// Meine Touren's filters and the row's activity line (owner 2026-11-13: „Die Favoriten sind wirklich wichtig“ – the
// planner follows the app's My routes). Mirrored from the app (web/parity.json): ui/routes/Favourites.kt (the star on a
// row, the „☆ Favoriten“ pill: only the starred ones), ui/routes/ActivityFilter.kt (the chip after Favoriten, preset with
// the planning activity each time Meine Touren opens; its menu the six as a single choice and „Alle“ under a line; an
// activity shows exactly its own tours) and Screens.kt's ActivityLine („🚶 Wandern · T2 · ↻ Rundtour“). Both filters
// apply to the saved routes; Zuletzt (the plan worked on) always shows (app 82167d11). The words are the app's strings.
import { t } from './i18n';
import { icon, activityIcon } from './icons';
import type { Activity } from './engine/types';
import type { ActivityRow } from './activity';

/** ActivityFilter.MENU – the menu's order. */
export const ACT_MENU: readonly Activity[] = ['HIKE', 'BIKE_TOURING', 'BIKE_TREKKING', 'EBIKE', 'MTB', 'EMTB'];

/** ActivityFilter.keep: [activity] shows under [chosen] (null = „Alle“) – exactly that activity; unknown only under Alle. */
export const keepActivity = (activity: string | null | undefined, chosen: Activity | null) => chosen == null || activity === chosen;

/** Favourites.shown: all, or with the pill on only the starred ones. */
export const shownFavourites = <T extends { favourite?: boolean }>(list: T[], only: boolean): T[] => (only ? list.filter((x) => !!x.favourite) : list);

/** ActivityFilter.saved without the sort (routesort.ts does that): the saved rows the filters let through. */
export function filterSaved<T extends { favourite?: boolean; activity?: string | null }>(list: T[], onlyFavourites: boolean, act: Activity | null): T[] {
  return shownFavourites(list, onlyFavourites).filter((x) => keepActivity(x.activity, act));
}

/**
 * The „☆ Favoriten“ pill (the app keeps it while it runs – AppViewModel.routesFavouritesOnly, not in the prefs): here while
 * the page is open.
 */
let favouritesOnly = false;
export const favouritesPill = { get: () => favouritesOnly, set: (on: boolean) => { favouritesOnly = on; } };

const esc = (s: string) => s.replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]!);

/** FavouriteChip: on = light olive with a gold ★, off = the outline; it says what a tap does. */
export function favChipHtml(on: boolean): string {
  const spoken = t(on ? 'routes_fav_chip_on' : 'routes_fav_chip_off');
  return `<button type="button" class="sortchip favchip${on ? ' on' : ''}" aria-pressed="${on}" aria-label="${esc(spoken)}" title="${esc(spoken)}">` +
    `<span class="star${on ? ' on' : ''}">${icon(on ? 'star' : 'starOutline', 18)}</span><span>${esc(t('routes_fav_chip'))}</span></button>`;
}

const chipOf = (a: Activity, rows: ActivityRow[]) => rows.find((r) => r.key === a)?.chip ?? a;
const labelOf = (a: Activity, rows: ActivityRow[]) => rows.find((r) => r.key === a)?.label ?? a;

/** ActivityFilterChip: „🚶 Wandern ▾“ (selected, light olive) or „Alle ▾“ (the outline); a drop-down list to TalkBack. */
export function actChipHtml(chosen: Activity | null, rows: ActivityRow[]): string {
  const name = chosen ? chipOf(chosen, rows) : t('routes_act_all');
  const spoken = t('routes_act_chip_desc', name);
  return `<button type="button" class="sortchip actchip${chosen ? ' on' : ''}" aria-haspopup="listbox" aria-expanded="false" aria-label="${esc(spoken)}" title="${esc(spoken)}">` +
    (chosen ? activityIcon(chosen, 18) : '') + `<span>${esc(name)}</span>${icon('dropDown', 18)}</button>`;
}

/** ActivityFilterItems: the six (single choice, each with its sign and a radio), a line, „Alle“. */
export function actMenuHtml(chosen: Activity | null, rows: ActivityRow[]): string {
  const item = (key: string, sign: string, label: string, on: boolean) =>
    `<button type="button" class="actpick" role="option" data-act="${key}" aria-selected="${on}"><span class="asign">${sign}</span>` +
    `<span class="alabel">${esc(label)}</span><span class="radio${on ? ' on' : ''}" aria-hidden="true"></span></button>`;
  return ACT_MENU.map((a) => item(a, activityIcon(a, 22), labelOf(a, rows), a === chosen)).join('') +
    '<hr class="actsep">' + item('ALL', '', t('routes_act_all'), chosen == null);
}

/** FavouriteStar: filled gold = favourite, an outline in the muted ink = not; says what a tap does. */
export function starHtml(on: boolean, name: string): string {
  const spoken = t(on ? 'routes_fav_on' : 'routes_fav_off', name);
  return `<button type="button" class="favstar iconbtn${on ? ' on' : ''}" aria-pressed="${on}" aria-label="${esc(spoken)}" title="${esc(spoken)}">${icon(on ? 'star' : 'starOutline', 26)}</button>`;
}

/**
 * Screens.kt ActivityLine: „Wandern · T2“ / „Fahrrad“ / „Gravel“ … with the activity's sign (the app's chip labels), then
 * „· ↻ Rundtour“ for a round trip; '' when neither is known.
 */
export function activityLineHtml(activity: string | null | undefined, sac: number | undefined, loop: boolean, rows: ActivityRow[]): string {
  const a = ACT_MENU.includes(activity as Activity) ? (activity as Activity) : null;
  if (!a && !loop) return '';
  let h = '';
  if (a) {
    const text = a === 'HIKE' && sac != null && sac >= 1 && sac <= 5 ? t('activity_chip_with_level', chipOf(a, rows), sac) : chipOf(a, rows);
    h += `${activityIcon(a, 16)}<span class="atext">${esc(text)}</span>`;
  }
  if (loop) h += (a ? '<span class="dot"> · </span>' : '') + `${icon('loop', 16)}<span class="aloop">${esc(t('plan_round_trip'))}</span>`;
  return `<span class="actline muted small">${h}</span>`;
}
