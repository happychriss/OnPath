# Detour stitch: the writer stage, core + state (umbau, night 2026-11-24/25)

Branch **`wip/umbau-2026-11-24-stitch-writers` = c651906ac** (from readers-all d6493f680). **Nothing on main; writers ship only after the readers' release** (design §6), app AND planner together.

## Built (each piece by a Sonnet helper, merged by [umbau], full gate green after each merge; last: 1912 app + 2030 core + 996 state)
- **W0** (8349b4289): DB columns `tours.anchors` / `tour_plan.anchors` in row format – `AnchorsJson.writeRow` / `readRow`: the row's profile + an FNV-1a 64 hash of all its points (pinned value `62c3b8e261299bd5` for the test points, computed outside Kotlin); another profile or other points → none, reported. Old array still read; backup keeps the old array inside its activity-carrying record.
- **W1** (3640e9027…1c56d44dd): `PlanStitch` (core: `VisibleLines`, `linesOf`, `after` – insert → `followInsert`, move ≤ 2 km → `forInsert` again, else `edited`), `PlanSession.stitchLines`, `PlanRecalcState.linesFor` / `offerLines()` in `begin`, `LegSplit.seed(expanded)` for anchored plans.
- **W2** (ba2e30aef…bdc7e0f29): `LegCache.epoch` (no old-data leg after a clear), `Planner.settle` (epoch, cancel before put, `skip`, `failedLegs`), `PlanRecalcState.routeTaken` / `takeQuiet` / `beginning()`, `PlanRecalcSlice` settle on the routing dispatcher (cancelled by every begin and before `legCache.clear()`; not while touring / recording / Navigate; a leg failing twice is skipped until new data or a run without anchors; one quiet re-run from the cache when it routed). Also: a plan with a named stop stitches; lines dropped after a failed run.
- **W3** (bac827707): `PlanSession.clearAnchors` (one undoable step, points kept), `PlanDocument.hasDetours` / `clearDetours`, `PlanEditState.recalcFromScratch(activity, sac)`. **No menu row** (owner's pick first).
- Review fixes of W0 + W1 (44d6c96db): a day's stitch reaches a tour without anchors (with its profile); hash pinned.

## Checked
- Opus review of W0 + W1: done, fixed (above). Left on purpose: a move re-stitch is only fast when the old prev→next leg is cached; a §8.5 fallback stops stitching in that plan.
- `SameRouteTest` cold verify at 6b4dd091e: **112 of 112 GREEN** (`/tmp/umbau-sr-verify.log` on donald). The full warm-vs-cold run (`WRITE_SHA=0`) was still running at 00:30 → `/tmp/umbau-sr-full.log`.

## NOT done – the next session, in this order
1. **W2's Opus review** was running at hand-off (not reported back): run ONE fresh Opus review of `git log 6fc23bdba..26056113a` (W2) again, fix findings test-first. Known doubt: the quiet re-run does not refresh the map-data corridor (`mapData.routeComputed`).
2. **`SameRouteTest` full run:** read `/tmp/umbau-sr-full.log` (must end `full=0`, all green, no hash written); if gone, rerun: `WRITE_SHA=0 KTTEST=tools/routing/jvm-test/SameRouteTest.kt EXTRA_KT="core/src/main/kotlin/com/onpath/app/routing/PlanResult.kt core/src/main/kotlin/com/onpath/app/domain/PlanGaps.kt core/src/main/kotlin/com/onpath/app/routing/PlanReach.kt core/src/main/kotlin/com/onpath/app/routing/ProfileData.kt" JAVA_XMX=2g tools/routing/jvm-test/run.sh ~/onpath-routing/segments <workDir>` (and `MODE=verify` for the 112).
3. **W3's Opus review** (bac827707) – not reviewed.
4. **W4 = the menu row** after the owner's pick (picture `sketch/umbau-2026-11-25-ganz-neu-berechnen`, A vs B, B recommended; the sketch commit's `PlanMenu.onRecalcFromScratch` + strings are a model, never merge that branch): row visible while `plan.hasDetours(activity, sac)`, calls `planEdit.recalcFromScratch(activity, sac)`.
5. **Full E2E on GitHub** for the writers branch (`ci/umbau-writers`, `tools/ci/e2e_github.sh --ref … --wait`) + Web planner workflow.
6. **For the laptop, before the writers ship:** the planner does not stitch (`WebPlanChain` never calls `taken`, so no lines); it needs: `taken` / lines, its own settle (LegCache + engine in the page, `Planner.settle` after a taken run, cancel on edit, the cap, `takeQuiet`, a `beforeClear`), the row (`edit.recalcFromScratch`, `plan.hasDetours`).
