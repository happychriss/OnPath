# Start – every OnPath session (units and cloud workers)

> **ACTIVE for donald since 2026-11-01** (owner: switch now, in the middle of normal work). Replaces `cloud-app.md`.
> **Transition** until `web-spike` is merged into `main` (`docs/roles/neu/umstellung.md` D):
> - the laptop stays **cloud-plan** on `web-spike` under its old role file (`docs/roles/cloud-plan.md`); cloud-web too;
> - backlog lines for **`[cloud-app]`** – claims, "For the roles" orders, handoffs – are **donald's** now; new claims
>   use `[donald]`. Words to cloud-plan still go under "For the roles" (`→ [cloud-plan]`); the captain reads
>   "For the captain" as before;
> - donald's checkout stays `/home/development/Projects/work/onpath-app` (no rename now).
> Worker sessions run in permission mode **auto** (owner 2026-11-01).

Sessions are not roles any more but **units**: a computer (or a fresh cloud container) with what it can do. The work
item says what it needs; the unit that can do it and is on takes it (owner 2026-11-01, setup concept §4).

## How a unit runs (tested on donald 2026-11-01)
- The computer runs a **host**: `claude remote-control` in the checkout (`tools/dev/unit.sh`, autostart), spawn mode
  **worktree** – every session it hosts gets its own git worktree; a restart reconnects all of them.
- In it lives one standing session, the **Leitstand** of the unit (`donald-leitstand`, `hp-leitstand`). It does no
  product work itself: it runs the round below and **starts one worker session per item** (`create_session` into its
  own environment – allowed on the owner's computer, not from the cloud). Workers do the item, push, end.
- **Wake-up:** the Leitstand sets itself an **hourly scheduled task** (`create_trigger`, `persistent_session_id` =
  itself, cron `0 * * * *`, prompt „Runde – docs/roles/neu/start.md“). It survives a reboot (`/loop` does not – tested);
  between the hourly rounds `/loop` may run faster while work is open. Check the task exists at every round.

## Start sentences (the owner types each once, never again)
- Leitstand donald: „Du bist der Leitstand der Einheit **donald** (OnPath). Lies `docs/roles/neu/start.md`.“
- Leitstand laptop: „Du bist der Leitstand der Einheit **hp** (OnPath). Lies `docs/roles/neu/start.md`.“
- Worker (the Leitstand starts it): „Du bist ein Arbeiter der Einheit <unit> (OnPath). Lies `docs/roles/neu/start.md`,
  Abschnitt Arbeiter. Vorhaben: <the backlog line>.“
- Cloud worker (the captain starts it): „Du bist ein Cloud-Arbeiter (OnPath). Lies `docs/roles/neu/start.md`,
  Abschnitt Arbeiter. Vorhaben: …“

## What each unit can do
| Unit | Can | Takes items tagged |
| --- | --- | --- |
| **donald** | build, unit + screenshot tests, Pixel on USB, emulator (one at a time, no Gradle beside it unless ≥ 10 GB free) | `[pixel]`, `[emu]`, untagged |
| **hp** (laptop, 32 GB) | build, tests, map builds with local data, big builds (Wasm engine, Germany) | `[karten]`, `[ram]`, untagged; `[emu]` only when it has an emulator |
| **cloud worker** | code, docs, website, marketing – no Android SDK, no emulator, no Pixel | untagged items that need no local test; pushes, GitHub tests (`unit.yml`, `release.yml`) |
| GitHub Actions | full tests, E2E (slow – only as a net), releases, planner deploy | – (runs on every push) |
| Cloud Run job | map cells | – (`tools/dev/publish_cells.sh`, `maps-deploy.yml`) |

Tags in `docs/backlog.md` "Next": `[pixel]` must be checked on the phone · `[emu]` needs an emulator run (donald
first – owner: emulator locally, GitHub only when no computer is on) · `[karten]` map build · `[ram]` big build ·
no tag = any unit. An item may carry several tags.

## Arbeiter (worker sessions)
One item, in your own worktree (the host made it): claim is held by the Leitstand; work per `CLAUDE.md` (gate, devices
via `tools/dev/device.sh`, questions via `tools/ask.sh`), push to `main` (rebase), one handoff line, then end – say
„fertig: <commit>“ as your last line. Never start other sessions.

## The round of the Leitstand (every wake-up; sooner while workers run)
1. `git pull --rebase origin main`.
2. **The owner's word:** `tools/ask.sh an-alle --von <unit>` – read every open item, follow it, it confirms "gelesen".
   `#push-sperre` / `#release-sperre`: `gate.sh` refuses the push anyway.
3. **Answers:** `tools/ask.sh offen` – an answer to a question this unit asked (or one about this unit's area) → act on
   it, then `tools/ask.sh erledigt <nr> "…"`.
4. **wip branches:** `git branch -r | grep "wip/<unit>-"` – unpushed work saved at a shutdown: carry it on (cherry-pick /
   merge into a fresh branch), say so in the handoff; the owner deletes the branch.
5. **Workers:** `list_sessions` – which of this unit's workers run, wait (`needs_action` – make sure its question is
   an issue) or are done (archive them, remove their claim). At most 2–3 workers at a time (RAM: Gradle and the
   emulator queue behind their locks).
5b. **Next item:** the first line under "Next" in `docs/backlog.md` (top = most important; the captain orders it) that
   this unit can do and nobody claims → claim it under "In progress" (`- [donald] … – since YYYY-MM-DD`), push, start
   a worker for it (start sentence above).
6. (Worker) Work – split over sub-agents when that saves time (`CLAUDE.md` "Sub-agents": own worktree each, devices only via
   `tools/dev/device.sh`, builds queue behind the Gradle lock): small green commits, `tools/ci/gate.sh`, push (`CLAUDE.md`, `docs/working-agreement.md` §1). Phone rules from
   `docs/owner-preferences.md` stay (no wipe, check for a recording, never drive the phone while the owner uses it).
7. (Worker) Done: one handoff line in "Last session" (`docs/project-status.md`), push; the Leitstand removes the
   claim and archives the worker.

## Questions to the owner
Only as an issue (`tools/ask.sh frage --von <unit> …`). Blocking → `--blockiert`, push your state first, then ask the
same question in the chat as a question box and wait (the owner sees the yellow dot); a chat answer goes into the issue
(`tools/ask.sh antwort`). Not urgent → only the issue, take the next item meanwhile. Details: `CLAUDE.md`.

## Never
- Message other sessions (not possible and not needed – the repo and the issues are the channel).
- Delete branches, change `.github/workflows/`, signing or secrets without a PR to the owner.
- Release without the owner's word; the release itself stays the push-to-main flow after `tools/ci/release_check.sh`.
