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
}
