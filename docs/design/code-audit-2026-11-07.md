# OnPath code audit – 2026-11-07 night (read-only)

Base: origin/main **997779bd** (304 Kotlin files, 56.5k lines in app/src/main). Not yet on main: `donald-pkg-all`
(theme / settings / Zuletzt / polish) rewrites 41 main files – MainActivity, map/MapController, map/PositionDot,
map/RouteReveal (new), poi/PoiSheet, ui/{ActivitySwitchSheet, AppViewModel, BackgroundDownloads, BackupScreens, CellCard,
ElevationProfile, HoldToConfirm, LivePlan (new), MapFooter, MapLoad, MapOverlays, MapScreen, MessageCard, NavSimChip,
NavigateScreen, PaceSettings, Panels, PlanPanel, PlanTopBar, PointsSection, RestView, RideSummary, RouteBreakdown,
RouteDetailsScreen, RouteTag, Screens, SettingRows (new), SettingsScreen, SlideToUnlock, StepSheet, Theme, TourActionBar,
TourBar, UpdateUi}, ui/search/PlaceCard, ui/setup/{PhoneSetupSection, SetupGuideScreen}. Also queued and not on main:
midroute-once (navigation/Navigator), expand-gestures, plan-quiet-gps, turn-cue (recording/Demo*). **Run every package
below on top of main AFTER those have landed.**

Scripts used (in the scratchpad): `swallow.py` (catch / runCatching / getOrNull / getOrDefault / getOrElse / onFailure
without Problems.warn|error|trace or an `expected` reason within ±8 lines), `dead.py` (declarations with no second
reference in main), a strings-vs-code scan, and `grep` for geo constants.

---

## 1. Swallowed errors

The base is good. Only 39 hits overall, and most of them report through their own channel: `listener.failed`,
`DiskIndex.fail`, `failLoud`, `packagesFailed`, `failed`, or the diagnostics JSON. A global crash handler writes crashes
to the problem log (`OnPathApp.kt:41`), and MapLibre's logger feeds Problems. There are no direct `Log.e`/`Log.w` calls
and no `printStackTrace`. The violations that remain:

| P | Where | What | Fix |
|---|---|---|---|
| P0 | `recording/RecordingService.kt:1326` `activityOf` | A bad stored activity name silently becomes HIKE, so the voice, times and profile switch without any trace. | Use `enumOr(name, HIKE, "recording")`, which warns once (helper below). |
| P0 | `navigation/NavSession.kt:188` | Same for the session activity read from `nav-session.json`, mid-tour. | Same. |
| P1 | `data/Prefs.kt:200, 206, 212, 241` | Enum prefs fall back silently. | Use `enumOr` (warn) or `// expected: older value`. |
| P1 | `cells/MapCells.kt:1036–1037` | Unparsable queue entries are skipped (`?: continue`) and the bad reason becomes NEAR, both without a word. | Warn once per load with the count of entries dropped. |
| P1 | `IncomingUri.kt:14, 16` | A failed `canonicalPath` becomes "not private". It is security-relevant, so the fallback must be visible. | `Problems.warn("intent", …)` and treat the path as private (refuse). |
| P1 | `map/OptionalSources.kt:41` | `catch (_: Throwable) {}` around `Log.i`. | Add `// expected: JVM tests have no android.util.Log`, or route through Problems.trace. |
| P1 | `recording/PointQueue.kt:39` | Returns the exception. The caller's handling was not visible in `RecordingService`. | Verify that the caller reports it (track points are core). If not, `Problems.error("recording", …)`. |
| P2 | `debug/Diagnostics.kt:51, 58, 64, 68, 70, 139, 179, 187` | The error is written into the export JSON. That is acceptable for a diagnostics dump. | Add `// expected: diagnostics records the error in the export` once at `safe()`. |
| P1 | `ui/RouteDialogs.kt:97`, `cells/MapCells.kt:1000` | Only `Problems.trace` (focus request; `Cancelled`). These are fine as expected events. | Add `// expected:` comments. |

