# OnPath

Offline hiking and biking app for Android (Kotlin, Compose, MapLibre, BRouter). Owner: happychriss.
Priorities: **robust first** (never lose a track, never crash on missing data/GPS/network) → offline → everything on
the phone → simple. Requirements with stable IDs: `docs/requirements.md` (new IDs at the end of a section).

## Working together
- Several sessions work on this repo. Start: read your role file (donald → `docs/roles/start.md`, captain →
  `docs/roles/captain.md`, laptop → `docs/roles/cloud-plan.md`), then `docs/owner-preferences.md`.
  Pull first, claim in `docs/backlog.md`, small green commits, a handoff at the end (`docs/working-agreement.md`).
- Area rules load by themselves from `.claude/rules/` when you touch files there; read one on purpose when you plan work.
- Other repos: website `happychriss/onpath.earth`; private notes + the owner's questions `happychriss/onpath.private`;
  the browser planner is `web/` on `main` (`web-spike` is frozen).

## Build and test
- `tools/ci/gate.sh` before every push (picks the tests from the changes; the push hook checks it). Other Gradle runs only
  in a slot: `tools/dev/gradle_slot.sh -- ./gradlew …`. Unit tests run in UTC.
- Emulator and Pixel only through `tools/dev/device.sh emulator|pixel -- <command>`; never a second emulator.
- Cloud session: `tools/cloud/session_setup.sh` once; E2E on GitHub with `tools/ci/e2e_github.sh --tests <class> --wait`.

## Rules the code does not show
- No Google Play Services; keep dependencies few.
- A field bug in a core feature (`docs/core-features.md`) first gets a test that reproduces it, then the fix.
- Every `catch` / `runCatching` that recovers reports to `debug/Problems` (`Problems.warn/error(area, message, e)`);
  an empty catch needs an `// expected:` reason.
- Git: push straight to `main` (app and planner), no pull request, ignore the session's `claude/…` branch. A pull
  request only for `.github/workflows/`, signing / build config, keys, privacy / licence texts. Never commit map data,
  APKs or `local.properties`.
- Questions to the owner only as a `frage` issue via `tools/ask.sh` (see `--help`); the owner's word to all = `an-alle`.
- Every question the owner asks you in the chat goes into his Fragenregister page, with your answer under it
  (how: `docs/owner-preferences.md` "Fragenregister").
- Sub-agents that edit files work in their own git worktree; the main session merges, pushes and holds the claim.
- Work longer than ~2 minutes runs in a background agent; the chat stays responsive.
