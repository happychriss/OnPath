# Core features – must never break (owner 2026-10-21)

Owner: "2nd time this happened, please note to have test cases for this scenario. Core features."
Rule: **every core feature has JVM regression tests; every field bug in a core feature first gets a failing test
that reproduces it, then the fix.** Run `./gradlew testDebugUnitTest` before every release (the release workflow does).

| # | Core feature | Field bugs so far | Regression tests |
| --- | --- | --- | --- |
| C1 | **Plan: hold a route point (≤ 48 dp) → its menu → Delete / Start here / Google; beside it → a new point, never a question (owner 2026-10-22)** | 0.7.19 (map turned under the finger), 0.8.3 (new point added instead of the menu), 0.8.5 (holds 68 dp from the point added a point next to it; "Delete did nothing") | `CoreHoldPointTest`, `CoreDeletePointTest`, `MapGesturesTest`; emulator `CoreDeleteE2ETest` |
| C2 | Plan: hold the map / route line → add a point (smart insertion) | – | `MapGesturesTest`, `WaypointInsertionTest`, `PlanEditsTest` |
| C3 | Routing: route for every activity, offline, fallback to straight lines; a point on a way without a connection (BRouter "island") is snapped to the next way | 2026-10-26 (Köln Dom → Neumarkt: no route for any activity) | `PlannerTest`, `ReferenceRoutesTest`, `RouteRulesTest`, `IslandPointTest`; JVM `tools/routing/jvm-test/IslandTest.kt` |
| C4 | Recording never loses a track; ends only deliberately (Plan never stops it) | 0.7.7 | `TrackReplayTest`, `PointFilterTest`, `PauseWatchTest`, `AppModeTest` |
| C5 | Navigate: follow, off-route alarm, re-route, arrival | 0.7.26 (re-route kept the old route) | `NavigatorTest`, `NavReplayTest`, `OffRouteDetectorTest`, `RerouteTargetsTest`, `ArrivalGateTest` |
| C6 | Map loads: missing data / cells / style never crash; old map stays until the new one is complete | 0.8.0 (profile crash), small streets missing | `CellStoreTest`, `CellDownloadTest`, `StyleLoaderTest`, `ProfileTinyTest` |
| C7 | GPX import / export (Komoot `<type>` → activity, written on export; the user's T level stays inside the type's band – hike T1–T2, mountaineering T3–T5; a changed activity is said in one grey line in the track panel, captain 2026-10-26; opening a recorded or imported tour from My routes switches to its activity the same way – "Activity set to Bike from the tour", owner 2026-10-26) | review 2026-10-26 (every recorded tour stored as T3 → its GPX said "mountaineering", reopening set T3) | `GpxParserTest`, `SportTypeTest` (`openingATour*`), `NewTourSacTest`, `OneTimeModelTest` (time without timestamps = the planned time), screenshot `ScreenshotTest.trackImportedActivityNote` |
| C8 | App update from GitHub (Settings → Updates) – resumes after a broken connection | 2026-10-21 (4× "connection abort") | `UpdateDownloadTest`, `AppUpdaterTest` |
| C9 | **Plan: point editor = the full plan sheet** (only via swipe up / handle tap) – ✕ deletes at once (Undo on the map), hold ≡ and drag reorders (to the top = new start), tap a row → map shows the point, round trips (start = goal once, ↺) | – (new 2026-10-21, replaces ⋯ → "Points… (delete)") | `PlanPointsTest`, `PlanHapticsTest`, screenshots `planPoints*`; emulator `CorePointsListE2ETest` |
| C10 | **Routing data comes with the map** – the missing 1° routing parts (of BRouter's 5° .rd5, ~10 MB each, `Rd5PartStore`) load by themselves with every map region and where a route is planned (first start, point set, search, route corridor); mobile data too unless "Maps only on Wi-Fi"; one loading modal "Loading the map for your area · about X MB" (one % by bytes, one Cancel, never the words "routing data"); plain grey notice while missing ("Map loading – straight line for now", never a file name/path); the plan is computed again when it arrives; Offline maps sizes include the parts, Delete removes parts no other region needs (`RoutingDataLoader`, `RoutingLoads`) | 2026-10-21 (fresh phone: never downloaded, straight line, notice with a file path) | `RoutingDataAutoTest`, `RoutingLoadsTest`, `Rd5PartsTest`, `Rd5PartStoreTest`, screenshots `MapLoadOverlayTest.withRoutingData`, `planRoutingDataLoading`; phone `RoutingDataFreshInstallE2ETest` (`routingDownload=true`) |
| C11 | **Tours & plans are never lost: backup, restore, recycle bin** – a GPX per finished tour / saved plan / import in Documents/OnPath (or a chosen folder), restore after a reinstall / on a new phone, delete = 30-day recycle bin, Export all (ZIP), Android backup of the DB; map updates / Clean up / Clear all never touch tours | – (new 2026-10-21, owner decision) | `BackupTest`, `MapsNeverTouchToursTest`, screenshots `recycleBin*`, `settingsBackup`, `restoreOffer`; emulator `CoreBackupE2ETest` |
| C12 | **Bus / car / lift in a tour** (owner 2026-10-22, `docs/design/vehicle-detection.md`) – nothing during the ride; at End tour a summary (timeline, small map) with Save only until … / Save all, rides not counted / Split into two tours / Save as recorded; the choice sets km / time / ascent, the drawn track, Export and the backup file; raw points always kept (`tours.rides`, "Edit rides" in My routes) | – (new; field data: Taser gondola + car 2026-10-22) | `RideDetectorTest`, `OwnerTaserTourTest`, `RideViewTest`, `RideApplyTest`, `RideBackupTest`, `TourSchemaTest`, screenshot `RideSummaryScreenshotTest` |
| C13 | **No stale or unexpected downloads** – route-corridor map regions and routing parts belong to the *current* plan: a new route / Undo / Clear / Cancel at the long-route question / "not routed" / routing failed drops the ones no longer needed (running download stops, installed maps stay); nothing loads for a plan that is not routed; at start the saved queue is revalidated (corridor waits for the restored plan's route, point requests older than 24 h go); right before each download the reason is checked again (corridor → still on the route, point → still near a planned point / the search / the position / a tour or the user waits in the modal, refresh → area still installed); every request / start / finish / drop is in the events trace; finished regions join the map in one generation (≤ 10 s window, never while the map is dragged); what loads in the background is visible (blue strip after 10 s, Navigate chip) and stoppable ("Cancel all" there and in Settings → Offline maps) | 2026-10-22 (16 corridor regions of a long route kept loading after every start on Wi-Fi: 392 MB, 40 generations) | `NoStaleDownloadsTest`, `CellTriggersTest.t4NewRouteDropsTheOldCorridor` / `planWithoutRouteKeepsNoCorridor`, `RoutingDataAutoTest.corridorPartsFollowThePlan`, `BackgroundDownloadsTest`, screenshots `BackgroundDownloadScreenshotTest`; emulator `NoStaleDownloadsE2ETest` |
| C14 | **Offline maps: Clean up / Clear all** (owner 2026-10-22, Settings → Offline maps) – "Clean up" first shows what goes ("Remove 23 areas · 410 MB") and what stays (current plan, saved routes, running tour, where you are now + 10 km), nothing before Remove; "Clear all" (red, second confirmation) removes every downloaded area, the world overview stays, not offered during a tour. Removal goes through the store generation path: batched in one generation, never while the map is dragged, never during a tour (applied after it); routing parts only removed areas needed go too; every removal is in the events trace. Replaces Diagnostics → "Reset maps" | – | `MapCleanupTest`, `CellDownloadTest.removalWaitsForTheTourAndTheGestureAndIsBatched` / `clearAllRemovesEveryAreaAndEveryRequest` / `freshInstallAndResetLoadTheMapAgain`, `MapsNeverTouchToursTest`; screenshots `SettingsScreenshotTest.offlineMaps*` |
| C15 | **Plan links open as the plan** (A-005, web planner phase 1, `docs/design/web-planner.md` §8) – `https://planer.onpath.earth/#p=<code>` (`PlanCode` v1 – frozen) from the phone's camera (QR code), a tapped link (App Link), text shared to OnPath or pasted into the search box loads points, activity, T level, point names and pinned points like a saved route: "Replace current route?" (Save first / Replace) when the plan has unsaved changes – a plan still exactly as an earlier link brought it is replaced without a question (owner 2026-10-27); during a tour nothing is loaded (grey line "Plan links open after the tour …"); a broken / cut-off / newer link changes nothing and says so in one red line; sheet ⋯ → Share plan writes the same link (`PlanLinks`) | – (new 2026-10-27) | `PlanLinksTest`, `PlanCodeTest` (`formatVersion1IsFrozen`), `CorePlanLinkTest`; emulator `CorePlanLinkE2ETest` |

C11 details (owner 2026-10-21, `backup/`): file `YYYY-MM-DD Name.gpx` (sanitised, unique; rename / re-save overwrite the
same file via `files/backup-index.json`), GPX 1.1 with `<metadata><extensions><onpath:backup>` = JSON (uuid, type
tour|plan|imported, name, activity, SAC, dates, stats, stops with names, pinned POIs, deleted). Written on one background
thread (`Backups`) after: tour end (RecordingService.finish), "Save" of an unfinished tour, plan save / overwrite,
rename (also the automatic place name), GPX import, delete → `Deleted/`, restore from the bin; at app start the bin is
emptied of entries older than 30 days (files too) and tours without a file are written (first start of this version,
new folder). Failures → Problems WARN + message "Backup to … failed – reason" until OK (kept across restarts).
Android 10+: MediaStore (Files, RELATIVE_PATH Documents/OnPath, no permission); Android 8–9: the folder with
WRITE_EXTERNAL_STORAGE (Settings → Backup → Allow); chosen folder: SAF tree with persistable permission. Restore:
folder picker (opens at Documents/OnPath), imports only files with the OnPath record, newest per uuid, skips uuids on
the phone and the bin. First start with no tours asks once (prefs `backup` → `restoreOffered`). Android Auto Backup:
`res/xml/backup_rules.xml` + `data_extraction_rules.xml` – tours DB, settings, tour/plan times only.

End to end on an emulator (since 2026-10-21): `app/src/androidTest/.../CorePlanE2ETest` – the real app and MapLibre
map, a finger held on the screen (UiAutomator): hold → Start here, hold → point added, hold a point → menu → Delete,
the same after a map style reload and on a turned map. Runs in `.github/workflows/e2e.yml` on every app push to main,
beside the release (owner 2026-10-22); the release gate is only the short `StartSmokeE2ETest` (the app opens, also with a
saved plan – the 0.9.1 crash). A red full run is fixed at once. On failure the run keeps
screenshots, the app state and logcat as the artifact "e2e-report".

Navigate draws only the route line, no numbered plan points (owner 2026-10-22, reverting 0.9.1); Plan draws them,
also during a tour (`MapScreen.planLayers`, `PlanLayersTest`; e2e `CoreFieldE2ETest.navigateThenBackToPlanStillDeletes`
checks none in Navigate, back in Plan).

Route warnings and hints (plan sheet, `ui/RouteWarnings`): one amber ⚠ line, tap → "On this route" list → tap a row →
the map shows that spot. Way warnings since 2026-10-22 (pushing, steps, above your T, steep) – no pushing / steep for
MTB / E-MTB since 2026-10-26 (owner: "remove the rules for MTB – we don't want to look stupid": footways, hard trails
and steep sections are what they ride; the line was on 87 % of MTB routes, now 55 %); a signed way stays, worded as
legal information: bicycle=no → "1.2 km no bikes allowed" (row "No bikes allowed – sign on the way"), bicycle=dismount
→ "300 m dismount (sign)" (`RouteWarningsTest.mtbHasNoPushingOrSteepHint` / `mtbSignedWaysAreLegalInfoNotPushing` /
`mtbKeepsTheHintsToCheck`); hints to check before
going since 2026-10-26 (owner: "a warning/hint on a route if it contains a ferry or anything else that requires
validation"): **ferry** for every activity ("Ferry 320 m – check times", several: "2 ferries – check times"; first in
the line), **construction site** ("Construction – may be closed"), **ford** (way or node; "Ford – water to cross") and,
for bikes only, a **stile / kissing gate / turnstile** ("Stile – lift the bike over"). The tags come from BRouter's way
and node tags per span (`BRouterWayStats.perNode` → `WayAttrs.ford / barrier / construction`). Tests:
`RouteWarningsTest`, `BRouterWayStatsTest.hintTagsReachTheWayAttributes`, `WayTimesTest.codeRoundTrip`, screenshots
`ScreenshotTest.planFerryHint` / `routeSpotsListHints`; JVM `tools/routing/jvm-test/FerryHintTest.kt` (Rhine ferries
Zons ⇄ Urdenbach, Kaiserswerth ⇄ Langst, every activity class). Counts on real routes: `HintInventory.kt`.

To add next: recording survives Plan mode, map update with a real cell generation. (Navigate → Plan with a followed
plan, markers back: `CoreFieldE2ETest.navigateThenBackToPlanStillDeletes`.)

C1 since 0.8.7: catch zone 48 dp (point menu), 48–80 dp a small "Point N: options / New point here" menu, beyond a new
point (`MapGestures.MARKER_RADIUS_DP` / `NEAR_RADIUS_DP`). The point menu acts on the point it was opened for, found
by position (`PointMenuTarget`), not on an index that the plan can change under it. Every hold, press, menu open /
action / close and route result is one line in the gesture log (`Problems.trace`, last 50, never merged; logcat tag
OnPath and `problems.json` "events" in the diagnostics export).

Plan haptics (owner 2026-10-21 after 0.8.9, `ui/PlanHaptics` + `ui/Haptics`, played by `MapHost` when the hold ring
is full): **vibrate at the moment of the press, never after the action, only on the map.** Hold on the empty map /
route line / a POI = 1× (40 ms); hold on a route point (menu with Delete) = 2× (40/100/40 ms); amplitude ~230. Nothing
after an edit (menu buttons, set, delete, undo) and nothing in the Points list (✕, drag). Only OnPath's setting
"Vibrate when planning" (default on) counts; the pulses carry an explicit media usage – unlabelled, Android treated the
short 1× pulse as touch feedback and muted it with the system "Touch feedback" off (why "set" never vibrated in 0.8.9).

C9 sheet steps (owner 2026-10-22, `PlanPanel` + `ui/StepSheet.kt`; replaces the 2026-10-21 sizes): small = the summary
line (activity · time + distance · ⋯); profile = + ascent / descent, difficulty badge and the large elevation profile
(slide a finger: the dot runs along the route); full = + the Points list (≡ drag, ✕ delete, tap → map; ⇅ Reverse /
↺ Round trip). One body, a step only shows more of it; the sheet follows the finger and springs to the nearest step.
Reorder: drag ≡ (at once, or after holding; no vibration). The primary button stays at the bottom in every step. Way
types / surfaces: only on the **Route details** screen (`RouteDetailsScreen`), opened by tapping the profile or the
difficulty badge.
