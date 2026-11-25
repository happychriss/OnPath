# Cleanup inventory – laptop (hp), 2026-11-24 [laptop]

Read only; nothing deleted.

## How it was checked
- `git fetch --prune`; every worktree of this repo (88 besides the main checkout: 80 in `.claude/worktrees/`, plus `onpath-stitch`, plus one detached `/tmp/pcheck` that is prunable), every local branch (151; 9 without a worktree are listed as their own items), the one stash. Remote branches are not items: only the upstream of a local branch is shown (almost all track `origin/main`, i.e. nothing of their own).
- A commit counts as saved when its sha is on any origin branch (incl. `origin/wip/laptop-2026-11-22-saved-work` 46e291aa4, whose 37 parents are the former unsaved pieces) **or** `git cherry origin/main` finds its patch on main **or** its `git patch-id --stable` equals a commit on an origin branch (since 2026-09-15). Merge commits not counted as own work. Uncommitted: `git status --porcelain` incl. untracked (an untracked `web/app/node_modules` alone is ignored).
- (D) = main checkout, `main`, any `wip/laptop-*` branch/worktree, worktrees changed in the last 24 h (dir/.git mtime), worktrees with a process in it (`lsof +D`, `/proc/*/cwd`: none found), branch names found in backlog In progress / Waiting / Later-free text or the top of `handoff-laptop-2026-11-17.md`.
- Note: the main checkout is behind origin/main by 3 and has an uncommitted `web/app/test/areas-smoke.mjs` (the flaky-smokes claim of another session) – untouched.
- 10 items in (A) are saved ONLY because `saved-work` has their commits as parents (marked below): `saved-work` must stay, or they move to (B).

## Counts
| Group | Worktrees | Local branches without worktree | Stashes |
|---|---|---|---|
| (A) kann weg | 80 | 62 | 0 |
| (B) aufheben | 0 | 0 | 0 |
| (C) unklar | 3 | 0 | 1 |
| (D) aktiv – nie anfassen | 6 | 2 | 0 |
| total | 89 | 64 | 1 |

## (B) aufheben
- none – every own commit is on some origin branch or its patch is on main (via `saved-work` for 10 of them).

## (C) unklar
- `wt/agent-abc400503a5e07cb3` [worktree-agent-abc400503a5e07cb3] 2026-11-19 – uncommitted: 1 entries · ?? web/engine/build · „Planner wait window: a failed / slow / sto“
- `wt/agent-ae0ae0f0208bc8363` [worktree-agent-ae0ae0f0208bc8363] 2026-11-17 – uncommitted: 2 entries · M tools/routing/jvm-test/run.sh; ?? tools/multiday/ · „Merge branch 'main' of https://github.com/“
- `wt/umbau-hp` [umbau-hp-work] 2026-11-20 – uncommitted: 1 entries · ?? tools/spike-routing-kmp/ · „Backlog: release hardening done – the C13 “
- stash stash@{0} – 2026-11-13 – On worktree-agent-a5188c3a1cb765678: laptop-a5188-mine · own work, not patch-checked

