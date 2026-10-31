# Handoff: Bug-01 pass only around the cut stop – nothing to code, it comes with the shared Planner

**Why.** The owner noticed that adding a point in the web planner takes about 5 s. His plan (Hike T2, 70.6 km,
11 points, TU Dortmund → Hilden, points 1, 2 and 11 pinned): BRouter's via correction cuts point 5 in the 68 km chunk
from point 2, and the Bug-01 pass (`MAX_SPUR_PASSES`) routed that whole chunk once more with point 5 as a chunk end –
the same 2.5 s again, plus the short-trip retries a second time. Owner decision 2026-10-31: "Option 1" (Drive
„2026-10-31 abends donald (Web-Planer) – Übergabe an die App-Sitzung: Zusatzrunde nur um den abgeschnittenen Punkt“).

## Main side (done)

- `routing/Planner.kt` (`planChunk`, `splice`, `spliceRun`): the chunk's first line stays. For a run of cut stops
  i..j only A → i → … → j → B is routed (2-point requests: every stop is a request end; each with the short-trip
  retry) and spliced into the first line. A / B are points of the first line `SPLICE_REACH_M` (1 km) before / after
  where it passes the cut stops – or the plan points i−1 / j+1 when those are nearer. (The proposal said "i−1 → i →
  i+1"; on the owner's plan the leg 5 → 6 alone is 51 km and took 1.1 s – the 1 km reach routes 2.1 km instead.)
  Distance, ascent / descent (BRouter's 10 m filter on the old piece), time, way types, surfaces, snapped and skipped
  are worked out for the spliced line. The other legs keep their route and their short-trip retries (not run twice).
- Same points, same route: the result depends only on the points; nothing from an earlier plan is reused.
- Fallback = the old pass (the chunk again with the cut stops as chunk ends) when a leg has no route, a leg starts /
  ends more than 5 m off the first line, or the line would newly turn back on itself at A or B (`Planner.doubles`).
  Trace lines: "Splice between points 4 and 6: 2066 m of the first line → 2102 m to the cut stop(s)", or "… – the
  whole stretch again".
- `RoutingFingerprint.PLANNER_VERSION` 2 → **3** (a route for the same points can differ slightly): every fingerprint
  `r1-…` changes once; plans saved before may show the quiet "planned with other routing" line once.
- Numbers (JVM, warm, `tools/routing/jvm-test/SpurSpliceJvmTest.kt`): the owner's plan 2.7 → 1.3 s; requests
  "10 pts 1.2 s; …; 4 pts; 7 pts 1.4 s" → "10 pts 1.2 s; …; 2 pts 0.0 s; 2 pts 0.0 s"; length 70,599 → 70,600 m
  (+1 m), ascent 867 → 868 m. TU Dortmund (`SpurStopJvmTest`) +92 m as before. In the browser (Wasm) expect about
  2.5–3 s instead of 4.9 s.
- Tests: `PlannerSpliceTest` (fake engine: only the legs around the stop, the 1 km reach on a long leg, U-turn and
  failing leg fall back, two cut stops in a row, the 10 m filter), `PlannerTest` (two request-list checks follow the
  new pattern), JVM `SpurSpliceJvmTest.kt` (E5_N50).

## Web steps (web-spike, after merging main)

1. Nothing to code: `routing/Planner.kt` is already in `appFiles` and uses nothing new (`Geo`, `WaypointSnap`,
   `RouteSummary`, `Problems`). `node web/parity/check.mjs --update` for the changed shared files.
2. `web/test/plans.json`: add the owner's plan as a normal gate case (app = web) –
   `{"name": "Dortmund Uni → Hilden, hike, point 5 cut (splice)", "points": "51.49275,7.41772;51.4906,7.42465;51.4872,7.41724;51.48471,7.41236;51.48362,7.42875;51.22429,6.99459;51.22052,6.99428;51.21558,6.99643;51.20045,7.00439;51.19222,6.96206;51.16826,6.93204", "pins": [0, 1, 10], "activity": "HIKE", "sac": 2}`
   – use the file's own key for pins if it differs; the app gives 70,600 m; needs E5_N50.
3. Measure the time live (Wasm) and note it in the next handoff; the page's Diagnostics line shows "planner 3".
