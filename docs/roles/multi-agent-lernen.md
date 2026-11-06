# Multi-Agent-Betrieb: Was wir gelernt haben (OnPath, Stand 2026-11-05, nach dem Aufräumen)

Ein Owner, ein mittelgroßes Projekt (Android-App + Web-Planer + Website), zwei Rechner, mehrere Claude-Sitzungen.
Diese Datei hält fest, was **geprüft** funktioniert, was nicht, und welche Regeln daraus folgen – als Grundlage für
ein sauberes Setup, auch für andere Projekte. Die Geschichte der Setup-Arbeit (Konzepte, Analyse, Audit) liegt in
`docs/history/setup-2026-11/` – **das Audit dort zuerst lesen**. Herkunft: Setup-Review 2026-11-01/02, Tests auf donald, Laptop und
in der Cloud. „Geprüft“ heißt: selbst ausprobiert, mit Datum.

## 1. Das Setup, das wir fahren („Leicht mit Captain“)
- **Je Rechner eine arbeitende Sitzung**, benannt nach dem Rechner (donald, laptop). Sie läuft in einem
  **Gastgeber** (`claude remote-control`, Worktree-Modus) und weckt sich **stündlich selbst**. Eiliges sagt der Owner
  der Sitzung direkt. Parallelität über Hintergrund-Agenten/Forks in eigenen Worktrees, zwei Gradle-Plätze je Rechner.
- **Ein Captain** in der Cloud: Überblick (Builds, App ⇄ Planer, CI, Fragen), ordnet das Backlog. Schreibt keinen Code.
  Er hat seit 11-03 die Freigabe-Karte: darf Rechner-Sitzungen erreichen (jeder Aufruf braucht den Tipp des Owners),
  weckt sich aber nicht selbst.
- **Das Repo ist das Gedächtnis**, nicht der Chat. **GitHub-Issues** sind der Kanal zum Owner (Fragen, „an alle“).
- **Regeln werden erzwungen**, nicht erbeten: Hooks, Deny-Regeln, Skripte.
Verworfen bzw. entfernt (Audit 2026-11-05): Leitstand-Sitzung je Rechner; 10-Minuten-Schleife; `[sofort]` mit
Wecker-Hook (nie benutzt); Autostart- und Sichern-Dienst (nie installiert); Rollen je Aufgabe statt je Rechner;
Backlog als Nachrichtenkanal.

