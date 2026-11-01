# Gesamtkonzept: wie OnPath künftig gebaut wird (Fassung 2, 2026-11-01)

Von der Sitzung „Setup review handoff“. Fakten: `docs/roles/setup-review-handoff.md`. **Nur ein Vorschlag –
nichts wurde umgestellt.** Fassung 2 nach deinen drei Hinweisen: meist Browser auf donald, vom Handy muss ein Fix
bis ins App-Update gehen, die Rechner sind austauschbare Rechen-Einheiten.

## Kurz
1. **Nicht Rollen, sondern Einheiten und Vorhaben.** Fünf Einheiten (donald, Laptop, GitHub Actions, Claude-Cloud,
   Cloud Run) mit festen Fähigkeiten. Jedes Vorhaben sagt, was es braucht (Handy, Emulator, viel RAM, Kartendaten
   oder nichts). Die Einheit, die das kann und an ist, nimmt es.
2. **Vom Handy ein Fix bis ins App-Update: geht heute schon.** Cloud-Sitzung → Code → Push auf `main` mit Version +
   Notizen → `release.yml` testet, baut, signiert, veröffentlicht → die App bietet das Update an. Donald ist dafür
   nicht nötig. Eine Lücke: ob die Cloud-Sitzung vorher selbst testen kann (siehe §3).
3. **Deine Fragen an einem Ort:** GitHub-Issues mit Label `frage` in `onpath.private`, immer gleich aufgebaut, von
   jeder Einheit mit einem kleinen Skript angelegt. Lesezeichen auf dem Handy = Dashboard.
4. **Kein Captain als Dauersitzung, kein Backlog-Postverkehr, ein Branch.** Der Release-Check wird ein Skript, der
   tägliche Abgleich fällt mit dem einen Branch weg.
5. **Alles Gebaute bleibt:** `gate.sh`, `risk_tests.py`, Release per Push, E2E mit Emulator-Snapshot, Web-Parity,
   `merge-main.sh`, In-App-Update, Problems-Log, `onpath.private`. Es wird nur anders zusammengesteckt.
6. **Nichts vor v0.14.0.** Nur die Fragen-Liste kann sofort anfangen.

## 1. Was du mir gesagt hast
- Die meiste Arbeit: Browser, Sitzung auf donald. Ausnahme: vom Handy – und dann muss ein Fix wirklich gebaut und
  über das In-App-Update verteilt werden können.
- donald hat zu wenig RAM, darum der Laptop. Die Rechner sind Rechen-Einheiten mit Hardware- und
  Software-Voraussetzungen, dynamisch zuteilbar – nicht fest „App“ und „Planer“.
- Nichts ändern, beobachten, ein Gesamtkonzept vorschlagen. Etwas Kleines bauen (Skript, MCP) wäre in Ordnung.

## 2. Was schon gebaut ist und bleibt
| Gebaut | Bleibt als |
| --- | --- |
| `tools/ci/gate.sh` + `risk_tests.py` (Tests nach geänderten Dateien, Gradle-Lock, 2 Worker) | **Das** Tor vor jedem Push, auf jeder Einheit gleich. |
| `release.yml`: Push auf `main` mit Version + Notizen → Tests, Smoke-Emulator, signierter APK, GitHub Release | **Der** Release-Weg – von jeder Einheit, auch vom Handy aus. |
| `update/AppUpdater.kt`: App liest das neueste GitHub Release, installiert in place | Das Ende des Handy-Fix-Wegs. Fertig. |
| `unit.yml` (alle Tests + Screenshots), `e2e.yml` (Emulator mit gecachtem Snapshot auf GitHub) | Emulator läuft auf GitHub – donald braucht ihn im Alltag nicht. Das löst das RAM-Problem zum Teil. |
| `web-spike.yml`: Engine, Seite, 5 Smoke-Gruppen, Deploy nach plan.onpath.earth | Bleibt – nach dem Zusammenlegen mit Pfad-Filter auf `main`. |
| `web/parity/check.mjs` + `merge-main.sh` + täglicher Abgleich 05:47 | Parity-Check bleibt (läuft in CI). Merge-Skript wird nach dem Zusammenlegen nicht mehr gebraucht. |
| Release-Rundgang (`release_rundgang.sh`, 6 min), optional | Bleibt optional, nur auf donald. |
| `tools/dev/push_cell.sh`, `publish_cells.sh`, Cloud Run Job, R2 TEST | Kartenbau = Cloud Run, Laptop als Ersatz. |
| Problems-Log + Diagnose-Export | Für Tester-Rückmeldungen. |
| `docs/roles/`, Working Agreement §8, Backlog-Kanäle, Captain-Freigabe | Werden zu einem Dokument `docs/roles/start.md` zusammengefasst; die Kanäle entfallen (§4f). |
| `onpath.private` (Marketing, Bilder, Ideen) | Bleibt; dazu die Fragen-Issues. |

