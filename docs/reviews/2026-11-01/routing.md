# Pre-release review – routing, plan editing, time, GPX

Reviewer focus: `routing/`, `time/`, `gpx/`, and how `ui/AppViewModel` drives them. Read-only review of main @ 15de6d99
and web-spike @ f31b9acb. The shared files (Planner, PlanResult, WaypointSnap, BRouterEngine, PlanPoints, PlanEdits,
WaypointInsertion, RouteTimes, PaceModel, GpxParser, TrackToPlan, RoutingFingerprint, PlanCode, RouteSummary, PlanGaps)
are byte-identical on both branches, so app and web share every finding in them.

No BLOCKER found. The splice code from yesterday (Planner.splice / spliceRun) looks index-safe and bounded (I checked
`at` / `ends` / `shift`, run order, cancel). PLANNER_VERSION was raised to 3 for it.

---

## HIGH

### H1 – One single-thread routing dispatcher for everything: the user's plan waits behind background work that cannot be cancelled
- **Where:** `ui/AppViewModel.kt:1425` (`routingDispatcher = newSingleThreadExecutor`), used by recalc `:2661`, the activity
  check `:3498`, `followAhead` (plan edit during a tour) `:3770`, `routeSyncedPlans` `:4239`, `planFromShown`/TrackToPlan `:4426`.
- **Scenario:** a user pairs the browser that holds 10 plans. `syncReport` → `routeSyncedPlans` routes them one after
  another on the routing thread, each with `Planner(eng, RouteCancel())`, a cancel nobody holds. While that runs, the
  user taps a point. recalc queues behind it: "Calculating…" for a minute or more, and **Cancel does nothing useful**,
  because it cancels only the user's own run. The same happens behind "Plan a route from it" (TrackToPlan: up to 60
  router calls, no cancel), and during a tour: `followAhead` (the tour follows the edited plan) waits behind a sync.
- **Evidence:** a single FIFO executor. Only recalc's `RouteCancel` is stored (`routeCancel`). The sync and TrackToPlan
  calls create their own and drop them.
- **Fix:** give interactive work priority. Run background routing (sync, TrackToPlan) on its own executor with a stored
  `RouteCancel`, and cancel or pause it when recalc / followAhead starts. At minimum, `routeSyncedPlans` should yield
  (check a flag between plans, and skip while `routing` or a tour is active).
- **Role:** cloud-app · **Certainty:** confirmed (code). The size of the wait depends on the plans.

### H2 – GPX with both `<rte>` and `<trk>` becomes one doubled line; route-only files become straight lines that TrackToPlan "follows"
- **Where:** `gpx/GpxModel.kt:30` (`allSegments = lines.flatMap { it.segments }`). `GpxLine.isRoute` is parsed but never
  used anywhere. Import stats in `ui/AppViewModel.kt:4768-4779`, `WebImport.kt:22-28`. `planFromShown` flattens all
  segments (`:4403`).
- **Scenario A:** a planned-route GPX from OsmAnd, or from Garmin BaseCamp and similar tools, holds the control points
  as `<rte><rtept>` plus the geometry as `<trk>`. OnPath draws both, sums both lengths and times both: about 2× distance and
  time. "Plan a route from it" fits a line that runs to the goal, jumps back to the start and runs again.
- **Scenario B:** a route-only file (`<rte>` with 10–50 rtepts, common in route-planner exports) is shown as straight
  lines with straight-line distance. TrackToPlan resamples those straight lines every 25 m and places route points
  where the router "deviates" from them, which means points in fields and lakes.
- **Fix:** when a file has a `<trk>`, ignore `<rte>` for the line, or offer its rtepts as stops. A `<rte>` alone with few
  points: use its rtepts as plan points, like `PlanGpx.stopsOf` does for wpts. Do not fit straight lines.
- **Role:** cloud-app (shared code → web too) · **Certainty:** the merge is confirmed in code. That OsmAnd writes both
  elements is from memory: verify with one OsmAnd export.

### H3 – No safety net around the new splice / merge code: a bug fails the whole plan and shows a raw exception text
- **Where:** `routing/Planner.kt:110-128` (planChunk → splice/spliceRun, no catch for non-RoutingException);
  `ui/AppViewModel.kt:2712` (`routeError = it.message`); web `WebApi.kt` plan → `"Unexpected: $e"`.
