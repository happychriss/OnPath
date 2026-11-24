# Handoff laptop – 2026-11-17 late (context nearly full – the next laptop session starts here)

Read first: the top lines of `docs/team/backlog.md` „For the roles“ (captain 49750e83 ~23:20 = the order for tomorrow
evening's owner goal), then this page, then `handoff-laptop-2026-11-16.md`.

## Owner's goal (captain 2026-11-17 ~23:20)
Tomorrow evening a new version on the phone AND in the browser: multi-day tours + round-trip suggestions, with the new
maps. Laptop's order: (1) hold-rule mirror in `web/` → Web planner run green → write „done“ in the mehrtage line
(→ [laptop] [IR] [donald] (mehrtage …)) so mehrtage + IR can build in `web/`; (2) CELL_SCHEMA 9 (suggest layer + aims,
content 2): test cells first, published to TEST (NRW incl. Hilden / Nordkirchen / Monschau, Mosel–Traumpfade,
Fränkische Schweiz / Pottenstein 49.77, 11.41, Mittenwald / Garmisch), tell IR; donald then rebuilds all cells
(fast lane); (3) deploy the planner whenever its tests are green (`gh workflow run web.yml --ref main -f deploy=true -f quick=true`).

## Done since (2026-11-18 early)
- **Hold-rule mirror on main and live (e5f82377)**: real paths, simplified rule, one-row place menu, WebPlaceMenuTest 16/16; merged with donald's ui → domain move (engine imports follow); parity registers mehrtage's / IR's shared files; Web planner green, planner deployed. Backlog says „web/ free“ for mehrtage, then IR.
- **CELL_SCHEMA 9 on main and on TEST** (suggest layer content 2); donald has the publish commands; cell list `tools/dev/areas/owner-test-cells.txt`.
- Next for the laptop: follow mehrtage's / IR's web merges (parity, check-web, deploy when green); donald's cleanup may move more shared files.

## State 2026-11-22 late, shutdown – the next laptop session starts HERE
hp and donald were shut down by the owner. **None of the three branches is on main, nothing went online.** Every planner deploy needs the owner's own yes in the laptop chat.

**Branches**
- Readers (web part): `wip/laptop-2026-11-22-stitch-readers`, 641429f22 (code 727aa939d, Web planner green). Exact Wasm check on donald at 0c31e817e: 112 of 112; after it only an `areas-smoke` check and docs. Stays off main until the captain says so. Open points: `docs/team/items/2026-11-22-stitch-readers-web-state.md` on that branch.
- Long-link fix: `wip/laptop-2026-11-22-long-link`, f59badf40, reviewed, Web planner green, mechanism proven (`PlanCode.parse`'s Regex overflows the stack in Wasm's slow tier).
- Pairing-link fix: `wip/laptop-2026-11-22-pair-link`, af0aef505, in `core` (reaches the app; donald told), gate and Web planner green. No own review, no phone. Compared with the old Regex on 50,000 texts.
- **Everything else unsaved from older sessions:** `wip/laptop-2026-11-22-saved-work`, 46e291aa4: one commit whose parents are the 37 pieces that were on no remote (branches, worktree HEADs, one stash, two uncommitted worktrees). List: `SAVED-WORK-2026-11-22.md` in it. Not sorted, not reviewed; do not merge it, take single pieces with `git branch <name> <sha>`.

**Open questions for the owner (both under „Waiting for the owner“)**
1. May the long-link fix go online? Asked in the laptop chat, no answer yet. Recommended: yes.
2. The modal wait window: what does Cancel do? Like the app (window closes, the area loads on beside the map; recommended) or back to the old state? Asked in the older laptop chat. Do not build before his answer.

**Next, in order**
1. On his yes: long-link to main, Web planner green, deploy.
2. Then the pairing-link piece to main.
3. Readers only on the captain's word. When merging: `Engine.linkCode` is on the readers and the long-link branch, **keep one copy** (also its test: `WebLinkCodeTest` and the one in `WebAnchorsTest`); both add a step „5.“ to `share-smoke`.
4. Before the writer stage: test for real that the wait window counts only visible legs on a **cold** plan with anchors (the browser check ran with finished legs).

Still open from before: `WebLegStore.remove` does not delete from IndexedDB.

## State 2026-11-22 evening – the next laptop session starts HERE
- **Stitch in Wasm:** branch `wip/laptop-2026-11-22-stitch-wasm` = 2a47fffb8 (worktree `onpath-stitch`). `WebLegStore` has `_cut` / `_settled` / exact; `web/app/test/same-route-wasm.mjs` compares the exact hashes and refuses other data (DATA.txt). On the laptop's other E10_N45: JVM and Wasm byte-identical on all 112 lines (55 match the files, as expected not all). **The exact check is GREEN on donald (captain's run at 2a47fffb8, recorded data file, no NEIGHBOURS): 112 of 112 hash-equal, peak 2.9 GB, 242 s.** A fresh umbau session builds the readers on `wip/umbau-2026-11-22-stitch-readers` from this commit; nothing of the stitch on main yet. The captain starts a fresh laptop session for the web part of the readers and the modal window.
- Still open for the web once green: `WebLegStore.remove` does not delete from IndexedDB yet (no worker wiring), `planParse` writes 5 decimals (needs 6), `share.ts` qr limit.
- Memory: Node 22's Wasm tier-up, not the engine (over 9 GB; 2.84 GB with `--no-wasm-dynamic-tiering`, set by the runner). A real browser tab on a long tour was not measured.
- **Wait window, the owner's idea (2026-11-22):** make it modal as in the app (`WaitWindowDialog`); that replaces the „own bar“ proposal. Asked in the chat as plain text what Cancel does: like the app (window closes, area loads on beside the map; recommended) or back to the old state. **Build only after his answer.** No question boxes while work runs: they blocked this session for hours.
- robust-smoke „percent moves“: the bar's first move comes 4–4.5 s in by its own rule; the check now has the 10 s limit. The failing 60 was not reproduced in a browser here.

