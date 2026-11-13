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

// the rows (PinMenuItems.of): a hold's rows, "Remove pin" after Google, "More" last
eq(P.pinRows(['START_HERE', 'GO_HERE'], [], true), ['START_HERE', 'GO_HERE', 'GOOGLE', 'REMOVE_PIN'], 'empty plan');
eq(P.pinRows(['SET_DESTINATION'], [], true), ['ADD', 'GOOGLE', 'REMOVE_PIN'], 'one point');
eq(P.pinRows(['ADD_STOP'], ['NEW_DESTINATION'], true), ['ADD_STOP', 'GOOGLE', 'REMOVE_PIN', 'MORE'], 'a clear stop: its alternative behind More');
eq(P.pinRows(['ADD_STOP'], ['NEW_DESTINATION'], true, true), ['ADD_STOP', 'GOOGLE', 'REMOVE_PIN', 'NEW_DESTINATION'], 'More clicked');
eq(P.pinRows(['ADD_STOP', 'NEW_DESTINATION'], [], false), ['ADD_STOP', 'NEW_DESTINATION', 'GOOGLE'], 'a map POI: no Remove pin');
process.exit(bad ? 1 : 0);
