package com.onpath.app.ui

import com.onpath.app.data.TourKind
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * Every way a plan / tour is saved, overwritten, exported or imported, walked once (owner 2026-11-06: "einmal die Abläufe
 * durchsuchen"). The table and the proposals: docs/design/save-paths.md. These tests pin TODAY's behaviour of the paths
 * that had no test; the one path that loses a plan is reproduced in [endOfTourSaveKeepsThePlan] (ignored until the owner
 * picks a fix) – [todayTheEndOfTourSaveReadsAnAlreadyClearedPlan] breaks when the code changes, and points here.
 */
class SavePathsTest {
    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private fun body(start: String) = vm.substringAfter(start).substringBefore("\n    }\n")

    private val a = GeoPoint(51.17, 6.93)
    private val b = GeoPoint(51.20, 6.77)

    /**
     * End tour with "Plan auch speichern" ON, as AppViewModel does it: [endTour] stops the service and opens Save's name
     * dialog; the service's "finished" (milliseconds later) clears the plan ("the plan leaves the map, saved or not");
     * the dialog's Save then reads the plan's points NOW (saveRoute: `waypoints.toList()`, < 2 points → return).
     */
    private class EndFlow(var plan: List<GeoPoint>) {
        var switchOn = true
        var dialogOpen = false
        val myRoutes = mutableListOf<List<GeoPoint>>()
        var undo: List<GeoPoint>? = null

        /** [AppViewModel.endTour]: stopRecording(); if (withRoute) requestSave(). */
        fun endTour() { dialogOpen = switchOn && RouteRules.isUnsaved(plan, null) }

        /** RecordingService.finished collector: `if (waypoints.isNotEmpty()) clearPlan()` (one Undo step). */
        fun serviceFinished() { if (plan.isNotEmpty()) { undo = plan; plan = emptyList() } }

        /** Save's dialog → Save: confirmSave → saveRoute(name) → `val pts = waypoints.toList(); if (pts.size < 2) return`. */
        fun confirmSave() { dialogOpen = false; val pts = plan; if (pts.size < 2) return; myRoutes += pts }
    }

    @Test fun todayTheEndOfTourSaveReadsAnAlreadyClearedPlan() {
        // the code facts the model above stands on
        val end = body("fun endTour() {")
        assertTrue(end, end.indexOf("stopRecording()") in 0 until end.indexOf("requestSave()"))
        val save = body("fun saveRoute(name: String, then: (() -> Unit)? = null) {")
        assertTrue(save, save.contains("val pts = waypoints.toList()") && save.contains("if (pts.size < 2 || saving) return"))
        val finished = vm.substringAfter("RecordingService.finished.collect { id ->").substringBefore("fun initialCamera()")
        assertTrue(finished.contains("if (waypoints.isNotEmpty()) clearPlan()"))
        assertFalse("nothing waits for the open Save dialog", finished.contains("saveAsk"))
        // a human types the name after the service has finished: the plan is empty when Save reads it
        val f = EndFlow(listOf(a, b))
        f.endTour(); assertTrue(f.dialogOpen)
        f.serviceFinished()
        f.confirmSave()
        assertTrue("today: nothing reaches My routes", f.myRoutes.isEmpty())
        assertEquals("only Undo (in memory) brings it back", listOf(a, b), f.undo)
    }

    @Ignore("field bug (owner 2026-11-06 'a plan made on the phone was gone after the tour'): the end-of-tour Save " +
        "reads the plan after RecordingService.finished cleared it – fix proposals in docs/design/save-paths.md (P1)")
    @Test fun endOfTourSaveKeepsThePlan() {
        val f = EndFlow(listOf(a, b))
        f.endTour(); f.serviceFinished(); f.confirmSave()
        assertEquals(listOf(listOf(a, b)), f.myRoutes)
    }

    /** End tour with rides found (bus, lift …): the ride summary has no plan switch, and none is preset. */
    @Test fun todayTheRideSummaryOffersNoPlanSave() {
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        val rides = screen.substringAfter("is EndAsk.Rides -> RideSummaryDialog(").substringBefore("null -> {}")
        assertFalse(rides, rides.contains("routeLabel") || rides.contains("saveRouteAtEnd"))
        val ask = body("fun askEndTour(saveIfNoRides: Boolean = false) {")
        assertTrue(ask.contains("s != null -> endAsk = EndAsk.Rides(id, s)"))
    }

    /** A saved route loaded during a tour → "Mit dieser Route weiter": an unsaved plan is replaced without a question. */
    @Test fun todayContinueWithALoadedRouteReplacesAnUnsavedPlanWithoutAsking() {
        val unsaved = listOf(a, b)
        assertTrue(RouteRules.isUnsaved(unsaved, null))
        assertEquals(OpenAsk.BLOCKED_DURING_TOUR, RouteRules.openAsk(TourKind.PLANNED, 2, navigating = true, unsaved, null))
        assertEquals(TourLoad.Step.FOLLOW_NOW, TourLoad.step(TourLoad.Answer.CONTINUE))
        assertTrue(body("fun openTour(t: Tour, duringTour: Boolean = false) {").contains("change { clear(); addAll(pts) }"))
    }

    /** "Als Plan nutzen" on a shown track replaces the plan at once (Undo only), also an unsaved one. */
    @Test fun todayPlanFromATrackReplacesThePlanWithoutAsking() {
        val from = body("fun planFromShown() {")
        assertTrue(from.contains("change { clear(); addAll(pts) }") && from.contains("change { clear(); addAll(r.points) }"))
        assertFalse(from.contains("replaceAsk") || from.contains("planUnsaved"))
    }

    /** Process death / app update mid-plan: every edit writes the points, the next start reads them. */
    @Test fun thePlanSurvivesTheProcess() {
        assertTrue(body("private fun change(").contains("prefs.planWaypoints = waypoints.toList()"))
        assertTrue(vm.contains("val waypoints = mutableStateListOf<GeoPoint>().apply { addAll(prefs.planWaypoints) }"))
        assertTrue(vm.contains("private var savedPoints by mutableStateOf(prefs.planSavedPoints)"))
    }

    /** Backups, "Export all" and sync carry saved items only – an unsaved plan lives in the app's prefs alone. */
    @Test fun todayAnUnsavedPlanIsInNoBackup() {
        val backups = File("src/main/java/com/onpath/app/backup/Backups.kt").readText()
        assertFalse(backups.contains("planWaypoints"))
        assertTrue(backups.contains("for (t in db.listTours().filter { it.state == \"done\" })"))
    }

    /** GPX import always makes an imported track; a plan GPX becomes a plan again only via "Als Plan nutzen". */
    @Test fun todayEveryImportIsATrack() {
        val imp = body("fun importGpx(uri: Uri) {")
        assertTrue(imp.contains("db.insertTour(TourKind.IMPORTED,"))
        assertFalse(imp.contains("TourKind.PLANNED"))
        assertTrue(body("fun planFromShown() {").contains("PlanGpx.stopsOf(track, sh.waypoints)"))
    }

    /**
     * The owner's screen 2026-11-06 14:37 (docs/design/save-paths.md "Flow: follow a saved tour while a tour runs"): over
     * a shown track the full tour card (Weiter · Pause · red Tour beenden) stands above the track sheet (Als Plan
     * übernehmen · Tour nachfahren) – 5 buttons, two green full-width ones; "Weiter" keeps the OLD guidance.
     */
    @Test fun todayATrackShownDuringATourHasFiveButtons() {
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(screen.contains("if (vm.cardPlace != null || vm.shown != null) rec?.let {"))
        assertTrue(screen.contains("onContinue = vm::continueNavigation, activity = vm.activity)"))
        assertTrue(screen.contains("else { vm.followShown(); vm.closeShown(); vm.continueNavigation() }"))
        val bar = File("src/main/java/com/onpath/app/ui/TourBar.kt").readText()
        assertTrue("End tour is red in Plan's tour card", bar.contains("border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)"))
        val panel = File("src/main/java/com/onpath/app/ui/Panels.kt").readText().substringAfter("fun TrackPanel(")
            .substringBefore("internal fun trackFollowLabel")
        assertTrue(panel.contains("OutlinedButton(onClick = onPlan") && panel.contains("Button(onClick = onFollow"))
    }

    /** Export: a planned item = the plan GPX (line + route points + pins), a tour = its track. */
    @Test fun exportPlannedIsThePlanGpxTourIsTheTrack() {
        val exp = body("fun exportTour(t: Tour) {")
        assertTrue(exp.contains("if (t.kind == TourKind.PLANNED) com.onpath.app.gpx.PlanGpx.export("))
        assertTrue(exp.contains("PlanGpx.withPins(t.waypoints, t.pinned)"))
        assertTrue(exp.contains("else com.onpath.app.gpx.GpxFile(GpxWriter.fileName("))
    }
}
