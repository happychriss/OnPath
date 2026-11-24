# Skizze: Zeile „Ganz neu berechnen“ im Plan-Menü (☰)

Nichts davon ist ausgeliefert. Links jeweils „heute“, rechts „mit Zeile“.

Was die Zeile tut: Wenn du einen Punkt nahe an einer langen Etappe setzt, behält die App die alte Route und rechnet nur
einen kurzen Umweg durch den neuen Punkt (schnell, ca. 0,3 s statt 4–15 s). Dabei liegen unsichtbare Zwischenpunkte auf
der alten Linie. „Ganz neu berechnen“ wirft diese weg und rechnet die sichtbaren Punkte des Plans komplett neu (langsamer,
die Route kann etwas anders und eventuell besser werden). Die Zeile erscheint nur, solange der Plan solche Umwege (oder
Schnittpunkte) enthält; auf der Skizze ist sie immer da.

Bilder (hell; dunkel gibt es in den Screenshot-Tests nicht):
- `menu-GermanTest.png` – Deutsch, normale Schrift
- `menu-GermanLargeTest.png` – Deutsch, größte Schrift der Tests (2,0)
- `menu-EnglishTest.png` – Englisch, normale Schrift
- `menu-EnglishLargeTest.png` – Englisch, Schrift 2,0

Letzte Zeile des Menüs, Stil wie die anderen (Symbol ↻, ein Text, keine Erklärzeile). Bei Schrift 2,0 bricht der Text wie
„Nach Updates suchen“ in zwei Zeilen um, nichts ist abgeschnitten.

Englisch, zur Wahl:
- Recalculate from scratch (in der Skizze)
- Recalculate fully
- Route from scratch

Empfehlung: „Ganz neu berechnen“ / „Recalculate from scratch“ – sagt am klarsten, dass die Umwege verworfen werden.
