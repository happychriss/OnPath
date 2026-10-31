# Handoff: one plan computation for app and browser (`routing/PlanResult.kt`)

**Problem.** The phone's plan sheet and the browser planner each put the plan result together on their own:
`AppViewModel.recalc` (+ `withModelTime`, `snapToRoute`, `planGaps`, `routeSpots`, `rememberRating`,
`ui/ElevationProfile.profileData`) and the web's `Engine.plan` in `web/engine/src/main/kotlin/com/onpath/web/WebApi.kt`
(+ `profileJson`, `WebPlanRules.gaps`). They already drifted (Bug 01: the phone snapped a stop, the web did not; see
"Differences today" below). Owner rule (2026-10-31): routes, stops, numbers and warnings are always identical in the
browser and the app – share the code.

**Main side (done).** One pure function, `app/src/main/java/com/onpath/app/routing/PlanResult.kt`, used by
`AppViewModel.recalc` (threading, Cancel and "a newer request supersedes" stay in the view model / the page). The
profile numbers moved to `routing/ProfileData.kt` (from `ui/ElevationProfile.kt`, which now draws `ProfileData.of`).
Both kept pure by `SharedCoreRuleTest.files` (with the `ui/PlanGaps.kt`, `ui/RouteWarnings.kt`, `data/Geo.kt` it calls);
locked by `PlanResultTest` (fake engine: snapping, a pinned, a named, a far and a skipped stop, model time, warnings,
rating, profile).

## API

```kotlin
// routing/PlanResult.kt – blocking, call it in the worker
PlanResult.plan(engine: RoutingEngine, points: List<GeoPoint>, activity: Activity, sac: Int,
                pins: Set<Int>, named: Set<Int>, factors: Factors, cancel: RouteCancel? = null): PlanResult
PlanResult.plan(planner: Planner, points, activity, sac, pins, named, factors): PlanResult   // keeps planner.describeTries()
PlanResult.of(points, planned: PlannedRoute, activity, sac, pins, named, factors): PlanResult // route already there

data class PlanResult(
    val routed: List<GeoPoint>,   // the points as routed (the request)
    val route: PlannedRoute,      // durationS = TimeEstimate.planSeconds (OnPath's model, not the router's)
    val activity: Activity, val sac: Int, val pins: Set<Int>, val named: Set<Int>,
    val points: List<GeoPoint>,   // the plan points after snapping, same size and order as routed
    val moved: Boolean,           // some point moved (false: points == routed)
    val far: Set<Int>,            // kept: not reached (≥ PlanGaps.FAR_M) or skipped (PlannedRoute.skipped, Bug 01)
    val gaps: List<PlanGap>,      // PlanGaps.of(points, route.snapped, route.skipped) – the red line + grey dashed piece
    val spots: List<RouteSpot>,   // RouteWarnings.detect – the amber line and its "On this route" rows
) {
    val rating: RouteSummary.Rating            // lazy: the difficulty badge
    val profile: ProfileData?                  // lazy: null = not enough elevation ("No elevation data")
    val steepness: List<RouteSummary.Steepness> // lazy: gradient class per profile piece (empty without a profile)
    fun withNamed(named: Set<Int>): PlanResult // names changed meanwhile: snapping + gaps again, same route
}
class ProfileData(dist: DoubleArray, ele: DoubleArray, idx: IntArray, min: Double, max: Double, total: Double)
```

`factors` = the pace setting: the app passes `TimeEstimate.planFactors(a, settings)`, the web `WebPace.factors(act)`.
`named` = indices of the stops with a place name (app: `stopName(p) != null`; web: `state.meta[i].name`). `pins` = the
POI-pinned indices (as today). Snapping rule (unchanged from the app): a free point moves onto the way the router used
(3 m – 1 km, `WaypointSnap.apply`, its elevation kept); pins, named stops and `far` keep their exact coordinates.

## What web-spike must do

1. **Merge main**, then add `"routing/PlanResult.kt", "routing/ProfileData.kt"` to `appFiles` in
   `web/engine/build.gradle.kts` (everything they call is in the list already: Planner, WaypointSnap, RouteSummary,
   `ui/PlanGaps.kt`, `ui/RouteWarnings.kt`, `time/TimeEstimate.kt`, `data/Geo.kt`). Add `"routing/PlanResultTest.kt"`
   to `appTests` (plain JUnit, no Android).
