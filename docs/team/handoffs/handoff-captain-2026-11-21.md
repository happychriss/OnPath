# Handoff [captain] – Saturday 2026-11-21 ~10:30 (weekend mode)

Start sentence: „Du bist mein OnPath-Captain im Wochenend-Modus. Lies `CLAUDE.md`, `docs/team/roles/captain.md`,
`docs/team/owner-preferences.md` (Night jobs, Tokens), `docs/team/items/2026-11-20-after-the-release.md` und diese Datei.“

**Taken over by `session_01167zC4wv2gKUDADAAUNFEH` (claude-captain), Sa ~10:55.**

## The job this weekend
The owner tries the app himself and writes remarks and bugs to the captain; the captain passes each to the right role.
Work goes on one block after the other, to the end. The captain writes no product code.

- **Check:** hourly while roles work, about every two hours while all of them wait for the owner (background `sleep`;
  pull, „For the captain“, `list_sessions`, `context_usage`). Wake a stopped role with its next step; start a fresh
  session on Opus at ~350k context, from the role's handoff.
- **Never open a question box while running the loop** – one box stopped all checks for a night (2026-11-20). Ask in
  plain text, start the next wait first.
- **Sessions do not wake on a backlog line** – a role that waits for another needs a `send_message`.
- **Publishing and branch deletion** need the owner's own word in the chat of the session that does it. Donald has his
  standing word for 0.18.x updates until Monday (gate + the change's tests). The laptop has none yet for deploys; each
  app release run deploys the planner too.

## Sessions (newest of each role)
| Role | Session | Computer | State at 10:30 |
|---|---|---|---|
| donald | `session_01167zC7QqLNJtmKMiqG1hvy` | donald | waits for 3 answers: far start, T1 detour, off-route sentence |
| umbau | `session_01167zC6FhPGtEQkbxks3vH9` | donald | waits for the day-row picture (A recommended) |
| laptop | `session_01167zC7LQg1C8EKQypPNApf` | hp | waits for question 1 of 3 on the detour stitch |
| tracking | `session_01167zC8ejXp1nW5gU4RUroB` | donald | waits for pull requests 17 + 19, then a real share from the Pixel |
| umbau-hp | `session_01167zC7wdzDMaN52nL5LE4h` | hp | done; the routing port waits its turn (Fable) |

Environments: donald `env_01J9Bt5em2qaQ7qKdXzuiDFs`, hp `env_01QJbBwr5875K3VDN8mSUzMn`.

## Where things are
- App: **0.18.6** released („Standort teilen“ hidden behind the developer tools). Planner: newer state on main waits for
  a deploy (the next app release run does it, or the owner runs „Web planner“).
- Order of blocks: planner on shared code (**closed by the owner: „Block abschließen“**, logic mirrors only) → long-tour
  speed-ups (laptop) + multi-day day rows (umbau, sketch first) → rebuild phase 3/4 → routing port.

## Waiting for the owner
Pull requests 15, 16, 17, 18, 19 · the five open questions above · „Secret löschen ja“ in the newest donald chat · a
sentence to the laptop that it may deploy the planner itself · two merged helper worktrees of umbau on donald
(`agent-a42222aaab25bd23b`, `agent-a1e5af939680c4130`; their removal was refused, they do no harm).
