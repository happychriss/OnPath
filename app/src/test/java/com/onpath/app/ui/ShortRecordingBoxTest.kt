package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
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
    private fun src(f: String) = sourceFile(f).readText()
    private val vm = src("ui/AppViewModel.kt")

    @Test fun theBoxBesideTheSavedBox_RueckgaengigAndOk() {
        val main = src("MainActivity.kt")
        assertTrue(main.contains("ShortRecordingDialog(t, onUndo = vm.tourEnd::undoShortRecording, onOk = vm.tourEnd::closeShortRecording)"))
        val box = src("ui/Screens.kt").substringAfter("fun ShortRecordingDialog(").substringBefore("private fun SavedTourActions(")
        assertTrue("the owner's words and the bin's days", box.contains("routes_short_recording_title") && box.contains("RecycleBin.DAYS"))
        assertTrue("Rückgängig · OK in the saved box's row (≥ 48 dp)", box.contains("SavedTourActions(str(Res.string.plan_undo), onUndo, str(Res.string.common_ok), onOk"))
        assertTrue("Back / outside = OK, the bin keeps it", box.contains("onDismissRequest = onOk"))
        assertFalse("never a timed line", vm.contains("ACTION_MS") || src("ui/Notices.kt").contains("NoticeAction"))
    }

    // The decision (the points of every piece, the distance as recorded, a ride part, a read that fails keeps the tour), the tour read once
    // before it, the bin write's result deciding box or saved box, and Rückgängig / OK are behaviour flows since piece 17 – TourEndFlowTest:
    // aShortRecordingIsBinnedWithItsBoxNotSaved, aShortRecordingWhoseBinWriteFailsIsKeptWithTheSavedBox, aReadThatFailsForTheShortCheckKeepsTheTour,
    // aShortLookingTourWithAFoundRideIsNeverBinned, theDistanceBeforeTheRideChoiceDecidesNotTheChosenOne, aGapPieceFarAwayKeepsTheTour,
    // undoBringsTheShortTourBackAsANormalTourInTheOrderOfTheRule, aFailedRestoreSaysSoKeepsTheTourInTheBinAndReadsTheList,
    // okOnTheShortBoxLeavesTheTourInTheBin.
}
