# OnPath

Offline hiking and biking app for Android (Kotlin, Compose, MapLibre, BRouter). Owner: happychriss.
Priorities: **robust first** (never lose a track, never crash on missing data/GPS/network) → offline → everything on
the phone → simple. Requirements with stable IDs: `docs/requirements.md` (new IDs at the end of a section).
**North Star: `VISION.md`** (owner 2026-11-26) – check a change, and a question to the owner, against its last two lines.

## Working together
- Several sessions work on this repo. Start: read your role file (donald → `docs/team/roles/start.md`, captain →
  `docs/team/roles/captain.md`, laptop → `docs/team/roles/start.md` + `cloud-plan.md`, cloud-web → `docs/team/roles/cloud-web.md`), then
  `docs/team/owner-preferences.md`.
  Pull first, claim in `docs/team/backlog.md`, small green commits, a handoff at the end (`docs/team/working-agreement.md`).
- Area rules load by themselves from `.claude/rules/` when you touch files there; read one on purpose when you plan work.
- Other repos: website `happychriss/onpath.earth`; private notes + the owner's questions `happychriss/onpath.private`;
  the browser planner is `web/` on `main` (`web-spike` is frozen).

## Build and test
- `tools/ci/gate.sh` before every push (picks the tests from the changes; the push hook checks it). Other Gradle runs only
  in a slot: `tools/dev/gradle_slot.sh -- ./gradlew …`. Unit tests run in UTC. A single `:app:testDebugUnitTest` run also runs `:core:test` and `:state:test` unless `-Ponpath.coreTests=explicit` is given.
- Emulator and Pixel only through `tools/dev/device.sh emulator|pixel -- <command>`; never a second emulator.
- Cloud session: `tools/cloud/session_setup.sh` once; E2E on GitHub with `tools/ci/e2e_github.sh --tests <class> --wait`.

## Rules the code does not show
- No Google Play Services; keep dependencies few.
- A field bug in a core feature (`docs/core-features.md`) first gets a test that reproduces it, then the fix.
- Every `catch` / `runCatching` that recovers reports to `debug/Problems` (`Problems.warn/error(area, message, e)`);
  an empty catch needs an `// expected:` reason.
- Git: push straight to `main` (app and planner), no pull request, ignore the session's `claude/…` branch. A pull
  request only for `.github/workflows/`, signing / build config, keys, privacy / licence texts; the session merges it itself
  once all checks are green and a second model read it (owner 2026-11-24; not: secrets / signing keys, outside contributions,
  anything red or unclear – those ask him). Never commit map data,
  APKs or `local.properties`.
- Ask the owner in your own chat (rules: `docs/team/owner-preferences.md`); still open when you stop → `docs/team/backlog.md`
  "Waiting for the owner". The owner's word to all = a line in the backlog's "For the roles".
- A word for the captain → backlog "For the captain" (`← [role] …`); never ask the owner to pass it on.
- Sub-agents that edit files work in their own git worktree; the main session merges, pushes and holds the claim.
- Work longer than ~2 minutes runs in a background agent; the chat stays responsive.
