# Core features – must never break (owner 2026-10-21)

Owner: "2nd time this happened, please note to have test cases for this scenario. Core features."
Rule: **every core feature has JVM regression tests; every field bug in a core feature first gets a failing test
that reproduces it, then the fix.** Run `./gradlew testDebugUnitTest` before every release (the release workflow does).

| # | Core feature | Field bugs so far | Regression tests |
| --- | --- | --- | --- |
| C1 | **Plan: hold a route point → its menu → Delete / Start here / End here** | 0.7.19 (map turned under the finger), 0.8.3 (new point added instead of the menu) | `CoreHoldPointTest`, `MapGesturesTest` |
| C2 | Plan: hold the map / route line → add a point (smart insertion) | – | `MapGesturesTest`, `WaypointInsertionTest`, `PlanEditsTest` |
| C3 | Routing: route for every activity, offline, fallback to straight lines | – | `PlannerTest`, `ReferenceRoutesTest`, `RouteRulesTest` |
| C4 | Recording never loses a track; ends only deliberately (Plan never stops it) | 0.7.7 | `TrackReplayTest`, `PointFilterTest`, `PauseWatchTest`, `AppModeTest` |
| C5 | Navigate: follow, off-route alarm, re-route, arrival | 0.7.26 (re-route kept the old route) | `NavigatorTest`, `NavReplayTest`, `OffRouteDetectorTest`, `RerouteTargetsTest`, `ArrivalGateTest` |
| C6 | Map loads: missing data / cells / style never crash; old map stays until the new one is complete | 0.8.0 (profile crash), small streets missing | `CellStoreTest`, `CellDownloadTest`, `StyleLoaderTest`, `ProfileTinyTest` |
| C7 | GPX import / export | – | `GpxParserTest` |
| C8 | App update from GitHub (Settings → Updates) – resumes after a broken connection | 2026-10-21 (4× "connection abort") | `UpdateDownloadTest`, `AppUpdaterTest` |

Gaps (to do): an end-to-end test on an emulator that really holds a marker on the MapLibre map (C1/C2) – the JVM
tests cover the decision functions MapHost uses (`MapGestures.markerUnderFinger`, `decide`), not the Android touch
path itself. Candidate: GitHub Actions emulator job (android-emulator-runner) with one UiAutomator test.
