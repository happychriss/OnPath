# Save paths – where a plan or a tour is stored, and where it can still get lost

Walked once through the code on 2026-11-06 (owner: "einmal die Abläufe durchsuchen"). Line numbers are of main 3844808b.
Tests that pin today's behaviour of the paths without a test before: `app/src/test/java/com/onpath/app/ui/SavePathsTest.kt`.

**Where things live:** the current plan (points, pins, saved link) is in `Prefs` only – written on every edit
(`AppViewModel.change`, :1337), read at start (:618). Saved plans, tours and imports are rows in `TourDb`; only those go
to the backup folder (`Backups.tourChanged`), "Export all" (`Backups.exportZip`, `Backups.kt:214`) and browser sync.

**The rule since 2026-11-06 (owner: "Zuletzt" instead of "Nicht gespeichert", like Komoot plus recent plans):**
nothing asks to save, nothing is lost.

| Path | What happens | Test |
|------|--------------|------|
| ✕ / ☰ **Neu planen** (also during a tour), a route / plan link / track **replaces** the plan, a track is **followed** during a tour | The plan leaves the map at once, no question. If it is not saved as it is (`planUnsaved`, ≥ 2 points) it is kept as a **draft** under My routes → Geplant → ZULETZT (`keepAsDraft`, rule `Drafts`, `RoutesState.keepDraft`); the same points twice make no second entry; **exactly one plan under Zuletzt** – the one worked on last, saved or not (owner 2026-11-12): a new draft sends the draft before it to the recycle bin (never gone for good), a plan saved as it is takes the place as that saved route (small "Gespeichert" tag, it stays under Gespeichert); the plan on the map is that row ("In Bearbeitung", `LivePlanRows`). One Undo step brings the plan back. During a tour the empty sheet says "Tour läuft weiter – …". | `SavePathsTest.replacingAPlanKeepsItUnderZuletzt`, `ClearPlanDuringTourTest`, E2E `CoreFieldE2ETest.aClearedPlanIsUnderZuletztSavedOrDeletedThere`, `…followingATrackDuringATourKeepsTheUnsavedPlanUnderZuletzt`, `CorePlanLinkE2ETest.unsavedPlanGoesUnderZuletzt` |
| **Los geht's** | A plan never saved is saved under the proposed name; a draft keeps its name and becomes saved; a saved plan changed before the start is saved as a copy "Name (2)" – never overwritten silently (`PlanAutoSave.atStart`). | `PlanAutoSaveTest` |
| End / arrival sheet | About the tour only: end sheet Tour speichern · Weiter · Löschen; arrival box (owner 2026-11-07) "Du bist angekommen." – Weiter aufzeichnen · **Tour beenden**, under them Tour löschen. Nothing asked about the plan. After the end (saved or deleted) the app is back at the start as after ✕ Neu planen: the same `clearPlan` (row above) – a plan saved as it is stays in My routes, one changed on the way goes under ZULETZT, one Undo brings it back; the empty sheet shows its guide. | `SavePathsTest.theEndOfATourGoesBackToTheStartTheNeuPlanenWay`, `TourFlowPropertyTest` (rule 6), `ArrivalBoxTest`, `TourEndPathsTest`, E2E `CoreFieldE2ETest.endTourSavesTheTourKeepsThePlanAndClearsTheMap` / `endTourDeleteBinsTheTourAndKeepsThePlan` / `arrivalEndTourBringsTheStartScreen` |
| My routes → ZULETZT | Each draft: **Speichern** (becomes saved: backup, browser sync; "✓ Gerade gespeichert" under GESPEICHERT) · **Löschen** (recycle bin, no question). Pale route sign, "Plan · date". | `TourSchemaTest` (schema 5, `tours.draft`), screenshots `toursRecent` |
| ☰ → Plan speichern | The name dialog; a saved plan changed → overwrite (its own question, the user started it). A draft saved this way replaces its draft. | `SaveDialogTest` |
| Delete in My routes (⋮) | Straight to the recycle bin, no question (owner 2026-11-06: the bin is the net, 30 days). | `BackupTest.recycleBinKeeps30Days` |
| Backup folder / Export all / browser sync | Saved items only – **no drafts** (`Backups` `!it.draft`, `DbTourStore` `!it.draft`). | `SavePathsTest.draftsAreInNoBackupAndNoSync` |
| App killed | The plan on the map lives in `Prefs` (every edit) and comes back; drafts are rows in `TourDb`. | `thePlanSurvivesTheProcess` |
| GPX export / import | Unchanged: planned item = plan GPX, recorded = track; an import is a track until "Als Plan nutzen". | `PlanGpxTest`, `todayEveryImportIsATrack` |

Gone with this rule: the "Nicht gespeichert ›" sign, "Plan speichern?" / "Plan leeren?" / "Plan ersetzen?" /
`ReplaceAsk` / `ClearAsk` / `DiscardPlanDialog` / `ReplacePlanDialog`, the delete question in My routes. The walk of
2026-11-06 below (paths 1–14, P1–P4) is history – it led here.

## History: the walk of 2026-11-06 morning

### The paths (then)

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

### Proposals (then) – all settled by the rule above

- **P1 (fixes 1, recommended):** the end-of-tour Save keeps the plan until the dialog is answered – either the Save
  dialog gets the plan's points when it opens (`SaveAsk` carries points, route line, pins, activity) and saves those,
  or the finished handler does not clear the plan while Save's dialog is open (clears after Save / Cancel). Then
  un-ignore `SavePathsTest.endOfTourSaveKeepsThePlan`. Simpler alternative: with the switch ON save at once under the
  proposed name, no name dialog (rename later in My routes).
