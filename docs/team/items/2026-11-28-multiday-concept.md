# Multi-day tour – the owner's concept (2026-11-28 07:59)

Why this file: the concept was only in chats, never in `docs/requirements.md`, so the night's check and the captain's
first advice („the phone writes no multi-day data“) went the wrong way. This is the owner's text in rows; the rebuild
thread copies the rows into `docs/requirements.md` §3.3 / §3.5 (next free IDs) as its first commit.

| Row | Requirement |
| --- | --- |
| MD-0 | **Split (owner 08:05, „kristallklar“):** the browser planner stays as it is – it edits the whole trip, shows the days, jumps from the day overview into one day, re-plans it, jumps back to the multi-day view and the next day, plans the nights. The phone **guides through** the trip: open it in Meine Touren (it unfolds), one day goes into the phone's Plan sheet, changes only to that day, then go – **never the whole plan**. |
| MD-1 | A multi-day tour is **planned in the browser planner**. The phone does not plan one (no whole-trip view in the app's Plan sheet, no day count that follows activity / pace / route choice). |
| MD-2 | On the phone it lies in **Meine Touren as one whole tour** (badge „N Tage“). A tap shows its days; a tap on a day puts **that one day** into the app's Plan sheet to ride it. „‹ Alle Tage“ leads back to this day list. |
| MD-3 | In the Plan sheet the day can be changed (add / delete a point …). **As long as its start and goal stay, it stays part of the multi-day tour** (saving writes into the trip). |
| MD-4 | **Start or goal changed** → the chain no longer fits: the app says „Start und Ziel geändert – wird als eigene Tour gespeichert“ and saves a **copy as a standalone tour**; the trip's day stays as it was. |
| MD-5 | A day is **done** when it was navigated from its start **to its goal**. The recording stays tied to its day (also when the plan got a new point on the way). |
| MD-6 | On the phone the **plan and the ridden days belong together**: Meine Touren shows which days are done (✓) and which are not. Ridden days stay on the phone (the planner plans routes – VISION). |
| MD-7 | Backlog, later: the ridden day's GPX goes back to the browser (a12c241f2). Today it does not. |

## Gaps the captain sees (for the owner, one at a time, recommendation first)
1. MD-3: does a changed day (same start / goal) also go back to the browser planner? Recommendation: yes, it is the same trip (sync as today).
2. MD-5: stopped before the goal, or a shortcut → not done; the recording stays with the day as „angefangen“. Goal = within 200 m (as the ticks today).
3. A day ridden twice: the ✓ stays, the newest recording counts, the older one stays in Meine Touren.

## Found by the check (donald, `/mnt/project-files/mehrtage-konzept-pruefung.md`)
Missing: MD-4 note + copy; MD-5 day link lost after an added point; done only after navigating to the goal. Out: whole-trip
view in the Plan sheet, „‹ Alle Tage“ to the whole trip, automatic day count. Rebuild after release 0.18.12 and the tidy,
app only, after the owner's yes; hot files `PlanPanel.kt`, `MapScreen.kt`, `AppViewModel.kt`.
