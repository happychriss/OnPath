# Gesamtkonzept: wie OnPath künftig gebaut wird (Fassung 3b, 2026-11-01)

Von der Sitzung „Setup review handoff“. Fakten: `docs/roles/setup-review-handoff.md`. **Nur ein Vorschlag –
nichts wurde umgestellt.** Fassung 3 nach deinen Antworten: ein Captain, dem du nur „du bist mein OnPath-Captain“
sagst; keine Sitzung von Hand starten; Fragen und Meinungsänderungen müssen sicher ankommen; Emulator lokal.

## Kurz
1. **Ein Captain, der wirklich funktioniert.** Eine Cloud-Sitzung „OnPath-Captain“, die du anspricht wie heute – aber
   **ohne Freigabe-Karte**. Dann kann sie sich selbst wecken (heute geprüft: ja), Arbeit verteilen, Fragen
   einsammeln und dir melden. Sie schreibt keine Sitzung direkt an – das braucht sie nicht, weil GitHub der Kanal
   wird.
2. **GitHub-Issues sind der Kanal – in beide Richtungen.** `frage` = eine Sitzung fragt dich. `an-alle` = dein Wort
   an alle Sitzungen (Regel, Prioritätswechsel, Stopp …): jede Sitzung liest es beim Start, nach jedem Pull und vor
   jedem Push; steht `#push-sperre` drin, verweigert `gate.sh` den Push. Du schreibst es selbst oder sagst es dem
   Captain.
2b. **Je Rechner eine stehende Sitzung, die nicht stirbt.** `claude remote-control --name donald` beim Anmelden
   (Autostart); nach einem Neustart hängt sich der Befehl mit `--session-id` an **dieselbe** Sitzung – kein neues
   Anlegen, kein Start-Text. Das ist dein heute beobachteter zuverlässiger Weg, nur automatisiert.
3. **Rechen-Einheiten statt Rollen.** donald, Laptop, GitHub Actions, Claude-Cloud, Cloud Run – jedes Vorhaben sagt,
   was es braucht. Der Captain legt die Sitzung dort an, wo es passt und was an ist. Emulator: donald zuerst,
   GitHub nur ohne Rechner.
4. **Fix vom Handy bis ins App-Update: geht.** Captain → Sitzung (donald, sonst Cloud) → Push auf `main` mit
   Version → `release.yml` → App bietet das Update an. Die Cloud kann mit vorgewärmtem Gradle-Cache sogar selbst
   bauen und testen (heute geprüft: APK + Unit-Test gebaut).
5. **Alles Gebaute bleibt** (`gate.sh`, Release per Push, E2E, Parity, Updater, `onpath.private`). Neu sind drei
   kleine Skripte, eine Start-Datei und der Captain-Prompt. Kein MCP.
6. **Nichts vor v0.14.0.** Fragen- und `an-alle`-Issues können sofort anfangen.

## 1. Was du mir gesagt hast
- Normalfall: Browser, Sitzung auf donald. Ausnahme Handy – dann muss ein Fix bis ins In-App-Update kommen.
- Die Rechner sind Rechen-Einheiten mit Voraussetzungen, dynamisch zuteilbar.
- Du willst einen Verteiler, keine Sitzung selbst starten, keine Start-Texte tippen: „Du bist mein OnPath-Captain“
  muss reichen. Fragen müssen bei dir ankommen; eine Meinungsänderung („kein Release“) muss alle erreichen.
- Geschwindigkeit: Emulator lokal, nicht auf GitHub (nur wenn kein Gerät da ist).
- Nichts ändern, beobachten, Gesamtkonzept; etwas Kleines bauen ist in Ordnung.

