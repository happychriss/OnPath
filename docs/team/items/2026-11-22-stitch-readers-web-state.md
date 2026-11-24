# Detour stitch: the planner's reader stage (laptop, 2026-11-22)

Branch `wip/laptop-2026-11-22-stitch-readers`, code at 727aa939d. **Nothing of it on main until the captain says so.**

**Checked:** gate, Web planner on GitHub (run 45066861507), exact Wasm check on donald at 0c31e817e: 112 of 112 (the only commit after it adds a check to `areas-smoke`).

**Open, before the writer stage:**
- The wait window on a **cold** plan with anchors counting only visible legs is not tested for real: the browser check ran with finished legs (counter 0). It rests on the `Planner` code.
- No real phone and no plan from a real writer yet: the test plans are hand-built.
- `areas-smoke` B2 holds the running wait through the page's own wait object, not through held map data (not every test machine has that data).

**Long-link fix:** its own piece, `wip/laptop-2026-11-22-long-link` (f59badf40), reviewed, Web planner green. It goes to main and online only on the owner's word in the laptop chat. Merging it here will meet the same `linkCode` in `WebApi.kt`: keep one copy.

**Seen, not fixed:** a damaged or crafted pairing link of 700+ characters overflows the same way (`PairCode.parse`). A real pairing code is short; no data is lost.
