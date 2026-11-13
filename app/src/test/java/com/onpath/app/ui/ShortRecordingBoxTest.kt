package com.onpath.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The box after a short recording (owner 2026-11-12; review 2026-11-13: the data before the ride choice, the points of
 * every piece, a box that stays until answered – never a timed line): what the view model and MainActivity wire
 * (the controller's rules are in ShortRecordingTest / TourFlowPropertyTest).
 */
class ShortRecordingBoxTest {
    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()
    private val vm = src("ui/AppViewModel.kt")

    @Test fun theBoxBesideTheSavedBox_RueckgaengigAndOk() {
        val main = src("MainActivity.kt")
        assertTrue(main.contains("ShortRecordingDialog(t, onUndo = vm::undoShortRecording, onOk = vm::closeShortRecording)"))
        val box = src("ui/Screens.kt").substringAfter("fun ShortRecordingDialog(").substringBefore("private fun SavedTourActions(")
        assertTrue("the owner's words and the bin's days", box.contains("routes_short_recording_title") && box.contains("RecycleBin.DAYS"))
        assertTrue("Rückgängig · OK in the saved box's row (≥ 48 dp)", box.contains("SavedTourActions(str(Res.string.plan_undo), onUndo, str(Res.string.common_ok), onOk"))
        assertTrue("Back / outside = OK, the bin keeps it", box.contains("onDismissRequest = onOk"))
        assertFalse("never a timed line", vm.contains("ACTION_MS") || src("ui/Notices.kt").contains("NoticeAction"))
    }

    /** The decision reads the points of every piece and the distance as recorded – never RideView.stats of a choice. */
    @Test fun theDecisionReadsThePointsAndTheRecordedDistance() {
        val check = vm.substringAfter("private suspend fun isShortRecording(t: Tour)").substringBefore("\n    }\n")
        assertTrue(check.contains("db.loadSegments(t.id)"))
        assertTrue(check.contains("t.rides?.recorded?.distanceM ?: t.distanceM"))
        assertTrue(check.contains("rides = t.rides?.rides?.isNotEmpty() == true"))
        assertTrue("a read that fails keeps the tour", check.contains(".getOrDefault(false)"))
        val finished = vm.substringAfter("RecordingService.finished.collect { id ->").substringBefore("fun initialCamera()")
        assertTrue("the tour is read once, before the decision", finished.indexOf("val done = withContext(Dispatchers.IO) { db.getTour(id) }") < finished.indexOf("tour.finishedOutcome("))
        assertTrue("the bin write's result decides box or saved box", finished.contains("tour.afterShortBin(done, binned = ok,"))
        // the saved path is as before: place names for the tour and the split parts
        assertTrue(finished.contains("done?.let { nameRecordedTour(it) }"))
        assertTrue(finished.contains("parts.forEach { p -> withContext(Dispatchers.IO) { db.getTour(p) }?.let { nameRecordedTour(it) } }"))
    }

    /** Rückgängig: the one restore path of the bin (RoutesState.restore), the backup file back, then the saved box + name. */
    @Test fun rueckgaengigRestoresAsANormalTourWithItsSavedBox() {
        val undo = vm.substringAfter("fun undoShortRecording() {").substringBefore("\n    }\n")
        assertTrue(undo.contains("tour.takeShortBinned() ?: return"))
        assertTrue(undo.contains("routes.restore(t.id)"))
        assertTrue(undo.contains("Backups.tourChanged(getApplication(), t.id)"))
        assertTrue(undo.indexOf("tour.showFinished(done,") < undo.indexOf("nameRecordedTour(done)"))
        assertTrue("a failed restore is said; the tour stays in the bin", undo.contains("msg_tour_restore_failed"))
        val ok = vm.substringAfter("fun closeShortRecording() {").substringBefore("\n    }\n")
        assertTrue("OK leaves it in the bin, nothing else", ok.contains("tour.takeShortBinned()") && !ok.contains("restore") && !ok.contains("deleteTour"))
    }
}
