# Audit: Was die Setup-Sitzung gebaut hat – und was davon taugt (2026-11-05)

Vom Owner verlangt: „genau, schonungslos, offen, faktenbasiert“. Geprüft wird die Arbeit der Sitzung „Setup review
handoff“ (claude.ai/code/session_01167zC4tfZrPpQgArEgsVb4) vom 2026-11-01 bis 2026-11-05. Quellen: `git log` auf
`main`, die Issues in `onpath.private`, die Sitzungsliste und die geplanten Aufträge der Plattform, die Übergaben in
`docs/project-status.md`. Wo ein Satz eine Vermutung ist, steht „vermutlich“.

## 1. Kurzfazit
- **Ein Teil hat sich bewährt und wird benutzt:** Fragen als Issues (30 Issues, 15 erledigt, von 4 verschiedenen
  Absendern), die Hooks beim Start und vor dem Push, die Deny-Regeln, die kleine `CLAUDE.md` mit Regeln je Bereich, die
  Geräte-Sperre, der Gastgeber auf den Rechnern.
- **Ein großer Teil wurde gebaut und nie benutzt:** `[sofort]` (0-mal im Backlog), deshalb liefen `new_items.sh` und der
  Wecker-Hook ins Leere; der Autostart (`unit.sh install`) und das Sichern beim Ausschalten wurden nie installiert – die
  Rechner haben ihre Arbeit stattdessen von Hand auf `wip/`-Branches gesichert (7 liegen offen herum).
- **Drei Fehler von mir haben andere Sitzungen gebremst oder blockiert** (§4): der Push-Hook verlangte nach jedem
  Rebase einen neuen Torlauf, er sperrte Pushes ins private Repo, und die Geräte-Sperre verklemmte sich mit dem
  Rundgang.
- **Das größte Problem ist nicht ein einzelnes Teil, sondern der Takt:** In fünf Tagen habe ich das Zielbild viermal
  umgeworfen (Rollen → Einheiten mit Leitstand → „Leicht mit Captain“ → Captain mit Karte) und jedes Mal Dateien,
  Regeln und Fragen an dich erzeugt. 40 der 183 Commits auf `main` seit 11-01 sind von mir, 31 davon an einem Tag.

## 2. Zeitachse
| Tag | Was ich getan habe | Rückblick |
| --- | --- | --- |
| 11-01 vormittags | Konzept in vier Fassungen (v1, v2, v3, v3b) in vier Stunden, 6 Fragen an dich | zu schnell, zu viele Fassungen auf `main` |
| 11-01 mittags | `ask.sh`, `an-alle`, Regeln für Fragen | **gut**, wird benutzt |
| 11-01 nachmittags | `release_check.sh`, `save-on-shutdown.sh`, `unit.sh`, `device.sh`, neue Rollen-Dateien, 7 Tests auf donald | Tests gut; Skripte teils nie installiert |
| 11-01 abends | Analyse, Hooks, Stempel, Deny-Regeln, `CLAUDE.md`-Diät, eine Lern-Datei | Hooks und Diät gut; **Stempel und `reset --hard` fehlerhaft** (§4) |
| 11-02 | Umstieg auf „Leicht mit Captain“ (Leitstand verworfen), Push-Schalter, Namen, 10-Minuten-Schleife, **eine Stunde später** durch den Wecker-Hook ersetzt, `/fragen`, firstmate-Vergleich | Schleife war Wegwerfarbeit; Wecker nie ausgelöst |
| 11-03 | Testplan „Captain mit Karte“ | Test lief, Ergebnis: jeder Aufruf braucht deinen Tipp – im Alltag unbrauchbar |
| 11-05 | Gradle-Plätze, Stempel nach Rebase, Fotos je Klasse; Hook-Fehler beim privaten Repo behoben | behebt Bremsen, die zum Teil von mir stammen |

