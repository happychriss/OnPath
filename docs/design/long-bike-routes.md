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

## Why it is slow on the phone – analysis 2026-11-05 (donald, owner: "a 100 km bike route should be fast")

### What the Pixel logged (event log of the owner's Pixel 8, v0.16.0, field case 2026-11-04 23:36–23:45)
Ruhr → Schloss Nordkirchen, Bike (`touring.brf`), 75.5 km straight, ~98 km routed, edits during a tour:

| Step | Pixel: BRouter request | Pixel: "ready after" |
| --- | --- | --- |
| 2 points | 2 pts 14.3 s | 14.6 s |
| + a point between (3) | 3 pts 18.4 s – the whole 98 km again | 18.7 s |
| + a point between (4) | 4 pts 15.4 s – the whole 98 km again | 21.7 s (waited ~6 s for the run below) |
| + a point (5) | 5 pts 7.6 s | 7.9 s |

During a tour every plan edit routes **twice**: the plan (above), then `followAhead` (`AppViewModel` ~3187) routes
position → every point ahead again – another ~98 km on the same single routing thread (23:38:29 plan ready →
23:38:49 "The tour follows position → 3 points ahead", ~20 s). That run has its own `RouteCancel` that nothing
cancels, so the next edit's plan run waits behind it (the 6 s above).

### The same plan on the desktop JVM (`LongBikeRuhrTest.kt`, `phone-bench/PhoneBench.java`, E5_N50.rd5, `-Xmx192m`)
Hilden → Nordkirchen, 75 km straight; A ≈ Essen-Kupferdreh, B ≈ Dortmund-Hörde, C ≈ Bochum (C between A and B):

| Step | Whole plan (one request, today) | Only the new legs (2-point requests) | km whole / sum of legs |
| --- | --- | --- | --- |
| 2 points, cold / again | 2.9 s / 2.6 s | – | 97.8 |
| + A | 2.3 s | 1.0 + 1.2 = 2.2 s (both legs new) | 97.5 / 97.5 |
| + B (A → goal split) | 2.0 s | 0.54 + 0.42 = **0.97 s** | 106.9 / 106.9 |
| + C (A → B split) | 2.0 s | 0.23 + 0.26 = **0.48 s** | 108.5 / 108.6 |
| a 6 km leg | – | 0.05 s | – |

- **The Pixel is 5–8× slower than the desktop** for the same BRouter work (98 km: 14.3–18.4 s vs 2.6–3.0 s) – ART's JIT
  vs HotSpot, a phone core, and the map / tour running beside it. Nothing in OnPath's own code is slow: BRouterEngine,
  Planner and PlanResult are < 1 % of the profile (JFR: decoding the rd5 tiles and evaluating the profile per way ~45 %,
  the A* search ~40 %, BRouter's two passes per section).
- **BRouter's node-cache limit is not it:** `memoryclass` 64 → 256 MB saves 7–10 % (heap 192 → 512 MB). Keep 64.
- **Routing legs alone gives the same route:** BRouter routes a multi-point request section by section anyway; the
  measured lengths of "whole" and "sum of legs" agree within 0.1 km. What a whole request adds is only the via
  correction across a section join (a spike at a via cut out) – which the Planner already undoes for stops on a way
  (cut stop → routed to it again, owner rule), so legs need the same join rule, not more.
- **The real multiplier is "every edit = the whole plan"** – and twice during a tour. On the phone an edit costs
  ~15–18 s (+ ~20 s for the tour's follow line), where only one or two legs changed.

### Proposal (ranked by gain / risk)
1. **Leg cache in the Planner (app + planner, shared code)** – route a plan leg by leg (2-point requests), each leg
   cached by (from, to, activity, T level) in memory; the engine's own LRU (`BRouterEngine` `CACHE_SIZE` 8 whole
   requests) becomes the leg cache (≈ 64 legs; a 35 km leg ≈ 1 000 line points, a few MB in all). Same points = same
   legs = same route (no history: the cache only saves time; a fresh start gives the identical line). Inserting or
   moving a point routes its 2 legs, removing one routes 1 leg, Reverse routes all (other direction). Expected on the
   Pixel for the 98 km case: an edit ~15 s → ~2.5–5 s (desktop 2.0 s → 0.5–1.0 s). Join rule at every leg end: the
   existing cut-stop / spur splice (`Planner.splice`, `WaypointSnap.missesMatched`) and the short-trip retry per leg
   stay; a there-and-back at a join that a whole request would have cut is checked against today's routes on the JVM
   (`SpurSpliceJvmTest`, `GpxCompare`) before switching. Needs `PLANNER_VERSION` 4 and the planner deploy together
   (web: same `Planner.kt`, the Wasm engine keeps its own LRU – no extra memory beyond the cached lines). ~1 day.
2. **Tour edit: the follow line reuses the legs and stale runs stop (app only)** – `followAhead` routes position →
   first point ahead (1 new short leg) + the cached legs ahead, instead of ~98 km again; a newer edit cancels the older
   `followAhead` (its `RouteCancel`), so the plan run never waits behind it. Expected: ~20 s → 1–3 s per edit during a
   tour. Small once 1 is in (~2 h).
3. **Legs as they come** (step 3 above) – each finished leg drawn at once, the rest thin and straight until routed.
   Makes the first route of a long plan (no cache yet: still ~14 s on the phone for 98 km) feel faster. Planner
   callback + plan sheet; the web mirrors it. ~½ day after 1.
4. **Later, after numbers on the phone:** two legs in parallel on the phone for a fresh plan (≈ 1.7× on a cold plan;
   the BRouter lock allows one search today, each search holds its own node cache ≈ 30–60 MB → check the heap first);
   in the browser a second Web Worker would need its own rd5 copy – not worth it now.
- **Not worth it:** a bigger BRouter node cache (−7–10 %), a lighter first pass (BRouter already does a fast pass per
  section), cutting a long 2-point leg at automatic points (changes the route).

### Run it again
- JVM, the whole app path: `KTTEST=tools/routing/jvm-test/LongBikeRuhrTest.kt tools/routing/jvm-test/run.sh <dir with E5_N50.rd5>`
  (`MEMCLASS=256` patches BRouter's node-cache limit, `STEPS=quick` skips the legs).
- BRouter alone on the desktop and on the Pixel's own runtime (nothing installed into the app; only when no tour
  records): `ANDROID_SERIAL=<pixel> tools/dev/device.sh pixel -- tools/routing/phone-bench/run.sh <dir> both`.
