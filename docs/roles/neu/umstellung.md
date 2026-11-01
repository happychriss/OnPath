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
| `docs/roles/neu/start.md`, `captain.md` | fertig, noch nicht gültig |

## A. Drei Tests auf donald (je ~2 min, nichts wird dabei eingeschaltet)
1. **Wiederanhängen:** im Terminal auf donald im OnPath-Ordner `tools/dev/unit.sh run test-donald`. Erscheint ein Link
   und steht danach eine ID in `~/.local/state/onpath-unit/test-donald.id`? Abbrechen (Strg-C), noch einmal starten
   → ist es in der Claude-App **dieselbe** Sitzung (alter Chat da)? Wenn die ID nicht erkannt wird: die Ausgabe der
   CLI ansehen und die Zeile `session_…` im Skript anpassen.
2. **Schleife:** in dieser Sitzung `/loop` (ohne Zeitangabe) mit „sag alle paar Minuten die Uhrzeit“ – läuft sie
   weiter, wenn niemand tippt? (Sonst: stündlicher Weckruf in die Sitzung als Ersatz, `create_trigger` mit
   `persistent_session_id`.)
3. **Sichern:** `tools/dev/save-on-shutdown.sh dry` – listet es die richtigen Ordner? Danach Test-Sitzung archivieren,
   `rm ~/.local/state/onpath-unit/test-donald.id`.

## B. 👤 Entscheidungen beim Umstellen (je eine Fragebox)
- Erlaubnis-Modus der stehenden Sitzungen (`ONPATH_PERMISSION_MODE`): ohne Modus fragt die Sitzung bei vielem nach
  (gelbe Punkte ohne echte Frage) – Vorschlag `auto`.
- Ordnername auf beiden Rechnern: `~/Projects/work/onpath` (statt `onpath-app` / `onpath-plan`) – Vorschlag ja.

## C. Einschalten
1. donald: `ONPATH_PERMISSION_MODE=<B> tools/dev/unit.sh install donald` → Link öffnen, Start-Satz aus
   `docs/roles/neu/start.md` einmal tippen, `/loop`. Neustart-Test: Rechner neu starten → dieselbe Sitzung läuft wieder.
   Sicher-Test: eine Datei ändern, neu starten → Branch `wip/donald-…` auf GitHub.
2. 👤 Die alte Sitzung `claud-app` beenden, sobald die neue läuft (vorher: alles gepusht, Übergabe geschrieben).
3. Laptop einrichten wie donald: Android SDK, Node ≥ 22.23, Python + Karten-Tools, `gh auth setup-git`, `git config
   user.name/email`, Checkout auf `main`, Kartendaten daneben. Dann `tools/dev/unit.sh install hp`, Start-Satz, `/loop`.
   👤 Alte Sitzung `claud-plan` beenden.
4. **Captain:** 👤 die alte Captain-Sitzung archivieren (sie ist freigegeben und kann sich nicht wecken). Neue
   Cloud-Sitzung, Satz: „Du bist mein OnPath-Captain.“ Sie richtet ihren Weckruf selbst ein und testet den Push
   (`docs/roles/neu/captain.md` „Once“). **Keine Freigabe-Karte bestätigen.**

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