## 3. Was ich geprüft habe
- **Commits:** 37 von 66 auf `main` in 24 h waren Backlog-, Übergabe- oder Captain-Notizen. Letzte 7 Tage: 67 Commits
  von 3 Autoren-Namen.
- **Branches:** `web-spike` 1157 Commits voraus, 33 zurück; Unterschied unter `app/` nur 74 Dateien, 580 Zeilen.
  Das Zusammenlegen ist ein einmaliger Merge (keine Umschreibung der Geschichte).
- **Captain:** seine Erinnerung ist um 00:24 mit `ACCESS_DENIED` gescheitert. Eine freigegebene Cloud-Sitzung kann
  sich nicht selbst wecken.
- **Sitzungen heute:** `claud-app` und `claud-plan` laufen bereits über `claude remote-control` (CLI) auf donald /
  Laptop. Auf dem Laptop gibt es zwei Umgebungen (`hp:onpath-plan:4225`, `:dbef`).
- **Hat eine Cloud-Sitzung schon eine Sitzung auf einem Rechner gestartet?** Ja: „Web-Planer: Kartenlook + Bug 01
  (donald)“ am 31.10. kam aus der Cloud-Sitzung „Web-Planer (Spikes)“ (`create_session` auf eine
  Bridge-Umgebung). Das ist die Basis für §4c „Verteiler“. Nicht erneut getestet (ändert etwas).
- **Claude-Cloud als Bau-Einheit:** Android-SDK in 12 s geladen (458 MB), 4 Kerne, 15 GB RAM, 30 GB Platz, kein
  KVM (kein Emulator). Gradle-Build: **noch offen** – erster Lauf scheiterte, weil Maven Central den Cloud-Proxy drosselt (HTTP 429); ein Wiederholungslauf mit Pausen lief beim Schreiben noch (Ergebnis wird nachgetragen).
- **GitHub-Issues:** im Repo an, 0 offen; von Cloud-Sitzungen über das GitHub-Werkzeug, von den Rechnern über `gh`
  erreichbar.
- **Das Handy:** `AppUpdater` liest `releases/latest`; `release.yml` läuft auf GitHub und braucht keinen Rechner von
  dir. `gate.sh` lässt eine Release-Commit (nur Version + Notizen) ohne lokalen Test durch, wenn `unit.yml` auf der
  Basis grün ist.

## 4. Das Konzept

