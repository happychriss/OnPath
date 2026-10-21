# Core features – must never break (owner 2026-10-21)

Owner: "2nd time this happened, please note to have test cases for this scenario. Core features."
Rule: **every core feature has JVM regression tests; every field bug in a core feature first gets a failing test
that reproduces it, then the fix.** Run `./gradlew testDebugUnitTest` before every release (the release workflow does).

| # | Core feature | Field bugs so far | Regression tests |
| --- | --- | --- | --- |
| C1 | **Plan: hold a route point → its menu → Delete / Start here / End here** | 0.7.19 (map turned under the finger), 0.8.3 (new point added instead of the menu), 0.8.5 (holds 68 dp from the point added a point next to it; "Delete did nothing") | `CoreHoldPointTest`, `CoreDeletePointTest`, `MapGesturesTest`; emulator `CoreDeleteE2ETest` |
| C2 | Plan: hold the map / route line → add a point (smart insertion) | – | `MapGesturesTest`, `WaypointInsertionTest`, `PlanEditsTest` |
| C3 | Routing: route for every activity, offline, fallback to straight lines | – | `PlannerTest`, `ReferenceRoutesTest`, `RouteRulesTest` |
| C4 | Recording never loses a track; ends only deliberately (Plan never stops it) | 0.7.7 | `TrackReplayTest`, `PointFilterTest`, `PauseWatchTest`, `AppModeTest` |
| C5 | Navigate: follow, off-route alarm, re-route, arrival | 0.7.26 (re-route kept the old route) | `NavigatorTest`, `NavReplayTest`, `OffRouteDetectorTest`, `RerouteTargetsTest`, `ArrivalGateTest` |
| C6 | Map loads: missing data / cells / style never crash; old map stays until the new one is complete | 0.8.0 (profile crash), small streets missing | `CellStoreTest`, `CellDownloadTest`, `StyleLoaderTest`, `ProfileTinyTest` |
| C7 | GPX import / export | – | `GpxParserTest` |
| C8 | App update from GitHub (Settings → Updates) – resumes after a broken connection | 2026-10-21 (4× "connection abort") | `UpdateDownloadTest`, `AppUpdaterTest` |
| C9 | **Plan: point editor = the full plan sheet** (only via swipe up / handle tap) – ✕ deletes at once (Undo on the map), hold ≡ and drag reorders (to the top = new start), tap a row → map shows the point, round trips (start = goal once, ↺) | – (new 2026-10-21, replaces ⋯ → "Points… (delete)") | `PlanPointsTest`, `PlanHapticsTest`, screenshots `planPoints*`; emulator `CorePointsListE2ETest` |

End to end on an emulator (since 2026-10-21): `app/src/androidTest/.../CorePlanE2ETest` – the real app and MapLibre
map, a finger held on the screen (UiAutomator): hold → Start here, hold → point added, hold a point → menu → Delete,
the same after a map style reload and on a turned map. Runs in `.github/workflows/e2e.yml` on every app push to main
and **before every release** (release.yml `needs: e2e` – no release when it fails). On failure the run keeps
screenshots, the app state and logcat as the artifact "e2e-report".

To add next: Navigate → Plan with a followed plan (markers back), recording survives Plan mode, map update with a
real cell generation.

C1 since 0.8.7: catch zone 48 dp (point menu), 48–80 dp a small "Point N: options / New point here" menu, beyond a new
point (`MapGestures.MARKER_RADIUS_DP` / `NEAR_RADIUS_DP`). The point menu acts on the point it was opened for, found
by position (`PointMenuTarget`), not on an index that the plan can change under it. Every hold, press, menu open /
action / close and route result is one line in the gesture log (`Problems.trace`, last 50, never merged; logcat tag
OnPath and `problems.json` "events" in the diagnostics export).

Plan haptics (owner 2026-10-21, `ui/PlanHaptics` + `ui/Haptics`, played in `AppViewModel.change` so map, menus and the
Points list feel the same; off with the system "touch vibration" setting): a hold that opens a menu = very light tick;
a point set (Start here, Go here, new stop, "New point here", added by a hold, a place added) = 1×; a point deleted
(point menu Delete, ✕ in the list) = 2×; reorder drop = 1×; undo / redo, reverse, round trip, start / end here,
clear = nothing.

C9 sheet sizes (owner 2026-10-21, "simple – only the slider and then the point editor"): small = summary; half =
summary + profile; full = the point editor: handle + one line (time · distance) + the Points list with ⇅ Reverse /
↺ Round trip (scrolls inside the sheet, ≤ 45 % of the screen), then the elevation profile with its gradient legend
(owner 2026-10-21; tap → Route details) – no way types. Reorder: drag ≡ (at once, or after holding; no vibration);
the sheet follows the finger and springs to the nearest size (field test 0.8.9). The primary button stays at
the bottom in every size. Way types / surfaces / difficulty + the large profile: **Route details** screen
(`RouteDetailsScreen`), opened by tapping the profile or the difficulty badge in the half sheet.
