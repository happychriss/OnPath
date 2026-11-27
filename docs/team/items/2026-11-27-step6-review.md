# Step 6 review – `feat/wait-step-bar` @ d1b6e53c0 (second model, donald / Suchkategorie, 2026-11-27 night)

Read: `93b11e62e` step bar, `07595f373` no creep, `807028e2f` one motion (WaitMotion), `d1b6e53c0` plan spacing. Not
fixed here – for the Wartefenster thread. Order = how much it matters.

## 1 – Must fix: the filled pieces are not the real legs (VISION „nie eine erfundene Prozentzahl“)
`WaitWindowUi.filledPieces` = `shownPieces * percent / 100` while `measured`. `percent` is `RouteWait`'s OneBar value over
the **whole** wait: the legs are only `1 - memory.routeCheck` of it, and `bar.next(...)` moves between reports (stepLeft
estimate). So:
- **While „prüfen“ runs** (`checking` → `measured = true`, `piecesDone = total`): with a check share of 0.2 the bar shows
  ~80 % → 13 legs routed, about 10 pieces filled; the last pieces then fill over the check time – pieces that are done
  look not done, and fill by time.
- **While a leg runs with the router's share**: filled = total · legs-share · frac, i.e. fewer pieces than legs routed
  (and the time-driven part of `bar.next` can push it either way).
- The commit says „finished pieces are real legs“ – true only for `measured = false`.
Suggestion: `filledPieces = doneShown + (router's share of the running leg, scaled to its piece)`; while checking all
pieces full and the check shown as its own state (its row „prüft“, or an indeterminate sliver) – never `percent` of the
whole. Test: `stepBarNeverMakesUpProgress` should assert `filledPieces >= doneShown` always and `== shownPieces` while
checking (both fail today with a check share > 0).

## 2 – Should fix: the map wait window still shows „8 %“
`pieces = 0` (map loads) keeps the bar with its number. VISION 2026-11-27: the app shows the steps and how long it runs,
never a made-up percentage. The map's percent mixes the server's build estimate and learned step shares (`OneBar`,
`StepShares`) – that is an estimate, not a measurement. Not step 6's own scope, but the same rule; owner decision only if
the bytes part (real) should keep a number.

## 3 – Should fix: comment and behaviour disagree on the direction arrow
`WaitMotion` KDoc lists „the turning direction arrow at the position“ among the signs that stand still; the commit message
and the captain's rule say position and direction arrow are live data and move on. Code leaves the arrow alone (correct) –
fix the KDoc so nobody „completes“ it later.

## 4 – Check: `BesideSign` returns nothing while a window is up
The map-load pill disappears (not just stands still) – its tap target goes for the time of the window and comes back
after. Fine if intended („stand still / hide“); otherwise draw it without animation. Same for `RoutingRing` (hidden).

## 5 – Parity: the planner
`WaitWindowUi.pieces / piecesDone / measured` are in `WaitRules.kt` (web-ready, the planner compiles it), but `WebWait`
does not pass pieces – the planner keeps the bar (and its percent). Backlog has the parity line for the step bar
(laptop) – include finding 1's rule there, or the planner copies the same mismatch.

## 6 – Fine
- No percent in the route window with pieces; TalkBack gets the range (`progressBarRangeInfo`), no number read out.
- One motion: every decorative spinner asks `WaitMotion.othersMove()`; `OneMotionRuleTest` pins the list. Live data
  (position, arrow) untouched. Counter never below 0, `DisposableEffect` registers / unregisters.
- `PlanSpacing` (+8 / +10 dp) in its own file; `PlanPanel.kt` net +0 lines (cap 814 holds). Rebase note: main now has
  `ThumbGoalRow` (486763c7f) in `ThumbHead` – no overlap with `PlanBody` / `PlanHead`, but re-record the plan shots after
  the rebase (the goal row + the spacing together, e.g. `planOnePoint*`, `planPointsOne`).