### 4a. Rechen-Einheiten
| Einheit | Kann | Kann nicht | Wann aus |
| --- | --- | --- | --- |
| **donald** (30 GB, Pixel per USB, SDK) | Bauen, Unit + Screenshot-Tests, auf dem Pixel testen, Rundgang, Emulator (einzeln) | Gradle und Emulator gleichzeitig | nachts, unterwegs |
| **Laptop hp** (32 GB, Kartendaten, SDK?) | Bauen, Tests, Kartenbau, Planer-Smokes | Pixel (außer angesteckt) | nachts, unterwegs |
| **GitHub Actions** (kostenlos, immer an) | Volle Tests + Screenshots, Emulator-E2E, Release bauen + signieren, Planer bauen + deployen, Karten-Deploy | Pixel, lange interaktive Arbeit | nie |
| **Claude-Cloud** (4 Kerne, 15 GB, immer an) | Code schreiben, Docs, Website, Marketing, (Unit-Tests nach SDK-Setup – §3), Sitzungen anstoßen | Emulator, Pixel, Kartenbau | nie, aber jede Sitzung ist frisch |
| **Cloud Run Job** | Kartenzellen bauen | alles andere | nie |

Beide Rechner bekommen dieselbe Software (SDK, Node 22, Python, Karten-Tools, Gradle-Lock) und je einen Checkout
von `OnPath` auf `main`. Dann ist es egal, auf welchem die Sitzung läuft – Ausnahme Pixel und große Kartendaten.
Der Gradle-Lock pro Rechner (schon da) bleibt die einzige Rücksicht auf den RAM.

### 4b. Vorhaben statt Rollen
Das Backlog bleibt die To-do-Liste. Jedes Vorhaben bekommt ein Kürzel, was es braucht:

| Kürzel | Heißt | Einheit |
| --- | --- | --- |
| `[pixel]` | muss auf dem Handy geprüft werden | donald |
| `[emu]` | Emulator-E2E nötig | GitHub Actions (automatisch), sonst donald |
| `[karten]` | Kartenbau | Cloud Run, sonst Laptop |
| `[ram]` | großer Build (Wasm-Engine, Germany) | Laptop |
| *(nichts)* | Code, Tests, Docs, Website | jede Einheit, die an ist |

Die Rollen-Dateien werden ein Dokument `docs/roles/start.md`: ein Start-Satz pro Einheit, dieselben Regeln für
alle. Die Rolle einer Sitzung ergibt sich aus Einheit + Vorhaben, nicht aus einem Namen.

### 4c. Wie Sitzungen entstehen – drei Wege
1. **Du, im Browser auf donald (Normalfall):** `claude remote-control` im Checkout, eine Zeile: „Lies
   `docs/roles/start.md`, nimm das nächste Vorhaben ohne `[pixel]`“ – oder konkret, was du willst. Die Sitzung
   liest Backlog + offene Fragen und legt los. Stirbt sie mit dem Rechner: dieselbe Zeile noch einmal. Alles Wichtige
   liegt im Repo, nichts im Chat.
2. **Verteiler (optional, wenn du nicht selbst anstoßen willst):** Ein geplanter Auftrag in der Cloud (frische
   Sitzung, z. B. morgens 7:xx und 13:xx): liest Backlog, Fragen und CI, prüft, welche Einheit online ist
   (`list_environments`), startet eine Sitzung dort mit dem Start-Satz und dem Vorhaben (`create_session` mit der
   Bridge-Umgebung), und schreibt dir einen Push: „donald: Vorhaben X gestartet“. Keine Freigabe nötig: er liest
   keine fremden Transkripte und schickt keine Nachrichten – genau das, woran der Captain scheiterte.
3. **Vom Handy (Fix-Weg, §4d):** Cloud-Sitzung, auch ohne jeden Rechner.

### 4d. Der Handy-Fall von Anfang bis Ende
Du sitzt mit dem Handy auf einer Hütte, die App hat einen Fehler. Ablauf, alles schon vorhanden:
1. Neue Cloud-Sitzung: „Lies `docs/roles/start.md`. Bug: … Fix + Release.“
2. Die Sitzung schreibt Test + Fix auf `main`. Vor dem Push: `gate.sh` – **wenn die Cloud bauen kann** (§3); sonst
   pusht sie mit dem Hinweis „ungetestet“ und `unit.yml` testet auf GitHub.
