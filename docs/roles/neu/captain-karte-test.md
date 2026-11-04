# Test: Captain mit Freigabe-Karte (owner 2026-11-03)

**Ziel:** Der Captain soll Arbeit direkt an die Rechner geben, statt sie nur ins Backlog zu schreiben. Dafür braucht
er die Freigabe-Karte (trusted device). Geprüft ist (10-31): mit Karte darf eine Cloud-Sitzung Rechner-Sitzungen
**anschreiben** und **mitlesen**. Zugesagt, aber nie probiert: Sitzungen auf einem Rechner **starten**. Bekannter Preis:
mit Karte weckt sich der Captain nicht mehr selbst (sein Weckruf scheitert mit `ACCESS_DENIED`).

**Voraussetzung:** donald ist an, der Gastgeber läuft (`tools/dev/unit.sh run donald`, Worktree-Modus).

## Ablauf – für den Captain (Cloud-Sitzung)
Der Owner schreibt dem Captain: „Mach den Karten-Test aus `docs/roles/neu/captain-karte-test.md`.“

0. **Karte holen.** `list_environments` → die aktive Umgebung `donald:onpath-app:…` (die den Gastgeber von donald trägt).
   Den ersten Aufruf aus Test 1 versuchen. Die Plattform lehnt ab und nennt einen `content_digest` →
   `confirm_repository_trust` mit genau diesem Wert → der Owner bestätigt die Karte auf dem Handy. Dann Test 1 noch einmal.
1. **Starten:** `create_session` mit `environment_id` = donalds Umgebung, Titel `karten-test`, Auftrag:
   „Führe `pwd` und `git branch --show-current` aus und antworte in einer Zeile: ‚Test ok – <Ordner> – <Branch>‘.
   Ändere nichts, pushe nichts, starte nichts.“
   **Erwartet:** im Gastgeber-Terminal „Capacity 2/32“; die Sitzung antwortet aus einem eigenen Worktree.
2. **Schreiben:** `send_message` an `karten-test`: „Zweite Nachricht vom Captain – antworte nur ‚Nachricht angekommen‘.“
   **Erwartet:** die Antwort kommt.
3. **Mitlesen:** `list_events` (kinds `assistant`) auf `karten-test` – beide Antworten müssen lesbar sein. Zusätzlich
   `get_session` auf die Sitzung **donald** (nur lesen, nichts schicken).
4. **Aufräumen:** `archive_session` auf `karten-test`. Den eigenen stündlichen Weckruf (`list_triggers`, persistent auf
   diese Sitzung) **löschen** – mit Karte scheitert er ohnehin; Owner vorher mit einer Fragebox fragen.
5. **Ergebnis** in 3 Zeilen an den Owner (je Test ✓/✗ + die genaue Fehlermeldung, falls ✗) und als Abschnitt
   „Ergebnis Karten-Test“ unten in diese Datei; Tor, Push.

Schlägt Test 1 fehl, aber 2 und 3 gehen: Der Captain kann donalds **laufende** Sitzung beauftragen (`send_message`),
nur keine neuen starten – auch brauchbar. Schlägt alles fehl: nichts ändern, Fehlermeldungen festhalten.

## Wenn alles klappt (danach, eigener Schritt)
`docs/roles/neu/captain.md` umschreiben: der Captain gibt Aufträge direkt (`create_session` für ein neues Vorhaben,
`send_message` an die laufende Sitzung), mit den Entscheidungen des Owners im Auftrag; kein eigener Weckruf; Überblick,
wenn der Owner schreibt (`/fragen`, `/lage`). Die Rechner behalten Wecker, Runde und Push.

## Ergebnis Karten-Test (2026-11-03, 23:03–23:10, Captain-Sitzung)
- **Karte:** `create_session` auf `donald:onpath-app` lehnte zuerst ab (`elevated_session_untrusted_device`) →
  `confirm_repository_trust` → Owner bestätigte auf dem Handy. Endgültig: die Sitzung ist jetzt an vertrauenswürdige Geräte gebunden.
- **1 Starten ✓** – `karten-test` lief auf donald im eigenen Worktree (`.claude/worktrees/bridge-cse_…`, Branch
  `worktree-bridge-cse_…`), Gastgeber zeigte „Capacity 2/32“. Erst nach `tools/dev/unit.sh run donald` verbunden (vorher PENDING).
- **2 Schreiben ✓** – `send_message` kam an; die Antwort kam per `send_message` zurück.
- **3 Mitlesen ✓** – `list_events` zeigt beide Antworten; `get_session` auf donald lesbar.
- **Preis:** jeder Aufruf an eine andere Sitzung (`archive_session`, `send_message` …) fragt den Owner einzeln
  („Einmal erlauben“, kein „immer“); auch die gestartete Sitzung auf donald brauchte eine Freigabe für ihre Antwort.
  Der stündliche Captain-Weckruf ist gelöscht (Owner, mit Karte scheitert er).
- **Folgerung:** Aufträge direkt an die Rechner gehen, kosten aber pro Aufruf einen Tipp des Owners. Vorschlag: nur
  für eilige Dinge direkt (`send_message` an die laufende Sitzung), sonst weiter über das Backlog.
