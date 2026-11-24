# Handoff donald, 2026-11-20 afternoon (for the fresh donald session)

Start: `docs/roles/start.md` → this file → backlog lines for [donald]. Token rules (owner 2026-11-20, captain line on top
of „For the roles“): fresh session at ~350k context; helpers on Sonnet (set `model`), one big-model review per piece;
**no full E2E / Rundgang per small weekend update** – gate + the change's own tests.

## Releases today
- **0.18.2** (52d0eb547, morning, owner's word in donald's chat; full round green, fresh first start on a wiped emulator
  green), planner deployed by the release run.
- **0.18.3** (318182e2a): a point on the routed line splits its leg at once (shared `core/…/routing/LegSplit.kt`). Its
  planner deploy was skipped on a red areas-parity smoke → handed to [laptop].
- **0.18.4** (5df211840, ~14:10): a hold always lands (`PlaceAddSlice.measureHold` decides again on the shown plan),
  a POI from its menu keeps its kind at once, [umbau]'s „Zuletzt updates its entry“. Check the release run
  (`gh run list --workflow release.yml`) and the planner deploy in it; a full E2E on `ci/donald-2026-11-20-rc-0184` ran
  beside it – look at its result once.

## The owner's words in donald's chat (also in memory)
- **Weekend (until Mon 2026-11-23):** his fixes / change requests (via the captain) go out as 0.18.x updates without a
  further question, after gate + the change's tests. After Monday: no release without „prepare for building a release“.
- Pixel (USB 44080DLJH000E5) runs the official 0.18.2 since 10:10 – he updates via the app; not on USB this weekend.
- `CF_START_TOKEN` deleted (his word). The paper try is dropped („gar nicht“).

## Open for donald (top first)
1. **The place menu's silent drop** – `state/…/ui/plan/PlaceMenusSlice.kt:157` drops a menu „not opened“ when the plan
   changed during its measure – same pattern as the hold fix 62e7064c5; test first, ship as an update.
2. **Workflow patch** `tools/ci/pending/e2e-first-map-key-2026-11-20.patch` – a `first-map` E2E job with the TEST key
   running `FirstMapDownloadE2ETest` (on main; skips without the key, green on GitHub). The token cannot push workflows
   → captain / owner opens the PR, owner merges.
3. **Website pictures after 0.18.2–0.18.4** (Next item) – `tools/dev/pictures_affected.sh v0.18.1..v0.18.4`.
4. The rest of „Next“ (tidied today, `docs/team/items/2026-11-20-donald-next-tidy.md`).
- Rule learned: the project settings deny `adb uninstall` / `pm clear` (they protect the Pixel) – a fresh emulator is
  `emulator -avd Rundgang … -wipe-data` (owner's yes 2026-11-20 morning); never a second emulator.
- Branches `ci/donald-*`, `ci/legsplit-app`, `ci/neutral-test-points`, `try/*` are left on GitHub – delete only via
  `tools/dev/prune_branches.sh` after the owner's yes to the list.

## Fresh session, 2026-11-20 ~14:45–17:00 (what changed since the list above)
- 0.18.4's release run green (published, planner deployed).
- **Pull request 18** (first-map E2E job with the TEST key) open – its job ran green on the branch (the first try was red only on a
  main-thread stall warning of GitHub's emulator, now not counted: 8e0abce7f). Owner merges.
- On main, not released yet (next 0.18.x): place menu never silently not opened (26c61cc88, CorePoiTapE2ETest green on GitHub
  – the first try timed out once on the cold runner, the rerun green); a failed region package asked once per run (7a51d2913);
  an interrupted tour whose start read fails once is read again (cc95c7f3b + review fixes 027653dc1); NoStaleDownloadsTest flake (2342a23e9).
- Website pictures: app side only I02 (onpath.private f0442c4); planner pictures → [laptop], build-in → [cloud-web] (backlog line).
- Open for donald: the pin menu over the search bar (needs the emulator – no camera event after `panBy`), the interrupted-tour
  rest (both reads fail), I03 picture, the rest of Next.
- **0.18.5 released** ~16:40 (b2454f04a): the batch above + the interrupted-tour rest (0ef237e65: both start reads failed → the next
  tour start reads once more). Change E2E on GitHub green (CorePoiTap only on rerun – runner slow; Next item for the test).
- Evening: found-place menu below the search bar (5d3be63e5 – `MapController.panBy` eases the camera; MapLibre's `scrollBy`
  sends no camera event at all), CorePoiTap held once more after a slow-runner timeout (80b260cf3), POI-name check
  `tools/check_poi_names.py` (8273568d6; style clean since d91d04d23; before/after renders in `~/onpath-cells-work/poi-names-check/`).
  Ship with the next 0.18.x together with tracking's „Standort teilen“ (hidden behind the developer tools – captain 17:45).
- **0.18.6 released** ~19:35 (a68d79933): full E2E green twice (101/101 on 36db9f14 and bc5577e9), release run green.
  Next for the fresh session: the rest of „Next“ top-down (I03 picture, very short recordings, the green line, colour rests …).
  PR 18 still waits for the owner. Helper worktree `agent-adb75876…` is locked (stopped agent) – harmless.