## 2. Was ich geprüft habe (heute)
| Frage | Ergebnis |
| --- | --- |
| Kann sich eine Cloud-Sitzung **ohne** Freigabe selbst wecken? | **Ja.** Weckruf in dieser Sitzung um 10:19 angekommen. Der Captain heute Nacht scheiterte (`ACCESS_DENIED`), weil er per Karte freigegeben war. |
| Kann eine Cloud-Sitzung eine Sitzung auf donald anlegen? | **Spur: ja.** „Web-Planer: Kartenlook + Bug 01 (donald)“ am 31.10. kam per `create_session` aus einer Cloud-Sitzung. Heute nicht wiederholt (würde auf deinem Rechner etwas starten). |
| Sieht der Captain, dass eine Sitzung wartet – und worauf? | **Ja.** Die Sitzungsliste zeigt pro Sitzung Status („wartet“) und einen Kurztext der Frage (`needs_action`) – ohne Transkript, also ohne Freigabe. |
| Sieht der Captain, ob donald an ist? | **Nicht direkt.** Die Ordnerliste ist leer (du nutzt `claude remote-control`). Indirekt: Umgebung vorhanden + zuletzt aktive Sitzung; schlägt `create_session` fehl, ist der Rechner aus. |
| Kann die Claude-Cloud die App bauen? | **Ja, mit Cache.** SDK in 12 s; der erste Build scheitert, weil Maven Central den Cloud-Proxy drosselt (HTTP 429). Nach 6 Anläufen (Cache füllt sich) in 11 min: APK gebaut, Unit-Test grün. Mit Setup-Skript + Cache: normal. Kein Emulator (kein KVM). |
| GitHub-Issues erreichbar? | Ja, Cloud per GitHub-Werkzeug, Rechner per `gh`. 0 offen. |
| Wie entstehen deine Rechner-Sitzungen heute? | Per `claude remote-control` im Ordner, Link anklicken (dein Hinweis). Jeder Aufruf legt eine **eigene Wegwerf-Umgebung** an (`hp:onpath-plan:4225`, `:dbef` = zwei Aufrufe) – daher die Doppelten. In so einer Umgebung kann niemand von außen eine Sitzung anlegen. |
| Kann der Befehl eine Sitzung weiterführen? | **Ja** (`claude remote-control --help`): `--name <name>` fester Name; `--continue` hängt sich an die zuletzt in diesem Ordner benutzte Sitzung (≈ 4 h); `--session-id <id>` an eine bestimmte Sitzung – jederzeit. Nicht ausprobiert (bräuchte deinen Rechner). |
| Zahlen | 37 von 66 Commits in 24 h waren Notizen. `web-spike` 1157 voraus / 33 zurück, unter `app/` nur 74 Dateien Unterschied. |

## 3. Was schon gebaut ist und bleibt
| Gebaut | Bleibt als |
| --- | --- |
| `tools/ci/gate.sh` + `risk_tests.py` | Das Tor vor jedem Push, überall gleich – bekommt die `an-alle`-Prüfung. |
| `release.yml` (Push auf `main` mit Version → Tests, Smoke, signierter APK, Release) | Der eine Release-Weg, auch vom Handy. |
| `AppUpdater` (In-App-Update) | Ende des Handy-Fix-Wegs. |
| `unit.yml`, `e2e.yml` (Emulator-Snapshot) | Volle Prüfung nach jedem Push; E2E auf GitHub nur als Netz, nicht als Wartezeit. |
| `web-spike.yml`, `web/parity/check.mjs` | Bleiben, nach dem Zusammenlegen auf `main` mit Pfad-Filter. |
| `merge-main.sh`, täglicher Abgleich 05:47 | Entfallen mit einem Branch. |
| Release-Rundgang (6 min, donald) | Bleibt optional, `[emu]`. |
| `tools/dev/push_cell.sh`, `publish_cells.sh`, Cloud Run | Kartenbau. |
| `docs/roles/*`, Working Agreement §8, Backlog-Kanäle, Freigabe-Karte | Werden `docs/roles/start.md` + `captain.md`; Kanäle und Karte entfallen. |
| `onpath.private` | Bleibt, dazu die Fragen-Issues. |

## 4. Das Konzept

### 4a. Der Captain
**Eine** Cloud-Sitzung mit Titel „OnPath-Captain“. Du startest sie einmal mit: „Du bist mein OnPath-Captain.“
Sie liest `docs/roles/captain.md` und alles Weitere aus dem Repo. Wird sie lang, startest du eine neue mit
demselben Satz – mehr nie. **Keine Freigabe-Karte** (sonst verliert sie den Weckruf).

