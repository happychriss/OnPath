# Morgenbericht 28.11.2026 (Captain, 06:15)

**Kurz:** Die Nacht ist durch. App und Planer sind auf `main` abgestimmt, der Planer ist online, der Kartenbau ist fertig.
Release 0.18.12 ist fertig vorbereitet und wartet nur noch darauf, dass der Build auf deinem Pixel läuft. Nichts veröffentlicht.

## Fertig auf main (Merge-Reihenfolge der Nacht)
| Stufe | Was | Commit |
| --- | --- | --- |
| 1 | next-round-2 → main | 7cf8510d4 |
| 1b/1c | Planer: Nacht-Fix „auf der Route“ | 531afdba1, ef4f85e36 |
| 2 | Mehrtagestouren (Badge „📅 N Tage“, Tagesauswahl) | 13e6eb4b7 |
| 2b | Aktivitätsauswahl Variante C | bec36c639 |
| 3 | „Ziel suchen“ in leerer Kachel | 486763c7f |
| 4 / 5 | Aufräumen Planer / App | b5fe760d8 / 08416d63a |
| 6 | Schritt-Balken, eine Bewegung, Abstände | 9b0a247ce |
| 7 | Tagestabelle in der App (+ Parität) | e90b39462…cc5055f84, be5b5d3d8 |
| 8 | Position wandert im Navigationsmodus – Fix (E2E 115/0) | 659bbfba0…65d357660 |
| 9 | alte Aktivitätsauswahl im neuen Aussehen | 74e4f931a |
| – | keine Prozentzahl mehr in der App (Kartenzeichen, „12 von 40 MB“) | b811f069b, 67852bf6c |

## Planer
Parität fertig und online (a0e7358ca): Abstände, Kartenzeichen ohne Prozent, Badge „📅 N Tage“, runde Suchsymbole,
„Ziel suchen“. Nachtentscheidung: kein eigenes Tagesblatt im Planer, das Panel ist schon die Tagestabelle.

## Kartenbau
Fertig um 01:57: 724 Zellen, 0 übrig, keine neuen Fehler, nichts nachzuveröffentlichen.

## Release 0.18.12
Vorbereitet: E2E auf main 115/0 (Run 46088774870), `release_check` GO (App 67852bf6c, Planer a0e7358c), `gate.sh --full`
grün, Notes auf `release/0.18.12-notes` @ 51eff6569. Enthält den Positions-Fix (dein Wort 23:39).
**Nächster Schritt (Wartefenster-Thread):** Pixel einstecken → `adb install -r` (laufende Tour sichern) → kurz prüfen →
Version + Notes auf main → release.yml. Dafür brauche ich nur, dass das Pixel am Rechner hängt.

## Was die Rollen nachts selbst entschieden haben
Siehe `2026-11-27-night-answers.md` (7 Punkte, alle gegen VISION geprüft): z. B. Tagestabelle auf 360 dp zweizeilig
(„gesamt“ grau unter km), Planer-Wartefenster mit Schritt-Balken statt „77 %“, Kartenzeichen füllt nach Bereichen ohne Zahl.

## Offen danach
- App: Kopf der Mehrtagestour mit „Speichern“ + Zeile „Gesamte Reise“ (braucht MapScreen.kt, heiße Datei).
- `SacChips` ungenutzt → beim nächsten Aufräumen.
- Branch `feat/wait-step-bar` löschen nur mit deinem Ja (über `prune_branches.sh`).
