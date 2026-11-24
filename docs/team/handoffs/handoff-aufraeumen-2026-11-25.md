# Handoff [aufräumen] – 2026-11-25 ~00:30 (night mode)

Start a fresh session from here: read CLAUDE.md, `docs/team/owner-preferences.md`, then this file. Questions to the
owner only as a question box (his word 2026-11-24) – but **not at night** (night-job rules: self-answer, one line in
`items/<date>-night-answers.md`).

## Done (all on main, gate green each time)
- **Cleanup on donald:** inventory `items/2026-11-24-cleanup-inventory.md`; 105 clean worktrees removed, 133 GitHub
  branches deleted (the owner ran `~/aufraeumen-2026-11-24-zweige-loeschen.sh` himself – the session's permission check
  blocks remote branch deletion), 19 GB build leftovers freed (his box: only that; Docker / old emulators / Gradle caches
  stay). B / C groups (50 + 5 worktrees, 4 + 2 branches) kept. hp → [laptop] (Next).
- **Flaky tests:** evidence list `items/2026-11-24-flaky-tests.md`. Fixed here: Rundgang flow 10 chip wait (30 s). The
  three web smokes: [laptop] took them. Playwright install retry: patch in that item – **the donald token has no
  `workflow` scope, the pull request could not be pushed** (owner / another token).
- **Hard-case flows** (audit `items/2026-11-24-hard-case-test-coverage.md`), all in `state/…/ui/tour/` unless noted:
  `GpsLostFlowTest`, `TourKillFlowTest`, `JumpFixFlowTest`, `ClockJumpFlowTest`, `PermissionRevokedFlowTest`,
  `state/…/ui/MapAreaNetworkLostFlowTest`, `app/…/plansync/PlanSyncCutFlowTest`. Testkit: `FakeRecording` runs the
  service's real core pieces (pinned by `TestKitTest.theFakeRecordingCopiesTheServicesLines` – change the fake with the
  service); `core/src/testkit/…/FlakyLink` (TCP relay that cuts / drops / refuses).
- **Bugs those tests found, fixed (test first):**
  1. distance after a killed process short by the metres since the last 10 s stats write → `TourRun.resumed` counts at
     least the saved points;
  2. a GPS jump reached navigation / arrival / trip pace before PointFilter → false off-route + re-route, or "arrived"
     2 km early → `RecordingService.onFix` judges first, a jump returns (RecordingService at its size cap 1774 lines);
  3. clock set back → "to go" / arrival frozen for that hour → `TripPace.refresh` restarts the display;
  4. plan sync: lost PUT answer + a second edit → "(copy)" and back to the first edit → `SyncMerge.reconcile` keeps our
     newer version over our own write (parity blob updated; shared code, the planner gets it on merge).
- Notes in `docs/core-features/C4-recording.md`, `C5-navigate.md`, `C16-pair-browser.md`.

## Second session (night 2026-11-25, ~00:30–01:30) – all on main, gate green each time
- Storage-full flow (#6) merged; its two bugs fixed test-first (2ae6f3da0): the tick's write clears `saveFailed` and
  the ongoing notification (inside `writePoints`); the "written late" line counts every late point (`withNewPoint`).
  RecordingService still at its cap 1774.
- `SchemaUpgradeFlowTest` (state): every tour-DB version 1–9 (+ v3 with `rides`, no `acc`) → active tour offered,
  stats, points, numbering intact, 30 more on top. No bug. The upgrade steps are a mirror of `TourDb.onUpgrade`
  (Android-only); the real helper is in the emulator `TourDbMigrationTest`.
- `BatterySaverFlowTest` (state; `FakeRecording.batterySaverChanged`, pinned): told once per tour, silence = lost GPS,
  fixes back = a new piece, no distance jump. No bug.
- Bug fixed test-first (0fc09442e): **Save** of an unfinished tour kept the row's stale distance → now at least the
  saved points' length, as Continue (`TourEndSlice.saveUnfinished`; `TourEndFlowTest.saveOfAKilledTourCountsAtLeastItsSavedPoints`).
  Ascent/descent/time still up to 10 s stale on both paths (cannot be rebuilt from points the same simple way).
- `PlanSyncCutFlowTest` :110 was red on GitHub by timing (ConnectException vs SocketException on a refused link) –
  test only, 5f140fa9e.

## Open
- **Morning, owner awake:** the reboot receiver test (`TourInterruptedReceiver`, BOOT_COMPLETED / MY_PACKAGE_REPLACED)
  needs Robolectric = build config = a pull request (the session merges it itself once green + a second model read it).
- At the queue's cap the "written late" line still says "none lost" though the oldest were given up (minor, log only).
- `ClockJumpFlowTest.onTheWayIsTheTourTimeAfterTheClockWentOn` stays `@Ignore` (minor: "on the way" 1 h long after a
  clock set ON by hand; summer time does not do it) – the owner may decide.
- Audit's flow list (`items/2026-11-24-hard-case-test-coverage.md`) is done except the receiver test.
- None of the fixes is released; they go with the next update (release only on the owner's word).
