package com.onpath.app.ui

import com.onpath.app.data.TourKind
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every way a plan / tour is saved, overwritten, exported or imported, walked once (owner 2026-11-06: "einmal die Abläufe
 * durchsuchen"). The table and the proposals: docs/design/save-paths.md. These tests pin TODAY's behaviour of the paths
 * that had no test. The end of a tour asks nothing about the plan and clears it the "Neu planen" way (owner 2026-11-07) –
 * [theEndOfATourGoesBackToTheStartTheNeuPlanenWay].
 */
class SavePathsTest {
    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private fun body(start: String) = vm.substringAfter(start).substringBefore("\n    }\n")

    private val a = GeoPoint(51.17, 6.93)
    private val b = GeoPoint(51.20, 6.77)

    /**
     * Way 1 (owner 2026-11-06, the tour and the plan are two things; owner 2026-11-07 "Tour beenden" → back to the start
     * as after "✕ Neu planen"): a plan never saved is saved at "Los geht's"; ending, saving or deleting the tour asks
     * nothing about the plan and then clears it the ONE clear way – [AppViewModel.clearPlan]: kept under Zuletzt when
     * changed on the way, one Undo brings it back (E2E: CoreFieldE2ETest.endTourSavesTheTourKeepsThePlanAndClearsTheMap,
     * .arrivalEndTourBringsTheStartScreen).
     */
    @Test fun theEndOfATourGoesBackToTheStartTheNeuPlanenWay() {
        val finished = vm.substringAfter("RecordingService.finished.collect { id ->").substringBefore("fun initialCamera()")
        assertTrue(finished, finished.contains("afterTourEnded()"))
        // after the tour bookkeeping (saved box / bin, the list refreshed, Plan entered) – never before the tour is stored
        assertTrue(finished.indexOf("refreshTours()") < finished.indexOf("afterTourEnded()"))
        assertTrue(finished.indexOf("tour.showFinished(") < finished.indexOf("afterTourEnded()"))
        val after = body("private fun afterTourEnded() {")
        assertTrue("no parallel path: the Neu planen clear", after.contains("clearPlan()"))
        assertFalse("nothing asked about the plan", after.contains("requestSave") || after.contains("saveAsk"))
        assertTrue("clearPlan keeps an unsaved plan under Zuletzt first", body("fun clearPlan() {").trimStart().startsWith("keepAsDraft(\"cleared\")"))
        assertFalse(body("fun endTourAndDelete() {").contains("requestSave"))
        assertEquals(PlanAutoSave.AtStart.SAVE_NEW, PlanAutoSave.atStart(points = 2, unsaved = true, changesASavedPlan = false))
        // the plan saved at Los geht's and unchanged is no draft (it is in My routes); changed on the way → Zuletzt
        assertFalse(Drafts.keeps(points = 2, unsaved = false)); assertTrue(Drafts.keeps(points = 2, unsaved = true))
        assertTrue("a mode switch still closes Save's dialog", vm.contains("if (saveAsk != null) cancelSave()"))
    }

    /**
     * Tester review 2026-11-07: the draft is written BEFORE the plan leaves the map – not after a wait of up to 5 s for a
     * place name in the background (a kill in that window lost the plan). The name follows (RoutesFlowPropertyTest
     * .theDraftIsWrittenBeforeTheNameAndANameThatNeverComesLosesNothing).
     */
    @Test fun theDraftIsWrittenBeforeThePlanLeavesTheMap() {
        val keep = body("private fun keepAsDraft(why: String) {")
        val write = keep.indexOf("routes.keepDraft(")
        assertTrue(keep, write >= 0)
        assertTrue("written before any background work", keep.indexOf("launchSafe") < 0 || write < keep.indexOf("launchSafe"))
        assertTrue("written before the name wait", write < keep.indexOf("NAME_WAIT_MS"))
        assertTrue("the place name follows as a rename", keep.contains("routes.renameDraft("))
    }

    /** Way 2: the ride summary has no plan part – nor does any end sheet now. */
    @Test fun theRideSummaryOffersNoPlanSave() {
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        val rides = screen.substringAfter("is EndAsk.Rides -> RideSummaryDialog(").substringBefore("null -> {}")
        assertFalse(rides, rides.contains("routeLabel") || rides.contains("saveRouteAtEnd"))
        // rides found → the summary (and nothing about the plan): TourEndPathsTest.endTourAndArrivalRunTheRideCheck
    }

    /**
     * Ways 6 + 8 and following a track during a tour (owner 2026-11-06, "Zuletzt"): nothing asks to save any more – the
     * plan they replace goes under My routes → Zuletzt ([AppViewModel.keepAsDraft]) when it is not saved as it is.
     */
    @Test fun replacingAPlanKeepsItUnderZuletzt() {
        assertTrue(Drafts.keeps(points = 2, unsaved = true))
        assertFalse("a saved plan as it is stays in My routes", Drafts.keeps(points = 2, unsaved = false))
        assertFalse("one point is no plan worth keeping", Drafts.keeps(points = 1, unsaved = true))
        assertTrue(vm.contains("fun followShownDuringTour() = unlessUnsaved("))
        assertFalse(vm.contains("ReplaceAsk") || vm.contains("replaceUnsavedAsk") || vm.contains("replaceAsk"))
        assertTrue(body("fun clearPlan() {").contains("keepAsDraft(\"cleared\")"))
        // every way a plan is replaced: a route opened, a plan link, a track made a plan
        assertTrue(vm.split("keepAsDraft(\"replaced by").size - 1 >= 3)
    }

    /** Making a plan of a track still replaces the plan in one Undo step (after the question above). */
    @Test fun planFromATrackIsOneUndoStep() {
        val from = body("private fun planFromShownNow() {")
        assertTrue(from.contains("change { clear(); addAll(pts) }") && from.contains("change { clear(); addAll(r.points) }"))
    }

    /** Process death / app update mid-plan: every edit writes the points, the next start reads them. */
    @Test fun thePlanSurvivesTheProcess() {
        assertTrue(body("private fun change(").contains("prefs.planWaypoints = waypoints.toList()"))
        assertTrue(vm.contains("val waypoints = mutableStateListOf<GeoPoint>().apply { addAll(prefs.planWaypoints) }"))
        assertTrue(vm.contains("private var savedPoints by mutableStateOf(prefs.planSavedPoints)"))
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
        assertTrue(panel.contains("OutlinedButton(onClick = onPlan") && panel.contains("Button(colors = ActionButtons.go(), onClick = onFollow"))
        assertTrue("Als Plan übernehmen is blue", panel.contains("colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = OnPathColors.ActionEdit)"))
        val menu = File("src/main/java/com/onpath/app/ui/Screens.kt").readText().substringAfter("internal fun TourMenuItems(")
        assertTrue(menu.contains("if (onPlanFrom != null) DropdownMenuItem(text = { Text(str(Res.string.plan_from_track), color = OnPathColors.ActionEdit) }"))
    }

    /** Export: a planned item = the plan GPX (line + route points + pins), a tour = its track. */
    @Test fun exportPlannedIsThePlanGpxTourIsTheTrack() {
        val exp = body("fun exportTour(t: Tour) {")
        assertTrue(exp.contains("if (t.kind == TourKind.PLANNED) com.onpath.app.gpx.PlanGpx.export("))
        assertTrue(exp.contains("PlanGpx.withPins(t.waypoints, t.pinned)"))
        assertTrue(exp.contains("else com.onpath.app.gpx.GpxFile(GpxWriter.fileName("))
    }
}
