# Handoff [captain] – Sunday 2026-11-22 ~21:00 (weekend mode)

Start sentence: „Du bist mein OnPath-Captain im Wochenend-Modus. Lies `CLAUDE.md`, `docs/team/roles/captain.md`,
`docs/team/owner-preferences.md` (Night jobs, Tokens), `docs/team/items/2026-11-20-after-the-release.md` und diese Datei.“

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
