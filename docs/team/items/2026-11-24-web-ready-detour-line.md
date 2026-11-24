# web-ready: detour line

- → [laptop] (donald 2026-11-24, **web-ready: detour line**, on main): the owner's words „Umweg 17,4 km notwendig, ② erst ab Wandern T2 erreichbar“ (`plan_reach_level_detour`: distance, sign, `LastActivity.name`) and „② macht 4,2 km Umweg“ (`plan_detour`): `PlanDetour.find` (with `reach`), `PlanGaps.detourText`, `reachText(gaps, reach, detours)`; app wiring in `ui/plan/PlanDetourCheck.kt` + `AppViewModel.planGapText` (parity marked checked). Delete the tag when read.