## 3. Inventar: jedes Teil mit Beleg und Urteil
| Teil | Zweck | Benutzt? (Beleg) | Urteil |
| --- | --- | --- | --- |
| `tools/ask.sh`, Labels `frage`/`an-alle` | Fragen an einem Ort | ja: 30 Issues (captain 19, donald/cloud-app 8, Cloud-Arbeiter 1), 15 geschlossen | **behalten** |
| `/fragen` (Skill) | Fragen nacheinander abarbeiten | kein Beleg | behalten (klein), ausprobieren |
| `an-alle` + `#push-sperre` | Wort an alle, Stopp | 2 Issues, davon 1 mein Test; nie als Sperre benutzt | behalten (kostet nichts), nicht ausbauen |
| Hook beim Start (`session-start.sh`) | Lage automatisch in den Kontext | ja, auf donald bestätigt | **behalten** |
| Push-Hook (`pre-bash.sh`) | kein Push ohne Tor, Handy nur über Sperre | ja – **aber zwei Fehler** (§4); jetzt behoben | behalten, beobachten |
| Deny-Regeln (`.claude/settings.json`) | Force-Push, `reset --hard`, Handy leeren unmöglich | aktiv seit 11-01 (vom Owner angewendet) | **behalten** |
| `CLAUDE.md`-Diät + `.claude/rules/` | weniger Lesestoff je Start | aktiv: `CLAUDE.md` 1 136 Wörter (vorher 3 851) | **behalten** |
| `tools/dev/device.sh` | ein Agent je Gerät | ja (2 Commits anderer); **verklemmte sich mit dem Rundgang**, von donald repariert (56403bb9) | behalten |
| `tools/dev/gradle_slot.sh` (11-05) | zwei Builds statt einem | neu, auf donald noch nicht erlebt | behalten, in einer Woche messen |
| `tools/ci/release_check.sh` | App ⇄ Planer prüfen | vermutlich ja: der Captain schrieb „GO v0.14.0 … CI green“ in Commits; kein direkter Beleg | behalten |
| `tools/dev/unit.sh run` | Gastgeber starten | ja, von Hand auf donald | behalten (`run`), `install` streichen oder endlich nutzen |
| `tools/dev/save-on-shutdown.sh` + Autostart | Sichern beim Ausschalten | **nie installiert**; stattdessen 7 handgemachte `wip/`-Branches | entscheiden: installieren oder löschen |
| `tools/dev/new_items.sh` + Wecker-Hook (`stop-wake.sh`) | Eiliges in Minuten | **`[sofort]` nie benutzt** – der Hook wartet nach jeder Antwort jeder Rechner-Sitzung eine Stunde lang und fragt minütlich GitHub, ohne je zu wecken | **entfernen**, bis echter Bedarf belegt ist |
| Stündliche Weckrufe der Rechner | weiterarbeiten ohne dich | ja: „donald: Runde“ und „hp: Runde“ laufen | behalten |
| `tools/dev/apply_permissions.py` | Deny-Regeln einspielen | einmal benutzt | erledigt, kann weg |
| Captain mit Karte | Aufträge direkt an die Rechner | Test ✓, aber **jeder Aufruf braucht deinen Tipp** | nur für Eiliges; nicht als Grundlage |
| Leitstand-Sitzung je Rechner | Arbeiter verteilen | gebaut, getestet, **verworfen** | Reste aus der Doku entfernen |
| 10-Minuten-Schleife | Eiliges holen | **eine Stunde gelebt** | weg (ist weg) |
| Rollen-Doku | Anleitung für alle Sitzungen | 13 Dateien, 13 152 Wörter; die 4 alten Rollen-Dateien liegen **neben** den neuen | **zusammenstreichen** (§6) |

## 4. Fehler, die ich gemacht habe – mit Folgen
1. **Push-Stempel an den Commit gebunden (11-01 bis 11-05).** Nach jedem `git pull --rebase` – also jedes Mal, wenn ein
   anderer Agent gepusht hatte – verlangte der Hook einen neuen Torlauf, der wieder am **einzigen** Gradle-Schloss
   anstand. Vermutlich ein wesentlicher Teil der Wartezeiten, über die du dich beklagt hast. Behoben 0f5c2cd6.
2. **Push-Hook sperrte Pushes ins private Repo** (seit 11-01; von donald am 11-03 als offener Punkt gemeldet, bis heute
   ungelöst). Behoben 174e9d76.
3. **Geräte-Sperre und Rundgang sperrten sich gegenseitig** – `device.sh emulator -- release_rundgang.sh` wartete
   ewig auf sich selbst; ein in der Sperre gestarteter adb-Server hielt sie danach fest. donald hat beides repariert
   (56403bb9). Ich hatte den Rundgang-Teil gebaut, ohne die Kombination zu testen.
4. **`git reset --hard` in einem eigenen Test** (11-01) verwarf meine eigene Änderung an `gate.sh` und die
   Hook-Registrierung. Kein Schaden auf GitHub, aber zwei Nacharbeits-Commits. Folge: Deny-Regel.
