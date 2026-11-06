# OnPath – guide for AI assistants and contributors

**Several sessions work on this repo (laptop, desktop "donald", cloud): follow `docs/working-agreement.md` – pull first, claim in
`docs/backlog.md`, push small green commits, leave a handoff.**
**Every session reads its role file at the start:** donald and the laptop → `docs/roles/start.md` (the laptop also
`docs/roles/cloud-plan.md` for the planner and map cells), captain → `docs/roles/captain.md`, cloud-web → `cloud-web.md`
(`[cloud-app]` backlog lines are donald's, `[cloud-plan]` the laptop's). One branch since 2026-11-06: app and planner on `main`. Why it is built this way: `docs/roles/multi-agent-lernen.md`.

Read this first, then `docs/owner-preferences.md` (how the owner likes to work), `docs/project-status.md` (where we are, decisions, next steps) and
`docs/requirements.md` (full requirements with IDs like F-PLAN-04, R-07). **Rules per area live in `.claude/rules/`** (ui, routing, map,
recording, sync-web, data-pipeline, release) – they load by themselves when you touch files there; read one on purpose when you plan work in that area.
**Hooks** (`.claude/settings.json`, `tools/hooks/`): every session starts with the owner's word to all, open questions, saved `wip/` work and the
backlog top in its context; `git push` is refused without a green `tools/ci/gate.sh` for these changes or while a `#push-sperre` is open; `adb install`
only through `tools/dev/device.sh`.

## What OnPath is
A simple, robust, fully offline hiking and biking app for Android (Komoot-like maps, much simpler).
Owner: happychriss. Licence: Apache-2.0. Distribution: Google Play (later); test builds are sideloaded.

Guiding principles, in priority order: **robust first** (never lose a track, never crash on missing
data/GPS/network) → **offline by design** → **everything on the phone** → **simple** → smart routing → open data.

## Repository map (details and rules per area: `.claude/rules/<area>.md`)
| Path | What | Rules |
| --- | --- | --- |
| `app/src/main/java/com/onpath/app/` | Kotlin app (Jetpack Compose + MapLibre) | |
| `  ui/` | `AppViewModel` (all UI state), `MapScreen`, `NavigateScreen`, `PlanPanel`, `SettingsScreen`, `MessageCard` (the one message box), `Theme` (`OnPathColors`, `OnPathType`), `Notices` | ui |
| `  map/`, `poi/`, `cells/` | `MapController` + `ActivityLayers` (shared with the web), `StyleLoader`, POIs, map cells on the phone | map |
| `  routing/`, `navigation/`, `time/`, `rides/`, `gpx/` | `RoutingContract` (shared interface), `BRouterEngine`, `Planner`, `PlanResult` (the one plan computation of app and web), `PlanEdits`, `Navigator` / `Rerouter`, cues, pace, GPX | routing |
| `  recording/`, `data/`, `setup/`, `backup/`, `search/`, `update/`, `debug/` | `RecordingService` (foreground, every DB write guarded), `TourDb`, `Prefs`, phone setup, backups, search, in-app updates, `Problems` log | recording |
| `  sync/`, `plansync/`, `app/web-shared-files.txt` | plan sync phone ⇄ browser (`sync/` = pure code shared with the web planner); the one list of app files the planner compiles | sync-web |
| `app/libs/`, `app/src/main/assets/brouter/` | BRouter 1.7.11-beta jars (`-onpath1`), profiles + `lookups.dat` | routing |
| `app/src/main/assets/style/onpath.json`, `fonts/` | map style – **generated** by `tools/make_style.py`; glyphs | map |
| `tools/` | data pipeline, map cells (`tools/cells/`, `tools/cloud/`), dev loop (`tools/dev/`), CI gate (`tools/ci/`), hooks (`tools/hooks/`), `ask.sh` | data-pipeline, release |
| `strings/` | user texts EN + DE (Compose Multiplatform resources, `docs/i18n.md`) | ui |
| `docs/` | requirements, status, backlog, design (`docs/design/ui-style.md`), roles (`docs/roles/`) | |
| *(other repos)* | website `happychriss/onpath.earth`; private notes + the owner's question dashboard `happychriss/onpath.private`; the browser planner is `web/` (served at plan.onpath.earth; `web-spike` is frozen since the merge 2026-11-06) | sync-web |

