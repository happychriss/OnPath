# Roles

Everything on the computers lives under `/home/development/Projects/work/`. A fresh session with the same role carries on
from the repo – never from another session's chat.

| Session | Runs on | Folder / repos | Reads |
| --- | --- | --- | --- |
| **donald** | donald (Pixel, emulator), Remote Control host | `onpath-app` (`main`) | [start.md](start.md) |
| **laptop** (planner + map cells, `[cloud-plan]` lines) | laptop (32 GB, map data), Remote Control host | `onpath-plan` (`main`) | [start.md](start.md) + [cloud-plan.md](cloud-plan.md) |
| **cloud-web** | cloud only | `onpath.earth`, `onpath.private` | [cloud-web.md](cloud-web.md) |
| **captain** | cloud only | all, mostly read-only | [captain.md](captain.md) |

Rules: one session per computer folder; two sessions never share a checkout (helpers use their own worktrees).
Backlog tags = session names (`[donald]`, `[cloud-plan]`, `[cloud-web]`, `[captain]`).
Why it is set up this way, what was tested and what went wrong: [multi-agent-lernen.md](multi-agent-lernen.md).