## 2. Plattform-Fakten (geprüft)
| Fakt | Geprüft | Folge |
| --- | --- | --- |
| `claude remote-control` ist ein **Gastgeber für bis zu 32 Sitzungen**; ein Neustart verbindet alle Sitzungen des Ordners wieder („Environment preserved“) | 11-01, donald | Sitzungen überleben Neustarts – nicht mehr neu anlegen |
| Mit `--session-id` / `--continue` ist er **„Single session“** und nimmt keine neuen Sitzungen an | 11-01 | normaler Start, kein `--session-id` |
| **Spawn-Modus `worktree`** (Taste `w`, pro Ordner gespeichert; laut Doku auch `--spawn worktree`): jede neue Sitzung bekommt ihren eigenen Worktree | 11-01 | parallele Sitzungen kollidieren nicht |
| Ein neuer Ordner muss **einmal von Hand vertraut** werden (`claude` dort starten) – sonst „Workspace not trusted“ | 11-01 | Teil der Einrichtung; ein Autostart kann das nicht |
| Ausgabe der CLI durch eine Pipe → **Tasten (`w`) gehen nicht mehr** | 11-01 | Logging über `script`, nicht `tee` |
| Eine Sitzung **auf dem Rechner** darf weitere Sitzungen in ihrem Gastgeber starten (`create_session`) | 11-01, spawn-test-2 | Verteilen geht nur vom Rechner aus |
| Eine **Cloud-Sitzung darf das nicht** (`elevated_session_untrusted_device`) | 11-01 | Captain kann keine Rechner-Sitzungen starten |
| Eine Cloud-Sitzung darf einen Weckruf, der eine Rechner-Sitzung weckt, **nicht auslösen** – nur die Sitzung selbst oder ein Mensch | 11-02 | Captain kann donald nicht anstoßen → 10-Minuten-Blick in donald |
| **Freigabe-Karte** (trusted device) für eine Cloud-Sitzung: sie erreicht dann Rechner-Sitzungen, **verliert aber das Selbst-Wecken** (Weckruf → `ACCESS_DENIED`, jede Nacht) | 10-31/11-02 | Captain **ohne** Karte |
| Cloud-Sitzung **ohne** Karte kann sich selbst wecken (`send_later`) | 11-01 | Captain-Weckruf stündlich |
| Rechner- und Cloud-Sitzungen können sich **gegenseitig nicht anschreiben** (ohne Karte) | 10-31 | Kanal = Repo + Issues |
| `/loop` läuft nur, solange die Sitzung lebt **und gerade nicht arbeitet**; **stirbt beim Neustart**; max. 7 Tage | 11-01 (Test 5/6) | stündlicher Weckruf richtet die Schleife wieder ein |
| Ein Weckruf, den sich eine **Rechner-Sitzung selbst stellt**, überlebt einen Neustart des Gastgebers | 11-01 (Test 7) | der verlässliche Herzschlag |
| **Hooks aus `.claude/settings.json` im Repo** feuern auch in Remote-Control-Sitzungen (SessionStart-Ausgabe landet im Kontext) | 11-02, donald | Lage beim Start automatisch |
| Hooks wirken nur auf Befehle, die **Claude** ausführt – nicht auf das Terminal des Owners | 11-02 | der Owner behält den direkten Weg |
| Eine Sitzung darf **ihre eigenen Berechtigungen nicht ändern** (Auto-Modus-Prüfung) | 11-02 | Deny-Regeln als Vorschlag + Skript, der Owner wendet an |
| **Deny-Regeln** greifen in jedem Modus, auch `bypassPermissions` | Doku | harte Grenzen (Force-Push, `reset --hard`, Handy leeren) |
| `.claude/rules/*.md` mit `paths:` laden **nur**, wenn die Sitzung passende Dateien anfasst; `@import` spart nichts | Doku, 11-02 eingeführt | `CLAUDE.md` klein halten |
| **Push aufs Handy**: Remote-Control-Sitzungen ja (`/config`: „Push when actions required“, „Push when Claude decides“ – standardmäßig aus); **Cloud-Sitzungen nein** | Doku 11-02 | Schalter auf jedem Rechner; Cloud-Fragen im Gespräch + Dashboard |
| **GitHub meldet eigene Issues nicht** (Sitzungen schreiben unter dem Owner-Konto) | Doku/Annahme | Dashboard allein reicht nicht, der Push muss von Claude kommen |
| `gh issue …` (GraphQL) ist in Cloud-Sitzungen **gesperrt**, REST (`gh api`) geht | 11-01 | `ask.sh` nur über REST |
| GitHub listet ein neues Issue erst nach einigen Sekunden | 11-01 | in Skripten nicht sofort zurücklesen |
| Cloud-Container: Android-SDK ladbar (12 s), **Maven Central drosselt** (HTTP 429) bis der Gradle-Cache warm ist; **kein KVM** (kein Emulator) | 11-01 | Cloud baut nur mit Cache; Emulator auf dem Rechner |

## 3. Gestaltungsregeln, die sich bewährt haben
1. **Das Repo ist das Gedächtnis.** Was nur im Chat steht, ist beim nächsten Neustart weg (gestern: die Design-Review
   steckte nur im alten Chat). Übergaben, Backlog, Entscheidungen ins Repo.
2. **Nie Arbeit ungesichert lassen.** Pushen in kleinen Schritten; vor einer blockierenden Frage pushen; beim
   Ausschalten Halbfertiges auf einen `wip/<rechner>-<datum>-<thema>`-Branch pushen (so haben es die Rechner ab 11-03
   gemacht; ein Dienst dafür wurde gebaut und nie gebraucht).
3. **Prosa ist teuer und unverbindlich.** Jede Regel in `CLAUDE.md` liest jede Sitzung bei jedem Start; befolgt wird sie
   nur, wenn die Sitzung daran denkt. Was gelten **muss**, gehört in einen Hook, eine Deny-Regel oder ein Skript
   (Push nur nach grünem Tor, Handy nur über die Geräte-Sperre, kein Force-Push).
4. **Wenige bewegliche Teile.** Ein Koordinator (Captain), die Rechner arbeiten selbst. Jeder zusätzliche Koordinator
   kostet Weckrufe, Token und Fehlerquellen.
5. **Namen nach Rechner, Arbeit nach Kürzeln.** `[pixel]`/`[emu]` → donald, `[karten]`/`[ram]` → laptop, ohne Kürzel →
   wer frei ist; Eiliges sagt der Owner der Sitzung direkt. **Der Owner verteilt nie selbst**: Er sagt es dem Captain (oder der
   Sitzung, mit der er spricht), es landet im Backlog, der passende Rechner nimmt es.
