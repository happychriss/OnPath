package com.onpath.app.ui

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold lock's band fills in REAL time, whatever the phone's animation setting (the tour bar's lock, CORE C4/C5).
 * The bug: GitHub E2E 2026-11-07 (run 42363890364) – the emulator runs with animations off (animator scale 0), a 21 ms
 * tap on the locked "Pause" fired the 2 s hold 50 ms after the finger went down, and a short tap on "Weiter" resumed the
 * tour. The same on every phone with Settings → Accessibility → "Remove animations": Compose multiplies a tween's length
 * by that scale, so the band (then an `Animatable.animateTo(tween)`) was full on its first frame – this test, run against
 * that tween, said "full after 16 ms of a 2 s hold".
 *
 * No Compose state here (a plain list gets the band's values): an Animatable written outside a composition left the
 * global snapshot's apply notifications stuck for the Paparazzi tests that ran after it in the same JVM.
 */
class HoldFillTest {
    /** Frames every 16 ms on a clock the test reads. */
    private class Frames : MonotonicFrameClock {
        var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R { nanos += 16_000_000; return onFrame(nanos) }
        val ms get() = nanos / 1_000_000
    }

    /** The phone's animation scale as Compose sees it (0 = animations off). */
    private class Scale(override val scaleFactor: Float) : MotionDurationScale

    private fun heldMsUntilFull(scale: Float, holdMs: Int, from: Float = 0f): Long {
        val frames = Frames()
        val shown = mutableListOf<Float>()
        runBlocking(frames + Scale(scale)) { HoldRule.fill(from, holdMs) { shown += it } }
        assertEquals("full when it returns", 1f, shown.last(), 0.0001f)
        assertTrue("the band only grows", shown.zipWithNext().all { (a, b) -> b >= a })
        // the first frame starts the hold: the time held is from there
        return frames.ms - 16
    }

    @Test fun animationsOffAShortTapNeverFiresTheHold() {
        val held = heldMsUntilFull(scale = 0f, holdMs = 2_000)
        assertTrue("animations off: full after $held ms of a 2 s hold", held >= 2_000)
    }

    @Test fun animationsSlowOrFastTheHoldKeepsItsTime() {
        for (scale in listOf(0.5f, 1f, 5f)) {
            val held = heldMsUntilFull(scale, holdMs = 1_000)
            assertTrue("scale $scale: full after $held ms of a 1 s hold", held in 1_000..1_100)
        }
    }

    @Test fun aSecondTouchContinuesFromWhereTheBandStands() {
        val held = heldMsUntilFull(scale = 0f, holdMs = 1_000, from = 0.4f)
        assertTrue("from 40 %: the rest of a 1 s hold, $held ms", held in 600..700)
    }
}
