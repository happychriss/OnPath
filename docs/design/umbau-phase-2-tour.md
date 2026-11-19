# Rebuild phase 2 – the tour pieces 16–19 ([umbau], 2026-11-19)

Working paper for `umbau-phase-2.md` §4.1 rows 16–19 and §4.3. Analysis only – no code changed, no Gradle run.
Measured by reading on main @ 42850ec1e (`AppViewModel.kt` 4,038 lines; all line numbers below are of that commit –
piece 12 lands before the tour pieces, so **find everything by name**). Read in full: every tour member of
`AppViewModel.kt` (the lines of §1), `RecordingClient.kt`, `TourController.kt`, `FollowStore.kt`, `TourPlanCopy.kt`, the
service's `startCommand` / `finish` / `onDestroy` / `loadFollow` / companion, the hosts of the moved slices, the test kit
fakes. Read by header comment and name only: the 35 methods of `CoreFieldE2ETest` (bodies not read), `RouteRun`'s
reducer, `PlanRouteSlice`. "Not verified" marks what I could not prove by reading. Deleted with `umbau-phase-2.md`.

## 0. In one table

| Question | Answer |
| --- | --- |
| Tour lines still in the view model | ≈ 1,130 (28 % of the file): activity check 215, tour end 310, follow 390, session 215 |
| Lines that talk to the service | 19 command sites + 11 read / acknowledge sites + 5 file hand-overs (§2) – the design's "30" holds |
| Order rules | **36** (§3). Proven today on real view-model code: **0** (it cannot run on the JVM). A source pin or a flow test through a host: 12. Only the pure rule underneath is tested, not the order: 15. **No JVM test of any kind: 9**; of those **6 have no E2E either** (and a 7th on two of its three paths) |
| Recommended cut | **six landings**: 16a (contract split + fakes + order pins, no body moves) → 16b `ActivityCheckSlice` → 17 `TourEndSlice` → 18a `TourFollow` (follow state + writes + plan copy) → 18b `TourEdits` (what a plan edit means for the tour) → 19 `TourSession` |
| Lines out of the view model | 16b −170 · 17 −230 · 18a −170 · 18b −145 · 19 −155 → **≈ 3,170** after 19 (the design's 1,335 assumed more from earlier pieces; forwarders + hosts stay until 21) |
| Where the pattern of pieces 1–15 does not fit | (1) the collectors' launch position inside the big `init` is behaviour (first pass runs inline); (2) the mode flip at start must stay *after* `recalc()`; (3) prerequisites outside `ui/`: `RideApply` + `RideStore` and the check sheet's types sit in `:app`; (4) the fake's tour end is in the wrong order (§5) |

## 1. The inventory

Thread: **M** = main (`viewModelScope` = `Dispatchers.Main.immediate`; a `launchSafe` without a context runs inline until
its first suspension), **IO**, **PC** = `planCopyDispatcher` (IO, one at a time), **R** = `planRoute.threads.routing`,
**CR** = `…threads.check`, **D** = `Dispatchers.Default`. Scope is `viewModelScope` everywhere. `rec` = `recState.value`.

### 1.1 Piece 16b – the activity check sheet

| Member (lines) | Reads | Writes / calls | Thread, job |
| --- | --- | --- | --- |
| `activityCheck` 2904, `checkJob` 2906, `checkCancel` 2907, `checkSession` 2908, `checkRest` 2909, `checkHikeSac` 2921, `sacFor` 2918 | `sac` | – | M |
| `openActivityCheck` 2928–2944 | `rec.nav.session`, `activity`, `sac`, `tracker.location.value` (only "is there a fix") | `closeActivityCheck`, `checkSession`, `activityCheck` | M |
| `startCheck` 2951–2983 | `checkSession`, `checkRest`, `tracker.location` (value, else **suspends for the first fix**; altitude), `rec.nav.progressM` | `cancelCheck`, `checkCancel`, `activityCheck`, `ActivityCheck.rest` on **D**, `checkRow`, `applySwitch` | M; job `checkJob` |
| `cancelCheck` 2986–2989 | – | `checkCancel.cancel()` then `checkJob.cancel()` | M |
| `checkRow` 2992–3009 | `routingEngine()`, `planFactors(a)` | `eng.route(…, RouteLimits(TIMEOUT_MS, cancel))` | **CR** |
| `chooseHikeLevel` 3017–3041 | `activityCheck`, `checkSession`, `rec.tourId` | free recording: `closeActivityCheck`, `selectActivity`, `selectSac`, `db.updateActivity` (**IO**, own launch); else `checkHikeSac`, `activityCheck`, `startCheck` | M |
| `updateCheck` 3044–3047, `chooseActivity` 3053–3068 | same | free: `closeActivityCheck`, `selectActivity`, `db.updateActivity` (**IO**); else `applySwitch` / `startCheck` | M |
| `applySwitch` 3075–3099 | `checkSession`, `checkRest`, `followFromPlan`, `waypoints.size`, `mode` | `closeActivityCheck`, `switchPlanActivity` **or** `NavSession.start`, `followStore.propose`, `recording.proposeSwitch`, `enter(NAVIGATE)` | M |
| `switchPlanActivity` 3107–3118 | `rec.tourId` | `sac`, `prefs.sac`, `routeEvent(FollowNext)`, `selectActivity`, `db.updateActivity` (**IO**) | M |
| `closeActivityCheck` 3121–3126 | – | `cancelCheck`, `checkRest`, `checkHikeSac`, `activityCheck` | M |

Not in 16b (the TSV put them under ActivityCheck): `navActivitySeen` 2911, `planBaseline` 2913, `planFollowed` 2915,
`syncNavActivity` 3129–3138 → **18a** (they are follow state); `switchToPlan` 3141 → **19**.

### 1.2 Piece 17 – the tour's end

| Member (lines) | Reads | Writes / calls | Thread, job |
| --- | --- | --- | --- |
| the `finished` collector 1461–1498 (in the big `init`) | `recording.finished`, `db.getTour` (**IO**), `isShortRecording`, `DemoLocation.endWithTour()` | `recording.finishedSeen()`, `tour.finishedOutcome / closeFinished / afterShortBin / showFinished / afterFinished`, `binFinishedTour`, `nameRecordedTour`, `recording.takeFinishedParts()`, `refreshTours`, `clearFollow`, `demo.navSimLine`, `enter(PLAN)`, `afterTourEnded` | M with IO hops; lives as long as the scope |
| `afterTourEnded` 3151–3156 | `waypoints` | `clearPlan`, `guidanceEndedByEmptyPlan = false`, `kickThumbs` | M |
| `binFinishedTour` 3159–3163, `isShortRecording` 3169–3176 | `db.loadSegments` | `db.setDeleted`, `Backups.tourChanged` | **IO** |
| `shortBinnedTour` 3179, `undoShortRecording` 3186–3202, `closeShortRecording` 3205–3207 | `tour.shortBinned`, `db.getTour`, `tourTimes` | `routes.restore` (**IO**), `Backups.tourChanged`, `tour.showFinished`, `nameRecordedTour`, `refreshTours`, `sayError` | M + IO |
| `keepRecordingAfterArrival` 3210, `endTour` 3216, `endAsk` 3222, `endTourAndDelete` 3300, `dismissEndAsk` 2697, `onArrived` 3309 | – | `tour.end(delete)`, `stopRecording`, `tour.arrivalAsk`, `tour.endAsk` | M |
| `askEndTour` 3232–3251 | `rec.tourId`, `rec.nav.session.activity`, `db.loadSegments`, `db.getTour` (**IO**), `activity` | `arrivalAsk`, `endAsk`, `endTour` | M + IO |
| `endTourWith` 3254–3263 | – | `endAsk`, `RideApply.choose(TourDbRides(db), …)` (**IO**), `endTour` | M + IO |
| `editRides` 3266, `openEditRides` 3268–3280, `applyEditRides` 3282–3294 | `db.loadSegments`, `shown` | `RideApply.edit` (**IO**), `Backups.tourChanged` (on IO), `sayError`, `refreshTours`, `openTour` | M + IO |
| `clearInterruptedNote` 3533, `continueRecording` 3535–3538 | – | `InterruptedLog.chose`, `TourInterruptedReceiver.cancelNote(app)`, `tour.continueTour` | M |
| `unfinishedRides` 3541, `askSaveUnfinished` 3547–3563, `backToUnfinished` 3566 | `rec.tourId`, `db.loadSegments`, `activity` | `tour.unfinished`, `enter(NAVIGATE)`, `tour.unfinishedRides`, `saveUnfinished` | M + IO |
| `saveUnfinished` 3572–3592, `deleteUnfinished` 3595–3614 | `tour.unfinishedRides`, `rec.tourId`, `activity` (read on IO) | `InterruptedLog.chose`, `tour.closeUnfinished`, `enter`, `db.updateStats / setDeleted`, `RideApply.choose / finish`, `Backups.tourChanged`, `refreshTours` (hop to M) | M, body on **IO** |
| `pauseRecording` 3616, `stopRecording` 3618–3622 | – | `tour.pause`, `routeBackAsk`, `planSize`, `tour.stop` | M |
| forwarders already there: `unfinished`, `unfinishedTrack`, `finishedTour`, `finishedTimes` 1229–1238, `openSavedTour` 1245, `closeSavedTour` 1253, `arrivalAsk` 279, `newPlanNote` 188 | `tour.*` | `refreshTours`, `routes.showSaved` | M |

Stays in the view model behind the host: `nameRecordedTour` 2727–2753 (search index, `RouteNamer`, `DemoLocation.TOUR_PREFIX`
– left by piece 14), `tourTimes`, `kickThumbs`, `clearPlan` (already `planEdit`).

### 1.3 Piece 18a – follow state and its writes

| Member (lines) | Reads | Writes / calls | Thread |
| --- | --- | --- | --- |
| `followStore` 1110; `followName` 1172, `followLine` 1175 (restored from `follow.json` in the initialiser), `followOriginal` 1178 (from `nav-session.json`), `navVersion` 1180, `followFromPlan` 1313, `guidanceEndedByEmptyPlan` 1316, `followIsTrack` 1330 (from `prefs`), `planAtTrackFollow` 1332, `planBaseline` 2913, `followHistory` 773, `lastSessionToken` 3507 | files, prefs | – | M (construction) |
| `setFollow` 3485–3503 | `activity`, `sac`, `rec.nav.version`, `navVersion` | `NavSession.start`, `followStore.appFollows`, the follow state, `prefs.followIsTrack`, `recording.followChanged(checkNow)` | M |
| `followAgain` 3361–3377 | – | `followStore.appFollows`, follow state, `copyFollowedPlan`, `recording.followChanged()` | M |
| `followPlanned` 3383–3391 | `route`, `waypoints`, `savedName`, `stopName`, `pinnedIndices` | `setFollow`, `followFromPlan`, `copyFollowedPlan` | M |
| `followShown` 3473–3479 | `shown`, `waypoints` | `setFollow`, `followFromPlan`, `planCopy { notFollowed() }`, `planAtTrackFollow` | M |
| `clearFollow` 3509–3522 | `rec` | `splitRequests.invalidate`, `planCopy { notFollowed() }`, follow state, `prefs.followIsTrack`, `followStore.appEnds`, `recording.followChanged()` | M |
| `newSessionToken` 3506, `planFollowed` 2915, `planReplacesTrack` 2841, `planGuides` 2867 | follow state | – | M |
| `planCopyDispatcher` 1268, `tourPlanCopy` 1269–1276, `planCopy` 1277, the tour-id collector 1281–1287 | `recState`, `db.getTour` | `db.setTourPlan`, `Backups.tourChanged` | **PC**; collector on M |
| `recopyFollowedPlan` 3398, `copyFollowedPlan` 3403–3412, `tourPlanNow` 3415–3423, `setSaved` 2653–2657 | `rec.nav.session`, `savedId`, `savedName`, `savedStops`, `route` | `planCopy { followed(…) }`, `plan.setSaved` | M → PC |
| `syncNavActivity` 3129–3138, `navActivitySeen` 2911 | `activity` | `followOriginal`, `routeEvent(AdoptBaseline)`, `selectActivity` | M (from the `state` collector) |
| `sayEvent` 1686, `myPosition` 1691 | `rec`, `tracker.location`, `rec.track` | `recording.say` | M |

### 1.4 Piece 18b – what a plan edit means for the tour

| Member (lines) | Reads | Writes / calls | Thread |
| --- | --- | --- | --- |
| `pendingSplit` 1324, `splitBefore` 1326, `splitRequests` 1328, `followCancel` 1307 (`@Volatile`), `passedPoints` 1717, `passedTourId` 1718, `loadedOnTheWay` 1721 | – | – | M |
| `tourPassed` 1699–1710 | `rec` (track, nav), follow state, `myPosition`, `activity` | `passedPoints`, `passedTourId` | M |
| `splitOf` 1724–1735 | `loadedOnTheWay`, `tourPassed` | `splitRequests.invalidate`, `pendingSplit`, `splitBefore` | M |
| `answerRouteBack` 1747–1773 | `routeBackAsk`, `rec`, `myPosition`, `waypoints` | `plan.replace`, `cellsForNewPoints`, `prunePinned`, `routeEvent(PlanEdited)`, `recalc`, `clearFollow`, `guidanceEndedByEmptyPlan`, `noPositionForEdit` | M |
| `endGuidanceIfPlanEmpty` 1780–1785, `oneLeftGoesFromHere` 1793–1831 | `plan.oneLeft(…)`, `myPosition`, follow state | `clearFollow`, `guidanceEndedByEmptyPlan`, `pendingSplit`, `plan.empty / fromHere`, `routeBackAsk` | M |
| `wayThereFrom` 1909–1913 | `rec.nav.session`, `followIsTrack` | – | M |
| `followEditedPlan` 3319–3358 | `route`, `rec`, follow state, `pendingSplit`, `splitBefore`, `followHistory` | `followPlanned`, `recopyFollowedPlan`, `planBaseline`, `followAgain`, `followAhead` | M (called by `recalc`'s effect 2437) |
| `followAhead` 3430–3471 | `myPosition`, `waypoints`, `activity`, `sac`, `planFactors`, `pinned`, `legCache`, `routingEngine()` | `splitRequests.next`, `followCancel`, `PlanResult.plan` on **R**, `setFollow`, `planBaseline`, `followFromPlan`, `copyFollowedPlan`, `sayError` | M + R |
| `followShownDuringTour` 2685, `followShownNow` 2687–2694 | `rec`, `waypoints` | `clearPlan`, `followShown`, `closeShown`, `continueNavigation` | M |

Reached from `recalc` (stays in the view model until 9b): `followCancel?.cancel()` 2302, `planBaseline = it.points`
2411, `followPlanned(checkNow = true)` 2434, `followEditedPlan()` 2437, `if (rec == null) route = null` 2426.

### 1.5 Piece 19 – mode, start, the service's answers, the start-up check

| Member (lines) | Reads | Writes / calls | Thread, job |
| --- | --- | --- | --- |
| `mode` 269, `navStartZoom` 275, `canEditMap` 277, `straightAsk` 2835, `startJob` 2817, `START_TIMEOUT_MS` 2832 | – | – | M |
| `enter` 2762–2781 | `activity`, `headingUp`, `tracker.location`, `rec`, `waypoints` | `mode`, `tapHoldHint`, `offlineInfo`, `save.cancel`, `navStartZoom`, the menus (`planPoi`, `poiMenu`, `closePointMenu`, `firstPointMenu`, `holdAddMenu`, `cancelHoldMeasure`, `highlight`), `followMe`, `planSize`, `showPlanWhole`, `recording.navigating` | M |
| `startNavigation` 2791–2815 | `rec`, `tour.canStartNew`, `shown`, `route`, `waypoints`, `errorCard` | `continueNavigation`, `straightAsk`, `followShown / followPlanned / clearFollow`, `shown`, `secureTourCorridor`, `save.atTourStart`, `startRecording`, `startJob`, `enter(NAVIGATE)`, `sayError` | M; job `startJob` (cancelled by the next start only) |
| `secureTourCorridor` 2825, `planHasStraightLegs` 2837, `straightConfirmed` 2844, `startRecording` 3524–3530 | `route`, `waypoints`, follow state | `mapData.secureTourCorridor`, `tour.start` | M |
| `continueNavigation` 2852–2864, `checkNow` 2876, `switchToPlan` 3141, `trackLook` 2873 | follow state, `routing`, `route` | `straightAsk`, `followPlanned(checkNow)`, `routeEvent(FollowNext)`, `recording.checkNow`, `enter` | M |
| the seven answers 2881–2899 | – | `recording.takeRoute / takeVia / keepRoute / leadThere / justRecord / joinHere / leadFailedOk` | M |
| the big `init` 1390–1460: the left-over line check 1396–1407, the mode flip 1410, the start-up check 1411–1432, the `startFailed` collector 1433–1438, the `state` collector 1439–1460 | `recording.state / startFailed`, `db.findActiveRecording`, `db.loadSegments` | `clearFollow`, `mode`, `tour.unfinishedPending`, `enter`, `startModeKnown`, `tour.showUnfinished`, `recording.startFailedSeen`, `sayError`, `followHistory.clear`, `guidanceEndedByEmptyPlan`, `onArrived`, `navVersion`, `followLine`, `syncNavActivity` | M; the start-up check on **IO** with hops to M |

Stays in the view model as wiring: `recalc()` + `refreshTours()` at the head of that `init`; `recState` 1261; the
provisional-position collector 4004–4015 (`tracker.setFusedWanted`); `diagnosticsState` 1505; every host that hands out
`recState` (`RoutePictures`, `DemoRides`, `MapDataFlow`, `ImportExport`, `PlanSync`, `AppUpdates`, `SyncDriver`).
The brief names `tracksFix`: it is `RecordingService`'s own (line 113 / 663), the view model has no use of it.

### 1.6 What cannot go to `:state`, and the port each needs

| Android-bound thing | Used by | Port (existing first) |
| --- | --- | --- |
| `ServiceRecordingClient`, `RecordingCommand.intent(context)`, `RecordingService` | all four | **`RecordingClient`** – interface to `:core` in 16a; the slices get the view model's one object |
| `LocationTracker.location` (`StateFlow<android.location.Location?>`) | `myPosition`, `enter` (has a fix?), the check sheet | **`Position`** (exists, the same lambda `MapViewSlice` gets) for `myPosition` / `enter`; **new `Fixes`** for 16b: `fun now(): GeoPoint?` (with altitude when the fix has one) + `suspend fun first(): GeoPoint` – the adapter is the two lines of `startCheck` 2963–2964 |
| `tracker.setFusedWanted` (collector 4004) | – | none: stays in the view model |
| `TourDb` | 16b, 17, 18a, 19 | **`ToursStore`** has `getTour`, `loadSegments`, `rename`; **new `TourRows`** (the design's name): `findActiveRecording()`, `updateActivity(id, activity, sac)`, `updateStats(id, distance, ascent, descent, duration, state)`, `setDeleted(id, ms)`, `setTourPlan(id, plan)`, `rides(): RideStore` – adapter with named arguments, pinned |
| `Backups.tourChanged(app, id)` | 17, 18a | host `tourChanged(id)` (same shape as `SaveHost` / `RoutesHost`) |
| `TourInterruptedReceiver.cancelNote(app)` | 17 | host `clearInterruptedNote()` |
| `DemoLocation.endWithTour()` + `demo.navSimLine = null` | 17 | host `demoEndedWithTour()` (one call, the `if` inside the adapter – pinned) |
| `FollowStore(app.filesDir)` | 16b, 18a | no port: `FollowStore` is pure `:core`; the slices take **the view model's instance** |
| `prefs.followIsTrack`, `prefs.sac` | 18a, 16b | `FollowPrefs { var followIsTrack: Boolean }`; `sac` through `planRoute` (as `selectSac` writes it – but see STOP in §5.2) |
| `mapData.secureTourCorridor`, `search.offlineIndex`, `RouteNamer`, `kickThumbs` | 19, 17 | host functions; `nameRecordedTour` stays whole in the view model |
| `RideApply`, `RideStore` (`app/…/rides/RideApply.kt`, with `TourDbRides`) | 17 | prerequisite move to `:core` (`core_move.py`; `TourDbRides` into its own `:app` file) |
| `ActivityRowState`, `ActivityRow`, `ActivityCheckUi`, `ActivityCheckRules` (in the Composable file `ActivitySwitchSheet.kt`) | 16b | type move to `state/…/ui/ActivityCheckUi.kt`, package unchanged; `activityRowStatus` stays |

No `Intent`, no `SavedStateHandle`, no permission call in the tour code of the view model (permissions are
`MainActivity` → `openSetup(BEFORE_TOUR) { vm.startNavigation() }`).

## 2. Every line that talks to the service

All on the main thread. A command is an `Intent` to the service: **it is handled a main-loop turn later**, never inside
the call. "Before" / "after" = what the function has done before the call and does after it.

| # | Line | Call | In | Before it | After it | Rule |
| ---: | ---: | --- | --- | --- | --- | --- |
| C1 | 3527 | `tour.start(activity.name, sac)` → `START` | `startRecording` ← `startNavigation` | `canStartNew` checked twice (2795 and in `TourController.start`); what is followed is on disk | `startJob`: wait for the first `RecState` | 2, 3 |
| C2 | 3537 | `tour.continueTour(t)` → `START` + `continueId` | `continueRecording` | `InterruptedLog.chose(…, ::clearInterruptedNote)`; `tour.unfinished = null` inside | nothing (mode is Navigate since the start-up check) | 26 |
| C3 | 3616 | `tour.pause(p)` → `PAUSE` / `RESUME` | `pauseRecording` | – | – | 32 |
| C4 | 3621 | `tour.stop()` → `STOP` | `stopRecording` ← `endTour`, `endTourAndDelete` | `tour.end(delete)`; `routeBackAsk = false`; `planSize` | the `finished` collector | 10, 11, 32 |
| C5 | 3502 | `recording.followChanged(checkNow)` | `setFollow` | session + line written (3495), follow state set; only if `rec != null` | caller: `followFromPlan`, plan copy | 1, 18 |
| C6 | 3376 | `recording.followChanged()` | `followAgain` | files (3365), state, plan copy queued; **no `rec != null` guard** | – | 1, 20 |
| C7 | 3521 | `recording.followChanged()` | `clearFollow` | requests invalidated, files gone (3516), state null; only if `rec != null` | callers set `guidanceEndedByEmptyPlan = true` | 1, 17, 19 |
| C8 | 2780 | `recording.navigating(m == NAVIGATE)` | `enter` | everything else of `enter`; only if `rec != null` | – | 15 |
| C9 | 2877 | `recording.checkNow()` | `checkNow` ← `continueNavigation` | only if `rec != null && followLine != null` | `enter(NAVIGATE)` | 22 |
| C10–16 | 2881–2899 | the seven answers with the question id | one-liners | – | – | 31 |
| C17 | 3096 | `recording.proposeSwitch(fit.timeS)` | `applySwitch` | sheet closed; proposal file written (3095) | `enter(NAVIGATE)` if in Plan | 1, 24 |
| C18 | 1687 | `recording.say(what)` | `sayEvent` ← `planEditHost.sayNoDestination` 863 | only if `rec != null`; `routeBackAsk` just set | the one-left rule | 30 |
| C19 | 367 | `recording.settingsChanged()` | `settingsHost` ← `SettingsSlice.updateSettings` | settings stored; only if a tour records | – | 29 |
| A1 | 1464 | `recording.finishedSeen()` | `finished` collector | nothing – first statement | the tour is read on IO | 7 |
| A2 | 1436 | `recording.startFailedSeen()` | `startFailed` collector | nothing | error box, back to Plan | 13 |
| A3 | 1485 | `recording.takeFinishedParts()` | `finished`, SAVED branch | saved box shown, the tour's name asked for | each part named | 7 |
| S1 | 1261 | `recState = recording.state` – ≈ 45 `recState.value` reads | everywhere | – | – | – |
| S2 | 1410 | `recording.state.value != null` → `mode = NAVIGATE` (no `enter`) | `init` | `recalc()`, `refreshTours()` | the start-up check | 4 |
| S3 | 1417–1421 | `recording.state.value` ×3 | start-up check (**read on IO**) | DB read | 1.5 s, the question | 5 |
| S4 | 1435 | `recording.startFailed.collect` | `init` | – | – | 13 |
| S5 | 1443 | `recording.state.collect` | `init` | – | – | 14 |
| S6 | 1462 | `recording.finished.collect` | `init` | – | – | 7–9 |
| S7 | 2811 | `recording.state.filterNotNull().first()` in `withTimeoutOrNull(6000)` | `startJob` | `START` sent | Navigate or the error box | 3 |
| S8 | 1284 | `recState.map { it?.tourId }.distinctUntilChanged()` | plan-copy `init` | – | `planCopy { tour(id) }` | 21 |
| S9 | 4006 | `recState.collect` → `tracker.setFusedWanted` | last `init` | – | – | 34 |
| S10 | 3641, 3672, 1568 | `recState` handed to `PlanSync`, `SyncDriver`, `AppUpdates` | wiring | – | – | – |
| F1 | 3495, 3365 | `followStore.appFollows(session, name, points)` | `setFollow`, `followAgain` | session built (new token, version + 1) | C5 / C6 | 1 |
| F2 | 3516 | `followStore.appEnds()` | `clearFollow` | – | C7 | 1 |
| F3 | 3095 | `followStore.propose(proposed)` | `applySwitch` | – | C17 | 1 |
| F4 | 1172–1178 | `followStore.loadLine()` ×2, `loadSession()` | property initialisers | – | – | 33 |
| N1 | 3533 | `TourInterruptedReceiver.cancelNote(app)` | the three answers of the interrupted tour | – | – | 26 |

What the service does with them (read in `startCommand`): any control command with no tour recording stops the service
again – so a command sent to a dead service is harmless but useless. `FOLLOW_CHANGED` re-reads **both files in one
look** (`loadFollow`), `START` does the same before its first `publish()`. `finish()` sets, in this order,
`_finishedParts` → `_finished` → … → `_state = null`. `onDestroy` without `finish` sets only `_state = null`.

## 3. The order rules

"Pinned today": **PURE** = a JVM test of the pure rule underneath (not of the view model's order); **PIN** = a source-text
test on `AppViewModel.kt`; **FLOW** = a behaviour test on real moved code that reaches the rule through a host fake;
**–** = nothing. E2E = `CoreFieldE2ETest` unless a class is named.

| # | Rule (one sentence) | Lines | What the user sees if it breaks | Pinned today | E2E |
| ---: | --- | --- | --- | --- | --- |
| 1 | A hand-over file is written before its command, in the same main-loop turn: follow files before `FOLLOW_CHANGED` (set, again, clear), the proposal file before `PROPOSE_SWITCH`. | 3495→3502, 3365→3376, 3516→3521, 3095→3096 | Guidance follows the old route, or a deleted route comes back; the activity question never appears. *Today a swap inside one function would not show (the Intent arrives later) – a suspension between the two would.* | PURE `FollowStoreTest` (the store); the view model's order **–** | `deletingAllPointsDuringATour…`, `aSavedRouteTappedDuringATour…`, `deletingTheStartDuringATour…` |
| 2 | Start: already recording → Continue; an interrupted tour pending / asked → nothing; straight legs → ask; then what is followed (track / plan + corridor + save at start / nothing), then `START`. | 2792–2806 | A second tour beside the interrupted one; a tour that follows nothing; the plan not saved at "Los geht's". | PURE `PlanStartTest`, FLOW `UnfinishedStartGuardTest` (controller), `SaveFlowTest` (`atTourStart`); the order **–** | `endTourSavesTheTour…`, `losGehtsWithAChangedSavedPlan…`, `aClearedPlanIsUnderZuletzt…` |
| 3 | Navigate only after the first `RecState` (6 s); else "recording did not start" – only when no error box shows – and the mode stays Plan; a newer start cancels the older wait. | 2809–2814, 2832 | In Navigate with nothing recorded (R-01); or never in Navigate. | **–** | **none** |
| 4 | At construction the mode stays Plan through `recalc()` and `refreshTours()`; with a service already running it flips to Navigate directly, without `enter`. | 1391–1410 | The stored plan is routed as "not in Plan" (no freshen, other sheet state); or `enter` touches members not built yet (the 0.9.1 class of crash). | **–** (`ViewModelInitOrderTest` checks declarations only) | none (needs a re-created Activity during a tour) |
| 5 | Start-up check: `unfinishedPending` set on main before the check is launched; DB read on IO; `enter(start)` + `startModeKnown` on main **before** the wait; the question only if no service state before and after 1.5 s; `showUnfinished` before the flag clears; cleared in `finally` (non-cancellable). | 1413–1431 | A new tour beside an interrupted one; the "interrupted tour" question although the tour runs; every start refused for good. | FLOW `UnfinishedStartGuardTest` (the flag's effect); the order, the 1.5 s, the `finally` **–** | `UnfinishedTourSetupE2ETest` (question + Continue); the sticky 1.5 s path none |
| 6 | A followed line left over with an empty plan is removed at start only when the database has no active tour and still nothing records. | 1398–1407 | A blue line nothing can remove (Pixel 2026-11-01) – or the guidance of a tour that is coming back is deleted. | **–** | none |
| 7 | Tour finished: `finishedSeen()` first; the tour read once on IO; then the outcome – the user's delete (taken once) before "short" before saved; deleted / short: saved box closed, then the bin write, whose result decides; saved: box, then names for the tour and its split parts. | 1464–1487 | The end handled twice; a short tour lost without its box; a deleted tour shown as saved. | PIN `ShortRecordingBoxTest`, `SavedTourBoxTest`; FLOW `TourFlowPropertyTest` (controller) | `endTourSaves…`, `endTourDeleteBins…`, `arrivalEndTour…` |
| 8 | After the outcome: `refreshTours` → `clearFollow` → demo reset → `enter(PLAN)` → `tour.afterFinished` → `afterTourEnded` (clear the plan the "Neu planen" way). | 1489–1495, 3151 | The followed line of the ended tour stays; the app stays in Navigate; the plan is cleared before the tour is listed; "DEMO" stays on the map. | PIN `SavePathsTest.theEndOfATour…` (two orders), `.theDemoRideEndsWithTheTour` | the same three + `aDoneTourBringsBackThePlan…` |
| 9 | Everything after the first IO hop of the `finished` collector runs with `recState == null` (the service sets `finished` **before** `state = null`; the hop is what puts the rest behind it): no `FOLLOW_CHANGED`, no `NAVIGATING`, the camera moment is `TOUR_END`, the sheet is not the one-row tour sheet. | 1465, service 1452 / 1462 | Wrong camera and sheet after the end; commands to a stopped service. | **–** | partly `endTourSaves…` (the start screen) |
| 10 | The delete decision is set (`tour.end(delete)`) before `STOP` and taken once. | 3217–3218, 3301–3302 | "Löschen" saves the tour – or the next tour is binned. | FLOW `TourFlowPropertyTest` | `endTourDeleteBins…` |
| 11 | The ride choice is stored on the tour before `STOP` (the service applies it in `finish`); a failed write still ends the tour, as recorded. | 3256–3261 | "Save only until …" ignored: wrong km / time. | PURE `RideApplyTest`; the order **–** | **none** (C12 has no E2E) |
| 12 | "End tour": the ride check on IO; its result is dropped when that tour is no longer recording; a failed check asks plainly. | 3232–3250 | An end sheet for a tour that already ended. | PURE `TourEndPathsTest`; the guard **–** | `lockOffPauseThenEndOpensTheEndSheet`, `lockOnHoldPauseThenHoldEnd` |
| 13 | The service stopped by itself: `startFailedSeen()`, the reason in the error box, back to Plan only from Navigate. | 1436 | The reason shown again and again; Navigate with nothing recording. | **–** | **none** |
| 14 | The `state` collector, per state: a new tour id clears the follow history; an ended tour resets "recording only"; "track cannot be saved" once per failure; arrival once per arrival; a new nav version makes the service's line the followed line; then the session's activity. | 1443–1459 | A taken re-route not drawn; Undo brings a route of another tour; the save warning missing. | FLOW `TourFlowPropertyTest.theArrivalBoxComesOncePerArrival` (controller only); the collector **–** | `arrivalEndTour…`; re-route line: not verified |
| 15 | `enter`: mode first; Save's dialog closes; Navigate closes every menu and follows; back in Plan the sheet and the plan camera; **last** the service is told the mode, only during a tour. | 2762–2780 | Off-route questions in Plan / none in Navigate; a dialog behind the other mode. | PIN `SavePathsTest` (one line), PURE `AppModeTest`, `PlanSizeTourTest` | `navigateThenBackToPlan…`, `backInPlanTheMapShowsThePlan…`, `leavingTheRoute…` |
| 16 | An edit during a tour: the split is decided (and older follow requests made stale) before the route is asked; the tour follows the edited plan only from the newest route generation; a position → ahead route is applied only while it is the newest, the plan is unchanged and the tour still records. | `PlanEditSlice.change`, 1724–1731, 2302, 2437, 3442–3456 | Deleted points come back in the guidance; the tour follows a plan the map does not show. | PURE `RouteRunTest`, FLOW `PlanEditFlowTest` (hook order, scripted host), PIN `FollowLineLegsTest.aNewerEditStopsTheFollowLine` | `deletingTheStartDuringATour…`, `aPlaceAddedDuringATour…`, `aPlaceMadeTheDestinationDuringATour…` |
| 17 | An empty plan, "Neu planen", "No" to "route back" and one point right here end the **guidance**, never the recording; "recording only" is set **after** `clearFollow` (which resets it). | 1772, 1783–1784, 1808–1809, 1822–1823, `PlanEditSlice` 312–313 | The tour stops by an edit (never!); or Undo no longer brings the guidance back. | PURE `PlanGuidanceTest`, PIN `TourEditTest`, FLOW `PlanEditFlowTest.clearDuringATour…` | `deletingAllPointsDuringATour…`, `clearingThePlanDuringATourAsksNothing`, `onePointLeftRightHere…`, `undoDuringATourBackToOnePointHere…` |
| 18 | `setFollow`: "recording only" off first; a session with a new, strictly larger token and a version above the service's; a session that cannot be built → the plain line is followed; files, then state, then the command (only during a tour). | 3488–3502 | A late re-route of the old line overwrites the new route; voice and cues keep the old line. | PURE `FollowStoreTest.aReRouteOfTheOldRoute…`; the view model **–** | indirectly every followed tour |
| 19 | `clearFollow`: follow requests stale first, the plan copy told, the files gone, the state null, then the command. | 3510–3521 | A slow route result re-follows after the guidance ended. | **–** | `deletingAllPointsDuringATour…` |
| 20 | Undo / Redo back to a plan followed before in this tour follows that session exactly (way there or re-route included) with a higher version; the session it replaces is remembered first. | 3337–3341, 3353–3356, 3363 | Undo routes anew and loses "Guide me". | PURE `FollowHistoryTest`; the wiring **–** | `deletingTheStartDuringATour…` (Undo) |
| 21 | The plan copy: one serial thread, in the order the plan changed; `tour(id)` on every id change; `notFollowed` for a track / nothing; copied again when the plan is saved during the tour; the final write tells the backup only when it changed something. | 1268–1286, 3403–3411, 2656, 3477, 3512 | "Gemacht → Planen" brings an old plan or none. | PURE `TourPlanCopyTest`; the wiring **–** | `aDoneTourBringsBackThePlanItFollowed` |
| 22 | Continue: an edited plan with straight legs asks first; an edited plan is followed with "check now"; a plan still routing is followed when its result is there; else only "check now"; **then** Navigate. | 2853–2863 | Back in Navigate on the old route; no off-route check after Continue. | PURE `PlanGuidanceTest.anEditedPlanReplacesAFollowedTrack`; the order **–** | `navigateThenBackToPlan…`, `lockOnOneHold…` |
| 23 | Check sheet: nothing is routed before a row is chosen; a new tap, "Keep" and Back stop the running check (router first, then the job); a result of a stopped check changes nothing. | 2928–2989 | Six rows routed for 90 s (tour 207); a switch the user did not choose. | PURE `ActivityCheckRulesTest`, `ActivityCheckTest`; the slice **–** | `farFromThePlanTheT5Chip…` |
| 24 | A switch: on the route – sheet closed → proposal file → `PROPOSE_SWITCH` → Navigate; never reached and following the plan – T level stored → "follow the next result" → the plan routed again → the tour's row; free recording – sheet closed → activity → the tour's row. | 3075–3117, 3023–3030, 3058–3064 | The plan is re-routed but not followed; the question asked in Plan; the tour keeps the old activity in My routes. | **–** | `farFromThePlanTheT5Chip…` (never reached); on the route and free: **none** |
| 25 | The service navigates another activity: the followed original is the session's, the next result is a baseline (not an edit), then the plan is routed for it. | 3133–3136 | A switch taken from the notification makes the app "follow the edited plan" and resets the session. | **–** | **none** |
| 26 | Interrupted tour, every answer: the log + the notification first, the question closed, "the service came back meanwhile" checked (then Navigate, nothing written), the mode, then the writes on IO – Save: "done", the choice, `finish`, backup of the tour and its parts, list on main; Delete: "done" **before** the bin, backup only on success, an error said. | 3536–3537, 3574–3590, 3596–3611 | The notification stays; a restored tour is "unfinished" again; a tour the service records is closed under it. | PIN `InterruptedLogTest`, `TourEndPathsTest.deletingAnInterruptedTourMarksItDoneFirst`; PURE `InterruptedLogTest` | `UnfinishedTourSetupE2ETest` (Continue only) |
| 27 | Short recording: the bin write's result decides box or saved box; Rückgängig: restore → backup → saved box → name → list; a failed restore says so and re-reads the list. | 1474–1479, 3186–3201 | A short tour gone without its box. | PIN `ShortRecordingBoxTest`, FLOW `TourFlowPropertyTest.aShortRecording…` | none |
| 28 | Naming a recorded tour: only a date name; rename → backup → the saved box's name if it still shows that tour → list. | 2727–2752 | A renamed tour is overwritten; the box shows the old name. | PIN (two lines in `SavedTourBoxTest`) | `endTourSaves…` (not verified that the name is asserted) |
| 29 | A changed setting is stored before the service is told, and the service is told only during a tour. | `SettingsSlice` 185–189, 367 | The service re-reads the old settings. | FLOW `SettingsFlowTest` | `navigateGearOpensSettingsOnTheWay…` |
| 30 | "No destination ahead" is said only during a tour, when the question is set and before the one-left rule. | `PlanEditSlice` 159–164, 1687 | The sentence without a question, or twice. | FLOW `PlanEditFlowTest.deletingTheGoalDuringATourAsksRouteBack…`; the `rec != null` guard **–** | none |
| 31 | The seven answers pass the question's id through; no app state changes with them. | 2881–2899 | A late answer changes the route. | PURE `RecordingClientTest` (the commands) | `leavingTheRoute…` (the question; its answer not verified) |
| 32 | Pause / resume pass through; `stopRecording` closes "route back" and steps the sheet before `STOP`. | 3616–3621 | A question of the old tour in the next one. | PURE `PlanSizeTourTest` | `lockOffPause…`, `lockOnHoldPause…`, `lockOnOneHold…` |
| 33 | What survives the process: the followed line and name (`follow.json`), the original (`nav-session.json`), "is a track" (prefs), the plan; **not**: `followFromPlan`, the baseline, the split, the history, the passed points, "recording only", the token counter. | 1172–1180, 1330 | After a restart mid-tour: Continue re-plans a followed track, or Clear leaves a line behind. | PURE `FollowStoreTest`; the view model **–** | none |
| 34 | The provisional (fused) position is asked for exactly while a tour records, is not paused and has had no GPS fix – only on a change. | 4004–4014 | Indoors no position; or the fused request never stops. | PURE (`Provisional`) | `CoreIndoorStartE2ETest` |
| 35 | Edit rides of a saved tour: the sheet closes, the edit on IO, the backup only on success, an error said, the list read again, the shown tour re-opened. | 3282–3293 | The map shows the tour as before the edit. | **–** | **none** |
| 36 | A shown track followed during a tour: the plan leaves the map first (one Undo step, kept under Zuletzt), then the track is followed, closed as "shown", then Continue. | 2687–2693 | The plan and the track both followed; Undo brings nothing back. | PIN `SavePathsTest` (one line) | `followingATrackDuringATourKeepsTheUnsavedPlan…`, `…WithASavedPlanGoesAtOnce` |

**Count.** 36 rules. **No JVM test of any kind (9):** 3, 4, 6, 9, 13, 19, 24, 25, 35. **Only the pure rule underneath
is tested – the view model's order is not (15):** 1, 2, 5, 11, 12, 14, 18, 20, 21, 22, 23, 31, 32, 33, 34. **A source
pin or a flow test through a host (12):** 7, 8, 10, 15, 16, 17, 26, 27, 28, 29, 30, 36. No E2E passes through: 3, 4, 6,
11, 13, 25, 27, 30, 33, 35 (10), and 24 on two of its three paths; so **3, 4, 6, 13, 25, 35 have nothing at all today**.
Corrections to the design's five: its rule 2 is rules 2 + 3 here (three guards come before
"follow"); its rule 3 misses `enter(start)` before the wait and `showUnfinished` before the flag clears; its rule 4
misses the demo reset and `afterTourEnded`, and rule 9 is new; its rule 5 lives in `RouteRun` + `SplitRequests`, not in
one function.

## 4. The test plan – before any move

### 4.1 (a) Provable today on pure classes – add now, with 16a

| Flow to add | Against | Fakes | Rules |
| --- | --- | --- | --- |
| The fake ends a tour as the service does: `parts` → `finished` → `state = null` (today `FakeRecordingClient.finish` sets `state` first). A source pin on `RecordingService.finish` keeps it true: index of `_finishedParts = parts` < `_finished.value = tourId` < `_state.value = null`. | `FakeRecordingClient`, `RecordingService.kt` (does not move) | – | 9 |
| App writes, then `followChanged` → the service navigates the new session; `followChanged` with the write one turn late → the old one (why rule 1 exists). | `FakeNavService` (real `Navigator`, `FollowStore`), temp dir | two `ManualExecutor`s | 1 |
| `appEnds`, then a re-route result of the old session arrives → dropped, no file. | `FakeNavService` | `FakeRoutingEngine` | 1, 19 |
| `propose` then take → the session once; take without a file → nothing. | `FollowStore` (exists: `theActivityProposalIsHandedOverOnce`) | – | 1 |
| A session with a version not above the running one is not re-read by cues (if `Navigator` has such a check – **not verified**; else drop). | `FakeNavService` | – | 18 |
| `showUnfinished` before `unfinishedPending = false`: `canStartNew` is false at every step of that sequence; `unfinishedRides` open also holds a start. | `TourController` | `TourRecorder` recording calls | 5 |
| `end(delete = true)` → `finishedOutcome` DELETED once, the next one SAVED even when short = false; `end(false)` after `end(true)` resets. | `TourController` | – | 10 |
| A stale generation never yields `FollowEditedPlan`; `AdoptBaseline` suppresses it; `FollowNext` wins. Check `RouteRunTest` has the three; add what is missing. | `RouteRun` | – | 16, 24, 25 |
| `isCurrent` is false after `invalidate`, after a newer `next`, and for another plan. | `SplitRequests` (`PlanStateTest`) | – | 16, 19 |
| `choose` then `finish` applies the choice; `finish` alone only marks (exists in `RideApplyTest` – confirm, no new test). | `RideApply` | map store | 11 |

### 4.2 (b) Source-order pins on `AppViewModel.kt` until the move – `TourOrderPinsTest` (`:app`, `sourceFile`)

Each pin: the function body cut by an anchored `substringAfter(start).substringBefore(end)` (both anchors must be found
exactly once – assert it), then `indexOf` order of the listed texts, each found exactly once in the scope. Written and
reviewed on its own in 16a; every pin is **deleted in the piece that moves its function**, replaced by the flow of §4.4.

| Pin | Scope: from … to | Texts in this order | Rule | Goes in |
| ---: | --- | --- | ---: | --- |
| P1 | `private fun setFollow(` … `\n    }\n` | `guidanceEndedByEmptyPlan = false` · `.copy(token = newSessionToken(), version = (recState.value?.nav?.version ?: navVersion).coerceAtLeast(0) + 1,` · `followStore.appFollows(session, name, points)` · `followLine = points` · `if (recState.value != null) recording.followChanged(checkNow)` | 1, 18 | 18a |
| P2 | `private fun followAgain(` … `\n    }\n` | `s.copy(version = maxOf(s.version, versionNow) + 1, token = newSessionToken())` · `followStore.appFollows(session, session.name, session.active)` · `followOriginal = session.original` · `recording.followChanged()` | 1, 20 | 18a |
| P3 | `fun clearFollow() {` … `\n    }\n` | `splitRequests.invalidate()` · `planCopy { notFollowed() }` · `followStore.appEnds()` · `followLine = null` · `if (recState.value != null) recording.followChanged()` | 1, 19 | 18a |
| P4 | `private fun applySwitch(` … `\n    }\n` | `closeActivityCheck()` · `switchPlanActivity(a, fit.sac)` · `followStore.propose(proposed)` · `recording.proposeSwitch(fit.timeS)` · `if (mode == AppMode.PLAN) enter(AppMode.NAVIGATE)` | 1, 24 | 16b |
| P5 | `private fun switchPlanActivity(` … `\n    }\n` | `prefs.sac = sac` · `routeEvent(RouteRun.Event.FollowNext)` · `selectActivity(a, userChange = false)` · `db.updateActivity(id, a.name, storedSac)` | 24 | 16b |
| P6 | `private fun startCheck(` … `\n    }\n` | `cancelCheck()` · `activityCheck = ActivityCheckRules.checking(ui, a, level)` · `if (!isActive \|\| cancel.isCancelled) return@launchSafe` (first) · `val st = checkRow(rest, a, level, cancel)` · `if (st is ActivityRowState.Ready) applySwitch(st.fit)` | 23 | 16b |
| P7 | `recording.finished.collect { id ->` … `fun initialCamera()` | `recording.finishedSeen()` · `val done = withContext(Dispatchers.IO) { db.getTour(id) }` · `tour.finishedOutcome(` · `tour.showFinished(done,` · `val parts = recording.takeFinishedParts()` · `refreshTours()` · `clearFollow()` · `demo.navSimLine = null` · `enter(AppMode.PLAN)` · `tour.afterFinished()` · `afterTourEnded()` | 7, 8, 9 | 17 |
| P8 | `fun endTourWith(` … `\n    }\n`; `fun endTour() {`; `fun endTourAndDelete() {`; `fun stopRecording() {` | `RideApply.choose(` · `endTour()`; `tour.end(delete = false)` · `stopRecording()`; `tour.end(delete = true)` · `stopRecording()`; `routeBackAsk = false` · `tour.stop()` | 10, 11, 32 | 17 |
| P9 | `fun askEndTour(` … `\n    }\n` | `arrivalAsk = false` · `if (recState.value?.tourId != id) return@launchSafe` · `TourEndFlow.afterRideCheck(` | 12 | 17 |
| P10 | `fun saveUnfinished(` … `\n    }\n` | `InterruptedLog.Choice.SAVE, ::clearInterruptedNote)` · `tour.closeUnfinished()` · `if (recState.value?.tourId == t.id) { enter(AppMode.NAVIGATE); return }` · `enter(AppMode.PLAN)` · `db.updateStats(` · `RideApply.finish(` · `Backups.tourChanged(getApplication(), t.id)` · `withContext(Dispatchers.Main) { refreshTours() }` | 26 | 17 |
| P11 | `fun deleteUnfinished(` … `\n    }\n`; `fun continueRecording(` | the same head · `"done"` · `db.setDeleted(` · `if (ok) Backups.tourChanged(`; `InterruptedLog.Choice.CONTINUE, ::clearInterruptedNote)` · `tour.continueTour(t)` | 26 | 17 |
| P12 | `fun applyEditRides(` … `\n    }\n`; `fun undoShortRecording() {` | `editRides = null` · `RideApply.edit(` · `.also { if (it) Backups.tourChanged(` · `refreshTours()` · `openTour(it)`; `routes.restore(t.id)` · `Backups.tourChanged(getApplication(), t.id)` · `tour.showFinished(done,` · `nameRecordedTour(done)` · `refreshTours()` | 27, 35 | 17 |
| P13 | `fun followPlanned(` … `\n    }\n`; `fun followShown() {` | `setFollow(` · `followFromPlan = true` · `copyFollowedPlan(activity, sac)`; `setFollow(s.name, s.points, isTrack = true)` · `followFromPlan = false` · `planCopy { notFollowed() }` · `planAtTrackFollow = waypoints.toList()` | 21 | 18a |
| P14 | the file | `private val planCopyDispatcher = Dispatchers.IO.limitedParallelism(1)`; `recState.map { it?.tourId }.distinctUntilChanged()` then `.collect { id -> planCopy { tour(id) } }`; in `setSaved`: `plan.setSaved(name, id, points)` · `recopyFollowedPlan()` | 21 | 18a |
| P15 | `private fun syncNavActivity(` … `\n    }\n` | `navActivitySeen = a` · `followOriginal = s.original` · `routeEvent(RouteRun.Event.AdoptBaseline)` · `selectActivity(a, userChange = false)` | 25 | 18a |
| P16 | `private fun endGuidanceIfPlanEmpty()`; `fun answerRouteBack(`; `private fun oneLeftGoesFromHere(` (each to `\n    }\n`) | `clearFollow()` · `guidanceEndedByEmptyPlan = true`; `splitRequests.invalidate()` · `if (recState.value == null) return` · `this.plan.replace(back)` · `routeEvent(RouteRun.Event.PlanEdited)` · `recalc()` and the text `{ clearFollow(); guidanceEndedByEmptyPlan = true }`; the two-line text `clearFollow()` / `guidanceEndedByEmptyPlan = true` exactly twice, each after `pendingSplit = null` | 17 | 18b |
| P17 | `private fun followAhead(` … `\n    fun followShown()` | `val gen = splitRequests.next(waypoints)` · `followCancel?.cancel()` · `if (cancel.isCancelled) {` · `if (!splitRequests.isCurrent(gen, waypoints.toList()) \|\| recState.value == null) return@launchSafe` · `setFollow(name, line, isTrack = false, checkNow = true,` · `copyFollowedPlan(act, level)` | 16 | 18b |
| P18 | `private fun followShownNow() {` … `\n    }\n` | `if (recState.value == null) return` · `clearPlan()` · `followShown(); closeShown(); continueNavigation()` | 36 | 18b |
| P19 | `fun startNavigation(` … `\n    }\n` | `if (recState.value != null) { continueNavigation(force); return }` · `if (!tour.canStartNew)` · `straightAsk = true; return` · `PlanStart.NAVIGATE_PLAN -> { followPlanned(); secureTourCorridor(); save.atTourStart() }` · `startRecording()` · `startJob?.cancel()` · `withTimeoutOrNull(START_TIMEOUT_MS) { recording.state.filterNotNull().first() }` · `if (started) enter(AppMode.NAVIGATE)` · `else if (errorCard == null) sayError(Txt.s(Res.string.msg_recording_not_started), NoticePlace.MAP)`; and the text `private val START_TIMEOUT_MS = 6_000L` | 2, 3 | 19 |
| P20 | `    init {\n        recalc()` … `// The service stopped by itself` | `recalc()` · `refreshTours()` · `if (!active && waypoints.isEmpty() && recState.value == null && followLine != null && !followIsTrack) {` · `if (recording.state.value != null) mode = AppMode.NAVIGATE` · `tour.unfinishedPending = true` · `viewModelScope.launchSafe("app", "start-up work", Dispatchers.IO) {` · `val active = db.findActiveRecording()` · `withContext(Dispatchers.Main) { enter(start); startModeKnown = true }` · `delay(1500)` · `withContext(Dispatchers.Main) { tour.showUnfinished(active, line) }` · `} finally {` · `withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { tour.unfinishedPending = false }` | 4, 5, 6 | 19 |
| P21 | `recording.startFailed.collect { why ->` … `// Re-routing (service)`; `recording.state.collect { st ->` … `recording.finished.collect` | the whole line `if (why != null) { recording.startFailedSeen(); sayError(why, NoticePlace.BACKGROUND); if (mode == AppMode.NAVIGATE) enter(AppMode.PLAN) }`; `followHistory.clear()` · `guidanceEndedByEmptyPlan = false }` · `onArrived(st?.arrived == true)` · `val n = st?.nav ?: run { navActivitySeen = null; return@collect }` · `if (n.version != navVersion) { navVersion = n.version; if (followLine != n.line) followLine = n.line }` · `syncNavActivity(n.session)` | 13, 14 | 19 |
| P22 | `private fun enter(m: AppMode) {` … `\n    }\n`; `fun continueNavigation(` … `\n    }\n` | first `mode = m`, `if (save.ask != null) save.cancel()`, last statement of the body `if (recState.value != null) recording.navigating(m == AppMode.NAVIGATE)`; `straightAsk = true; return` · `followPlanned(checkNow = true)` · `routeEvent(RouteRun.Event.FollowNext)` · the body's last statement `enter(AppMode.NAVIGATE)` | 15, 22 | 19 |

Piece 12 edits the same file: if an anchor is not found once after its merge, fix the pin first, not the code.

### 4.3 (c) End to end

Mapped from each method's header comment and name; the bodies were not read – "covers" means the flow passes through
the rule, not that the order is asserted.

| E2E method | Rules it passes through |
| --- | --- |
| `goHereFromMyLocationThenDeleteWhileFollowing`, `navigateThenBackToPlanStillDeletes` | 2, 3 (happy), 15, 22 |
| `deletingAllPointsDuringATourEndsTheGuidanceUndoBringsItBack` | 1, 16, 17, 19 |
| `aSavedRouteTappedDuringATourIsFollowedAtOnceAndUndoBringsThePlanBack` | 1, 16, 18, 21 |
| `endTourSavesTheTourKeepsThePlanAndClearsTheMap`, `arrivalEndTourBringsTheStartScreen` | 2, 7, 8, 9 (partly), 10, 14 (arrival), 28 |
| `endAndANewStartWithinMillisecondsKeepTheNewTourRecording` | the service's `finish(startId)`; sends `STOP` / `START` itself – not the view model's order |
| `losGehtsWithAChangedSavedPlanUpdatesItAndKeepsTheOldUnderZuletzt`, `aClearedPlanIsUnderZuletztSavedOrDeletedThere` | 2 (save at start) |
| `endTourDeleteBinsTheTourAndKeepsThePlan` | 7, 8, 10 |
| `aDoneTourBringsBackThePlanItFollowed` | 21, 8 |
| `deletingTheStartDuringATourRoutesFromHereToTheOnePointLeft`, `deletingTheGoalAgainAfterOnePointLeftEmptiesThePlan`, `onePointLeftRightHereEmptiesThePlan`, `undoDuringATourBackToOnePointHereEndsTheGuidance` | 16, 17, 20 |
| `farFromThePlanTheT5ChipPlansItAgainAndReachesThePointT1CouldNot` | 23, 24 (never reached) |
| `aPlaceAddedDuringATourIsAStopBetweenNotANewStart`, `aPlaceMadeTheDestinationDuringATourEndsThePlanThereUndoBringsTheGoalBack` | 16 |
| `followingATrackDuringATourKeepsTheUnsavedPlanUnderZuletzt`, `followingATrackDuringATourWithASavedPlanGoesAtOnce`, `anEmptyDoneTourOpenedDuringATourKeepsTheFollowedLineInNavigate` | 36, 18 |
| `clearingThePlanDuringATourAsksNothing` | 17 |
| `backInPlanTheMapShowsThePlanNotThePosition`, `backInPlanWithWalkingDirectionUpTheMapShowsThePlan`, `oneOrientationNorthStaysNorthAndTravelKeepsTheBearing`, `backInNavigateDuringATourStaysInTheApp` | 15 |
| `navigateGearOpensSettingsOnTheWayAndBackReturns` | 29 |
| `lockOffPauseThenEndOpensTheEndSheet`, `lockOnHoldPauseThenHoldEnd`, `lockOnOneHoldOpensTheBarWeiterOnATapLocksAgain` | 12, 32, 22 |
| `leavingTheRouteShowsAndSaysBothStepsAndTheCardGoesBackOnTheRoute` | 15 (Navigate told), 31 |
| `CoreIndoorStartE2ETest.indoorStartShowsTheWifiPositionRecordsOnlyGps` | 34, 3 (happy) |
| `UnfinishedTourSetupE2ETest.continueWithASetupStepOpenShowsTheGuideAndGoesOn` | 5 (question), 26 (Continue) |
| the other 6 of the 35 (`aPlaceMadeTheDestinationWhilePlanning…`, `aHoldBesideThePlan…`, `locateOffTheMap…`, `theNavigateCardOpens…` …) | none of the tour rules |

**No end-to-end cover:** 3 (start fails), 4, 6, 11, 13, 24 (on the route; free recording), 25, 26 (Save / Delete of an
interrupted tour), 27, 33, 35. These exist after the move only as flow tests – the reason the flows of §4.4 must be
strict there.

### 4.4 The flow tests after the move

Common harness `TourApp` (`state/src/test/…/ui/tour/TourFlowBase.kt`, grown piece by piece): real `PlanDocument` on a
memory store, real `TourController`, `FakeRecordingClient` (from the shared kit after 16a) with a hook
`onSend: (RecordingCommand) -> Unit` in which the test **snapshots at call time** what the rule is about
(`followStore.load()`, the mode, `state.value`); a real `FollowStore` on a temp folder; `JdbcTours` behind `TourRows`
(it needs `updateActivity`, `setTourPlan` / `getTourPlan`, `setRides` – each with the SQL of `TourDb`, pinned as
`TourDbMirrorTest` does); every host a recording fake that logs `name(args) [state at call time]`. Scope = a
`VirtualDelayDispatcher { it.run() }` (main, inline, virtual time); `io` = a `ManualExecutor` the test steps, so "what
runs before the IO hop" is visible; the fake checks the thread where a rule names one (`TourRows` only on `io`, hosts
only on main). `OwnComposeSnapshot` as rule while everything runs on the test thread; the one test with a real second
thread (the serial plan copy) lives in a class without the rule and writes through `Snapshot.withMutableSnapshot`.
None of the tour code collects a `snapshotFlow` – only `StateFlow`s of the client.

| Piece | Flow (steps → asserted in order) | Proves | Break proof to demand |
| --- | --- | --- | --- |
| 16b `ActivityCheckFlowTest` | Open on a followed route → rows Idle, **no** engine call, no command. | 23 | route every row in `open` → red |
| | Tap Bike, then Hike before the first returns (held `check` executor) → first cancel fired, its result changes no row and sends nothing. | 23 | drop `cancelCheck()` in `startCheck` |
| | On the route, found → at `PROPOSE_SWITCH`: sheet closed, proposal file readable with the new activity, `timeS` as the fit's; then `enterNavigate` when in Plan, not when in Navigate. | 1, 24 | swap propose / send; drop `closeActivityCheck()` |
| | Never reached + plan followed → log `sac=…`, `routeEvent(FollowNext)`, `selectActivity`, then `updateActivity(id, a, storedSac)` on io; no proposal file, no command. | 24 | move `FollowNext` after `selectActivity` |
| | Free recording: row / T chip → sheet closed, `selectActivity`, `updateActivity(tourId, …)` on io; no tour → no write. | 24 | drop the write |
| | No fix and `reached` → the note; a check waits for `Fixes.first()`; no route / straight-line engine / timeout → the row says so, sheet stays. | 23 | – |
| 17 `TourEndFlowTest` | Save: `end(false)` then `STOP`; the fake finishes **in the service's order**; assert `finishedSeen` at once (before `io` is stepped); step io → saved box, `nameRecordedTour(tour)`, parts named, then host log `refreshTours [rec=null]`, `clearFollow [rec=null]`, `demoEndedWithTour`, `enterMode(PLAN) [rec=null]`, then `tour.arrivalAsk == false`, `clearPlan`, `kickThumbs`. | 7, 8, 9, 10 | call `refreshTours` before the outcome; drop the IO hop (`io = Unconfined` must turn the `[rec=null]` asserts red); move `finishedSeen` behind the read |
| | Delete → row has `deleted_at`, `tourChanged(id)` once, no saved box; a failing bin write (`failWrites`) → the error, tour stays. | 7, 27 | ignore the write's result |
| | Short → binned + box; bin write fails → saved box; Rückgängig → restored, backup, saved box, name, list; restore fails → error + list. | 27 | – |
| | Ride summary → Save with a choice: at `STOP` the tour row already holds the choice; choice write fails → `STOP` still sent. | 11 | send `STOP` before the write |
| | "End tour" while the check is held, the tour ends meanwhile → no sheet. Arrival → Save without rides → `STOP` at once. | 12 | drop the id guard |
| | Interrupted: Continue → log line, note cleared, question gone, `START continueId`. Save / Delete: with `state.tourId == t.id` → `enterMode(NAVIGATE)`, **no write**; else `enterMode(PLAN)`, rows: "done" (then `deleted_at`), backups after success, `refreshTours` on main; Delete's failed write → error. | 26 | write before the sticky check; bin before "done" |
| | Edit rides: closed, edit on io, backup on success only, list, shown tour re-opened. | 35 | backup on failure |
| 18a `TourFollowFlowTest` | `followPlanned` without a tour → files hold session + line, **no** command; with a tour → at `FOLLOW_CHANGED` the files already hold the new token and `version = service's + 1`; `checkNow` extra as passed. | 1, 18 | write after send; reuse the token |
| | `clearFollow` during a tour → at the command both files are gone and `splitRequests` is stale; without a tour no command. "Recording only" false after it. | 1, 19 | send first; drop `invalidate` |
| | `followAgain` → same stops, version above both, new token, baseline = plan line. | 20 | – |
| | Plan copy on a **real** serial dispatcher: follow, edit, edit, tour id arrives late → rows written in order, the last version stored; a failed write retried; end of tour → final write, `tourChanged` only when it changed; track followed → `notFollowed`; `setSaved` during a tour → a copy with the saved route's uuid. | 21 | make the dispatcher parallel; drop `tour(id)` |
| | The service's state with another activity → `followOriginal` = session's, `AdoptBaseline` before `selectActivity`, once per activity. | 25 | swap the two |
| | A new slice on the same folder / prefs restores line, name, original, "is a track" – and nothing else. | 33 | – |
| 18b `TourEditsFlowTest` (real `PlanEditSlice` + `TourFollow`, scripted route results) | Delete a point ahead with two passed → `followAhead`: held routing; a newer edit → the first result applies nothing; the newest applies, `firstNumber` as computed. | 16 | drop the `isCurrent` check |
| | Empty plan / Neu planen / "No" / one point here → files gone, "recording only" **true**, `STOP` never in `sent`; Undo → followed again with `checkNow`. | 17 | set the flag before `clearFollow` |
| | "Route back: yes" → plan = position → start, one Undo step; no position → the note, guidance ended. | 17 | – |
| | Undo to a plan followed before → `followAgain` with that session, no routing call. | 20 | – |
| | Track followed during a tour → `clearPlan` first (draft kept), then files hold the track, `shown == null`, Continue. | 36 | follow before clearing |
| 19 `TourSessionFlowTest` (all real slices) | "Los geht's" on a plan: log `followPlanned` (files written), `secureTourCorridor`, `save.atTourStart`, then `START`; mode Plan; fake publishes a state → Navigate and `NAVIGATING on=true` **after** the state. | 2, 3, 15 | start before follow; enter before the state |
| | No state: advance 5,999 ms → nothing; 6,000 → the error, mode Plan; with an error box already open → no second; a second start within the wait cancels the first. | 3 | change the constant; drop `startJob?.cancel()` |
| | Held: `unfinishedPending` → nothing sent, plan and files untouched; straight legs → the question, nothing sent. Already recording → Continue path, no `START`. | 2 | – |
| | Start-up (construct on prepared `JdbcTours`, `start()`): flag true before io is stepped; active tour, no state: after the read `enterMode(NAVIGATE)` + `startModeKnown` **before** the timer; 1,499 ms no question; 1,500 → track read, question, flag false **after** it. State arrives in the 1.5 s → no question. Read throws → flag false. No tour → Plan. | 4, 5 | drop the second state check; clear the flag outside `finally` |
| | Construct with a state already published → mode Navigate without `NAVIGATING` sent at construction; `recalc` saw Plan. | 4 | flip before `recalc` |
| | Left-over line: empty plan + files, no active tour → gone; active tour → stays. | 6 | – |
| | `startFailed` → seen, error, Plan only from Navigate. State stream: new id clears history; `saveFailed` true, true, false, true → two errors; new nav version → `followLine` is the service's. | 13, 14 | – |
| | Continue: unchanged → `CHECK_NOW` then Navigate; edited → `FOLLOW_CHANGED checkNow=true` then `NAVIGATING`; routing → `FollowNext`, and the result follows. | 22 | enter before the decision |
| | A whole tour on real slices: start → edit → end (service order) → nothing sent after `STOP`, camera `TOUR_END`. | 8, 9 | – |

At least one break proof per order rule is named above or is the pin's own (§4.2); the helper adds the brief's "five
different production steps" on top. Rules 29, 30, 34 keep their existing tests.

## 5. The pieces as helper briefs

Each brief goes with `umbau-phase-2-brief.md`. Common to all six: **alone on the file** (no other lane); the E2E class
on a `ci/umbau-p2-<n>` ref before the push (`e2e_github.sh --tests` takes one class per run – two classes = two refs in
parallel); `web/parity.json`: the planner mirrors **nothing** of the tour, but mirror #25 hashes the whole
`AppViewModel.kt` → re-hash with `node web/parity/check.mjs --update …` after confirming its named functions
(`recalc`'s long-route ask, `placeMenu`, the kept places) are untouched; adapter pins in `TourWiringTest` (`:app`) in
the style of `RoutesWiringTest`. Old adapter pins that name tour members break when a host is re-pointed:
`PlanEditAdaptersTest`, `PlaceAddAdaptersTest`, `RoutesWiringTest`, `RouteWarningsTest` (line 340), `SyncWiringTest`,
`StartAtPointOneTest` (lines 166–167) – re-point each line, never delete.

### 5.1 Piece 16a – the contract in `:core`, the kit, the pins (no function body moves)

1. **`core/…/recording/RecState.kt`**: `RecState` verbatim (same package → the same JVM name
   `com.onpath.app.recording.RecState`; it is a plain data class, not `Parcelable`, not `Serializable`, never in an
   `Intent`, never persisted – it lives in the service's static `MutableStateFlow`; no minify rule names it). Its field
   types `NavState`, `TripTime`, `GeoPoint` are in `:core`. Readers that only need the import to keep compiling:
   `RecordingService`, `AppViewModel`, `DemoRides`, `ImportExport`, `MapDataFlow`, `RoutePictures`, `MapScreen`,
   `NavigateScreen`, `MapLoadService` (via `RecordingService.state`).
2. **`core/…/recording/RecordingClient.kt`**: `RecordingCommand` (the data class and its companion, without
   `intent()`) and `interface RecordingClient`, verbatim. The 17 action strings, 8 extra names and 2 `SAY_` values
   move as literals into `object RecordingWire` in the same file; the companion uses `RecordingWire.X`.
   `RecordingService` keeps **every constant name** and aliases the value (`const val ACTION_START = RecordingWire.ACTION_START`)
   – E2E tests and `PendingIntent`s keep reading `RecordingService.ACTION_…`. Not one string changes:
   `RecordingClientTest.everyCommandIsTheIntentItAlwaysWas` writes each out and stays as it is.
3. **`app/…/recording/ServiceRecordingClient.kt`** (the old file, renamed): `fun RecordingCommand.intent(context)` as
   an extension (body verbatim) and `ServiceRecordingClient`.
4. **Kit**: `FakeRecordingClient` → `core/src/testkit`; its `finish` in the service's order + the pin of §4.1 row 1
   (check `RecordingClientTest.theAppSaysSeen…` still holds); the `onSend` hook. `FakeRecording` (implements
   `TourRecorder` of `:state`) → `state/src/test/…/testkit`, with its part of `TestKitTest`. `tourRecorder(client)` →
   `:state` beside `TourController`.
5. **Tests**: `TourOrderPinsTest` (§4.2, all 22), the pure flows of §4.1. `RecordingClientTest` stays in `:app`: its
   action-count regex reads the literals in the `:core` file and additionally asserts the service has an alias for
   each; `outsideRecordingNobody…` and `theFollowedRouteFilesHaveOneStore` must stay green unchanged.
6. The planner compiles the whole `core/` folder: `-p web/engine test generateWasmGC -Pkotlin.incremental=false`.

*STOP:* a wire string would change; the engine build fails on the two new files (do not edit `core-excludes.txt` –
report); `RecState` turns out to be referenced by reflection / a manifest (not found by me).
*E2E:* `CoreFieldE2ETest` (the service file is touched).

### 5.2 Piece 16b – `ActivityCheckSlice`

- **Members**: §1.1. **Types first** (own commit): the four sheet types out of `ActivitySwitchSheet.kt` (§1.6);
  `ActivityCheckRulesTest` moves with them; parity `watch` entry of `ActivitySwitchSheet.kt` changes hash – update,
  list for the planner session.
- **Slice** `state/…/ui/tour/ActivityCheckSlice.kt`: `(planRoute: PlanRouteSlice, recording: RecordingClient,
  follow: FollowStore, rows: TourRows, fixes: Fixes, host: ActivityCheckHost, scope, io, cpu: CoroutineDispatcher)`;
  `checkRouting` = `planRoute.threads.check`, the engine = `planRoute.routingEngine()`.
  `ActivityCheckHost`: `mode`, `enterNavigate()`, `followFromPlan`, `planPoints: Int`, `routeEvent(e)`,
  `planFactors(a)`.
- **Stays in the view model**: the forwarders `activityCheck`, `openActivityCheck`, `chooseActivity`,
  `chooseHikeLevel`, `closeActivityCheck`; `enter`'s Navigate branch does not close the sheet today – keep that.
- **Substitutions**: `recState.value` → `recording.state.value`; `tracker.location…` → `fixes`; `db.updateActivity` →
  `rows.updateActivity` (inside the same `launchSafe(…, io)`); `Dispatchers.Default` → `cpu`; `followStore` → `follow`;
  `selectActivity` / `selectSac` / `activity` / `sac` → `planRoute.*`.
- **Rules to keep**: 1, 23, 24. **Pins that go**: P4, P5, P6 → the flows of §4.4; `UxReviewFixesTest` (reads
  `MapScreen`) stays.
- **E2E**: `CoreFieldE2ETest`.
- *STOP:* `switchPlanActivity` writes `sac` + `prefs.sac` directly and not through `selectSac` (no `recalc`, no cause
  noted) – if `PlanRouteSlice` has no setter that does exactly these two writes, report instead of using `selectSac`;
  `checkRow`'s router call needs anything of `PlanRunSlice`; a second caller of `closeActivityCheck` appears in tour
  code not listed here.

### 5.3 Piece 17 – `TourEndSlice`

- **Step 0** (own commit): `RideStore` + `RideApply` → `:core` with `core_move.py` (`RideApplyTest` with them,
  `TourDbRides` into `app/…/rides/TourDbRides.kt`); planner lock.
- **Members**: §1.2. **Slice** `state/…/ui/tour/TourEndSlice.kt`: `(tour: TourController, routes: RoutesState,
  routesFlow: RoutesSlice, planEdit: PlanEditSlice, recording: RecordingClient, tours: ToursStore, rows: TourRows,
  host: TourEndHost, say: Say, scope, io)`. `TourEndHost`: `activity`, `activityOrNull(name)`, `tourTimes(t)`
  (called inside `withContext(io)` as today), `nameRecordedTour(t)`, `refreshTours()`, `clearFollow()`,
  `enterMode(m)`, `endGuidanceFlag()` (= `guidanceEndedByEmptyPlan = false`), `kickThumbs()`, `demoEndedWithTour()`,
  `tourChanged(id)`, `clearInterruptedNote()`.
- **`start()`** holds the `finished` collector and is called **where the collector is launched today, inside the big
  `init`** – not in the last `init` (§6 risk 1). Declare the slice above that `init`; extend `ViewModelInitOrderTest`'s
  second test or pin the position in `TourWiringTest`.
- **Stays in the view model**: all forwarders of §1.2 (≈ 35 names – screens and `MainActivity` use 30 of them),
  `nameRecordedTour`, the host, the `TourRows` adapter.
- **Substitutions**: `db.*` → `tours.*` / `rows.*`; `RideApply.x(TourDbRides(db), …)` → `RideApply.x(rows.rides(), …)`;
  `Backups.tourChanged(getApplication(), id)` → `host.tourChanged(id)` **on the same thread as today** (IO in
  `binFinishedTour`, `applyEditRides`, `saveUnfinished`, `deleteUnfinished`; main in `undoShortRecording`);
  `enter(m)` → `host.enterMode(m)`; `clearPlan()` → `planEdit.clearPlan()`; `shown` / `openTour` → `routesFlow`.
- **Rules**: 7–12, 26–28, 32, 35. **Pins that go**: P7–P12 and the `finished` halves of `SavePathsTest`
  (`theEndOfATour…`, `theDemoRideEndsWithTheTour`), `ShortRecordingBoxTest` (two tests), `SavedTourBoxTest`,
  `TourEndPathsTest.deletingAnInterruptedTourMarksItDoneFirst`, `InterruptedLogTest.everyAnswerClears…` (regex ×3),
  `ArrivalBoxTest` (the `keepRecordingAfterArrival` body) – each replaced by its flow of §4.4; field-bug and owner
  pins inside them are kept and re-pointed.
- **E2E**: `CoreFieldE2ETest`, `UnfinishedTourSetupE2ETest`.
- *STOP:* a body needs `Dispatchers.Main` named explicitly where the scope's dispatcher is not it; `routes.restore`
  turns out to write Compose state from `io` (today's call is on IO – keep, but report); the `finished` collector cannot
  stay at its place in the `init` without reading a later-declared member.

### 5.4 Piece 18a – `TourFollow` (state and writes)

- **Members**: §1.3. **Slice** `state/…/ui/tour/TourFollow.kt`: `(plan: PlanDocument, planRoute: PlanRouteSlice,
  recording: RecordingClient, files: FollowStore, prefs: FollowPrefs, rows: TourRows, position: Position,
  host: TourFollowHost, scope, planCopy: CoroutineDispatcher)`. The property initialisers read `files` and `prefs`
  exactly as today (three reads at construction – allowed: restore, like `PlanDocument`). `TourFollowHost`: `route`,
  `stopName(p)`, `pinnedIndices()`, `savedStops(pts)`, `defaultRouteName()`, `shown`, `routeEvent(e)`,
  `invalidateSplits()` (until 18b), `tourChanged(id)`, `savedTourUuid(id)` – or `tours.getTour`.
- **`start()`** = the tour-id collector, called at the place of today's small `init` (1281).
- **Stays**: `followName` / `followLine` / `guidanceEndedByEmptyPlan` / `planGuides` forwarders; `followShown`,
  `followPlanned`, `clearFollow` forwarders (hosts of `planEdit`, `placeAdd`, `mapHost`, `routesHost` read them);
  `setSaved` as a two-line forwarder; the `state` collector still calls `follow.syncNavActivity`, `follow.navVersion`.
- **Rules**: 1, 18–21, 25, 33. **Pins that go**: P1–P3, P13–P15.
- *STOP:* the planCopy dispatcher must be **one object** made once (`Dispatchers.IO.limitedParallelism(1)` in the
  view model, passed in) – two would break the serial order; `followAgain`'s missing `rec != null` guard: keep as is,
  do not add one; `newSessionToken` uses `System.currentTimeMillis()` – keep verbatim, no `Clock`.
- **E2E**: `CoreFieldE2ETest`.

### 5.5 Piece 18b – `TourEdits`

- **Members**: §1.4. **Slice** `state/…/ui/tour/TourEdits.kt`: `(plan: PlanDocument, planEdit: PlanEditSlice,
  planRun: PlanRunSlice, planRoute: PlanRouteSlice, follow: TourFollow, recording: RecordingClient,
  position: Position, routesFlow: RoutesSlice, host: TourEditsHost, say: Say, scope, routing: CoroutineDispatcher)`.
  `TourEditsHost`: `recalc()`, `cellsForNewPoints(before)`, `noPositionForEdit(note, text)`, `planFactors(a)`,
  `stopName(p)`, `pinned`, `continueNavigation()` (until 19).
- **Wiring**: `planEditHost` / `placeAddHost` / `routesHost` answer from the slice (`splitOf`, `oneLeftGoesFromHere`,
  `endGuidanceIfPlanEmpty`, `tourPassed`, `wayThereFrom`, `followShownNow`, `loadedOnTheWay`); `recalc` (still in the
  view model) calls `edits.followCancel?.cancel()`, `edits.followEditedPlan()`.
- **Rules**: 16, 17, 20, 36. **Pins that go**: P16–P18, `FollowLineLegsTest` (two of three tests – the third pins
  `recalc`), `TourEditTest.theRecordingGoesOn…`, `StartAtPointOneTest` 166–167, the `followShownDuringTour` line of
  `SavePathsTest`.
- *STOP:* `followCancel` is `@Volatile` and written from `recalc` too – it must stay one field with the same
  visibility; `answerRouteBack` writes `routeBackAsk` (a `PlanEditSlice` setter that is `private set` today – one
  declaration made public, listed); `followAhead` needs `legCache` + `routingEngine()` exactly as `recalc` uses them.
- **E2E**: `CoreFieldE2ETest`.

### 5.6 Piece 19 – `TourSession`

- **Members**: §1.5. **Slice** `state/…/ui/tour/TourSession.kt`: `(plan, planEdit, planRun, planRoute, placeAdd,
  save: SaveSlice, map: MapViewSlice, tour: TourController, follow: TourFollow, edits: TourEdits, end: TourEndSlice,
  recording: RecordingClient, rows: TourRows, tours: ToursStore, position: Position, host: TourSessionHost, say, scope,
  io, startTimeoutMs = 6_000L, stickyWaitMs = 1_500L)`. `TourSessionHost`: `errorCard`, `headingUp`,
  `secureTourCorridor(line, last, activity)`, `closeMenusForNavigate()` (the one line 2771 minus `followMe`),
  `startModeKnown()`, `shown` (get / set), `routing`, `route`.
- **`start()`** in two calls **at today's places**: `startMode()` (the flip, 1410) directly after `refreshTours()`
  and `start()` (left-over line check, start-up check, the two collectors) where the blocks are – same `init`, same
  order. `mode`'s initial value stays `PLAN`.
- **Stays**: `mode`, `navStartZoom`, `straightAsk` and every action as forwarders (`vm.mode` is read 35×);
  `recalc()` + `refreshTours()` in the `init`; the provisional-position collector.
- **Rules**: 2–6, 13–15, 22, 31. **Pins that go**: P19–P22; `SavePathsTest`'s `save.cancel()` line.
- **E2E**: `CoreFieldE2ETest`, `CoreIndoorStartE2ETest`, `UnfinishedTourSetupE2ETest` (three refs).
- *STOP:* `enter` cannot reach a member except through more than ≈ 12 host functions (then split `enter`'s menu
  closing out as one host call and report); the start-up check's hops must be `withContext(Dispatchers.Main)` –
  with `main` = the scope's dispatcher passed by name; `finally` keeps `NonCancellable`.

## 6. Risks before starting

### 6.1 Where the pattern of pieces 1–15 does not fit

1. **A collector's place in the `init` is behaviour.** `launchSafe` without a context on `Main.immediate` runs the
   body inline to its first suspension; `StateFlow.collect` delivers the current value in that first pass. With a
   service already running (Activity re-created, process alive) the `state` collector therefore runs once **inside
   the constructor**: it may set `followLine`, call `syncNavActivity` → `selectActivity` → `recalc()`. Moving the
   launch into "the last `init`" (the standing brief's rule) would run that pass after map data, sync and the cells
   have started – a different order of effects. → for 17, 18a and 19 `start()` is called **in place**.
2. **The mode flip must stay after `recalc()`**: `recalc` reads `mode == PLAN` (2308); the flip is at 1410. An
   initial value "Navigate if a service runs" in the slice's constructor would change the first route run.
3. **State written by two sides**: the follow files (app and service – by design, `FollowStore`), `followLine`
   (the app's follow writes and the `state` collector), `planBaseline` (`recalc`, the follow code), `followCancel`
   (`recalc`, `followAhead`), `routeBackAsk` (`PlanEditSlice`, the tour's answer), `guidanceEndedByEmptyPlan`
   (`clearFollow` resets, five callers set it right after), `tour.unfinishedPending` (the view model writes into
   `TourController`). Each needs one owner slice and a setter the other side uses – listed in the briefs.
4. **Jobs cancelled from several places**: `checkJob` (new tap, close, open), `startJob` (only the next start – not
   `startFailed`, not `onCleared`), `followCancel` (the next `recalc`, the next `followAhead`). `onCleared` cancels
   none of them by name; the scope does.
5. **Code that runs although the view model is gone**: the service (records, navigates, ends), `TourInterruptedReceiver`
   (boot / update: notification only). The view model's part after a death is the property initialisers + the
   start-up check – rule 33 and 5. No `SavedStateHandle` anywhere.
6. **Prerequisites outside `ui/`**: `RideApply` / `RideStore` in `:app`; the check sheet's types in a Composable file;
   `FakeRecording` cannot go to the shared kit (it implements a `:state` interface); `JdbcTours` lacks four writes.
7. **Hosts multiply before they shrink**: after 16b–18b the later slices are still reached through hosts
   (`enterMode`, `clearFollow`, `continueNavigation`). The proofs of rules 8 and 9 are therefore split – 17 proves
   "called with `rec == null`", 18a "sends nothing then"; only 19's whole-tour flow proves them on real code together.

### 6.2 Latent bugs seen while reading (reported, not fixed)

| # | What | Where | Effect |
| ---: | --- | --- | --- |
| L1 | **Fixed 2026-11-19** (each collector pass guarded and reported; a failed read at a tour end is read as "tour not found"). Was: a collector dies with its first exception: `launchSafe` reports and the `collect` is over. `db.getTour(id)` (and `tourTimes`, `db.getTour(p)`) in the `finished` collector are unguarded. | 1465, 1479, 1482, 1486 | One failed read at a tour's end: `finishedSeen` is already said, so nothing else happens – the app stays in Navigate with no tour, the line stays, the plan is not cleared; **no later tour end is handled** until the app restarts. The track itself is safe (the service stored it). The same shape in the `state` collector (a throw inside `syncNavActivity` → `recalc`). |
| L2 | **Fixed 2026-11-19** (a failed read is reported and read as "no active tour"; a throwing `enter(start)` is reported; `startModeKnown` is set on every non-cancelled path). Was: the start-up check: if `db.findActiveRecording()` throws, `enter(start)` and `startModeKnown = true` never run (the flag is cleared – good). | 1416–1418 | `startModeKnown` stays false for the process: plan links wait on it for ever (`links.startModeKnown()`), `MapDataFlow` and the start camera read it. |
| L3 | `navActivitySeen` (2911) and `planBaseline` (2913) are declared below the `init` whose collector can write them inline at construction; their initialisers then reset them. | 1456–1458 | Harmless today (the next state repeats it). The move changes it silently – name it in 18a's commit, do not "fix". |
| L4 | A failed start can show two boxes: the service's reason (rule 13), and after 6 s "recording did not start" if the first was closed meanwhile; `startJob` is not cancelled by `startFailed`. | 2811–2813, 1436 | Cosmetic. |
| L5 | An activity switch in a **free recording** changes the app and the tour's row – the service is not told and keeps `recActivity`. | 3058–3064; service 330, 1549, 846 | The service's end (`RideApply.finish(…, timeActivity())`), its time prior and `rescueRecording` use the old activity, while the end sheet's ride summary uses the new one. What differs in practice: a bike ride after a Hike→Bike switch is judged as a hike at the end – the service finds a "vehicle ride" in it and stores a rides record (stats unchanged); a rescued row gets the old activity. **Fixed 2026-11-19**: `ACTIVITY_CHANGED` command (`RecordingWire`, extras activity + sac) from the three activity writes of `ActivityCheckSlice`, `RecordedActivity` in the service (also kept when the service itself takes a switch on the route); `RecordedActivityTest`, `RecordedActivityServiceTest`, `ActivityCheckFlowTest`. |
| L6 | The service gone without `finished` while the process lives (`onDestroy` without `finish`): the view model only traces. | 1448; service 1468–1490 | Mode stays Navigate with no tour; the "interrupted tour" question comes only at the next app start. How Navigate looks then: not verified. |
| L7 | `followAgain` sends `FOLLOW_CHANGED` without the `rec != null` guard of its siblings. | 3376 | None today (only reachable during a tour). |
| L8 | `saveUnfinished`: `db.updateStats` unguarded on IO, after the question was closed. | 3580 | A failed write is only in the problem log; the tour stays "active" and is asked about again at the next start. Nothing lost. |
| L9 | The three `db.updateActivity` launches are independent IO jobs. | 3027, 3061, 3114 | Two quick switches can store the older one last. |
| L10 | `FakeRecordingClient.finish` publishes `state = null` before `finished` – the reverse of the service. | test kit | A flow test on it would prove rule 9 wrongly. Fixed in 16a. |

### 6.3 Order and cut

- **16 → 17 → 18 → 19 is right** (the end owns the least state; the start touches everything), with two splits:
  **16a** apart from 16b because the contract move touches `RecordingService.kt`, the planner's lock and the test kit
  and should be judged by itself (and it carries the order pins – the "test first, reviewed on its own" of §4.3 (c) of
  the design); **18 in two** because 390 lines with two concerns (writes to the service / decisions about a plan edit)
  are more than one review can compare body by body.
- Not merged: 16b with 17 (different rules, different E2E risk); 18b with 19 (19's `init` work needs a quiet diff).
- Considered and dropped: 18a before 17 (so that the end's flow test has a real `clearFollow`) – the split proof of
  §6.1 item 7 is enough, and the end first keeps the inside-out order.
- Realistic lines: §0. The view model is ≈ 3,170 lines after 19 (+ what piece 12 changes); the big drop is piece 21.
- After 19: the latent bugs L2 and L5 are worth a piece each (L1 and L2 are fixed, 2026-11-19) – a failing flow test first (cheap then), the fix
  after. They are not part of the move.

### 6.4 Not verified

- That the first pass of a collector really runs inline in the constructor (read from the coroutine rules and
  `SafeLaunch.kt`; not run).
- The bodies of the 35 E2E methods; whether `RouteRunTest` already has the three effect cases of §4.1; whether
  `Navigator` ignores a session whose version did not grow.
- That the web engine compiles `RecordingClient.kt` / `RecState.kt` from `core/` (coroutines are on its classpath
  since piece D; `Navigator.kt` and `TripTime.kt` compile there today).
- `routes.restore` (called on IO in `undoShortRecording`) – whether it writes Compose state there.
- Line counts "back" are estimates; "out" are counted from the spans of §1.
