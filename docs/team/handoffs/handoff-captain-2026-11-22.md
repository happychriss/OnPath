# Handoff [captain] – Sunday 2026-11-22 ~21:00 (weekend mode)

Start sentence: „Du bist mein OnPath-Captain im Wochenend-Modus. Lies `CLAUDE.md`, `docs/team/roles/captain.md`,
`docs/team/owner-preferences.md` (Night jobs, Tokens), `docs/team/items/2026-11-20-after-the-release.md` und diese Datei.“

## RESTART after the shutdown of Sunday 2026-11-22 ~23:15 – READ THIS FIRST
The owner shut down donald and hp. **Every session id in this file is dead.** The state below is what the sessions left.

**Start again, in this order**
1. The owner starts donald and hp and opens a captain session with the start sentence at the top of this file.
2. The captain pulls, checks CI and asks him the open questions (list at the end), one short plain-text list.
3. The captain starts only the roles that have a next step, on Opus, each from its handoff (top section):
   - **donald:** `docs/team/roles/start.md` + `handoffs/handoff-donald-2026-11-21.md`
   - **laptop:** `docs/team/roles/start.md` + `cloud-plan.md` + `handoffs/handoff-laptop-2026-11-17.md`
   - **umbau:** only for the writers stage, `handoffs/handoff-umbau-2026-11-19.md`
   - **tracking:** only after pull requests 17 + 19, `handoffs/handoff-tracking-2026-11-20.md`

**Work in order**
1. POI symbols: does 0.18.9 answer in his hand? If not: his export, `[poi] Press …` with `frames=` and `end=`.
2. Long plan links (live bug): `wip/laptop-2026-11-22-long-link`, on his yes to main and online. Then `wip/laptop-2026-11-22-pair-link` (needs its one review).
3. Detour hint: `wip/donald-2026-11-22-detour-hint`, merge after his yes to the picture.
4. Fast detours, readers: `wip/donald-2026-11-22-stitch-readers-all`. Before main: his word on „Zuletzt“, a second read of the last fixes, a full E2E, the long-link merge (`linkCode` is on both, conflict in `share-smoke.mjs`). Then one release, then writers, then speed-up 3.
5. The compass keeps the map repainting (cause unknown, battery): limiting the dot's updates only after he has seen it.
6. Then the old order: rebuild phase 3/4, routing port.

**Limits that end soon:** donald's standing word for 0.18.x runs out **Monday 2026-11-23**: after that a release needs his word again. The weekly token warning lasts until Tuesday 2026-11-24 03:00.

**On the disks:** worktrees survive the shutdown; `/tmp` does not (only logs; the results are written here and in the handoffs). 208 worktrees on donald, 109 of them with commits that are not on GitHub, nearly all old helper work; six hold uncommitted probes or old experiments. Nothing was pushed or deleted for them: tidying needs his word. On hp the laptop saved 37 otherwise unsaved pieces to `wip/laptop-2026-11-22-saved-work` (46e291aa4, list in `SAVED-WORK-2026-11-22.md`, **not for merging**). His diagnostics exports stay in `~/.claude/uploads/` (his location, never into git).

## The job
The owner tries the app and writes remarks, bugs and diagnostics exports to the captain; the captain passes each to the
right role. No product code. **His word 2026-11-22: „Feel free to reduce token usage but don't hold off anything.“**
Lean: Sonnet helpers, one big review per piece, no full rounds. The weekly token limit is at its warning level until
**Tuesday 2026-11-24 03:00**.

- **Check:** hourly while roles work, about every two hours while all wait (background `sleep`; pull, CI, `list_sessions`,
  `get_session` → `context_usage.used_tokens`, the only reliable context number). Fresh session at ~350k.
- **No question box in the captain's chat.** Roles also ask in plain text now: a box stops the role until he answers.
- **A role stuck on an outdated or long-open box:** `interrupt_session`, then one `send_message` with everything again
  (the interrupt cancels queued messages). The owner's answer to a publishing question cannot be given for him.
- **Publishing** needs his own word in the publishing role's chat. Donald's standing word for 0.18.x (gate + the change's
  tests) runs **until Monday 2026-11-23**. The laptop has no standing word: it asks in its own chat.

## Sessions (newest of each role)
| Role | Session | Computer | State at 21:00 |
|---|---|---|---|
| donald | `session_01167zC8mNt5zArXMqTEQxwK` | donald | detour hint (picture → his yes → merge), then the readers' app part |
| laptop | `session_01167zC7KDVXu8wFyGtzCU9i` | hp | web part of the readers on `wip/laptop-2026-11-22-stitch-readers` |
| umbau | `session_01167zC79oCuJxW51t7ggryz` | donald | done, stopped; a fresh one for the writers stage |
| tracking | `session_01167zC8ejXp1nW5gU4RUroB` | donald | waits for pull requests 17 + 19, then a real share from the Pixel |
| old laptop | `session_01167zC5Kw6CfkBWFrAV5QhY` | hp | stopped; the „Abbrechen“ question was asked in its chat |

