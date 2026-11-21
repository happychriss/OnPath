package com.onpath.app.ui

import com.onpath.app.data.TourKind
import com.onpath.app.domain.OpenAsk
import com.onpath.app.domain.PlanAutoSave
import com.onpath.app.domain.RouteRules
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every way a plan / tour is saved, overwritten, exported or imported, walked once (owner 2026-11-06: "einmal die Abläufe
 * durchsuchen"). The table and the proposals: docs/design/save-paths.md. The behaviour of the ways is flow tests of the real
 * slices since the test clean-up G04 (2026-11-19); what stays here is what a flow cannot see – the thread a call runs on, the
 * app's adapters and screens, the backup / sync / export files:
 *  - the end of a tour asks nothing about the plan and clears it the "Neu planen" way (owner 2026-11-07), was
 *    theEndOfATourGoesBackToTheStartTheNeuPlanenWay = TourEndFlowTest.aSavedTourIsHandledInTheOrderOfTheRulesBehindTheIoHop… (the host log
 *    holds clearPlan, no ask), .aDeletedTourGoesToTheBin…, SaveFlowTest.losGehtsSavesAPlanNeverSavedWithoutAskingAndNotTwice (SAVE_NEW),
 *    SaveFlowTest.aReplacedUnsavedPlanIsKeptUnderZuletztAtOnceAndASavedOneAsItIsIsNot (Drafts), the adapter line TourWiringTest;
 *  - the demo ride ends with the tour (Pixel review 2026-11-06) = TourEndFlowTest (host log: demoEndedWithTour after clearFollow) + the
 *    adapter line in TourWiringTest;
 *  - replacing a plan keeps it under Zuletzt (owner 2026-11-06), was replacingAPlanKeepsItUnderZuletzt = RoutesFlowTest
 *    .aPlannedRouteBecomesThePlanAfterTheOldOneWentUnderZuletzt (a route opened), the track-to-plan flows, PlanLinkFlowTest / RoutesGoWaitTest
 *    (a plan link), RoutesFlowTest "during the tour" (follow a track);
 *  - a track made a plan is one Undo step, was planFromATrackIsOneUndoStep = the same RoutesFlowTest cases (host.change log "change N points").
 */
class SavePathsTest {
    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()

    private val a = GeoPoint(51.17, 6.93)
    private val b = GeoPoint(51.20, 6.77)

    /**
     * Tester review 2026-11-07: the draft is written BEFORE the plan leaves the map – not after a wait of up to 5 s for a
     * place name in the background (a kill in that window lost the plan). The write, its order and the name that follows
     * are behaviour tests now (RecentPlansTest.theDraftIsWrittenBeforeThePlanLeavesTheMapAndTheNameFollows,
     * .aNameThatNeverComesLosesNothing); (SaveFlowTest.aReplacedUnsavedPlanIsKeptUnderZuletztAtOnce…); here only that the slice hands the plan over on the calling thread.
     */
    @Test fun thePlanIsHandedToZuletztBeforeItLeavesTheMap() {
        val slice = com.onpath.app.testkit.sourceFile("ui/routes/SaveState.kt").readText() // keepAsDraft moved there (piece 3)
        val keep = slice.substringAfter("fun keepAsDraft(why: String) {").substringBefore("\n    }\n")
        assertTrue(keep, keep.contains("recent.keep("))
        assertFalse("handed over at once, no background work before it", keep.contains("launch"))
        // SaveState has no coroutines: deferring is `background.` (a write off the thread) or `names.` (a wait for the name)
        assertFalse("handed over at once, nothing deferred (background)", keep.contains("background."))
        assertFalse("handed over at once, no wait for a name (names)", keep.contains("names."))
    }

    /** Way 2: the ride summary has no plan part – nor does any end sheet now. */
    @Test fun theRideSummaryOffersNoPlanSave() {
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        val rides = screen.substringAfter("is EndAsk.Rides -> RideQuestionDialog(").substringBefore("null -> {}")
        assertFalse(rides, rides.contains("routeLabel") || rides.contains("saveRouteAtEnd"))
        // rides found → the summary (and nothing about the plan): TourEndFlowTest.endTourWithARideShowsTheSummaryAfterTheCheckOnIo / theArrivalBoxsEndTourOfATourWithARideShowsTheSummaryAndStopsNothing
    }

    /**
     * Process death / app update mid-plan: every edit writes the points, the next start reads them – behaviour now
     * (ui/plan/PlanDocumentTest.everyEditIsStoredAndTheNextStartReadsIt, .everyOtherWayThePointsChangeIsStoredToo,
     * .theSavedLinkSurvivesTheProcessAndCanBeTakenAway, on the real preferences). Here only the wiring: the view model's
     * plan is that document on the app's preferences, and every edit goes through it.
     */
    @Test fun thePlanSurvivesTheProcess() {
        assertTrue(vm.contains("val plan: com.onpath.app.ui.plan.PlanDocument = com.onpath.app.ui.plan.PlanDocument(com.onpath.app.ui.plan.PrefsPlanStore(prefs))"))
        // every edit is stored at once and read back at the next start: PlanEditFlowTest (restarted() re-reads the store after each edit)
        assertFalse("no second place that writes the plan's preferences", vm.contains("prefs.planWaypoints") || vm.contains("prefs.planSavedPoints"))
    }

    /** Backups, "Export all" and the browser sync carry saved items only – no plan under Zuletzt (owner 2026-11-06). */
    @Test fun draftsAreInNoBackupAndNoSync() {
        val backups = File("src/main/java/com/onpath/app/backup/Backups.kt").readText()
        assertFalse(backups.contains("planWaypoints"))
        assertTrue(backups.contains("for (t in db.listTours().filter { it.state == \"done\" && !it.draft })"))
        assertTrue(backups.contains("(db.listTours() + db.listDeleted()).filter { !it.draft }"))
        val sync = File("src/main/java/com/onpath/app/plansync/DbTourStore.kt").readText()
        assertTrue(sync.contains("&& !it.draft }"))
    }

    /**
     * The owner's screen 2026-11-06 14:37 (docs/design/save-paths.md "Flow: follow a saved tour while a tour runs") had 5
     * buttons over a shown track. F1 as decided after the pictures (onpath.private#33, "zu kompliziert"): during a tour a
     * done tour / track tapped in My routes is followed at once (owner 2026-11-06 22:05, was "Dieser Tour folgen?") – no track sheet, the plan
     * sheet never shows a foreign tour as a status; "Als Plan übernehmen" lives in the row's ⋮. Without a tour the track
     * sheet keeps green "Tour nachfahren" and blue "Als Plan übernehmen".
     */
    @Test fun aTrackTappedDuringATourOnlyAsksToFollow() {
        assertEquals(OpenAsk.FOLLOW_DURING_TOUR, RouteRules.openAsk(TourKind.RECORDED, 0, navigating = true, listOf(a, b), null))
        assertEquals(OpenAsk.NONE, RouteRules.openAsk(TourKind.RECORDED, 0, navigating = false, listOf(a, b), null))
        val panel = File("src/main/java/com/onpath/app/ui/Panels.kt").readText().substringAfter("fun TrackPanel(")
            .substringBefore("internal fun trackFollowLabel")
        assertTrue(panel.contains("OutlinedButton(onClick = onPlan") && panel.contains("Button(colors = ActionButtons.planGo(), onClick = onFollow"))
        assertTrue("Als Plan übernehmen is blue", panel.contains("colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = OnPathColors.ActionEdit)"))
        val menu = File("src/main/java/com/onpath/app/ui/Screens.kt").readText().substringAfter("internal fun TourMenuItems(")
        assertTrue(menu.contains("if (onPlanFrom != null) DropdownMenuItem(text = { Text(str(Res.string.plan_from_track), color = OnPathColors.ActionEdit) }"))
    }

    /** Export: a planned item = the plan GPX (line + route points + pins), a tour = its track. */
    @Test fun exportPlannedIsThePlanGpxTourIsTheTrack() {
        // moved with the export to ImportExport (rebuild phase 2 piece 7)
        val imp = File("src/main/java/com/onpath/app/ui/ImportExport.kt").readText()
        val a = imp.indexOf("    fun exportTour(t: Tour) {"); assertTrue("exportTour not found", a >= 0)
        val exp = imp.substring(a).substringBefore("\n    }\n")
        assertTrue(exp.contains("if (t.kind == TourKind.PLANNED) com.onpath.app.gpx.PlanGpx.export("))
        assertTrue(exp.contains("PlanGpx.withPins(t.waypoints, t.pinned)"))
        assertTrue(exp.contains("else com.onpath.app.gpx.GpxFile(GpxWriter.fileName("))
    }

    /**
     * Owner 2026-11-07: a loaded saved plan changed and started with "Los geht's" updates the ORIGINAL route (like
     * ☰ Plan speichern) – no "Name (2)" copies; the version before goes under Zuletzt.
     * (The one-line rule of the deleted PlanAutoSaveTest; owner 2026-11-06: the tour and the plan are two things, like Komoot / Strava –
     * the behaviour is SaveFlowTest.losGehtsOnASavedPlanChangedSince… on the real slice.)
     */
    @Test fun aSavedPlanChangedSinceUpdatesTheOriginal() =
        assertEquals(PlanAutoSave.AtStart.SAVE_OVERWRITE, PlanAutoSave.atStart(points = 3, unsaved = true, changesASavedPlan = true))

    /**
     * Ways 6 + 8 and following a track during a tour (owner 2026-11-06, "Zuletzt"): nothing asks to save any more – the
     * plan they replace goes under My routes → Zuletzt ([AppViewModel.keepAsDraft]) when it is not saved as it is. The behaviour is
     * RoutesFlowTest / PlanLinkFlowTest; what no flow can see stays here: no question is left in the view model, and no copy of the
     * rule (every `keepAsDraft("replaced by …")` is a slice's).
     */
    @Test fun replacingAPlanAsksNothingAndTheViewModelHoldsNoCopyOfTheRule() {
        assertFalse(vm.contains("ReplaceAsk") || vm.contains("replaceUnsavedAsk") || vm.contains("replaceAsk"))
        assertEquals("…and no copy of that rule is left in the view model", 0, vm.split("keepAsDraft(\"replaced by").size - 1)
    }
}