Was sie bei jedem Weckruf tut (stündlich – öfter erlaubt die Plattform nicht – und sofort, wenn du schreibst):
1. `git pull`; Backlog, Issues (`frage`, `an-alle`, Release-Issue), CI-Stand lesen.
2. Sitzungsliste: Wer arbeitet woran, wer wartet? Wartet eine Sitzung mit einer Frage, die noch kein Issue ist →
   Issue `frage` anlegen.
3. Arbeit verteilen: das nächste Vorhaben für jede Einheit oben ins Backlog (`→ donald: …`). Die stehende
   Rechner-Sitzung (4g) holt es sich beim nächsten Pull. Ist kein Rechner da und das Vorhaben braucht keinen
   (`[pixel]`/`[emu]`/`[karten]` warten), legt der Captain eine **Cloud-Sitzung** dafür an (`create_session`, Cloud –
   das geht ohne Freigabe).
4. Dir melden, was neu ist: offene Fragen, Fertiges, Blockiertes – **kurz, als Push** und als Antwort im Chat.
5. Dein Wort umsetzen: „kein Release“ → `an-alle`-Issue mit `#release-sperre`; „A“ zu Frage 12 → Kommentar + Issue zu; „mach X zuerst“
   → Backlog-Zeile oben + nächste Sitzung dafür.

Was sie nicht tut: Produktcode schreiben, Sitzungen anschreiben (unnötig), Releases freigeben (du).

### 4b. Die beiden Richtungen – Fragen rauf, Entscheidungen runter
**Rauf (eine Sitzung fragt dich):**
- Die Sitzung legt die Frage als Issue `frage` in `onpath.private` an (`tools/ask.sh`, festes Format: wer, 2–3
  Zeilen worum, Frage mit Optionen + Empfehlung, Link). Sie macht dann anderes weiter oder wartet.
- Der Captain sieht wartende Sitzungen in der Liste und legt das Issue nach, falls die Sitzung es nicht konnte.
- Du bekommst es: (a) als Push vom Captain beim nächsten Weckruf oder sofort, wenn du mit ihm sprichst;
  (b) Lesezeichen `onpath.private/issues?q=is:open+label:frage` auf dem Handy; (c) die Claude-App zeigt wartende
  Sitzungen ohnehin markiert. Ob (a) aus einer Cloud-Sitzung auf dem Handy ankommt, ist der **einzige ungeprüfte
  Punkt** – ein Test-Push vom Captain klärt das in einer Minute.
- Du antwortest mit „A“ als Kommentar oder sagst es dem Captain. Die Sitzung liest Antworten beim Start und nach
  jedem Pull (`tools/ask.sh --offen`), handelt, schließt das Issue.

**Runter (etwas, das alle wissen müssen – Stopp ist nur ein Fall):**
- Ein Issue mit Label `an-alle`: „Kein Release bis ich es sage“, „Ab jetzt zuerst die Tester-Bugs“, „Texte neu:
  ‚Tour‘ heißt jetzt …“, „Pixel heute nicht anfassen“. Du schreibst es selbst (Handy) oder sagst es dem Captain.
- **Jede Sitzung liest offene `an-alle`-Issues beim Start, nach jedem Pull und vor jedem Push** (`tools/ask.sh
  --an-alle`, auch in `gate.sh`). Steht `#push-sperre` im Text, **verweigert `gate.sh` den Push** – nichts geht raus,
  auch wenn die Sitzung dein Wort noch nicht gelesen hat. `#release-sperre` blockt nur Release-Commits.
- Jede Sitzung bestätigt mit einem Kommentar „gelesen – donald“; so siehst du (und der Captain), wer es weiß.
  Erledigt → Issue zu. Dauerhafte Regeln wandern danach in `CLAUDE.md` / Backlog.
- Für ein „sofort aufhören“ tippst du in die Sitzung in deiner App (die Liste hast du ohnehin) – oder der Captain
  bricht sie beim nächsten Weckruf ab, falls das aus der Cloud geht (`interrupt_session`, ungeprüft).
- Dauerhaftes (Prioritäten, Regeln) → Backlog oben bzw. `CLAUDE.md`, wie heute.