- **Scenario:** an unforeseen geometry, such as an empty leg, an IndexOutOfBounds in `at`, or a NaN distance, throws
  inside `splice`. Planner has no fallback for that case. AppViewModel shows e.g. "Index 7 out of bounds for length 7" as
  the red route error, and the browser shows "Unexpected: java.lang…". This breaks the rule "plain-language errors, no
  stack traces". It also wastes the working first route that was already in hand.
- **Fix:** about 5 lines. In `planChunk`, wrap the splice pass in `try { … } catch (e: Exception) { if (e is RoutingException) throw e;
  Problems.error("routing","Splice failed",e); return replan(...) }`, or return `first.route`. In recalc's `onFailure`, show
  `UserText.of(it, msg_routing_failed)` instead of `it.message`.
- **Role:** cloud-plan (Planner), cloud-app (VM text) · **Certainty:** the path is confirmed. A real crash in splice is not
  observed, but the code is one day old.

---

## MEDIUM

### M1 – Navigate's remaining ascent uses a different climb filter than the plan: the number jumps at Start
- **Where:** `data/FollowProgress.kt:27,40` → `Geo.climb` (`data/Geo.kt:39`, 4 m reference threshold). The plan sheet
  shows BRouter's `track.ascend` (10 m hysteresis, `BRouterEngine.kt:285`). The splice approximates it with
  `Planner.climb` (`Planner.kt:600`, 10 m hysteresis). StraightLineEngine and imports use Geo.climb.
- **Scenario:** plan sheet "↑ 850 m". Tap Start: Navigate shows "↑ 9xx m" at the very start, because rolling DEM bumps of
  5–10 m count in Geo.climb but not in BRouter's filter. The docs promise the time "never jumps when navigation starts".
  The same promise should hold for ascent (F-NAV-01, F-PLAN-06).
- **Evidence:** there are four ascent algorithms: Geo.climb(4 m), Planner.climb(10 m hysteresis), the BRouterEngine
  descent mirror, and BRouter itself.
- **Fix:** one shared `climb` for lines with DEM elevation (the 10 m hysteresis, i.e. `Planner.climb`), used by
  FollowProgress, StraightLineEngine and BRouterEngine's descent. Keep Geo.climb with the GPS threshold only for
  recorded GPS tracks. A unit test: FollowProgress(line).at(0) ascent == plan ascent ± 1 m.
- **Role:** cloud-app · **Certainty:** the difference is confirmed. Its size is estimated.

### M2 – "Same points, same route in app and web" is not true under wall-clock budgets
- **Where:** `Planner.kt:432-465` (`retryShortDetours`: `left()` and `MIN_RETRY_MS` decide whether a retry runs);
  `Planner.kt:370` (whole-request timeout → all straight); `RouteTimeLimit`.
- **Scenario:** a short-trip detour retry runs on a desktop browser but is skipped on a slow phone ("no time left").
  Or the whole request times out on the phone at 45 s and finishes in 8 s on the desktop. The same plan link then gives a
  different route or "took too long", while the Planner doc says the result "depends only on the points". The
  fingerprint and Diagnostics would claim the two are equal.
- **Fix:** make the retry decision independent of time. For example, always run the bounded number of retries and let only
  the BRouter search limit apply. Or document that timeouts can differ by device. Log the outcome in the plan's sync
  fields so a mismatch can be explained.
- **Role:** cloud-plan · **Certainty:** confirmed (logic). How often it happens is unknown.

### M3 – TrackToPlan: no cancel, quadratic work per call, and the result overwrites edits made meanwhile
- **Where:** `routing/TrackToPlan.kt:50` (`engine.route` without limits or cancel; only `cancelled()` between calls, and
  the app passes none). `:89-110` `worst()` costs O(route points × track samples) twice per call.
  `AppViewModel.kt:4441` `change { clear(); addAll(r.points) }` runs with no check that the plan is unchanged.
- **Scenario:** a 120 km Komoot tour → "Plan a route from it". The first call is a 120 km request (limit about 87 s). Then
  splits, up to 60 calls, plus seconds of CPU in `worst()` per level (≈ 10k line points × 5k samples). For minutes there
  is only `planBusy` and no Cancel, and all planning is blocked (H1). If the user taps points meanwhile, they vanish when
  the fit lands. Undo brings them back, but nothing says so. The web worker is blocked in the same way.
- **Fix:** pass a `RouteCancel` and `RouteLimits`, and show Cancel. Check `waypoints` are unchanged before replacing them, or
  ask. In `worst()`, project with a moving window or index instead of a full scan. Cap the total time and keep what was
  fitted so far.
- **Role:** cloud-app (+cloud-web for the worker) · **Certainty:** cancel and overwrite are confirmed. The CPU cost is
  estimated.

