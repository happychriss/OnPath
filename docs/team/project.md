# OnPath – the project sheet

**Read first: `docs/team/captains-guide.md`** (how we work – roles, loop, board, the three checks, the vision, working
with the owner), **then `VISION.md`** (what we build). This sheet is the OnPath part the guide leaves to the project:
computers, who sits where, tools, hot files, parity, release, map build, repos. Each role's own sheet is in
[`roles/`](roles/README.md); how to ask the owner and his standing permissions are in
[`owner-preferences.md`](owner-preferences.md).

## The product
Offline hiking and biking app for Android (Kotlin, Compose, MapLibre, BRouter) plus the browser planner (`web/` on
`main`, served at plan.onpath.earth). Owner: Chriss (`happychriss`). Priorities and code rules: `CLAUDE.md`.
Requirements with stable IDs: `docs/requirements.md`. Product decisions: `docs/project-status.md` "Decisions" and the
design documents in `docs/design/`.

## The Claude project (owner 2026-11-27)
- **Project lead** = the coordinator in the Claude project chat ([`roles/projektleiter.md`](roles/projektleiter.md)).
- **Captain** = one cloud thread, always named **„Captain“** ([`roles/captain.md`](roles/captain.md)); it keeps
  [`board.md`](board.md).
- **Role threads** = Remote Control sessions on donald or HP, or cloud threads. Every other thread is renamed after its
  current or main task, with the computer in brackets – „… (donald)“, „… (HP)“.
- Language: German toward the owner; the documents in the repo are English.

## The computers
| Computer | Role | What only it can do |
| --- | --- | --- |
| **donald** | app | Android builds, emulator, the owner's **Pixel 7 over adb**, map builds (cells) |
| **HP** (laptop) | planner | browser planner `web/`, planner deploy |
| cloud | captain, cloud-web, reviews | reading, docs, GitHub – **no Android build** |

## Who sits where
Everything on the computers lives under `/home/development/Projects/work/`.

| Session | Runs on | Folder / repos | Reads |
| --- | --- | --- | --- |
| **donald** | donald (Pixel, emulator), Remote Control host | `onpath-app` (`main`) | [start.md](roles/start.md) |
| **laptop** (planner + map cells, `[cloud-plan]` lines) | laptop (32 GB, map data), Remote Control host | `onpath-plan` (`main`) | [start.md](roles/start.md) + [cloud-plan.md](roles/cloud-plan.md) |
| **cloud-web** | cloud only | `onpath.earth`, `onpath.private` | [cloud-web.md](roles/cloud-web.md) |
| **project lead** (coordinator) | the Claude project chat – talks with the owner, starts the captain, passes the captain's orders to the role threads | – | [projektleiter.md](roles/projektleiter.md) |
| **captain** | cloud thread | all, mostly read-only; keeps [board.md](board.md) | [captain.md](roles/captain.md) |
| **IR** (tour suggestions, „Routenfinder“, since 2026-11-15) | donald, own worktree of `onpath-app` (`main`) | `tools/suggest-poc/`, `tools/*suggest*`, `app/…/suggest/`, `docs/design/tour-suggestions.md` | [start.md](roles/start.md) + its latest `docs/team/handoffs/handoff-ir-*.md` |
| **umbau** (the rebuild of 2026-11, `docs/design/architecture-plan-2026-11.md`) | donald, own worktree of `onpath-app` (`main`) | module structure, `:core` / `:state`, tests and CI layout – by the plan's phases | [start.md](roles/start.md) + the plan |
| **umbau-hp** (second hand of the rebuild; first package: routing spike 5a) | hp / laptop, own worktree of `onpath-plan` (`main`) | `experiments/spike-routing-kmp/`, `docs/design/routing-kmp-spike.md`; later packages from [umbau] | [start.md](roles/start.md) + the plan |
| **mehrtage** (multi-day tours, since 2026-11-16) | donald, own worktree of `onpath-app` (`main`) | `routing/PlanDays.kt`, `PlanCode.kt`, `PlanEdits.kt`, `docs/design/multi-day-tours.md`; planner (`web/`) and phone parts by agreement | [start.md](roles/start.md) + `docs/design/multi-day-tours.md` |

Rule: one session per computer folder; helpers use their own worktrees.
Backlog tags = session names (`[donald]`, `[IR]`, `[mehrtage]`, `[umbau]`, `[umbau-hp]`, `[laptop]` / `[cloud-plan]`,
`[cloud-web]`, `[captain]`; the old `[cloud-app]` = donald). Item tags: `[pixel]`, `[emu]` → donald · `[karten]`, `[ram]`
→ laptop (`roles/start.md`).

## Tools
- `tools/ci/gate.sh` before every push; other Gradle runs only in a slot: `tools/dev/gradle_slot.sh -- ./gradlew …`
  (`working-agreement.md` §4).
- Emulator and Pixel only through `tools/dev/device.sh emulator|pixel -- <command>`; phone rules in
  `owner-preferences.md` "The owner's phone".
- Remote Control host of a computer: `tools/dev/unit.sh run <name>` (`roles/start.md`).
- E2E: one test locally, big runs on GitHub with `tools/ci/e2e_github.sh` (`working-agreement.md` §6).
- Release check: `tools/ci/release_check.sh` (`roles/captain.md` "Release"); app ⇄ planner: `--no-ci`.
- Old branches only with `tools/dev/prune_branches.sh` after the owner's yes (`roles/start.md` round step 4).
- Cloud session: `tools/cloud/session_setup.sh` once.

## Hot files and ownership
- The current hot files (several threads touch them) are listed at the top of [`board.md`](board.md); rule (owner
  2026-11-27): new UI goes into its own file.
- Generated files and how to resolve their conflicts: `working-agreement.md` §3. Who owns which area by default:
  `working-agreement.md` §2.

## Parity app ⇄ planner
App and planner alike (owner 2026-11-27, `VISION.md`); every UI change on one side gets a parity line for the other
side; ridden tours stay phone-only. The rule: `owner-preferences.md` „App and planner alike“. Mirrored files:
`web/parity.json`, `node web/parity/check.mjs` (`roles/cloud-plan.md`).

## Release and deploy
- App release only on the owner's word: `.claude/rules/release.md`, GO by `tools/ci/release_check.sh --go vX.Y.Z`
  (`roles/captain.md` "Release").
- Planner: deploy when done and green, without asking (`owner-preferences.md`); by hand: Actions → Web planner → Run
  workflow (`roles/cloud-plan.md`).

## Map build
Map builds (cells) on donald, see the computer table; the laptop's part: `roles/cloud-plan.md`. A paused map build is
resumed on donald with `systemctl --user stop onpath-germany-pause`.

## Repos and the private rule
- `happychriss/OnPath` – app and planner, **public**.
- `happychriss/onpath.earth` – the finished website, **public**.
- `happychriss/onpath.private` – private notes, marketing, ideas, Google Play texts, website planning. Nothing from it
  is copied into a public repo (`working-agreement.md` §5, `roles/cloud-web.md`).
