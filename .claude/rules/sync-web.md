---
paths:
  - "app/src/main/java/com/onpath/app/sync/**"
  - "app/src/main/java/com/onpath/app/plansync/**"
  - "app/web-shared-files.txt"
  - "web/**"
  - "tools/sync/**"
  - "docs/design/web-sync.md"
  - "docs/design/web-planner.md"
---
# Plan sync and the browser planner (loads when you touch sync/, plansync/, web/ or the shared-file list)

| Path | What |
| --- | --- |
| `  sync/`, `plansync/` | Plan sync phone ⇄ browser (A-007, `docs/design/web-sync.md`): `sync/` = the pure code shared with the web planner (pairing code + keys, AES-GCM, item format with plans and imported tracks – `TrackCode`, ledger, merge "newer wins, older stays as a copy", `SyncRound`; `SharedCoreRuleTest`); `plansync/` = the app's side: `PlanSync` (background thread, triggers, never during a recording; `SyncPoll` – about every 60 s while the app shows My routes / Browser sync (owner 2026-10-30: not the Plan map)), `PlanSyncPass` (routes / imported tracks ⇄ items; a delete from outside → recycle bin), `HttpMailbox` (plan.onpath.earth/sync/v1), `SyncFiles` (files/sync/, atomic, not in Android backup); Settings → Browser sync. Test against the real mailbox in Node: `tools/sync/e2e.sh` |
| `app/web-shared-files.txt` | **The one list of app files the web planner compiles** (+ the app tests its build runs) – read by both builds; `SharedFilesRuleTest` checks each file exists and imports only what the web provides. A change to a listed file = shared code ("Web planner" CI runs; tell the laptop if it turns red) |
| *(other repo)* | **Website onpath.earth** lives in its own repo `happychriss/onpath.earth` (Cloudflare Pages, owner) – not in this repo; app sessions don't add website files here. The browser planner (`web/`, on `main` since 2026-11-06) is a different thing: its code stays here, it is served under onpath.earth (`plan.onpath.earth`, owner). onpath.earth is OnPath's main address (welcome, help, privacy, imprint); 44-2.de is parked. |

- Shared code (every file in `app/web-shared-files.txt`): a change is announced to the planner side (backlog); app release and planner deploy go out together after `tools/ci/release_check.sh` says GO.
