# Analyse: Ist unser Setup effizient – und was holt Claude Code noch heraus? (2026-11-01)

Von der Sitzung „Setup review handoff“, nach dem Umbau-Tag. Ehrliche Bilanz, keine Schönfärberei. Fakten zu Claude
Code aus der offiziellen Doku (code.claude.com, am 2026-11-01 geprüft); was ich nicht prüfen konnte, steht dabei.

## Kurz
1. **Das Konzept passt zum Anwendungsfall** (ein Owner, Handy, zwei Rechner, Robustheit zuerst). Die Bausteine, die
   heute getestet wurden, funktionieren. Aber wir haben **alles als Prosa in Markdown** gebaut – und Prosa ist
   **teuer** (jede Sitzung liest sie) und **unverbindlich** (eine Sitzung kann sie vergessen).
2. **Größter Hebel: weniger lesen, mehr erzwingen.** Jede Sitzung liest beim Start rund 15 000 Wörter (`CLAUDE.md`
   allein 3 700, Doku-Empfehlung: unter 200 Zeilen). Mit `.claude/rules/` (laden nur bei passenden Dateien) und
   **Hooks** (laufen automatisch, können Pushes sperren) wird das billiger und sicherer – und viele Regelsätze
   können aus `CLAUDE.md` verschwinden, weil ein Skript sie durchsetzt.
3. **Was Claude Code bietet und wir noch nicht nutzen:** Hooks (SessionStart, PreToolUse), `.claude/rules/` mit
   Pfaden, Skills (laden erst bei Aufruf), eigene Unteragenten (`.claude/agents/`, mit Worktree-Isolation), Deny-Regeln
   (greifen in jedem Modus – ideal für die Handy-Regeln), `--spawn worktree` für den Gastgeber.
4. **Nicht alles auf einmal.** Fünf Schritte in Reihenfolge (§5), jeder einzeln messbar.

