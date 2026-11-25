# Detour stitch readers: second review, full E2E, Web planner ([umbau] 2026-11-24)

Branch `wip/donald-2026-11-22-stitch-readers-all`, now **d6493f680** (was 3c7779e2c, donald's merge of main's long plan link).
Nothing on main, nothing released (captain's word: donald merges, asks the owner for the release).

## What was read
One fresh Opus reviewer, read-only, on its own worktree: `git diff 248482631 cd9777cfd` (AnchorSet / §8.16, the order in
`RoutesSlice.openTour` + `RoutesOpenState.openSharedPlan`, `MultiDayFlow.snappedDay` + the guard in `pointsChanged`), the later
fixes c0d3d9d7a, 18635d52c, e12c39dff, ac64075d3, and the merge 3c7779e2c. Found sound: AnchorSet / §8.16, both orders,
snappedDay / pointsChanged / DayFollow, Zuletzt once and only on success, the DB paths incl. the onOpen repair, the merge.

## Fixed (530f3d356 … d6493f680)
1. **must-fix, also for plans WITHOUT anchors:** a track's or round trip's profile switch re-routes the old plan on the new
   profile at once; Zuletzt then stored it under the OLD activity with the NEW profile's route (distance, line, times,
   difficulty). Now `OwnProfile(activity, sac, route)` is taken before the switch (`RoutesSlice.leavingNow()`, the
   track note, the round trip's `take` in AppViewModel) and `SaveState.keepAsDraft(why, own)` keeps exactly that;
   `SaveHost.keepWhole(act, sac, route)` gets the same route for a followed day.
2. **should-fix:** the track's note went stale (closed panel, edited plan, re-opened track → a bike plan kept as a hike).
   The note now holds the plan's points and counts only while they are unchanged.
3. Small: the snap test now runs with the tour's own profile (with null the check was skipped); `RouteItems.planCode`
   checks anchors against the clamped T level; no `Column anchors already there` info on every app start.
4. **Web planner red after donald's merge (not flaky):** main's `web/app/test/longlink-engine.mjs` called `planCode` /
   `planCodeTour` without the readers' extra `anchors` argument → undefined in Wasm → the engine threw. The script passes
   `''` now; reproduced locally (old: throws, new: all ok). The planner's worker already passes it.
   Planner Kotlin: `WebTrackToPlan` follows `keepWhole`'s new parameter; parity: SaveState + AppViewModel checked.

Tests: `RoutesAnchorsFlowTest` +3 (track take keeps its own route, round trip the same, an edited plan's stale note).

## Checked
- `gate --full` green at d6493f680: 1911 app + 2003 core + 979 state, screenshots.
- **Full E2E on GitHub green:** 113 cases, 0 failed, run 45487971813 at 1a1a81293 (d6493f680 differs only in a web test script).
- **Web planner green** at d6493f680: run 45490248195 (build, jvm, all smokes).
- The fifth finding (`TourDb.setTourPlanActivity` clears the copy's anchors) was already on the branch (c0d3d9d7a) with a JVM text test and
  `TourDbMigrationTest` on the device.

## Not checked
The Pixel, a stitched plan really run in the app, adds 5–10 km off (as before). The core routing (Planner, LegCache) was outside this review.

## For the prune list (owner's yes)
`ci/umbau-readers-all`; the reviewer's worktree `agent-a64e455d58133a00f` (nothing committed); my worktree `umbau-readers-all` (branch `work/umbau-readers-all`, pushed).
