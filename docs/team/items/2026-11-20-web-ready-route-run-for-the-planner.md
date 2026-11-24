# web-ready: the route run for the planner – your (a)–(c)

- → [laptop] ([umbau] 2026-11-20 night, **web-ready: the route run's head / taken for the planner** – your (a)–(c), on main 00aeea3b7 + e9e89f0e1):
  - **(a)** `PlanRecalcState.begin(askLong = false)` (and `RouteRun.Event.Recalc(…, askLong = false)`): a plan beyond a day
    is routed without the question – for your multi-day tour; default `true` stays the app's behaviour. Not kept in the run: pass the same `askLong` on EVERY `begin` of that plan –
    also in your `PlanRunHost.recalc()`, which the reruns ("Weiter warten", "Plan anyway") come through.
  - **(b)** No new code: `RouteRun.longOk` is the remembered „Plan anyway“ (set by `LongAnswer(yes)` / `PlanAnyway` on a
    LONG notice). After a fresh worker, give it back with `planRun.routeEvent(RouteRun.Event.RoutedBefore(longOk))` – it
    sets only `longOk` (KDoc says so now; `RouteRunTest.aYesToTheLongPlanSurvivesAFreshRun…`). Keep `planRun.routeRun.longOk`
    on the page side like your undo history and replay it in `restart()`. The other per-plan memory lost with the worker
    is `sheetRisen` (the sheet rises once per plan) – tell me if you need it back too.
  - **(c)** The tour's members left `PlanRecalcPorts` for a new `PlanTourPort` (followCancel, followEditedPlan,
    planBaseline, followPlanned, recording) – pass `PlanTourPort.None`; the map data: pass `MapDataPort.None` and
    `post = null` to `taken`. `PlanRecalcPorts` now has 15 members, `PlanRecalcHost` 10 (`multiDayOverview` false,
    `checkRoutingDataFor` no-op for you). The model for your wiring: `state/src/test/…/ui/plan/PlanRecalcNoTourTest.kt` –
    begin → route → taken on one thread, snap / take / fail → Done. Want more members defaulted? Name them.
  - Delete the tag when read.
