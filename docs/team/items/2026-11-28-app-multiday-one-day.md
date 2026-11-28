# App: a multi-day trip is guided one day at a time (owner 2026-11-28)

Tag: [donald]. After release 0.18.12, captain's order (hot files: PlanPanel.kt, MapScreen.kt, AppViewModel.kt).
Concept: `docs/design/multi-day-tours.md` §0, rows MD-0 … MD-11 (owner 2026-11-28 07:59 / 08:05 / 08:11 „kristallklar“;
requirements F-PLAN-16 … 22, F-NAV-08 … 12). Check of today's app with file:line:
`/mnt/project-files/mehrtage-konzept-pruefung-20261128T080040-b37e.md`.

Fits already (MD-2, MD-3 in part, MD-6): badge „8 Tage“ + day sheet in Meine Touren, one tap loads a day, „Tag N von M“, saving a day writes it back
into the trip, recorded tour ↔ day (tour_plan.day) with ✓ and the 200 m start/end rule.

To change (each with a test first):
1. **MD-4** – saving with the day's start or goal changed: notice „Start und Ziel geändert – wird als eigene Tour
   gespeichert“ and save as its own route; the trip stays (`state/…/plan/MultiDaySlice.kt` overwriteWhole ~321–350,
   `routes/SaveState.kt`).
2. **MD-3 / MD-5** – points changed in between keep the day reference for the recording (today any edit drops it –
   review fix 2026-11-27, MultiDaySlice followedDay); only a changed start / goal drops it.
3. **MD-5 / MD-9 / MD-10** – ✓ only when the day was navigated to its goal (arrival), not for every saved recording of
   the day; stopped before the goal → „angefangen“, tied to the day; ridden twice → ✓ stays, newest finished counts.
4. **MD-1 / MD-2** – out of the app: the whole-trip overview in the plan sheet (PlanPanel.kt 253/334/346,
   MultiDaySheet.kt, MultiDayPanelParts MultiDayOverview/TripProfile/PlanningChips), „Tag n fahren“; „‹ Alle Tage“ →
   the day list in Meine Touren (MultiDaySheet.kt 187, MapScreen.kt ~620); the day sheet loads the day directly
   (DayPickSlice.kt 67/89); Zuletzt / restart keep only the day (MultiDaySlice 163/359, MultiDayFlow 312); no re-split
   of the days on activity / pace change (MultiDayFlow 211/221). Several days in a row („Tag 3–4“): **hold – open for
   the owner** (below).
5. **MD-11** – no maps question: opening a day loads the maps of all days automatically in the normal map loading
   window with the line „Es werden alle Karten der Mehrtagestour geladen, damit du jederzeit offline loslegen kannst.
   Das kann einen Moment dauern.“ (replaces the „Ganze Tour / Nur Tag n / Später“ question, MultiDaySlice ~198).
6. **MD-8** – a changed day (same start / goal) syncs to the browser like any saved route (check it does after item 1).
7. Dead code: MultiDayHead, the „Als Übernachtung“ menu row, unused „Mehrtagestour planen“ strings in the app.
8. Release notes: the 0.18.12 draft (branch `release/0.18.12-notes`, `docs/release-notes/v0.18.12.md` lines 14–15 /
   50–51) says only „badge, a tap shows the days, one tap makes a day the plan, ✓“ – fits MD-2 / MD-6; no sentence
   about the number of days following activity / pace any more. Nothing to change there (checked 2026-11-28, read only).

## Code to align (step B)

Read on main 2026-11-28 (`origin/main` 60641c5a3). `file:line – what it says – contradicts`. S = `state/src/main/kotlin/com/onpath/app/ui`,
A = `app/src/main/java/com/onpath/app/ui`, ST = `state/src/test/kotlin/com/onpath/app/ui`, AT = `app/src/test/java/com/onpath/app/ui`,
DE = `strings/src/commonMain/composeResources/values-de`.

