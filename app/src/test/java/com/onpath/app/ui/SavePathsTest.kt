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
 * that had no test; the one path that loses a plan is reproduced in [endOfTourSaveKeepsThePlan] – fixed 2026-11-06 (P1: the plan stays
 * while Save's dialog is open); [theEndOfTourKeepsThePlanWhileSavesDialogIsOpen] pins the code facts.
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

        var clearAfterSave = false
        private fun clear() { if (plan.isNotEmpty()) { undo = plan; plan = emptyList() } }

        /** RecordingService.finished collector: the plan leaves (one Undo step) – unless Save's dialog is open. */
        fun serviceFinished() { if (dialogOpen && plan.size >= 2) clearAfterSave = true else clear() }

        /** Save's dialog → Save: saveRoute reads the plan now, then (on success) the end of the tour clears it. */
        fun confirmSave() {
            dialogOpen = false; val end = clearAfterSave; clearAfterSave = false
            val pts = plan; if (pts.size < 2) return; myRoutes += pts; if (end) clear()
        }

        /** Save's dialog → Cancel: nothing saved, the plan leaves as after every tour. */
        fun cancel() { dialogOpen = false; if (clearAfterSave) { clearAfterSave = false; clear() } }
    }

    @Test fun theEndOfTourKeepsThePlanWhileSavesDialogIsOpen() {
        // the code facts the model above stands on (fixed 2026-11-06; E2E: CoreFieldE2ETest.endTourWithSavePlanOnPutsThePlanInMyRoutes)
        val end = body("fun endTour() {")
        assertTrue(end, end.indexOf("stopRecording()") in 0 until end.indexOf("requestSave()"))
        val finished = vm.substringAfter("RecordingService.finished.collect { id ->").substringBefore("fun initialCamera()")
        assertTrue(finished, finished.contains("val pendingSave = saveAsk") &&
            finished.contains("if (waypoints.isNotEmpty() && pendingSave == null) clearPlan()") &&
            finished.contains("saveAsk = pendingSave; clearPlanAfterSave = true"))
        val confirm = body("fun confirmSave(input: String) {")
        assertTrue(confirm, confirm.contains("finishEndOfTourSave()") && confirm.contains("clearPlanAfterSave = false"))
        assertTrue(vm.contains("fun cancelSave() { saveAsk = null; if (clearPlanAfterSave)"))
        assertTrue("a mode switch closes the dialog the same way", vm.contains("if (saveAsk != null) cancelSave()"))
    }

    @Test fun endOfTourSaveKeepsThePlan() {
        val f = EndFlow(listOf(a, b))
        f.endTour(); f.serviceFinished(); f.confirmSave()
        assertEquals(listOf(listOf(a, b)), f.myRoutes)
        assertTrue("then the plan leaves the map", f.plan.isEmpty())
    }

    @Test fun endOfTourCancelClearsThePlanLikeEveryTourEnd() {
        val f = EndFlow(listOf(a, b))
        f.endTour(); f.serviceFinished(); f.cancel()
        assertTrue(f.myRoutes.isEmpty()); assertTrue(f.plan.isEmpty()); assertEquals(listOf(a, b), f.undo)
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
    /**
     * Ways 6 + 8 (P3, done 2026-11-06 with flow map M1): "Mit dieser Route weiter", "Als Plan übernehmen" and following a
     * track during a tour ask "Plan speichern?" first when they would replace a plan that is not saved ([ReplaceAsk]).
     */
    @Test fun replacingAnUnsavedPlanAsksFirst() {
        val unsaved = listOf(a, b)
        assertTrue(RouteRules.isUnsaved(unsaved, null))
        assertTrue(ReplaceAsk.asks(unsaved = true, planPoints = 2))
        assertTrue(ReplaceAsk.asks(unsaved = true, planPoints = 1))
        assertFalse("a saved plan stays in My routes", ReplaceAsk.asks(unsaved = false, planPoints = 2))
        assertFalse("no plan, nothing to lose", ReplaceAsk.asks(unsaved = true, planPoints = 0))
        assertEquals(OpenAsk.BLOCKED_DURING_TOUR, RouteRules.openAsk(TourKind.PLANNED, 2, navigating = true, unsaved, null))
        assertTrue(body("fun answerTourLoad(").contains("TourLoad.Step.FOLLOW_NOW -> unlessUnsaved("))
        assertTrue(vm.contains("fun planFromShown() = unlessUnsaved(\"Track → plan\") { planFromShownNow() }"))
        assertTrue(vm.contains("fun planFromTour(t: Tour) = unlessUnsaved("))
        assertTrue(vm.contains("fun followShownDuringTour() = unlessUnsaved("))
        val ask = body("private fun unlessUnsaved(what: String, go: () -> Unit) {")
        assertTrue(ask, ask.contains("ReplaceAsk.asks(planUnsaved, waypoints.size)"))
        val answer = body("fun answerReplaceUnsaved(a: ReplaceAsk.Answer) {")
        assertTrue(answer, answer.contains("ReplaceAsk.Answer.SAVE -> askSave { go() }") && answer.contains("ReplaceAsk.Answer.CANCEL -> Unit"))
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
        assertTrue(body("private fun planFromShownNow() {").contains("PlanGpx.stopsOf(track, sh.waypoints)"))
    }

    /**
     * The owner's screen 2026-11-06 14:37 (docs/design/save-paths.md "Flow: follow a saved tour while a tour runs") had 5
     * buttons over a shown track. F1 as decided after the pictures (onpath.private#33, "zu kompliziert"): during a tour a
     * done tour / track tapped in My routes only asks "Dieser Tour folgen?" (Abbrechen · Folgen) – no track sheet, the plan
     * sheet never shows a foreign tour as a status; "Als Plan übernehmen" lives in the row's ⋮. Without a tour the track
     * sheet keeps green "Tour nachfahren" and blue "Als Plan übernehmen".
     */
    @Test fun aTrackTappedDuringATourOnlyAsksToFollow() {
        assertEquals(OpenAsk.FOLLOW_DURING_TOUR, RouteRules.openAsk(TourKind.RECORDED, 0, navigating = true, listOf(a, b), null))
        assertEquals(OpenAsk.NONE, RouteRules.openAsk(TourKind.RECORDED, 0, navigating = false, listOf(a, b), null))
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertFalse(screen.contains("TourRunningBar("))
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
