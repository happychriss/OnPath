# A fresh device and the editing device can show a slightly different line (found 2026-11-21, [umbau])

**Already so on `main`, without any detour.** Found by `SameRouteTest` on `wip/umbau-2026-11-21-detour-stitch` (a8ed3a939; the whole result: `docs/team/items/2026-11-21-same-route-jvm-result.md` on that branch). Not fixed; a separate item from the stitch unless the owner's choice there covers it.

**Cause (measured).** A leg is routed to the tapped point. The point then moves onto the way (the snap), and the leg is kept under the moved point (`LegCache.alias`, `PlanRecalcState.aliasSnappedLegs`). Another device gets the moved point and routes from it, and BRouter can end that leg slightly differently. Plan codes without tag 7 also round points to 1e-5°.

**Measured** (real BRouter, `E10_N45`, four long tours, each with a control plan that has no edit at all):
- The control is red on all four tours: the editing device's line and a fresh device's line are not identical.
- Usually a step under a metre at plan points. With the exact snapped points the München–Venezia Bike tour also differs by a side path at a stop in the Pustertal (about 170 m against 360 m).
- Total distance differs by at most 0.08 %.
- Routing once more from the snapped points settles it: after the second run no point moved on any of the four tours (one extra run, 1.5–9.8 s on the PC for the whole tour).

**Not measured:** a phone, Wasm, tours outside `E10_N45`, how often users would notice.
