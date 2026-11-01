# Handoff: review the multi-session setup and propose a better one (2026-11-01)

**For a fresh Claude session.** The owner (happychriss) finds the current setup – several remote sessions with roles,
one-way messages, a backlog as message bus – more complicated than expected. Your job: understand how OnPath is
worked on today, then **propose a simpler, robust setup for exactly this use case** (2–3 options with trade-offs, a
recommendation, migration steps, and the questions only the owner can answer). **Do not change the setup, roles,
sessions, scheduled tasks or repos yourself** – propose; the owner decides. Write your proposal in German for the
owner (plain words, he reads on the phone), and keep this file as the factual base.

## 1. The project in one paragraph
OnPath = offline hiking / biking app for Android (Kotlin, Compose, MapLibre, offline BRouter routing) plus a browser
planner (plan.onpath.earth, the app's routing / sync code compiled to Wasm) plus a website (onpath.earth) and
marketing. One owner, no other humans; all coding by Claude sessions. First real testers are about to be invited
(release v0.14.0). Read `CLAUDE.md`, `docs/owner-preferences.md`, `docs/working-agreement.md` (§1 rules, §8 roles),
`docs/roles/*.md`, `docs/backlog.md`, "Last session" in `docs/project-status.md`.

## 2. Where everything lives
| What | Where |
| --- | --- |
| App + shared code | `happychriss/OnPath` (public), branch `main` – push straight to main after `tools/ci/gate.sh` |
| Browser planner | same repo, branch `web-spike` (`web/`); compiles the app's shared files from its own copy of `app/` (file list `web/engine/build.gradle.kts` `appFiles`); every green push deploys plan.onpath.earth |
| Website | `happychriss/onpath.earth` (public, Cloudflare Pages, deploys `main`) |
| Marketing, private notes, screenshots | `happychriss/onpath.private` (private); old Google Drive "OnPath / 01 Aufträge" = history |
| CI | GitHub Actions: `unit.yml` (full unit + screenshot tests), `e2e.yml` (emulator), `release.yml` (version bump on main → signed APK + GitHub Release), `web-spike.yml`, `maps-deploy.yml` |
| Map / sync servers | Cloudflare (R2, Workers, D1) + Google Cloud Run job for map cells; TEST environment only so far |
| Reviews / decisions | `docs/reviews/2026-11-01-pre-release.md` (+ reports), `docs/requirements.md`, `docs/design/*` |

## 3. Today's setup (as of 2026-11-01)
**Computers (owner's):**
- **donald** (desktop): Android SDK, emulator, the owner's Pixel 8 on USB. The only place the app can be built and
  tested end to end. Folder `/home/development/Projects/work/onpath-app`. Memory is tight: Gradle ≤ 2 workers, one
  heavy run at a time (`~/onpath-cells-work/gradle.lock`).
- **laptop "hp"** (32 GB): map builds (big OSM data outside git), planner work. Folder
  `/home/development/Projects/work/onpath-plan`.
- Both are switched off at night / when the owner is away.

**Sessions (roles, `docs/roles/`):**
| Role | Runs where | Owns |
| --- | --- | --- |
| cloud-app | donald, via Remote Control ("bridge" session) | app, releases |
| cloud-plan | laptop, via Remote Control | web planner, map pipeline |
| cloud-web | Anthropic cloud container | website, marketing (`onpath.private`) |
| cloud-captain | Anthropic cloud container | priorities, release check, orders – writes no product code |

**Also running, outside the roles:** a scheduled task "OnPath Web-Planer: täglicher Abgleich mit der App" (daily
05:47 Berlin, fresh cloud session): merges `main` into `web-spike`, adopts app changes in the web, pushes → deploys.
It is not mentioned in any role file and overlaps with cloud-plan.

**Channels:**
- Captain → role: direct message (`send_message`). Works only after the owner cleared the captain once on a trusted
  device (`confirm_repository_trust`, card on the phone).
- Role → captain: **impossible** (`elevated_session_untrusted_device`); roles write under "For the captain" in
  `docs/backlog.md`, the captain reads it after a pull. Captain → role lasting orders also go into the backlog
  ("For the roles").
- Handoffs: "Last session" in `docs/project-status.md`, one entry per role per sitting.
- Owner ↔ every session: typing into each session in the Claude app.

## 4. Pain points observed (concrete, from the last 24 h)
1. **Sessions die with the computer.** Ending Remote Control on donald / laptop ended (the owner says "deleted") the
   cloud-app / cloud-plan sessions; the owner had to archive them, open new ones, rename them and paste start prompts
   again. Bridge environments pile up (`hp:onpath-plan` exists twice).
2. **One-way messaging.** Roles cannot answer the captain; every answer is a commit to `main`'s backlog, read only
   when the captain happens to pull. A role's direct reply was refused again today.
3. **The captain cannot schedule its own check-ins.** After the trusted-device clearance, a `send_later` reminder
   failed with `ACCESS_DENIED` – so the captain only acts when the owner writes or a background job of its own ends.
4. **A cleared captain may never attach to a computer folder** (it would lose its reach into the other sessions), and
   a fresh captain needs the clearance card again.
5. **Cloud sessions cannot build or test the app** (no Android SDK in the container), so when donald is off nothing
   on the app moves – the "role works on in the cloud" fallback is not real today.
6. **Backlog as message bus = many tiny commits to `main`** ("Backlog: …", "Handoff …") from 4+ writers, merge races,
   noise in the history and in `gate.sh` (docs-only, cheap, but still pulls / pushes).
7. **Two long-lived branches for shared code** (`main` / `web-spike`) + a daily merge task + cloud-plan merging by hand
   → drift risk (19 shared files are compiled only on `web-spike`, review architecture #5).
8. **Files stuck on one computer:** the A-019 screenshots sat on donald / in Drive; it took a helper to copy them into
   `onpath.private` so cloud-web could work.
9. **The owner is the router:** starting sessions, pasting prompts, approving cards, relaying "is X done?", naming
   sessions – exactly the overhead he wants to lose.

## 5. What must stay true (constraints)
- App builds, emulator E2E and Pixel tests need donald (or a cloud environment with an Android SDK – check what a
  setup script / custom environment allows: `read_documentation` topics `environment.setup_script`,
  `environment.dependencies`, `session.resources`).
- Map builds need big local data (laptop) or the existing Cloud Run job.
- Robust first: never lose work; everything important pushed; small green commits; the owner's rules in `CLAUDE.md`
  ("Git", "Releases") and `docs/owner-preferences.md`.
- App and planner must release together from the same shared code (release check in `docs/roles/cloud-captain.md`).
- Public repos never get private material; `onpath.private` for that.
- The owner mostly works from the phone / browser; computers are sometimes off.

## 5b. The owner's main wish – one place for open questions (2026-11-01)
"I need one central place that tells me when any assistant has a question: a short overview, a little background,
then the question – a dashboard with all questions I have to answer. Something like this probably exists already.
We really don't want much more; that would be enough."
- Requirements: every session (roles, scheduled tasks, helpers) puts each owner question there with: who asks, a
  2–3 line background, the question with clear options + a recommendation, link to details; the owner answers there
  (or the answer reaches the session); answered questions disappear; works on the phone; a notification when a new
  one arrives. Nothing else – no big tool.
- Check what already exists before building anything: the Claude app's session list already marks sessions that
  wait for input (status "blocked" / `needs_action`, e.g. cloud-web's "Soll ich loslegen?" today); push
  notifications from sessions; GitHub issues with a label like `owner-question` (phone app + notifications, every
  session can write with `gh`, the answer is a comment the session can read); a small artifact page that lists them.
  Today questions are scattered: chat in each session, "Waiting for the owner" in `docs/backlog.md`, decision lists in
  review files (e.g. D1–D4 in `docs/reviews/2026-11-01-pre-release.md`).
- Make this the first thing your proposal solves; the rest of the setup should serve it.

## 6. Ideas worth evaluating (not decided – check feasibility first)
- **Fewer standing sessions:** e.g. one lead session for app + planner on donald, the website as an occasional cloud
  session, the captain's release check as a script / checklist instead of a session.
- **Sessions started by a scheduler or by one coordinator instead of by hand:** `create_session` with a bridge or
  self-hosted environment (`environment_id`), or scheduled tasks (`create_trigger`, fresh session per fire) with a
  standard prompt per role – so a dead session is just restarted, no prompt pasting.
- **Start prompts in the repo** (`docs/roles/start-prompts.md`) or role detection from the folder, so a new session
  needs one line.
- **GitHub Issues / one "coordination" issue per release as the message bus** instead of backlog commits: every
  session can read and comment with `gh`, the owner gets notifications, no commits to `main`; check whether bridge
  sessions may comment and whether PR / issue subscriptions (`subscribe_pr_activity`) can wake a session.
- **One branch:** merge `web-spike` into `main` (planner under `web/`, deploy from `main` on path changes) to end
  the drift and the daily merge task.
- **A cloud environment with the Android SDK** (setup script) so app unit + screenshot tests run without donald;
  device and emulator checks stay on donald.
- **Shared files:** screenshots and handoff material always in `onpath.private`, never only on one computer.

## 6b. Owner's question: a ChatGPT agent in the crew? (2026-11-01)
The owner asks whether a ChatGPT-based agent (e.g. Codex with GitHub access) could join. It cannot use Claude's
session messages, but the repo channels (backlog "For the roles" / "For the captain", handoffs, GitHub issues) are
tool-agnostic. Captain's view: feasible with a narrow, file-disjoint role – best as an **independent reviewer** (reads
code / diffs, writes findings to an issue or "For the captain", does not push to `main`); it would need an
`AGENTS.md` pointing to `CLAUDE.md`; it has the same Android-SDK limit in its cloud. Include it in your options
(cost, benefit, how it fits the questions dashboard) – or say why not.

## 7. What to deliver
1. A short picture of today's setup in your own words (so the owner sees you understood it).
2. 2–3 concrete setups, each: who runs where, how work is started, how sessions talk, what happens when a computer is
   off, what the owner still has to do daily, risks.
3. Your recommendation + step-by-step migration that keeps the v0.14.0 release safe (do not disturb running work).
4. The questions only the owner can answer (e.g. may donald stay on, is a paid / bigger cloud environment OK, one
   branch or two).
Before claiming a capability exists, verify it (`read_documentation`, `list_environments`, a harmless test).
