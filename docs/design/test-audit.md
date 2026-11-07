# Test audit – app and planner (2026-11-06)

Owner order 2026-11-06: review and audit the tests of the app and the browser planner, then throw out what does not
earn its keep.

**Status 2026-11-07:** app steps (a)–(d) of §5 done by donald in the night – unit tests 2,840 → ≈ 2,610, screenshots
547 → 373, source-text pins → behaviour tests; `PlanSession`, `TourController`, `RoutesState` out of AppViewModel, each
with a random-flow property test (`PlanFlowPropertyTest`, `TourFlowPropertyTest`, `RoutesFlowPropertyTest`).
**Open:** ≈ 110 pure + ≈ 90 mixed source-text tests (wiring without a JVM harness); (e) `SafeAreaE2ETest` and the E2E
merges; the planner part (§6 "For the laptop" – `robust-smoke` still not in `web.yml`, `node-smoke.mjs` still there).
The tables below are the analysis of 2026-11-06 (method and verdicts); counts are of that day.

Measured on `main` @ 3881dc07 (unit-test reports of the last full run, snapshot folder, `git log --since=7.days`).
Scripts: kept outside the repo; the counting rule is "a test is SOURCE when it reads `src/main`, `../strings`, `../web`
… as text and asserts on that text".

## 1. Summary

| | before | after (planned) |
| --- | --- | --- |
| Unit test files (`app/src/test`) | 327 | ≈ 305 |
| Unit `@Test` | 2,550 (Gradle: 2,648 incl. other source sets) | ≈ 2,350 (−130 source, −≈ 60 snapshot functions, 55 source → 40 behaviour, +≈ 10 harness / property) |
| … of which source-text tests | 202 (18 files only source, 60 mixed) | ≈ 15 (architecture rules only) |
| Snapshot images | 488 (24.9 MB, 46 classes, 265 screens) | ≈ 330 (17 MB) |
| Unit-test run time (sum of classes) | 106 s | ≈ 75 s |
| E2E `@Test` (androidTest) | 54 in 16 files | ≈ 50 in 14 files |
| Planner tests (`web/`) | 33 scripts (1 runs nowhere) + 4 JVM classes + 3 host suites | 28 scripts, all run; 4 moved to `web/app/tools/`, 1 dropped |

Run time is not the problem (106 s, a third of it in one test). **Maintenance is:** in 7 days 227 commits touched
`app/src/test`; the top files are `ScreenshotTest` (59 commits) and `GermanScreenshotTest` (37), then source-text
tests that pin the current wording of the code: `PlanActionsTest` 10, `TourActionBarTest` 10, `SharedCoreRuleTest` 9,
`WholeRouteTest` 8, `SavePathsTest` 6, `PlanSheetLayoutTest` 6. Each UI change re-writes these tests; none of them
caught any of today's field bugs – the owner found all of them on the phone. Snapshots: 115 commits changed
3,599 images in 7 days; 1,196 of those were an EN image re-recorded together with its DE twin (in 92 commits).

Run time by kind: behaviour 54 s · snapshot 29 s · source text 12.6 s. Slowest single test:
`CellDownloadTest.requestsFileIsNeverMissingWhileItIsRewritten` 18.9 s (3,000 rewrites; 300 prove the same) –
shrink it, keep it.

## 2. App – KEEP / CONVERT / DROP

**KEEP (the bulk, ≈ 2,000 behaviour tests):** every pure rule / logic test (routing, navigation, cues, voice,
recording, rides, sync, cells, search, GPX, backup), every field-bug reproduction, every `Core*Test`. No change.

Source-text tests kept on purpose – they are **architecture rules** over the whole tree, not pins on one function:

| File | Why it stays |
| --- | --- |
| `map/FontGlyphsTest` | asset invariant: a missing glyph range = a blank label on the map |
| `map/PositionDotOnTopTest` | rule over every file: no layer above the position dot (owner) |
| `routing/SharedCoreRuleTest` | core stays free of Android / TeaVM-friendly (iPhone, web) |
| `ui/AmericanEnglishTest` | text invariant over all string files |
| `ui/ViewModelInitOrderTest` | the 0.9.0 start crash; replace by "construct the VM" once the harness (§5.1) exists |
| `ui/FirstPointHintTest.theOwnersWords` | owner-chosen words stay (the code half drops) |

**CONVERT – intent matters, the test must run the code** (needs the plan-session harness, §5.1, unless noted):