KDoc / code:
1. A/MultiDaySheet.kt:111–113 – the overview below the summary: profile with day borders, one row per day, tap = this day, hold = several in a row (scenario O) – MD-1 (no whole-trip view in the plan sheet); hold: open (b).
2. A/MultiDaySheet.kt:102 – overview title line „München – Venedig · 6 Tage“ in the plan sheet – MD-1.
3. A/MultiDaySheet.kt:185–190 – DayHeadLine „‹ Alle Tage“ calls onAllDays (brings the whole tour back as the plan) – MD-2 (back to the day list in Meine Touren).
4. A/MultiDaySheet.kt:208–215 – RideDaysButton „Tag 4 fahren / Tage 3–4 fahren / Tag wählen“ – MD-1 / MD-2 (the day sheet loads the day directly); range: open (b).
5. A/MultiDaySheet.kt:227–235 – tourMapsMessage: *Ganze Tour* / *Nur Tag n* / *Später* – MD-11 (no question, all days load automatically).
6. A/MultiDaySheet.kt:195 – „Karten für diesen Tag fehlen · Laden“ in the day head – MD-11 (all days' maps load; the line may stay as the robust net, decide in the rebuild).
7. A/PlanPanel.kt:142–146 – callbacks „a day row tapped / held (several days in a row), Tag n fahren, ‹ Alle Tage“ – MD-1 / MD-2.
8. A/PlanPanel.kt:253 – the foot's GO is RideDaysButton while `ui.days` is set – MD-1.
9. A/PlanPanel.kt:334 – DayHeadLine with onAllDays – MD-2.
10. A/PlanPanel.kt:346 – MultiDayOverview in the plan sheet – MD-1.
11. A/MapScreen.kt:620 – wires tapDay / holdDay / rideDays / allDays into the plan sheet – MD-1 / MD-2.
12. A/MultiDayPanelParts.kt:42–49 – MultiDayHead: title + Speichern + „Gesamte Reise“ (the whole trip's totals) – MD-1 (dead code too).
13. A/MultiDayPanelParts.kt:85–91 – PlanningChips „8 Tage ✎“ + limits in the plan sheet – MD-1.
14. A/MultiDayPanelParts.kt:115–120 – TripProfile „Höhenprofil · gesamte Reise“ – MD-1.
15. A/MultiDayPanelParts.kt:133–139 – MultiDayOverview: the plan sheet's multi-day body like the planner's panel – MD-0 / MD-1.
16. S/MultiDayUi.kt:20–24 – DaysUi carries the whole trip's rows, table and limits for the plan sheet – MD-1.
17. S/MultiDayUi.kt:27–30 – DayHeadUi: „‹ Alle Tage“ when the whole tour can come back – MD-2.
18. S/DayTableUi.kt:25–27 – the day table „for the plan sheet's overview (MultiDaySlice.daysUi) and My routes' day sheet“ – MD-1 (keep the day sheet use only).
19. S/DayTableUi.kt:62–73 – the multi-day panel's head / chips / profile texts „parity with the planner's panel“ – MD-1.
20. S/MultiDayFlow.kt:81 – selected = „one day, or several in a row“ – open (b).
21. S/MultiDayFlow.kt:205–211 – compute(): with day limits N follows a change of activity / T level / pace / marked trails (PlanDays.followLimits) – MD-1.
22. S/MultiDayFlow.kt:219–225 – show(): writes the followed N into the tour and saves – MD-1 (the phone changes the trip's day count).
23. S/MultiDayFlow.kt:236–240 – holdDay: the days from the picked one to this one („Tag 3–4“) – open (b).
24. S/MultiDayFlow.kt:263–268 – leaveDay(): „‹ Alle Tage: the whole tour (with the day's edits) is the plan again“ – MD-2.
25. S/MultiDayFlow.kt:300–318 – the kept state / restore keep the whole tour + marks + day, the restart can come back to the overview – MD-1 (keep only the day).
26. S/tour/TourFollow.kt:56–59 – followedDay(): null for „several days in a row, or the plan left the day“ – MD-5 (an edit in between keeps the day) / open (b).
27. S/plan/MultiDaySlice.kt:72–78 – class KDoc: „the overview of an opened tour, the map question at the first opening, Tag n fahren, ‹ Alle Tage, every change written back into the whole tour“ – MD-1, MD-2, MD-3/MD-4, MD-11.
28. S/plan/MultiDaySlice.kt:155–163 – reloadIfDayLost(): loads the stored whole tour as the plan again – MD-1 (keep the day, not the whole plan).
29. S/plan/MultiDaySlice.kt:193–194 – daysFor(): Save stores the days of the overview – MD-1.
30. S/plan/MultiDaySlice.kt:198–202 – askMapsOnce(): one question at the first opening, sizes of whole tour and day – MD-11.
31. S/plan/MultiDaySlice.kt:245–250 – tapDay / holdDay / rideDays of the overview – MD-1 / MD-2; hold: open (b).
32. S/plan/MultiDaySlice.kt:289–296 – followedDay(): null after any edit of the day – MD-5 (only a changed start / goal drops it).
33. S/plan/MultiDaySlice.kt:298 – allDays(): the whole tour is the plan again – MD-2.
34. S/plan/MultiDaySlice.kt:315–350 – overwriteWhole(): writes the day into the whole tour whatever changed – MD-3 / MD-4 (start or goal changed → notice + standalone copy).
35. S/plan/MultiDaySlice.kt:352–359 – keepWhole(): the whole tour with the day's change goes under Zuletzt – MD-1 (Zuletzt keeps the day).
36. S/routes/DayPickSlice.kt:67 – days not worked out → `openWhole(t)` (the whole route is the plan) – MD-0 (never the whole plan; say it and stay in the list?) – decide in the rebuild.
37. S/routes/DayPickSlice.kt:84–91 – ride(n): opens the whole tour, then rideDayAlong; fails → „the whole route is the plan“ – MD-2 (the day directly).
38. S/routes/DayPick.kt:62–70 – matches(): a recorded tour whose ridden ends lie within 200 m = the day ridden – MD-5 / MD-9 (✓ only on arrival at the goal; ends of a stopped tour must not count).

Strings (shared with the planner where noted – drop from the app only):
39. DE/strings_multiday.xml:10 `multiday_days_of` „Tage %1$s–%2$s von %3$s“ – open (b).
40. DE/strings_multiday.xml:11–13 `multiday_ride_day` / `multiday_ride_days` / `multiday_pick` „Tag n starten“, „Tage a–b starten“, „Tag wählen“ – MD-1 / MD-2.
41. DE/strings_multiday.xml:16–21 `multiday_maps_title` / `_wifi` / `_whole` / `_day` / `_later` – the maps question – MD-11 (new line needed: „Es werden alle Karten der Mehrtagestour geladen, …“).
42. DE/strings_days.xml:59, 63, 64, 66, 67 `days_planning`, `days_whole_trip`, `days_profile_whole_trip`, `days_chip_hours`, `days_chip_ascent` – used by the app's plan-sheet panel – MD-1 (the planner keeps them).
43. DE/strings_days.xml:4, 5, 32 `days_plan_tour`, `days_plan_as_tour`, `days_overnight_here` – planner only; check no app use remains (item 7).

Tests:
44. ST/MultiDayFlowTest.kt:60 `anotherProfileTheDaysFollowTheLimits` – MD-1.
45. ST/MultiDayFlowTest.kt:85 `aMultiDayTourOpensToItsOverview` – MD-1 / MD-2.
46. ST/MultiDayFlowTest.kt:142 `dayTwoIsFollowedAndAnEditGoesBackIntoTheTour` – MD-3 (add the start / goal case of MD-4).
47. ST/MultiDayFlowTest.kt:173 `severalDaysInARow` – open (b).
48. ST/plan/MultiDaySliceTest.kt:554 `rideDaysDoesNothingDuringATour…` – MD-1 / MD-2.
49. ST/plan/MultiDaySliceTest.kt:619, 636 `aDayRemembersItselfForTheRecordingOnlyWhileUnedited`, `aMovedOrRemovedPointDropsTheDayTooAndSeveralDaysNeverCount` – MD-5.
50. ST/plan/MultiDaySliceTest.kt:686 `saveWhileADayIsThePlanWritesTheWholeTourWithTheDaysChange` – MD-4 (no start / goal case).
51. ST/plan/MultiDaySliceTest.kt:930, 951, 964, 989 `allDays…` – MD-2.
52. ST/plan/MultiDaySliceTest.kt:1004 `aFollowedDaysUnsavedChangeKeepsTheWholeTourUnderZuletzt…` – MD-1.
53. ST/plan/MultiDaySliceTest.kt:1052, 1072, 1107, 1119, 1131, 1138 the maps question (`…AsksOnce…`, `onlyTheDayLoads…`, `laterLoadsNothing…`) – MD-11.
54. ST/plan/MultiDaySliceTest.kt:1279, 1296 `theMapShowsDayEndsOnlyInPlanAndWhileTheOverviewShows`, `theDayHeadCanLeaveOnlyWhenNoTourRuns` – MD-1 / MD-2.
55. ST/routes/DayPickTest.kt:67 `ridenDaysAreThoseOfRecordedToursNotInTheBin` – MD-5 / MD-9 (arrival).
56. AT/MultiDayScreenshotTest.kt:71, 74, 81 `overview`, `overviewNoDayPicked`, `mapsQuestion` – MD-1, MD-11 (website picture M01 uses `overview`).
57. AT/DayTableScreenshotTest.kt:82–85 `DayTable*GermanTest.overviewDayPicked` – the plan sheet's day table – MD-1.
58. app/src/androidTest/…/e2e/MultiDayTourE2ETest.kt:53, 85 – clicks „Tag 2 starten“ in the overview – MD-2 (pick the day in Meine Touren).

## Open for the owner (via captain)

- (b) Does „Tag 3–4“ (two or more days in a row, scenario O) stay on the phone? Captain's recommendation: yes, as one
  chosen section – MD-0 means „not the whole plan“. Code places marked „open (b)“ above wait for it.
- Answered 2026-11-28: (a) maps → MD-11 (all days, automatically, 08:11); (c) the three gaps → MD-8, MD-9, MD-10.