3. Sie bumpt die Version, schreibt `docs/release-notes/vX.Y.Z.md`, pusht. `release.yml` testet (risikobasiert),
   startet den Smoke-Emulator, baut, signiert, veröffentlicht. Rot = kein Release, nichts kaputt.
4. Auf dem Handy: Einstellungen → Updates → installieren. Touren bleiben.
Dauer heute: ~15–25 min Rechnerzeit auf GitHub. Du musst nichts freigeben; Pull Requests nur bei Workflows / Keys
(wie heute).

Das Einzige, was fehlt: Punkt 2 sicher machen. Zwei Wege:
- **Setup-Skript der Cloud-Umgebung** lädt das SDK (12 s) + wärmt Gradle vor. Dann testet die Cloud-Sitzung wie
  donald. Hängt davon ab, ob Maven Central den Cloud-Proxy durchlässt (§3).
- **Oder** wir akzeptieren: Vom Handy wird „blind“ gepusht, GitHub testet. Für einen kleinen Fix reicht das, weil
  `release.yml` rot bleibt, wenn etwas bricht.

### 4e. Deine Fragen an einem Ort
**GitHub-Issues, Label `frage`, im privaten Repo `onpath.private`** (privat wegen Tester / Marketing). Alle
Einheiten erreichen GitHub. Ein Skript `tools/ask.sh` (neu, ~30 Zeilen) sorgt für dasselbe Format aus jeder
Sitzung:

```
tools/ask.sh "Debug-Build für echte Tester?" \
  --von donald --worum "Releases sind debuggable + Test-Key …" \
  --frage "Vor den Testern auf Release-Build umstellen?" \
  --a "Ja, mit dem Test-Key (Empfehlung)" --b "Nein, erst mit Play" \
  --details docs/reviews/2026-11-01-pre-release.md#D1
```

- Lesezeichen auf dem Handy: `github.com/happychriss/onpath.private/issues?q=is:open+label:frage`.
- Du antwortest mit „A“ als Kommentar. Jede Sitzung liest beim Start und nach jedem Pull die offenen Issues
  (`tools/ask.sh --offen`), handelt, schließt das Issue mit einer Zeile „gemacht: …“.
- Regel für alle: Eine Frage an dich gibt es nur als Issue. Im Chat steht höchstens „Frage #12 offen“.
- Die vier offenen Entscheidungen D1–D4 aus dem Review und die Liste „Waiting for the owner“ werden die ersten Issues.
- **Benachrichtigung – einmal prüfen:** Die Sitzungen schreiben unter deinem Konto; GitHub meldet dir eigene Issues
  nicht. Darum: (a) die Sitzung schickt einen Push (geht bei Remote-Control-Sitzungen), (b) der Verteiler (§4c)
  meldet „3 Fragen offen“, oder (c) du schaust morgens und abends. Ein Test-Issue zeigt es in einer Minute.
- Später, wenn gewünscht: eine kleine Artefakt-Seite, die dieselben Issues hübscher zeigt. Kein eigenes MCP nötig.

### 4f. Wie Sitzungen miteinander reden
Gar nicht direkt. Das Repo ist der Kanal – wie es die Working Agreement von Anfang an wollte:
- Backlog = To-do + Claims (wie heute), aber ohne „For the roles / For the captain“.
- Übergabe in `project-status.md` nur am Ende einer Sitzung (eine pro Sitzung, wie heute).
- Abstimmung pro Release: ein Issue „Release vX.Y.Z“ in `OnPath` mit Checkliste (Fixes, Check-Skript grün, go).
- Der Captain-Release-Check (Fingerprint, Sync-Format, Shared-Code-Tests) wird `tools/ci/release_check.sh` (neu,
  ~40 Zeilen). Mit einem Branch ist er fast trivial.
Damit entfallen: Captain-Sitzung, Freigabe-Karte, Einbahn-Nachrichten, 30+ Notiz-Commits pro Tag.

