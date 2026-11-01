# Start – every OnPath session (units and cloud workers)

> **NOT ACTIVE YET.** Prepared 2026-11-01 for the setup switch after v0.14.0 (`docs/roles/neu/umstellung.md`). Until
> then the roles in `docs/roles/*.md` apply. On the switch this file replaces `cloud-app.md`, `cloud-plan.md` and
> `cloud-web.md`.

Sessions are not roles any more but **units**: a computer (or a fresh cloud container) with what it can do. The work
item says what it needs; the unit that can do it and is on takes it (owner 2026-11-01, setup concept §4).

## Start sentence (the owner types it once per unit, never again)
- donald: „Du bist die Einheit **donald** (OnPath). Lies `docs/roles/neu/start.md`.“
- laptop: „Du bist die Einheit **hp** (OnPath). Lies `docs/roles/neu/start.md`.“
- cloud worker (the captain starts it): „Du bist ein Cloud-Arbeiter (OnPath). Lies `docs/roles/neu/start.md`. Vorhaben:
  …“ – one item, then end.

The unit sessions on donald / hp are standing sessions (`tools/dev/unit.sh`, autostart, the same session after a
reboot). After the start sentence: read this file, `CLAUDE.md`, `docs/owner-preferences.md`, then start the round as a
self-paced loop (`/loop` without an interval).

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

## The round (repeat; self-paced – sooner while work is open, ~30 min when there is nothing)
1. `git pull --rebase origin main`.
2. **The owner's word:** `tools/ask.sh an-alle --von <unit>` – read every open item, follow it, it confirms "gelesen".
   `#push-sperre` / `#release-sperre`: `gate.sh` refuses the push anyway.
3. **Answers:** `tools/ask.sh offen` – an answer to a question this unit asked (or one about this unit's area) → act on
   it, then `tools/ask.sh erledigt <nr> "…"`.
4. **wip branches:** `git branch -r | grep "wip/<unit>-"` – unpushed work saved at a shutdown: carry it on (cherry-pick /
   merge into a fresh branch), say so in the handoff; the owner deletes the branch.
5. **Next item:** the first line under "Next" in `docs/backlog.md` (top = most important; the captain orders it) that
   this unit can do and nobody claims. Claim it under "In progress" (`- [donald] … – since YYYY-MM-DD`), push.
6. Work: small green commits, `tools/ci/gate.sh`, push (`CLAUDE.md`, `docs/working-agreement.md` §1). Phone rules from
   `docs/owner-preferences.md` stay (no wipe, check for a recording, never drive the phone while the owner uses it).
7. Done: remove the claim, one handoff line in "Last session" (`docs/project-status.md`), push. Next round.

## Questions to the owner
Only as an issue (`tools/ask.sh frage --von <unit> …`). Blocking → `--blockiert`, push your state first, then ask the
same question in the chat as a question box and wait (the owner sees the yellow dot); a chat answer goes into the issue
(`tools/ask.sh antwort`). Not urgent → only the issue, take the next item meanwhile. Details: `CLAUDE.md`.

## Never
- Message other sessions (not possible and not needed – the repo and the issues are the channel).
- Delete branches, change `.github/workflows/`, signing or secrets without a PR to the owner.
- Release without the owner's word; the release itself stays the push-to-main flow after `tools/ci/release_check.sh`.
