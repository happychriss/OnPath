# Umstellung nach v0.14.0 – Checkliste

> Vorbereitet 2026-11-01 (Sitzung „Setup review handoff“). **Erst nach dem Release v0.14.0.** Konzept:
> `docs/roles/setup-vorschlag-2026-11-01.md`. Jede Sitzung kann das abarbeiten; 👤 = macht der Owner.
> Reihenfolge einhalten: erst testen, dann einschalten, dann Altes abbauen. Jeder Schritt einzeln rückbaubar.

## Was schon fertig im Repo liegt
| Datei | Stand |
| --- | --- |
| `tools/ask.sh`, `an-alle` in `gate.sh`, Regeln in `CLAUDE.md` | **aktiv** seit 2026-11-01 |
| `tools/ci/release_check.sh` | fertig, in der Cloud gegen `main` / `web-spike` getestet (zwei Branches und ein Branch) |
| `tools/dev/save-on-shutdown.sh` | fertig, mit Test-Repo getestet (Checkout bleibt unberührt, Worktrees, offline → nachpushen, keine Doppelten) – **nicht auf donald** |
| `tools/dev/unit.sh` | fertig, mit Platzhaltern getestet (ID merken, Wiederanhängen, Rückfall auf neue Sitzung, Dienst-Dateien) – **echte CLI nicht getestet** |
| `docs/roles/neu/start.md`, `captain.md` | gültig (donald + Captain), „Leicht mit Captain“ seit 2026-11-02 |

## A. Tests auf donald (erledigt 2026-11-01, Ergebnisse unten)
Das Verhalten von Claude / Plattform (Gastgeber, Worktrees, Sitzungen starten, Weckruf) gilt auf dem Laptop genauso.
**Auf dem Laptop nur drei Prüfungen bei der Einrichtung (C.3):** (a) `gh api repos/happychriss/onpath.private --jq
.full_name` und `tools/ask.sh an-alle` – ohne `gh` greifen dort weder `an-alle` noch die Push-Sperre (Backlog: dort
fehlt `gh auth setup-git`); (b) A.0 im Ordner; (c) `save-on-shutdown.sh dry` – keine Kartendaten dabei.
**Laptop geprüft 2026-11-01:** (a) ✓ `gh` erreicht `onpath.private`, `jq` 1.8.1, `ask.sh` läuft (als Kopie aus
`origin/main` – der Checkout steht noch auf `web-spike`); (c) ✓ leer, `onpath-plan` sauber (0 geändert, 0 ungepusht),
keine Kartendaten. Offen nur (b) beim Einrichten des neuen Ordners `onpath` auf `main`.
0. **Jeder neue Ordner einmal:** `claude` darin starten, die Vertrauensfrage bestätigen, `/exit`; dann
   `claude remote-control`, `w` drücken bis „worktree“, Strg-C. Sonst endet
   `unit.sh` mit „Workspace not trusted“ (gesehen auf donald 2026-11-01; ein Autostart kann die Frage nicht beantworten).
1. **Wiederanhängen:** im Terminal auf donald im OnPath-Ordner `tools/dev/unit.sh run test-donald`. Erscheint ein Link
   und steht danach eine ID in `~/.local/state/onpath-unit/test-donald.id`? Abbrechen (Strg-C), noch einmal starten
   → ist es in der Claude-App **dieselbe** Sitzung (alter Chat da)? Wenn die ID nicht erkannt wird: die Ausgabe der
   CLI ansehen und die Zeile `session_…` im Skript anpassen.
2. **Schleife:** in dieser Sitzung `/loop` (ohne Zeitangabe) mit „sag alle paar Minuten die Uhrzeit“ – läuft sie
   weiter, wenn niemand tippt? (Sonst: stündlicher Weckruf in die Sitzung als Ersatz, `create_trigger` mit
   `persistent_session_id`.)
3. **Sichern:** `tools/dev/save-on-shutdown.sh dry` – listet es die richtigen Ordner? Danach Test-Sitzung archivieren,
   `rm ~/.local/state/onpath-unit/test-donald.id`.

### Ergebnisse 2026-11-01 (erste echte Tests auf donald)
- A.0: neuer Ordner → „Workspace not trusted“, bis einmal `claude` darin gestartet und bestätigt wurde (jetzt in `unit.sh`).
- A.1: **Wiederanhängen klappt** – `unit.sh run test-donald` speichert die ID, der zweite Start verbindet dieselbe
  Sitzung, die Umgebung bleibt („Environment preserved“).
