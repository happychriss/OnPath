package com.onpath.app.ui

import com.onpath.app.ui.MapGestures.Buzz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner rule: vibrate at the moment of the press, never after the action, only on the map –
 * hold on the map / line / POI 1×, hold on a route point 2×; nothing after an edit, nothing in the Points list.
 */
class PlanHapticsTest {
    @Test fun patterns() {
        assertEquals(0, PlanHaptics.pattern(Buzz.NONE).size)
        assertEquals(longArrayOf(0, 40).toList(), PlanHaptics.pattern(Buzz.ONE).toList())
        assertEquals(longArrayOf(0, 40, 100, 40).toList(), PlanHaptics.pattern(Buzz.TWO).toList()) // two separate pulses
    }

    /** Clearly felt where the vibrator supports strength (~230). */
    @Test fun amplitudesMatchPatterns() {
        for (b in Buzz.values()) assertEquals(PlanHaptics.pattern(b).size, PlanHaptics.amplitudes(b).size)
        assertEquals(230, PlanHaptics.amplitudes(Buzz.ONE)[1])
        assertTrue(PlanHaptics.amplitudes(Buzz.TWO).filter { it > 0 }.all { it == 230 })
    }

    /** Only OnPath's "Vibrate when planning" counts (on by default) – there is no system-flag parameter any more. */
    @Test fun gatedByOwnSettingOnly() {
        for (b in listOf(Buzz.ONE, Buzz.TWO)) {
            assertTrue(PlanHaptics.shouldPlay(b, planVibrate = true))
            assertFalse(PlanHaptics.shouldPlay(b, planVibrate = false))
        }
        assertFalse(PlanHaptics.shouldPlay(Buzz.NONE, planVibrate = true))
        assertTrue(com.onpath.app.data.AppSettings().planVibrate)
    }

    /**
     * "Set" must vibrate too. The 1× pulse is short enough for Android to re-label an unlabelled
     * vibration as touch feedback (muted with "Touch feedback" off) – which is why Haptics always sends an explicit
     * non-touch usage. This pins the reason: the 1× waveform is such a candidate, the 2× one was not.
     */
    @Test fun onePulseWouldBeTreatedAsTouchWithoutExplicitUsage() {
        assertTrue(PlanHaptics.pattern(Buzz.ONE).size <= PlanHaptics.MAX_UNLABELLED_TOUCH_SEGMENTS)
        assertTrue(PlanHaptics.pattern(Buzz.TWO).size > PlanHaptics.MAX_UNLABELLED_TOUCH_SEGMENTS)
    }

    /** The hold itself vibrates: 2× on a route point (its menu with Delete), 1× on the map, the line or a POI. */
    @Test fun holdVibratesAtThePress() {
        assertEquals(Buzz.TWO, MapGestures.buzz(true, false, 0, false))  // route point → its menu
        assertEquals(Buzz.TWO, MapGestures.buzz(true, false, 2, true))   // a POI that is a stop = its point
        assertEquals(Buzz.ONE, MapGestures.buzz(true, false, -1, true))  // POI menu
        assertEquals(Buzz.ONE, MapGestures.buzz(true, false, -1, false)) // map / line: add point or first-point menu
        assertEquals(Buzz.NONE, MapGestures.buzz(true, true, -1, false)) // only closes the menu
    }

    /** No vibration after an edit: the ViewModel and the Points list have no haptics (source check). */
    @Test fun editsAndPointsListDoNotVibrate() {
        val ui = java.io.File("src/main/java/com/onpath/app/ui")
        for (f in listOf("AppViewModel.kt", "PointsSection.kt")) {
            val src = java.io.File(ui, f).readText()
            assertFalse("$f must not vibrate", Regex("Haptics\\.play|performHapticFeedback|LocalHapticFeedback").containsMatchIn(src))
        }
    }
}
