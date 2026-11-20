// „⌂ Zuhause“ off (src/pinmenu.ts – the app's HOME_ROW_SHOWN / shownMenuRows, owner 2026-11-19, HomeRowHiddenTest).
// The menu's placement and rows are the app's shared code now (src/pinplace.ts → the engine, WebPinMenuTest).
// Plain Node: node test/pinmenu-check.mjs
const P = await import('../src/pinmenu.ts');
let bad = 0;
const eq = (a, b, what) => { const ok = JSON.stringify(a) === JSON.stringify(b); console.log((ok ? 'ok   ' : 'FAIL ') + what + (ok ? '' : ` – ${JSON.stringify(a)} ≠ ${JSON.stringify(b)}`)); if (!ok) bad++; };

// the rows still know „⌂ Zuhause“, the shown rows drop it – „★ Merken“ stays
eq(P.HOME_ROW_SHOWN, false, 'the Zuhause switch is off');
eq(P.shownMenuRows(['START_HERE', 'GO_HERE', 'FAVOURITE', 'HOME', 'GOOGLE', 'REMOVE_PIN']), ['START_HERE', 'GO_HERE', 'FAVOURITE', 'GOOGLE', 'REMOVE_PIN'], 'no „Als Zuhause festlegen“, „Merken“ stays');
eq(P.shownMenuRows(['ADD_STOP', 'NEW_DESTINATION', 'FAVOURITE', 'HOME', 'GOOGLE']), ['ADD_STOP', 'NEW_DESTINATION', 'FAVOURITE', 'GOOGLE'], 'a map POI: no „Als Zuhause festlegen“ either');
process.exit(bad ? 1 : 0);
