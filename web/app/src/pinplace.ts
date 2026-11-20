// Where the found place's menu goes and which rows it has – the app's PinMenuPlace / PinMenuItems (owner 2026-11-13,
// "A mit Ausweich B") and PlaceAddMenu.Menu.emphasised, run as they are in the page's own Wasm instance (WebState.kt
// WebRules.pinMenu*, slices.ts); nothing of the rules is copied here. A picked search result gets the pin's hold menu,
// already open, above the pin (A); the map moves down first when that makes it fit; under the pin's tip when the map may
// not move; where it fits neither – decided by the menu's measured height – the same rows as a card at the bottom (B).
// Robust first: without the instance (not loaded) the menu is the card at the bottom with the add rows the engine gave,
// nothing tinted, the map not moved – every add still works (reported).
//
// All values in pixels of the map box: [top] / [bottom] = the free map between the top bar and the plan sheet.
import { slice } from './slices';

export type PinMenuKind = 'ABOVE' | 'BELOW' | 'CARD';
/** [panPx] > 0: the map moves its content down by this much first, so the menu fits above the pin. */
export interface PinMenuDecision { kind: PinMenuKind; panPx: number }
export type PinRow = 'START_HERE' | 'GO_HERE' | 'ADD' | 'ADD_STOP' | 'NEW_DESTINATION' | 'FAVOURITE' | 'HOME' | 'GOOGLE' | 'REMOVE_PIN';

let sizes: { gap: number; tipRoom: number } | null = null;
/**
 * PinMenuPlace.GAP_DP (between the pin's head and the menu) and TIP_ROOM_DP (kept under the pin's tip when the map
 * moves it down) in the page's px; 0 / 0 until the instance is there (the menu is the card then).
 */
export function pinSizes(): { gap: number; tipRoom: number } {
  sizes ??= slice<{ gap: number; tipRoom: number }>('pinMenuSizes');
  return sizes ?? { gap: 0, tipRoom: 0 };
}

/**
 * The app's PinMenuPlace.decide: [headY] top of the pin's head, [tipY] its tip (= the place), [menuH] the menu's
 * measured height, [mayPan] the map may move. The app's values are whole pixels: [menuH], [top], [bottom], [gap] and
 * [tipRoom] are rounded.
 */
export function decidePinMenu(headY: number, tipY: number, menuH: number, top: number, bottom: number, gap = pinSizes().gap,
  tipRoom = pinSizes().tipRoom, mayPan = true): PinMenuDecision {
  return slice<PinMenuDecision>('pinMenuDecide', headY, tipY, Math.round(menuH), Math.round(top), Math.round(bottom), Math.round(gap),
    Math.round(tipRoom), mayPan) ?? { kind: 'CARD', panPx: 0 };
}

/**
 * The app's PinMenuPlace.cardPan – B: how far the map moves its content up (a negative pixel count, 0 = not at all) so
 * the pin stays visible above a card whose top is at [cardTop]; never so far that the pin's head goes under the top bar.
 */
export const cardPan = (headY: number, tipY: number, cardTop: number, top: number, tipRoom = pinSizes().tipRoom): number =>
  slice<number>('pinMenuCardPan', headY, tipY, Math.round(cardTop), Math.round(top), Math.round(tipRoom)) ?? 0;

/**
 * The app's PinMenuItems.of: the rows of a held place's menu in their order – the same for a hold and for a picked
 * search result. [main] = the add entries (the engine's placeMenu – PlaceAddMenu; "ADD" or "SET_DESTINATION" for the one
 * add row), [pin] = the found place's pin (the menu ends with "Remove pin"), [keep] = the place can be kept („☆ Merken“,
 * „⌂ Als Zuhause festlegen“ after the route rows). Without the instance: the add rows alone.
 */
export function pinRows(main: string[], pin: boolean, keep = false): PinRow[] {
  return slice<PinRow[]>('pinMenuRows', main.join(','), pin, keep) ?? main.map((i): PinRow => (i === 'SET_DESTINATION' ? 'ADD' : i as PinRow));
}

/** The app's PlaceAddMenu.Menu.emphasised: the likelier add row, shown strong (tinted); null with one point or none. */
export const emphasised = (main: string[]): string | null => slice<string | null>('addMenuEmphasised', main.join(','));
