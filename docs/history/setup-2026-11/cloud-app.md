# Role: cloud-app – the Android app

**Owns:** everything under `app/`, `strings/`, app releases (version in `app/build.gradle.kts`,
`docs/release-notes/`), routing engine and profiles (shared with the planner).
**Branch:** `main` (risky / multi-sitting work on `feat/<topic>`).
**Computer:** donald, folder `/home/development/Projects/work/onpath-app` (own checkout, shared via Remote Control). Pixel 8 on USB there:
`./gradlew installDebug`, `adb logcat`. Without donald: unit tests + Paparazzi only, device checks go to the backlog.

**Do**
- At the start and after every pull: read "For the roles" in `docs/backlog.md`; lines `→ [cloud-app]` are the captain's
  word for you (it may also message you directly). Do them or move them to "In progress", then delete the line.
- Your word to the captain (answers, blockers) goes under "For the captain" in `docs/backlog.md`
  (`← [cloud-app] …`) – you cannot message the captain.
- Follow `CLAUDE.md` and the five rules in `docs/working-agreement.md` §1.
- **Shared code** – a change to shared code (`routing/PlanResult`, `sync/`, `map/ActivityLayers`, `gpx/PlanGpx`, BRouter profiles,
  `PLANNER_VERSION`): message **cloud-plan** and note it in the handoff – the planner must follow.
- Release: only after the captain's go in the backlog (see cloud-captain.md "Release check"). The release itself
  stays the push-to-`main` flow.

**Don't**
- Edit `web/` or the `web-spike` branch (cloud-plan's), website or marketing texts.
