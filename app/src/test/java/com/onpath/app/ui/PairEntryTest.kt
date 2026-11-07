package com.onpath.app.ui

import com.onpath.app.plansync.PlanSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ☰ → "Pair with browser" (owner 2026-10-30): the camera at once, the step line only the first time, fallback without a camera. */
class PairEntryTest {
    private val notPaired = PlanSync.State()

    @Test fun cameraFirstOnceTheStepWasSeen() {
        assertEquals(PairEntry.Step.HINT, PairEntry.step(notPaired, hintSeen = false))
        assertEquals(PairEntry.Step.CAMERA, PairEntry.step(notPaired, hintSeen = true))
        // offline is no reason to stop at the settings page
        assertEquals(PairEntry.Step.CAMERA, PairEntry.step(notPaired.copy(problem = PlanSync.Problem.Offline), hintSeen = true))
    }

    @Test fun pairedOrAProblemGoesToBrowserSync() {
        assertEquals(PairEntry.Step.SETTINGS, PairEntry.step(notPaired.copy(paired = true), hintSeen = true))
        assertEquals(PairEntry.Step.SETTINGS, PairEntry.step(notPaired.copy(paired = true), hintSeen = false))
        // ended in the browser: the amber ☰ item is cleared by opening the page – it opens, not the camera
        assertEquals(PairEntry.Step.SETTINGS, PairEntry.step(notPaired.copy(ended = true), hintSeen = true))
        // paired, waiting for the browser's first hello: the page as before
        assertEquals(PairEntry.Step.SETTINGS, PairEntry.step(notPaired.copy(paired = true, browser = com.onpath.app.plansync.BrowserLink.WAITING), hintSeen = true))
    }

    /** Unpaired in the browser (field bug 2026-10-31): ☰ "Pair with browser" opens the camera as for a first pairing. */
    @Test fun unpairedInTheBrowserOpensTheCamera() {
        val gone = notPaired.copy(paired = true, browser = com.onpath.app.plansync.BrowserLink.UNPAIRED)
        assertEquals(PairEntry.Step.CAMERA, PairEntry.step(gone, hintSeen = true))
        assertEquals(PairEntry.Step.HINT, PairEntry.step(gone, hintSeen = false))
        assertEquals(PairEntry.Step.CAMERA, PairEntry.step(gone.copy(problem = PlanSync.Problem.Offline), hintSeen = true))
        // a problem worth a look still opens the page
        assertEquals(PairEntry.Step.SETTINGS, PairEntry.step(gone.copy(problem = PlanSync.Problem.Storage), hintSeen = true))
    }

    @Test fun cameraIntentFirst() {
        val started = mutableListOf<String>()
        var fellBack = false
        assertTrue(PairEntry.openCamera(start = { started += it }, fallback = { fellBack = true }))
        assertEquals(listOf(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), started)
        assertFalse(fellBack)
    }

    @Test fun noCameraAppFallsBack() {
        var fellBack: Exception? = null
        val opened = PairEntry.openCamera(start = { throw android.content.ActivityNotFoundException("no camera") },
            fallback = { fellBack = it })
        assertFalse(opened)
        assertTrue(fellBack is android.content.ActivityNotFoundException)
    }

}
