# Review of the planner's writer part – [umbau] 2026-11-26

Fresh Opus reviewer, read-only, `4c37a0c38..origin/wip/laptop-2026-11-25-writers-web` (563db94ff, 05b8c82aa, 253af15a4).
Line numbers are on that branch. Integration branch with main + writers + this part: `work/umbau-writers-0-18-11`
= 34b505516 (clean merges, gate full green 1954 + 2081 + 1105; Web planner run 45882460113).

**Verdict: not ready for main.** 1 must-fix, 2–6 should-fix. No route lost, the settle never writes over a newer plan.

1. **must-fix – worker plan and page plan differ after a run.** `WebPlanChain.kt:303-325` (`end` → `recalc.taken`) and
   `:207` (`nameOfStop = null`). `taken` → `snapToRoute` moves the worker's points / snapped history / kept lines with the
   engine's unrounded coordinates and no named stops; the page adopts `r.plan.points` rounded to 6 decimals with its
   named stops in place (`historySnap` then does nothing: `same6` fails). BRouter's `getILat()/1e6 - 90.0` differs bit for
   bit from the parsed 6-decimal text in ~75 % of cases; `withNamed(emptySet)` re-snaps a named stop onto the way (≤1000 m).
   On the next `plan.replace(pagePoints)`: `PlanStitch.after` / `PlanAnchors.follow` see new points → anchors dropped
   without undo, saved route turns unsaved; `linesFor(before)` misses → next edit not stitched; „Ganz neu berechnen“
   finds no anchors (row null); Undo brings the named stop back moved and nameless. The smoke misses it (reload before
   each edit). Fix: hand `taken` what the page adopts – points `round(x*1e6)/1e6`, the page's named stops (names into
   `runEnd`, `recalcHost.nameOfStop` / the edit ports' `stopName` answer). Test: WebPlanChainTest with unrounded
   `route.snapped` + one named index, then `record` with rounded points → anchors kept, edit stitches.
2. **should-fix (regression) – a tap/hold kills the detour check and the settle.** `client.ts:105`: `holdOnMap`,
   `gesture`, `pointMenu`, `placeMenu`, `placeEdit` added to `endsDetour`, but `stopDetour` restarts the worker for
   `detourAt` too. A tap during a detour step cancels the hint (and logs an error); the settle's `pending` dies;
   a tap during the settle → spurious „Settling … failed“ WARN (`main.ts:1743`), never retried → web and app end on
   different lines. Comment at `client.ts:83` now wrong. Fix: separate „ends settle“ list, or settle one leg per step like
   `detourAt`; a cancelled settle is quiet and retried when the plan is unchanged.
3. **should-fix – the settle is one call that can block for minutes.** `encode` (Save, Share, address bar), `poiMenu`,
   `rows`, search, `routeName`, `planTime` wait behind it; an edit restarts the worker (no kept lines, no `lastResult` →
   not stitched); legs routed so far lost (`legsOut` only at the end). Fix: the stepped settle of 2, legs saved per step.
4. **should-fix – „changed, not saved“ right after Save.** `main.ts:2074` compares all `engine.anchors` (any profile) with
   the saved code's anchors (filtered by `forProfile`). Stitch on BIKE, switch to HIKE, Save → unsaved at once and after
   every reload. Fix: compare with `anchorsCounting(enc(state.points), saved.activity, saved.sac)` like `sameAnchors`.
5. **should-fix – Undo after „Ganz neu berechnen“ restores the wrong multi-day tour.** `main.ts:2482` `recalcFromScratch`
   adds an engine undo step without `tourUndo.push(state.tour); tourRedo.length = 0` (as `commit`, `:587`). Or hide the row
   for `state.tour`.
6. **should-fix (differs from the app) – the page never settles a multi-day tour.** `settleLine`'s `same()`
   (`main.ts:1745`) requires `!state.tour`; the app's `PlanRecalcSlice.tourRuns` settles in the multi-day overview. Drop
   `!state.tour` or agree with the app.
7. nit – the settle clears the detour hint (`main.ts:1752` `planGaps.set` from a result without detours; `settleRun`
   replaces `lastResult`). Check what the app keeps after `takeQuiet`.
8. nits – `end` „failed“ logs `Problems.error` on top of the page's own report (and „ok“ without a result too);
   `head` → `plan.pin` never prunes; `WebPlanStore.savedAnchorsJson` is never set on the web (the rule lives in the saved
   code, fine apart from 4).

Fine: no stale write (every edit / Neu / link / Undo ends the settle; `same()` / `takeQuiet` guard); the recalc row is
one undoable step; a saved route with other detour points stays unsaved across reload; cache clear and data change stop
the settle.