Environments: donald `env_01J9Bt5em2qaQ7qKdXzuiDFs`, hp `env_01QJbBwr5875K3VDN8mSUzMn`.

## Where things are
- **App 0.18.9** out (f9bfa79d4), release run fully green, **planner deployed with it**. The live page was not
  machine-checked (HTTP 200 only).
- **The owner's POI bug** (a symbol does not answer): his two exports showed no map idle for 30+ s on a still map. Reproduced on
  the emulator with a moving compass value on the position dot. 0.18.9 answers a press after the next drawn frame instead of
  waiting for idle. **Not proven on his Pixel: he must confirm.** Asked: the same taps with the phone on a table against
  held in the hand. Exports land in `~/.claude/uploads/…`, hold his location, never go into git; delete unpacked copies.
- **Open from it:** why the map repaints while the compass moves (unknown, costs battery), limiting the dot's compass updates
  (he must see it first), no device test for a second press during the wait, the review fixes not re-reviewed, a map that draws
  nothing for 2.5 s can still turn a hold into a point.
- **Fast detours (detour stitch):** `SameRouteTest` **112/112 on JVM and Wasm** (2a47fffb8) against the data file in
  `core/src/test/resources/same-route/DATA.txt` (only on donald). Readers: core + state on `wip/umbau-2026-11-22-stitch-readers`;
  web and app parts in progress. **Nothing of it on main until the captain says so** (a released reader freezes the format;
  check: `git grep ExpandedPlan origin/main` is empty). Before main: a second read of `git diff 248482631 cd9777cfd`. Then one
  release with readers → writers with the idle wiring → speed-up 3.
- **To run the exact Wasm check on donald:** a helper, own worktree, `tools/dev/gradle_slot.sh -- ./gradlew -q -p web/engine
  generateWasmGC copyWasmGCRuntime`, then from `web/app`, always capped (an uncapped run filled the owner's memory):
  `systemd-run --user --scope -q -p MemoryMax=6G -p MemorySwapMax=0 -- env OMP_NUM_THREADS=1 nice -n 19 ionice -c3 node
  --max-old-space-size=4096 test/same-route-wasm.mjs /home/development/onpath-routing/segments`.

## Update Sunday ~22:30 (newest)
- **Readers together, not on main:** `wip/donald-2026-11-22-stitch-readers-all` = ac64075d3 (core + state + app + web). Gate full and Web planner green; exact Wasm check **112/112** (peak 3.18 GB); `TourDbMigrationTest`, `CoreBackupE2ETest`, `CorePlanE2ETest`, `CorePlanLinkE2ETest` green one commit before (only `web/parity.json` differs). Not checked: Pixel, a stitched plan in the app, the other E2E classes, the last review fixes.
- **Before the readers go to main:** the owner's word on „Zuletzt“ (it also changes plans without anchors), a second read of the last fixes, the full E2E once.
- **Separate, waiting:** `wip/laptop-2026-11-22-long-link` (f59badf40, a bug in the live planner: waits for his yes in the newest „claude-plan“ chat; merging it onto the readers conflicts in `share-smoke.mjs`, and `linkCode` exists on both) · `wip/laptop-2026-11-22-pair-link` (af0aef505, after the long link, no own review yet) · `wip/donald-2026-11-22-detour-hint` (31a123de7, waits for his yes to the picture).
- donald `session_01167zC8mNt5zArXMqTEQxwK` and laptop `session_01167zC7KDVXu8wFyGtzCU9i` have stopped and wait; both handoffs are on main.

## Things that bit us
- `release.yml` publishes the app **before** its planner check ends. Green on the commit before is not green on the release.
- An app change to a mirrored or new shared file turns „Web planner“ red at parity, and the smokes then do not run at all.
- Planner smokes went red four times on timing in two days; one of them was a real bug. Never just rerun; find the cause.
- Read a role's „green“ against the run yourself. Read a changed test's diff: is the check weaker?

## Waiting for the owner
- Does the symbol answer now in 0.18.9 (table / in the hand)?
- „Abbrechen“ in the planner's wait window: like the app (window closes, area loads on)? Recommended: yes.
- A replaced plan under „Zuletzt“ keeps its own activity and level (on the readers branch only)? Recommended: yes.
- The detour hint's line on his Mutkopf tour: picture comes from donald.
- Run the planner check before publishing the app (a pull request for `release.yml`)? Recommended: yes.
- Pull requests 15–19 · „Secret löschen ja“ in a donald chat · old `ci/…` branches and merged helper worktrees need his word.