**Coroutines.** Nothing is swallowed, but uncaught exceptions crash the app.
- 68 `launch` calls in AppViewModel run on `viewModelScope` without a `CoroutineExceptionHandler`. 13 more in
  MapDataFlow, 7 in SearchSession, 4 in MapCells.
- `CoroutineScope(SupervisorJob()+IO)` in `data/AppRouting.kt:22`, `cells/AppCells.kt:30` and
  `cells/MapLoadService.kt:38` have no handler either.
- Only `RecordingService.newScope()` (:133) has one.
- An exception in any other launch reaches `OnPathApp`'s handler, so it is logged, and then **the app dies**. That
  contradicts "robust first".
- **Fix (P1):** add one `ProblemsHandler(area)` (`CoroutineExceptionHandler` → `Problems.error(area, "…", e)`). Put it in
  `viewModelScope + handler` via a `launchSafe(area)` extension and into the three scopes above. Never use it inside
  RecordingService track writing, which has its own handler.

**Helper to add (P1, `debug/Problems.kt` or `data/Enums.kt`):**
`inline fun <reified T: Enum<T>> enumOr(name: String?, default: T, area: String): T` – warns once per (type, value).
It replaces the 13 `runCatching { X.valueOf(..) }` copies in:
- Prefs ×5
- NavSession ×2
- AppViewModel
- SearchStore
- RecordingService
- Problems
- TourDb
- MapCells

## 2. Duplicate code

| P | Duplicate | Places | Single home |
|---|---|---|---|
| P1 | Point → segment / polyline distance (local equirectangular projection) | `data/Geo.kt:88 distanceToLine`; `routing/WaypointInsertion.kt:97, 157 distanceToSegment` (×2 in the file); `routing/WaypointSnap.kt:101 project` (unscaled lat/lon, different metric); `navigation/NavLine.kt:32, 90` | `Geo.projectOnSegment(p, a, b): (t, distM, point)`, used by all of them. |
| P1 | Metres-per-degree constants, with mismatching values | `111_320` lat/lon in WaypointInsertion, SegmentDownloader:115, CellMath:162, Rd5Parts:108, DemoPath:48; `111_320`/`110_540` in Geo, NavLine, TrackCode:123, HeadingEngine:82; `111_000` in RouteNamer:49, 94 | `Geo.M_PER_DEG_LAT` / `Geo.mPerDegLon(lat)`, so one value everywhere. Navigation and routing currently use different scales (0.7 % apart). |
| P1 | Path length | `data/SegmentDownloader.kt:111–117` (own `length` + `metres`) | `Geo.length`. |
| P1 | Bearing | `routing/TurnDetector.kt:126` (great circle), `navigation/JogRule.kt:151` (flat), `map/HeadingEngine.kt:86`, `ui/CellCard.kt:215` | `Geo.bearing` (+ `Geo.normDeg`, today `HeadingEngine.norm`:58). JogRule's flat version is fine for short distances but must name its difference. |
| P2 | Distance / duration formatting | `data/Geo.kt` `Format.distance/duration/durationShort/meters` (display) vs `navigation/VoiceText.kt:101–135` (spoken) | Separate on purpose (spoken vs written), but `Format` lives in `Geo.kt`. Move it to `data/Format.kt`. The `Instrument` helper of pkg-all must build on `Format`, not repeat it. |
| P2 | Dialog button rows | `RouteDialogs.DecisionLayout` (:164), `Screens.SavedTourActions` (:632, own width measuring), `TourBar.DeleteApartButtons` (:286) | One `DialogButtons(primary, secondary, danger?)` on DecisionLayout. |
| P1 | Enum parse with fallback | 13 places, see §1 | `enumOr`. |

## 3. Dead code and historical ballast

