# Start – how a session on donald or the laptop works

The session on a computer is named after it: **donald** (Pixel, emulator), **laptop** (32 GB, map data). Since the
one-branch merge (2026-11-06) both work on `main`; the laptop keeps the planner and map cells (`cloud-plan.md` – what
it owns) and cloud-web works as before (`cloud-web.md`). The captain: `captain.md`. Why it is built this way and what was tested:
`multi-agent-lernen.md`.

**Start sentence** (the owner types it once): „Du bist die Einheit **donald** (OnPath). Lies `docs/roles/start.md`.“

## How it runs
- The computer runs a **host**: `tools/dev/unit.sh run <name>` in the checkout (`claude remote-control`, spawn mode
  worktree, permission mode auto). A restart of the host reconnects its sessions.
- In it lives **one standing session** that does the work itself and splits long steps over background agents in their
  own worktrees (`CLAUDE.md` "Sub-agents", "Responsive chat").
- **Wake-up:** once, the session sets itself an hourly scheduled task (`create_trigger`, `persistent_session_id` =
  itself, prompt „Runde – docs/roles/start.md“). It survives a restart; `/loop` does not.
- When the chat gets long: handoff, archive, start a new session with the same sentence – the repo is the memory.

## Who takes what (tags in `docs/backlog.md` "Next")
`[pixel]`, `[emu]` → donald · `[karten]`, `[ram]` → laptop · no tag → any unit that is free. The owner does not route:
an item said to the wrong session goes into the backlog for the right one.

## The round (every wake-up and whenever the owner writes – the start hook already shows 2–4)
1. `git pull --rebase origin main`.
2. Owner's word: `tools/ask.sh an-alle --von <name>`.
3. Answers: `tools/ask.sh offen` – answered questions of this unit → act, `tools/ask.sh erledigt <nr> "…"`.
4. `wip/<name>-…` branches (work saved before a shutdown): bring them to `main`, say so in the handoff. Old branches
   go with `tools/dev/prune_branches.sh` (owner 2026-11-06): `list` → one question box to the owner with exactly that list
   ("Alle löschen" / "Nein"; branches checked by hand marked as such) → on yes `delete [--by-hand "<why>"] <branches…>`,
   then commit + push `docs/history/deleted-branches.md`. Never the bare git delete commands; `restore <branch>` undoes one.
5. Next item this unit can do and nobody claims → claim under "In progress", push.
6. Work: small green commits, `tools/ci/gate.sh`, push. Gradle only in a slot (`gate.sh` / `tools/dev/gradle_slot.sh`),
   devices only via `tools/dev/device.sh`, phone rules in `docs/owner-preferences.md`.
7. Done: remove the claim, one handoff line in "Last session" (`docs/project-status.md`), push (`docs/working-agreement.md` §1).

**Before switching the computer off / when asked to stop:** push everything; half-done work on a `wip/<name>-<date>-<topic>`
branch.

## Questions to the owner
Only as an issue (`tools/ask.sh frage --von <name> …`). Blocking → `--blockiert`, push first, then the same question as
a question box in the chat. Not urgent → only the issue, go on. Rules: `docs/owner-preferences.md`.

## Never
Delete branches other than through `tools/dev/prune_branches.sh` after the owner's yes to the list (round, step 4); change `.github/workflows/`, signing or secrets without a PR to the owner; release without the
owner's word.
