package com.onpath.app.live

import com.onpath.app.live.LiveStatusLine.Key
import com.onpath.app.live.LiveStatusLine.Line
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The viewer page's one status line (docs/design/live-tracking.md §3). The risk: the wrong line wins – "on the way"
 * while the phone has been silent for an hour, or an alarm colour in a normal dead zone – and the person at home is
 * either falsely calmed or frightened for nothing.
 */
class LiveStatusLineTest {
    private val fixAt = 1_800_000_000_000L
    private val moving = LiveHead(LiveState.RECORDING, fix = LiveFix(46.689, 11.14, 1522, fixAt, 5), nextS = 120)
    private fun line(h: LiveHead?, sinceMin: Double = 2.0, gone: Boolean = false, broken: Boolean = false, endedMs: Long? = null) =
        LiveStatusLine.of(h, gone, broken, (sinceMin * 60_000).toLong(), endedMs)

    /** Guards: the normal case reads "on the way" with the age of the last contact. */
    @Test
    fun movingAndFresh() {
        assertEquals(Line(Key.MOVING, minutes = 2), line(moving))
        assertEquals(Line(Key.MOVING, minutes = 0), line(moving, sinceMin = 0.4))
        assertEquals("ten minutes are not yet silence", Line(Key.MOVING, minutes = 10), line(moving, sinceMin = 10.0))
    }

    /** Guards: a stopped or expired share shows nothing of the tour, whatever the page still holds. */
    @Test
    fun stoppedBeatsEverything() {
        assertEquals(Line(Key.STOPPED), line(moving, gone = true))
        assertEquals(Line(Key.STOPPED), line(moving.copy(state = LiveState.ENDED), gone = true, broken = true, endedMs = 5))
        assertEquals(Line(Key.STOPPED), line(null, gone = true))
    }

    /** Guards: a link without a working key says so – and a box without data yet is "no data", not "broken". */
    @Test
    fun brokenAndNoDataYet() {
        assertEquals(Line(Key.BROKEN), line(moving, broken = true))
        assertEquals(Line(Key.BROKEN), line(null, broken = true))
        assertEquals(Line(Key.NO_DATA), line(null))
    }

    /** Guards: "Tour beendet" stays the line however long ago it was – never "no contact" after a finished tour. */
    @Test
    fun endedBeatsNoContactOffRouteAndPause() {
        val ended = moving.copy(state = LiveState.ENDED, offRouteM = 200, stillS = 900)
        assertEquals(Line(Key.ENDED, atMs = fixAt), line(ended))
        assertEquals(Line(Key.ENDED, atMs = fixAt), line(ended, sinceMin = 11 * 60.0))
        assertEquals(Line(Key.ENDED, atMs = fixAt, arrived = true), line(ended.copy(arrived = true)))
        // the server's end mark alone (the final head did not arrive) ends it too, with the last place's time
        assertEquals(Line(Key.ENDED, atMs = fixAt), line(moving, sinceMin = 300.0, endedMs = fixAt + 9))
        assertEquals(Line(Key.ENDED, atMs = 77), line(moving.copy(fix = null), endedMs = 77))
    }

    /** Guards: silence beats "on the way", "off route" and "pause" – the stale state must not look current. */
    @Test
    fun noContactBeatsMovingOffRouteAndPause() {
        assertEquals(Line(Key.NO_CONTACT, minutes = 25, atMs = fixAt), line(moving, sinceMin = 25.0))
        assertEquals(Line(Key.NO_CONTACT, minutes = 25, atMs = fixAt), line(moving.copy(offRouteM = 150), sinceMin = 25.0))
        assertEquals(Line(Key.NO_CONTACT, minutes = 11, atMs = fixAt), line(moving, sinceMin = 11.0))
        assertEquals("grey below an hour, amber from then on", Line(Key.NO_CONTACT, minutes = 59, atMs = fixAt), line(moving, sinceMin = 59.9))
        assertEquals(Line(Key.NO_CONTACT, minutes = 60, atMs = fixAt, amber = true), line(moving, sinceMin = 60.0))
        assertEquals(Line(Key.NO_CONTACT, minutes = 180, atMs = null, amber = true), line(moving.copy(fix = null), sinceMin = 180.0))
    }

    /**
     * Guards: a rest is not an alarm – standing still the phone reports only every ten minutes, so twelve silent
     * minutes are still a pause; twice its own interval is silence.
     */
    @Test
    fun aRestIsNotSilence() {
        val rest = moving.copy(state = LiveState.PAUSED, stillS = 600, nextS = 600)
        assertEquals(Line(Key.PAUSE, minutes = 22), line(rest, sinceMin = 12.0))
        assertEquals(Line(Key.PAUSE, minutes = 30), line(rest, sinceMin = 20.0))
        assertEquals(Line(Key.NO_CONTACT, minutes = 21, atMs = fixAt), line(rest, sinceMin = 21.0))
    }

