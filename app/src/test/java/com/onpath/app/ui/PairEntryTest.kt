package com.onpath.app.ui

import com.onpath.app.plansync.PlanSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /** The wiring: ☰ → vm.startPairing; no camera from the map → Settings → Browser sync with the line; seen after the camera. */
    @Test fun wiring() {
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(map.contains("onPairBrowser = { vm.startPairing(pairCamera) }"))
        assertTrue(map.contains("pairHintMessage(onCamera = { vm.pairHintCamera(pairCamera) }"))
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText().substringAfter("fun openPairCamera(")
            .substringBefore("\n    }\n")
        assertTrue(vm.contains("if (!fromSettings) openSettings(SettingsPage.SYNC)"))
        assertTrue(vm.contains("sayError(Txt.s(Res.string.sync_no_camera), NoticePlace.SETTINGS)"))
        assertTrue(vm.contains("if (opened) prefs.pairHintSeen = true"))
        val main = File("src/main/java/com/onpath/app/MainActivity.kt").readText()
        assertTrue(main.contains("private fun startPairCamera(action: String) = startActivity(Intent(action))"))
        assertTrue(main.contains("pairCamera = ::startPairCamera"))
    }
}