| File (source tests) | Intent to keep as behaviour |
| --- | --- |
| `ui/SaveDialogTest` (9) | C18: proposal, typed name, empty name keeps proposal, overwrite renames in one write, copy proposes "(2)" |
| `ui/SavePathsTest` (3 of 9) | plan survives the process; replacing an unsaved plan asks; plan-from-track is one undo step. The `today…` tests DROP (they described the state for save-paths.md) |
| `ui/ClearPlanDuringTourTest` (4) | invariant "clear empties plan + plan guidance, never the recording" |
| `ui/TourEndPathsTest` (4) | all three tour ends run the ride check and save – C11/C12 "never lose a track" |
| `recording/ArrivalInServiceTest` (2) | review H2: arrival checked in the service on every fix (pure test on the service's fix handler) |
| `ui/BackgroundRoutingRuleTest` (2) | overwrite checks the stored route first (C16/C18 data loss) |
| `ui/FollowLineLegsTest` (2 of 4) | a newer edit cancels the follow line; new routing data clears the leg cache |
| `recording/ModeQuietTest` (5) | no arrival / off-route / app voice while in Plan – one table test on a pure "what may speak" rule |
| `recording/QuestionNotificationTest` (2 of 5) | navigation stop stays quiet until Continue; ids distinct (the latter is pure already) |
| `ui/PlanStatusTest` (3) | long-route dialog stays until answered; cancel after an edit takes it back; wait bar never moves back |
| `ui/PlanActionsTest` (1 of 4) | "close plan asks only with unsaved changes" |
| `ui/PlanSheetLayoutTest` (1 of 7) | "green button from two points, never during a tour" → pure rule function |
| `ui/UxReviewFixesTest` (1 of 4) | first-start dialogs one after the other → pure queue rule |
| `ui/TourEditTest` (2 of 4) | during a tour "new plan" clears through the one clear path, recording goes on |
| `ui/TourLoadTest` (8) | C4: loading a route during a tour – continue follows it, "end and load" only after the end, cancel loads nothing |
| `ui/PlanGuidanceTest` (3) | clear during a tour ends the guidance; an edited plan replaces a followed track; one point left goes "from here" (today's loop) |
| `ui/OfflineReadyTest` (2 of 4), `ui/SharedPlaceMenuTest` (2 of 4) | cell state → "here / loading / missing" mapping; same share opens the menu again – pure rules |

≈ 55 source tests → ≈ 40 behaviour tests.

**DROP – checks only the text of the code, the screenshot or E2E shows the same:**

| Block | Tests | Why |
| --- | --- | --- |
| Snapshot EN twins (rule: keep DE + large-font DE; drop the EN image of every screen that has a DE twin at the same font size; EN-only screens stay) | 156 images, 7.8 MB: `ScreenshotTest` 86, `PlanChipLargeFontTest` 16, `SetupScreenshotTest` 12, `SlideLabelLargeFontTest` 11, `BackgroundDownloadScreenshotTest` 7, 12 small classes 24 | German words are longer – the layout risk lives in DE; the EN twin doubles every re-record (1,196 twin re-records in 7 days) |
| Pure layout / wiring pins in `ui/` | `PlanActionsTest` 3, `PlanSheetLayoutTest` 6, `TourActionBarTest` 5, `BackToNavigationTest` 5, `FoundPlacePinTest` 7, `WholeRouteTest` 6, `CandidateMarkerTest` 4, `SavedTourBoxTest` 3, `NavSimChipTest` 1, `FirstPointHintTest` 1, `UxReviewFixesTest` 3, `TourEditTest` 2, `SharedPlaceMenuTest` 2, `OfflineReadyTest` 2, `NoviceFixesTest` 3, `PlanColourTest` 3, `MessagePlacementTest` 1, `BackgroundDownloadsTest` 3 | they assert that a function contains a string; a rename breaks them, a real layout bug passes them; the snapshot of the same screen is the check |
| Remaining source halves of mixed files | ≈ 50 tests in ≈ 35 files (1–3 each: `Rd5PartsTest`, `RoutingFingerprintTest`, `RouterClassHashTest`, `PlanSizeTourTest`, …) | same reason; the behaviour half of each file stays. Each one read before it goes – a field-bug pin moves to CONVERT |
| `SavePathsTest` `today…` (6) | — | documented today's state for a proposal, not a rule |

Total DROP ≈ 130 source tests + 156 images (≈ 60 snapshot test functions / variant classes).

## 3. E2E (androidTest)

| File | Verdict | Reason |
| --- | --- | --- |
| `CoreDeleteE2ETest`, `CoreFieldE2ETest`, `CorePointsListE2ETest`, `CorePlanLinkE2ETest`, `CoreBackupE2ETest`, `NoStaleDownloadsE2ETest`, `PhoneSetupE2ETest`, `CoreIndoorStartE2ETest` | KEEP | the core features under real conditions (C1, C9, C11, C13, C15, C17, C4/C5) |
| `StartSmokeE2ETest`, `ReleaseRundgang` (+ `RundgangMailbox`) | KEEP | release gate and the owner's flow list |
| `TourDbMigrationTest` | KEEP | schema upgrades – a broken migration loses every tour |
| `CorePlanE2ETest` | DROP → merge | hold → point is in `CoreDeleteE2ETest` and `CoreFieldE2ETest`; move its one unique assert (C2 insert on the line) there |
| `FreshInstallE2ETest` + `RoutingDataFreshInstallE2ETest` | merge into one | same start state (nothing on the phone), two app launches |

## 4. Core-feature coverage after the clean-out

Every feature C1–C18 keeps at least one behaviour test (checked against the class lists in `docs/core-features.md`):

| Feature | Behaviour tests that stay (examples) |
| --- | --- |
| C1/C2 points on the map | `CoreHoldPointTest` 12, `CoreDeletePointTest` 8, `MapGesturesTest` 28, `WaypointInsertionTest` 16 + E2E |
| C3 planning | `PlannerTest`, `WaypointSnapTest`, `PlanGapsTest`, `OnPathHintsTest` |
| C4/C5 tour + navigation | `NavigatorTest` 22, `JunctionCuesTest` 24, `OffRouteDetectorTest` 24, `RerouteTargetsTest` 18, `TrackReplayTest`, … |
| C6/C10/C13/C14 maps and routing data | `CellDownloadTest` 27, `CellTriggersTest` 21, `RoutingDataAutoTest` 23, `MapCleanupTest` 7 |
| C7 import | `GpxParserTest`, `SportTypeTest` 19, `PlanGpxTest` |
| C8 update | `AppUpdaterTest`, `UpdateDownloadTest` |
| C9 point list | `PlanPointsTest` 24, `PlanHapticsTest` + E2E |
| C11 backup | `BackupTest` 21, `MapsNeverTouchToursTest` + E2E |
| C12 rides | `RideDetectorTest`, `RideViewTest`, `RideApplyTest`, `RideBackupTest` |
| C15/C16 link + sync | `PlanCodeTest` 16, `PlanLinksTest`, `CorePlanSyncTest` 20, `PlanSyncTest` 29 |
| C17 phone setup | `PhoneCheckTest` 16, `SettingsSummaryTest` + E2E |
| C18 save | `SaveDialogTest` (5 + converted), `RouteRulesTest` 15, `PlanLinkTest` |

Fix in `docs/core-features.md` with the clean-out: C17 names `SetupGuideTest` (does not exist); C18 lists
`PlanActionsTest` (source only – replace by the converted `SaveDialogTest`); `UxReviewFixesTest` under C17 likewise.

## 5. Gaps and plan

The day's field bugs (one-point loop, end sheet cut off, button jumping) slipped through 2,550 green tests. What
was missing:

1. **No test drives the real AppViewModel without the emulator.** `AppViewModel` is 4,557 lines, an
   `AndroidViewModel` holding `TourDb` directly; the 200 source tests exist because it cannot be run on the JVM.
   *Plan:* extract a plain-Kotlin `PlanSession` (points, edits, undo, clear, save/overwrite, guidance on/off, tour
   start/end hand-off) behind two small interfaces (`PlanStore`, `Router`), the VM delegates to it. Slice 1 = plan
   state + clear + save (≈ 1 day); slice 2 = tour hand-off + guidance. No new dependency (Robolectric would be one –
   only with the owner's OK). The CONVERT list moves onto it. Fits `SharedCoreRuleTest` (iPhone / web share it).
2. **No random flows with invariants.** *Plan:* one `PlanFlowPropertyTest` on `PlanSession`: seeded
   `kotlin.random` (no library), 200 runs × 50 steps of add / insert / move / delete / undo / redo / clear / save /
   load / start tour / end tour / process restart; the failing seed and step list printed. Invariants after every
   step:
   - **"Plan lässt sich immer leeren"** – clear ⇒ no points, no line, no plan guidance; the recording untouched.
   - **"Gespeicherter Plan geht nie verloren"** – every saved route stays loadable byte-equal until an explicit delete;
     an overwrite never hits another route.
   - **"Plan zeigt = Navigation fährt"** – the navigator's route geometry equals the plan's line after every edit.
   - **"Rechnet nie endlos"** – each action triggers a bounded number of recalcs (would have caught today's
     one-point loop).
   - **"Kein Knopf springt"** – the bar's buttons come from a pure `barFor(state)` function; invariant: the green
     button's slot never changes between consecutive states of one flow. (Pixel positions: see 3.)
   Runs in < 2 s. The same file runs in the web's JVM job (shared code) – planner parity for free.
3. **Paparazzi cannot see window insets** (the end sheet cut off was found on the phone). *Plan:* one E2E
   `SafeAreaE2ETest` – gesture navigation + a display cutout on the emulator, opens each sheet / dialog of the
   Rundgang and asserts every button's bounds lie inside the safe area. On GitHub with the next E2E run.
4. **Snapshot = picture, not a check of rules.** Keep DE + large DE as the layout guard; no new variant classes
   without a new risk (a new screen gets DE, plus large DE when it has a row of buttons).

Order of work after the six branches merge: (a) DROP source blocks + EN twins (one commit each, gate --full,
re-record only the touched classes), (b) shrink `CellDownloadTest`, (c) `PlanSession` slice 1 + CONVERT,
(d) `PlanFlowPropertyTest`, (e) E2E merges + `SafeAreaE2ETest`.

## 6. Planner (`web/`, on main) – KEEP / CONVERT / DROP

| Test | Verdict | Reason |
| --- | --- | --- |
| `plan-parity.mjs` + `ParityRun.kt`, `parity/check.mjs`, `browser-compare.mjs`, `sync-engine.mjs`, `gpx-engine.mjs`, `lang-engine.mjs` | KEEP | Wasm = JVM = app – the "same plan, same route" promise |
| `WebImportTest`, `WebSearchTest` (field bug 0.13.2), `WebSyncJoinTest`, `WebSyncRoutingTest` | KEEP | JVM rules of the web side |
| `host/test/host`, `maps`, `sync` `.test.js` | KEEP | the mailbox and map host rules |
| `texts-check.mjs`, `nowebgl-smoke.mjs`, `cells-smoke.mjs`, `areas-smoke.mjs`, `version-smoke.mjs`, `share-smoke.mjs`, `snap-smoke.mjs`, `follow-smoke.mjs`, `activity-look-smoke.mjs`, `overview-smoke.mjs`, `lang-smoke.mjs`, `edit-smoke.mjs`, `ui-smoke.mjs` (profile, 3D, fly-along) | KEEP | robust-first rules and owner reports in the browser |
| `sync-smoke.mjs` (722 lines, 19 commits / 7 d), `import-smoke.mjs` (23), `parity-smoke.mjs` (13), `sheet-smoke.mjs` (10) | KEEP, slim | keep the flows; drop asserts on pixel offsets and exact texts the snapshot / `texts-check` already cover |
| `robust-smoke.mjs` | CONVERT → wire in | 11 commits in 7 days, but **run nowhere** (not in `web.yml`, not in `check-web.sh`) – into the `short` smoke group, or it is dead |
| `search-smoke.mjs` | KEEP (local) | needs the cell store, so only `check-web.sh`; fine |
| `node-smoke.mjs` | DROP | run nowhere; `browser-compare` does the same in the real browser |
| `perf.mjs` | MOVE | a measurement, not a check – keep it in CI as a report step, not as a gate |
| `pair-shots.mjs`, `ui-shots.mjs`, `terrain-shots.mjs`, `browsers-check.mjs` | MOVE to `web/app/tools/` | picture generators / manual check, not tests |

### For the laptop (planner owner)

1. Wire `robust-smoke.mjs` into `web.yml` (group `short`) and `check-web.sh` – or drop it; today it guards nothing.
2. Delete `node-smoke.mjs`; move the three `*-shots.mjs` and `browsers-check.mjs` to `web/app/tools/`.
3. `perf.mjs`: report only (`continue-on-error`).
4. Slim `sync-smoke` / `import-smoke` / `parity-smoke` / `sheet-smoke`: flows stay, pixel offsets and copied
   texts go.
5. When the app's `PlanFlowPropertyTest` lands (shared code), check it runs in the web's `jvm` job.
