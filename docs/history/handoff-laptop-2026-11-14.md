# Handoff laptop – 2026-11-13 / 14 (day and night)

For donald and the captain: what the laptop changed in the planner (plan.onpath.earth) and the shared sync code, what is open.
Everything below is on main and deployed by hand (`web.yml` deploy quick), unless marked otherwise.

## 1. Sync (phone ⇄ browser)
- **Re-pairing rate limit (owner's Pixel, night 11-12):** each device writes ≤ 30 items per round, the rest 60 s later;
  429 / 5xx = quiet retry (retry-after, else 30 s doubling, ≤ 10 min); host answers 503 + retry-after instead of a thrown
  500; SYNC_RATE 300/min. Shared `SyncRound`, `PlanSync.paceNext`, `HttpMailbox`, `StuckOutbox`; phone part shipped in 0.17.10.
- **OK after pairing never asks for the name of a saved plan** (two races fixed, sync-smoke scenario 1).
- **Phone: pull to refresh on Meine Touren → Geplant, poll every 15 s** (was 60 s) – 94127390, in 0.17.10.

## 2. Planner – following the app (0.17.9 / 0.17.10)
- Sort chips + search like the app; 0.17.9 look (palette D, sage + float shadow over the map); Zuletzt = one plan with
  the app's rules, a browser recycle bin for drafts (30 days); the goal row on a phone no longer hidden.
- 0.17.10: favourite star (field 9/10, synced, no copy) + Favoriten pill, map pictures per row (`routethumbs.ts`,
  off-screen MapLibre, 8 MB memory / 4 MB IndexedDB), activity filter, „· ↻ Rundtour“, search pick → hold menu
  (`pinmenu.ts`), long-route Cancel → back to the plan, POI `dz` density (`poidensity.ts`), „Make it the destination“
  appends (engine `placeMenu.keepOld` from `PlaceAddMenu.keepsOldGoal`).
- **Owner decision:** the planner has NO large route overview – a row or its tile opens the plan.

## 3. Planner – owner's field reports
- Row tap opens the plan at once (no empty white overview); undo pill side by side, beside the search bar on wide
  windows; no undo after opening a plan; shared link on a phone: sheet collapsed, the route visible; one load card with a
  percent; **one wait at a time** (`onewait.ts` arbiter); dialogs centred (overview picture redraw); POI badge round again
  (`.badge` padding collision); „·“ before Rundtour (`.dot` collision).
- Flaky smokes fixed – two were real page bugs: `poipin.ts` read a GPU canvas (froze software-GL pages 13–28 s);
  `routereveal.ts` started its clock before the first frame (the reveal jumped).

## 4. Open
- **Owner:** the CI-smokes change for `.github/workflows/web.yml` waits as `tools/ci/pending/web-yml-ci-smokes-2026-11-13.patch`
  (the laptop's token cannot push workflow files) – `git am` it on a branch, open the PR.
- **Owner to decide (backlog „Zuletzt follow-ups“):** bin „Jetzt löschen“, bin for saved routes, bin button always,
  same-points check, activity change counted as unsaved (align to the app: points only).
- Map pictures on weak phone browsers are unmeasured (software GL blocked 5–7 s); round trip + „Make it the
  destination“ appends after the closing point (as the app) – untested.
- Branches to prune after the owner's yes: `wip/laptop-2026-11-13-sync-pace`, `wip/laptop-2026-11-14-search-menu`,
  `wip/laptop-2026-11-14-favourites`, local `laptop-base-1113`, `laptop/ci-smokes-2026-11-13`.
- A stash entry „laptop-a5188-mine“ (6abbcd6f) is left in the shared stash list (applied; drop by hand).
