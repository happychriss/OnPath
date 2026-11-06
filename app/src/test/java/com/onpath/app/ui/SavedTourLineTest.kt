package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-06 (onpath.private#35, "Weglassen"): after "Tour speichern" in the end sheet no second box "Tour
 * gespeichert" (Löschen · GPX exportieren · Fertig, whose Löschen asked again). Instead one ANZEIGE line in the plan sheet
 * "Tour gespeichert – in Meine Touren" (+ moving time / breaks, which only the box showed); a tap opens the tour as from
 * My routes; it goes when the user does something new – never by time. Export and delete are in My routes → ⋮.
 */
class SavedTourLineTest {
    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()
    private val vm = src("ui/AppViewModel.kt")
    private fun body(start: String) = vm.substringAfter(start).substringBefore("\n    }\n")

    @Test fun noBoxAfterTheEndSheet() {
        val main = src("MainActivity.kt")
        assertFalse(main.contains("FinishedTourDialogs") || main.contains("vm.finishedTour?.let"))
        assertFalse(src("ui/Screens.kt").contains("fun FinishedDialog("))
        // the tour still gets its place name and is still the one just saved (backups / E2E read it)
        val finished = vm.substringAfter("RecordingService.finished.collect { id ->").substringBefore("fun initialCamera()")
        assertTrue(finished.contains("finishedTour = withContext(Dispatchers.IO) { db.getTour(id) }"))
        assertTrue(finished.contains("finishedTour?.let { nameRecordedTour(it) }"))
        assertTrue(finished.contains("parts.forEach { p -> withContext(Dispatchers.IO) { db.getTour(p) }?.let { nameRecordedTour(it) } }"))
    }

    @Test fun theLineInThePlanSheet() {
        val map = src("ui/MapScreen.kt")
        assertTrue(map.contains("savedTourText = vm.finishedTour?.takeIf { rec == null }?.let { str(Res.string.plan_tour_saved_line) }"))
        assertTrue(map.contains("onOpenSavedTour = vm::openSavedTour"))
        val panel = src("ui/PlanPanel.kt")
        assertTrue(panel.contains("SavedTourLine(ui.savedTourText, cb.onOpenSavedTour)"))
        assertTrue(panel.contains("ui.savedTourTimes?.let { Box(Modifier.padding(start = 28.dp, bottom = 4.dp)) { TourTimesText(it) } }"))
        // a tap: the tour opens as from My routes (the same asks)
        val open = body("fun openSavedTour() {")
        assertTrue(open, open.contains("requestOpen(t)"))
    }

    /** It goes with something new: a point set / a plan loaded (not the end of a tour, which only takes points away). */
    @Test fun itGoesWhenTheUserDoesSomethingNew() {
        assertTrue(body("private fun change(block: MutableList<GeoPoint>.() -> Unit) {").contains("if (waypoints.size > before.size) movedOn()"))
        assertTrue(vm.contains("fun openRoutes() { movedOn();"))
        assertTrue(vm.contains("fun openSearch() { movedOn();"))
        assertTrue(body("fun startRecording() {").contains("movedOn()"))
        assertTrue(body("fun startNavigation(force: Boolean = false) {").contains("movedOn()"))
        // never by time
        val moved = body("private fun movedOn() {")
        assertFalse(moved, moved.contains("delay("))
    }
}
