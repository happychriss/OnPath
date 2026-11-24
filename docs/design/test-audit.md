# Test audit – app (refreshed 2026-11-18 for row T of the architecture plan; planner part of 2026-11-06 kept)

Owner, 2026-11-18: „So viele Testzeilen. Wir wollten risikobasiertes, verhaltensbasiertes Testen und nicht Millionen kleiner Tests.“
Row **T** of `architecture-plan-2026-11.md` §5: unit test lines 61,000 → under 35,000, no core feature left without a behaviour test, source-text
tests ≤ 15, unit run under 3 min. The clean-up runs **piece by piece inside the rebuild**: the tests of a package are sorted KEEP / CONVERT / DROP
**before** that package moves (§8 gives the order). This file is the list the lead and the reviewers work from.

**Summary.** 490 unit files · 55,624 lines · 3,635 tests (+ 5,778 E2E lines = the owner's 61,000). Verdicts: KEEP 360 files · CONVERT 99 files in 18 groups · DROP 28 files (1,753 lines) · `?` 3. Lines after verdicts: 48,164 (screenshots 4,291 of it); with the levers of §1.5 ≈ 41,311. Source-text tests 132 → 14 permanent (+ 3 temporary; ≤ 15). Class time 202 s → ≈ 106 s with the loop-shrinks (§7). **Target 35,000 is not reached by what can be proven by reading**; features at risk of losing coverage: **C18** (until SaveFlowTest exists), **C4 / C5 service side** (until RecordingServiceHarnessTest), **C8** (small tests only), C14 / C12 (no E2E).

Analysis only (no test, no code, no Gradle run). Measured on `main` @ 0b0de5c17 (0.18.0, 2026-11-18). The per-file list (490 rows: path · package · lines ·
tests · kind · verdict · group · reason · convert target · guards · reads source text · time · lines after · how I checked) is **`docs/design/test-audit.tsv`**.
Test times are from the newest full unit report on this machine (502 classes, 3,754 test cases, all green; `app/build/test-results/testDebugUnitTest`, read only).

**How far I read** (column `checked` in the TSV): `body` = I read the whole file or the whole source-text test bodies (73 files, including every DROP and every source-text
test); `kdoc+test names` = class comment + all test names (the other 417 files; ~half of them have no real KDoc – their verdict KEEP is **by default, not proven**:
see §1.5 and §10). Verdict `?` = I could not tell; the question is in the `reason` column.

## 1. Totals

### 1.1 Now vs the audit of 2026-11-06

| | 2026-11-06 | now (2026-11-18) |
| --- | --- | --- |
| unit test files (`app/src/test/java`) | 327 | **490** (+ 0 in `core/` – it does not exist on main yet) |
| unit test lines | not counted then | **55,624** |
| unit `@Test` | 2,550 | **3,635** (Gradle runs 3,754 cases: parameterised) |
| E2E (`androidTest`) | 54 `@Test` in 16 files | 111 `@Test` in 26 files, 5,778 lines |
| all test lines (unit + E2E) | – | **61,402** ← the owner's "61,000" is this number, not the unit number |
| source-text tests (read `src/main` text) | 202 in 78 files | **134** tests in 81 files by my count (test-level, see §3; the plan said 83 files) |
| snapshot classes (Paparazzi) | 46 classes, 547 images | 48 files, 5,312 lines, 477 test methods (images: 551 files in `snapshots/images`) |
| unit run time (sum of class times) | 106 s | **202 s** (no parallel forks in `app/build.gradle.kts`: wall time ≈ the sum + Gradle start) |

In 12 days the suite grew by 163 files (+50 %) and ~1,100 tests: the clean-up of 2026-11-06 cut about 230; new features (multi-day, kept places, suggestions, place sync, thumbs, overview swipe, POI tap) brought the rest.

### 1.2 By verdict (lines of `app/src/test/java`)

| verdict | files | tests | lines now | lines after | meaning |
| --- | ---: | ---: | ---: | ---: | --- |
| KEEP | 360 | 2,713 | 43,132 | 42,306 | stays (incl. 31 files that only lose their source-text test(s): see §3) |
| CONVERT | 99 | 817 | 10,585 | 5,704 | folded into 18 flow / table tests (groups G01–G20, §4) |
| DROP | 28 | 83 | 1,753 | 0 | deleted (§5) |
| ? | 3 | 22 | 154 | 154 | lead decides (§5.2) |
| **all** | **490** | **3,635** | **55,624** | **48,164** | |

### 1.3 By kind

| kind | files | tests | lines now | lines after verdicts |
| --- | ---: | ---: | ---: | ---: |
| rule-risk | 194 | 1,877 | 27,151 | 24,959 |
| core-flow | 39 | 290 | 6,264 | 5,079 |
| field-bug | 46 | 337 | 5,762 | 5,634 |
| screenshot | 48 | 477 | 5,312 | 4,291 |
| format-fixture | 17 | 182 | 2,860 | 2,751 |
| unit-small | 70 | 242 | 2,658 | 2,390 |
| testkit | 18 | 10 | 1,846 | 1,706 |
| wording | 28 | 128 | 1,745 | 698 |
| source-text | 18 | 53 | 1,095 | 587 |
| tool | 5 | 10 | 683 | 0 |
| structure | 7 | 29 | 248 | 69 |

`rule-risk` is the bulk (27,151 lines): see the honesty note in §1.5.

### 1.4 By package (top level; `domain/*`, `ui/*` summed)

| package | phase | files | tests | lines now | lines after | DROP files | CONVERT groups touching it |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| ui | phase 2 | 166 | 1,169 | 15,424 | 11,597 | 21 | G04, G05, G09, G10, G11, G12, G13, G14, G19 |
| navigation | phase 1 | 31 | 381 | 5,706 | 5,690 | 0 | – |
| routing | phase 1 | 40 | 353 | 4,691 | 4,625 | 0 | – |
| domain | phase 1 | 36 | 336 | 3,978 | 3,591 | 0 | G05, G10, G11, G13, G20 |
| cells | phase 1 | 22 | 165 | 3,789 | 3,297 | 2 | G01, G10, G16 |
| data | phase 1 | 29 | 184 | 2,960 | 2,548 | 0 | G01, G09, G11 |
| search | phase 1 | 25 | 156 | 2,680 | 2,514 | 2 | G15 |
| map | later / with its code | 21 | 152 | 2,544 | 2,534 | 0 | – |
| plansync | phase 1 | 13 | 96 | 2,461 | 2,056 | 0 | G01, G02 |
| time | phase 1 | 15 | 102 | 2,222 | 1,955 | 0 | G01, G03, G14 |
| recording | phase 4 | 27 | 161 | 1,933 | 1,596 | 1 | G08 |
| sync | phase 1 | 8 | 76 | 1,678 | 1,486 | 0 | G02 |
| testkit | later / with its code | 9 | 10 | 790 | 650 | 0 | G18 |
| suggest | phase 1 | 5 | 23 | 763 | 396 | 1 | – |
| backup | phase 1 | 4 | 40 | 757 | 718 | 0 | G01 |
| debug | phase 1 | 14 | 60 | 753 | 563 | 0 | G01, G17 |
| rides | phase 1 | 5 | 30 | 591 | 576 | 0 | – |
| gpx | phase 1 | 4 | 39 | 522 | 522 | 0 | – |
| setup | phase 1 | 3 | 36 | 408 | 408 | 0 | – |
| poi | later / with its code | 5 | 32 | 387 | 315 | 0 | G15 |
| update | later / with its code | 4 | 18 | 313 | 313 | 0 | – |
| (root) | later / with its code | 3 | 8 | 140 | 80 | 1 | – |
| net | phase 1 | 1 | 8 | 134 | 134 | 0 | – |

### 1.5 Projection – does it reach under 35,000?

**No. The verdicts below, applied as written, give 48,164 unit lines (from 55,624); with the E2E lines 53,942 (from 61,402).** What I can prove by reading:

| step | unit lines | note |
| --- | ---: | --- |
| now | 55,624 | |
| DROP (28 files) | −1,753 | wording / structure / layout pins, local-only tools, characterisation of finished refactors |
| CONVERT (18 groups, 99 files) | −4,881 | estimated: ratio per group (§4), not measured |
| source-text tests inside behaviour files | −785 | the other tests of those files stay |
| **after verdicts** | **48,164** | of which screenshots 4,291 (counted separately below) |

What else would be needed (estimates, **not** verdicts – none of these can be decided by reading a KDoc; each is a decision per file at the moment its package moves):

| lever | lines | what it needs |
| --- | ---: | --- |
| L2 – table-test compression of the big pure-rule classes (≥ 12 tests, 43 files, 11,453 lines): shared fixtures, `@Parameterized`-like tables; ~20 % | −2,291 | rewrite while moving into `:core` |
| L3 – **policy decision**: rules pinned only by an owner decision (no field bug, no C-feature, no format; 76 files, 7,916 lines, e.g. VoiceGuideTest 738, PlanDaysTest 354, SmartScreenTest 265, SportTypeTest 237, CueRuleTest 231 ...) are folded into the flow tests that already drive the same state; ~35 % | −2,771 | owner's OK – several of these ARE his decisions and the only place they are written down as a check |
| L4 – phase 4b screenshots: components instead of whole screens, DE + large font only (4,291 → ~2,500) | −1,791 | phase 4b |
| **projected unit lines** | **≈ 41,311** | |

So even with L2–L4 the unit suite lands at **≈ 41,311 lines** (≈ 47,089 with E2E): a gap of ≈ 6,311 unit lines (≈ 12,089 with E2E) to 35,000.
If the target is read as **unit lines without screenshot classes** (generated-picture code, phase 4b; E2E is a different suite) the numbers are: after verdicts 43,873, after L2 + L3 **38,811** – a gap of ≈ 3,811 lines, about a tenth of the remaining rule-risk bulk. That is the share a *second* reading round can plausibly find (more twins like CellReuse / CellStoreReuse, §1.5 honesty note) **while each package moves** – I cannot prove it from here. **The target is not reachable by KEEP / CONVERT / DROP of what I could verify; it needs L2 + L3 (owner's OK) and the second round.**

**Honesty note.** 210 of the 360 KEEP files are `rule-risk` / `unit-small` kept on the strength of a KDoc that names an owner decision or a feature; ~106 of them (14,632 lines) have no marker at all (no date, no C-id, no "field"). Per row T they are KEEP only while nothing says they are twins – I looked for twins by comparing test names in the big ones (found: CellReuseTest / CellStoreReuseTest, PinMenuPlaceTest / PlaceAddMenuTest; not twins: SharedPlace* trio, Rd5PartsTest / Rd5PartStoreTest, CueRule / JunctionCues / JogRule, Navigator / ActivityCheck) but did not read every KEEP body line by line.

## 2. Kinds and verdict rules used

Kinds as in row T: `core-flow` · `field-bug` · `rule-risk` · `format-fixture` · `unit-small` · `source-text` · `screenshot` · `wording` · `structure` · `tool` · `testkit`.
Kind of the rows I did not read in full comes from the KDoc keywords ("field" / "bug" → field-bug, `Core*` / flow / property / replay → core-flow, fixture / golden / format → format-fixture, ≤ 60 lines and ≤ 6 tests → unit-small, else rule-risk); the TSV is the place to correct it.
Kind is the test's *main* purpose; a `rule-risk` file with one source-text test is still `rule-risk` and loses that test (column `reads source text`: `yes n/T: files` = n of T tests read text of main source). `no (reads assets …)` = it reads assets / strings / resources / the web host code, which is data, not source text.

- **KEEP**: field-bug reproductions (a date / "field" / "bug" in the KDoc), `Core*Test`, flow and property tests, rules with real risk (track loss, navigation decisions, sync merge, stored formats and migrations, routing cost, downloads), format fixtures, the DE and large-font screenshots that guard layout.
- **CONVERT**: several small tests of neighbouring functions that describe one behaviour → the one flow / table test named in the group (§4). A group is only dropped **after** its flow test exists and is green (G04, G08 especially).
- **DROP**: pins on wording, colours, dp numbers, code structure (sizes, init order, who calls what), source-text tests the module system or a behaviour test now covers, twins, tests that only run on the developer's computer (env var → `assumeTrue`).
- A field-bug test is never DROP, even when it is small (e.g. ProfileAxisTest, ListTopTest → CONVERT *keeps* its assertion in the flow test).

## 3. Source-text tests

Now **134 tests in 81 files** read the text of main source files (counted per test: it opens `src/main/java`, the manifest, `../strings`, `../web`, `../docs`, `../tools`, directly or through a helper). Row T wants ≤ 15.
After the verdicts: **17 tests in 11 files** (stay list below), of which 3 are *temporary* (they drop when their replacement exists) → **14 permanent (≤ 15)**.

### 3.1 Source-text tests that stay (≤ 15 permanent)

| file | tests | what it guards | why no behavioural alternative |
| --- | ---: | --- | --- |
| `SafeLaunchRuleTest` | 1 | no bare `viewModelScope.launch` (a failure there ended the app) | a rule over all call sites |
| `UserTextRuleTest` | 1 | no raw exception message / class name reaches the user (1 of 3 tests is source) | the message is built wherever an error is caught: only a scan sees all of them |
| `PositionDotOnTopTest` | 2 | no layer above the position dot (owner) | a rule over every file that adds a layer |
| `PlanLinksTest` | 1 | C15: the manifest's App Link intent filter (1 of 9 tests) | the camera hands the link over only with this exact filter |
| `RouterClassHashTest` | 3 | the app's profile hash = the cell builder's `WayClass.java` (3 of 4 tests read `tools/`) | cross-tool contract: a mismatch makes every cell look built with other profiles |
| `SharedCoreRuleTest` | 2 | core free of Android / TeaVM-friendly for web + iPhone; "TourSplit stays out of shared code" (2 of 4 tests stay) | the Android-free half is the module border once core/ exists (drop then) |
| `RouterProfileTest` | 1 | `RouterProfile.keyValues` equals the engine's private copy – **temporary** | drops when both read the same constant |
| `AmericanEnglishTest` | 1 | no British spellings in the English strings | text invariant over all string files |
| `HardCodedTextRuleTest` | 1 | every screen text comes from the strings (EN + DE) | a hard-coded word cannot be found by running a screen |
| `ViewModelInitOrderTest` | 2 | property order in AppViewModel (the 0.9.0 start crash) – **temporary** | replace by "construct the VM" once the harness exists, then drop |
| `PlayBuildTest` | 2 | Play build: no REQUEST_INSTALL_PACKAGES, no self-updater (store policy) | manifest + settings text of a build type |

### 3.2 What replaces the other source-text tests

| replaced by | source-text tests | files |
| --- | ---: | --- |
| deleted inside a KEEP file (the behaviour tests of the file stay; wiring is covered by the E2E Rundgang / flow tests) | 42 | BrouterUserAgentTest, TourSchemaTest, RouteWarningsTest, SharedPlaceMenuTest, ReliefAutoTest, StartAtPointOneTest, SyncFilesTest, SyncMailboxE2ETest, SyncedRoutingTest, PoiHitTest, PoiSpriteTest, RideViewTest, PlanEditsTest, SlowPlanTest, SuggestCoreTest, SuggestServiceTest, OneTimeModelTest, ActivityPickerSizesTest, BackStaysRuleTest, CorePlanLinkTest, FarDestinationTest, FirstMapCardTest, LongRouteCancelCameraTest, MapFooterCreditTest, NextStopTest, NoticesTest, PlanGpsTest, PlanLayersTest, ShortRecordingBoxTest, StopTextTest, WholeRouteTest |
| deleted / trimmed (2026-11-19: SharedFilesRuleTest, FontSizeRuleTest stay as rule tests; ColourRuleTest cut 2026-11-20 on the owner's word) | 20 | done: AppViewModelSizeTest gone; source-text tests trimmed out of MapControlsAlwaysThereTest, MessagePlacementTest, PageMotionTest (kept again: owner-marked), PlanSheetLayoutTest, SavedTourBoxTest (only `nothingInThePlanSheet` stays), StoppedTextTest, TourBottomBarTest |
| the flow / table test of G04 (SaveFlowTest) | 14 | BackgroundRoutingRuleTest, PlanActionsTest, PlanAutoSaveTest, SaveDialogTest, SavePathsTest |
| RecordingServiceHarnessTest (G08, phase 4; wiring tests stay until it exists) | 11 | GpsWatchdogTest, InterruptedLogTest, ModeQuietTest, OffRouteCueTest, QuestionNotificationTest, RecordingClientTest, StillSenseTest, TourRunTest |
| the flow / table test of G05 (TourEndFlowTest) | 11 | ArrivalBoxTest, FollowLineLegsTest, TourEndPathsTest, TourRunningTest |
| the flow / table test of G09 (fold into an existing rule test) | 6 | FoundPlacePinTest, PlanSizeTourTest, UxReviewFixesTest |
| the flow / table test of G13 (RoutesListFlowTest) | 4 | ActivityFilterTest, RoutesOpeningTest |
| the flow / table test of G10 (MapLoadFlowTest) | 3 | BackgroundDownloadsTest, MapLoadWaitTest |
| the flow / table test of G20 (PlaceAddMenuTest) | 1 | PinMenuPlaceTest |
| the flow / table test of G15 (SearchRulesTest) | 1 (kept: no behaviour test drives showSharedPlace) | SharedRecentTest |
| undecided (`?`, §5.2) | 1 | FirstPointHintTest |
| the flow / table test of G12 (SettingsPagesTest) | 1 | SettingsSummaryTest |

## 4. CONVERT groups (every CONVERT row carries its group id in the TSV)

| group | files | lines now → after (est.) | the one test it folds into | what it must assert so nothing real is lost |
| --- | --- | ---: | --- | --- |
| G01 | `BrokenBackupIndexTest`, `BrokenCellFilesTest`, `BrokenFollowFileTest`, `BrokenPlanPrefsTest`, `BrokenRoutingFilesTest`, `BrokenSmallFilesTest`, `BrokenTourJsonTest`, `BrokenProblemsFileTest`, `BrokenSyncFilesTest`, `BrokenTimesFilesTest` | 1,069 → 480 | `BrokenFilesTest (table-driven, in testkit as `BrokenInputs`)` | For each stored-file reader (the 10 files below name them) x {missing, empty, cut, garbage, wrong type, huge}: no throw, one `Problems` entry (`ProblemsRule.said`), the other files untouched, the next start works. Keep the per-reader "next start still works" asserts. The slow part (BrokenSyncFiles 8.7 s, BrokenCellFiles 1.3 s) shrinks with shared temp folders. **Note:** Moves with data / backup / time / debug / plansync / cells: build the helper first, convert each file when its package moves. |
| G02 | `StuckOutboxTest`, `SyncMarksTest`, `SyncPlacesTest`, `SyncTrackTest` | 679 → 408 | `CorePlanSyncTest / CorePlaceSyncTest (rounds) + PlanSyncTest (formats)` | One round per item kind (route, imported track, kept place): save, rename, delete both ways with the mailbox rules (claim, 409, ended, offline); the "New / Changed" mark appears on the other side and goes when opened; a stuck outbox is said after three passes. The item / ledger / merge format asserts per kind stay as fixtures in PlanSyncTest. **Note:** C16 is the heaviest feature (6 k lines): convert only after SyncRulesRandomTest + CorePlanSyncTest are green on the new module. |
| G03 | `PaceLevelsTest`, `PaceModelTest`, `TimeBreakdownTest`, `WayTimesTest` | 430 → 257 | `TimeEstimateTablesTest` | One table test: level (5, ~15 % apart, DIN 33466) x way attribute (SAC, surface, smoothness, MTB scale, steps) x slope -> km/h; breakdown per span; migration of the old three levels. **Note:** Pure, low risk. |
| G04 | `BackgroundRoutingRuleTest`, `PlanActionsTest`, `PlanAutoSaveTest`, `SaveDialogTest`, `SavePathsTest` | 507 → 228 | `SaveFlowTest (on PlanSession / RecentPlans, plus the JdbcTours store)` | C18: every Save opens the dialog with the proposal ("A -> B", else "Route 31 Oct, 10:12"), typed name wins, empty name keeps the proposal, overwrite renames in one write and never hits another route, overwrite first checks whether sync changed the stored route (review sync H3), copy proposes "(2)", "Los geht's" saves a never-saved plan, the end of a tour neither asks nor clears the plan, the plan survives a process restart, replacing an unsaved plan asks, plan from a track is one undo step. **Write this test before dropping the 7 source tests of SavePathsTest.** C18 has no E2E. **Note:** Needs the plan-session harness (exists: PlanSession, JdbcTours). |
| G05 | `PlanGuidanceTest`, `ArrivalBoxTest`, `ClearPlanDuringTourTest`, `FollowLineLegsTest`, `RouteBackTest`, `TourEditTest`, `TourEndPathsTest`, `TourRunningTest` | 608 → 273 | `TourEndFlowTest (next to TourFlowPropertyTest)` | All three tour ends (End tour, "You have arrived" -> Save, "Interrupted tour" question after a restart) run the ride check and save; arrival box offers Continue / End tour; "new plan" during a tour clears plan + guidance and never the recording; one point left goes "from here"; an edited plan replaces a followed track; deleting the goal of a 2-point plan asks "Route back?"; an empty plan during a tour says the tour still runs; a newer edit cancels the follow line and new routing data clears the leg cache. **Note:** Rides on TourController / PlanSession; no emulator. |
| G08 | `ArrivalInServiceTest`, `GpsWatchdogTest`, `InterruptedLogTest`, `ModeQuietTest`, `OffRouteCueTest`, `QuestionNotificationTest`, `RecordingClientTest`, `StillSenseTest`, `TourRunTest` | 696 → 420 | `RecordingServiceHarnessTest (phase 4, on FakeNavService / FakeRecording)` | Per GPS fix the service checks arrival (also with the screen off, review H2); no voice / junction cue in Plan, quiet after "Stopp" until Continue; off-route is said (voice or notification); a second tour on the same service starts a fresh run; the saver warning is said once; the question notification takes the quiet channel while the app is visible; `RecordingClient` commands = the service's actions (both ways); a vanished tour row is rescued (TourSchemaTest). **Note:** **Until the service is split these wiring tests are the only guard: keep them, drop them only with the harness test.** Phase 4, high risk. |
| G09 | `RoutingDataNoticeTest`, `FoundPlacePinTest`, `NoviceFixesTest`, `PlanSizeTourTest`, `UxReviewFixesTest` | 315 → 80 | `fold into an existing rule test (listed per file)` | Each file keeps only its pure rule: RestoreOfferRule table -> PhoneCheckTest; tap-shows-hold-hint -> MapGesturesTest; PlanSize back-in-plan / End tour one drag -> SheetDragTest; RoutingDataNotice kind/keys -> RoutingDataAutoTest; found-pin rules -> plan-session flow. **Note:** Mostly deletes source text; small. |
| G10 | `MapLoadServiceTest`, `LoadSmootherTest`, `OfflineReadyTest`, `BackgroundDownloadsTest`, `CellCardsTest`, `LoadNamesTest`, `MapLoadCancelTest`, `MapLoadProgressTest`, `MapLoadWaitTest`, `MapNoticeTest`, `MapUpdatesTest`, `OfflineResultTest`, `RoutingLoadsTest` | 980 → 540 | `MapLoadFlowTest (a fake CellSource / doorman, the FakeDoorman exists)` | C6 / C10 / C13: one overall % that never goes back (0-80 preparing, 80-99 download, 100 done); modal vs card vs chip vs notification text by state; Cancel = stop and nothing half-written; an update shows a veil while loading and is never a mix of two versions; routing data is never named; background strip shows only what counts; "Ohne Netz" pill mapping; the one plain line after Clean up. **Note:** Feature-critical (C6 / C10 / C13 / C14) - keep NoStaleDownloadsTest, CellTriggersTest, RoutingDataAutoTest as they are. |
| G11 | `HoldSettingTest`, `SheetDragTest`, `UnlockWindowTest`, `ExpandGestureTest`, `HoldRuleTest`, `SheetRiseTest` | 382 → 230 | `SheetAndLockGestureTest` | Hold lock: only a full band fires, early release goes back + hint, one hold unlocks 10 s, hold time setting + migration of the old switch; plan sheet: tap and drag open / close, settles on one of three steps, the first route raises it once. **Note:** Pure rules, low risk. |
| G12 | `ProblemFoldTest`, `SettingRowsTest`, `SettingsOnTheWayTest`, `SettingsSummaryTest`, `SyncSettingsTest`, `SetupTextsTest` | 666 → 333 | `SettingsPagesTest` | Settings topics: row order (Navigation first, then only the topics that make sense on the way), value / summary per topic, sync status line, phone-setup "why" text never contradicts its state, diagnostics list folded. **Note:** Wording-heavy: assert the state -> text key mapping, not the German sentence. |
| G13 | `RoutesTabTest`, `ListTopTest`, `OverviewPagesTest`, `RoundTripTest`, `RouteSortTest`, `RouteTagTest`, `ActivityFilterTest`, `RoutesOpeningTest` | 460 → 276 | `RoutesListFlowTest (next to RoutesFlowPropertyTest)` | My routes: tabs (Planned / Done), activity filter families and "Alle", sort order, every opening starts the same way ("Neueste zuerst"), New / Changed tags, round-trip label, swiping through overviews, list read order after a save (E2E 2026-11-07). **Note:** Low risk. |
| G14 | `TourReviewTest`, `ReviewTextTest` | 231 → 185 | `TourReviewTest (absorbs ReviewTextTest)` | What a done tour's stored points give and nothing they do not; the words follow the same data. **Note:** Low risk. |
| G15 | `PoiFilterTest`, `SearchCategoryTest`, `SearchNotesTest`, `SearchTextTest`, `SharedRecentTest` | 207 → 178 (done 2026-11-19: 11 tests; core `PoiFilterTest` + `SearchTextRulesTest`, app `SearchRulesTest`; the source-text test of SharedRecentTest is kept) | `SearchRulesTest` | Six chips table (each kind in at most one chip), search text normalisation, POI filter, the two owner decisions in search notes, shared place goes to Recent. **Note:** Low risk. |
| G16 | `CellReuseTest`, `CellStoreReuseTest` | 474 → 308 | `CellReuseFlowTest` | A new map version downloads only the files that changed (same sha256 -> from the phone); a damaged file is downloaded again; no installed version / no hashes -> full download; kept files that no longer fit -> dropped and loaded in full; the map is never a mix of two versions. The store side (`CellStore.reusable`) is asserted through the same flow. 4.8 s + 1.3 s today. **Note:** Twins by scenario (damaged file, does-not-fit) - names compared, bodies not read line by line. |
| G17 | `DiagnosticsTest`, `FrameStatsTest`, `HealthLineTest`, `MainStallTest`, `MapFpsTrialTest`, `MapJankTallyTest`, `MapLibreLogLevelTest`, `ScreenLifecycleTest` | 343 → 188 | `DiagnosticsSmallTest` | pmtiles header reader, fps-trial parse, stall classification, jank tally, frame stats, health line, old ERROR entry not announced again, event log names each screen. **Note:** Low risk; they guard diagnostics, not features. |
| G18 | `TestKitTest` | 255 → 115 | `TestKitSmokeTest` | Proves the kit itself (ManualExecutor, FakeRecording, ProblemsRule) in 3 short tests; the tour lifecycle is proved by TourFlowPropertyTest (4.7 s today). **Note:** Low risk. |
| G19 | `GermanScreenshotTest`, `ScreenshotTest` | 2,158 → 1,187 | `ComponentShots (phase 4b)` | Whole-screen pictures (EN and DE) -> pictures of components (message card, status card, sheet header, chips ...) in DE + largest font only; EN twins drop where a DE twin exists. Lines drop mostly with 4b, not before: GermanScreenshotTest borrows the bodies of ScreenshotTest. **Note:** Count the lines separately (see §1.4). |
| G20 | `PinMenuPlaceTest` | 125 → 62 | `PlaceAddMenuTest (fold)` | The menu-content half of PinMenuPlaceTest (empty plan, round trip, one point, clear stop, doubtful, during a tour) is the same zone table as PlaceAddMenuTest.theMenuPerZone; the 7 placement tests (above the pin / bottom card by measured height) stay. **Note:** Names compared only. |

Ratios are estimates (G01 .45, G02 .6, G03 .6, G04 .45, G05 .45, G08 .6, G09 .25, G10 .55, G11 .6, G12 .5, G13 .6, G14 .8, G15 .65, G16 .65, G17 .55, G18 .45, G19 .55, G20 .5). The person who rewrites a group re-measures.

## 5. DROP and `?` rows

### 5.1 DROP (28 files, 1,753 lines, 83 tests) – each read whole

| file | package | lines | tests | kind | reason |
| --- | --- | ---: | ---: | --- | --- |
| `NetReaderTest` | suggest | 288 | 4 | tool | needs E5_N50.rd5 on the developer's computer; skipped everywhere else; the rule is proved by NetSearchParityTest + SuggestCoreTest fixtures (twin: NetSearchParityTest)|
| `RealCellsTest` | cells | 152 | 3 | tool | optional tests on real cells built locally; skipped in CI|
| `StoreWriteCostTest` | cells | 108 | 1 | tool | optional measurement (ONPATH_STORE_BENCH=1); never runs in CI|
| `PlanColourTest` | ui | 104 | 8 | wording | pins hex values and contrast numbers of the plan colours; design value|
| `ColourRuleTest` | ui | 89 | 5 | source-text | five source-text tests pin which Kotlin line uses which colour token; a colour change in the theme rewrites it; screenshots show colours|
| `PlanSheetLayoutTest` | ui | 81 | 5 | wording | button words, foot height, source text of PlanPanel; the screenshots show the sheet; the 5 asserts of `PlanFoot.of(ui)` (GO from two points, BACK during a tour, none below) fold into PlanStartTest|
| `BreakdownTest` | ui | 80 | 2 | unit-small | frozen copy of the old Compose tables ("as before") against BreakdownRules; twin: routing/BreakdownRulesTest (6 tests, same shared rules; compared by KDoc and names, not line by line)|
| `AddressSpeedLocalTest` | search | 74 | 1 | tool | optional local timing on real map cells (env var); never runs in CI; a measurement, not a check|
| `AscentCompareTest` | recording | 61 | 1 | tool | a report that runs only with real terrain tiles that are not in the repo; skipped in CI and on every normal run; AscentCounterTest proves the rule|
| `SharedFilesRuleTest` | (root) | 60 | 3 | source-text | the module system guards the border once piece D of phase 1 is on main (web builds from core/); until then it is the only guard of the shared list|
| `FontSizeRuleTest` | ui | 57 | 3 | source-text | source-text lint over ui/ (hand-set fontSize) + design scale numbers; a lint rule would do the same; not on the stay list|
| `MessagePlacementTest` | ui | 56 | 3 | source-text | 12 dp gap rule plus three source-text tests that read Screens.kt / TopBar.kt / SettingsScreen.kt for dp literals; layout, screenshots show it|
| `PlanControlsPlacementTest` | ui | 55 | 6 | structure | pins fixed positions / pill widths of the map controls (owner design of 2026-11-06 / 08); layout|
| `TourBottomBarTest` | ui | 54 | 3 | wording | width-fit table of the tour bar (dp numbers) + source text; layout, large-font screenshots show a cut word|
| `StoppedTextTest` | ui | 47 | 3 | wording | pins German strings of the stopped head and that a key is gone; wording. The clock-time assert (1 line) moves into NavStatusTextTest|
| `MapControlsAlwaysThereTest` | ui | 46 | 3 | source-text | two of three tests read MapFooter.kt; the third pins a tint alpha; the screenshots show greyed controls|
| `SavedTourBoxTest` | ui | 42 | 2 | source-text | both tests read AppViewModel / Screens / PlanPanel source for names; ReleaseRundgang.flow07 checks the saved-tour box for real|
| `TopBarLayoutTest` | ui | 34 | 4 | structure | pins dp numbers of the top bar fit rule; the large-font screenshots show a cut title|
| `GermanSearchTextsTest` | search | 32 | 4 | wording | whole German sentences of search / POI / import; wording, the DE screenshots show them|
| `CompassColourTest` | ui | 32 | 3 | wording | pins colour contrast numbers of the compass needle; design value, the screenshot shows it|
| `AppViewModelSizeTest` | ui | 31 | 1 | structure | pins a line count of AppViewModel; the rebuild (phases 2-4) shrinks it by moving code, no behaviour guarded|
| `RecycleBinNoteTest` | ui | 29 | 2 | wording | pins one German sentence of the recycle-bin note; wording (StringFilesTest and the DE screenshots see strings)|
| `ButtonActivityNameTest` | ui | 28 | 2 | wording | pins two label words (MTB / E-MTB / Gravelbike) of the activity; wording|
| `PageMotionTest` | ui | 28 | 3 | source-text | durations of a fade + a source test that every page is wrapped in PageFade; structure|
| `TourActionBarTest` | ui | 26 | 1 | wording | contrast numbers of the tour bar colours; design value|
| `FooterPlacementTest` | ui | 22 | 4 | structure | pins pixel placement of the map controls; layout, the large-font screenshots and SafeAreaE2E see it|
| `BackToNavigationTest` | ui | 20 | 1 | structure | pins the band height of the plan sheet (dp numbers); screenshots + E2E show the button|
| `CompassSpecTest` | ui | 17 | 2 | structure | pins that the needle gets bearing 0 in heading-up (a recompose optimisation); structure|

`SharedFilesRuleTest` is DROP **only with piece D of phase 1** (until then it is the one guard of the shared-file list). The five local-only tools (AscentCompare, AddressSpeedLocal, StoreWriteCost, RealCells, NetReader) are skipped by `assumeTrue` everywhere but one computer: if the owner wants the measurements they move to `tools/dev/` as scripts, they stop being test lines. `Rd5PartStoreTest` also holds two `live*` tests that need the network – check they are skipped in CI (not verified).

### 5.2 `?` – I could not tell

- `RouteRevealTest` (map, 69 lines): pins per-frame fractions of the route-drawing animation (owner polish 2); design
- `FirstPointHintTest` (ui, 21 lines): pins the owner's empty-plan words (EN + DE) in the strings XML; the 2026-11-06 audit kept it as "owner words stay" – question for the lead: is a text pin the right guard, or review + the DE screenshot?
- `InstrumentTest` (ui, 64 lines): number formatting like an instrument (big digits, small units); design

## 6. Core-feature coverage after the clean-up

Source: the tests named in `docs/core-features/C*.md` with my verdict, plus the E2E classes (`app/src/androidTest/java/com/onpath/app/e2e/`). "Behaviour tests" = KEEP rows that are not screenshots. **Flag** = would be left with none, only small tests, or depends on a flow test that does not exist yet.

| C | behaviour tests that stay (unit, tests) | E2E | flag |
| --- | --- | --- | --- |
| C1 | CoreDeletePointTest (8), CoreHoldPointTest (12), MapGesturesTest (31) | CoreDeleteE2ETest | ok |
| C2 | MapGesturesTest (31), PlanEditsTest (14), WaypointInsertionTest (16) | – | ok (shares MapGesturesTest with C1 / C19) |
| C3 | IslandPointTest (3), LegCacheTest (16), PlanGapsTest (9), PlannerLegsTest (6), PlannerTest (10), ReferenceRoutesTest (4), RouteRulesTest (15), WaypointSnapTest (10) | – | ok; the doc names 5 tests that do not exist (IslandTest, LegsVsWholeTest, OnPathHintsTest, SpurSpliceJvmTest, SpurStopJvmTest are tools/ or gone) – fix `C3-routing.md` |
| C4 | AppModeTest (8), AscentCounterTest (7), CardFitTest (4), DemTest (6), LegFilesTest (10), NavStatusTextTest (5), PauseWatchTest (3), PlanStartTest (8), PointFilterTest (11), RouteRowActionsTest (14), RouteRulesTest (15), RoutesGoTest (5), ShortRecordingBoxTest (3), ShortRecordingTest (7), StoredLineTest (8), TourFlowPropertyTest (7), TourPlanCopyTest (12), TourSchemaTest (13), TrackReplayTest (3) → converts: ArrivalBoxTest, ListTopTest, SavePathsTest, TourRunTest, UnlockWindowTest | CoreFieldE2ETest, CorePlanE2ETest | ok today; **recording itself (service) is covered by wiring tests (G08) only until RecordingServiceHarnessTest exists**; few E2E classes record on a device (CoreBackupE2E, CoreIndoorStartE2E, CoreFieldE2E) |
| C5 | ActivityCheckRulesTest (4), ActivityCheckTest (22), ArrivalGateTest (11), CueRuleTest (22), DemoLocationTest (13), FarStartTest (14), FollowHistoryTest (2), GoalSideTest (3), JogRuleTest (15), JunctionCuesTest (26), NavReplayTest (11), NavTextTest (9), NavigatorTest (24), OffRouteDetectorTest (29), OffRouteStepsTest (6), PlanLayersTest (9), PocketLogicTest (8), ProvisionalTest (10), RerouteFitTest (9), RerouteTargetsTest (18), RoundTripLeadTest (1), RoutingSpotTest (2), StartAtPointOneTest (12), StopSkipTest (14), TourChannelsTest (7), TourGpsOnlyTest (4), TourSplitTest (20), VoiceGuideTest (50), VoiceNowTest (10), VoiceTextTest (4), WaypointInsertionTest (16) → converts: OffRouteCueTest, TourRunTest | CoreFieldE2ETest | ok (heaviest: 33 tests, 5.9 k lines); service side = G08 |
| C6 | CellDownloadTest (31), CellStoreTest (20), DataStorageTest (8), StorageNotReadyTest (2), StyleLoaderTest (6) | – | ok |
| C7 | GpxParserTest (13), NewTourSacTest (2), OneTimeModelTest (7), PlanGpxTest (4), SportTypeTest (19) | – | ok; no E2E (import / export not driven on a device) – small risk |
| C8 | AppUpdaterTest (7), UpdateDownloadTest (1) | – | **only small tests** (AppUpdaterTest 7, UpdateDownloadTest 1; the doc omits UpdateFallbackTest 6 and net/HttpDownloadTest 8 which also guard it); no E2E |
| C9 | PlanHapticsTest (5), PlanPointsTest (24) | CorePointsListE2ETest | ok |
| C10 | CellTriggersTest (24), FarDestinationTest (7), MapCleanupTest (9), OfflineCorridorTest (3), Rd5PartStoreTest (14), Rd5PartsTest (7), RoutingDataAutoTest (24) → converts: MapLoadCancelTest, RoutingLoadsTest | CorePlanE2ETest, RoutingDataFreshInstallE2ETest | ok |
| C11 | BackupNotReadyTest (8), BackupTest (26), MapsNeverTouchToursTest (1) | CoreBackupE2ETest | ok |
| C12 | OwnerTaserTourTest (3), RideApplyTest (5), RideBackupTest (4), RideDetectorTest (13), RideViewTest (9), TourSchemaTest (13) | – | ok; no E2E; screenshot of the summary only |
| C13 | CellTriggersTest (24), NoStaleDownloadsTest (11), RoutingDataAutoTest (24) → converts: BackgroundDownloadsTest | NoStaleDownloadsE2ETest | ok |
| C14 | CellDownloadTest (31), MapCleanupTest (9), MapsNeverTouchToursTest (1), StorageNotReadyTest (2) | – | **no E2E; behaviour = CellDownloadTest / MapCleanupTest / MapsNeverTouchToursTest** – fine but the Clean-up flow itself is only a screenshot (SettingsScreenshotTest) |
| C15 | CorePlanLinkTest (5), PlanCodeTest (16), PlanLinksTest (9) | CorePlanLinkE2ETest | ok |
| C16 | BrowserLinkTest (6), CorePairingLinkTest (1), CorePlaceSyncTest (10), CorePlanSyncTest (34), PairEntryTest (5), PlanSyncTest (37), SyncCryptoTest (7), SyncFilesTest (4), SyncPollTest (12) → converts: RouteTagTest, SyncMarksTest, SyncPlacesTest, SyncSettingsTest, SyncTrackTest | SyncMailboxE2ETest | ok (the most tests of all; G02 folds 4 files into CorePlanSyncTest – do it after the module move) |
| C17 | PhoneCheckTest (16), VoiceSetupTest (15) → converts: SettingsSummaryTest, UxReviewFixesTest | PhoneSetupE2ETest | ok; SettingsSummaryTest → G12 |
| C18 | PlanLinkTest (4), RouteRulesTest (15) → converts: SaveDialogTest | – | **no E2E and no surviving unit test until SaveFlowTest (G04) exists** – SaveDialogTest / SavePathsTest are the C18 tests and both convert; RouteRulesTest + PlanLinkTest alone do not drive the dialog. Write G04 first |
| C19 | MapGesturesTest (31), PlaceMenuMeasureTest (4), PoiHitTest (9) | CorePoiTapE2ETest | ok (MapGesturesTest + PoiHitTest + CorePoiTapE2E; PoiHitTest loses 3 source tests) |

Other E2E classes not named by a feature file: `ReleaseRundgang` (18 flows, the release gate), `StartSmokeE2ETest`, `FreshInstallE2ETest`, `RoutingDataFreshInstallE2ETest`, `UnfinishedTourSetupE2ETest`, `MultiDayTourE2ETest`, `RoundTripSuggestE2ETest`, `KeptPlacesE2ETest`, `OverviewSwipeE2ETest`, `RouteThumbE2ETest`, `RoutesActivityFilterE2ETest`, `DoneTourReviewE2ETest`, `CorePoiTapE2ETest`, `CorePlanE2ETest`, `CoreFieldE2ETest` (35 tests, 1,528 lines). The 2026-11-06 E2E advice (§A.1 below) still stands; `CorePlanE2ETest` is still there (13 tests).

## 7. Time

Sum of class times in the newest full report: **202 s** (3,754 cases; 502 classes). One JVM, no parallel forks. Target < 180 s.

### 7.1 Slowest 30 classes

| s | tests | class | verdict |
| ---: | ---: | --- | --- |
| 20.9 | 31 | `cells.CellDownloadTest` | KEEP |
| 15.0 | 5 | `search.SearchAreasTest` | KEEP |
| 12.5 | 13 | `navigation.NavSessionStoreTest` | KEEP |
| 12.0 | 34 | `plansync.CorePlanSyncTest` | KEEP |
| 11.2 | 7 | `navigation.FollowStoreTest` | KEEP |
| 10.8 | 144 | `ui.GermanScreenshotTest` | CONVERT G19 |
| 8.7 | 6 | `plansync.BrokenSyncFilesTest` | CONVERT G01 |
| 8.1 | 115 | `ui.ScreenshotTest` | CONVERT G19 |
| 7.1 | 20 | `cells.CellStoreTest` | KEEP |
| 6.4 | 5 | `data.AtomicFilesConcurrencyTest` | KEEP |
| 6.1 | 11 | `cells.NoStaleDownloadsTest` | KEEP |
| 5.0 | 3 | `ui.ModeSwitchTest` | KEEP |
| 4.9 | 2 | `ui.SheetSmoothnessTest` | KEEP |
| 4.8 | 9 | `cells.CellReuseTest` | CONVERT G16 |
| 4.7 | 10 | `testkit.TestKitTest` | CONVERT G18 |
| 3.6 | 4 | `suggest.NetReaderTest` | DROP |
| 3.3 | 10 | `plansync.CorePlaceSyncTest` | KEEP |
| 3.2 | 8 | `plansync.SyncMarksTest` | CONVERT G02 |
| 3.1 | 1 | `search.SearchChipsGermanScreenshotTest` | nested class / variant in a file above |
| 2.8 | 3 | `search.DiskIndexTest` | KEEP |
| 2.1 | 2 | `ui.search.SearchStableListTest` | KEEP |
| 1.9 | 1 | `ui.CellStateReadsTest` | KEEP |
| 1.7 | 20 | `ui.PlanChipLargeFontGermanTest` | nested class / variant in a file above |
| 1.6 | 25 | `ui.SlideLabelLargeFontGermanTest` | nested class / variant in a file above |
| 1.6 | 25 | `ui.SlideLabelLargeFontTest` | KEEP |
| 1.4 | 9 | `suggest.app.SuggestServiceTest` | KEEP |
| 1.4 | 8 | `cells.RouterClassCheckTest` | KEEP |
| 1.3 | 3 | `cells.BrokenCellFilesTest` | CONVERT G01 |
| 1.3 | 7 | `cells.CellStoreReuseTest` | CONVERT G16 |
| 1.2 | 14 | `data.Rd5PartStoreTest` | KEEP |

### 7.2 What the verdicts save

- DROP: **4 s** · CONVERT (ratio estimate): **22 s** · source-text tests inside KEEP files: **0 s** → **≈ 26 s** of 202 s (→ ≈ 176 s). Gradle start and Paparazzi set-up come on top (not in these numbers), so the verdicts alone are **too tight for 180 s** – the time is not in the tests I drop, it is in a few big behaviour classes.
- **Shrink the loop, keep the test** (not a verdict; none of these is verified, read the loop first): `CellDownloadTest` 20.9 s (the earlier audit already said 3,000 rewrites where 300 prove the same: still there) → ~4 s; `SearchAreasTest` 15.0 s (5 tests: incremental vs full index build) → ~6 s; `NavSessionStoreTest` 12.5 s + `FollowStoreTest` 11.2 s (writer-lock stress, 20 tests) → ~5 s together; `CorePlanSyncTest` 12.0 s → ~8 s; `CellStoreTest` 7.1 s, `AtomicFilesConcurrencyTest` 6.4 s (field bug – keep the threads, cut the repetitions), `NoStaleDownloadsTest` 6.1 s. Together ≈ −55 s.
- Screenshots: `GermanScreenshotTest` 10.8 s + `ScreenshotTest` 8.1 s + large-font classes ≈ 30 s → with 4b (components, DE only) ≈ −15 s.
- Estimated total: 202 − 26 − ~55 − ~15 ≈ **106 s** of class time: comfortably under 180 s **only when the loop-shrinking is done as well** (six classes, ≈ 80 s of the 202 s today).

## 8. Order of work

Per package: **before it moves**, sort its tests (this file), do the CONVERT that needs the harness, delete the DROPs, then move. Lines now → after are the verdict numbers of §1.4 (without L2 / L3 / L4). Risk = risk of the clean-up itself.

| # | package(s) | phase | lines now → after | DROP files | CONVERT groups | risk | note |
| ---: | --- | --- | ---: | ---: | --- | --- | --- |
| 1 | suggest | phase 1 | 763 → 396 | 1 | – | low | all KEEP (NetReader local-only → DROP); RouterProfileTest is a temporary source-text test |
| 2 | net | phase 1 | 134 → 134 | 0 | – | low | HttpDownloadTest only |
| 3 | setup | phase 1 | 408 → 408 | 0 | – | low | – |
| 4 | time | phase 1 | 2,222 → 1,955 | 0 | G01, G03, G14 | low | G03 first (pure); no DROP |
| 5 | gpx | phase 1 | 522 → 522 | 0 | – | low | C7 fixtures stay |
| 6 | rides | phase 1 | 591 → 576 | 0 | – | low | C12: RideViewTest loses 1 source test |
| 7 | backup | phase 1 | 757 → 718 | 0 | G01 | low | C11: all KEEP (BackupTest, BackupNotReady are field bugs); G01 BrokenBackupIndex |
| 8 | search | phase 1 | 2,680 → 2,514 | 2 | G15 | low | G15; DROP GermanSearchTexts + AddressSpeedLocal; keep the pins (SearchPin, AddressPin, DiskIndex) |
| 9 | cells | phase 1 | 3,789 → 3,297 | 2 | G01, G10, G16 | medium | G16 (CellReuse + CellStoreReuse), G01; DROP StoreWriteCost + RealCells; shrink CellDownloadTest first (20.9 s) |
| 10 | routing | phase 1 | 4,691 → 4,625 | 0 | – | medium | DROP SharedFilesRuleTest with piece D; SharedCoreRuleTest shrinks; RouterClassHash stays; 6 source-text tests in PlanEdits/PlanLinks/SlowPlan/… |
| 11 | domain | phase 1 | 3,978 → 3,591 | 0 | G05, G10, G11, G13, G20 | medium | G20; owner-decision pins here are the L3 pool; PinMenuPlace / RouteWarnings / SharedPlaceMenu lose 1 source test each |
| 12 | navigation | phase 1 | 5,706 → 5,690 | 0 | – | medium | almost all KEEP (cues, voice, reroute are owner-tuned field rules); StartAtPointOne loses 1 source test; shrink NavSessionStore / FollowStore loops |
| 13 | sync | phase 1 | 1,678 → 1,486 | 0 | G02 | medium | all KEEP (formats, crypto vectors, random rules); MemoryMailbox is a testkit |
| 14 | plansync | phase 1 | 2,461 → 2,056 | 0 | G01, G02 | high | C16: G02 and G01 (BrokenSyncFiles 8.7 s); CorePlanSyncTest stays the anchor; SyncMailboxE2E needs node (not source text) |
| 15 | data | phase 1 | 2,960 → 2,548 | 0 | G01, G09, G11 | medium | G01 (BrokenFollow / PlanPrefs / RoutingFiles / SmallFiles / TourJson); DROP RoutingDataNotice (G09 fold); BrouterUserAgent → behaviour; TourSchema 2 tests move |
| 16 | debug | phase 1 | 753 → 563 | 0 | G01, G17 | low | G01 (BrokenProblemsFile), G17; stay-list: SafeLaunchRule, UserTextRule |
| 17 | ui | phase 2 | 15,424 → 11,597 | 21 | G04, G05, G09, G10, G11, G12, G13, G14, G19 | high | phase 2: the bulk of DROP (wording / layout / colour / source text) and G04, G05, G09–G14; **write SaveFlowTest + TourEndFlowTest first**; stay-list ViewModelInitOrderTest, AmericanEnglish, HardCodedText |
| 18 | recording | phase 4 | 1,933 → 1,596 | 1 | G08 | high | phase 4: G08 needs RecordingServiceHarnessTest; **keep the wiring tests until then**; then service split |
| 19 | map / poi / update / testkit / (root) | later | 4,174 → 3,892 | 1 | G15, G18 | low | not named in row T: PositionDotOnTop + PoiHit (3 source tests) + PlayBuild; G18 (TestKit) + testkit helpers move with their users |

The three flow tests that must exist **before** their group is deleted: `SaveFlowTest` (G04, C18), `TourEndFlowTest` (G05, C4 / C12), `RecordingServiceHarnessTest` (G08, phase 4). The `BrokenInputs` helper (G01) goes into `testkit` in the first piece.

## 9. Rule for `docs/team/working-agreement.md` (proposal)

> **Tests.** A new test names, in its KDoc, the risk or the behaviour it guards (a core feature C-id, a field bug with its date, an owner decision, a stored format) – a test without that line is not merged.
> Tests that pin wording, colours, dp numbers or the structure of the code do not get written; a text of the main source is read by a test only for a rule from the stay list (`test-audit.md` §3.1). Prefer one flow or table test per feature over many small tests of one function.

## 10. What I could not determine

- **Per-file proof for the KEEP bulk.** 417 files were judged from the class comment + all test names; ~half have no real KDoc. A few more twins like CellReuse / CellStoreReuse are likely inside the 105 `rule-risk` files with no marker (14.6 k lines): the person who moves a package should read its files once more with §2 in hand.
- **CONVERT after-lines are ratios (.25–.8), not measured.** Only the lead's "projected" number depends on them (−4,881 lines).
- **Source-text counts** are by a script that looks for the paths of main source / manifest / `../strings` / `../web` / `../docs` / `../tools` inside a test (or a helper it calls); a few tests that read resources through other helpers may be missing, a few that read the web host code (`SyncMailboxE2ETest`) are executions, not text checks (counted as `no`, flagged in the TSV).
- **Timing** is from another worktree's full run of the same commit family (read only), not from a fresh run; per-class times vary ±30 % with the machine.
- Whether the `live*` tests in `Rd5PartStoreTest` are skipped in CI; whether the source-text test of `PoiSpriteTest` (reads `PoiSprite.kt` to see that `Problems.warn` is called) needs a replacement: a behaviour test with a broken sprite file and `ProblemsRule` would do, small.
- The question for the lead (`?`): `FirstPointHintTest` (owner words as a text pin), `InstrumentTest`, `RouteRevealTest`.

## 11. The 10 biggest wins (lines)

| # | what | lines saved | detail |
| ---: | --- | ---: | --- |
| 1 | CONVERT G19 ComponentShots | 971 | 2 files, 2,158 -> 1,187 lines |
| 2 | KEEP-but-drop-the-source-text-tests (31 files) | 785 | the source-text test(s) in otherwise behavioural files |
| 3 | CONVERT G01 BrokenFilesTest | 589 | 10 files, 1,069 -> 480 lines |
| 4 | CONVERT G10 MapLoadFlowTest | 440 | 13 files, 980 -> 540 lines |
| 5 | CONVERT G05 TourEndFlowTest | 335 | 8 files, 608 -> 273 lines |
| 6 | CONVERT G12 SettingsPagesTest | 333 | 6 files, 666 -> 333 lines |
| 7 | DROP NetReaderTest | 288 | 288 lines, tl |
| 8 | CONVERT G04 SaveFlowTest | 279 | 5 files, 507 -> 228 lines |
| 9 | CONVERT G08 RecordingServiceHarnessTest | 276 | 9 files, 696 -> 420 lines |
| 10 | CONVERT G02 CorePlanSyncTest / CorePlaceSyncTest | 271 | 4 files, 679 -> 408 lines |

---

# Appendix – kept from the audit of 2026-11-06

The sections on E2E tests and on the browser planner (`web/`) were not part of row T and are kept as they were; their numbers are those of 2026-11-06 (status of the planner part: `robust-smoke` and `node-smoke` still as described – not re-checked).

## A.1 E2E (androidTest)

| File | Verdict | Reason |
| --- | --- | --- |
| `CoreDeleteE2ETest`, `CoreFieldE2ETest`, `CorePointsListE2ETest`, `CorePlanLinkE2ETest`, `CoreBackupE2ETest`, `NoStaleDownloadsE2ETest`, `PhoneSetupE2ETest`, `CoreIndoorStartE2ETest` | KEEP | the core features under real conditions (C1, C9, C11, C13, C15, C17, C4/C5) |
| `StartSmokeE2ETest`, `ReleaseRundgang` (+ `RundgangMailbox`) | KEEP | release gate and the owner's flow list |
| `TourDbMigrationTest` | KEEP | schema upgrades – a broken migration loses every tour |
| `CorePlanE2ETest` | DROP → merge | hold → point is in `CoreDeleteE2ETest` and `CoreFieldE2ETest`; move its one unique assert (C2 insert on the line) there |
| `FreshInstallE2ETest` + `RoutingDataFreshInstallE2ETest` | merge into one | same start state (nothing on the phone), two app launches |

## A.2 Planner (`web/`, on main) – KEEP / CONVERT / DROP

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