6. **Fragen an den Owner: ein Ort + ein Push.** Jede Frage als Issue (`tools/ask.sh`, festes Format: wer, Hintergrund,
   Optionen, Empfehlung); blockierende zusätzlich als Fragebox im Chat (gelber Punkt + Push). Eine Frage nur im Chat
   ist ein Fehler. Der Owner schließt nie ein Issue. Eine Frage nach der anderen.
7. **Wort an alle über ein Issue**, nicht über Nachrichten (`an-alle`, `#push-sperre`, `#release-sperre`) – wirkt beim
   nächsten Pull und vor jedem Push, auch bei Sitzungen, die nichts davon wissen.
8. **Chat bleibt ansprechbar – maßvoll.** Lange, abgeschlossene Arbeit in Hintergrund-Forks (die den Chat-Kontext
   behalten); Gespräch, Entscheidungen, Kleinkram direkt. „Alles in den Hintergrund“ kostet Kontext und macht
   Kleinkram langsamer. Ein freier Chat ist außerdem Voraussetzung, dass Schleifen und Weckrufe überhaupt ankommen.
9. **Parallelität braucht Sperren.** Eigener Worktree je Agent, eine Gradle-Sperre pro Rechner (RAM), eine Sperre je
   Gerät (Pixel, Emulator – `tools/dev/device.sh`).
10. **Token beim Start zählen.** Jede Sitzung liest die Grund-Doku. `CLAUDE.md` von 3 851 auf 1 125 Wörter gekürzt,
    Details in Regeln nach Bereich. `project-status` und `backlog` werden zu Geschichtsbüchern, wenn niemand kürzt.
11. **Backlog als Nachrichtenkanal erzeugt Rauschen:** 37 von 66 Commits an einem Tag waren Notizen.
12. **Erst klein testen, dann bauen.** Die Freigabe-Karte hat den alten Captain gelähmt, weil niemand vorher geprüft
    hatte, was sie kostet. Ein Ein-Minuten-Test (Weckruf, Sitzung starten, Hook) spart Stunden.
13. **Der Owner behält den direkten Weg.** Für Owner-Schritte ein eigener Checkout (`onpath-owner`), nie im Ordner einer
    laufenden Sitzung arbeiten.

## 4. Fehler, aus denen Regeln wurden
| Vorfall | Regel |
| --- | --- |
| Sitzungen auf beiden Rechnern versehentlich beendet, ungesicherte Arbeit (11-01 abends) | Sichern beim Ausschalten; Push vor jeder blockierenden Frage |
| Captain mit Freigabe-Karte weckt sich nie (10-31 bis 11-02) | Captain ohne Karte |
| `git reset --hard` in einem lokalen Test verwarf eine eigene Änderung, ein Hook war kurz scharf ohne seinen Stempel (11-01) | Deny-Regel für `reset --hard`; Änderungen erst committen, dann testen |
| Fragen nur im Chat, Owner merkt nichts (11-02) | Push-Schalter je Rechner; Frage nur im Chat = Fehler |
| Wichtiger Inhalt (Design-Review) nur im Chat einer alten Sitzung | Ergebnisse immer als Datei ins Repo |
| Zwei Sitzungen mit gleichem Namen, Verwechslung | Alte Sitzung erst umbenennen/archivieren |

## 5. Offen / nächste Schritte
- „Ein Branch“: `web-spike` → `main` (Entscheidung des Owners vom 11-01) umsetzen oder bewusst verwerfen – Backlog.
- Die `wip/`-Branches von 11-01/03 prüfen und abbauen – Backlog.
- In einer Woche messen, ob die Gradle-Plätze und der Stempel nach Rebase die Wartezeiten wirklich senken.
- Nur bei belegtem Bedarf: Push für Cloud-Sitzungen (ntfy), Backlog als Issues, eigener Linux-Benutzer, gemieteter
  Rechner mit KVM (Google Cloud mit „nested virtualization“; Hetzner Cloud kann es nicht).

## 5b. Vergleich: firstmate (github.com/kunchenguid/firstmate, angesehen 2026-11-02)
Ein „Agent-Distro“: man redet mit einem **First Mate**, der eine Crew in Worktrees startet und überwacht.
- **Gleiche Ideen wie wir:** ein Ansprechpartner, Worktree je Aufgabe, Zustand auf der Platte, nur echte
  Entscheidungen eskalieren, Hooks (SessionStart, PreToolUse Bash, Stop als Weckmechanismus), Lagebericht
  (`/bearings`), Wissen sichern (`/stow`), Abwesenheit (`/afk`). Unabhängig zu denselben Schlüssen gekommen.