    /** Guards: off the planned route shows before pause and moving – but not once arrived, and not while paused. */
    @Test
    fun offRouteBeatsPauseAndMoving() {
        assertEquals(Line(Key.OFF_ROUTE, metres = 150, amber = true), line(moving.copy(offRouteM = 150)))
        assertEquals(Line(Key.OFF_ROUTE, metres = 150, amber = true), line(moving.copy(offRouteM = 150, stillS = 900)))
        assertEquals(Line(Key.MOVING, minutes = 2), line(moving.copy(offRouteM = 150, arrived = true)))
        assertEquals(Line(Key.PAUSE, minutes = 2), line(moving.copy(offRouteM = 150, state = LiveState.PAUSED)))
    }

    /**
     * Guards: a low battery (below 15 %) is the line – it beats off route, pause and moving, but not silence or the end
     * (owner 2026-11-26: „Akku 12 %“, the fact only).
     */
    @Test
    fun lowBatteryBeatsOffRoutePauseAndMoving() {
        assertEquals(Line(Key.LOW_BATTERY, percent = 12, amber = true), line(moving.copy(batteryPct = 12)))
        assertEquals(Line(Key.LOW_BATTERY, percent = 12, amber = true), line(moving.copy(batteryPct = 12, offRouteM = 150, stillS = 900)))
        assertEquals(Line(Key.MOVING, minutes = 2), line(moving.copy(batteryPct = 15)))
        assertEquals(Line(Key.NO_CONTACT, minutes = 25, atMs = fixAt), line(moving.copy(batteryPct = 5), sinceMin = 25.0))
        assertEquals(Line(Key.ENDED, atMs = fixAt), line(moving.copy(batteryPct = 5, state = LiveState.ENDED)))
    }

    /** Guards: a pause counts on while nothing comes; a short stop (a photo, a gate) still reads "on the way". */
    @Test
    fun pauseAndShortStops() {
        assertEquals(Line(Key.PAUSE, minutes = 0), line(moving.copy(state = LiveState.PAUSED), sinceMin = 0.5))
        assertEquals(Line(Key.PAUSE, minutes = 14), line(moving.copy(state = LiveState.PAUSED, stillS = 720), sinceMin = 2.0))
        assertEquals(Line(Key.MOVING, minutes = 2), line(moving.copy(stillS = 120), sinceMin = 2.0))
        assertEquals("standing 8 min is still on the way (owner: Pause from 10 min)", Line(Key.MOVING, minutes = 2), line(moving.copy(stillS = 360), sinceMin = 2.0))
        assertEquals(Line(Key.PAUSE, minutes = 10), line(moving.copy(stillS = 480), sinceMin = 2.0))
        assertEquals("a wrong clock gives no negative age", Line(Key.MOVING, minutes = 0), line(moving, sinceMin = -3.0))
    }

    /**
     * Guards: „Etwas später als geplant“ only with the phone's planned arrival (an older app sends none → never), only when
     * the delay is more than 30 % of the planned time still to go and at least 15 min, and below every other line.
     */
    @Test
    fun lateAgainstThePlan() {
        val min = 60_000L
        val planned = fixAt + 100 * min // 100 min still to go by the plan
        val onTime = moving.copy(arrivalMs = planned + 20 * min, plannedArrivalMs = planned)
        assertEquals("20 min of 100 is no news", Line(Key.MOVING, minutes = 2), line(onTime))
        val late = moving.copy(arrivalMs = planned + 31 * min, plannedArrivalMs = planned)
        assertEquals(Line(Key.LATE, atMs = planned + 31 * min, amber = true), line(late))
        assertEquals("no planned arrival (an older app) -> never late", Line(Key.MOVING, minutes = 2), line(late.copy(plannedArrivalMs = null)))
        assertEquals("no expected arrival -> never late", Line(Key.MOVING, minutes = 2), line(late.copy(arrivalMs = null)))
        val nearEnd = moving.copy(arrivalMs = fixAt + 12 * min, plannedArrivalMs = fixAt + 2 * min)
        assertEquals("near the end 10 min late is no news", Line(Key.MOVING, minutes = 2), line(nearEnd))
        val pastPlan = moving.copy(arrivalMs = fixAt + 10 * min, plannedArrivalMs = fixAt - 10 * min)
        assertEquals("past the planned arrival, 20 min late", Line(Key.LATE, atMs = fixAt + 10 * min, amber = true), line(pastPlan))
        assertEquals("off the route wins", Key.OFF_ROUTE, line(late.copy(offRouteM = 150)).key)
        assertEquals("a pause wins", Key.PAUSE, line(late.copy(stillS = 900)).key)
        assertEquals("arrived -> never late", Key.MOVING, line(late.copy(arrived = true)).key)
        assertEquals("no contact wins", Key.NO_CONTACT, line(late, sinceMin = 30.0).key)
    }

    /** Guards: the planned arrival survives the head's bytes, and a head without it reads as before. */
    @Test
    fun plannedArrivalInTheHead() {
        val h = moving.copy(arrivalMs = fixAt + 90 * 60_000L, plannedArrivalMs = fixAt + 60 * 60_000L)
        assertEquals(h.plannedArrivalMs, LiveItem.readHead(LiveItem.head(h))?.plannedArrivalMs)
        assertEquals(null, LiveItem.readHead(LiveItem.head(moving))?.plannedArrivalMs)
    }
}
