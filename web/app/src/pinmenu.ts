// Where the found place's menu goes – the app's ui/PinMenuPlace (owner 2026-11-13, "A mit Ausweich B"), mirrored
// (web/parity.json): a picked search result gets the pin's hold menu, already open, above the pin (A); the map moves
// down first when that makes it fit; under the pin's tip when the map may not move; where it fits neither – decided by
// the menu's measured height – the same rows as a card at the bottom (B, name + address on top). Pure, no imports
// (test/pinmenu-check.mjs runs it in plain Node).
//
// All values in pixels of the map box: [top] / [bottom] = the free map between the top bar and the plan sheet.

export type PinMenuKind = 'ABOVE' | 'BELOW' | 'CARD';
/** [panPx] > 0: the map moves its content down by this much first, so the menu fits above the pin. */
export interface PinMenuDecision { kind: PinMenuKind; panPx: number }

/** Room kept under the pin's tip when the map moves it down (the place stays clearly above the sheet) – TIP_ROOM_DP. */
export const TIP_ROOM = 24;
/** Gap between the pin's head and the menu – GAP_DP. */
export const MENU_GAP = 22;

/**
 * The app's PinMenuPlace.decide: [headY] top of the pin's head, [tipY] its tip (= the place), [menuH] the menu's
 * measured height, [mayPan] the map may move.
 */
export function decidePinMenu(headY: number, tipY: number, menuH: number, top: number, bottom: number, gap = MENU_GAP,
  tipRoom = TIP_ROOM, mayPan = true): PinMenuDecision {
  const above = headY - gap - menuH;
  if (above >= top) return { kind: 'ABOVE', panPx: 0 };
  const need = Math.ceil(top - above);
  if (mayPan && tipY + need <= bottom - tipRoom) return { kind: 'ABOVE', panPx: need };
  if (tipY + gap + menuH <= bottom) return { kind: 'BELOW', panPx: 0 };
  return { kind: 'CARD', panPx: 0 };
}

/**
 * The app's PinMenuPlace.cardPan – B: how far the map moves its content up (a negative pixel count, 0 = not at all) so
 * the pin stays visible above a card whose top is at [cardTop]; never so far that the pin's head goes under the top bar.
 */
export function cardPan(headY: number, tipY: number, cardTop: number, top: number, tipRoom = TIP_ROOM): number {
  const want = cardTop - tipRoom - tipY;
  if (want >= 0) return 0;
  const most = top - headY; // the farthest up the head may go (≤ 0 when the head is below the top bar)
  return Math.min(0, Math.trunc(Math.max(want, most)));
}

export type PinRow = 'START_HERE' | 'GO_HERE' | 'ADD' | 'ADD_STOP' | 'NEW_DESTINATION' | 'FAVOURITE' | 'HOME' | 'GOOGLE' | 'REMOVE_PIN';

/**
 * The app's PinMenuItems.of: the rows of a held place's menu in their order – the same for a hold and for a picked
 * search result. [main] = the add entries (the engine's placeMenu – PlaceAddMenu; the planner has no tour, so an empty
 * plan gives Start here · Go here; with 2+ points while planning one row in a clear case, both „Einfügen“ and „Neues
 * Ziel“ only when near equal, the likelier first – owner 2026-11-15 / 2026-11-17; nothing under „⋯ Mehr“), [pin] = the found place's pin (the menu ends with "Remove pin"),
 * [keep] = the place can be kept (owner 2026-11-15, „Orte merken“): after the route rows „☆ Merken“ and „⌂ Als Zuhause
 * festlegen“ – what is about the place itself.
 */
export function pinRows(main: string[], pin: boolean, keep = false): PinRow[] {
  const rows: PinRow[] = main.map((i): PinRow => (i === 'SET_DESTINATION' ? 'ADD' : i as PinRow));
  if (keep) rows.push('FAVOURITE', 'HOME');
  rows.push('GOOGLE');
  if (pin) rows.push('REMOVE_PIN');
  return rows;
}

/**
 * „⌂ Zuhause“ shown – the app's HOME_ROW_SHOWN (ui/search/SearchScreen.kt), OFF for now (owner 2026-11-19: „so wichtig
 * ist das Zuhause eigentlich auch nicht. Können wir das erstmal abschalten, bis wir eine saubere Lösung haben“): no
 * Zuhause row in the empty search and no „Als Zuhause festlegen“ / „Zuhause entfernen“ in a place's menu. The stored
 * home place and its sync stay, „★ Gemerkt“ stays. true brings both rows back.
 */
export const HOME_ROW_SHOWN: boolean = false;

/** The app's shownMenuRows: a place menu's rows ([pinRows]) without HOME while [HOME_ROW_SHOWN] is off. */
export function shownMenuRows<T extends string>(rows: T[]): T[] {
  return HOME_ROW_SHOWN ? rows : rows.filter((r) => r !== 'HOME');
}

/**
 * The app's PlaceAddMenu.Menu.emphasised: the likelier add row, shown strong (tinted, owner 2026-11-15) – the first of
 * the two add rows; null with one point or none.
 */
export function emphasised(main: string[]): string | null {
  const first = main[0];
  return main.length === 2 && (first === 'ADD_STOP' || first === 'NEW_DESTINATION') ? first : null;
}
