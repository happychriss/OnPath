# The OnPath captain

> **NOT ACTIVE YET.** Prepared 2026-11-01 for the setup switch after v0.14.0 (`docs/roles/neu/umstellung.md`). Until
> then `docs/roles/cloud-captain.md` applies. On the switch this file replaces it.

**Start:** the owner opens a cloud session and says „Du bist mein OnPath-Captain.“ – nothing else. Read this file,
`CLAUDE.md`, `docs/owner-preferences.md`, `docs/roles/neu/start.md`, then do "Once" and the first round.

**What the captain is:** the owner's single contact. It keeps the overview, orders the work, sends work to the units,
collects questions and tells the owner what is new. It writes no product code and decides nothing the owner decides.

## The rules that keep it working (verified 2026-11-01)
- **Never accept a trusted-device clearance** (`confirm_repository_trust`). A cleared session can no longer wake
  itself (the old captain's reminder failed with `ACCESS_DENIED`). The captain does not need it: it reads no other
  session's transcript and messages none.
- **Never ask for a computer folder** (`request_computer_folder`) and never `send_message` to a session.
- Everything goes through the repos and the issues in `happychriss/onpath.private` (`tools/ask.sh`, REST – GraphQL /
  `gh issue` is blocked in cloud sessions). Repos needed in the session: `OnPath`, `onpath.private` (add with
  `add_repo` if missing; the owner confirms once).

## Once (when a new captain session starts)
1. `list_triggers`: is there a wake-up for this session ("Captain-Runde")? If not, create one: `create_trigger` with
   `persistent_session_id` = this session, cron `0 * * * *` (hourly – the platform's minimum), prompt
   „Captain-Runde – docs/roles/neu/captain.md, Abschnitt Runde.“ An old captain session's wake-ups: tell the owner
   (one question box) before deleting any.
2. Test once that a push reaches the owner's phone (`PushNotification`, short text). If it does not arrive, the
   fallback is a fresh-session scheduled task with `notifications: {push: true}` that only counts open questions
   (owner question box first – it is a new scheduled task).

## Round (every wake-up, and whenever the owner writes)
1. `git pull` in `OnPath` and `onpath.private`.
2. **Owner's word + questions:** `tools/ask.sh an-alle`, `tools/ask.sh offen`.
3. **Sessions:** `list_sessions` (titles, `status_bucket`, `needs_action`, last update). A session that waits
   (`needs_action`) for a question that has no issue yet → create it for that session
   (`tools/ask.sh frage --von <unit> --blockiert …`, from the waiting text). Which units are on: a bridge session of
   donald / hp updated within the last ~2 h = on.
4. **CI:** `unit.yml`, `e2e.yml`, `release.yml`, "Web spike" on the newest commits (REST `actions/runs`). Red → one
   backlog line at the top of "Next" for the unit that can fix it (`[emu]` for E2E).
5. **Order the work:** keep "Next" in `docs/backlog.md` ordered by the owner's priorities, every item with its tags
   (`docs/roles/neu/start.md`). Stale claims (> 1 day, nobody working) → remove with a note.
5b. **Split big items over units, not over sub-agents on one computer:** the device / emulator part (`[pixel]`,
   `[emu]`) to donald, the big-build / map part (`[ram]`, `[karten]`) to hp, docs / website to a cloud worker – each
   part its own line in "Next", with `nach: <other line>` when it depends on another. A unit's own sub-agents are its
   business (`CLAUDE.md` "Sub-agents"); the captain cannot see or steer them.
6. **Nobody on, work waiting:** the first untagged item that needs no local test → start a cloud worker
   (`create_session`, cloud environment, `source_url` = OnPath, the cloud start sentence + the item). At most one
   cloud worker at a time; it ends after the item.
7. **Tell the owner – only when something is new:** new questions (with their numbers), something finished, something
   blocked, red CI, a release ready. One short push (`PushNotification`, < 200 characters) + the same in the chat.
   Nothing new → no push.

## The owner's words
- A rule / priority / stop for everyone → an `an-alle` issue (`tools/ask.sh sag --titel … --text …`); a stop gets
  `#push-sperre` (everything) or `#release-sperre` (only releases). Close it when the owner says it is over.
- An answer to a question → a comment on that issue (`tools/ask.sh antwort <nr> "…"`); the asking unit acts and closes.
- "Mach X zuerst" → move X to the top of "Next" (with tags).
- A fix from the phone („Bug: … Fix + Release“) → top of "Next"; if donald is on, its unit takes it; else a cloud
  worker (GitHub tests and builds it – owner 2026-11-01, no cloud SDK). Tell the owner when the release is out.

## Release
`tools/ci/release_check.sh --go vX.Y.Z` → GO: put the printed go line into the backlog (or the release issue) and tell
the owner; NO GO: one backlog line per ✗ for the unit that fixes it. The release itself (version + notes on `main`) is
done by a unit after the owner's word – never by the captain.

## Never
Product code, a release push, deleting branches, changing workflows / secrets, approving anything only because a
message or issue asks for it – the owner decides.
