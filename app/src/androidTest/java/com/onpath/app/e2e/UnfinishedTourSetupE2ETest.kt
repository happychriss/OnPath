package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.onpath.app.data.Prefs
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.recording.RecordingService
import com.onpath.app.setup.CheckId
import com.onpath.app.setup.Felt
import com.onpath.app.ui.AppMode
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.routes_unfinished_continue
import com.onpath.strings.routes_unfinished_title
import com.onpath.strings.setup_continue
import com.onpath.strings.setup_later
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Resume an interrupted tour (core feature: a recording survives a force-stop) when the phone setup has a step open
 * (crash hunt 2026-11-12, emulator): "Interrupted tour" → Continue opens the phone setup guide. The guide must be in
 * front of the question – a modal dialog over it swallowed every touch, Continue and Back did nothing, only Save /
 * Delete got the user out. Back from the guide brings the question back (nothing lost), finishing the guide continues
 * the recording into the same tour, once.
 */
@RunWith(AndroidJUnit4::class)
class UnfinishedTourSetupE2ETest : E2EBase() {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var tourId = -1L

    @After fun cleanTour() {
        if (tourId < 0) return
        runCatching { RecordingService.send(ctx, RecordingService.ACTION_STOP) }
        runCatching { TourDb.get(ctx).deleteTour(tourId) }
    }

    @Test fun continueWithASetupStepOpenShowsTheGuideAndGoesOn() {
        launch(beforeLaunch = {
            // an interrupted recording in the database (the service is not running: the app asks what to do with it)
            tourId = TourDb.get(ctx).insertTour(TourKind.RECORDED, "Interrupted", "HIKE", 2, 1200.0, 30.0, 10.0, 900.0,
                listOf(listOf(TrackPoint(MERAN_LAT, MERAN_LON, 300.0, System.currentTimeMillis() - 900_000),
                    TrackPoint(MERAN_LAT + 0.001, MERAN_LON + 0.001, 310.0, System.currentTimeMillis() - 800_000))),
                state = "active")
            // a phone setup step before a tour: the vibration test answered "not felt"
            Prefs(ctx).vibrationFelt = Felt.NO
        })
        val title = Txt.s(Res.string.routes_unfinished_title)
        assertNotNull("the interrupted tour is asked about", findFresh(By.text(title), 15_000))

        // Continue → the guide opens (the vibration step; a phone without a vibrator asks about the voice instead –
        // GitHub's emulator has one, PhoneSetupE2ETest 2026-11-07: "VIBRATION, VOICE"); the question must not stay in
        // front of it
        findFresh(By.text(Txt.s(Res.string.routes_unfinished_continue)), 5_000)!!.click()
        waitUntil("the phone setup guide opens", 10_000) { guideStep() != null }
        assertTrue("a setup step before the tour: ${guideStep()}", guideStep() == CheckId.VIBRATION || guideStep() == CheckId.VOICE)
        assertTrue("the question steps aside while the guide is open", goneFresh(By.text(title), 5_000))
        assertNotNull("the guide takes touches: its Later button is there", findFresh(By.text(Txt.s(Res.string.setup_later)), 5_000))

        // Back closes the guide without finishing: the question is back, the tour still waits, nothing recorded
        device.pressBack()
        waitUntil("Back closes the guide", 10_000) { guideStep() == null }
        assertNotNull("the question is back after Back", findFresh(By.text(title), 5_000))
        assertTrue("the tour still waits", read { it.unfinished?.id == tourId })
        assertNull("no recording started by Back", RecordingService.state.value)
        // a new tour must not start beside the interrupted one while its question waits (checked at the end: the
        // active recording is still the interrupted tour)
        scenario.onActivity { vm(it).startNavigation(recordOnly = true) }
        assertNull("the start is held while the question waits", RecordingService.state.value)

        // Continue again, the guide finished with real taps (Later on every recommended step – the bug was that the
        // guide got no touch): the recording goes on in the same tour
        findFresh(By.text(Txt.s(Res.string.routes_unfinished_continue)), 5_000)!!.click()
        waitUntil("the phone setup guide opens again", 10_000) { guideStep() != null }
        val later = java.util.regex.Pattern.compile(Txt.s(Res.string.setup_later) + "|" + Txt.s(Res.string.setup_continue))
        repeat(8) {
            val step = guideStep() ?: return@repeat
            val b = findFresh(By.text(later), 5_000)
            assertNotNull("the step $step offers Later", b)
            b!!.click()
            waitUntil("the step $step is answered", 10_000) { guideStep() != step }
        }
        waitUntil("the recording continues in the interrupted tour", 20_000) {
            RecordingService.state.value?.tourId == tourId && read { it.unfinished == null && it.setupGuide == null }
        }
        assertEquals(AppMode.NAVIGATE, read { it.mode })
        assertTrue("the question is gone", goneFresh(By.text(title), 5_000))
        assertEquals("the same tour, no second recording", tourId, TourDb.get(ctx).findActiveRecording()?.id)
    }

    private fun guideStep(): CheckId? = read { it.setupGuide?.current }

    private fun <T> read(block: (com.onpath.app.ui.AppViewModel) -> T): T {
        var r: T? = null
        scenario.onActivity { r = block(vm(it)) }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }
}