## Build and test
- JDK 17+ (21 used), Android SDK 35 (`local.properties` → `sdk.dir`, not committed). `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`; `./gradlew testDebugUnitTest` (UTC).
- **Before every push: `tools/ci/gate.sh`** – tests picked from the changed files, screenshots when ui / texts changed, docs-only → no Gradle, `--full` = everything; builds in a Gradle slot (`tools/dev/gradle_slot.sh`). The push hook checks it ran green. Details: `.claude/rules/release.md`.
- Emulator and Pixel only via `tools/dev/device.sh emulator|pixel -- <command>` (one agent per device; emulator on donald first). Screenshot tests, JVM routing tests, style checks: see the area rules.
- **Cloud session** (no computer): `tools/cloud/session_setup.sh` once (SDK + Maven mirror, ~90 s), then build as above; E2E on GitHub's emulator with `tools/ci/e2e_github.sh --tests <class> --wait`.

## Conventions
- **Kotlin**, Compose + Material 3, package `com.onpath.app`, minSdk 26, target/compileSdk 35.
- **No Google Play Services** (LocationManager directly); keep dependencies few.
- **Core features** (`docs/core-features.md`, e.g. hold a route point → menu → Delete): each has regression tests;
  a field bug in one first gets a test that reproduces it, then the fix (owner).
- **No silent errors:** every `catch` / `runCatching` that recovers reports to `debug/Problems`
  (`Problems.warn/error(area, plain message, e)`; INFO for expected situations like offline) – never `catch (_: …) {}`
  without an `// expected:` reason (how Problems works: `.claude/rules/recording.md`).
- **Git**: small commits with clear messages, pushed **straight to `main`** (app and planner alike) after `tools/ci/gate.sh` –
  standing permission of the owner, no pull request, don't use or ask about the session's `claude/…` branch. A pull
  request to the owner only for `.github/workflows/`, signing / build config, keys / secrets, privacy / licence texts.
  Never force-push or delete a shared branch (`docs/working-agreement.md` §1 "Where to push"); never commit map data,
  APKs or `local.properties`.
- **Questions to the owner + the owner's word to all** (owner 2026-11-01): issues in `happychriss/onpath.private` via `tools/ask.sh` (REST,
  `--help`). A question exists **only** as a `frage` issue (`tools/ask.sh frage --von <unit> …`); **blocking** → `--blockiert`, push your state, ask
  the same in the chat and wait (else keep working); a chat answer goes into the issue (`ask.sh antwort`). Act, then `ask.sh erledigt <nr> "…"` – the session closes
  a `frage`, never the owner. Label `an-alle` = the owner's word to every session (confirm once: `ask.sh an-alle --von <unit>`; `#push-sperre` /
  `#release-sperre` stop pushes). How to ask: `docs/owner-preferences.md`.
- **Sub-agents** (owner 2026-11-01): every sub-agent that edits files works in its own git worktree on its own branch; the main session merges, pushes
  and holds the one backlog claim. Gradle only in a slot – `tools/ci/gate.sh` or `tools/dev/gradle_slot.sh -- ./gradlew …` (two builds side by side per
  computer, one while the emulator runs), never a bare `flock gradle.lock`; screenshots: record only the changed class
  (`--tests`); never start a second emulator.
  Parallel work across computers = separate backlog items, one per unit.
- **Responsive chat** (owner 2026-11-01): work longer than ~2 min runs in a background agent (preferably a fork), the session answers at once
  ("läuft: …"); details: `docs/owner-preferences.md`.
- **Requirements** live in `docs/requirements.md` (exported from the shared Claude doc); keep IDs stable,
  add new ones at the end of a section.
