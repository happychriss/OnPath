# Vorschlag: einfacheres Setup für OnPath (2026-11-01)

Von der Sitzung „Setup review handoff“ (Auftrag: `docs/roles/setup-review-handoff.md`, dort die Fakten).
**Nur ein Vorschlag – nichts wurde umgestellt.** Du entscheidest.

## Kurz
- **Fragen an dich:** ein Ort – GitHub-Issues mit dem Label `frage` im privaten Repo `onpath.private`. Ein Lesezeichen
  auf dem Handy = dein Dashboard. Antwort = Kommentar, erledigt = Issue zu.
- **Weniger Sitzungen:** eine Haupt-Sitzung für App + Web-Planer (auf donald), Website nur bei Bedarf. Der Captain
  als Dauersitzung entfällt; der Release-Check wird ein Skript.
- **Ein Branch:** `web-spike` nach dem Release in `main` holen. Dann gibt es keinen täglichen Abgleich mehr.
- **Kein Nachrichten-Bus im Backlog:** Backlog = nur To-do-Liste. Abstimmung über ein Issue pro Release.
- **Alles erst nach v0.14.0.** Nur die Fragen-Liste kann sofort starten, sie stört nichts.

## 1. So läuft es heute (mein Verständnis)
Vier feste Sitzungen: cloud-app (donald, Pixel), cloud-plan (Laptop, Web-Planer + Karten), cloud-web (Cloud,
Website + Marketing), cloud-captain (Cloud, Überblick). Dazu ein täglicher Auftrag um 05:47, der `main` in
`web-spike` mischt – in keiner Rollen-Datei erwähnt, überschneidet sich mit cloud-plan.

Der Captain kann den anderen schreiben, aber keiner kann ihm antworten. Antworten laufen als Commits in
`docs/backlog.md`. Geprüft: **37 von 66 Commits auf `main` in den letzten 24 Stunden** sind Backlog-, Übergabe- oder
Captain-Notizen. Die Erinnerung des Captains („E2E + review check“) ist um 00:24 mit `ACCESS_DENIED` gescheitert –
er wird also nur wach, wenn du schreibst.

Geht donald oder der Laptop aus, endet die Sitzung dort. Du legst neue an, benennst sie und fügst den Start-Text ein.
Es gibt schon zwei Laptop-Umgebungen (`hp:onpath-plan:4225`, `hp:onpath-plan:dbef`). Deine Fragen stehen an vier
Stellen: im Chat jeder Sitzung, im Backlog unter „Waiting for the owner“, in Review-Dateien (D1–D4) und in Drive.

**Kern:** Du bist der Verteiler. Das Setup hat mehr Sitzungen, als ein Ein-Personen-Projekt braucht. Und die
Kanäle zwischen den Sitzungen funktionieren nur in eine Richtung.

## 2. Zuerst: ein Ort für deine Fragen
Was es schon gibt, geprüft:

| Möglichkeit | Ergebnis |
| --- | --- |
| Sitzungsliste in der Claude-App | Zeigt „wartet“ (`needs_action`), aber nur pro Sitzung. Fragen aus Aufträgen und Helfern fehlen, kein Überblick. |
| Backlog „Waiting for the owner“ | Nur nach einem Pull lesbar, keine Benachrichtigung, Antwort = Commit. |
| **GitHub-Issues** | Funktioniert: Issues im Repo `OnPath` sind an, heute 0 offen. Cloud-Sitzungen schreiben über ihr GitHub-Werkzeug, die Rechner-Sitzungen über `gh`. GitHub-App auf dem Handy, Antwort per Kommentar. |
| Artefakt-Seite mit Datenbank | Möglich, aber neu zu bauen. Unklar, ob die Rechner-Sitzungen sie erreichen. Später als hübschere Ansicht denkbar. |

**Vorschlag:** Issues im **privaten** Repo `onpath.private`, Label `frage`. Privat, weil Fragen auch Marketing oder
Tester betreffen. Pro Frage ein Issue, immer gleich aufgebaut:

```
Titel:  [cloud-app] Debug-Build für echte Tester?
Wer:    cloud-app (donald)
Worum:  2–3 Zeilen Hintergrund
Frage:  … ?
  A) … (Empfehlung)
  B) …
Details: Link auf Datei / Commit
```

- **Du:** Lesezeichen `github.com/happychriss/onpath.private/issues?q=is:open+label:frage`. Antworte mit „A“ als
  Kommentar.
- **Die Sitzung:** liest die Antwort beim Start und nach jedem Pull, handelt danach und schließt das Issue. Erledigte
  Fragen verschwinden so aus der Liste.
- **Regel für alle (Sitzungen, Aufträge, Helfer):** Eine Frage an den Owner gibt es nur als Issue. Im Chat steht dann
  nur „Frage #12 offen“.

**Ein Haken, bitte einmal prüfen:** Die Sitzungen schreiben unter *deinem* GitHub-Konto. GitHub benachrichtigt dich
nicht über deine eigenen Issues. Die Benachrichtigung muss deshalb anders kommen. Zur Wahl:
(a) die Sitzung schickt dir eine Push-Nachricht (bei Rechner-Sitzungen mit Remote Control geht das),
(b) ein kleiner täglicher Auftrag mit Push („3 Fragen offen“), oder
(c) einfach morgens und abends das Lesezeichen öffnen.
Ein Test-Issue zeigt in einer Minute, was bei dir ankommt.

## 3. Drei Setups