- **Anders:** alles auf **einem** Rechner im Terminal (tmux) – der First Mate steuert die Crew direkt, das
  Cloud ⇄ Rechner-Problem gibt es dort nicht, aber auch keine Handy-Steuerung. Sehr groß (819 Dateien, ~26 000 Wörter
  Kern-Anweisungen, > 8 000 PRs, täglich Änderungen) – ein zweites System zum Pflegen.
- **Sicherheit:** Claude-Crew startet standardmäßig mit `--dangerously-skip-permissions`, Codex ohne Sandbox; die Doku
  sagt selbst „not a sandbox“. Ohne Container hat jeder Agent alle Rechte des Benutzers.
- **Docker für uns:** schlecht passend (Pixel per USB, Emulator mit KVM, Android-SDK müssten durchgereicht werden);
  wir sichern stattdessen mit Auto-Modus + Deny-Regeln + Push-Hook. Leichter Zusatzschritt, falls gewünscht: eigener
  Linux-Benutzer für Claude auf donald (eigener `gh`-Zugang nur für OnPath, keine SSH-Schlüssel des Owners).
- **Übernommen:** `/fragen` (wie `/ahoy`). Der Wecker-Hook (`asyncRewake`) wurde gebaut und am 11-05 wieder
  entfernt – er wartete nach jeder Antwort, aber es gab nie etwas zu wecken. **Nicht übernehmen:** das Gesamtsystem.

## 5c. Irgendwann vielleicht: eigener Linux-Benutzer für Claude (owner 2026-11-02: „ganz weit in die Zukunft“)
Mehr Abstand ohne Docker: `sudo adduser claude-onpath` (Gruppen `plugdev` für das Pixel, `kvm` für den Emulator), dort
einmal `claude` anmelden, ein **eigener GitHub-Token nur für OnPath + onpath.private**, eigener Klon; SDK, Kartendaten
und die Gradle-Sperre gemeinsam lesbar. Start: `sudo -iu claude-onpath`, dann `tools/dev/unit.sh run donald`.
Haken: nur **ein** `adb`-Server pro Rechner – Owner und Claude-Benutzer kommen sich in die Quere. Aufwand ~1 h je
Rechner. Gewinn: kein Zugriff auf die SSH-Schlüssel, andere Projekte und das volle GitHub-Konto des Owners. Nicht
geplant; heute schützen Auto-Modus, Deny-Regeln und der Push-Hook.

## 6. Regeln für jede Setup-Arbeit (owner 2026-11-05, nach dem Audit)
1. **Erst recherchieren und prüfen, dann bauen.** Doku, vorhandene Lösungen (z. B. firstmate) und die echten Verläufe
   ansehen; die Plattform-Fakten mit einem Mini-Test belegen. Keine Annahme als Tatsache verkaufen.
2. **Kein Mechanismus ohne belegten Bedarf** (ein echter Fall im Backlog oder im Verlauf). Was eine Woche lang nicht
   benutzt wird, fliegt raus.
3. **Hooks, `gate.sh`, Sperren treffen alle Sitzungen zugleich:** vor dem Push in allen Kombinationen testen (Worktree,
   Rebase, anderes Repo, Rundgang, Emulator).
4. **Erst messen, dann den Owner fragen.** Eine Frage nur, wenn Daten sie nicht beantworten können.
5. **Eine Datei je Thema, überschreiben statt anhängen.** Konzept-Fassungen gehören in den Chat, nicht auf `main`.
6. **Höchstens eine Setup-Änderung pro Tag**, damit die Sitzungen nicht stündlich neue Regeln lesen.

## 7. Wo was liegt
| Thema | Datei |
| --- | --- |
| Wer arbeitet wo | `docs/roles/README.md` |
| Arbeitsweise der Rechner-Sitzungen | `docs/roles/start.md` |
| Captain | `docs/roles/captain.md` |
| Geschichte, Audit, Entscheidungen des Owners zum Setup | `docs/history/setup-2026-11/` |
| Skripte | `tools/ask.sh`, `tools/ci/gate.sh`, `tools/ci/release_check.sh`, `tools/dev/{unit,device,gradle_slot}.sh`, `tools/hooks/` |
| Erzwungene Regeln | `.claude/settings.json` (Hooks, Deny), `.claude/rules/` |
