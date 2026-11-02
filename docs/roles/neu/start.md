# Start – every OnPath session (units and cloud workers)

> **ACTIVE for donald since 2026-11-01** (owner: switch now, in the middle of normal work). Replaces `cloud-app.md`.
> **Transition** until `web-spike` is merged into `main` (`docs/roles/neu/umstellung.md` D):
> - the laptop stays **cloud-plan** on `web-spike` under its old role file (`docs/roles/cloud-plan.md`); cloud-web too;
> - backlog lines for **`[cloud-app]`** – claims, "For the roles" orders, handoffs – are **donald's** now; new claims
>   use `[donald]`. Words to cloud-plan still go under "For the roles" (`→ [cloud-plan]`); the captain reads
>   "For the captain" as before;
> - donald's checkout stays `/home/development/Projects/work/onpath-app` (no rename now).
> Sessions in the host run in permission mode **auto** (owner 2026-11-01).

Sessions are not roles any more but **units**: a computer (or a fresh cloud container) with what it can do. The work
item says what it needs; the unit that can do it and is on takes it (owner 2026-11-01, setup concept §4).

## How a unit runs (owner 2026-11-02: "Leicht mit Captain" – one working session per computer, no Leitstand)
- The computer runs a **host**: `claude remote-control` in the checkout (`tools/dev/unit.sh`, autostart), spawn mode
  **worktree**; a restart reconnects every session of the folder (tested).
- In it lives **one standing session, the unit itself** (`donald`, `laptop` – owner 2026-11-02: sessions are named after the computer, the work is routed by the backlog tags). It **does the work** – like cloud-app did –
  and splits it over sub-agents / forks in their own worktrees when that saves time (`CLAUDE.md` "Sub-agents",
  "Responsive chat"). It may start an extra session in its own environment for a big separate item (works, tested);
  that is the exception, not the rule.
- **Wake-up:** the unit sets itself an **hourly scheduled task** once (`create_trigger`, `persistent_session_id` =
  itself, cron `0 * * * *`, prompt „Runde – docs/roles/neu/start.md“) – it survives a reboot (`/loop` does not). So the
  unit goes on by itself after a restart and while the owner is away. Check at every round that the task exists.
- When the chat gets long (days): write the handoff, archive the session, start a new one in the host with the same
  start sentence – the repo is the memory.

## Names (owner 2026-11-02)
The session on a computer is named after the computer: **donald**, **laptop** – in the Claude app, in the start
sentence and as the backlog tag (`[donald]`, `[laptop]`). The name says *where* the work runs, not *what* it is: until
`web-spike` is merged the laptop still does the planner (as cloud-plan, tag `[cloud-plan]`), afterwards both do both.
The owner does not route work: he tells the captain (or whichever session he is talking to), the item goes into the
backlog with its tags, and the computer that can do it takes it. A session told something that belongs to the other
computer puts it into the backlog for it instead of doing it.

## Start sentences (the owner types each once, never again)
- donald: „Du bist die Einheit **donald** (OnPath). Lies `docs/roles/neu/start.md`.“
- laptop: „Du bist die Einheit **laptop** (OnPath). Lies `docs/roles/neu/start.md`.“
- Cloud worker (the captain starts it for an item that needs no computer): „Du bist ein Cloud-Arbeiter (OnPath). Lies
  `docs/roles/neu/start.md`. Vorhaben: …“ – one item, then end.

## What each unit can do
| Unit | Can | Takes items tagged |
| --- | --- | --- |
| **donald** | build, unit + screenshot tests, Pixel on USB, emulator (one at a time, no Gradle beside it unless ≥ 10 GB free) | `[pixel]`, `[emu]`, untagged |
| **laptop** (32 GB) | build, tests, map builds with local data, big builds (Wasm engine, Germany) | `[karten]`, `[ram]`, untagged; `[emu]` only when it has an emulator |
| **cloud worker** | code, docs, website, marketing – no Android SDK, no emulator, no Pixel | untagged items that need no local test; pushes, GitHub tests (`unit.yml`, `release.yml`) |
| GitHub Actions | full tests, E2E (slow – only as a net), releases, planner deploy | – (runs on every push) |
| Cloud Run job | map cells | – (`tools/dev/publish_cells.sh`, `maps-deploy.yml`) |

Tags in `docs/backlog.md` "Next": `[pixel]` must be checked on the phone · `[emu]` needs an emulator run (donald
first – owner: emulator locally, GitHub only when no computer is on) · `[karten]` map build · `[ram]` big build ·
no tag = any unit. An item may carry several tags.

## The round (every wake-up and whenever the owner writes; the start hook already shows 2–4)
1. `git pull --rebase origin main`.
2. **The owner's word:** `tools/ask.sh an-alle --von <unit>` – follow it, it confirms "gelesen".
3. **Answers:** `tools/ask.sh offen` – an answer to a question of this unit → act, then `tools/ask.sh erledigt <nr> "…"`.
4. **wip branches:** `git branch -r | grep "wip/<unit>-"` – work saved at a shutdown: rebase it onto `main`, gate,
   push; say so in the handoff; the owner deletes the branch.
5. **Next item:** the first line under "Next" in `docs/backlog.md` (top = most important; the captain orders it) that
   this unit can do and nobody claims → claim it under "In progress" (`- [donald] … – since YYYY-MM-DD`), push.
6. **Work:** small green commits, `tools/ci/gate.sh`, push (`CLAUDE.md`, `docs/working-agreement.md` §1); sub-agents in
   worktrees, devices only via `tools/dev/device.sh`; the phone rules of `docs/owner-preferences.md` (no wipe, check
   for a recording, never drive the phone while the owner uses it). Stay responsive: long steps in a background fork.
7. **Done:** remove the claim, one handoff line in "Last session" (`docs/project-status.md`), push. Next round.

## Questions to the owner
Only as an issue (`tools/ask.sh frage --von <unit> …`). Blocking → `--blockiert`, push your state first, then ask the
same question in the chat as a question box and wait (the owner sees the yellow dot); a chat answer goes into the issue
(`tools/ask.sh antwort`). Not urgent → only the issue, take the next item meanwhile. Details: `CLAUDE.md`.

## Never
- Message other sessions (not possible and not needed – the repo and the issues are the channel).
- Start sessions as a habit – one working session per computer; an extra one only for a big separate item.
- Delete branches, change `.github/workflows/`, signing or secrets without a PR to the owner.
- Release without the owner's word; the release itself stays the push-to-main flow after `tools/ci/release_check.sh`.
