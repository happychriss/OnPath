# Long bike routes faster (owner 2026-11-04, "A")

Step 1 – measured (cloud worker 2026-11-04 night), `tools/routing/jvm-test/LongBikeTimeTest.kt`:
Bozen → Meran → Schlanders → Mals → Reschen, Bike (`touring.brf`), 5 points, **113 km**, E10_N45.rd5, desktop JVM
(the phone is roughly 5–10× slower).

| Run | `-Xmx512m` | `-Xmx192m` (phone-like) |
| --- | --- | --- |
| Whole plan, cold (first route, segments load) | 2642 ms | 1426 ms |
| Whole plan again, same points (BRouterEngine's route cache) | 28–46 ms | 12–18 ms |
| Each leg alone: 33 / 36 / 24 / 20 km | 600 / 342 / 175 / 146 ms | 209 / 239 / 140 / 60 ms |
| **Last point moved 300 m → whole plan again** | **706 ms** | **645 ms** |
| The same edit, only the changed leg | 54 ms | 50 ms |

What it says: the same points already give the same route at once (the engine's cache). The cost is an **edit**: the
Planner routes all points in one BRouter request, so moving one point of a 113 km plan routes all 113 km again –
~13× the one changed leg. On the phone that is seconds instead of a fraction of one.

Step 2 – the leg cache (not built yet, left for a day session – it changes how every plan is routed, shared with the
planner, needs `PLANNER_VERSION` 4 and a planner deploy together with the app):
- cache key: from-point, to-point, activity, T level, `RoutingFingerprint` (BRouter + profiles + planner version);
  same points = same route; held in memory per engine (LRU, a few hundred legs).
- route leg by leg, join at the route points; keep the cut-stop / spur rule (`Planner.splice`,
  `WaypointSnap.missesMatched`) at every join and the short-detour retry per leg.
- compare against today's whole-plan routes on the JVM (`GpxCompare.kt`, the 70.6 km hike in `SpurSpliceJvmTest`)
  before switching: lengths within a few %, no new spurs at joins.

Step 3 – legs shown as they come (after step 2): each finished leg drawn at once, the rest as a thin straight line
until routed.

Run it: `KTTEST=tools/routing/jvm-test/LongBikeTimeTest.kt JAVA_XMX=192m tools/routing/jvm-test/run.sh <segmentsDir>`.
