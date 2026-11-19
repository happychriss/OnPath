# Handoff [umbau] – 2026-11-19 ~03:00 (the rebuild, `docs/design/architecture-plan-2026-11.md`)

Start sentence: „Du bist die Einheit **umbau** (OnPath) auf donald. Lies `docs/roles/start.md`, `docs/owner-preferences.md`,
`docs/design/architecture-plan-2026-11.md`, `docs/design/umbau-phase-2.md`, `docs/design/umbau-phase-2-tour.md` und diese Datei.“ Rules of the job: the top
„For the roles“ line of 2026-11-18 (owner's go) – each piece own worktree, `gate --full`, fresh Opus review, then main;
a tag `umbau-phase-N` per phase after unit CI + planner run + full E2E; lines for the captain under „For the captain“.

## State 2026-11-19 evening (NEWEST – read this first)
- On main: phase 2 pieces 1–8, 9a, 10–20 (all tour pieces; 20 = 9e94daa5e), the tour weaknesses **L1 (cad8ec693), L5 (5ad426af9), L2 (with this commit's push)**. `MAX_LINES = 3_063`. Tags `umbau-phase-0/1` set.
- **9b (`recalc` → `PlanRecalcSlice`) is built and reviewed, NOT on main.** Branch `worktree-agent-a053e72521f0ce52a` (commits e6f8a3d25 + 47915fe07, on top of 9e94daa5e; `AppViewModel.kt` → 2,863 lines). Its gate has exactly one red test: `ViewModelInitOrderTest` test 1 (reads the old `recalc` text). The helper was DENIED the edit of that file by the permission check and asked me to make it – I did not (no laundering); the question is with the owner (backlog „Waiting for the owner“, also asked in chat). The replacement guard exists: `RecalcStartOrderTest` (proven red twice). **After the owner's yes:** remove test 1 of `ViewModelInitOrderTest` (keep test 2), cherry-pick the two commits onto main (parity.json by hand: the `AppViewModel.kt` mirror entry text + the new `PlanRecalcSlice.kt` mirror entry + `SheetRise.kt` watch; `PlanPanel.kt` blob stays c5df0c0…), cap = `wc -l`, gate --full, builds, FULL E2E run, push; a line to [laptop]: the mirror „recalc's long-route ask“ now lives in `state/…/plan/PlanRecalcSlice.kt`. Then piece 21 (forwarders out; left-overs listed in 9b's report: `checkRoutingData`, `recheckData`, `routingDataArrived` / `refreshRd5`, unused forwarders), „phase 2 done“, tag `umbau-phase-2`.
- Not blocked and next if 9b still waits: row T (remaining CONVERT groups, GPX import/export behaviour test), phase 3 design (core off the JVM), the backlog CAP → 20,000.
- A reviewer's findings go back to the SAME helper with `SendMessage` (it keeps its context); fresh review prompts list the concrete risks of the piece (start order, real path to the phone, stored keys) – that found most gaps.

## State 2026-11-19 later (after tour piece 17 – NEWEST, read this first)
- On main: phase 2 pieces 1–8, 9a, 10–15, tour 16a, 16b (b6964cf14), **17 `TourEndSlice` (35cfa9685)**. `AppViewModel.kt` 3,543 lines (`MAX_LINES = 3_543`). `TourOrderPinsTest` 21 pins left.
- **All six tour pieces are on main**: 18a (0b4fdaa6f), 18b (9b1b4c7a0), **19 `TourSession` (036e2750c)**, `MAX_LINES = 3_063`; full E2E 99/99 on 19. `TourOrderPinsTest` keeps P25 / P26 (code that stays in the view model). **20 is on main (9e94daa5e)** (`MultiDaySlice` / `MultiDayFlow` in `:state`, host class `app/…/ui/plan/AppMultiDayHost.kt` because `AppViewModel.kt` is at its cap – it may only shrink). **9b (`recalc`) is with a Sonnet helper** (own worktree, based on 9e94daa5e; E2E after it: `CorePlanE2ETest`, `RoutingDataFreshInstallE2ETest`, better a full run; if `parity.json`'s `AppViewModel.kt` mirror moved, a line to [laptop]). A reviewer's test gaps go back to the same helper with `SendMessage` (it keeps its context). Then 9b (`recalc`: keep `tourEdits.followCancel?.cancel(); tourEdits.followCancel = null` as its first lines; `snapToRoute` + `aliasSnappedLegs` first), 21, the tour weaknesses L1/L2/L5 (flows `L1_…`, `L2_…` exist and show them), „phase 2 done“, tag `umbau-phase-2`. The push hook refuses a push chained behind a commit in one command – gate first, push in its own call; main moves often, rebase + gate right before the push.
- Pull request 14 is merged: `core/**` + `state/**` paths, lint fails on new findings, `--continue`. The lock file, its test and `core_move.py lock` are gone (b6e2e5c34) – a helper based on an older state may still bring `web/engine/core-shared.lock` back: drop it at the merge.
- Owner's answers (via captain): decision-pin tests may be folded into their feature's flow test (the decision goes into the flow test's comment); tour pieces are released as usual; 5b (routing engine in Kotlin) is a go → [umbau-hp] builds it after the `umbau-phase-3` tag (line is in the backlog; write there when the tag is set).
- **Tags `umbau-phase-0` and `umbau-phase-1` are set on 45293f12f** (unit green on b6e2e5c34, full E2E 99/99 on 6b71c9677, planner green on 45293f12f; docs/parity stamps only between them). `umbau-phase-1.md` is deleted. `umbau-phase-2` after piece 21 + the three checks.
- E2E class names need the package: `com.onpath.app.e2e.CoreFieldE2ETest` (a wrong name fails the run in minutes).
- Review lesson of 17 (in the 18a brief): for every order of two writes a flow must read the effect back; for every asserted „X is null“ the test must have set X before. A helper keeps re-sending its report → `TaskStop` after the cherry-pick.
- Merge with a moved main: `git rebase origin/main`; `web/parity.json` conflicts by hand (keep both entries), then `node web/parity/check.mjs --update app/src/main/java/com/onpath/app/ui/AppViewModel.kt <new shared files>`; cap = `wc -l`; gate --full; app + androidTest compile; `-p web/engine test generateWasmGC -Pkotlin.incremental=false`.

## Done (on main)
- **Phase 0:** E2E nightly + 4 emulators (pull request 13); backlog slim + cap in the gate (`tools/dev/backlog_slim.py`,
  cap 40,000, target 20,000 once read role lines are deleted); core-features index; 350k handoff rule; 0b sleeps →
  `waitUntil` / `pause` (6 real waits, 114 named pauses).
- **Phase 1 piece A** (63b56ac93): hub splits – 213 of 231 candidate files can move.
- **Row T** (test clean-up, owner 2026-11-18 night): `docs/design/test-audit.md` + `.tsv` – verdict per test file.

## State 2026-11-19 ~05:30 (newer than the list below: items 2–4 are DONE)
- Piece B (b704582a8) and piece C (cb70c092a) are on main: `:core` = 253 main files, 1,744 tests; `:app` 1,988 tests. Nothing movable left (`python3 tools/dev/core_move.py plan --all-pure`).
- Workflow patch 2 is in `tools/ci/pending/core-paths-lint-2026-11-19.patch`, the captain is asked for the pull request. After its merge: delete `web/engine/core-shared.lock`, its test in `SharedFilesRuleTest` and `core_move.py lock`.
- `gate.sh` retries once with `-Pkotlin.incremental=false` on „Unresolved reference“ (stale state after moves). `web/engine` needs that flag by hand once.
- Lesson of piece C: a helper's edit put code into a `//` comment in `CellStore.tileHash` (stored hash) – only the Opus review saw it. Every content change in a moved file needs a behaviour or pin test; ask the reviewer for hunks in non-100 % renames explicitly.
- Next: piece D (planner on the module – line to the laptop, build it yourself if no answer), then E (tag `umbau-phase-1`; needs mehrtage's `TourDbMigrationTest` fix for a green full E2E – the tag `umbau-phase-0` waits for the same). Row T CONVERT groups of the core packages (`docs/design/test-audit.md` §4/§8) can run beside D as own pieces: start with G01 (`Broken*Test` → one table test) and `SaveFlowTest` (C18) – Sonnet helper + Opus review each.
- Then phase 2 design (`:state`): read `ui/AppViewModel.kt` slices already cut (`ui/state/`?), write `docs/design/umbau-phase-2.md` first, „phase 2 starts“ line under „For the roles“.

## State 2026-11-19 ~07:00
- CI for piece C: unit green, planner run fully green (f0bae480).
- Row T: G01 (`Broken*Test` → `BrokenFilesTest` / `BrokenAppFilesTest`, f2a06c999) on main. **G04 `SaveFlowTest` was taken back** (branch `worktree-agent-a6890f6908a681410`, commit bc58f6a1d, not merged): half its tests checked the test's own copy of the view model's save steps. Lesson: flow tests for anything that lives in `AppViewModel` come WITH phase 2 (the slice is the thing under test); before that only convert groups whose logic is in `:core`. Review prompt that worked: „name the tests that stay green if the production code breaks“.
- Piece D (planner compiles `core/src/main/kotlin` as a folder) is being built by a helper (the laptop did not answer the offer); when it reports: Opus review, then merge, then a line to the laptop asking for its look.

## State 2026-11-19 ~09:00
- Piece D on main (f40c8e6c6). Phase 1 complete except piece E (tag; blocked by `TourDbMigrationTest` on main → mehrtage).
- **Phase 2 started** („phase 2 starts“ is under „For the roles“). Design: `docs/design/umbau-phase-2.md` (21 pieces, briefs for 1–3 in §6, member index `umbau-phase-2-members.tsv`). Decisions taken by the lead: own module `:state`; Compose runtime snapshot state stays; piece 21 (screens read `vm.<slice>.x`) is in; mirror goal restated (state-logic mirrors 8 → 0). Pieces land serially; only the lead edits `MAX_LINES` of `AppViewModelSizeTest`.
- Per piece: Sonnet helper in a worktree with the brief from the design → Opus review (ask: bodies byte-identical via `--color-moved`; „which tests stay green if production breaks“; stored formats; init order) → own `gate --full` + androidTest compile + `-p web/engine test generateWasmGC -Pkotlin.incremental=false` + `node web/parity/check.mjs` on top of main → push → watch unit + planner CI.

## State 2026-11-19 ~10:30
- Phase 2 piece 1 on main (15d9f1231); `AppViewModelSizeTest.MAX_LINES` = 5,752 (forwarders eat the saving until piece 21). `RouteRowActions` stayed in `:app` (`ReviewText` builds icon rows). Piece 2 helper is running (brief = design §6 piece 2 + lessons); then piece 3 `SaveSlice` (C18; the taken-back `SaveFlowTest` on `worktree-agent-a6890f6908a681410` @ bc58f6a1d shows the flows – it must drive the real slice).
- Pending CI patch is now `tools/ci/pending/core-state-paths-lint-2026-11-19.patch` (local branch `umbau-workflow2` holds its two commits).
- `web/engine/gradle.properties` (heap) added after CI's jvm job went out of memory; check the planner run of 1c3c128 / later is green.
- Known gate gap (old): a `:core` change outside `domain/` does not run the `:state` flow tests in select mode (unit.yml's full run does).

## State 2026-11-19 ~12:00
- Phase 2 piece 2 on main (fe4e02117), `MAX_LINES` 5,723. Stayed in `:app` against the design: `SettingsSummary`, `RoutingLoads` (need Android / Composable-file types), `RouteRowActions`. DROP list of the design is too optimistic (1 of 20 deleted). Reviewer follow-up idea (not done): `SuggestSlice.run`'s inner catch could show FAILED for non-VM errors.
- Piece 3 (`SaveSlice`) helper running; review must check: bodies verbatim (`--color-moved`), same thread / order of DB writes, the JDBC fake's SQL equals `TourDb`'s, break proofs real, the `AppViewModel` parity mirror follows `requestSave`; then run ONE E2E class that saves on GitHub (`tools/ci/e2e_github.sh --ref ci/umbau-p2-3 --tests <class> --wait`) before the push.
- Pending CI patch has 3 commits now (core paths + lint, state paths, `--continue`); local branch `umbau-workflow2`.
- The planner lock bites as designed: after ANY edit of a core file or a core test the planner compiles → `python3 tools/dev/core_move.py lock`.

## State 2026-11-19 ~14:30
- Phase 2 piece 3 (`SaveSlice`, C18) on main (af2fb4bca), `MAX_LINES` 5,506; `CorePlanE2ETest` green on GitHub with it. Also on main: the lost wake-up fix in `MapCells.request` (the flaky `NoStaleDownloadsTest` was a real bug).
- **Helpers get `docs/design/umbau-phase-2-brief.md`** + the piece's row. Running now: piece 4 (`SettingsSlice`, lane C) and piece 8 (`PlanRoute` I, lane A). Merge serially; the second rebases. Per merge: lower `MAX_LINES` to the new line count yourself.
- Review prompt that works (Opus): compare each moved body old vs new via script, list every difference that is not a stated substitution; the store / host adapters' arguments; „which tests stay green if production breaks“; every old pin and what carries it now; `@Test` counts per module.

## State 2026-11-19 ~17:00
- On main: phase 2 pieces 1–4 and 8 (4342d6673), `MAX_LINES` 5,298. Running: piece 5 (`MapViewSlice`, branch `worktree-agent-ac61e54146d4400f3`, reviewed MERGE, helper closes lows L1–L4; at merge: rebase onto main, re-hash the `AppViewModel` parity blob, cap = new line count, run `CorePlanE2ETest` on a `ci/` ref) and piece 9 (`recalc`, HIGH risk, lane A; its brief asks for a split if entangled).
- Next after them: lane C piece 6 / 7, lane B piece 13 (Routes I), lane A piece 10 (PlanEdit, after 9). Pieces 16–19 (tour) one at a time with `CoreFieldE2ETest` between; no release between them and the owner's Pixel tour (his answer is still open – assume yes).
- Review lessons now in every helper prompt: fakes record state at call time (order), fakes check the thread, old pins re-pointed with anchors that must be found, adapters get source pins, no rule twice.

## State 2026-11-19 ~20:00
- On main: phase 2 pieces 1–6, 8, 9a (`PlanRunSlice`: run state + effects; **`recalc`'s coroutine did NOT move** – ~35 members incl. tour code; second half after pieces 10 + 16–19; suggested order: `snapToRoute` + `aliasSnappedLegs`, the routing-data members, then the coroutine). `MAX_LINES` 4,987.
- Running: piece 13 (`RoutesSlice`, branch `worktree-agent-aa365053dc6604e85`, in Opus review), piece 10 (`PlanEdit`, lane A).
- Merge recipe that works: `git checkout -B umbau-tmp2 origin/main`; cherry-pick the helper's commits (on a `web/parity.json` conflict: `git checkout --ours web/parity.json`, continue, then `node web/parity/check.mjs --update app/src/main/java/com/onpath/app/ui/AppViewModel.kt` – and re-add a NEW mirror entry by hand if the helper's commit added one and it got dropped; check `grep -c <Slice> web/parity.json`); set `MAX_LINES` to `wc -l`; `gate --full`, assembleDebug + androidTest compile, `-p web/engine test generateWasmGC -Pkotlin.incremental=false`; push to `ci/umbau-p2-N`, run the piece's E2E class with `e2e_github.sh --ref … --tests <one class> --wait`; push to main. A source pin that counted one occurrence may match twice after two pieces meet (seen: `RouteWarningsTest`) – scope it to its object.

## State 2026-11-19 night (after piece 10)
- On main: phase 2 pieces 1–8, 9a, 10, 13 (85e409ce6), `MAX_LINES` 4,548. Fix commit inside piece 10: `movePointInList` with a stale / same row emptied the plan.
- Running: piece 14 (`RoutesGoSlice`, branch `worktree-agent-a615037780b740993`, reviewed MERGE; helper adds `RoutesGoWaitTest` + a virtual-time dispatcher with `invokeOnTimeout` in the test kit; at merge run `CorePlanE2ETest`, `CorePlanLinkE2ETest`, `DoneTourReviewE2ETest` – one class per dispatch, each on its own `ci/` ref in parallel) and piece 11 (`PlaceMenus` I, lane A).
- A helper prompt that told the helper to `git reset --hard` onto an unmerged `ci/` ref was refused by the permission classifier – start helpers from `origin/main` only (wait for the push).
- Order from here: 12 (after 11), 15 (sync driver), 16 → 17 → 18 → 19 (tour: ONE at a time, `CoreFieldE2ETest` between, Fable-written order rules first – design §4.3), 20 (`MultiDaySlice`), 9b (`recalc` coroutine: `snapToRoute` + `aliasSnappedLegs`, routing-data members, then the coroutine), 21 (forwarders out). Then „phase 2 done“ line, tag after a green full E2E (still blocked by `TourDbMigrationTest`, mehrtage).
- Row T follow-up noted: an E2E (or JVM harness) for GPX import / export – no behaviour test exists.

## State 2026-11-20 (after piece 12 – the plan and routes lanes are done)
- On main: phase 2 pieces 1–15 except 9b (f0b90dc13), `MAX_LINES` 3,901, 497 flow tests in `:state`. Slices: notices, routes (list / go), save, settings, map view, plan route / run / edit, place add / menus, kept places, sync driver / synced routing; `:app` helpers pictures, demo, import-export.
- **Tour pieces: `docs/design/umbau-phase-2-tour.md`** (36 order rules, six landings 16a → 16b → 17 → 18a → 18b → 19; the standing brief has a section for them). Running: **16a** (recording contract to `:core`, `FakeRecordingClient` to the kit in the service's order, `TourOrderPinsTest` with 22 pins each proven by a swap; no body moves). Its review: wire strings byte-identical, JVM names, the pins' strength, existing tests that only passed with the fake's old order. E2E before the push: `CoreFieldE2ETest` (35 tests) + `StartSmokeE2ETest`.
- After each tour piece: `CoreFieldE2ETest` on a `ci/` ref (19 also `CoreIndoorStartE2ETest`, 17 `UnfinishedTourSetupE2ETest`). One at a time, no other helper on `AppViewModel.kt`.
- Helpers that keep re-sending their finished report can be stopped with TaskStop once their commits are cherry-picked.
- Reported to others, not fixed: POI kind not remembered by `addPoiPoint` (donald); synced-routing write race (laptop); tour weaknesses L1 / L2 / L5 (owner question).

## Open, in this order
1. **Tag `umbau-phase-0`:** needs one green full E2E run of main: push main's head to `ci/umbau-p0`, then
   `tools/ci/e2e_github.sh --ref ci/umbau-p0 --wait` (no `--tests`; a comma list of tests runs only the first). Red today
   only by `TourDbMigrationTest` (schema 9 needs its case – line to mehrtage) and the flaky `OverviewSwipeE2ETest`.
   Old `ci/umbau-wait*` branches: remove with `tools/dev/prune_branches.sh` (own ci branches, no owner question).
2. **Piece B** (`:core` module, `:strings` jvm target, `tools/dev/core_move.py`, first 57 files + 27 tests): branch
   `worktree-agent-a97ded78f641e807e` (worktree `.claude/worktrees/agent-a97ded78f641e807e`). Reviewed: FIX FIRST – the
   helper is on the fixes (rebase; `web/engine/core-shared.lock` + rule test because web.yml's cache key does not know
   `core/`; Android-API guard for `:core` (Animal Sniffer, API 26); tool traps: no test class into testkit, resource
   folders, web-listed tests; small things). When it reports: check the list is done, `gate --full` yourself on top of
   main, `-p web/engine test generateWasmGC`, `node web/parity/check.mjs`, push. A second short Opus look at the fix
   commits only (lock test, sniffer wiring, tool changes).
3. **Workflow patch 2** with piece B: local branch `umbau-workflow2` (this worktree) = `/tmp/core-paths-lint-2026-11-19.patch`
   (may be gone – recreate: `core/**` in unit.yml + web.yml path filters and web.yml's cache key, `core/build/reports/tests/`
   in the unit report, lint `abortOnError = true` + `continue-on-error` out). Put it in `tools/ci/pending/` and ask the
   captain for the pull request (the token here has no `workflow` scope). After its merge: delete the lock file + its test.
4. **Piece C:** the big move with `core_move.py` (plan said 119 files now; add the pure non-candidates `data/MultiDay`,
   `poi/PoiKinds`, `data/LastActivity`, `data/TourPlan`, `data/FollowFile`, `recording/AscentCounter` to the hubs if they
   are Android-free → most of the rest follows). Before each package moves: sort its tests by `test-audit.tsv` (DROP /
   CONVERT groups of that package; a field-bug test is never dropped; C18 saving needs `SaveFlowTest` first). In batches
   by package, each batch gate + review. `RecomposeProbe` / `ScaleRule` (internal, used by ui) stay in `:app`.
5. **Piece D** with the laptop: `web/engine` compiles `core/src/main/kotlin` as a source folder; list shrinks;
   `SharedFilesRuleTest` + the border test of `SharedCoreRuleTest` go; stand-ins `Problems`, `UserText`, `GpsStatus`,
   `DataFiles`, geojson go. Line to the laptop is in the backlog (they may take or review it).
6. **Piece E:** checkpoint – unit CI, planner run, full E2E, tag `umbau-phase-1`; delete `umbau-phase-1.md`.
7. **Phase 2** (`:state`, AppViewModel slices): write „phase 2 starts“ under „For the roles“ first. donald builds „Undo in
   the points sheet“ and IR's cards row is on main – both should be in before. Then phases 3, 4 (4b screenshots is
   offered to [umbau-hp] after its spike; lint-fails-on-new is in workflow patch 2).

## Things to know
- Push from this worktree: `git push origin HEAD:main`; the hook wants `tools/ci/gate.sh` green on HEAD first. Backlog
  edits: run `python3 tools/dev/backlog_slim.py` before committing. Unpushed work sits on the worktree branch; for a
  docs-only push beside it I used a temp branch `umbau-tmp2` from origin/main (branch deletion is denied – reuse it with `-B`).
- Helpers: Sonnet in own worktrees, Opus reviews read-only; their worktrees stay until merged. Gradle only via gate /
  `tools/dev/gradle_slot.sh`.
- Night watch: the captain asked for an hourly self wake-up „on the owner's word“; not set, because that word is not
  in the backlog (wake-up-off is the owner's rule). Set it when the line is there or the owner says so here.
- Spike 5a is [umbau-hp]'s; my helper's start is `wip/umbau-2026-11-18-kn-spike`.
- Waiting for the owner: may owner-decision pins be folded into flow tests (row T).
