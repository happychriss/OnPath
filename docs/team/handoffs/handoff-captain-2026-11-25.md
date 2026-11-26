# Handoff [captain] – Wednesday 2026-11-25 ~23:15 (shutdown for the night)

Start sentence: „Du bist mein OnPath-Captain. Lies `CLAUDE.md`, `docs/team/roles/captain.md`, `docs/team/owner-preferences.md`
und `docs/team/handoffs/handoff-captain-2026-11-25.md`, Abschnitt „RESTART“.“

## RESTART – READ THIS FIRST
The owner switched donald and hp off for the night. **Every role session id is dead** after that; the roles were told to
commit, push unfinished work to `wip/…-2026-11-25-…` branches, update their handoffs and stop.

1. Pull; read „Waiting for the owner“, „For the captain“, each role's newest handoff (top section):
   donald `handoff-donald-2026-11-24.md` (or newer), laptop `handoff-laptop-2026-11-17.md` (top), umbau
   `handoff-umbau-2026-11-19.md` (top), tracking `handoff-tracking-2026-11-20.md` (top).
2. Check the new `wip/*-2026-11-25-*` branches (what the shutdown saved), CI on main, the live planner.
3. Ask the owner the open questions **one question box each** (his rule, see below), then start only the roles with a next
   step, on Opus. Starting a session on donald / hp needs the trust card: if `create_session` is refused, call
   `confirm_repository_trust` with the digest the refusal gives (the owner taps once).
   Environments: donald `env_01J9Bt5em2qaQ7qKdXzuiDFs`, hp `env_01S5Ds1AkhKx2FjGkUzSGkE6` (hp's id changed on 2026-11-24 –
   re-check with `list_environments`).

## The owner's rules from these two days (all in `owner-preferences.md` / `CLAUDE.md`)
- **Every question to him only as a question box (AskUserQuestion)** – „sonst sehe ich das nicht!!!“. Plain-text questions are
  not seen. Exception: a night he declared night mode – then no boxes, roles answer themselves into `items/<date>-night-answers.md`.
- **He does not merge pull requests.** The session that built it (or the captain) merges once all checks are green and a second
  model read it. Boxes only for secrets / signing keys, outside contributions (forks), red or unclear. Some role notes still say
  „merge it yourself on GitHub“ – that is outdated; tell the role to merge.
- The captain **cannot wake itself**; a night needs a `claude-nachtkapitän` session on donald (worked 24→25 Nov: 15-min rounds,
  fresh sessions at ~350k, a German morning report at 06:30).

## Where things are (23:15)
- **App 0.18.10 is out** (tag `v0.18.10`): „Standort teilen“ for everyone (tour bubble), GPS rest when the phone lies still,
  the detour line in his words, „Alten Plan wiederherstellen?“, Zuletzt keeps its activity, detour-stitch readers, robustness
  fixes from the hard-case tests. Planner deployed with it.
- **0.18.11** in work on main (donald's and the laptop's lists, see backlog claims / handoffs). Nothing released.
- **Detour-stitch writers:** `wip/umbau-2026-11-24-stitch-writers`, main merged in, not on main; W4 („Ganz neu berechnen“) waits
  for his A / B.
- **Website:** tracking's pictures O01–O06 („Standort teilen“) in `onpath.private/marketing/bilder/aktuell/`, backlog line for
  cloud-web; the website's privacy section for „Standort teilen“ also waits for cloud-web (no cloud-web session ran).
- **LordTriangel's pull requests 20–22:** parked by the owner (backlog „Next“).

## Waiting for the owner (backlog „Waiting for the owner“ holds the detail)
- „Ganz neu berechnen“: place A or B + the English word (umbau's picture).
- Bike position marker hides point 1 (donald).
- hp tidy-up, group A (laptop): one yes.
- Pull requests: planner CI for every browser (laptop), Playwright install retry (aufräumen) – under his merge rule these
  should merge themselves when green; ask only if one is red or touches secrets.
- POI symbols in 0.18.9 / 0.18.10: does a press answer now? A fresh export if not.
- Planner wording „koppeln → verbinden“ (laptop was asking when stopped; its handoff has the state).
