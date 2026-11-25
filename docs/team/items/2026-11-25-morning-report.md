# Morgenbericht Mittwoch 25.11.2026 (Nachtkapitän, Nacht 24.→25.11.)

Kurz: **0.18.10 ist fertig bis zum letzten Schritt, alle Tests grün, nichts veröffentlicht.** main ist grün.
Fünf Rollen haben gearbeitet. Drei Sitzungen bekamen nachts eine frische Sitzung, weil ihr Kontext voll war:
donald, umbau und aufräumen. Der Laptop war ab ca. 23:30 nicht erreichbar, hp war offline.

## 0.18.10 – bereit, wartet auf dein Wort
- Zweig `ci/donald-2026-11-25-release-0.18.10b` (29a844f67) = main + Version + englische Notes.
- Volle E2E **114/114**, Rundgang **18/18**, `release_check` **GO**.
- Veröffentlichen: dein Wort **in donalds Chat**. Dann laufen Build, Tag und Planer-Deploy automatisch.
- Deutscher Text: [items/2026-11-25-release-0-18-10-notes-de.md](2026-11-25-release-0-18-10-notes-de.md).

**Was drin ist:**
- **„Standort teilen“ für alle**: Teilen-Knopf und ⚙ in der Tour-Blase, so wie du es gestern Abend gewählt hast.
  - Ein Blocker wurde nachts gefunden und behoben: Beim ersten Tippen auf Teilen ohne gespeicherten Namen kam nichts.
  - Die Bildschirme rund um Navigate, an denen sich etwas geändert hat, sind voll per E2E geprüft.
- **GPS ruht, wenn das Handy still liegt**: Der Bewegungssensor entscheidet mit, nach 1 h Stillliegen geht das GPS aus.
  - Auf einer echten Tour ist das noch **nicht geprüft**. Die Test-Version ist seit gestern 23:03 auf deinem Pixel.
- **Umwege in deinen Worten**: „Umweg 17,4 km notwendig, ② erst ab Wandern T2 erreichbar“, dazu die Begründung
  „② macht 2,8 km Umweg – mit Wandern T2 kürzer“.
- **„Alten Plan wiederherstellen?“**: Die Box kommt, wenn ein neuer Plan den alten ersetzt. Bild und Text hattest du freigegeben.
- **Zuletzt** behält Aktivität und T-Level eines ersetzten Plans.
- Pläne mit Umweg-Punkten aus dem Browser-Planer öffnen sich in der App und werden durch diese Punkte geführt.
- Robustheit, alle zuerst mit einem Test gefunden, dann behoben:
  - Ein einzelner GPS-Sprung macht kein falsches „abseits“ und keine falsche Ankunft mehr.
  - Eine zurückgestellte Uhr friert „noch“ nicht mehr ein.
  - Eine abgebrochene Tour zählt beim Speichern ihre ganze Strecke.
  - Speicher voll: Die Warnung geht weg, sobald wieder Platz ist.
  - Plan-Abgleich bei schwacher Verbindung: keine „(Kopie)“ mehr, und ein abgerissenes Netz gilt als offline statt als Serverfehler.
- Planer: Das Warte-Fenster sperrt die Karte, wie in der App. Die Planer-Änderungen liegen auf main, sind aber
  **noch nicht online**; sie gehen mit dem 0.18.10-Release online.

## Was sonst nachts fertig wurde
- **Website-Bilder „Standort teilen“** (tracking): O01–O06, DE + EN, nur Testdaten.
  - Sie liegen in `onpath.private/marketing/bilder/aktuell/`, mit einer Backlog-Zeile für cloud-web zum Einbauen.
- **Umweg-Writers** (umbau): W0–W3 sind gebaut, zweimal geprüft und korrigiert.
  - Liegen auf `wip/umbau-2026-11-24-stitch-writers` (9aeed35b1), **nicht auf main**.
  - SameRouteTest (gleiche Route wie auf einem frischen Handy): 108/108 grün.
  - Es fehlt nur noch die Menüzeile „Ganz neu berechnen“ (W4); sie wartet auf deine Wahl, siehe unten.
