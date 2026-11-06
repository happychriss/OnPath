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
 * that had no test. The end of a tour no longer touches the plan (owner 2026-11-06: two things) – [theEndOfATourNeverDropsThePlan].
 */
class SavePathsTest {
    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private fun body(start: String) = vm.substringAfter(start).substringBefore("\n    }\n")

    private val a = GeoPoint(51.17, 6.93)
    private val b = GeoPoint(51.20, 6.77)

    /**
     * Way 1 (owner 2026-11-06, the tour and the plan are two things – replaces P1 and the end sheet's plan switch): a plan
     * never saved is saved at "Los geht's"; ending, saving or deleting the tour leaves the plan on the map and asks nothing
     * about it (PlanAutoSaveTest pins the rule; E2E: CoreFieldE2ETest.endTourKeepsThePlanSavedAtTheStart).
     */
    @Test fun theEndOfATourNeverDropsThePlan() {
        val finished = vm.substringAfter("RecordingService.finished.collect { id ->").substringBefore("fun initialCamera()")
        assertFalse(finished, finished.contains("clearPlan()"))
        assertFalse(body("fun endTourAndDelete() {").contains("requestSave"))
        assertTrue(PlanAutoSave.atStart(points = 2, unsaved = true, changesASavedPlan = false))
        assertTrue("a mode switch still closes Save's dialog", vm.contains("if (saveAsk != null) cancelSave()"))
    }

    /** Way 2: the ride summary has no plan part – nor does any end sheet now. */
    @Test fun theRideSummaryOffersNoPlanSave() {
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