**Verified dead (no caller in main):**
- `ui/SlideToUnlock.kt`: the whole file (SlideToPlan:96, SlideLabel:64, SlideThreshold:70) since the hold lock. Only tests
  use it: SlideLabel*Test screenshot classes, ×4 test files.
- `ui/RouteDialogs.kt:111` `DecisionDialog`
- `ui/AppViewModel.kt:2459` `wholeRouteShown()`
- `search/DiskIndex.kt:33` `inMemory`
- `cells/MapCells.kt:154` `packageStates`
- `recording/RecordingService.kt:1072` private `screenLocked()`
- `ui/Theme.kt:102` `ActionStop`

**Kept alive only by tests (delete together with the tests / move to test sources):**
- `ui/TourBar.kt:72` `TourRunningBar`, and with it `TourControls`, `tourStatusText`, `TourStatus` :349, `TourStatusRow`
  :370, `TourPauseEnd`, `continueLabel` (frames `endTourConfirm`, `planMenuNavigating`; `ActivityLabelsTest`)
- `ui/Panels.kt:192` `MapPlaceholder`: move it to test sources (6 test files use it)
- `ui/RouteRules.kt:107` `saveAtHand`: obsolete since Zuletzt, no unsaved state
- `AppViewModel.sharePlan` :4079: verify whether the UI calls it via a method reference. If not, delete it.

The following test-only helpers are fine because they are tested pure rules: `DiskIndex.allPlaces`,
`JunctionCues.roundaboutAhead`, `TourDb.pointAccuracies`, `Problems.flushForTest`, `SyncLimits` (app side is a pending
`[cloud-app]` item – keep). `TourInterruptedReceiver`, `MapLoadService.onTimeout` and `HeadingEngine.*Listener` are false
positives: they are used through the manifest or as overrides.

**Ignored parameters (`@Suppress("UNUSED_PARAMETER")`):**
- `TourBar.kt:95 keepGoingLabel(activity)`
- `PlanPanel.kt:476 PlanCalculating.shows(…, routing)`
- `Poi.kt:23 title(german)`
- `Place.kt:51 title(german)`
- `PoiKinds.kt:26 label(german)`

Fix: drop each parameter and its callers.

**26 unused user strings** (DE+EN, `strings/…/values*/strings_*.xml`):
```
common_done common_replace nav_continue_navigation nav_end_stat_ascent nav_end_stat_distance nav_end_stat_time nav_keep_going
nav_keep_hiking nav_keep_riding nav_lock plan_clear_desc plan_clear_tour_confirm plan_clear_tour_text plan_clear_tour_title
plan_continue_navigation_for plan_hint_count plan_slide_change_route plan_start_short plan_switch_to_plan plan_tour_goes_on
routes_delete_title routes_replace_text routes_replace_text_link routes_replace_title routes_sac_line routes_save_first
```
Also remove them from `tools/i18n/meta/*.json`. Re-run the scan after pkg-all: SlideToUnlock's strings will join the list.

**Migration ballast:**
- `search/SearchAreas.kt:57–63, 110, 287–320` + `SearchSession.kt:363`: reads the 0.12 per-area search files. Every
  tester is on ≥ 0.14, so drop it in P3, after one more release.
- `Prefs.kt:150, 155` (`navLock`, `hikePace`/`bikePace` removal): this is the right pattern, keep it one release.
- `NavSession.kt:193, 269` and `SyncItem.kt:29, 33` (older file / peer): needed for forward/backward compatibility, keep.
- `LegacyBackupStore` (Android < 10): keep while minSdk < 29.

**Comments describing old behaviour:**
- ~70 hits for "older app / legacy / migrat / no longer / formerly / was …".
- Owner-dated rationale comments ("owner 2026-11-0x: …") are dense in ui/ and AppViewModel. They are useful history, but
  many describe states that no longer exist (e.g. the "Nicht gespeichert", slider and REC wording in AppViewModel KDoc).
- **P2:** comment pass per file during the AppViewModel split. Keep the WHY, drop the "was X, then Y, now Z" chains; git
  log keeps them.