## State 2026-11-22 ~00:15 – the next laptop session starts HERE
- **Planner live at 8cee9a2ec** (owner's yes in the laptop chat; run 44965414468 green, deploy-quick). No standing deploy word: every deploy needs his own yes in this chat.
- Fixed: a late "detailed map here?" answer after a zoom-out turned the sign into a window (`areas.ts` rested, reproduced in areas-smoke E).
- Test only: smoke D holds the routing data 700 ms (a fast machine beat the window's 400 ms); smoke B waits for the area's own first row and checks that window has no gap. The null at line 149 (release run 44941010944) was **not reproduced**: likely a first map's closing window. If the gap check goes red, it is a real flicker.
- **Open, owner's choice (backlog "Waiting for the owner"):** an area joining a window at 94 % leaves the bar standing for minutes (proven: 40 s at 94 % beside "about 5 minutes left"). Rescaling over the rest did not help (still 94 % after 60 s). Until decided, areas-smoke "moves the bar" can go red on a slow runner when the first map's window is up.
- Parity: ThumbRules and StopsBesideRoute stamped (phone-only). donald's further commits may need more stamps.

## State 2026-11-21 ~19:00 – the next laptop session starts HERE
- Owner said „Planer online stellen“ (laptop chat). Manual run on 57b0c7ca0 red (areas-smoke), no deploy. A background
  loop in the old session waits for the push run on 9c2b9931d and dispatches deploy+quick if green – check Actions: if
  nothing went online, deploy the newest green main on the owner's standing word.
- robust-smoke 10(a): fixed for good in 9c2b9931d (the plan's wait identified by an overlay.plan hook). If red again → a
  real finding.
- **9c2b9931d push run red too, smokes (areas-parity), other checks:** „the route's areas: the sign beside the map – a ring
  and one percent ({w:0,h:0,inMap:false})“, „its percent moves on, never back (-1 …)“, „no window and no strip line for
  them“. Areas smokes were green on 8a04b05cf; since then only donald's 7ed35111c (far start warning), 8856a6ecc /
  6a246bc9b (voice off route) and parity stamps. Suspect real: bisect areas-smoke between 8a04b05cf and 9c2b9931d first.
  **No deploy until green.**
- **Open: areas-smoke.mjs:170 „polling: the doorman's 40 % moves the bar (from 94 %)“** (run 44924598176) – on a slow
  runner the area wait's bar was already at 94 % before the doorman's answer; likely a test timing assumption, not proven.
  Investigate (don't just rerun).
- Navigation's OffRouteSay / RouteSide registered (57b0c7ca0); donald's batch may bring more new shared files → `--update`.
- Detour stitch: yes to umbau's nine points (backlog); web part when umbau's core is ready (captain starts it).

## State 2026-11-21 ~17:30 – the next laptop session starts HERE
- Since ~01:00: robust-smoke step 10 race fixed (083dcc17b – the link's first map wait must end before the shares are
  cleared); day rows variant A in the planner (df0cf81eb, WebDays + multiday.ts; picture in
  onpath-data/pictures/day-rows-planner-2026-11-21/); hotel „ab / bis …“ at a day's only known end (24809e72f + smoke
  f18cffd8b); donald's eye-on-suggestion parity stamped. Deploy line under „Waiting for the owner“ (move it to the newest
  green sha; the owner deploys or gives the word – `gh workflow run` is blocked here).
- Detour stitch: proposal `docs/design/detour-stitch.md` + owner's 3 answers; umbau's answer
  `items/2026-11-21-detour-stitch-answer.md` (9 points: ExpandedPlan for the 5 places outside Planner, followAhead,
  SameRouteTest before the reader release, hash over the line only, cut legs never in normal plans). Split: core + state
  umbau, app donald, web laptop. **No code until the captain starts the block.**
- Lesson: never chain a push after a smoke whose rc you don't check (24809e72f went out with a stale smoke expectation).
- Captain session: session_01167zC4wv2gKUDADAAUNFEH; umbau: session_01167zC68cVGgexER3YQGGWG (latest).

## State 2026-11-21 ~01:00 – the next laptop session starts HERE
Planner block **closed** (owner 2026-11-21 „Block abschließen“: only logic mirrors count; look / style / browser stay).
On main, **not deployed** (deploy needs the owner – Actions → Web planner → Run workflow deploy+quick, or his word; the
auto-mode classifier blocks `gh workflow run` here): line under „Waiting for the owner“ with the sha.
- Shared code now in the planner: PlanEditState (WebPlanChain in the routing worker on WebApi's history; edit-order
  cancel + robust-smoke (d)), PlaceAddState / PlaceMenusState (WebPlaceAdd, HoldMeasure measured in the worker),
  SearchState (WebSearch), PlanRecalcState route + findReach + the run head (RouteRun; askLong = !multiDay, run memory +
  epoch in historyExport, page-session „Plan anyway“ set → RoutedBefore), TrackToPlanState (WebTrackToPlan), core
  SearchLines / RoundTrip, RouteWait.legLabels. Mirrors 49 entries.
- Stay mirrors on purpose: Save (async IndexedDB, planner-only steps), wait window (one window map+route), list rules,
  ProfileRules / CellCards / ThumbRules / MapCredits / DEBOUNCE_MS / CellMath / Format … (needed synchronously on the page).
- Owner's word on „Ganz neu berechnen“: last row of the ▾ beside „Speichern“ (app: plan menu), shown only while the
  plan holds stitched detours (picture: onpath-data/pictures/ganz-neu-berechnen-2026-11-21/).
- **Next:** long-tour speed-ups 2–3 (`items/2026-11-20-add-point-speed.md`): design the stitch format (detour = field of
  the point), propose it in the backlog to [umbau] / [donald] before code, then app + planner together.
- Smokes: call each exactly as `web/parity/check-web.sh` does (segments folder only, except search/kept/poipin/routes) –
  a second argument breaks some of them silently. routes-smoke „a real scroll … closes the menu“ and poipin (d) are flaky.
- umbau's current session: session_01167zC6FhPGtEQkbxks3vH9 (messages work both ways).

## State 2026-11-20 ~14:30 – the next laptop session starts HERE
Planner live 6dcdfbb54 (CI green). 0.18.2 released 10:01 (planner deployed by release.yml, then the slices branch merged).
- Since the release: 55 mirrors (RoutesState, SaveDialog, KeptPlacesSlice, RouteSort / RoutesTab, PlanPoints drag,
  PinMenuPlace, LongRouteCancelCamera … on the page's second Wasm, `WebState.kt` / `slices.ts`); no click measure on
  legs > 30 km; green routes from z10 (`web/app/src/routezooms.ts`, owner: planner zooms may differ); the eye hides a
  suggestion's loop; night markers „Ende Tag n von N“, „Ein Tag weniger“ in a grey moon's menu; a POI point is plain.
- Earlier the same night: honest time left (no number without a measurement), place filter + instant chip, a night on
  the line routes nothing (`WebLegSplit.kt`), browser icon, 7-63-42 fixed (`cells.py bbox` plain decimals).
- Owner's decisions: stitching after the release as a field of the point (`docs/team/items/2026-11-20-add-point-speed.md`);
  Zuletzt keeps updating its entry in place; day rows „Tag 1 · km · ↑“ (mehrtage, later).
- Next: the planner-on-shared-code block with umbau (`docs/team/items/2026-11-20-after-the-release.md`), then the
  long-route speed-ups 2–3. Tracking owns `web/app/src/live/`, `web/host/src/live.js` – one writer at a time in web/.
- Rules learnt: check the gate's own exit code before a push (memory); backlog cap 40 KB – own lines short.

## State 2026-11-19 ~17:00 – the next laptop session starts HERE
Live and CI green (7b7fa63f): the app's progress design in the planner – form A `loadoverlay.ts` + `waitengine.ts` (a second
Wasm on the page runs the shared `WaitProgress` via `WebWait.kt`), form B `besidesign.ts`, learned shares `progressmemory.ts`,
map notes step back while the window is up, a failed map ends its wait (`LoadOverlay.fail`); pictures for the owner in
onpath.private `marketing/bilder/vorschau/planer-warten-2026-11-19/`. Zuhause off, Undo / Redo in the full points list.
umbau step 2 reviewed (DataFiles stand-in out, rule test „kotlin.“), answers to its phase-2 questions in the backlog.
Open: the sync race (umbau's find) once `SyncedRoutingSlice` settles in `:state`; poipin-smoke (d) and search-smoke's
POI click after a pan are flaky (timing).

## State 2026-11-19 ~10:00 – the next laptop session starts HERE
All live on plan.onpath.earth, CI green (push run 15d9f123):
- mehrtage's multi-day fixes + „N Tage ·“ on Meine Touren rows (the one gap of the 7-file mirror review – done).
- 98 % fix (c11e4e72): the card drops the percent while only the calculation is left – km + clock, running bar, after 10 s
  `plan_from_track_busy_text` (owner never chose another line – ask if he wants one). Smokes made CI-robust (f0bae480).
- Climb fix (owner's yes, shared `Planner.kt`, 32cd3d70): top ↑/↓ from the joined line, one 10 m filter = the days. **The app
  needs a version for it** (donald, owner's word).
- Leg cache (owner's go): IndexedDB, `WebLegStore` on the app's `LegStore`, ≤ 3000 legs / 40 MB; legcache-smoke.
- München stack overflow (fd9f58ed): BRouter's recursive `OsmNodesMap.minVisitIdInSubtree` replaced in the web build by an
  iterative copy (`web/engine/src/main/java/btools/mapaccess/OsmNodesMap.java`); deep-smoke (local list only – CI needs a
  web.yml PR). The phone keeps the recursive original (BRouter catches the overflow there and stops the cleanup halfway).
- Website pictures B01–B04, B07, C01, C02, C06, new B11–B14 in onpath.private `aktuell/` (line to cloud-web).
Open: poipin-smoke „(d) POI name beside its icon at z17+“ flaky (2/3 red locally); umbau phase 2 (piece D „for the laptop's
look“ – see the backlog); NoStaleDownloadsTest red on main (mehrtage / umbau).

## State 2026-11-18 ~12:00 – the next laptop session starts HERE
- **World overview fixed and live (2e15413f)**: the host ignores Range for `overview/*.pmtiles` → the planner reads the
  whole file once (`overview.ts` WholeOrRange). Done.
- **98 % load card – NOT on main yet.** The fix is commit `ad0b53ef` (branch `worktree-agent-a09c15482cdbee632`; my merge
  attempt is local branch `laptop-loadcard-merge`). Merging it with mehrtage's fe2b6d33 (worker progress now JSON with
  km: doneM / totalM, „the wait box shows k…“) conflicts in `worker.ts`; after resolving, robust-smoke part 9 fails
  (section counter / running bar / calm line) and multiday-smoke failed („waited for a blue moon“ – check whether it is
  red on origin/main alone first). Redo the merge on top of mehrtage's km progress: likely show km instead of legs.
  The owner must still choose the calm line (borrowed `plan_from_track_busy_text` „Bei einer langen Tour kann das ein
  paar Minuten dauern.“ or `plan_slow_title`, or a new text).
- **Mirror review owed** (backlog item): 7 app files marked checked unreviewed to deploy the overview fix.

## In flight 2026-11-18 morning (owner's field reports)
- **Long route stuck at 98 %** (Neuss → München): agent worktree `agent-a09c15482cdbee632` – the load card's calculation
  phase gets honest progress (leg counter / moving bar, no frozen number). Announced in the backlog (loadoverlay.ts).
- **Zoomed out = plain green over Germany** (photo): agent worktree `agent-a417a65e42caf77ed` – suspect the detail-cover
  hides the overview where no z7 region package / low zoom exists after the schema-9 rebuild; fix in the planner or a
  build order for donald (packages). The overview file itself is served fine.
- **Deployed 62741bff** with multi-day + round trips; CI sheet-smoke red on the header bar („Plan a multi-day tour“) –
  handed to mehrtage. TeaVM now out of process 6 GB (`web/engine/build.gradle.kts`).
If these agents are gone: redo from the descriptions above; merge → `node web/parity/check.mjs` → check-web → push →
`gh workflow run web.yml --ref main -f deploy=true -f quick=true`.

## Work in flight at the end of this session (now done – see above) (two agents, worktrees under `.claude/worktrees/`)
1. **Hold rule on real paths + one-row place menu + WebPlaceMenuTest** (donald's words 2026-11-17 in the backlog:
   `pathDetours` with leg lengths, `planningHold` simplified (≤ 1.2 → ASK), `planningPlaceMenu(lineDp, d)`, pin menu
   with ONE add row, names from z17 = no code change). Worktree `agent-a0ec544b55a26bdcc`, branch
   `worktree-agent-a0ec544b55a26bdcc` – no commit of its own yet when this was written. If the agent is gone: redo from
   donald's backlog lines (they hold exact code hints).
2. **Suggest layer s9 rebase onto main (AIMS, content 2)** – worktree `agent-aec6f4aef384aee8d`, branch
   `laptop-suggest-layer-rebase` (64f1aa4d schema 9 layer, 5f67799b docs). Still to do there: rebuild the 7 test
   cells with content 2 in `onpath-data/store-test-routes`, acceptance (`SUGGEST_FILE=1 SUGGEST_FILES=… cases.py`,
   `official.py compare --name Traumpfad`), the publish plan for the owner's test areas. Pushing it to main deploys
   the TEST doorman with CELL_SCHEMA 9 (owner said yes: „Normale Kartenschicht“, phones reload once – donald also
   builds the app's „reuse unchanged files“ fix).

## Main's Web planner run is RED until (1) lands
`WebPlaceMenuTest.besideTheGoalAsksTheCheaperFirst` (old rule) + parity: new shared files (PlanDays, Aims,
SuggestText, strings_suggest, PlanCodeDaysTest, PlanDaysTest, PlanMarksFollowTest) and the MapOverlays / AppViewModel
mirrors. After merging (1): `node web/parity/check.mjs --update` (bare – registers the new shared files; only once
every mirror error is really handled), full `web/parity/check-web.sh <segments> <store>`, push, deploy.

## Rules learnt today (also in memory)
- No Fragenregister any more; answer in the chat only. A word for the captain goes under „For the captain“ – never
  ask the owner to relay. Before touching `web/app` / `WebApi` write a line in the backlog (several sessions now:
  donald, IR, mehrtage, laptop, cloud-web).
- Multi-day tours are mehrtage's; the laptop owns `web/` and merges in order.

## Data on this laptop
Segments `/home/development/Projects/work/onpath-data/segments`, cell store `…/onpath-data/store`, test cells
`…/onpath-data/store-test-routes` (s8 routelines + s9 suggest test versions), venv with pyosmium
`…/onpath-data/test-routes-venv`.