- `gh` und `ask.sh` (mit `jq`) laufen auf donald – `an-alle` und die Push-Sperre greifen dort.
- `save-on-shutdown.sh dry` findet `onpath-app` (cloud-app mitten in der Arbeit) und einen Unteragenten-Worktree in
  `/tmp/claude-…/scratchpad` (den ein Neustart sonst löscht); keine falschen Ordner. Branch-Name jetzt Repo + Branch.
- `claude remote-control` ist ein Gastgeber für bis zu 32 Sitzungen („Capacity 1/32“, `w` = Spawn-Modus).
- Eine Cloud-Sitzung **ohne** Freigabe darf dort **keine** Sitzung starten (`elevated_session_untrusted_device`).
- **Die Sitzung auf donald darf** Sitzungen in ihrer Umgebung starten (spawn-test-2: eigener Worktree
  `worktree-bridge-cse_…`, „Test ok“, nichts geändert) – aber nur, wenn der Gastgeber im Modus „mehrere Sitzungen“
  läuft: ein normales `claude remote-control`. Mit `--session-id` / `--continue` ist er „Single session“ und nimmt
  keine neuen an (spawn-test blieb PENDING).
- Spawn-Modus mit `w` auf **worktree** – nur im echten Terminal (über eine Pipe reagierte `w` nicht); pro Ordner
  gespeichert. Ein normaler Neustart von `claude remote-control` verbindet alle Sitzungen wieder.
- `/loop` läuft ohne Zutun (alle 2 min, 16:35–16:46), **stirbt aber beim Neustart**.
- Ein **Weckruf, den die donald-Sitzung sich selbst stellt** (`send_later`), **überlebt den Neustart** (fällig 16:57,
  angekommen 16:58 nach Neustart). → die Einheit stellt sich den stündlichen Weckruf selbst.
- `unit.sh` umgebaut: normaler Gastgeber über `script` (echtes Terminal + Log), kein `--session-id` mehr.

### Hooks (2026-11-01, `docs/roles/setup-analyse-2026-11-01.md` Schritt 1)
`.claude/settings.json` im Repo: SessionStart → `tools/hooks/session-start.sh` (Lage in den Kontext), PreToolUse Bash →
`tools/hooks/pre-bash.sh` (kein `git push` ohne grünen `gate.sh`-Stempel für HEAD oder bei `#push-sperre`; `adb
install` nur über `device.sh`). **Einmal auf donald prüfen:** eine neue Sitzung im Gastgeber zeigt in ihrer ersten
Antwort die Lage „== OnPath session start ==“; `git push` nach einem Commit ohne Tor wird mit Meldung verweigert.
Ausschalten im Notfall: `ONPATH_HOOKS=0` oder `"disableAllHooks": true` in den Settings. Gilt nur auf `main`
(`web-spike` hat die Datei nicht). Registriert in `.claude/settings.json` (im Repo – kein Einrichten je Rechner; neue
Sitzungen haben sie nach dem nächsten Pull, laufende nach ihrem Neustart).
**Deny-Regeln (👤 einmal auf donald):** `python3 tools/dev/apply_permissions.py`, dann committen und pushen – sperrt
Force-Push, `reset --hard`, Branch-Löschen, `adb uninstall` / `pm clear`, `gradlew --stop` in jedem Modus.

### Push aufs Handy (owner 2026-11-02: „sonst kriege ich das nie mit“)
GitHub meldet eigene Issues nicht – der Push kommt von Claude selbst. 👤 **Einmal pro Rechner** (donald, Laptop):
zweites Terminal → `claude` → `/config` → **„Push when actions required“** und **„Push when Claude decides“** an
(„No mobile registered“: Claude-App auf dem Handy öffnen, dann erneut) → `/exit`. Gilt für alle Remote-Control-
Sitzungen des Rechners; kein Push, solange man am Terminal tippt. Cloud-Sitzungen (Captain, cloud-web) können nicht
pushen (Doku) – Entscheidung: vorerst ohne Push-Dienst; Captain-Fragen im Gespräch + Dashboard. Falls etwas
durchrutscht: ntfy-Hook (`Notification`, `idle_prompt`) als nächster Schritt.