## 4. Architecture

**Today:**
- One app module plus `:strings` (KMP: commonMain, androidMain).
- Packages by feature: backup, cells, data, debug, gpx, map, navigation, plansync, poi, recording, rides, routing,
  search, setup, sync, time, ui, update.
- The pure-Kotlin share is high: navigation 22/22 files, rides, sync, time, gpx and setup have no Android imports;
  routing 27/29, search 16/19.
- The web planner already compiles **103 app files unchanged** (`web/parity.json` → shared; engine stand-ins for
  Problems, DataFiles, GpsStatus, Txt). That is a de-facto shared core, but it is kept by path lists, not by a module
  boundary.

**Problems:**
1. **AppViewModel.kt has 4,645 lines, 232 public functions and 88 `mutableStateOf`.** It owns about 22 sections:
   - map cells, demo, updates, activity, planning, hold menus, points, search, shared places, time estimate, route
     warnings, Zuletzt, modes, activity check, follow/record, tours, plan sync, plan links, bin + backup, phone setup,
     GPX import, routing freshness
   - Some are already split out as plain classes the VM forwards to: PhoneSetupState, MapDataFlow, SearchFlow,
     PairingFlow, AppUpdates.
2. **ui/ holds 34 pure rule files** (no Android, no Compose) that are domain logic:
   - AppMode, FollowHistory, LoadNames, LoadSmoother, LockScreenNav, MapControlsEnabled, MapGestures, MapReload,
     NextStop, OfflineReady
   - PlanCamera, PlanGaps, PlanGuidance, PlanHaptics, PlanState, PlanStatus, PocketLogic, PointsDeleteGuard, QuietGate
   - RouteBack, RouteRules, RouteWarnings, RoutesTab, RoutingLoads, RoutingSpot, ScaleRule, SettingsSummary,
     SharedPlaceMenu, SheetDrag, SmartScreen, StartCamera, TourEndRules, WholeRoute
   - pkg-all adds LivePlan and HoldRule-type objects.
3. Data access from ui/: `MapDataFlow.kt:609–610` reads TourDb directly. Dispatchers.IO work happens in 7 ui files.
4. `MainActivity` (785 lines) does diagnostics export on a raw `Thread` (:639, :674), phone setup reads, intent
   parsing and language switching. That is fine for an Activity, but the diagnostics and phone setup belong in their
   holders.
5. Geo math and constants are duplicated (§2), and there are 3 point types: GeoPoint (routing), TrackPoint and
   LatLng (MapLibre).

**Target (Google "Guide to app architecture": UI layer → optional domain layer → data layer; UDF; state holders; SSOT):**
- **Data layer:**
  - repositories over TourDb, Prefs, CellStore and the routing engine: `TourRepository`, `SettingsRepository`
    (Prefs + AppSettings), `MapDataRepository` (cells), `RoutingRepository` (AppRouting / EngineProvider)
  - each with its own scope and error handler
  - the only places that touch IO
- **Domain layer (pure Kotlin, no Android):**
  - plan editing, plan guidance, route rules, Zuletzt rules, tour end rules, navigation (already pure), time estimate,
    rides, sync codec, search ranking
  - pure inputs → outputs
  - this is the future `:core`