## (D) aktiv – nie anfassen
- `onpath-plan` [main] 2026-11-24 – main checkout · „Tracking night: claim, two night answers (“
- `wt/agent-a624f28741e12a0c6` [wip/laptop-2026-11-20-slices] 2026-11-20 – wip/laptop-* or main · „Planner: the found place's menu from the a“
- `wt/laptop-long-link` [wip/laptop-2026-11-22-long-link] 2026-11-22 – wip/laptop-* or main · „Planner: a long plan link opens on a fresh“
- `wt/laptop-pair-link` [wip/laptop-2026-11-22-pair-link] 2026-11-22 – wip/laptop-* or main · „A long made-up pairing link is plainly no “
- `wt/laptop-stitch-readers` [wip/laptop-2026-11-22-stitch-readers] 2026-11-22 – wip/laptop-* or main; named in backlog/handoff · „Planner reader stage: state and what stays“
- `onpath-stitch` [wip/laptop-2026-11-22-stitch-wasm] 2026-11-22 – wip/laptop-* or main · „same-route-wasm: stays under 3 GB on Node “
- `main` [no worktree] (tracks origin/main) 2026-11-24 – wip/laptop-* or main · „Backlog [laptop]: the flaky web smokes are“
- `wip/laptop-2026-11-22-saved-work` [no worktree] 2026-11-22 – wip/laptop-* or main · „Saved work of all laptop worktrees at the “

## (A) kann weg
- `wt/agent-a036f14f318ee6775` [worktree-agent-a036f14f318ee6775] 2026-11-19 – all commits saved, clean · „Planner: My routes and Save's dialog run t“
- `wt/agent-a071c6093d4618358` [worktree-agent-a071c6093d4618358] 2026-11-19 – all commits saved, clean · saved ONLY via saved-work branch · „Planner: plans near München no longer fail“
- `wt/agent-a09c15482cdbee632` [worktree-agent-a09c15482cdbee632] 2026-11-18 – all commits saved, clean · saved ONLY via saved-work branch · „Planner load card: the route calculation n“
- `wt/agent-a0ec544b55a26bdcc` [worktree-agent-a0ec544b55a26bdcc] 2026-11-17 – all commits saved, clean · „Planner: hold beside the plan on real path“
- `wt/agent-a131b32526d21ea3d` [worktree-agent-a131b32526d21ea3d] 2026-11-19 – all commits saved, clean · saved ONLY via saved-work branch · „Planner follows the app: „Zuhause“ off, Un“
- `wt/agent-a179bb424711af00a` [laptop-dayrows] 2026-11-21 – all commits saved, clean · „Planner day rows (owner's variant A, like “
- `wt/agent-a19fa9011f3d3864f` [worktree-agent-a19fa9011f3d3864f] 2026-11-20 – all commits saved, clean · „Planner engine: WebPlanChain – the app's P“
- `wt/agent-a245587f82617ab79` [worktree-agent-a245587f82617ab79] 2026-11-13 – all commits saved, clean · „Sync: pull to refresh on Meine Touren → Ge“
- `wt/agent-a249f4a684e7b0e10` [worktree-agent-a249f4a684e7b0e10] 2026-11-18 – all commits saved, clean · „Plan totals: ascent / descent from the joi“
- `wt/agent-a260bba713b924b0d` [worktree-agent-a260bba713b924b0d] 2026-11-20 – all commits saved, clean · „Planner engine: the Compose stand-in gets “
- `wt/agent-a30c325bee8d24d79` [work-fixmerge] 2026-11-17 – all commits saved, clean · „Planner: clicks on the map are decided in “
- `wt/agent-a33411a9a1276506a` [fork-plan] 2026-11-05 – all commits saved, clean · „Backlog/status [laptop]: planner follow-up“
- `wt/agent-a3bcca615faa7b37a` [worktree-agent-a3bcca615faa7b37a] 2026-11-19 – all commits saved, clean · saved ONLY via saved-work branch · „Measurement: where one „add a point“ goes “
- `wt/agent-a3c2a37ef1bc43ff8` [worktree-agent-a3c2a37ef1bc43ff8] 2026-11-20 – all commits saved, clean · „Planner wait window: no time for map tiles“
- `wt/agent-a3eadf10f5e7c12fd` [worktree-agent-a3eadf10f5e7c12fd] 2026-11-12 – all commits saved, clean · „Planner undo pill: steps back on a phone w“
- `wt/agent-a401706ef5d07f30b` [worktree-agent-a401706ef5d07f30b] 2026-11-19 – all commits saved, clean · „Planner wait window: a map that cannot be “
- `wt/agent-a417a65e42caf77ed` [worktree-agent-a417a65e42caf77ed] 2026-11-18 – all commits saved, clean · „Planner: the world overview draws on a hos“
- `wt/agent-a446d89f6450499fa` [worktree-agent-a446d89f6450499fa] 2026-11-19 – all commits saved, clean · „Planner: a night (or any point) put in ON “
- `wt/agent-a454934ce85345499` [worktree-agent-a454934ce85345499] 2026-11-12 – all commits saved, clean · „Sync: a reinstalled phone never gets its o“
- `wt/agent-a454a6e44f0405635` [worktree-agent-a454a6e44f0405635] 2026-11-20 – all commits saved, clean · „Planner: signed routes further out than on“
- `wt/agent-a4710bde217afcb6e` [work-a4710] 2026-11-14 – all commits saved, clean · „search-smoke: the Köln density check waits“
- `wt/agent-a47e422c5b9364368` [laptop-rests] 2026-11-21 – all commits saved, clean · „Planner: stale comments fixed (SearchState“
- `wt/agent-a48ea99949170620b` [worktree-agent-a48ea99949170620b] 2026-11-20 – all commits saved, clean · „Planner: the eye also hides a round-trip s“
- `wt/agent-a5188c3a1cb765678` [worktree-agent-a5188c3a1cb765678] 2026-11-14 – all commits saved, clean · „stop-smoke: ask "undo still works in the f“
- `wt/agent-a53b26c6702a1372b` [worktree-agent-a53b26c6702a1372b] 2026-11-15 – all commits saved, clean · „Planner: unsaved = the points only, as the“
- `wt/agent-a587b0b026edf8f73` [laptop-search-wait] 2026-11-20 – all commits saved, clean · „Planner wait window: the legs' names „A → “
- `wt/agent-a5ca1e361d42216c3` [worktree-agent-a5ca1e361d42216c3] 2026-11-13 – all commits saved, clean · „Planner: one wait at a time – one arbiter “
- `wt/agent-a63149949a8541430` [worktree-agent-a63149949a8541430] 2026-11-20 – all commits saved, clean · „Planner: kept places through KeptPlacesSli“
- `wt/agent-a6371ecbd59274e58` [worktree-agent-a6371ecbd59274e58] 2026-11-18 – all commits saved, clean · „Planner: Meine Touren rows of a multi-day “
- `wt/agent-a64075d221d5c5959` [worktree-agent-a64075d221d5c5959] 2026-11-13 – all commits saved, clean · „Planner: the overview's route picture stay“
- `wt/agent-a67ee6f5e5050fac5` [worktree-agent-a67ee6f5e5050fac5] 2026-11-19 – all commits saved, clean · „robust-smoke 9: the wait-box half waits fo“
- `wt/agent-a685f8364d18a6e74` [worktree-agent-a685f8364d18a6e74] 2026-11-13 – all commits saved, clean · „Web: OK after pairing never asks for the n“
- `wt/agent-a6e3f572baeed7f81` [fix-pinchip-credits] 2026-11-16 – all commits saved, clean · „Planner: the found place's pin and name ch“
- `wt/agent-a71304987ef37a59c` [worktree-agent-a71304987ef37a59c] 2026-11-20 – all commits saved, clean · „Planner: an add the shared code turns down“
- `wt/agent-a7902402704aa1b22` [laptop-routes] 2026-11-20 – all commits saved, clean · „Planner: „Als Plan übernehmen“ runs the ap“
- `wt/agent-a7bac4ace29b83b03` [worktree-agent-a7bac4ace29b83b03] 2026-11-20 – all commits saved, clean · „Planner: a grey moon between two set night“
- `wt/agent-a7bc40373981cca58` [worktree-agent-a7bc40373981cca58] 2026-11-15 – all commits saved, clean · „Planner: kept places – Zuhause and Favorit“
- `wt/agent-a8709fdfef22e70c5` [worktree-agent-a8709fdfef22e70c5] 2026-11-13 – all commits saved, clean · „Planner Zuletzt = the app's one-plan rule,“
- `wt/agent-a8bcbb9d3e3cd54c1` [worktree-agent-a8bcbb9d3e3cd54c1] 2026-11-15 – all commits saved, clean · „Map cells: every signed route of a way, be“
- `wt/agent-a937c33478c3342c7` [laptop-runhead-fix] 2026-11-20 – all commits saved, clean · „Planner run head – review fixes: „Weiter w“
- `wt/agent-a9a12f2213adc82d3` [worktree-agent-a9a12f2213adc82d3] 2026-11-17 – all commits saved, clean · „search-smoke: the POI to click must lie in“
- `wt/agent-a9f24b14a77f0dfb0` [worktree-agent-a9f24b14a77f0dfb0] 2026-11-13 – all commits saved, clean · „Planner: a shared link on a phone opens wi“
- `wt/agent-a9f8eb445fb76cdad` [laptop-runhead] 2026-11-20 – all commits saved, clean · „Planner: the route run's head is the app's“
- `wt/agent-aa238736fc1e27ca7` [laptop/suggest-layer-2026-11-17] 2026-11-17 – all commits saved, clean · „Tour suggestions §4a: the seven suggest-la“
- `wt/agent-aa2806b1e578c8ac8` [worktree-agent-aa2806b1e578c8ac8] 2026-11-17 – all commits saved, clean · „Planner: a plan run that waited while the “
- `wt/agent-aa2a21d44a59dbb2b` [work-hold] 2026-11-17 – all commits saved, clean · „Planner: the hold rule while planning (F-P“
- `wt/agent-aa8abda008a0006fe` [worktree-agent-aa8abda008a0006fe] 2026-11-12 – all commits saved, clean · „Planner: right after a pairing the waiting“
- `wt/agent-aaa99d5cdde42e484` [laptop-cancel-fixes] 2026-11-20 – all commits saved, clean · „Planner: Cancel of a run takes back in edi“
- `wt/agent-aaaf6f6806e2e7282` [laptop-recalc] 2026-11-20 – all commits saved, clean · saved ONLY via saved-work branch · „Planner engine: the plan run is the app's “
- `wt/agent-aabda5e9b5081e662` [worktree-agent-aabda5e9b5081e662] 2026-11-13 – all commits saved, clean · „Web parity with app 0.17.9: one plan under“
- `wt/agent-aae97785ecd5c7850` [worktree-agent-aae97785ecd5c7850] 2026-11-18 – all commits saved, clean · „Planner pictures: flows-shots takes B08–B1“
- `wt/agent-ab6f8fb779f995d12` [worktree-agent-ab6f8fb779f995d12] 2026-11-12 – all commits saved, clean · „Planner: moving tours between folders uses“
- `wt/agent-aba56b268d0fde7fe` [worktree-agent-aba56b268d0fde7fe] 2026-11-13 – all commits saved, clean · „Sync: pace PUTs and retry 429 / 5xx quietl“
- `wt/agent-ac0c9f11e4cb438d2` [terrain-fix] 2026-11-05 – all commits saved, clean · „Backlog [laptop]: terrain view fixes done “
- `wt/agent-ac109bff1bad22b7b` [worktree-agent-ac109bff1bad22b7b] 2026-11-13 – all commits saved, clean · „Planner: Meine Touren sorts and searches l“
- `wt/agent-ac4c9d44f40789749` [work-bin] 2026-11-15 – all commits saved, clean · „Planner bin: saved routes deleted without “
- `wt/agent-ac7f69a28cb9673c1` [work-placesync] 2026-11-17 – all commits saved, clean · „Planner: kept places (Zuhause + Favoriten)“
- `wt/agent-ac9f788aac1ed512f` [worktree-agent-ac9f788aac1ed512f] 2026-11-16 – all commits saved, clean · „Cells schema 8: every cell keeps every sig“
- `wt/agent-aca64d9b49f59a285` [worktree-agent-aca64d9b49f59a285] 2026-11-12 – all commits saved, clean · „Planner profile calmer, like the app's (ow“
- `wt/agent-aceb66043ad69dbb5` [worktree-agent-aceb66043ad69dbb5] 2026-11-19 – all commits saved, clean · „Region packages on the prime meridian / eq“
- `wt/agent-ad8c6b620c78efd80` [agent-slices-ad8] 2026-11-20 – all commits saved, clean · „Revert "Planner: a plan from Zuletzt left “
- `wt/agent-add14643baf5000f3` [worktree-agent-add14643baf5000f3] 2026-11-13 – all commits saved, clean · „Planner: no large route overview (owner 20“
- `wt/agent-adf5810e1174ad346` [worktree-agent-adf5810e1174ad346] 2026-11-12 – all commits saved, clean · „Planner: a stop pinned on a POI keeps its “
- `wt/agent-adf62ef9d3665a9a8` [worktree-agent-adf62ef9d3665a9a8] 2026-11-19 – all commits saved, clean · „Tests: Mehr page pause spans – edges, step“
- `wt/agent-ae0431f4815dcca98` [work] 2026-11-14 – all commits saved, clean · „Meine Touren: a row's ⋮ menu closes only w“
- `wt/agent-ae431458899d127d6` [worktree-agent-ae431458899d127d6] 2026-11-19 – all commits saved, clean · „Planner search: a chip tap shows at once –“
- `wt/agent-ae46089dc46197388` [worktree-agent-ae46089dc46197388] 2026-11-13 – all commits saved, clean · „Planner: the app's 0.17.9 look – palette D“
- `wt/agent-ae4ee0db2fc17ed11` [worktree-agent-ae4ee0db2fc17ed11] 2026-11-15 – all commits saved, clean · „Planner: place menu „Einfügen“ / „Neues Zi“
- `wt/agent-ae785c2ca056f9855` [worktree-agent-ae785c2ca056f9855] 2026-11-19 – all commits saved, clean · „Planner: a browser tab icon – the app's la“
- `wt/agent-ae7bb5443cfc08091` [worktree-agent-ae7bb5443cfc08091] 2026-11-20 – all commits saved, clean · saved ONLY via saved-work branch · „Map cells: a download never starts or inst“
- `wt/agent-ae9d1a7e989637a82` [worktree-agent-ae9d1a7e989637a82] 2026-11-19 – all commits saved, clean · „Planner: finished legs kept in the browser“
- `wt/agent-aec6f4aef384aee8d` [laptop-suggest-layer-rebase] 2026-11-17 – all commits saved, clean · „Suggest layer: owner accepted the one-time“
- `wt/agent-aee7534cdf38462af` [worktree-agent-aee7534cdf38462af] 2026-11-12 – all commits saved, clean · „Planner: "Pair again" with a reinstalled p“
- `wt/agent-aefc7953096bd0dd0` [terrain-view] 2026-11-05 – all commits saved, clean · „Backlog/status [laptop]: terrain view A + “
- `wt/agent-af14805be46f347c6` [worktree-agent-af14805be46f347c6] 2026-11-14 – all commits saved, clean · „Planner: „Make it the destination“ appends“
- `wt/agent-af705442b94f986c1` [worktree-agent-af705442b94f986c1] 2026-11-13 – all commits saved, clean · „Planner: the map of a new place loads in o“
- `wt/agent-af7bec6598f639e16` [worktree-agent-af7bec6598f639e16] 2026-11-17 – all commits saved, clean · „Planner: a click on a POI right after the “
- `wt/agent-affc18a8db32baa80` [worktree-agent-affc18a8db32baa80] 2026-11-12 – all commits saved, clean · „Planner: the activity list never covers it“
- `wt/umbau-hp-wait` [umbau-hp-wait] 2026-11-19 – all commits saved, clean · „Backlog: under its cap“
- `/tmp/pcheck` [None] 2026-11-20 – worktree dir missing (prunable) · „Backlog + handoff [umbau]: web-ready: Plan“
- `fetch-area` [no worktree] (tracks origin/main) 2026-11-06 – all commits saved, clean · „tools/dev/fetch_area.py: published map cel“
- `laptop-base-1113` [no worktree] 2026-11-13 – all commits saved, clean · „Merge donald's 1113 app work (favourites, “
- `laptop-base-1117` [no worktree] 2026-11-17 – all commits saved, clean · „Merge branch 'worktree-agent-af7bec6598f63“
- `laptop-loadcard-merge` [no worktree] 2026-11-18 – all commits saved, clean · saved ONLY via saved-work branch · „Merge: the load card's calculation phase m“
- `laptop-save` [no worktree] (tracks origin/main) 2026-11-20 – all commits saved, clean · „Backlog: laptop – planner ready to deploy “
- `laptop/ci-smokes-2026-11-13` [no worktree] 2026-11-14 – all commits saved, clean · saved ONLY via saved-work branch · „Web planner CI: the smokes check-web.sh ru“
- `planner-quick-deploy` [no worktree] (tracks origin/main) 2026-11-06 – all commits saved, clean · „Web planner: deploy + quick goes online ri“
- `umbau-hp-b2` [no worktree] 2026-11-19 – all commits saved, clean · saved ONLY via saved-work branch · „Phase 4b batch 2, review fixes: poiMenu at“
- `umbau-hp-b3` [no worktree] 2026-11-19 – all commits saved, clean · saved ONLY via saved-work branch · „Rebuild phase 4b, batch 3: message boxes i“
- `umbau-hp-h1` [no worktree] 2026-11-18 – all commits saved, clean · „Routing spike 5a: port brouter-util and br“
- `umbau-hp-h2` [no worktree] 2026-11-18 – all commits saved, clean · „Routing spike 5a: port btools.expressions “
- `umbau-hp-h3` [no worktree] 2026-11-18 – all commits saved, clean · „Routing spike 5a: port RoutingEngine, Rout“
- `umbau-hp-h4` [no worktree] 2026-11-18 – all commits saved, clean · „Routing spike 5a: port btools.router path/“
- `web-3d-hint` [no worktree] (tracks origin/main) 2026-11-06 – all commits saved, clean · „Web: the 3D note says how to turn and tilt“
- `web-closed-folder-ask` [no worktree] (tracks origin/web-spike) 2026-11-02 – all commits saved, clean · „Web: removing a closed phone folder asks w“
- `web-compass3d` [no worktree] (tracks origin/main) 2026-11-06 – all commits saved, clean · „Web: north up in 3D keeps the tilt – only “
- `web-engine-restart` [no worktree] (tracks origin/main) 2026-11-07 – all commits saved, clean · „Planner: a dropped long plan never holds t“
- `web-fit-air` [no worktree] (tracks origin/main) 2026-11-07 – all commits saved, clean · „Web: route fits keep a marker's radius und“
- `web-flows-shots` [no worktree] (tracks origin/main) 2026-11-07 – all commits saved, clean · „Backlog [laptop]: planner pictures Meran 0“
- `web-flyover` [no worktree] (tracks origin/main) 2026-11-06 – all commits saved, clean · „Backlog/status [laptop]: planner 3D flyove“
- `web-follow-fix` [no worktree] (tracks origin/main) 2026-11-07 – all commits saved, clean · „follow-smoke: the position in view before “
- `web-import-smoke` [no worktree] (tracks origin/main) 2026-11-06 – all commits saved, clean · „import-smoke 1c: the user takes the map fi“
- `web-meine-touren` [no worktree] (tracks origin/main) 2026-11-07 – all commits saved, clean · „Backlog/status [laptop]: Meine Touren like“
- `web-par4` [no worktree] (tracks origin/main) 2026-11-07 – all commits saved, clean · „Web parity: SettingsScreen privacy flows w“
- `web-parity-0176` [no worktree] (tracks origin/main) 2026-11-08 – all commits saved, clean · „Planner synced with v0.17.6: T chips keep “
- `web-parity-1106` [no worktree] (tracks origin/main) 2026-11-06 – all commits saved, clean · „Backlog [laptop]: two-quick-clicks smoke r“
- `web-parity-1107` [no worktree] (tracks origin/main) 2026-11-07 – all commits saved, clean · „Planner sheet in the phone's order (owner “
- `web-parity-corridor` [no worktree] (tracks origin/main) 2026-11-07 – all commits saved, clean · „Backlog [laptop]: planner parity round + o“
- `web-parity-pin` [no worktree] (tracks origin/main) 2026-11-07 – all commits saved, clean · „Web parity: CellStore generations cached, “
- `web-perf3d` [no worktree] (tracks origin/main) 2026-11-06 – all commits saved, clean · „Web: 3D + big profile smooth again – the c“
- `web-planreach` [no worktree] (tracks origin/main) 2026-11-07 – all commits saved, clean · „Backlog/status [laptop]: PlanReach in the “
- `web-planreach-parity` [no worktree] (tracks origin/main) 2026-11-07 – all commits saved, clean · „Web parity: Meine Touren rows' Planen / Lo“
- `web-profile-range` [no worktree] (tracks origin/main) 2026-11-06 – all commits saved, clean · „Web: mark a stretch in the big profile – l“
- `web-sheet-a` [no worktree] (tracks origin/main) 2026-11-06 – all commits saved, clean · „Web parity, second app round of 2026-11-06“
- `web-sheet-b` [no worktree] (tracks origin/main) 2026-11-07 – all commits saved, clean · „Planner: length · ↑ · ↓ first, the sheet o“
- `web-spike` [no worktree] (tracks origin/web-spike) 2026-11-06 – all commits saved, clean · „Web: flaky smokes – a plan link no longer “
- `web-two-clicks` [no worktree] (tracks origin/main) 2026-11-06 – all commits saved, clean · „Backlog [laptop]: two quick clicks solved “
- `worktree-agent-a179bb424711af00a` [no worktree] 2026-11-21 – all commits saved, clean · „robust-smoke 10: the wait checks start onl“
- `worktree-agent-a1a7c490002c2fb5d` [no worktree] 2026-11-02 – all commits saved, clean · „Backlog [donald]: claim E2E red, brouter U“
- `worktree-agent-a30c325bee8d24d79` [no worktree] 2026-11-17 – all commits saved, clean · „Handoff 2026-11-16: suggest layer on a bra“
- `worktree-agent-a33411a9a1276506a` [no worktree] 2026-11-05 – all commits saved, clean · „Backlog [laptop]: claim the planner follow“
- `worktree-agent-a4710bde217afcb6e` [no worktree] 2026-11-13 – all commits saved, clean · „Tour suggestions: the planner plan parked “
- `worktree-agent-a47e422c5b9364368` [no worktree] 2026-11-21 – all commits saved, clean · „Backlog: owner – close the planner block (“
- `worktree-agent-a587b0b026edf8f73` [no worktree] 2026-11-20 – all commits saved, clean · „Planner engine: the plan run is the app's “
- `worktree-agent-a624f28741e12a0c6` [no worktree] 2026-11-20 – all commits saved, clean · „Backlog: unit.yml green on the 0.18.2 cand“
- `worktree-agent-a6e3f572baeed7f81` [no worktree] 2026-11-16 – all commits saved, clean · „Backlog: donald claims the hold rule (POI “
- `worktree-agent-a7902402704aa1b22` [no worktree] 2026-11-20 – all commits saved, clean · „Backlog: laptop – planner ready to deploy “
- `worktree-agent-a937c33478c3342c7` [no worktree] 2026-11-20 – all commits saved, clean · „Release 0.18.6: the found place's menu bel“
- `worktree-agent-a9f8eb445fb76cdad` [no worktree] 2026-11-20 – all commits saved, clean · „Planner wait window: the legs' names „A → “
- `worktree-agent-aa238736fc1e27ca7` [no worktree] 2026-11-17 – all commits saved, clean · „Handoff donald 2026-11-16: hold rule, Ø sp“
- `worktree-agent-aa2a21d44a59dbb2b` [no worktree] 2026-11-17 – all commits saved, clean · „Handoff donald 2026-11-16: hold rule, Ø sp“
- `worktree-agent-aaa99d5cdde42e484` [no worktree] 2026-11-20 – all commits saved, clean · „Backlog + handoff [umbau]: web-ready: Trac“
- `worktree-agent-aaaf6f6806e2e7282` [no worktree] 2026-11-20 – all commits saved, clean · „Backlog: laptop – planner ready to deploy“
- `worktree-agent-ab7fb20ede7de8d28` [no worktree] 2026-11-24 – all commits saved, clean · „Planner detour hint: an edit stops the che“
- `worktree-agent-ac0c9f11e4cb438d2` [no worktree] 2026-11-05 – all commits saved, clean · „Backlog [donald]: next 2–3 stops under the“
- `worktree-agent-ac4c9d44f40789749` [no worktree] 2026-11-15 – all commits saved, clean · „Tour suggestions: official loops rank abov“
- `worktree-agent-ac7f69a28cb9673c1` [no worktree] 2026-11-17 – all commits saved, clean · „Handoff donald 2026-11-16: hold rule, Ø sp“
- `worktree-agent-ad8c6b620c78efd80` [no worktree] 2026-11-20 – all commits saved, clean · „Backlog: laptop's morning state – candidat“
- `worktree-agent-ada8d149a2e6f0cc1` [no worktree] 2026-11-20 – all commits saved, clean · „Backlog: laptop – planner ready to deploy “
- `worktree-agent-ae0431f4815dcca98` [no worktree] 2026-11-13 – all commits saved, clean · „Tour suggestions: the planner plan parked “
- `worktree-agent-aec6f4aef384aee8d` [no worktree] 2026-11-17 – all commits saved, clean · „Multi-day tours: maps ≈ 340 MB with a 10 k“
- `worktree-agent-aefc7953096bd0dd0` [no worktree] 2026-11-05 – all commits saved, clean · „Backlog [donald]: Navigate – next 2–3 stop“

## Big non-git folders (sizes only – the owner decides)
- `.claude/worktrees` 12G in total (86 dirs; the biggest are the three active wip ones at 290–430 MB each; `onpath-stitch` 246M)
- `onpath-data/`: store 1.5G · store-test-routes 731M · segments 365M · test-suggest-seg 175M · test-routes-venv 105M · test-routes-suggest 46M · film 15M · multiday 2.4M · pictures 1.9M · test-routes-work 436K · nrw.log 4K
- main checkout `onpath-plan` without `.claude`: 1.1G; disk /home: 163G used of 466G (37 %).

## Owner's yes 2026-11-25 (captain's relay) → the owner's script
The session's permission check blocks the deletion, so `~/aufraeumen-laptop-2026-11-25.sh` does it when the owner runs it: every (A) worktree re-checked just before (no changes, no process inside – else skipped), `git worktree remove` without force; 61 local branches with `git branch -D` (each skipped when a worktree uses it). **`web-spike` is taken out of (A)** – the frozen planner branch, never touch. Log: `~/aufraeumen-laptop-2026-11-25.log`.
