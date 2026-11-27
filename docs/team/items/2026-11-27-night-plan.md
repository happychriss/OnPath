# Night 27./28.11. – plan (captain, owner's go 2026-11-27 23:01: „nichts braucht mehr meine Zustimmung“)

Owner's word (attached to every thread by the project lead): night preparation, nothing needs his approval; tomorrow a
planner and a phone that match; resume the map build so the maps are done tomorrow; if all went well a release
tomorrow – **first on his Pixel, then the release**. Night-job rules + „Nachtregel gilt“ in `owner-preferences.md`
apply. Taste questions → `VISION.md`, a line in `2026-11-27-night-answers.md` (`Q … → A … (role, time)`).

## Merge queue to `main` (each step: rebase on main, gate, push, report the commit)
| Step | What | Who | Starts when |
| --- | --- | --- | --- |
| 1c | long-ask wiring test `long-ask-seed` | Suchkategorie (donald) | now (1b on main 531afdba1) |
| 2 | `feat/multiday-days-main` | Mehrtagestouren (donald) | 1c pushed |
| 2b | `fix/activity-picker` | Mehrtagestouren | 2 pushed |
| 3 | `feat/kachel-goal` | Suchkategorie | 2b pushed |
| 4 | `chore/web-aufraeumen` | HP | now (web only, independent) |
| 5 | `chore/aufraeumen-0.18.11-on-main` | Wartefenster (donald) | 3 pushed |
| 6 | `feat/wait-step-bar` + MapScreen:242 + PlanTopBar redo (`creditBlockDp`) | Wartefenster | 5 pushed |
| R | release **0.18.12** | Wartefenster | 6 pushed + HP's „parity done“ |

Push order is the order of the table; a thread that is ready early waits for the commit before it. A red step stays
out (morning report), the next one goes on.

## Planner (HP) – parity by the morning
4 → cancel fix with „Berechnen“ → parity lines from today (step bar, plan sheet spacing c18236a84, multi-day badge +
day choice, activity picker if the cloud check finds it) → multi-day sheet draft (no „Fahrrad ▾“). Deploy when green
(standing rule). Last: tell the project lead „parity done“ with the commit. The release run deploys the planner too.

## Map build (donald, **Wartefenster thread owns it** – resumed 23:03, 570/725, 1 failed, ETA ~01:18)
Low priority beside the Gradle runs; rebuild cell 10-538-357 after the run; the end + `failed.txt` to the project lead.
Publish from donald with `tools/dev/publish_cells.sh <cells…>` (TEST R2 fast lane, the doorman adopts them,
`docs/design/map-hosting.md` §7h). HP does nothing for the upload; the doorman deploy (`maps-deploy.yml`) only if the
worker changed. Suchkategorie does not touch it.

## Release R (Wartefenster)
`tools/ci/release_check.sh --go v0.18.12`, `gate.sh --full`, `tools/ci/e2e_github.sh` (core classes) → install on the
Pixel (`adb install -r`, save a running tour, check the log) → version 0.18.12 + `docs/release-notes/v0.18.12.md`
(DE/EN plain words, delete v0.18.11.md) → push main → release.yml. Not before ~06:00 and not before HP's „parity done“;
red → no release, the Pixel build stays, morning report.

## Cloud
Tracker thread: planner picker check, then browser layout + logo – pick by `VISION.md`, push to main; ships with the
release run. Captain: morning report ~06:30 in `2026-11-28-morning-report.md`, German.