## 1. Was wir gebaut haben – und was es kostet
| Baustein | Nutzen | Kosten / Schwäche |
| --- | --- | --- |
| Fragen + „an alle“ als Issues (`ask.sh`) | ein Ort, Handy, beide Rechner – heute von cloud-app schon benutzt (#13) | Push vom Captain noch ungetestet; ohne `gh` stiller Ausfall (nur Warnung) |
| `gate.sh` + `an-alle`-Sperre | einzige **erzwungene** Regel | nur, wenn die Sitzung `gate.sh` auch aufruft – ein nacktes `git push` umgeht sie |
| Gastgeber + Leitstand + Arbeiter je Vorhaben | Sitzungen überleben Neustart, eigener Worktree je Arbeit, gelber Punkt je Arbeit | **jeder Arbeiter zahlt den vollen Start** (Dokulesen ~15 000 Wörter ≈ 20–25 k Token) bevor er eine Zeile Code schreibt; Weckruf nur stündlich |
| Captain (Cloud, ohne Karte) | Überblick, Fragen, Push | kann Rechner-Sitzungen weder starten noch anschreiben; Reaktion ≤ 1 h |
| Sichern beim Ausschalten | nichts geht verloren | noch nicht als Dienst installiert |
| Geräte-Sperren (`device.sh`) | Unteragenten kollidieren nicht | nur wirksam, wenn alle sie benutzen (wieder Prosa) |
| Rollen-Dateien alt + neu, Working Agreement §8, Konzept, Umstellung | Nachvollziehbar | **8 700 Wörter in 10 Dateien**, davon die Hälfte Übergangszustand – muss nach dem Branch-Merge auf 2 Dateien zusammen |

**Lesemenge beim Start einer Sitzung heute:** `CLAUDE.md` 3 724 Wörter + `owner-preferences` 824 + `project-status`
3 381 + `backlog` 4 285 + Rollendatei ~1 000 ≈ **13 000 Wörter**, bei jedem Arbeiter, jedem Weckruf des Leitstands,
jedem Cloud-Arbeiter. `project-status` und `backlog` sind zur Hälfte Geschichte (alte Übergaben, erledigte Listen).

**Verbindlichkeit heute:** fast alles ist Bitte („read at the start…“, „only through device.sh…“). Erzwungen sind nur
`an-alle` in `gate.sh` und die GitHub-Regeln (kein Force-Push). Claude Code hat dafür bessere Mittel (§3).

## 2. Passt das Konzept zum Anwendungsfall? Ja – mit zwei Vorbehalten
- **Ja:** Du willst vom Handy steuern, nicht Verteiler sein, nichts verlieren, zwei Rechner als Pool. Genau das
  leisten Issues + Leitstand + Gastgeber + Sichern. Die Tests von heute belegen die Mechanik.
- **Vorbehalt 1 – Latenz:** Leitstand und Captain reagieren im Stundentakt (Plattform-Minimum). Für „Fix vom Handy
  jetzt“ tippst du daher direkt in den Leitstand – das ist in Ordnung, muss aber klar sein.
- **Vorbehalt 2 – Token:** Drei Arbeiter + Leitstand + Captain am Tag = fünf- bis zehnmal die volle Doku lesen. Das
  ist der eigentliche Effizienzverlust, nicht die Skripte. §3 B löst das.

## 3. Was Claude Code bietet – geprüft – und wie wir es nutzen sollten
### A. Hooks (settings.json) – Regeln, die wirklich gelten
Hooks laufen automatisch bei Ereignissen; `.claude/settings.json` liegt im Repo, gilt also auf donald, Laptop und in
der Cloud (einmal prüfen: Remote-Control-Sitzung + Cloud-Sitzung).
- **SessionStart** (stdout wird Kontext): ein Skript `tools/hooks/session-start.sh` gibt aus: offene `an-alle`,
  Antworten auf eigene Fragen (`ask.sh offen`), vorhandene `wip/`-Branches, die obersten 10 Zeilen von „Next“. Damit
  entfallen die Schritte 1–4 der Runde als Prosa – sie passieren einfach. (Bei Fork-Unteragenten feuert er ebenfalls –
  Doku: „startup/resume/clear/compact/fork“.)
- **PreToolUse** auf `Bash` mit `git push`: verweigert (exit 2), wenn `gate.sh` für diesen HEAD nicht grün gelaufen
  ist (Stempeldatei `.git/onpath-gate-ok` = SHA) oder ein `#push-sperre` offen ist. Damit ist „kein Push ohne Tor“
  und „an alle“ **erzwungen**, nicht erbeten. Gleiches für `adb install` ohne `device.sh`.
- **Stop**: optional „Claim entfernt? Übergabe geschrieben?“ – ein Stop-Hook kann die Sitzung weitermachen lassen.

### B. `CLAUDE.md` abspecken mit `.claude/rules/` – weniger Token je Start
- Doku-Ziel: < 200 Zeilen `CLAUDE.md`. Wir: 213 Zeilen, 3 724 Wörter (die Repository-Karte allein ist ein Drittel).
- `.claude/rules/*.md` mit `paths:` im Frontmatter laden **nur**, wenn die Sitzung Dateien dort anfasst: `ui.md`
  (Message-System, Typo, Farben → `app/src/main/java/com/onpath/app/ui/**`), `routing.md`, `map-style.md`
  (`tools/make_style.py`, `tools/themes/**`), `web.md` (`web/**`), `release.md`. `@import` hilft **nicht** (lädt mit).
- `CLAUDE.md` behält: was OnPath ist, Prinzipien, Build/Test in 10 Zeilen, Git, Fragen/an-alle in 5 Zeilen, Verweis
  auf Rules. Ziel ≤ 120 Zeilen. **Ersparnis grob: 60–70 % der Startkosten je Sitzung.**
- `project-status.md`: „Last session“ auf die letzten 3 Einträge, Rest nach `docs/history/`. `backlog.md`: „Next“
  kurz halten (Top 15), Erledigtes raus (steht in Release-Notes / Git).

### C. Deny-Regeln – die Handy-Regeln endlich hart
`permissions.deny` greift **in jedem Modus**, auch `bypassPermissions`: `Bash(adb uninstall*)`, `Bash(adb shell pm
clear*)`, `Bash(git push --force*)`, `Bash(./gradlew --stop*)`. Dazu `allow` für `tools/ask.sh *`, `tools/ci/gate.sh
*`, `gh api repos/happychriss/*` (Lesen). Hinweis Doku: `auto` als Modus ist in **Projekt**-Settings wirkungslos –
nur per Flag (macht `unit.sh`) oder in `~/.claude/settings.json` auf dem Rechner.

### D. Skills – Abläufe, die nur bei Aufruf laden
`.claude/skills/<name>/SKILL.md`: nur Name + Beschreibung sind immer im Kontext, der Rest erst beim Aufruf.
Kandidaten: `/runde` (die Leitstand-Runde, `disable-model-invocation` – nur du oder der Weckruf), `/frage`
(Fragebox → `ask.sh frage`), `/release` (`release_check.sh` → Version + Notizen → Push), `/umstellung`,
`/feierabend` (`save-on-shutdown.sh dry` + Übergabe). Das ersetzt Absätze in `start.md`/`captain.md`.

### E. Eigene Unteragenten (`.claude/agents/*.md`)
`worker.md` (`isolation: worktree`, `permissionMode: auto`, Werkzeuge beschränkt), `tester.md` (nur `gate.sh`,
kein Edit), `pixel.md` (nur über `device.sh`). Die „Chat bleibt ansprechbar“-Regel entspricht dem **Fork**-Standard
von Claude Code (Unteragent erbt das Gespräch; an per Default, `CLAUDE_CODE_FORK_SUBAGENT`) – die Regel in `CLAUDE.md`
kann dann auf einen Satz schrumpfen. Unteragenten laufen auch in Remote-Control-Sitzungen (Doku); Cloud: zu prüfen.

### F. Gastgeber: Flags statt Tastendruck
Doku nennt `--spawn worktree` und `--capacity N` für `claude remote-control`. Dann entfällt das „`w` drücken bis
worktree“ aus `umstellung.md` A.0, und `unit.sh` ruft `claude remote-control --spawn worktree` auf. (Die Hilfe der
hier installierten CLI zeigte die Flags nicht – auf donald mit `claude remote-control --help` prüfen; Version dort
2.1.316.) Headless: der Prozess muss laufen (tmux/systemd), Doku bestätigt.

### G. Auto-Memory
Existiert (`~/.claude/projects/<repo>/memory/`), ist aber **je Rechner** und nicht in der Cloud. Unser Repo-Gedächtnis
bleibt richtig; Auto-Memory darf an bleiben (ergänzt lokal), ist aber kein Ersatz.

### H. Was wir **nicht** brauchen
Eigenes MCP, Artefakt-Dashboard, Cloud-Setup-Skript (Entscheidung 6), noch mehr Rollen. Die Doku-Mittel reichen.

## 4. Risiken, ehrlich
- **Über-Engineering für eine Person:** Leitstand + Captain + Arbeiter + Hooks sind mehr bewegliche Teile als „eine
  Sitzung auf donald“. Rechtfertigung: Handy-Steuerung und Neustart-Sicherheit. Gegenmittel: §5 schrittweise, nach
  jedem Schritt eine Woche beobachten; was nicht trägt, fliegt (alles einzeln rückbaubar).
- **Hooks sind scharf:** ein fehlerhafter PreToolUse-Hook blockt jeden Push. Darum: Hook sagt im Fehlerfall klar, was
  fehlt, und `ONPATH_HOOKS=0` schaltet ihn ab.
- **Rules mit `paths:`:** eine Sitzung, die eine UI-Datei nie öffnet, kennt die UI-Regel nicht – gewollt, aber beim
  Review bedenken (Reviewer öffnet die Dateien → Regel lädt).

## 5. Reihenfolge (jeder Schritt klein, messbar; nichts davon vor „split done“ auf donald nötig)
1. **Hooks** `session-start` + `pre-push` (½ Tag): erzwingt an-alle/Tor/Gerät, streicht vier Prosa-Absätze.
   Messen: kein Push mehr ohne grünes Tor (Git-Log).
2. **`CLAUDE.md`-Diät + `.claude/rules/`** (½ Tag): Ziel ≤ 120 Zeilen; `project-status`/`backlog` kürzen. Messen:
   Token beim Sitzungsstart (Diagnose in der App) vorher/nachher.
3. **Deny/Allow-Regeln** (1 h): Handy-Regeln hart, weniger Nachfragen.
4. **`--spawn worktree`** in `unit.sh` + Autostart-Dienst (1 h, nach Prüfung des Flags auf donald).
5. **Skills + Agenten** (`/runde`, `/frage`, `/release`, `worker`, `tester`) (1 Tag): Abläufe aus den Rollen-Dateien
   in Skills, Rollen-Dateien auf je 1 Seite.
Danach (nach Branch-Merge): alte Rollen, §8, Konzept-Datei → `docs/history/`.

## 6. Nicht geprüft
- Ob Repo-`settings.json`-Hooks in Remote-Control- und Cloud-Sitzungen feuern (einmal testen: Schritt 1).
- `--spawn` / `--capacity` in der CLI-Version auf donald.
- Unteragenten in Cloud-Sitzungen.