### 4g. Ein Branch
`web-spike` wird einmal in `main` gemergt (Planer unter `web/`). `web-spike.yml` läuft dann auf `main` mit
Pfad-Filter (`web/**`, Shared-Code-Dateien) – diese Workflow-Änderung ist ein PR an dich. Der tägliche Abgleich
05:47 wird ausgeschaltet. `web-spike` bleibt stehen (nicht löschen). `web/parity/check.mjs` läuft weiter in CI und
meldet, wenn eine gespiegelte App-Datei sich ändert – nur ist das dann im selben Commit sichtbar.

### 4h. RAM auf donald
- Emulator nicht lokal (E2E auf GitHub, Rundgang optional). Dann passt ein Gradle mit 2 Workern bequem.
- Laptop ist gleichrangig: ist donald voll, läuft das zweite Vorhaben dort. Das regelt der Gradle-Lock pro Rechner,
  keine Rolle.
- 64 GB für donald (deine offene Entscheidung) wird damit weniger dringend.

## 5. Was neu gebaut würde (klein, alles im Repo)
| Teil | Umfang | Zweck |
| --- | --- | --- |
| `tools/ask.sh` | ~30 Zeilen, `gh` | Frage als Issue im festen Format; `--offen` listet Antworten |
| `tools/ci/release_check.sh` | ~40 Zeilen | Captain-Check als Skript |
| `docs/roles/start.md` | 1 Seite | Start-Satz je Einheit, Vorhaben-Kürzel, Regeln (ersetzt 4 Rollen-Dateien + §8) |
| Verteiler-Auftrag (optional) | 1 Prompt | Sitzungen anstoßen, Fragen melden |
| Setup-Skript Cloud-Umgebung (optional) | ~10 Zeilen | SDK + Gradle-Cache, damit Cloud-Sitzungen testen |
Kein MCP, keine Datenbank, keine neue Seite.

## 6. Reihenfolge (das Release bleibt unberührt)
1. **Jetzt:** Label `frage` in `onpath.private`, Test-Issue, Benachrichtigung prüfen. D1–D4 als Issues.
   `tools/ask.sh` und die Regel in `CLAUDE.md` / `owner-preferences.md`.
2. **v0.14.0** mit dem heutigen Setup fertig machen.
3. **Danach:** `release_check.sh`; Captain-Sitzung beenden, seine Erinnerung löschen; `start.md` schreiben, Rollen
   und §8 darauf kürzen; Backlog-Kanäle entfernen.
4. **Eine ruhige Sitzung auf donald:** `web-spike` → `main` mergen, Workflow-PR an dich, Abgleich 05:47 aus.
5. Laptop gleich ausstatten wie donald (SDK, Node, Checkout `main`); doppelte Umgebung in der App aufräumen.
6. **Optional:** Cloud-Umgebung mit Setup-Skript; Verteiler-Auftrag.

## 7. Fragen, die nur du beantworten kannst
1. **Fragen-Ort:** Issues `frage` in `onpath.private` – ja?
2. **Benachrichtigung:** Push von der Sitzung, Sammel-Push vom Verteiler, oder du schaust selbst?
3. **Ein Branch** nach v0.14.0 – ja?
4. **Captain** nach dem Release beenden – ja?
5. **Verteiler-Auftrag** gewünscht, oder startest du Sitzungen lieber selbst mit einer Zeile?
6. **Handy-Fix:** Reicht „blind pushen, GitHub testet“, oder soll die Cloud selbst testen können (Setup-Skript)?
7. **Laptop:** gleich ausstatten wie donald, oder bleibt er nur die Karten-Einheit?

## 8. Was ich nicht geprüft habe
- `create_session` auf eine Bridge-Umgebung heute – nur die Spur vom 31.10. gesehen; ein Test ändert etwas.
- Ob GitHub dir eigene Issues meldet (ich vermute: nein).
- Ob der Laptop ein Android-SDK hat.