5. **Doku-Lawine.** Konzept, Analyse, Umstellung, Lernen, Testplan, neue Rollen, alte Rollen – 13 152 Wörter, die sich
   teils widersprechen (Leitstand steht noch drin, alte Rollen-Dateien gelten „bis zum Branch-Merge“). Genau die
   Lesekosten, die ich mit der `CLAUDE.md`-Diät senken wollte, habe ich an anderer Stelle neu erzeugt.
6. **Mechanismen auf Vorrat.** `[sofort]`, `new_items.sh`, Wecker-Hook, Autostart, Sichern-Dienst, Leitstand – gebaut,
   bevor ein echter Fall da war. Nichts davon wurde gebraucht; der Wecker-Hook kostet sogar laufend etwas.
7. **Zu viele Fragen an dich.** Fragen zu Varianten, Namen, Takt, Modus, Benutzer, Cloud, Rechnern – jedes Mal mit
   Fragebox. Viele davon hätte ich mit einem Messwert statt einer Frage beantworten können.
8. **Behauptet statt geprüft:** Ich habe die Karten-Variante als „zwei Dinge funktionierten schon 10-31“ verkauft – dass
   jeder Aufruf eine eigene Freigabe braucht, wusste ich nicht und habe es nicht vorher gesagt.
9. **„Ein Branch“ (deine Entscheidung 4 vom 11-01) ist nicht umgesetzt:** `web-spike` ist 562 Commits voraus, der
   tägliche Abgleich läuft weiter. Ich habe es auf „nach dem Release“ geschoben und dann nicht mehr verfolgt.

## 5. Was gut war
- **Kleine Tests vor großen Behauptungen**, wo ich sie gemacht habe: Gastgeber, Worktrees, Weckruf nach Neustart,
  Hooks auf donald, Sitzung starten aus der Cloud (nein) und von donald (ja). Diese Fakten stehen in
  `multi-agent-lernen.md` §2 und gelten weiter.
- **Fragen als Issues** haben die verstreuten Fragen eingesammelt; die Sitzungen benutzen es von selbst.
- **Erzwingen statt bitten** (Hooks, Deny) ist der richtige Weg – nur muss jeder Hook vorher in allen Kombinationen
  getestet sein, sonst trifft ein Fehler alle Sitzungen zugleich.

## 6. Was ich jetzt vorschlage (aufräumen, nichts Neues)
1. **Entfernen:** Wecker-Hook (`stop-wake.sh`) samt Eintrag in `.claude/settings.json`, `new_items.sh`, `[sofort]` aus
   der Doku; `apply_permissions.py`; alle Leitstand-Reste.
2. **Entscheiden, dann installieren oder löschen:** Sichern beim Ausschalten + Autostart. Ohne Installation sind sie toter
   Code; die Regel „vor dem Ausschalten pushen“ hat in der Praxis getragen (`wip/`-Branches).
3. **Doku auf zwei Dateien zusammenführen:** `docs/roles/start.md` (wie jede Sitzung arbeitet, ≤ 1 Seite) und
   `docs/roles/multi-agent-lernen.md` (geprüfte Fakten). Konzept, Analyse, Umstellung, Testplan und die vier alten
   Rollen-Dateien nach `docs/history/`. `CLAUDE.md` verweist nur noch auf `start.md`.
4. **„Ein Branch“ wirklich machen** (Entscheidung 4) oder ausdrücklich verwerfen – der Zwischenzustand kostet täglich.
5. **7 `wip/`-Branches:** die Rechner prüfen, was davon noch fehlt; du löschst den Rest.

## 7. Regeln für mich und jede Setup-Arbeit ab jetzt
- **Kein neuer Mechanismus ohne belegten Bedarf** (ein echter Fall im Backlog oder im Verlauf), und nach einer Woche
  ohne Nutzung wird er entfernt.
- **Jede Änderung an Hooks, `gate.sh` oder Sperren** wird vor dem Push in allen Kombinationen getestet (Worktree,
  Rebase, anderes Repo, Rundgang, Emulator), denn sie trifft alle Sitzungen zugleich.
- **Erst messen, dann fragen:** Wartezeiten, Nutzung, Fehler aus Verläufen und Git belegen, bevor ich dir eine Frage
  stelle.
- **Eine Datei je Thema, überschreiben statt anhängen**; Konzept-Fassungen gehören in den Chat, nicht auf `main`.
- **Höchstens eine Setup-Änderung pro Tag**, damit die Rechner-Sitzungen nicht jede Stunde neue Regeln lesen.
