# Restore the replaced plan (owner idea 2026-11-24, picture approved)

After a plan is replaced (round-trip suggestion taken, saved plan opened, …) a FRAGE box asks:
„Deinen alten Plan wiederherstellen?“ · detail „„‹old plan name›“ liegt jetzt unter Zuletzt.“ ·
buttons **Behalten** / **Wiederherstellen** (EDIT tone, blue).

- Wiederherstellen swaps: the old plan back on the map, the new one goes to Zuletzt (with its own activity + T level).
- Behalten: as today.
- Owner 2026-11-24: picture „Ja, so“ (`onpath.private/ideen/2026-11-24-alten-plan-wiederherstellen/`); the box stays
  until answered like every FRAGE box (his words: „wäre ‚bis ich antworte‘ nicht normal“) – donald noted the trade-off
  (one box at a time, a map question waits behind it); he may still change it.
- App + planner, later. Not started. Needs the readers' Zuletzt change (own activity) on main first.

## Built 2026-11-24 (donald)
`wip/donald-2026-11-24-restore-ask` 4d9c8d90f, on top of `wip/donald-2026-11-22-stitch-readers-all` 1a1a81293 – goes to main
with the readers, never before. Gate full green, androidTest compiles; NOT checked: device, E2E, a review.
- Owner's lines (boxes 2026-11-24): „„‹name›“ ist gesichert.“ (EN “‹name›” is kept safe.) – not „liegt unter Zuletzt“,
  because Zuletzt shows the new plan while it is on the map; a saved route left unchanged: „„‹name›“ ist gespeichert.“
- `state/…/routes/RestoreAsk.kt` (listed web-ready), set in `SaveState.keepAsDraft(…, asks)`; asks after a saved / Zuletzt
  plan opened, a plan link, a track's plan / round trip; never during a tour, never for < 2 points, not on Los geht's from
  My routes, not for a followed multi-day day. Goes when the old entry leaves My routes (saved new plan, Neu planen,
  deleted), a newer replacement, a tour start; memory only (a restart drops it). Answer: `RoutesOpenState.answerRestore`.
- Undo alone does not do it (no activity / T level, no saved link, the new plan not kept). `RestoreAskFlowTest` 11 tests.
- Planner: show `RestoreAsk.box(save.restoreAsk, list, touring)` as a FRAGE box; Restore opens the entry with
  `ask.activity` / `ask.sac`, then `ask.link`. Pictures: `onpath.private/ideen/2026-11-24-alten-plan-wiederherstellen-gebaut/`.

## On main 2026-11-25 (donald, night)
App box on main 2e754723f (readers + main merged, gate full green 1938 app / 2037 core / 1031 state). Open: the planner part (above) for the laptop; a device check and E2E with 0.18.10.
