# Save paths – where a plan or a tour is stored, and where it can still get lost

Walked once through the code on 2026-11-06 (owner: "einmal die Abläufe durchsuchen"). Line numbers are of main 3844808b.
Tests that pin today's behaviour of the paths without a test before: `app/src/test/java/com/onpath/app/ui/SavePathsTest.kt`.

**Where things live:** the current plan (points, pins, saved link) is in `Prefs` only – written on every edit
(`AppViewModel.change`, :1337), read at start (:618). Saved plans, tours and imports are rows in `TourDb`; only those go
to the backup folder (`Backups.tourChanged`), "Export all" (`Backups.exportZip`, `Backups.kt:214`) and browser sync.

## The paths

| # | Path | What happens (code) | Test | Risk |
|---|------|---------------------|------|------|
| 1 | Plan made on the phone → tour → **End tour, "Plan auch speichern" ON** | `endTour` (:3064) stops the service and opens Save's name dialog (`requestSave`). Milliseconds later `RecordingService.finished` clears the plan (:1117, "the plan leaves the map, saved or not"). The dialog's Save → `saveRoute` (:2541) reads `waypoints` **now** = 0 points → `return`, no message. Same for the arrival dialog's Save (`onArrived` → `askEndTour(saveIfNoRides)` → `endTour`) and for "Geänderten Plan auch speichern" of a saved plan (overwrite: `savedId` is -1 after the clear → `saveRoute` → return). | `SavePathsTest.todayTheEndOfTourSaveReadsAnAlreadyClearedPlan`; reproduced: `endOfTourSaveKeepsThePlan` (@Ignore) | **LOSS** – the plan is not in My routes; only Undo (memory only) brings it back. This is very likely the owner's field report of 2026-11-06; the preset ON (`EndPlanSave`) does not fix it. |
| 2 | End tour **with rides found** (bus, lift, car) | `askEndTour` (:3110) → `EndAsk.Rides` → `RideSummaryDialog` (`MapScreen.kt:231`): no plan switch, no preset; `endTourWith` → `endTour` with `saveRouteAtEnd` false. Then the plan is cleared (as in 1). | `todayTheRideSummaryOffersNoPlanSave` | **Gap** – an unsaved plan is never offered; Undo only. |
| 3 | Saved plan **changed during the tour** | Plan sheet during a tour: "Save route" → `requestSave` → dialog kind OVERWRITE (`askSave` :2470) → `overwriteSaved` (:2578); changed in the browser meanwhile → saved as a copy (`SaveRule.keepsOtherEdit`). At the end the switch "Geänderten Plan auch speichern" is OFF (owner) – ON runs into 1. | `SaveDialogTest.aSavedRouteShowsItsNameAndIsOverwritten`, `EndPlanSaveTest`, `PlanSyncTest` (SaveRule) | ok during the tour; at the end see 1. |
| 4 | **✕ Neu planen / Plan leeren** (also during a tour) | `requestClear` (:1913): unsaved or touring → "Plan leeren?" with Save (`discardSaveFirst` :1925 = Save's dialog, clear only after a successful save). | `ClearPlanDuringTourTest`, `SaveDialogTest` | ok |
| 5 | Open a **saved route from My routes** (no tour) | `requestOpen` (:2666) → unsaved plan → "Replace current plan?" with "Save first". | `TourLoadTest`, `RouteRulesTest` | ok |
| 6 | Open a saved route **during a tour → "Mit dieser Route weiter"** | `answerTourLoad` (:2678) → `openTour(duringTour)` → `change { clear(); addAll(pts) }`; the unsaved-plan check of 5 is not asked (`openAsk` returns BLOCKED_DURING_TOUR first). "Tour beenden und laden" → end question → 1. | `TourLoadTest`, `todayContinueWithALoadedRouteReplacesAnUnsavedPlanWithoutAsking` | **Gap** – an unsaved plan is replaced; Undo only. |
| 7 | **Plan link** (browser / shared link) | `openSharedPlan` (:3798): unsaved plan → asks first; during a tour it waits until after the tour. | `PlanLinkTest` | ok |
| 8 | Shown track → **"Als Plan nutzen"** | `planFromShown` (:3885): replaces the plan at once (plan GPX: its route points; other tracks: TrackToPlan). | `todayPlanFromATrackReplacesThePlanWithoutAsking`, `PlanGpxTest.reimportRebuildsTheStops` | **Gap** – an unsaved plan is replaced without a question; Undo only. |
| 9 | **App killed / process death** mid-plan or mid-tour | Plan in `Prefs` on every edit → back at the next start (route recomputed). Undo history is lost. A tour killed mid-way → "Unfinished tour" (Continue · Delete · Save, `saveUnfinished` :3413) – the plan is not cleared there. | `thePlanSurvivesTheProcess`, `StartSmokeE2ETest` | ok (the Undo safety net of 1, 2, 6, 8 is gone after a restart) |
| 10 | **GPX export** | `exportTour` (:4128): planned item = plan GPX (`PlanGpx.export`: line + route points as `<wpt>` with names, pins as `<type>`); recorded / imported = the track (`GpxWriter`, rides as chosen). | `PlanGpxTest`, `exportPlannedIsThePlanGpxTourIsTheTrack` | ok – the owner's "always the route, never the plan?": a planned item exports the plan. |
| 11 | **GPX import** (plan GPX or any track) | `importGpx` (:4170): always `TourKind.IMPORTED` (Tracks tab), shown as a track; a plan GPX becomes a plan again via "Als Plan nutzen" (exact route points, names, pins). | `GpxParserTest`, `SportTypeTest`, `PlanGpxTest`, `todayEveryImportIsATrack` | ok; note: an imported plan is not in "Planned" until made a plan and saved. |
| 12 | **Backup folder / Export all / Restore** | Saved items only (`state == "done"`); restore dedupes by uuid, skips the bin. | `BackupTest` | ok; an **unsaved plan is in no backup** (`todayAnUnsavedPlanIsInNoBackup`). |
| 13 | **Browser sync** | Only stored routes travel; the open plan is never sent or replaced. Phone save over a route changed in the browser → copy. | `PlanSyncTest`, `CorePlanSyncTest` | ok |
| 14 | **Delete** in My routes / recycle bin | Bin 30 days, restore, delete now; deleting the open plan's route keeps the plan (Save becomes a new route). | `BackupTest.recycleBinKeeps30Days` | ok |

## Proposals for the owner (no behaviour changed yet)

- **P1 (fixes 1, recommended):** the end-of-tour Save keeps the plan until the dialog is answered – either the Save
  dialog gets the plan's points when it opens (`SaveAsk` carries points, route line, pins, activity) and saves those,
  or the finished handler does not clear the plan while Save's dialog is open (clears after Save / Cancel). Then
  un-ignore `SavePathsTest.endOfTourSaveKeepsThePlan`. Simpler alternative: with the switch ON save at once under the
  proposed name, no name dialog (rename later in My routes).
- **P2 (2):** the ride summary gets the same "Plan auch speichern" switch as the plain end sheet (preset `EndPlanSave`).
- **P3 (6, 8):** before replacing an unsaved plan, the same "Replace current plan? · Save first" as in 5.
- **P4 (9, 12):** an unsaved plan worth keeping is auto-saved as a draft ("Entwurf") before it is cleared after a tour
  – then Undo is no longer the only way back, also after a restart.
