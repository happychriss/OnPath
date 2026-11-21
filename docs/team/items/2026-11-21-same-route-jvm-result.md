# SameRouteTest on the JVM: first result (2026-11-21 evening, [umbau])

**The test is RED, so no hashes are checked in and nothing of the stitch goes to main.** Real BRouter, `E10_N45`, four tours (München–Venezia Bike and Hike T2, Salzburg–Trieste, Garmisch–Trento), 27 cases each: a control without an edit, 20 adds up to 2 km off the line, a chain of 3 adds, a night in a span, a delete, an undo. Adds 5–10 km off are not covered: speed-up 3 is not built. Wasm has not run.

Run: `KTTEST=tools/routing/jvm-test/SameRouteTest.kt EXTRA_KT="core/src/main/kotlin/com/onpath/app/routing/PlanResult.kt core/src/main/kotlin/com/onpath/app/domain/PlanGaps.kt core/src/main/kotlin/com/onpath/app/routing/PlanReach.kt core/src/main/kotlin/com/onpath/app/routing/ProfileData.kt" JAVA_XMX=2g tools/routing/jvm-test/run.sh ~/onpath-routing/segments <workDir>`, with `CANON=1` for the second measurement. About 2.5 minutes.

## What was measured
| | Result |
|---|---|
| As the code works today (warm after snap and alias vs cold) | **92 of 108 red, the control too** |
| Largest distance difference | 0.08 % (limit 0.5 %) |
| `CANON=1`: base routed again from its snapped points, plain legs with a moved end routed again | **94 of 104 stitched cases green, 10 red** |
| Points still moving after the second run | 0 in all four tours |
| Extra cost of `CANON=1` | an add: at most 2 short requests, ≤ 0.2 s; the base: one more run, 1.5–9.8 s on this PC |

## What it means
1. **Most of the red is older than the stitch.** A leg is routed to the tapped point and kept under the snapped point (`LegCache.alias`). Another device routes from the snapped point and can get a slightly different line near that point: usually under a metre, in places a spur of a few hundred metres. Plans without anchors have this today.
2. **The 10 left are real misses of the sub-path assumption (§1), all at or inside cut legs:**
   - München–Venezia Bike, 7 cases, one place: the plan point in the Pustertal. The spur there is 153–167 m on one side and 361 m on the other.
   - Hike, 2 cases: 45 m against 42 m of line, 9–12 km from an anchor, at most 2.2 m apart.
   - Salzburg–Trieste, 1 case: 405 m against 378 m, 314 m from an anchor.

## Decision needed (not taken)
- **A (recommended): keep the exact hash and let the line settle.** The §1 safety net, widened: for a plan WITH anchors, when the router is idle (lowest priority, dropped on any edit), route again every leg that is not a real route from its exact ends: cut legs and aliased legs. Afterwards the editing device holds what a cold device routes. Plans without anchors stay as they are. **The price the owner would see: a line can still shift a little some seconds after an edit** (metres, at one place in this corpus about 200 m of spur at a stop), and the phone routes the far legs in the background.
- **B: relax the check** to „same way apart from short stretches near plan points“. No extra routing and no line that shifts, but devices keep small differences for good, and it changes the agreed criterion (§8.7).

§6 lists the idle re-route last, but with A it must come before the test can be green, so it moves in front of the readers.

## For the web side, whichever is chosen
`WebLegs` needs the cut key first (§8.14). The hash text form is in the header of `SameRouteTest.kt`.