## B. 👤 Entscheidungen beim Umstellen (je eine Fragebox)
- Erlaubnis-Modus der stehenden Sitzungen (`ONPATH_PERMISSION_MODE`): ohne Modus fragt die Sitzung bei vielem nach
  (gelbe Punkte ohne echte Frage) – Vorschlag `auto`.
- Ordnername auf beiden Rechnern: `~/Projects/work/onpath` (statt `onpath-app` / `onpath-plan`) – Vorschlag ja.

## C. Einschalten – „Leicht mit Captain“ (owner 2026-11-02)
0. **Nachtarbeit retten** (`wip/donald-2026-11-01-nacht`, Backlog ganz oben) macht die neue donald-Sitzung als Erstes.
1. donald: altes `claude remote-control` (Terminal `onpath-app`) mit Strg-C beenden; `git status --short` muss leer sein
   (sonst: Ausgabe an die Setup-Sitzung). Dann A.0 im Checkout (Vertrauen, `w` → worktree), dann
   `ONPATH_PERMISSION_MODE=auto tools/dev/unit.sh run donald` (später `install donald` für den Autostart).
2. ✅ 2026-11-02 19:53: Sitzung **donald** läuft im Gastgeber (Worktree-Modus, auto). Hooks feuern in der
   Remote-Control-Sitzung (sie kannte `wip/`-Branches und die offenen Fragen ungefragt); stündlicher Weckruf
   „donald: Runde (stündlich)“ steht (Minute 56); erstes Vorhaben = Nacht-Branch rebased, Tor im Hintergrund.
3. 👤 Alte Sitzung `claude-app` archivieren (ihre Design-Review vs. Komoot steht nur dort – vorher als Datei sichern
   lassen, siehe Backlog).
4. **Captain:** 👤 alte Captain-Sitzung archivieren und ihren toten Weckruf „Captain: E2E + review check“ löschen. Neue
   Cloud-Sitzung mit `OnPath` + `onpath.private`, Satz „Du bist mein OnPath-Captain.“ **Keine Freigabe-Karte
   bestätigen.** Er richtet seinen Weckruf ein, testet den Push, lässt `release_check.sh` laufen.
5. Laptop später wie donald (SDK, Node ≥ 22.23, `gh auth setup-git`, Checkout `main`, A.0, `unit.sh run laptop`); bis zum
   Branch-Merge bleibt cloud-plan.

## D. Ein Branch (eine ruhige Sitzung auf donald)
1. `web-spike` in `main` mergen (Planer unter `web/`); `web/parity/merge-main.sh` zeigt, was dabei kollidiert.
2. PR an den Owner: `web-spike.yml` auf `main` mit Pfad-Filter (`web/**` + die geteilten App-Dateien). 👤 mergen.
3. 👤 täglichen Auftrag „OnPath Web-Planer: täglicher Abgleich mit der App“ (05:47) ausschalten. `web-spike` stehen
   lassen.
4. `tools/ci/release_check.sh` zeigt danach „one commit … equal by construction“.

## E. Altes abbauen
1. `docs/roles/neu/*` nach `docs/roles/` verschieben; `cloud-app.md`, `cloud-plan.md`, `cloud-web.md`,
   `cloud-captain.md` löschen; `docs/roles/README.md` kürzen.
2. `CLAUDE.md`: Rollen-Zeile oben → „Jede Sitzung ist eine Einheit: `docs/roles/start.md`“.
3. `docs/working-agreement.md` §8 auf einen Absatz kürzen (Einheiten, Issues, kein Nachrichtenkanal).
4. `docs/backlog.md`: Abschnitte „For the roles“ / „For the captain“ entfernen; „Next“ mit Tags versehen.
5. 👤 Alte Umgebungen in der Claude-App aufräumen (doppeltes `hp:onpath-plan`), alte Sitzungen archivieren.
6. cloud-web (Website, Marketing) wird ein Cloud-Arbeiter für Vorhaben aus `onpath.private/backlog.md` – der Captain
   startet ihn, wenn dort etwas oben steht.

## Rückweg
Jeder Schritt lässt sich einzeln zurücknehmen: `tools/dev/unit.sh uninstall <name>`, alte Rollen-Dateien aus der
Git-Geschichte, Captain-Weckruf löschen. `ask.sh` / `an-alle` bleiben in jedem Fall.