- **P2 (2):** the ride summary gets the same "Plan auch speichern" switch as the plain end sheet (preset `EndPlanSave`).
- **P3 (6, 8):** before replacing an unsaved plan, the same "Replace current plan? · Save first" as in 5. **Done 2026-11-06:** ways 6 and 8 (and following a track during a tour, flow map M1) ask "Plan speichern?" (Speichern · Verwerfen · Abbrechen) before they replace an unsaved plan – `ReplaceAsk`, `SavePathsTest.replacingAnUnsavedPlanAsksFirst`, E2E `CoreFieldE2ETest.followingATrack*`.
- **P4 (9, 12):** an unsaved plan worth keeping is auto-saved as a draft ("Entwurf") before it is cleared after a tour
  – then Undo is no longer the only way back, also after a restart.

## Flow: follow a saved tour while a tour runs (owner 2026-11-06: "einfach zu viele Nutzereingaben")

The owner's screen (Pixel, 14:37, v0.17.1) and how it came about (events.log 14:31–14:38): app start → Planned tab →
"Rundtour ab Hilden (2)" → Los geht's (tour 132) → Navigate → Plan → Plan leeren (guidance ends, recording goes on) →
new point by a hold → POI "shelter" added → one more point (a new unsaved plan, followed) → Navigate ⇄ Plan twice →
My routes → "Demo" (a recorded tour) → **this screen** → "Tour nachfahren" → Navigate follows the GPX line (v5), 12 s later
a re-route "Neue Route. In 150 Metern rechts." (v6). The unsaved plan made on the way stays in the plan and meets path 1
at the end of the tour.

**Inputs for the goal "follow a saved tour" (from the code):**

| Step | During a tour (from locked Navigate) | Without a tour (from Plan) |
|------|--------------------------------------|----------------------------|
| 1 | slide "» Entsperren" (`SlideToUnlock`) | – |
| 2 | blue "Plan" (`ChangePlanButton`) | – |
| 3 | ☰ | ☰ |
| 4 | "Meine Touren" | "Meine Touren" |
| 5 | tab "Gemacht" (kept only while the app runs, `AppViewModel.routesTab`; Planned again after an app start – here the owner had used Planned) | tab "Gemacht" (same) |
| 6 | tap "Demo" (`requestOpen` → RECORDED → no question, `openTour` → `TrackPanel`) | tap "Demo" |
| 7 | "Tour nachfahren · Fahrrad" (`MapScreen.PlanBottom` onFollow: `followShown`, `closeShown`, `continueNavigation`) | "Los geht's · Fahrrad" (`startNavigation` → FOLLOW_TRACK, recording starts) |
| **Total** | **7 inputs, 0 questions** | **5 inputs, 0 questions** |

For comparison, a *planned* route tapped during a tour asks a box instead ("Mit dieser Route weiter / Tour beenden und
laden / Abbrechen", `TourLoad`) – also 7 inputs, but a different way for the same goal.

**The screen – 5 buttons + ✕ + ← for one decision:**

| Button | Where | What it does | Verdict |
|--------|-------|--------------|---------|
| "Weiter · Fahrrad" (green, full width) | tour card over the sheet (`TourRunningBar`, shown because `vm.shown != null`) | `continueNavigation` – back to Navigate with the **old** guidance (the plan), not this track | **double** with ← in the band, and competes with the other green button for the opposite meaning |
| "Pausieren" | tour card | pause | **double** – lives in Navigate's unlocked state |
| "Tour beenden" (red outline) | tour card (`TourPauseEnd`) | end question | **double**, and red – owner #27: Pause and End green, no red sign |
| "Als Plan übernehmen" (outlined) | track sheet | `planFromShown` – replaces the plan at once (path 8, an unsaved plan goes without a question) | keep, but blue (plan = blue) |
| "Tour nachfahren · Fahrrad" (green, full width) | track sheet | follow this track, back to Navigate | **the** main action |
| ✕ / ← | sheet / band | close the track / back to the tour | ok |

**Decided and built (owner 2026-11-06, onpath.private#33 – after the first F1 pictures: "zu kompliziert … unser Planblatt
nicht als Statusanzeige verwenden"):**

- During a tour a done tour / track tapped in My routes opens **no sheet**: a FRAGE box asks "Dieser Tour folgen?
  „‹name›“, 3,0 km. Deine Aufnahme läuft weiter." – Abbrechen · **Folgen** (green). Folgen = what "Tour nachfahren" did
  (`AppViewModel.answerTrackFollow` → `openTour(…, TrackThen.FOLLOW)`: `followShown`, `closeShown`, `continueNavigation`);
  Abbrechen = nothing loads, My routes stays (`RouteRules.openAsk` → `OpenAsk.FOLLOW_DURING_TOUR`, tests `TourLoadTest`,
  `RouteRulesTest`). Inputs during a tour: slide, Plan, ☰, Meine Touren, Gemacht, the tour, Folgen = 7 – but no screen to
  read in between and the same pattern as a planned route (one tap, one question).
- "Als Plan übernehmen" for a done tour / track lives in the row's ⋮ in My routes (blue, `TourMenuItems` onPlanFrom →
  `planFromTour`); the track sheet without a tour keeps "Tour nachfahren" / "Als Plan übernehmen".
- The way back to Navigate is the green "Navigation ›" at the bottom of the plan sheet (C); no tour card over a sheet.
- Still open (flow map M1): after "Folgen" the plan made on the way stays in Plan mode – an edit there switches the
  guidance back to the plan silently (`planAtTrackFollow`). The question does not change that.
- Not taken: F2 (Meine Touren in the band) and F3 (▶ per row).

Seen on the way (not save paths): the re-route right after "Tour nachfahren" (v5 → v6 in 12 s, "Neue Route") – the
owner was beside the start of the track; worth a look with the voice items.
