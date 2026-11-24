# Streichliste Tests – für den Owner zum Entscheiden ([umbau] 2026-11-20)

Nichts hiervon ist gestrichen. Die Zahlen hat ein Helfer aus den Dateien gezählt (Bilder nach Dateinamen); ob ein
Bildschirmfoto dasselbe mit abdeckt, ist die Aussage der Test-Prüfung vom 2026-11-06 und **nicht nachgeprüft**.
Stand heute: 73.289 Zeilen Tests / 4.350 Tests, 566 Bildschirmfotos.

## A. Tests, die nur Aussehen festhalten (Lage, Farbe, Schrift)

| Test | Was er bewacht | Deine Entscheidung dahinter | Ohne ihn | Zeilen / Tests | Empfehlung |
| --- | --- | --- | --- | --- | --- |
| Kartenknöpfe unten (`FooterPlacementTest`) | Die Knopfreihe rutscht nicht unter die Planleiste / über ☰ | keine | Rechenregel ohne anderen Wächter | 21 / 4 | behalten (klein, einziger Wächter) |
| Knopfspalte im Plan (`PlanControlsPlacementTest`) | Die Knöpfe springen nicht mit jeder Leistenstufe; Rückgängig steht unter der Spalte | 2026-11-06 („Knöpfe springen“), 2026-11-08 | Dein gemeldeter Fehler wäre unbewacht | 54 / 6 | behalten |
| Obere Leiste (`TopBarLayoutTest`) | Bei großer Schrift fällt erst das Etikett weg, der Titel bricht nie im Wort | keine | abgeschnittener Titel bei großer Schrift | 33 / 4 | behalten |
| Kompassfarbe (`CompassColourTest`) | Nadel blau statt rot, gut lesbar | 2026-11-07 „blau, nicht rot“ | rote oder blasse Nadel fiele erst im Bild auf | 31 / 3 | **streichen** – das Bild zeigt die Farbe |
| Tourleiste (`TourActionBarTest`) | Lesbarkeit (Kontrast) der Leistenfarben | 2026-11-06 „lesbare Farben“ | zu blasse Farben | 25 / 1 | behalten (einziger Kontrast-Wächter dort) |
| Farbregel (`ColourRuleTest`) | „Alles blau, Navigation grün“, Walnuss; liest den Quelltext | 2026-11-06, 11-08, 11-12 | Farbregel nur noch im Bild; bricht aber bei jedem Umbau der Zeilen | 89 / 5 | **streichen** – Bilder zeigen es, der Test stört bei Umbauten |
| Planfarben (`PlanColourTest`) | Farbwerte + Lesbarkeit der Planfarben | 2026-11-06, 11-07, 11-12 | Lesbarkeit unbewacht | 103 / 8 | **halb**: die Lesbarkeits-Prüfungen behalten, die festen Farbwerte streichen |
| Schriftgrößen (`FontSizeRuleTest`) | Keine von Hand gesetzte Schriftgröße; die fünf Stufen | Stilregel | Wildwuchs bei Schriftgrößen | 56 / 3 | behalten (billig, verhindert echten Wildwuchs) |

Alle acht gestrichen: 412 Zeilen, 34 Tests. Meine Empfehlung (Kompassfarbe, Farbregel, halbe Planfarben): rund 170 Zeilen,
12 Tests – wenig. **Ehrlich: bei den Pins ist kaum etwas zu holen; der Hebel sind die Bilder (B).**

Nicht Aussehen, aber ebenfalls trotz „streichen“-Urteil behalten – ich würde sie lassen: deine Wörter „MTB / E-MTB /
Gravelbike“ auf den Knöpfen, der Papierkorb-Hinweis ohne Pronomen, „Kompass zeigt in Fahrtrichtung“.

## B. Bildschirmfotos dünner (566 Bilder; 308 davon deutsch, 163 mit großer Schrift, keins dunkel)

| Vorschlag | Bilder weniger | Was dann nicht mehr auffällt | Empfehlung |
| --- | --- | --- | --- |
| 1. Große Schrift nur noch deutsch (englische Große-Schrift-Bilder weg) | ~61 | ein abgeschnittenes englisches Wort bei größter Schrift – Englisch ist kürzer, kleines Risiko | **ja** |
| 2. Englische Zwillinge der Menüs / Fenster weg (Warte-Fenster, Ortsmenüs, Mehrtage, Download …) | bis 36 | eine englische Textänderung auf genau diesen Bildschirmen | ja, aber erst nachsehen, ob ein anderes englisches Bild dieselbe Szene zeigt |
| 3. Deutsch normal weg, wo es dieselbe Szene deutsch in großer Schrift gibt | ~90 | ein Fehler, der nur bei normaler Schrift auftritt – selten, aber normale Schrift ist das, was fast alle sehen | **nein** |

1 + 2: rund 95 Bilder weniger (566 → ~470). Alle drei: ~187 (→ ~380).
Die eigentliche Last ist aber eine andere: wenn sich Fußleiste, Knopfspalte oder obere Leiste ändern, müssen ~170 ganze
Bildschirme neu aufgenommen werden. Das wird nur kleiner, wenn die Tests den Plan-Bildschirm über das Gerüst der App
zeichnen – das ist App-Code und gehört zu Phase 4 (wartet).

## Die Antworten des Owners (2026-11-20 nachmittags, einzeln gefragt in [umbau]s Chat)
1. Englische Bilder mit größter Schrift: **ja, streichen** (~61).
2. Englische Zwillinge der Menüs / Fenster: **ja, alle 36**.
3. Farb-Tests: **die drei Farb-Pins streichen** (Kompassfarbe, Farbregel ganz; bei den Planfarben nur die festen
   Farbwerte). Die Prüfungen auf Lesbarkeit (Kontrast) und alle Lage-Tests bleiben. Vorschlag 3 der Bilder (deutsch
   normal weg) wurde nicht gefragt – bleibt.

## Gebaut (2026-11-20, auf main)
566 → 505 Bilder (61 weniger: 27 englisch in größerer Schrift, 34 englische Zwillinge), Farb-Pins weg (rund 120 Zeilen).
Nicht gestrichen, weil die Bedingung „jede Szene behält ein Bild in größter Schrift / ein Bild überhaupt“ sonst bräche:
Einrichtung (12), Ruheansicht (5), Navigationsziel (1) – deutsch nur in normaler Schrift vorhanden; zwei Bilder der
Plan-Meldungen – deutsch gar nicht vorhanden. Wer die auch weghaben will: erst die deutschen Bilder aufnehmen.
Zurückgeholt nach der Review: die Regel „Navigations-Frage = grüner Knopf“ (Verhalten, kein Farbwert) steht jetzt in
`MessagesTest`. Nur noch im Bild bewacht: keine hellblauen Flächen, Papier als Grund jeder Fläche.