- **Harte Fälle als Tests** (aufräumen): Speicher voll, Schema-Upgrade mitten in einer Tour, Stromsparmodus, Uhrsprung.
  - Dabei wurden mehrere echte kleine Fehler gefunden und behoben, alle zuerst mit Test.
  - Freigegebener Platz auf donald: 19 GB Build-Reste.
- Ein E2E-Test war rot, weil brouter.de am 24.11. neue Routing-Daten erzeugt hat; ein App-Fehler war es nicht. Nur der Test wurde angepasst.

## Brauchen dein Wort
1. **0.18.10 veröffentlichen?** In donalds Chat.
2. **„Ganz neu berechnen“: Platz A oder B**, und das englische Wort (Empfehlung B, „Recalculate from scratch“).
   [Bilder](https://github.com/happychriss/OnPath/blob/sketch/umbau-2026-11-25-ganz-neu-berechnen/docs/sketches/2026-11-25-ganz-neu-berechnen/README.md)
3. **hp aufräumen**: Liste nur gelesen, nichts gelöscht.
   - 80 Worktrees und 62 Branches können weg, 3 Worktrees und 1 Stash sind unklar.
   - [items/2026-11-24-cleanup-inventory-laptop.md](2026-11-24-cleanup-inventory-laptop.md). Ein Ja reicht.
4. **POI-Test mit 0.18.9**: Reagiert ein langes Drücken auf ein POI-Symbol jetzt? Dein letzter Export war noch von 0.18.8.
   Wenn du es probierst: danach bitte einen Export unter Einstellungen → Diagnose.
5. Kleinere Punkte aus „Standort teilen“:
   - Die Überschrift der Zuschauer-Seite heißt noch „Live-Tour“; Empfehlung: „Geteilter Standort“.
   - Dein Wunsch „wann kommt der nächste Punkt?“: 3 Bilder sind fertig, Empfehlung V1 (dünner Balken + „nächster Punkt in 1:20“).
6. Zwei Pull Requests, die Regeln für Workflow und Build-Config betreffen, darum fragen wir vorher:
   - Ein Retry für die Playwright-Installation.
   - Robolectric für einen Test nach dem Neustart.

## Antworten der Nacht, die du kippen kannst
Alle stehen in [items/2026-11-24-night-answers.md](2026-11-24-night-answers.md). Die wichtigsten:
- „Standort teilen“ bleibt für alle an, nach deinem „Einbauen + für alle an“. Ein Satz „aus“ genügt.
- Die Release-Notes bleiben englisch, weil das Update-Fenster sonst doppelt so lang wird; Deutsch steht im Item.
- Umweg-Writers: Sie gleichen nicht während einer Tour ab, nur im Plan-Modus. Eine Strecke, die nie klappt, wird zweimal versucht und dann in Ruhe gelassen.
- „Ganz neu berechnen“ während einer Tour: Die Zeile ist ausgeblendet.
- Der kleine Uhrfehler bleibt offen: „unterwegs“ ist 1 h zu lang, wenn die Uhr von Hand **vor**gestellt wurde. Die Sommerzeit betrifft das nicht.

## Wer woran
- **donald**: Nachtliste fertig; wartet auf dein Wort zu 0.18.10. Die übrigen Punkte brauchen dich oder das Pixel.
  Das Pixel war nachts nicht angeschlossen, darum gibt es kein Log.
- **laptop**: Ab ca. 23:30 nicht erreichbar (hp offline).
  - Offen: PR 23 (alle Planer-Smokes auf GitHub); ein Smoke ist rot (sheet-sync).
  - Danach: die Planer-Seite für „Alten Plan wiederherstellen“ und die Writers.
- **umbau**: fertig; W4 wartet auf deine A/B-Wahl.
- **aufräumen**: fertig; als Nächstes kommt der Robolectric-PR auf dein Ja.
- **tracking**: fertig; danach der Countdown für die Zuschauer-Seite, wenn du Ja sagst.
- **donald-Speicher**: 90 % belegt, in Ordnung.
