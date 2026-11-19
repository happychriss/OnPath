// The found place's menu placement and rows (src/pinmenu.ts) against the app's own cases – app/src/test/…/ui/
// PinMenuPlaceTest.kt (owner 2026-11-13, "A mit Ausweich B"), the same numbers. Plain Node: node test/pinmenu-check.mjs
const P = await import('../src/pinmenu.ts');
let bad = 0;
const eq = (a, b, what) => { const ok = JSON.stringify(a) === JSON.stringify(b); console.log((ok ? 'ok   ' : 'FAIL ') + what + (ok ? '' : ` – ${JSON.stringify(a)} ≠ ${JSON.stringify(b)}`)); if (!ok) bad++; };
// the free map 300..1500 px, gap 58, tip room 63 (2.625 px per dp)
const decide = (tip, menuH, mayPan = true) => P.decidePinMenu(tip - 105, tip, menuH, 300, 1500, 58, 63, mayPan);

eq(decide(1200, 700), { kind: 'ABOVE', panPx: 0 }, 'a menu that fits above stays there, the map still');
eq(decide(900, 700), { kind: 'ABOVE', panPx: 263 }, 'the map moves down so the menu fits above');
eq(decide(900, 1000).kind, 'CARD', 'the map never pushes the pin under the sheet (900)');
eq(decide(1100, 1000).kind, 'CARD', 'the map never pushes the pin under the sheet (1100)');
eq(decide(400, 700, false), { kind: 'BELOW', panPx: 0 }, 'the map may not move: under the pin when that fits');
eq(decide(400, 700).kind, 'ABOVE', 'the map may move: above it, after the move');
eq(decide(900, 1300), { kind: 'CARD', panPx: 0 }, 'the largest font: the card');
eq(decide(900, 1300, false), { kind: 'CARD', panPx: 0 }, 'the largest font, no move: the card');
eq(decide(900, 400).kind, 'ABOVE', 'decided by height, not by font scale');
eq(P.cardPan(1295, 1400, 1300, 300, 63), -163, 'the card keeps the pin visible');
eq(P.cardPan(795, 900, 1300, 300, 63), 0, 'already above the card: the map stays');
eq(P.cardPan(350, 455, 400, 300, 63), -50, 'never so far that the head goes under the top bar');

// the rows (PinMenuItems.of): a hold's rows, "Remove pin" after Google; owner 2026-11-15: both add rows, the likelier
// first, nothing under „⋯ Mehr“
eq(P.pinRows(['START_HERE', 'GO_HERE'], true), ['START_HERE', 'GO_HERE', 'GOOGLE', 'REMOVE_PIN'], 'empty plan');
eq(P.pinRows(['SET_DESTINATION'], true), ['ADD', 'GOOGLE', 'REMOVE_PIN'], 'one point');
eq(P.pinRows(['ADD_STOP', 'NEW_DESTINATION'], true), ['ADD_STOP', 'NEW_DESTINATION', 'GOOGLE', 'REMOVE_PIN'], 'a clear stop: both, Insert first');
eq(P.pinRows(['NEW_DESTINATION', 'ADD_STOP'], true), ['NEW_DESTINATION', 'ADD_STOP', 'GOOGLE', 'REMOVE_PIN'], 'far: both, New destination first');
eq(P.pinRows(['ADD_STOP', 'NEW_DESTINATION'], false), ['ADD_STOP', 'NEW_DESTINATION', 'GOOGLE'], 'a map POI: no Remove pin');
eq(P.pinRows(['START_HERE', 'GO_HERE'], true, true), ['START_HERE', 'GO_HERE', 'FAVOURITE', 'HOME', 'GOOGLE', 'REMOVE_PIN'], 'kept places (owner 2026-11-15): after the route rows, before Google');
// „⌂ Zuhause“ off for now (owner 2026-11-19, the app's HOME_ROW_SHOWN = false, HomeRowHiddenTest): pinRows still knows
// the row, the shown rows drop it – „★ Merken“ stays
eq(P.HOME_ROW_SHOWN, false, 'the Zuhause switch is off');
eq(P.shownMenuRows(P.pinRows(['START_HERE', 'GO_HERE'], true, true)), ['START_HERE', 'GO_HERE', 'FAVOURITE', 'GOOGLE', 'REMOVE_PIN'], 'no „Als Zuhause festlegen“, „Merken“ stays');
eq(P.shownMenuRows(P.pinRows(['ADD_STOP', 'NEW_DESTINATION'], false, true)), ['ADD_STOP', 'NEW_DESTINATION', 'FAVOURITE', 'GOOGLE'], 'a map POI: no „Als Zuhause festlegen“ either');
// the likelier row is tinted (PlaceAddMenu.Menu.emphasised)
eq(P.emphasised(['ADD_STOP', 'NEW_DESTINATION']), 'ADD_STOP', 'Insert first: Insert tinted');
eq(P.emphasised(['NEW_DESTINATION', 'ADD_STOP']), 'NEW_DESTINATION', 'New destination first: it is tinted');
eq(P.emphasised(['SET_DESTINATION']), null, 'one point: nothing tinted');
eq(P.emphasised(['START_HERE', 'GO_HERE']), null, 'empty plan: nothing tinted');
process.exit(bad ? 1 : 0);
