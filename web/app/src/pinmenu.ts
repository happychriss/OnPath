// „⌂ Zuhause“ shown or not – the app's HOME_ROW_SHOWN / shownMenuRows (ui/search/SearchScreen.kt, an app file the
// engine does not compile: mirrored, web/parity.json). Where the found place's menu goes and its rows are the app's
// shared code (pinplace.ts). Pure, no imports (test/pinmenu-check.mjs runs it in plain Node).

/**
 * „⌂ Zuhause“ shown – the app's HOME_ROW_SHOWN (ui/search/SearchScreen.kt), OFF for now (owner 2026-11-19: „so wichtig
 * ist das Zuhause eigentlich auch nicht. Können wir das erstmal abschalten, bis wir eine saubere Lösung haben“): no
 * Zuhause row in the empty search and no „Als Zuhause festlegen“ / „Zuhause entfernen“ in a place's menu. The stored
 * home place and its sync stay, „★ Gemerkt“ stays. true brings both rows back.
 */
export const HOME_ROW_SHOWN: boolean = false;

/** The app's shownMenuRows: a place menu's rows (pinplace.ts pinRows) without HOME while [HOME_ROW_SHOWN] is off. */
export function shownMenuRows<T extends string>(rows: T[]): T[] {
  return HOME_ROW_SHOWN ? rows : rows.filter((r) => r !== 'HOME');
}