### M4 – Missing routing data between the points looks like a timeout or "no path", and one bad leg turns the whole plan straight
- **Where:** `BRouterEngine.kt:372-390` (`checkSegments` checks only the parts under the waypoints);
  `Planner.kt:368-370` (whole request timed out → `straight(waypoints, TOOK_TOO_LONG)` for every leg, no per-leg try).
- **Scenario:** a 12-stop hike crosses a 1° part that is not loaded yet, or one stop is effectively unreachable. BRouter
  searches until the 45 s limit, and all 11 good legs come back dashed with "took too long" instead of only the one leg.
  The data notice appears only if a part under a *point* is missing. The corridor load and the later re-route fix it
  eventually, but the first answer is misleading.
- **Fix:** run `Rd5Parts.forCorridor` of the straight legs in `checkSegments`, and give NO_DATA before searching. After a
  whole-request timeout with more than 2 points, try the legs with a short per-leg limit (e.g. 10 s each, total-capped)
  instead of making all of them straight.
- **Role:** cloud-plan · **Certainty:** suspicion. It depends on how BRouter behaves with empty parts in the corridor.

### M5 – Plans synced from the browser are stored with straight legs when the phone has no routing data
- **Where:** `AppViewModel.kt:4226-4260` `routeSyncedPlans`.
- **Scenario:** a plan made in the browser for a holiday region syncs to a phone that has no rd5 parts there. Planner does
  not throw on NO_DATA (`planOne` → per-leg → straight legs + error), so `r.error` is ignored and `db.replacePlanned`
  stores a straight line: ascent 0, flat time, in My routes and in the backup GPX. The KDoc says the opposite ("No routing
  data there yet: the points stay"). It is never retried until the route is opened.
- **Fix:** `if (r.error != null || r.legs.any { it.straight }) continue` (keep the points-as-line), and retry after the
  routing data for those parts arrives.
- **Role:** cloud-app · **Certainty:** confirmed.

### M6 – Large broken GPX: OutOfMemoryError in recovery is not caught and crashes the app
- **Where:** `gpx/GpxParser.kt:62` (`String(bytes)`: a 60 MB file becomes 120 MB of UTF-16, plus regex `findAll` groups);
  `AppViewModel.importGpx` catches only `Exception` (`:4810`).
- **Scenario:** a 50 MB multi-day export that was cut off during download is opened on a 256 MB-heap phone. SAX stops, the
  regex recovery allocates the string, an OOM follows, and the app crashes. This is "robust first", and it is the import path.
- **Fix:** run the recovery only when the SAX result is small, or stream it. Catch `OutOfMemoryError` in the parser and
  fall back to the SAX part with a warning. Lower MAX_BYTES for recovery (e.g. 15 MB).
- **Role:** cloud-app · **Certainty:** suspicion (not reproduced).

### M7 – Three different "where is plan point i on the line" matchers
- **Where:** `Planner.cutIndices` (`Planner.kt:546`: matched spot, first pass within 5 m); `PlanPoints.legs`
  (`PlanPoints.kt:101`: raw point, first within 25 m); `WaypointInsertion.routeIndices` (`WaypointInsertion.kt:131`: raw
  point, global nearest with no slack, used by tap-on-line insertion and `joinPoint`).
- **Scenario:** an out-and-back to a summit with a stop on the shared section. `routeIndices` can match the stop on the
  way back, so a tap on the way up is inserted into the wrong leg, and the candidate's dashed join line points somewhere
  else than the Points list's leg distances suggest. App and web share all three, so they agree with each other but not
  with themselves.
- **Fix:** one function (Planner.cutIndices with matched spots, kept in PlanResult) used by all three.
- **Role:** cloud-plan · **Certainty:** the divergence is confirmed. The wrong-leg case is reasoned, not reproduced.

### M8 – Imported track time = first→last timestamp (elapsed time including nights between segments), while every other time is moving time
- **Where:** `time/TimeEstimate.kt:36-44`.
- **Scenario:** a 2-day hut tour exported as one GPX with two segments shows "31 h" in the track panel and My routes, next to
  model moving times everywhere else. A Strava ride with a 1 h café stop counts the stop. The KDoc says "the gap between
  segments is not counted", but that holds only for the model branch.
- **Fix:** sum per segment and drop pauses (`BreakDetector` exists), or label the value as elapsed.
- **Role:** cloud-app · **Certainty:** confirmed.

---

## LOW

### L1 – Plan GPX round trip loses pins, and drops all stops when one stop lies off the line
`gpx/PlanGpx.kt:48-56`. Pins are not exported, so a re-import routes POI stops as free vias: they get snapped and
via-corrected, and the route can differ. `stopsOf` returns null when *any* stop is > 100 m from the line. A goal the
route cannot reach (a PlanGaps gap ≥ 150 m, e.g. a summit above the T level) therefore makes the whole re-import fall
back to TrackToPlan fitting, and the user's stops are gone. **Fix:** write a small `<extensions>` flag for pins. Allow
off-line stops when the first and last match. *cloud-app · confirmed.*

### L2 – Sport types of other apps are not recognised
`gpx/SportType.kt:28-42` knows only Komoot keys. Strava, Garmin and OsmAnd write `cycling`, `biking`, `road_biking`,
`mountain_biking`, `hiking`, `walking`, `running`, `e_bike_ride`…, and the import silently keeps the current activity.
**Fix:** add these synonyms. *cloud-app · confirmed.*

### L3 – GPX coordinate and elevation sanity
`GpxParser.kt` accepts `lat=0 lon=0`, which no-fix loggers write. One such point turns the track into a 5000 km line,
and `planFromShown` → `RoutingTiles.forLine` then asks for routing data along it. `ele` can be ±Infinity (SAX path) and
NaN (regex path, `eleRe…toDoubleOrNull()`), which feeds RouteTimes and ProfileData with NaN/Inf. **Fix:** drop exact
(0,0) points inside a track, and use `isFinite()` for ele in both paths. *cloud-app · confirmed (code).*

### L4 – Empty-at-source 1° part reads as "routing data missing" forever
`Rd5Parts.kt:283` (`size > 0`) + `BRouterEngine.checkSegments`. A point in an all-water part is "known empty" for the
store, but the engine throws NO_DATA, so the plain notice keeps saying the map is loading. **Fix:** let `hasData` return
true for parts the store has recorded as empty on the server, so that BRouter gives "too far from any way". *cloud-plan
· suspicion.*

### L5 – Leg errors lose the reason (R-13)
`Planner.legError` (`Planner.kt:504`) maps "position not mapped" / "too far from any way" to "no path found". R-13 asks
to explain why (move the point …). The owner wants short lines, but "Point 3: no way within 1 km" is still short.
*cloud-plan · confirmed.*

### L6 – RouteTimeLimit: stale doc, and E-bike grouped with the wrong profile
`RouteTimeLimit.kt:11` says "max 30 s", but the caps are 45–120 s. E-bike uses `touring.brf` (the slowest profile per the
doc's PC numbers) but gets the trekking limit (500 ms/km instead of 600). *cloud-plan · confirmed.*

### L7 – EngineProvider's straight-line fallback is practically dead
`EngineProvider.kt:19`: the `BRouterEngine` constructor loads nothing, so broken jars show up only per request (a
LinkageError → `plan_err_engine` on every leg), and `isRealRouter` stays true. Not harmful (the user gets a message),
but the documented "routing unavailable" path never runs. **Fix:** touch a BRouter class in the constructor.
*cloud-app · confirmed.*

### L8 – Import statistics are computed in three hand-synced places
`AppViewModel.importGpx:4768-4779`, `plansync/DbTourStore.kt:63`, `WebImport.kt:22-28`, all with the same
distance/climb/time code (and `web/parity.json` to keep them in step). **Fix:** one pure `ImportStats.of(GpxData, activity, factors)`.
*cloud-app · confirmed.*

### L9 – PlanCode: quietly truncated and rounded
`PlanCode.kt:63`: more than 500 points are cut with no word, which also loses a round trip's closing point. Points are
rounded to 1e-5° (≈ 1 m), so a link or sync round trip routes slightly different points than the phone did. Rarely
visible. *cloud-plan · confirmed.*

---

## Checked, looked fine
- Supersede/cancel in `recalc`: generation counter + `RouteCancel` + `stopIfCancelled` in every Planner catch. A late result
  can't bring an old line back, and a superseded job doesn't touch `routing`.
- BRouterEngine's result cache is reset with the engine when routing data changes (`engine = null`, AppViewModel `:660`, `:4883`).
- rd5 parts are written via tmp + rename (`Rd5Parts.write`), so BRouter never reads a half-written file.
- Snap → `history.replacePoints` keeps undo/redo markers consistent. Round-trip delete/move keeps the loop closed.
- PlanResult is the single plan computation for app and web (model time, snap, gaps, warnings, rating).
- GPX writer: valid 1.1, escaping, `<type>`, plan file name.
