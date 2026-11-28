# App: a multi-day trip is guided one day at a time (owner 2026-11-28)

Tag: [donald]. After release 0.18.12, captain's order (hot files: PlanPanel.kt, MapScreen.kt, AppViewModel.kt).
Concept: `docs/design/multi-day-tours.md` §0 (owner 2026-11-28 08:05 „kristallklar“). Check of today's app with file:line:
`/mnt/project-files/mehrtage-konzept-pruefung-20261128T080040-b37e.md`.

Fits already: badge „8 Tage“ + day sheet in Meine Touren, one tap loads a day, „Tag N von M“, saving a day writes it back
into the trip, recorded tour ↔ day (tour_plan.day) with ✓ and the 200 m start/end rule.

To change (each with a test first):
1. Saving with the day's start or end changed: notice „Start und Ziel geändert – wird als eigenständige Tour gespeichert“
   and save as its own route; the trip stays (`state/…/plan/MultiDaySlice.kt` overwriteWhole ~321–350, `routes/SaveState.kt`).
2. Points changed in between keep the day reference for the recording (today any edit drops it – review fix 2026-11-27,
   MultiDaySlice followedDay); only a changed start / end drops it.
3. ✓ only when the day was navigated to its end (arrival), not for every saved recording of the day.
4. Out of the app: the whole-trip overview in the plan sheet (PlanPanel.kt 253/333/346, MultiDaySheet.kt, MultiDayPanelParts
   MultiDayOverview/TripProfile/PlanningChips), several days in a row (hold), „Tag n fahren“; „‹ Alle Tage“ → the day list
   in Meine Touren (MultiDaySheet.kt 187, MapScreen.kt ~620); the day sheet loads the day directly (DayPickSlice.kt 67/89);
   Zuletzt / restart keep only the day (MultiDaySlice 163/359, MultiDayFlow 312); no re-split of the days on activity /
   pace change (MultiDayFlow 211/221); the „Ganze Tour“ maps question → maps of the chosen day (MultiDaySlice 198).
5. Dead code: MultiDayHead, the „Als Übernachtung“ menu row, unused „Mehrtagestour planen“ strings.
6. Release notes: the 0.18.12 sentence about the number of days following activity / pace is about the phone – drop it
   if 0.18.12 is not out yet, else say it in the next notes.
