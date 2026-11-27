# Night 27./28.11. – plan (captain, owner's go 2026-11-27 23:01: „nichts braucht mehr meine Zustimmung“)

Owner's word (attached to every thread by the project lead): night preparation, nothing needs his approval; tomorrow a
planner and a phone that match; resume the map build so the maps are done tomorrow; if all went well a release
tomorrow – **first on his Pixel, then the release**. Night-job rules + „Nachtregel gilt“ in `owner-preferences.md`
apply. Taste questions → `VISION.md`, a line in `2026-11-27-night-answers.md` (`Q … → A … (role, time)`).

**State 00:19: steps 1c–9 all on main** (4 web tidy b5fe760d8, 7 day table e90b39462…cc5055f84 + parity be5b5d3d8, 8 drift fix 659bbfba0…65d357660, E2E 115/0). Open: HP's „parity done“, map build end, release 0.18.12 in the morning with the Pixel.

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
| N | release notes 0.18.12 draft (DE/EN, `docs/release-notes/v0.18.12.md` on branch `release/0.18.12-notes`, not pushed to main) + second-model read of `feat/wait-step-bar` | Suchkategorie | ✓ notes `release/0.18.12-notes` @ 83f3846f0; review `2026-11-27-step6-review.md` (must-fix: wrong piece fill) – findings 1–4 into step 6 before its push (Wartefenster), 5 to HP |
| 7 | app multi-day day table (core `DayTable.kt`, item 2026-11-27-app-day-table) + HP's multi-day sheet parity | Mehrtagestouren → **HP integrates** | `feat/app-day-table-main` @ 5e8c6b839 (gate green); donald's push was refused (touches web/ = HP's area: days_* rename, multiday.ts) → HP checks the web part against his work, merges, web smokes + gate, pushes by 05:30. Header with Speichern + „Gesamte Reise“ (needs MapScreen) stays open → morning report |
| 8 | `fix/position-drift-main` (owner: into 0.18.12; second read clean, GapSplit should fixed, 30 s) | Suchkategorie | push after green E2E, may go before 7 (no overlap) |
| 9 ✓ main 74e4f931a (ActivitySwitchSheet during a tour now in the new look; SacChips unused → tidy later; not on the Pixel, a recording ran) | one screen still opens the old activity menu on the bike tap (owner 23:52, Pixel) – route it to the new picker | Mehrtagestouren | branch from main, test, gate; push by 05:30 if no file overlap with 7/8, else after them |
| R | release **0.18.12** | Wartefenster | 6 pushed + HP's „parity done“ + build on the Pixel (tomorrow, Pixel unplugged) |

Push order is the order of the table; a thread that is ready early waits for the commit before it. A red step stays
out (morning report), the next one goes on.

## Planner (HP) – parity by the morning
**23:54: planner online (version.json 2931061af): 1b, parity 4 rounds green, tracker 01afd2dc, multi-day sheet, cancel fix,
map wait line 4061a3038; CI + gate --full green.** Now step 7; „parity done“ after it.
4 → cancel fix with „Berechnen“ → parity lines from today (step bar, plan sheet spacing c18236a84, multi-day badge +
day choice, activity picker if the cloud check finds it) → multi-day sheet draft (no „Fahrrad ▾“). Deploy when green
(standing rule). Last: tell the project lead „parity done“ with the commit. The release run deploys the planner too.

## Map build (donald, **Wartefenster thread owns it** – resumed 23:03, 570/725, 1 failed, ETA ~01:18)
Low priority beside the Gradle runs; cell 10-538-357 was already built today 18:52 (s10), no rebuild. The unit runs
`tools/dev/publish_area.sh`, which builds **and publishes** each cell into TEST R2 itself (`map-hosting.md` §7h);
`publish_cells.sh <cells…>` only for cells in `failed.txt`. The end + `failed.txt` to the project lead. HP does nothing for the upload; the doorman deploy (`maps-deploy.yml`) only if the
worker changed. Suchkategorie does not touch it.

## Release R (Wartefenster)
`tools/ci/release_check.sh --go v0.18.12`, `gate.sh --full`, `tools/ci/e2e_github.sh` (core classes) → install on the
Pixel (`adb install -r`, save a running tour, check the log) → version 0.18.12 + the notes from `release/0.18.12-notes` (Suchkategorie's draft; delete v0.18.11.md) → push main → release.yml. Not before HP's „parity done“; red → no release, morning report.
**Owner unplugged the Pixel at 23:06:** the night goes up to a release-ready `main` (checks green, notes written on a
branch, not pushed). The version push waits until the owner plugs the Pixel in tomorrow and the build is on it.

## Field bug (owner 23:32): position wanders while the phone lies still
Suchkategorie (donald): ask where he sees it, read the code path (RestGps / RestWatch, live upload) and the live data if
reachable, a reproducing JVM test, a fix on a branch. Recording / live = core: per `VISION.md` it ships only after a real
tour – not in 0.18.12 unless it is only a display filter; field log from the Pixel tomorrow.
**State 23:39:** cause found (dot takes every fix; PointFilter.judge takes the first fix after a gap unchecked), fix on
`fix/position-drift` @ 26b9799cb, gate green. It changes the **track** (PointFilter) → captain: no merge before the
0.18.12 release; second-model read tonight; after the release a test build on the Pixel for the owner's next real tour,
then main → 0.18.13. REST_AFTER_MS 30 s goes on the same branch.
**Owner 23:39: „Dieser Fix kann jetzt schon ins Release.“** – his explicit exception to the real-tour line of `VISION.md`.
Now step 8: second-model read (tunnel case: no real way lost after a gap) + 30 s with test → gate --full → main by
05:30. A must-fix from the read that is not fixed by then → stays out, morning report.

## Cloud
Tracker thread: planner picker check, then browser layout + logo – pick by `VISION.md`, push to main; ships with the
release run. Captain: morning report ~06:30 in `2026-11-28-morning-report.md`, German.
