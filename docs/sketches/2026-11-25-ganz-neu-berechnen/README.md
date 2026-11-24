# Skizze: Zeile „Ganz neu berechnen“ im Plan-Menü (☰)

Nichts davon ist ausgeliefert. Links jeweils „heute“, rechts „mit Zeile“.

Was die Zeile tut: Wenn du einen Punkt nahe an einer langen Etappe setzt, behält die App die alte Route und rechnet nur
einen kurzen Umweg durch den neuen Punkt (schnell, ca. 0,3 s statt 4–15 s). Dabei liegen unsichtbare Zwischenpunkte auf
der alten Linie. „Ganz neu berechnen“ wirft diese weg und rechnet die sichtbaren Punkte des Plans komplett neu (langsamer,
die Route kann etwas anders und eventuell besser werden). Die Zeile erscheint nur, solange der Plan solche Umwege (oder
Schnittpunkte) enthält; auf der Skizze ist sie immer da.

Zwei Varianten (hell; dunkel gibt es in den Screenshot-Tests nicht), je links „heute“, rechts „mit Zeile“:
- Variante A: Zeile ganz unten in der App-Gruppe, unter „Nach Updates suchen“, Symbol ↻ (sieht aus wie „Mit Browser koppeln“).
  `menu-A-German.png`, `menu-A-GermanLarge.png` (Schrift 2,0), `menu-A-English.png`, `menu-A-EnglishLarge.png`
- Variante B: Zeile in der Plan-Gruppe, direkt nach „Neu planen“ vor „Nur aufzeichnen“, Symbol „Route“ (Weg mit zwei Punkten,
  keine Pfeile). `menu-B-German.png`, `menu-B-GermanLarge.png` (Schrift 2,0), `menu-B-English.png`

Stil wie die anderen Zeilen (Symbol, ein Text, keine Erklärzeile). Bei Schrift 2,0 bricht der Text wie die anderen in zwei
Zeilen um, nichts ist abgeschnitten.

Englisch, zur Wahl:
- Recalculate from scratch (in der Skizze)
- Recalculate fully
- Route from scratch

Empfehlung: Variante B – eine Plan-Aktion gehört in die Plan-Gruppe, und das Symbol wird nicht mit der Browser-Kopplung verwechselt.
Text: „Ganz neu berechnen“ / „Recalculate from scratch“ – sagt am klarsten, dass die Umwege verworfen werden.
