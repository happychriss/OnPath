# Roles

Everything on the computers lives under `/home/development/Projects/work/`. A fresh session with the same role carries on
from the repo – never from another session's chat.

| Session | Runs on | Folder / repos | Reads |
| --- | --- | --- | --- |
| **donald** | donald (Pixel, emulator), Remote Control host | `onpath-app` (`main`) | [start.md](start.md) |
| **laptop** (planner + map cells, `[cloud-plan]` lines) | laptop (32 GB, map data), Remote Control host | `onpath-plan` (`main`) | [start.md](start.md) + [cloud-plan.md](cloud-plan.md) |
| **cloud-web** | cloud only | `onpath.earth`, `onpath.private` | [cloud-web.md](cloud-web.md) |
| **coordinator** | the Claude project chat | – | takes the owner's conversations, starts and messages the role threads; asks the captain who a new task belongs to |
| **captain** | cloud thread | all, mostly read-only; keeps [board.md](../board.md) | [captain.md](captain.md) |
| **IR** (tour suggestions, „Routenfinder“, since 2026-11-15) | donald, own worktree of `onpath-app` (`main`) | `tools/suggest-poc/`, `tools/*suggest*`, `app/…/suggest/`, `docs/design/tour-suggestions.md` | [start.md](start.md) + its latest `docs/team/handoffs/handoff-ir-*.md` |
| **umbau** (the rebuild of 2026-11, `docs/design/architecture-plan-2026-11.md`) | donald, own worktree of `onpath-app` (`main`) | module structure, `:core` / `:state`, tests and CI layout – by the plan's phases | [start.md](start.md) + the plan |
| **umbau-hp** (second hand of the rebuild; first package: routing spike 5a) | hp / laptop, own worktree of `onpath-plan` (`main`) | `experiments/spike-routing-kmp/`, `docs/design/routing-kmp-spike.md`; later packages from [umbau] | [start.md](start.md) + the plan |
| **mehrtage** (multi-day tours, since 2026-11-16) | donald, own worktree of `onpath-app` (`main`) | `routing/PlanDays.kt`, `PlanCode.kt`, `PlanEdits.kt`, `docs/design/multi-day-tours.md`; planner (`web/`) and phone parts by agreement | [start.md](start.md) + `docs/design/multi-day-tours.md` |

Rules: one session per computer folder; two sessions never share a checkout (helpers use their own worktrees).
Backlog tags = session names (`[donald]`, `[IR]`, `[mehrtage]`, `[umbau]`, `[umbau-hp]`, `[laptop]` / `[cloud-plan]`, `[cloud-web]`, `[captain]`; the old `[cloud-app]` = donald).
Why it is set up this way, what was tested and what went wrong: [multi-agent-lernen.md](multi-agent-lernen.md).