- **UI layer:**
  - focused state holders instead of one VM: `PlanSession` (points, routing, undo/redo, Zuletzt, warnings – the
    test-audit's planned pure class), `TourController` (start/pause/end, follow, activity check, mid-route question),
    `SearchFlow` (exists), `PairingFlow` (exists), `PhoneSetupState` (exists), `RoutesState` (My routes, bin, backup,
    import, links), `AppUpdates` (exists)
  - AppViewModel becomes a thin composition root that wires them and exposes `mode`
  - Composables only read state and send events; no rules in composables (e.g. layout rules like `DecisionLayout`
    stay as pure helpers)
- **Compose rules:**
  - state hoisting: screens take `UiState` + callbacks, not the VM. NavigateScreen and PlanPanel take
    `vm: AppViewModel` today.
  - stable, immutable params (data classes, `@Immutable` where lists are passed)
  - no `Dispatchers.IO` in composables

**iPhone readiness (Kotlin Multiplatform):**
- **Already KMP:** `:strings`.
- **Movable to `commonMain` with small changes:** navigation, rides, sync, time, gpx (parser uses a SAX-like API –
  check), routing rules (WaypointInsertion, WaypointSnap, TurnDetector, PlanCode, PlanEdits), search ranking/text,
  plan rules from ui/ (the 34 files), Geo/Format.
- **Blockers and their fixes:**
  - `android.util.Log` inside `debug/Problems.kt` → an `expect` logger
  - `java.io.File` / `AtomicFiles` → okio or an `expect` file store
  - `org.json` (NavSession, Problems, MapCells, Rd5PartStore) → kotlinx.serialization
  - `java.util.concurrent` / `Executors` → coroutines
  - `String.format(Locale)` in Format → own formatter
  - `Math.*` → `kotlin.math`
  - BRouter is Java (JVM only): iOS needs another router or BRouter via a server. That is the hardest part, so keep
    routing behind `RoutingContract` (exists).
- **Staged plan:**
  1. Create a JVM `:core` module and move the 103 planner-shared files + the 34 ui rule files. The web engine depends
     on `:core` instead of path lists, which ends the `parity.json` "shared" upkeep.
  2. Replace `org.json`/`File`/`Log` in `:core` with interfaces (Problems sink, file store, json via
     kotlinx.serialization).
  3. Turn `:core` into KMP (`jvm` + `android` targets).
  4. Add an `iosArm64` target when an iPhone app is real.

  Steps 1–2 are useful now (clear boundary, faster unit tests). Steps 3–4 only on demand.

## 5. Test audit step 2 – status on main

**Not started.**
- 189 `File("src/main…")` reads remain in **87 test files**. The audit planned to drop or convert all but about 15
  architecture rules: FontGlyphsTest, SharedCoreRuleTest, PositionDotOnTopTest, AmericanEnglishTest,
  ViewModelInitOrderTest, ColourRuleTest(?), …
- Screenshots: 516 PNGs (27 MB), of which 184 are German. The EN twins with a German twin are still all there (planned:
  −156 / −7.8 MB). pkg-all re-records many, so do the drop AFTER it lands.
- `CellDownloadTest` 3,000-write test is not shortened.
- No `PlanFlowPropertyTest`, no `SafeAreaE2ETest`.
- Convert first (these hold field bugs): SaveDialogTest, TourLoadTest (gone with no-question?), TourEndPathsTest,
  ClearPlanDuringTourTest, PlanGuidanceTest, ArrivalInServiceTest, ModeQuietTest, PlanStatusTest,
  BackgroundRoutingRuleTest.

---

## Priorities

- **P0 (before the release):** §1 the activity fallbacks (RecordingService:1326, NavSession:188) with a warning, the
  `PointQueue` caller check, `launchSafe` + handlers for the three app-wide scopes (AppRouting, AppCells, MapLoadService)
  and for viewModelScope.
- **P1 (tonight, mechanical, low risk):**
  - `enumOr` helper
  - remaining §1 rows
  - Geo consolidation (projection, constants, length, bearing) with unit tests on the existing behaviour (keep numbers
    stable; where the two scales differ, pick 111_320/110_540 and check navigation tests stay green)
  - dead code §3 + 26 strings + ignored parameters
  - test audit step 2 (drop source-text tests per the report, EN twins)
- **P2 (big, allowed tonight if all green):**
  - split AppViewModel into PlanSession / TourController / RoutesState (in this order: PlanSession first – pure, best
    tested by the new PlanFlowPropertyTest; then TourController; then RoutesState). Each step must keep public VM
    signatures as forwarders so UI files do not change in the same step.
  - move the 34 ui rule files to `domain/` packages
  - JVM `:core` module stage 1
- **P3 (later):** drop the 0.12 search migration; comment pass; KMP stages 3–4; screens taking UiState instead of the VM.

## Work plan – independent packages (run after pkg-all + the queued fixes are on main)

1. **Errors (owns:** `debug/Problems.kt`, new `debug/SafeLaunch.kt`, `data/AppRouting.kt`, `cells/AppCells.kt`,
   `cells/MapLoadService.kt`, `recording/RecordingService.kt` (activityOf only), `recording/PointQueue.kt`,
   `navigation/NavSession.kt`, `data/Prefs.kt`, `cells/MapCells.kt` (queue load only), `IncomingUri.kt`,
   `map/OptionalSources.kt`, `debug/Diagnostics.kt`, `ui/RouteDialogs.kt:97`**)**: `enumOr`, `launchSafe`, handlers,
   every §1 row. AppViewModel's `viewModelScope.launch` → `launchSafe` is a mechanical replace, but AppViewModel is
   owned by package 4, so do it **after** package 4 or inside it.
2. **Geo (owns:** `data/Geo.kt` (+ new `data/Format.kt`), `routing/WaypointInsertion.kt`, `routing/WaypointSnap.kt`,
   `routing/TurnDetector.kt`, `navigation/NavLine.kt`, `navigation/JogRule.kt`, `data/SegmentDownloader.kt`,
   `cells/CellMath.kt`, `routing/Rd5Parts.kt`, `recording/DemoPath.kt`, `sync/TrackCode.kt`, `map/HeadingEngine.kt`,
   `search/RouteNamer.kt`**)**: one projection, constants, length, bearing.
   - Golden tests first (current outputs for sample inputs), then switch.
   - `web/parity.json` mirrors may turn red; the shared files compile in the engine, so run `node web/parity/check.mjs`
     and the engine build.
3. **Dead code + strings (owns:** `ui/SlideToUnlock.kt`, `ui/TourBar.kt`, `ui/RouteDialogs.kt` (DecisionDialog),
   `ui/Panels.kt` (MapPlaceholder → test), `ui/Theme.kt` (ActionStop), `search/DiskIndex.kt`, `ui/RouteRules.kt`
   (saveAtHand), `poi/Poi.kt`, `poi/PoiKinds.kt`, `search/Place.kt`, `ui/PlanPanel.kt` (PlanCalculating.shows), the
   strings XML + i18n meta, and the tests/screenshots that only keep those alive**)**. Also AppViewModel `wholeRouteShown`
   / `sharePlan` and RecordingService `screenLocked` – leave those two lines to packages 4 / 1.
4. **AppViewModel split, stage 1 (owns:** `ui/AppViewModel.kt` + new `ui/plan/PlanSession.kt`, `ui/tour/TourController.kt`,
   `ui/routes/RoutesState.kt`; the 34 rule files move to `domain/…` in a separate commit with only package renames**)**:
   - pure moves first
   - add `PlanFlowPropertyTest` against PlanSession
   - keep VM forwarders
   - after it, `launchSafe` in the VM (from package 1)
5. **Test audit step 2 (owns:** `app/src/test/**` source-text tests and EN screenshot twins; must not touch main code**)**:
   - delete or convert per docs/design/test-audit.md
   - shorten CellDownloadTest
   - run after packages 3 and 4 have landed, because they delete or move tests too; otherwise conflicts in the same
     test files
6. **`:core` module stage 1 (P2, last; owns:** `settings.gradle.kts`, new `core/` module, `web/engine/build.gradle.kts`,
   `web/parity.json`**)**: move the planner-shared files + domain rules. It conflicts with every package that moves
   files, so it runs alone at the end, or is postponed after the release.

Parallel tonight without file overlap: **1 ∥ 2 ∥ 3**, then **4**, then **5**; **6** after the release.