2. **`Engine.plan` calls PlanResult** instead of its own assembly. New signature with the names (the page has them –
   `namesText()`): `fun plan(points: String, activity: String, sac: Int, pinned: String = "", names: String = "")`.
   ```kotlin
   val wps = parsePoints(points); val act = Activity.valueOf(activity)
   val named = parseNames(names).keys            // indices with a name
   val res = PlanResult.plan(engine(), wps, act, sac, parseIndices(pinned), named, WebPace.factors(act))
   lastResult = res                              // replaces lastPlan / lastPlanAct (step 4)
   val p = res.route                             // p.durationS is the model time: no own planSeconds call
   ```
   JSON from the result: `timeS` = `p.durationS`; `difficulty` / `because` = `res.rating.text` / `res.rating.because`;
   `warning` / `spots` from `res.spots` (keep the row JSON: `RouteWarnings.text`, `rowTitle`, `rowDetail`,
   `showPoints`, `centerFor` stay presentation); new `"points"` = `res.points` (6 decimals, same order as the request)
   and `"moved"` = `res.moved`; new `"gaps"` = what `WebPlanRules.gaps` returns today, built from `res.gaps`
   (keep its text rule with `PlanGaps.text` / `withMore` / `hasMore` / `showTarget`); `"profile"` from `res.profile` +
   `res.steepness` – `null` when `res.profile == null`.
3. **The page adopts the snapped points** (`web/app/src/main.ts`, after `state.plan = r.plan`): when `r.plan.moved`,
   replace `state.points` with `r.plan.points` in place – **no undo step, no new plan request** (the route already
   belongs to them; the app amends its state and `PlanHistory.replacePoints(moves)` so undo snapshots get the same
   values – do the same to the page's undo stack). Meta (pinned / name) stays by index; the moved points are free
   anyway. Then draw the markers. A second plan of the snapped points must not move them again (the app's `SnapTest`
   checks "stable").
4. **One last result.** `lastPlan` / `lastPlanAct` → `lastResult: PlanResult?`. `lastPlanFor(wps)` must accept both the
   routed and the snapped points (after step 3 the page sends the snapped ones), compared at 6 decimals as the page
   rounds them: `lastResult?.takeIf { same6(it.routed, wps) || same6(it.points, wps) }?.route`.
   `lastPlanPoints` keeps its activity / T check against `lastResult.activity` / `.sac`.
5. **Gaps from the plan.** `PlanGapsLayer.update` takes `r.plan.gaps` instead of calling `planGaps`; drop the engine
   call `planGaps` (`WebApi.kt` export, `WasmEngine`, `CALLS` in `worker.ts`) and `WebPlanRules.gaps`, or keep the call
   only as a thin reader of `lastResult.gaps` if another place needs it.
6. **GPX import profile** (`WebImport.kt` line ~60): `ProfileData.of(geo.flatten())` instead of `profileJson` – the
   same numbers as the app's track panel (`ui/Panels.kt` draws `ElevationProfile(track.points)` = `ProfileData.of`).

### Delete in the web after that
- `Engine.profileJson` (re-implementation of `profileData` + steepness) – replace by one small writer of a
  `ProfileData?` + steepness list (`"profile":null` or `{"dist":…,"ele":…,"steep":…}`).
- The own time call in `Engine.plan` (`TimeEstimate.planSeconds(p.points, act, WebPace.factors(act))`): it is
  `res.route.durationS`. (`WebPace.planTime` for the pace dialog may keep its own call – same function, no plan.)
- The own `RouteSummary.rating(...)` and `RouteWarnings.detect(...)` calls in `Engine.plan`.
- `WebPlanRules.gaps`'s `PlanGaps.of(wps, p.snapped)` (step 5).
- `lastPlan`, `lastPlanAct` (step 4).

### Differences today (they go with the steps above)
- **No snapping in the web** (Bug 01 report: the same plan showed different stop positions): the phone moves free
  points onto the way, the browser kept them where clicked → steps 2–3.
- **Skipped stops missing in the web's gaps**: `WebPlanRules.gaps` calls `PlanGaps.of(wps, p.snapped)` without
  `p.skipped` – a stop BRouter's via correction still cuts shows a red line on the phone ("Route misses point 3 by
  50 m") but nothing in the browser → step 5.
- **Profile without elevation**: the web sends empty arrays (or one point) where the app shows "No elevation data"
  (`ProfileData.of` = null for < 2 points with elevation or a zero-length line) → `"profile":null`, the page shows the
  app's text (`plan_no_elevation`).
- **Named stops**: the app keeps a named stop exactly even when it is not pinned; the web does not snap at all, so it
  never mattered there – with step 2 pass the names.

### Checks
Engine build + engine tests (`PlanResultTest` with them), `parity/check.mjs`, the edit / parity smokes; one new smoke:
a click 20 m beside a path → after the plan the marker sits on the line and a second plan moves nothing; a POI stop
(pinned + named) stays where it was.
