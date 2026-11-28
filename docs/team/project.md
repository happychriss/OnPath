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
| **laptop** (planner, `[cloud-plan]` lines) | laptop (32 GB), Remote Control host | `onpath-plan` (`main`) | [start.md](roles/start.md) + [cloud-plan.md](roles/cloud-plan.md) |
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
Map builds (cells) run on **donald** (captain 2026-11-28; the night 27./28.11. built them there). A paused map build is
resumed on donald with `systemctl --user stop onpath-germany-pause`. Until then the laptop built them (History).

## Repos and the private rule
- `happychriss/OnPath` – app and planner, **public**.
- `happychriss/onpath.earth` – the finished website, **public**.
- `happychriss/onpath.private` – private notes, marketing, ideas, Google Play texts, website planning. Nothing from it
  is copied into a public repo (`working-agreement.md` §5, `roles/cloud-web.md`).

## History
- **The captain's role:** before 2026-11-27 the captain was the owner's contact; the owner opened a cloud session with
  „Du bist mein OnPath-Captain.“ Since 2026-11-27 the owner talks to the **project lead** (coordinator,
  `roles/projektleiter.md`) in the project chat of the Claude project; the coordinator takes the conversations and hands
  work to the role threads. The captain is a thread of its own (once named „Captain: Aufträge überwachen und
  verteilen“) that keeps the overview of who works on what and tells the coordinator who a new task belongs to; the
  overview duty stayed.
- **The captain splits and hands out** (owner 2026-11-27). The captain cannot reach the role threads – tested
  2026-11-27 – so the coordinator passes the order texts on word for word.
- **Vision gate** (owner 2026-11-27): every task and every branch in the merge queue is checked against `VISION.md`;
  the captain proposes changes or additions to the vision, the owner decides, the captain writes only what he approved;
  keep the vision short.
- **Second check „asked for?“** (owner 2026-11-28): a part nobody asked for goes to the owner as a question,
  recommendation „weglassen“.
- **Third check „built as decided?“** (owner 2026-11-28, case „‹ Alle Tage“ 40 px decided 27.11., a smaller pill built).
- **What the captain could and could not do** (tested – `roles/multi-agent-lernen.md` §2):
  - Before 2026-11-27 the captain session had the **trust card** (owner 2026-11-03): it may start sessions on donald /
    the laptop, message them and read them – but **every such call needs the owner's tap** („Einmal erlauben“). Use it
    only for something urgent the owner asked for now; normal work goes through the backlog. „Mach X jetzt“ could then
    also go – with his tap – as `send_message` to the running unit session.
  - With the card it **cannot wake itself**: it works when the owner writes. Confirmed again 2026-11-18: an hourly
    scheduled task into the captain session failed every run (access denied) – don't try. The computers work when the
    owner writes to them (their hourly wake-up is paused since 2026-11-07, owner: calm phase, saves tokens).
  - A captain session **without** the card can wake itself but cannot reach the computers at all. A new captain
    session is one or the other – ask the owner which.
- **Channels before the project lead** (`working-agreement.md` §5 until 2026-11-28): „Sessions do not message each
  other – the channels are the repo and the issues in `happychriss/onpath.private`“; „only the captain can, with the
  owner's tap per call, for something urgent the owner asked for“.
- **Map cells on the laptop** until the night 27./28.11.2026: the laptop unit owned the map cells and kept the big map
  data; details in `roles/cloud-plan.md` „History“.
