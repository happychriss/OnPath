package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-06 (onpath.private#35, after seeing the line in the plan sheet: "die zweite Box ist doch besser"): after
 * "Tour speichern" the box "Tour gespeichert" again, slim – the name, the numbers, moving time / breaks / planned vs.
 * needed, "In Meine Touren ›" and ONE button OK. No delete (the end sheet just offered it), no export (My routes ⋮);
 * nothing about the saved tour in the plan sheet or the mode pill.
 */
class SavedTourBoxTest {
    private fun src(f: String) = sourceFile(f).readText()
    private val vm = src("ui/AppViewModel.kt")

    @Test fun theBoxAfterTheEndSheet() {
        val main = src("MainActivity.kt")
        assertTrue(main.contains("SavedTourDialog(t, vm.tour.finishedTimes, onOpen = vm::openSavedTour, onOk = vm::closeSavedTour)"))
        val box = src("ui/Screens.kt").substringAfter("fun SavedTourDialog(").substringBefore("/** The items of")
        assertTrue(box.contains("Res.string.routes_track_saved") && box.contains("StatsRow(") && box.contains("TourTimesText(it)"))
        assertTrue(box.contains("Res.string.common_ok") && box.contains("Res.string.routes_saved_open"))
        assertFalse("no delete, no export in it", box.contains("common_delete") || box.contains("export"))
        // the tour still gets its place name and is still the one just saved (backups / E2E read it): a behaviour flow since piece 17 –
        // TourEndFlowTest.aSavedTourIsHandledInTheOrderOfTheRulesBehindTheIoHopWithNoTourRecording (the tour read on io, the saved box,
        // its name and its split parts' names asked for, each with the box already showing)
    }

    // "In Meine Touren ›" opens My routes → Gemacht with the tour marked (field bug 2026-11-06 22:30) – checked for real in
    // ReleaseRundgang.flow07_endTourSaveListedInDone, no source-text test (test audit 2026-11-06)

    @Test fun nothingInThePlanSheet() {
        assertFalse(src("ui/PlanPanel.kt").contains("savedTour"))
        assertFalse(src("ui/MapScreen.kt").contains("savedTour"))
        assertFalse(vm.contains("movedOn"))
    }
}