### 4c. Rechen-Einheiten
| Einheit | Kann | Kann nicht | Wann aus |
| --- | --- | --- | --- |
| **donald** (30 GB, Pixel, SDK, Emulator) | Bauen, Tests, Pixel, Rundgang, **Emulator** | Gradle und Emulator gleichzeitig | nachts, unterwegs |
| **Laptop hp** (32 GB, Kartendaten, SDK?) | Bauen, Tests, Kartenbau, Planer-Smokes; Builds, während donald den Emulator hat | Pixel | nachts, unterwegs |
| **GitHub Actions** (immer an) | Volle Tests, E2E (langsam – nur Netz / ohne Rechner), Release, Planer-Deploy, Karten-Deploy | Pixel, interaktiv | nie |
| **Claude-Cloud** (4 Kerne, 15 GB) | Code, Docs, Website, Marketing, Captain; mit Setup-Skript auch Unit-/Screenshot-Tests + APK | Emulator, Pixel, Kartenbau | nie; jede Sitzung frisch |
| **Cloud Run Job** | Kartenzellen | alles andere | nie |

Beide Rechner gleich ausgestattet (SDK, Node, Python, Karten-Tools, Gradle-Lock), je ein Checkout auf `main`.
Dann zählt nur: Pixel → donald, große Kartendaten → Laptop, Emulator → donald (Laptop als zweiter, wenn er den
Emulator bekommt). Der Gradle-Lock pro Rechner bleibt die RAM-Rücksicht; 64 GB für donald lohnt sich, weil der
Emulator lokal bleibt.

### 4d. Vorhaben statt Rollen
Backlog bleibt To-do-Liste. Jedes Vorhaben trägt, was es braucht: `[pixel]`, `[emu]`, `[karten]`, `[ram]` oder
nichts. Die Rollen-Dateien werden `docs/roles/start.md` (Start-Satz je Einheit, Kürzel, Regeln) +
`docs/roles/captain.md`. Die Sitzung weiß aus Einheit + Vorhaben, was sie ist.

### 4e. Der Handy-Fall
1. Du schreibst dem Captain: „Bug: … Fix + Release.“
2. Captain schreibt es als erstes Vorhaben ins Backlog; läuft donald, nimmt es seine stehende Sitzung; sonst legt
   der Captain eine Cloud-Sitzung an (mit Setup-Skript: baut und testet selbst; ohne: pusht mit „ungetestet“,
   `unit.yml` prüft).
3. Sitzung: Test + Fix, `gate.sh`, Version + Notizen, Push. `release.yml` testet, Smoke, baut, signiert,
   veröffentlicht. Rot = kein Release.
4. Captain meldet dir „v0.14.1 ist da“. Einstellungen → Updates → installieren.
Du tippst zwei Sätze, keinen Start-Text.

### 4f. Ein Branch
`web-spike` einmal in `main` mergen; `web-spike.yml` auf `main` mit Pfad-Filter (PR an dich); Abgleich 05:47 aus;
`web-spike` stehen lassen. Parity-Check läuft weiter in CI.

### 4g. Rechner anbinden – eine stehende Sitzung je Rechner
Dein zuverlässiger Weg bleibt: `claude remote-control` im Ordner, Link anklicken. Neu ist nur, dass es von allein
passiert und die Sitzung bleibt:
- **Autostart** beim Anmelden (systemd-user oder Autostart-Eintrag): `claude remote-control --name donald
  --session-id <gespeicherte ID>`; schlägt das fehl (Sitzung archiviert / zu alt), `--name donald` neu und ID
  speichern. Skript `tools/dev/unit.sh` (neu, ~20 Zeilen). Dasselbe auf dem Laptop mit `--name hp`.
- Die Sitzung heißt immer gleich, hat ihren Chat und ihr Wissen; nach Neustart geht es weiter. Keine
  Wegwerf-Umgebungen mehr. Wird sie lang: Script ohne `--session-id` einmal neu starten.
- **Wie bekommt sie Arbeit, ohne dass du tippst?** Zwei Wege, einer muss klappen (je 1 Minute Test):
  (a) ein geplanter Auftrag, der stündlich **in diese Sitzung** feuert („pull, lies `an-alle` + `frage`, nimm das
  nächste Vorhaben“; `persistent_session_id`) – unsicher, weil Rechner-Sitzungen als „erhöht“ gelten;
  (b) die Sitzung läuft selbst in einer Schleife (`/loop`: alle 30 min pull + nächstes Vorhaben) – du sagst es ihr
  einmal, und nach jedem Wiederanhängen läuft der Chat weiter.
