# STOP 2026-11-26 ~21:00 (owner: 0.18.11 is NOT released – the Daumen-Karte rebuild goes first, in a fresh donald) – start here

**On main today (each gated, pushed):** position while the route is changed on a tour = the normal blue dot with
direction, the activity badge gone (owner's pick B, aa4b9f419); **in Plan the numbered points lie over the dot**
(`map/PointsOverDot`, owner's option 1 – point ① seen when you stand on it; Navigate: dot on top) 162e66f13 – CorePlan +
CoreField E2E green on GitHub (35/35), **not seen by eye** (the emulator draws no route points) → look on the Pixel;
viewer start view from [tracking] merged 8f418ca96; pairing steps text option 1 („sonst im Browser auf „In OnPath öffnen“
tippen“) f63af904c. The detour writers are on main too (642904377, umbau).
**0.18.11 draft (not released):** `wip/donald-2026-11-26-release-0.18.11` = version 0.18.11 + EN notes + German item,
written before the writers landed; the next release takes what fits from it. The owner's publish word of 2026-11-25 is
withdrawn (backlog line removed). No device job running, no wip work open.
**Prune later:** `ci/donald-2026-11-26-points-over-dot` (merged), the branches named below.

# SHUTDOWN 2026-11-25 ~23:10 (owner's word via the captain) – start here, fresh donald

**On main (all gated):** 0.18.10 released 10:54 UTC (de9bb71a4, planner deployed). Viewer countdown V1 + „Geteilter
Standort“ 1ea95b37a (NOT deployed yet – goes live with 0.18.11's planner deploy). 0.18.11 app list complete
(`items/2026-11-25-release-0-18-11-plan.md` „[donald] done“): off-route loop jump + time-scaled skip 4b5988f31,
AGREE_DEG side check, NextStop passedBy + noisy arrival cd49d70cb, leadFailedOk + „none lost“ + zip a7407b508, privacy
texts (owner's yes) cf8c44351, My routes keeps its search 0328e6d02. No unfinished work, no wip branch, no device job.
**Waits:** the detour writers (umbau app done on `wip/umbau-2026-11-24-stitch-writers`; the laptop's planner part) on
main. Then: version 0.18.11 + EN notes (+ German item), full E2E + Rundgang on a `ci/` branch, Web planner green,
`release_check.sh --go v0.18.11` → **publish yourself: the owner's own word in donald's chat 2026-11-25 ~16:45**
(„publish everything to a final release … to test end-to-end in real life“; memory release-word-0-18-11). Red stays out.
**Owner question (backlog „Waiting for the owner“):** bike marker hides point „1“ – a picture first (marker under the
numbered point / smaller near a point); recommendation: marker under the numbered point while within ~30 m of it.
**Not checked on a device:** the 0.18.11 fixes (emulator helper stopped at the welcome screen; E2E covers the flows).
Prune later: `ci/donald-bisect-*`, `ci/donald-2026-11-25-*`, `wip/donald-2026-11-25-*` (all merged).

# 2026-11-25 ~11:55: 0.18.10 RELEASED on the owner's word („Ok, for the release“) – de9bb71a4 (the release commit cherry-picked onto main ab07f1cd6+docs; only test-only Robolectric + a planner CSS fix came in between, both CI-green), release run 45597989609 green, planner deployed.

# Night 2026-11-25 (fresh donald, ~02:00) – state

**On main:** restore box 2e754723f · detour reason fb38e0ec4 (Web planner jvm with WebDetourTest green) · share-name
blocker fixed 224ea091d (`LiveShareNameE2ETest` red → green; Navigate's `LiveSignLayer` draws the name question unless
Settings is open) · `CoreFieldE2ETest` stop hold picks an open spot 80 dp from the points 7d1dd4044 (brouter.de data of
2026-11-24 moved „Hierhin“'s snap; red on unchanged b1df0f478; class 35/35 green with it).
**0.18.10 prepared, NOT published:** `ci/donald-2026-11-25-release-0.18.10b` = main 8264369bd + version + EN notes
(`docs/release-notes/v0.18.10.md`); German text `items/2026-11-25-release-0-18-10-notes-de.md`. First branch (…-0.18.10):
full E2E 113/114 (the hold test above), Rundgang 18/18. Branch b (29a844f67): **full E2E 114/114** (45521118312), **Rundgang 18/18** (45521127107), `release_check.sh --app 78f3f75c --go v0.18.10` **GO** (78f3f75c → 8264369bd is one doc only; planner efe3ed5a).
**To publish (owner's word in donald's chat):** push the release commit of branch b onto main (fast-forward, or
cherry-pick on a newer main + gate) – release.yml then builds, tags and deploys the planner. Before: `release_check.sh --go v0.18.10`.
Prune later: `ci/donald-bisect-*`, `ci/donald-2026-11-25-*` (first release + rundgang + e2e-hold-fix).

# Handoff donald, Tuesday 2026-11-24 ~23:35 (night mode, context full – for the fresh donald)

Start: `docs/team/roles/start.md` → this file → backlog lines for [donald]. **NIGHT MODE** (captain ~23:05, owner asleep):
no question boxes – answer yourself with the recommendation, one line in `docs/team/items/2026-11-24-night-answers.md`;
publish nothing; 0.18.10 ready to the last step in the morning, his word in donald's chat. Opus for you, Sonnet helpers.

## Done today (on main)
- Detour line in his words „Umweg 17,4 km notwendig, ② erst ab Wandern T2 erreichbar“ (the laptop built the planner side).
- Standing-still GPS by the motion sensor (owner's box): poor fixes don't wake while the sensor says still, a sensor
  minute rests, the sensor moving wakes, **GPS off after 1 h still** (owner) – `recording/RestGps`, `RestWatch.onTick`.
  Test APK of main at that point on his Pixel 23:03. NOT checked: E2E, a real tour.
- LordTriangel PRs 20–22 read (notes in backlog Next, parked by the owner). Restore-plan box approved (picture + his lines).

## Branches – the night list (captain), in order
**1. DONE 23:55: the readers are on main (07fbe331d)**, Web planner on the branch green (45499972936). Still to do: watch the
Web planner run on main for 07fbe331d (deploys the planner); red → fix at once.

1. **Readers** `wip/donald-2026-11-22-stitch-readers-all` = **a171c962a**: umbau's d6493f680 + main merged (conflicts:
   TimeEstimateStub kept the real time; WebApi keeps both the detour exports and planCode/planCodeTour with `anchors`;
   parity merged, AppViewModel marked) + **areas-smoke B2 fix** (the modal wait window's veil took the `#locate` click –
   now `evaluate(() => locate.click())`). Gate full green, `DetourHintJvmTest` PASS on real data. The first Web planner
   run (45497794706) was red only in areas-parity B2; **the run with the fix is 45499972936** – green → merge main, gate,
   push main, watch Web planner on main, then a backlog line for [laptop]: readers on main, the B2 smoke change.
   LiveLaneTest failed once under two parallel gates (500 SQLite writes > 10 s), green alone – load, not the readers.
2. **Restore box** `wip/donald-2026-11-24-restore-ask` = **607af9e48** (worktree `agent-aae3feac0003a1128`): on readers
   8fa876931, gate full green (1937 app / 2032 core / 1013 state), `RestoreAskFlowTest` 11 tests. After the readers are
   on main: merge main, gate full, push main. Item: `docs/team/items/2026-11-24-restore-replaced-plan.md` (planner part
   for the laptop is there).
3. **Detour reason** `wip/donald-2026-11-24-detour-reason` = **1b127b706** (worktree `still-gps`): owner's word in the
   laptop chat „② macht 2,8 km Umweg – mit Wandern T2 kürzer“ (`plan_detour` + `PlanDetour.level`); tests + 6 pictures
   updated; gate full GREEN at 1b127b706 (before the readers landed: merge main, gate again). The planner uses the shared `PlanGaps.detourText` – check
   `web/engine/.../WebDetourTest.kt` (it compares to detourText, should follow) in the Web planner run; then main + the
   `For the roles` line from the laptop (2026-11-24) answered → delete it.
4. **0.18.10 to the last step** (NOT pushed): a branch, `appVersion` 0.18.10 in `app/build.gradle.kts` +
   `docs/release-notes/v0.18.10.md` (format of v0.18.9; DE/EN asked by the captain). Content: „Standort teilen“ for
   everyone (tour bubble, tracking e2c8a3e5a – captain: run the E2E classes that touch Navigate + the Rundgang on GitHub
   first), detour line in his words (+ its reason if 3 is in), Zuletzt keeps the replaced plan's activity / T level,
   restore box, km after a killed tour (54dec6e34), standing-still GPS / off after 1 h, readers named honestly (a plan
   with detour points from the planner opens and routes through them; the app sets none itself yet), planner's modal
   wait window. Then `tools/ci/release_check.sh --go v0.18.10`.
5. Then backlog „Next“ items tagged [donald].

## Night answers so far
None (no owner question came up).
