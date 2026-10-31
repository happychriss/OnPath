# Roles

On both computers (donald and the laptop) everything lives under `/home/development/Projects/work/`.

Sessions come and go; roles stay. Every Claude session working on OnPath has exactly one role. At the start the owner
says it ("you are cloud-plan"); the session reads its file here, then `docs/working-agreement.md` §8 (roles and
channels), `docs/project-status.md` and `docs/backlog.md`. A fresh session with the same role carries on from the
repo – never from another session's chat.

| Role | Owns | Runs on | Folder | Repos |
| --- | --- | --- | --- | --- |
| [cloud-app](cloud-app.md) | Android app | donald (desktop PC), via Remote Control | `/home/development/Projects/work/onpath-app` | `OnPath` (`main`) |
| [cloud-plan](cloud-plan.md) | browser planner, map cells | laptop (32 GB), via Remote Control | `/home/development/Projects/work/onpath-plan` | `OnPath` (`web-spike`) |
| [cloud-web](cloud-web.md) | website onpath.earth, marketing | cloud only | – | `onpath.earth`, `onpath.private` |
| [cloud-captain](cloud-captain.md) | overview, priorities, release go | cloud only | – | all, mostly read-only |

Rules:
- One session per role. Session title = role name (that is the name other sessions message).
- One folder per session: two sessions never share a git checkout.
- A session stays tied to the first computer it attaches to. Computer off → start a fresh cloud session with the same
  role (it works without the computer: no device, no local map data).
- Claim tag in `docs/backlog.md` = role name: `[cloud-app]`, `[cloud-plan]`, `[cloud-web]`, `[captain]`.

## Setup and migration (owner, once)

1. Create the private repo `happychriss/onpath.private` (done: `marketing/`, `ideen/`, `inbox/`, `backlog.md`); add it as a source for
   cloud-web and cloud-captain in claude.ai/code.
2. donald: `git clone` OnPath to `/home/development/Projects/work/onpath-app`; Claude desktop app → Settings → Claude Code → Remote Control →
   share that folder.
3. Laptop: Claude desktop app, `git clone` OnPath to `/home/development/Projects/work/onpath-plan` (checkout `web-spike`), share it; copy the
   map data next to it (`/home/development/Projects/work/onpath-data/`).
4. Every running session: push everything, write its handoff.
5. Rename the sessions to the role names and tell each: "Pull `main`, you are `<role>`, read `docs/roles/<role>.md`."
   Start the captain as a fresh session.
6. Test: the captain messages each role "test", each answers (checks that a session attached to a computer still
   sends and receives).
7. cloud-web copies the Drive folder into `onpath.private`; from then on Drive is read-only history.
