# Detour stitch: the reader stage, core + state done (umbau, 2026-11-22)

Branch `wip/umbau-2026-11-22-stitch-readers`, from 2a47fffb8 with main (1b6999585) merged. **Nothing of it on main until the captain says so.** No device writes anchors: `forInsert`, `followInsert`, `settle` have no caller.

**Checked at cd9777cfd:** gate green (1901 app + 2002 core + 962 state, screenshots), `SameRouteTest` 112 of 112 on the JVM (verify) and in Wasm against the data in `same-route/DATA.txt`. One independent review, its five findings handled (below). **Not checked:** a real phone, adds 5–10 km off. A stitched plan does not survive a restart or a save on the phone until the app part below is in, and the planner does not read anchors yet.

## What the stage does
- `SharedPlan.anchors` now reach: the plan on the map (`PlanDocument` / `PlanSession`, Undo / Redo per step), saved routes, Zuletzt, the followed plan's copy, backup record, sync item, plan link, a day of a multi-day tour and its write-back, the tour's follow-ahead (§8.3), routes from the browser.
- **Readers route, they never cut:** a plan with anchors skips `LegSplit.seed`, because only the writer stage has the idle step that brings a cut leg back to the router's answer (§8.15).
- **§8.16 (new): anchors belong to a profile.** `AnchorSet(anchors, profile)`; only `forProfile(activity, sac)` is routed, saved or shared. This replaces "cleared on an activity / T level change": a cancelled change brings them back, and no path can use them on the wrong profile.
- **Changed for every plan:** a plan replaced by another route or a link now goes under Zuletzt with **its own** activity and T level. Before, it took the new route's (an old bug the review found).

## What donald's app part needs (all in one piece, before any release)
1. `TourSchema` version 10: `tours.anchors` and `tour_plan.anchors` (TEXT, `AnchorsJson.write` / `read` with the row's waypoints); `stepsFrom`, `CREATE`, `PLAN_UPSERT`, `planArgs`. `TourDb` insert, `replacePlanned` (write what the row holds, empty = NULL; unlike `days` there is no "keep"), read, `getTourPlan` / `setTourPlan`.
2. `TourDb.setTourPlanActivity` must set `anchors` to NULL: the copy gets another profile.
3. `PrefsPlanStore.anchorsJson` (the text of `AnchorsJson.writeSet`, already with its profile).
4. `AppViewModel`: `PlannedRow.anchors` in insert / replace, `RecentPlans.DraftRow.anchors` in `insertDraft` / `updateDraft`, `insertDraftPlanned`.
5. `DbTourStore`: `PlanContent.anchors` on insert and replace. **Must ship with 1:** a phone that drops them sees every synced stitched plan as different (`RouteItems.samePlan`) and can send it back without them.
6. `Backups.kt`: `m.anchors` on restore.
7. The check on the Pixel. The menu row belongs to the writer stage.

**State of the app part (donald, 2026-11-22):** points 1–6 built on `wip/donald-2026-11-22-stitch-readers`, plus the findings of two reviews. Checked: gate full, `SameRouteTest` 112 of 112 (JVM), `TourDbMigrationTest` 10 of 10 and `CoreBackupE2ETest` on GitHub's emulator. **Not checked:** point 7 (Pixel), a stitched plan run in the app, the other E2E classes, Wasm with this branch; the fixes after the second review were not reviewed again.

## What the laptop's web part needs
1. `Engine.planParse`: points with **6** decimals (§8.12), and the anchors in its JSON. The page keeps them per plan and hands them back.
2. `Engine.planCode` and `WebDays.plan` take the anchors: today each re-encode drops tag 7 (share, sync, save).
3. `Engine.plan`: `anchors` into `PlanRecalcState.Run` (only those of the plan's own profile: `AnchorSet.forProfile`). `lastLegs` counts `wps.size - 1`: use the expanded legs.
4. `legsWanted`: no `LegSplit.seed` for a plan with anchors; the keys over the expanded list with `LegCache.lookupLeg`.
5. `aliasSnapped`: `LegCache.aliasExpanded` for a plan with anchors (§8.2).
6. `WebPlanChain` / page undo: `plan.arriving(points, AnchorSet.of(…))` before a loaded plan's change; the worker hand-over (`WebApi` `history.snapshot` / `restore`) also carries `history.anchorSnapshot()` (the two extra `restore` arguments).
7. `web/app/src/share.ts`: `qr.make()` has no guard and a link with anchors is longer. **Needed already for readers**, because a reader re-shares the plans it opened (§8.12).
8. Sync: check that `WebSync` keeps the item's code with tag 7 through every path.
9. The wait window counts visible legs (§8.4): check it with an anchored plan. Then `same-route-wasm.mjs` again, plus one case: link → page → share again gives the same code.

## For the writer stage (not started)
**Before any writer ships (review, not built):** the `anchors` columns store no profile and no hash of the row's points. A build without anchors (0.18.9) can edit a row and leave the column as it is; after an upgrade a point that still matches then routes through an old anchor, on the wrong activity. Store the profile and a hash of all row points, drop on mismatch.
The idle wiring notes in `handoff-umbau-2026-11-19.md` (State 2026-11-22) still hold. `forInsert` must store its anchors with the profile of the run.
