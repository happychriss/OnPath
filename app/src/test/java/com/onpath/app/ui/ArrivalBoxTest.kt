package com.onpath.app.ui

import com.onpath.app.navigation.NavNotification
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.nav_arrived_title
import com.onpath.strings.nav_end_tour
import com.onpath.strings.nav_keep_recording
import com.onpath.strings.nav_notif_arrived
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Du bist angekommen." (owner 2026-11-07): the box offers "Weiter aufzeichnen" and "Tour beenden"; "Tour beenden" saves
 * the tour (the ride check first) and the app is back at the start as after "✕ Neu planen" (SavePathsTest
 * .theEndOfATourGoesBackToTheStartTheNeuPlanenWay, TourFlowPropertyTest.endingGoesBackToTheStartAndKeepsThePlan). Pictures:
 * ScreenshotTest / GermanScreenshotTest / SlideLabelLargeFont(German)Test `arrival`; E2E CoreFieldE2ETest
 * .arrivalEndTourBringsTheStartScreen.
 */
class ArrivalBoxTest {
    private val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
    private val bar = File("src/main/java/com/onpath/app/ui/TourBar.kt").readText()

    @Test fun theOwnersWords() {
        assertEquals("Weiter aufzeichnen", de(Res.string.nav_keep_recording))
        assertEquals("Tour beenden", de(Res.string.nav_end_tour))
        assertEquals("Keep recording", en(Res.string.nav_keep_recording))
        assertEquals("End tour", en(Res.string.nav_end_tour))
        assertEquals("the buttons say it: the title only tells", "Du bist angekommen.", de(Res.string.nav_arrived_title))
    }

    /** "Tour beenden" goes the End tour way: the ride check, then saved at once (no second question). */
    @Test fun endTourSavesThroughTheRideCheckAndKeepRecordingOnlyClosesTheBox() {
        val call = screen.substringAfter("ArrivalDialog(").substringBefore("activity = vm.activity")
        assertTrue(call, call.contains("onEnd = { vm.askEndTour(saveIfNoRides = true) }"))
        assertTrue(call, call.contains("onDelete = vm::endTourAndDelete"))
        assertTrue(call, call.contains("onKeepRecording = { vm.keepRecordingAfterArrival() }"))
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val keep = vm.substringAfter("fun keepRecordingAfterArrival() {").substringBefore("\n    }\n")
        assertTrue(keep.contains("arrivalAsk = false"))
        assertFalse("the recording goes on", keep.contains("stop") || keep.contains("end("))
    }

    /**
     * The box comes while riding (raindrop, glove on the handlebar phone): it stays until a button is pressed – no swipe,
     * no tap beside it (ui-style "nothing disappears by itself"); every press within EndSheetGuard of it appearing ignored.
     */
    @Test fun theBoxStaysUntilAnsweredAndIgnoresEarlyPresses() {
        assertTrue(bar.contains("EndSheet(onKeepRecording, inline, answerOnly = true)"))
        assertTrue(bar.contains("confirmValueChange = { !answerOnly || it != androidx.compose.material3.SheetValue.Hidden }"))
        val content = bar.substringAfter("private fun ArrivalContent(").substringBefore("\n}\n")
        for (b in listOf("Tour beenden (arrival)", "Weiter aufzeichnen (arrival)", "Tour löschen (arrival)"))
            assertTrue(b, content.contains("guardedTap(\"$b\", shownAt"))
        assertTrue("the delete and bin note as in the end sheet", content.contains("EndDeleteArea(delete)"))
    }

    /** Two verb buttons in one row (confirm right); stacked only when the longer label does not fit its half. */
    @Test fun sideBySideOnlyWhenBothLabelsFitTheirHalf() {
        assertTrue(ArrivalButtons.sideBySide(rowW = 1000, keepW = 450, endW = 400, gap = 20))
        assertTrue("exactly fits", ArrivalButtons.sideBySide(rowW = 1000, keepW = 490, endW = 490, gap = 20))
        assertFalse("the longer one decides", ArrivalButtons.sideBySide(rowW = 1000, keepW = 491, endW = 200, gap = 20))
        assertFalse(ArrivalButtons.sideBySide(rowW = 1000, keepW = 200, endW = 600, gap = 20))
    }

    /** With the phone locked the lock screen says it too, not "0 m to go" (the box waits in the app). */
    @Test fun theLockScreenSaysArrived() {
        val t = NavNotification.arrived()
        assertEquals(Txt.s(Res.string.nav_arrived_title), t.title)
        assertEquals(Txt.s(Res.string.nav_notif_arrived), t.text)
        val svc = File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
        val nav = svc.substringAfter("private fun navNotification()").substringBefore("} catch")
        assertTrue(nav, nav.contains("if (arrived && st != null) com.onpath.app.navigation.NavNotification.arrived()"))
    }

    private fun de(r: org.jetbrains.compose.resources.StringResource) = withLocale("de") { Txt.s(r) }
    private fun en(r: org.jetbrains.compose.resources.StringResource) = withLocale("en") { Txt.s(r) }
    private fun <T> withLocale(tag: String, f: () -> T): T {
        val old = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
        try { return f() } finally { java.util.Locale.setDefault(old) }
    }
}