- Erst-Start einmal pro Rechner: „Du bist die Einheit donald, lies `docs/roles/start.md`.“ Danach nie wieder.

## 5. Was neu gebaut würde
| Teil | Umfang | Zweck |
| --- | --- | --- |
| `tools/ask.sh` | ~40 Zeilen | Frage als Issue im festen Format; `--offen` liest Antworten; `--an-alle` zeigt dein Wort an alle und bestätigt |
| `gate.sh`: `an-alle`-Prüfung | ~10 Zeilen | zeigt offene `an-alle`, kein Push bei `#push-sperre` |
| `tools/dev/unit.sh` | ~20 Zeilen | Autostart `claude remote-control --name … --session-id …`, ID merken |
| `tools/ci/release_check.sh` | ~40 Zeilen | der Captain-Check als Skript (Fingerprint, Sync, Shared-Tests) |
| `docs/roles/start.md`, `captain.md` | 2 Seiten | ersetzen 4 Rollen-Dateien + §8; Captain-Ablauf 4a |
| Captain-Weckruf | 1 geplanter Auftrag, stündlich, auf die Captain-Sitzung | 4a |
| Weckruf je Rechner-Sitzung **oder** `/loop` | 1 Auftrag oder 1 Satz | 4g |
| Setup-Skript Cloud-Umgebung | ~10 Zeilen + Gradle-Cache | Cloud-Sitzungen testen und bauen |
Kein MCP, keine Datenbank, keine eigene Seite. Später möglich: Artefakt-Seite, die die Issues hübscher zeigt.

## 6. Reihenfolge (Release bleibt unberührt)
1. **Jetzt:** Labels `frage` + `an-alle` in `onpath.private`; Test-Issue; `tools/ask.sh`; `an-alle` in `gate.sh`;
   D1–D4 und „Waiting for the owner“ als Issues; Regel in `CLAUDE.md`.
2. **v0.14.0** mit dem heutigen Setup fertig.
3. **Danach:** heutige Captain-Sitzung beenden (freigegeben, nicht weckbar); `captain.md` + `start.md`; neue
   Captain-Sitzung mit „Du bist mein OnPath-Captain“; Weckruf einrichten; **Tests (je 1 min):** Push vom Captain
   kommt auf dem Handy an; `--session-id` hängt sich wieder an; Weckruf in die donald-Sitzung oder `/loop`.
   `tools/dev/unit.sh` + Autostart auf donald, dann Laptop.
4. `release_check.sh`; Rollen-Dateien + §8 kürzen; Backlog-Kanäle raus.
5. Ruhige Sitzung auf donald: `web-spike` → `main`; Workflow-PR; Abgleich aus. Laptop ausstatten; doppelte
   Umgebung aufräumen.
6. Optional: Cloud-Setup-Skript mit Gradle-Cache.

## 7. Fragen, die nur du beantworten kannst
1. Fragen + Stopp als Issues in `onpath.private` – ja?
2. Captain stündlich wecken reicht? (Öfter geht nicht; du kannst ihn jederzeit anschreiben.)
3. Dürfen die Rechner-Sitzungen von allein arbeiten (Weckruf / Schleife), sobald ein Vorhaben offen ist – oder
   nur nach deinem „go“ pro Tag? Darf `claude remote-control` beim Anmelden automatisch starten?
4. Ein Branch nach v0.14.0 – ja?
5. Laptop gleich ausstatten wie donald – ja?
6. Cloud-Setup-Skript (Cloud testet selbst) gewünscht, oder reicht „GitHub testet“ für den Handy-Fall?

## 8. Nicht geprüft
- `--session-id` / `--continue` in der Praxis; ein Weckruf in eine Rechner-Sitzung; `/loop` dort;
  `interrupt_session` aus der Cloud. `create_session` auf einen Rechner ist mit 4g nicht mehr nötig.
- Ob ein Push aus einer Cloud-Sitzung auf deinem Handy ankommt.
- Ob der Laptop ein Android-SDK hat.