### A – „Eine Werkbank“ (meine Empfehlung)
| | |
| --- | --- |
| Sitzungen | **OnPath** auf donald: App + Web-Planer, ein Branch `main`. **Website** in der Cloud, nur wenn es etwas zu tun gibt. **Karten** auf dem Laptop, nur für einen Kartenbau. |
| Start | Du schreibst eine Zeile: „Du bist onpath – lies `docs/roles/onpath.md`“. Start-Texte liegen im Repo. |
| Abstimmung | Fragen → Issues `frage`. Pro Release ein Issue „v0.15.0“ mit einer Checkliste. Backlog = To-do-Liste. Übergabe nur am Ende einer Sitzung. |
| Release-Check | Skript `tools/ci/release_check.sh` (Fingerprint, Sync-Format, Shared-Code-Tests) statt Captain-Sitzung. Mit einem Branch fällt der Großteil ohnehin weg. |
| donald aus | Die App-Arbeit wartet. Die Website läuft weiter. Wenn du dann etwas willst: eine Cloud-Sitzung mit gleichem Start-Satz (ohne Handy). |
| Täglich für dich | Fragen-Lesezeichen ansehen, antworten. Sitzung starten, wenn donald neu gestartet ist (eine Zeile). |
| Risiko | Die Zusammenlegung von `web-spike` ist einmal Arbeit (heute 1157 Commits voraus, 33 zurück). Dafür nach dem Release eine eigene, ruhige Sitzung einplanen. |

### B – „Rollen bleiben, Kanäle neu“
Wie heute vier Rollen, aber:
- Rückmeldungen über Issues statt Backlog-Commits.
- Captain als täglicher Auftrag mit Push statt Dauersitzung. Der Auftrag liest Repo + CI und legt Fragen als Issues an.
  Er braucht keine Freigabe, weil er niemandem schreibt.
- Start-Texte im Repo.

Vorteil: wenig Umbau. Nachteil: zwei Branches und der tägliche Abgleich bleiben. Rechner-Sitzungen sterben weiter mit
dem Rechner. Du verteilst weiter Arbeit auf vier Stellen.

### C – „Cloud zuerst“
Eigene Cloud-Umgebung mit Android SDK (Setup-Skript). Die Haupt-Sitzung läuft in der Cloud und überlebt jeden Rechner.
donald nur für Pixel-Tests und Emulator: eine kurze Sitzung, wenn ein Gerätetest nötig ist.
- Geprüft: Die Cloud erreicht die Android-Downloads (`dl.google.com`, `maven.google.com`), hat 4 Kerne, 15 GB RAM und
  genug Platz. Unit- und Screenshot-Tests sollten damit laufen – **nicht getestet**, ein Probelauf wäre der nächste
  Schritt.
- Geprüft: **Kein Emulator in der Cloud** (kein KVM). E2E bleibt in GitHub Actions oder auf donald.
- Nachteil: Jede neue Sitzung installiert das SDK erst (einige Minuten). Gerätetests brauchen weiter dich und donald.

## 4. Empfehlung: A, später ein Stück C
A löst die meisten Schmerzpunkte mit dem wenigsten Neuen: eine Sitzung statt drei für den Code, keine Einbahn-Kanäle,
ein Branch, Fragen an einem Ort. C ist nur nötig, wenn donald oft aus ist und trotzdem App-Arbeit laufen soll. Das
kann man später ergänzen.

### Schritte (das Release v0.14.0 bleibt unberührt)
1. **Jetzt, ohne Risiko:** Label `frage` in `onpath.private` anlegen. Ein Test-Issue, prüfen, ob eine Benachrichtigung
   ankommt. Die Regel „Fragen nur als Issue“ in `CLAUDE.md` + `docs/owner-preferences.md`. Die offenen D1–D4 als erste
   Issues anlegen.
2. **Release v0.14.0** wie geplant mit dem heutigen Setup fertig machen. Bis dahin nichts umbauen.
3. **Nach dem Release:** Captain-Sitzung beenden, die Captain-Erinnerung löschen. Den Release-Check als Skript ins
   Repo.
4. **Eine Sitzung (donald):** `web-spike` in `main` holen. Den Planer aus `main` deployen (nur bei Änderungen unter
   `web/` oder am Shared Code). Das Workflow-Update ist ein PR an dich. Danach den täglichen 05:47-Auftrag ausschalten
   und `web-spike` stehen lassen (nicht löschen).
5. Rollen-Dateien zusammenlegen: `onpath.md` (App + Planer), `web.md`, `karten.md`. Start-Texte in
   `docs/roles/start.md`. §8 in `docs/working-agreement.md` kürzen. Backlog-Abschnitte „For the roles / For the
   captain“ entfernen.
6. Alte Umgebungen aufräumen (doppeltes `hp:onpath-plan`). Das machst du in der App, ich kann es nicht.
7. Optional später: Cloud-Umgebung mit Android SDK ausprobieren (Setup C).

## 5. Fragen, die nur du beantworten kannst
1. **Fragen-Ort:** Issues in `onpath.private` mit Label `frage` – einverstanden, oder lieber eine eigene Seite
   (Artefakt)?
2. **Benachrichtigung:** Push von der Sitzung, ein täglicher Sammel-Push oder kein Push (du schaust selbst)?
3. **Ein Branch:** `web-spike` nach v0.14.0 in `main` zusammenführen – ja?
4. **Captain:** Darf die Dauersitzung nach dem Release weg (Release-Check als Skript)?
5. **donald:** Darf donald tagsüber an bleiben, damit die Haupt-Sitzung dort lebt? Oder soll die Arbeit auch ohne
   donald weiterlaufen? Dann wäre Setup C nötig: eigene Cloud-Umgebung, längere Starts.
6. **Laptop:** Kartenbau künftig nur noch über den Cloud-Run-Job? Dann bräuchte der Laptop gar keine Sitzung mehr.
