# Handoff: short-trip detour retry – nothing to code, it comes with the shared Planner

**Why.** Owner field case Hilden, Bike (2026-10-31): 0.73 km straight, BRouter rode 2.05 km over signed cycle routes
because the direct 1.07 km has 13 right-angle turns and a short pedestrian zone. The owner chose option B – no profile
change (so no `RouterClassHash` change): the planner retries such a leg once via the midpoint of the straight line.

## Main side (done)

- `routing/Planner.kt` (`retryShortDetours`, constants `SHORT_LEG_M` 2 km, `DETOUR_FACTOR` 2, `MIN_DETOUR_M` 100 m,
  `SHORTER_BY` 30 %): a leg under 2 km straight routed more than twice as long is routed again via the midpoint (a
  normal via BRouter snaps); taken only when ≥ 30 % shorter and routed. The via is internal – the plan's points, their
  snapping and `PlannedRoute.snapped` / `skipped` keep their size and order. In a plan of more points the leg is tried
  alone (3-point request), then the whole chunk once more with the vias taken; kept only when shorter and skipping no
  stop the first one passed. The retries share the time limit of the request that gave the leg
  (`RouteTimeLimit.forRoute`, already in `appFiles`). Trace: "Leg 1: short-trip detour 2.8× → retried via midpoint:
  2048 → 1092 m (taken)".
- `Planner` got two constructor parameters with defaults (`now` – the clock, `shortDetourRetry` – false only for A/B
  runs); the web's `Planner(engine)` / `Planner(engine, cancel)` calls stay as they are.
- `RoutingFingerprint.PLANNER_VERSION` 1 → **2**: every fingerprint `r1-…` changes once (correct: routes for the same
  points can differ). Plans saved before carry the old fingerprint, so the quiet "planned with other routing" line may
  show once for them until they are saved again.
- Tests: `PlannerShortDetourTest` (fake engine), `tools/routing/jvm-test/ShortDetourTest.kt` (real data, Hilden ≤ 1.3 km;
  `AB_N=100` = the A/B in `docs/design/bike-routing.md` decision 12).

## Web steps (web-spike, after merging main)

1. Nothing in `web/engine/build.gradle.kts`: `routing/Planner.kt` is already in `appFiles` and uses nothing new
   (`Geo`, `RouteTimeLimit`, `WaypointSnap`, `Problems` – all there). `node web/parity/check.mjs --update` for the
   changed shared files.
2. `web/test/plans.json`: add the Hilden case as a normal gate case –
   `{"name": "Hilden, short trip with a long detour (midpoint retry)", "points": "51.169243,6.925344;51.169,6.93578", "activity": "BIKE_TOURING", "sac": 2}`
   – the app gives 1099 m (BRouter alone 2054 m); needs E5_N50.
3. The page's Diagnostics line shows "planner 2".
