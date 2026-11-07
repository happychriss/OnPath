package com.onpath.app.map

import com.onpath.app.debug.FrameStats
import com.onpath.app.map.ReliefAuto.Gesture
import com.onpath.app.map.ReliefAuto.Kind
import com.onpath.app.map.ReliefAuto.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "App merkt es selbst" (owner 2026-11-07): relief goes off once on a phone that stutters with it, never twice. */
class ReliefAutoTest {
    private fun g(frames: Int, janky: Int, relief: Boolean = true, kind: Kind = Kind.PAN) = Gesture(kind, frames, janky, relief)
    private val fresh = State()

    /** A gesture from frame times in ms, through the meter's own summary (janky = over 16 ms). */
    private fun measured(ms: List<Double>, hz: Float, kind: Kind = Kind.PAN): Gesture {
        val s = FrameStats.of(ms, hz)!!
        return Gesture(kind, s.frames, s.over16, true)
    }

    @Test fun theS10WithReliefSwitchesOff() {
        // owner's S10 (60 Hz), relief + contours: median 19 ms, p90 31 ms → more than half the frames over 16 ms
        val pan = measured(List(40) { 12.0 } + List(50) { 19.0 } + List(10) { 31.0 }, 60f)
        val zoom = measured(List(30) { 13.0 } + List(40) { 20.0 } + List(10) { 30.0 }, 60f, Kind.ZOOM)
        val v = ReliefAuto.decide(listOf(pan, zoom), reliefOn = true, state = fresh)
        assertNotNull(v)
        assertEquals(2, v!!.gestures)
        assertEquals(180, v.frames)
        assertEquals(110, v.janky)
        assertEquals(61, v.percent)
    }

    @Test fun theS10WithoutReliefFiguresStayBelow() {
        // both off on the S10: 8 % janky (p90 18 ms) – far under the mark even if relief were on
        val smooth = List(3) { measured(List(92) { 12.0 } + List(8) { 18.0 }, 60f) }
        assertNull(ReliefAuto.decide(smooth, true, fresh))
        // contours only: 14 %
        assertNull(ReliefAuto.decide(List(3) { g(100, 14) }, true, fresh))
    }

    @Test fun aPixel8At120HzStaysOn() {
        // 120 Hz, busy frames of 6–12 ms: many over its 8.3 ms budget, none over 16 ms – relief stays
        val pixel = List(4) { measured(List(150) { 6.0 } + List(100) { 12.0 } + List(5) { 18.0 }, 120f) }
        assertTrue(pixel.all { it.janky == 5 })
        assertNull(ReliefAuto.decide(pixel, true, fresh))
    }

    @Test fun thresholdIsThirtyPercent() {
        assertNotNull(ReliefAuto.decide(listOf(g(100, 30), g(100, 30)), true, fresh))
        assertNull(ReliefAuto.decide(listOf(g(100, 29), g(100, 30)), true, fresh))
    }

    @Test fun oneGestureIsNotEnough() {
        assertNull(ReliefAuto.decide(listOf(g(500, 400)), true, fresh))
    }

    @Test fun tooFewFramesTogetherIsNotEnough() {
        // two gestures, all janky, but only 100 frames together (< 120)
        assertNull(ReliefAuto.decide(listOf(g(50, 50), g(50, 50)), true, fresh))
        assertNotNull(ReliefAuto.decide(listOf(g(60, 60), g(60, 60)), true, fresh))
    }

    @Test fun shortGesturesDoNotCount() {
        // nudges and double taps (< 20 frames) tell nothing, however janky
        val nudges = List(20) { g(19, 19) }
        assertNull(ReliefAuto.decide(nudges, true, fresh))
        assertFalse(ReliefAuto.counts(g(19, 19)))
        assertTrue(ReliefAuto.counts(g(20, 0)))
        // … and do not dilute the real ones either
        assertNotNull(ReliefAuto.decide(listOf(g(100, 50)) + List(10) { g(5, 0) } + listOf(g(100, 50)), true, fresh))
    }

    @Test fun onlyGesturesWithReliefOnCount() {
        assertNull(ReliefAuto.decide(listOf(g(100, 90, relief = false), g(100, 90)), true, fresh))
        assertFalse(ReliefAuto.counts(g(100, 90, relief = false)))
    }

    @Test fun theLastGesturesDecide() {
        // an early stall (first tiles loading) is outweighed by the smooth gestures after it
        val early = List(2) { g(100, 100) }
        val later = List(ReliefAuto.WINDOW) { g(100, 5) }
        assertNull(ReliefAuto.decide(early + later, true, fresh))
        // the other way round the recent stalls are inside the window: 4 smooth + 2 janky = 220 of 600 frames (37 %)
        assertNotNull(ReliefAuto.decide(later + early, true, fresh))
    }

    @Test fun reliefAlreadyOffNothingToDo() {
        assertNull(ReliefAuto.decide(listOf(g(100, 90), g(100, 90)), reliefOn = false, state = fresh))
    }

    @Test fun theUsersChoiceWins() {
        val janky = listOf(g(100, 90), g(100, 90))
        // the user set relief by hand (on or off) in Settings → Map: never decided again
        assertNull(ReliefAuto.decide(janky, true, ReliefAuto.userSet(fresh)))
        assertFalse(ReliefAuto.mayDecide(true, State(byUser = true)))
    }

    /** The wiring (the view model needs Android): a hand change in Settings marks the user's choice, the app's own does not. */
    @Test fun wiring() {
        val vm = java.io.File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val update = vm.substringAfter("fun updateSettings(s: AppSettings) {").substringBefore("\n    }\n")
        assertTrue(update.contains("if (s.hillshade != settings.hillshade)") && update.contains("ReliefAuto.userSet(reliefAuto)"))
        val auto = vm.substringAfter("fun mapGestureMeasured(").substringBefore("\n    }\n")
        assertTrue(auto.contains("ReliefAuto.decide(") && auto.contains("ReliefAuto.switchedOff(reliefAuto)"))
        assertTrue("the app's switch must not count as the user's", !auto.contains("updateSettings("))
        assertTrue(auto.contains("sayInfo(Txt.s(Res.string.maps_relief_auto_off), NoticePlace.MAP)"))
        val host = java.io.File("src/main/java/com/onpath/app/ui/MapHost.kt").readText()
        assertTrue(host.contains("MapGestureFrames(w, vm::mapGestureMeasured)") && host.contains("gestureFrames?.idle()"))
    }

    @Test fun neverTwice() {
        val janky = listOf(g(100, 90), g(100, 90))
        val after = ReliefAuto.switchedOff(fresh)
        assertTrue(after.autoOff)
        // relief back on (by hand → byUser, but even without it): the app never switches it off a second time
        assertNull(ReliefAuto.decide(janky, true, after))
        assertNull(ReliefAuto.decide(janky, true, ReliefAuto.userSet(after)))
    }
}
