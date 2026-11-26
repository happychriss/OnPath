package com.onpath.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * When the phone uploads and the viewer page asks (docs/design/live-tracking.md §5, §5.1). The risks: a rule that asks
 * too often drains the phone on a long day and eats the server's daily request budget (the owner's concern
 * 2026-11-20); one that asks too rarely leaves the family without news.
 */
class LivePolicyTest {
    private val t0 = 1_800_000_000_000L
    private fun due(up: Long?, tried: Long?, moving: Boolean = true, paused: Boolean = false, low: Boolean = false,
                    event: Boolean = false, failures: Int = 0, retry: Long? = null, back: Boolean = false) =
        LivePolicy.nextUpload(up, tried, moving, paused, low, event, failures, retry, back)

    /** Guards: the design's upload table – 2 min moving, 10 min paused / standing / low battery, the first one at once. */
    @Test
    fun theUploadTable() {
        assertEquals(0L, due(null, null))
        assertEquals(t0 + 120_000, due(t0, t0))
        assertEquals(t0 + 600_000, due(t0, t0, paused = true))
        assertEquals(t0 + 600_000, due(t0, t0, moving = false))
        assertEquals(t0 + 600_000, due(t0, t0, low = true))
        assertEquals(120, LivePolicy.intervalS(moving = true, paused = false, lowBattery = false))
        assertEquals(600, LivePolicy.intervalS(moving = true, paused = true, lowBattery = false))
        assertEquals(600, LivePolicy.intervalS(moving = false, paused = false, lowBattery = false))
        assertEquals(600, LivePolicy.intervalS(moving = true, paused = false, lowBattery = true))
    }

    /** Guards: an event goes out soon – but a burst of events (pause, resume, pause …) never uploads more than once per 30 s. */
    @Test
    fun anEventGoesAtOnceButNeverFasterThanTheFloor() {
        assertEquals(t0 + 30_000, due(t0, t0, event = true))
        assertEquals(t0 + 30_000, due(t0, t0, paused = true, event = true))
        assertTrue(due(t0, t0, event = true) - t0 >= LivePolicy.EVENT_FLOOR_MS)
        // a burst: one event every second for ten minutes
        var last = t0; var uploads = 0
        for (now in t0..t0 + 600_000 step 1000) if (now >= due(last, last, event = true)) { last = now; uploads++ }
        assertEquals(20, uploads)
    }

    /** Guards: no retry storm without network – 30 s doubling to 10 min; the first upload failing is retried too. */
    @Test
    fun failuresBackOff() {
        assertEquals(listOf(30L, 60, 120, 240, 480, 600, 600, 600), (1..8).map { LivePolicy.backOff(it) / 1000 })
        assertEquals(600_000L, LivePolicy.backOff(1000))
        assertEquals(t0 + 30_000, due(t0 - 500_000, t0, failures = 1))
        assertEquals(t0 + 240_000, due(null, t0, failures = 4))
        assertEquals("an event does not shorten a failure's wait", t0 + 240_000, due(t0 - 900_000, t0, event = true, failures = 4))
        // three hours without network: under 25 tries in all
        var tried = t0; var failures = 1; var tries = 1
        while (true) { val next = due(t0 - 1, tried, failures = failures); if (next > t0 + 3 * 3_600_000) break; tried = next; failures++; tries++ }
        assertTrue("$tries tries", tries in 15..25)
    }

    /** Guards: a busy server is left alone as long as it says (at least a minute, at most ten) – also when the network "comes back". */
    @Test
    fun aBusyServerIsLeftAlone() {
        assertEquals(t0 + 60_000, due(t0 - 1, t0, failures = 1, retry = 0))
        assertEquals(t0 + 90_000, due(t0 - 1, t0, failures = 1, retry = 90_000))
        assertEquals(t0 + 600_000, due(t0 - 1, t0, failures = 1, retry = 86_400_000))
        assertEquals(t0 + 90_000, due(t0 - 1, t0, failures = 3, retry = 90_000, back = true, event = true))
    }

    /** Guards: the way goes up as soon as there is network again – without hammering while the connection flaps. */
    @Test
    fun theNetworkComingBackTriesSoon() {
        assertEquals(t0 + 10_000, due(t0 - 3_600_000, t0, failures = 6, back = true))
        assertEquals("only after a failure", t0 + 120_000, due(t0, t0, back = true))
    }

    /**
     * Guards: the page's pacing follows the phone's own plan – it asks just after the next upload is due (owner 2026-11-26:
     * the point jumps when the countdown reaches 0), never before; a phone a little late is asked every 5 s for a moment.
     */
    @Test
    fun theViewerAsksJustAfterThePhonesNextUpload() {
        assertEquals(123_000L, LivePolicy.viewerNext(0, 120, 0, false))
        assertEquals(93_000L, LivePolicy.viewerNext(30_000, 120, 0, false))
        assertEquals(603_000L, LivePolicy.viewerNext(0, 600, 0, false))
        assertEquals("never faster than the floor", 5_000L, LivePolicy.viewerNext(121_000, 120, 0, false))
        assertEquals("a little late: every 5 s", listOf(5_000L, 5_000L, 5_000L), listOf(123_000L, 128_000L, 133_000L).map { LivePolicy.viewerNext(it, 120, 1, false) })
        assertEquals("the upload is overdue: the quiet steps", 60_000L, LivePolicy.viewerNext(138_000, 120, 4, false))
        assertEquals("… by how late", listOf(60L, 120, 300, 600, 600), listOf(150_000L, 250_000L, 500_000L, 700_000L, 9_999_999L).map { LivePolicy.viewerNext(it, 120, 9, false)!! / 1000 })
        assertEquals("no head yet", 60_000L, LivePolicy.viewerNext(0, null, 0, false))
        assertEquals(listOf(60L, 120, 300, 600, 600, 600), (1..6).map { LivePolicy.viewerNext(9_999_999, null, it, false)!! / 1000 })
        assertEquals("a silly nextS cannot make the page hammer", 13_000L, LivePolicy.viewerNext(0, 0, 0, false))
        assertEquals("… or sleep for a day", 3_603_000L, LivePolicy.viewerNext(0, 999_999, 0, false))
    }

    /** Guards: after the end or a stop the page never asks again; a busy server is left alone for at least a minute. */
    @Test
    fun theViewerStopsWhenItIsOverAndWaitsWhenTheServerIsBusy() {
        assertNull(LivePolicy.viewerNext(0, 120, 0, true))
        assertNull(LivePolicy.viewerNext(0, null, 5, true, retryAfterMs = 1000))
        assertEquals(60_000L, LivePolicy.viewerNext(0, 120, 0, false, retryAfterMs = 0))
        assertEquals(180_000L, LivePolicy.viewerNext(0, 120, 0, false, retryAfterMs = 180_000))
        assertEquals(600_000L, LivePolicy.viewerNext(0, 120, 3, false, retryAfterMs = 86_400_000))
    }

    /**
     * Guards: the request budget of §5.1 – a ten-hour moving tour watched the whole day stays under 350 page requests
     * (a 30-s tick would be 1,200); when every sixth upload comes late, each late one costs a few quick questions more.
     */
    @Test
    fun aWatchedTenHourTourStaysUnder350Requests() {
        for (lateEvery in listOf(0, 6)) {
            val rnd = Random(4)
            val uploads = ArrayList<Long>()
            var t = 0L
            while (t < 10 * 3_600_000L) { uploads += t; t += 120_000 + if (lateEvery > 0 && rnd.nextInt(lateEvery) == 0) 15_000 + rnd.nextInt(60_000) else rnd.nextInt(3000) }
            val asks = watch(uploads, nextS = 120, hours = 10)
            assertTrue("$asks requests (late every $lateEvery)", asks in 200..(if (lateEvery == 0) 349 else 549))
        }
    }

    /** Guards: standing still (the phone reports every ten minutes) the page asks about six times an hour, not thirty. */
    @Test
    fun aRestIsAskedSixTimesAnHour() {
        val uploads = (0 until 5 * 6).map { it * 600_000L + 1500 }
        val asks = watch(uploads, nextS = 600, hours = 5)
        assertTrue("$asks requests in 5 h", asks in 25..32)
    }

    /** Guards: a dead zone – the page slows down to every ten minutes instead of asking on at the phone's pace. */
    @Test
    fun aDeadZoneIsAskedEverMoreSlowly() {
        val asked = ArrayList<Long>()
        watch(listOf(0L), nextS = 120, hours = 10, asked = asked)
        val perHour = (0 until 10).map { h -> asked.count { it >= h * 3_600_000L && it < (h + 1) * 3_600_000L } }
        assertTrue("first hour ${perHour[0]}", perHour[0] <= 13) // opening, 4 quick ones at 0, then after 1, 2, 5, 10 minutes …
        assertTrue("later hours $perHour", perHour.drop(1).all { it <= 6 })
        assertTrue("${asked.size} requests in 10 h", asked.size < 8 * 10)
    }

    /** The viewer page's loop against the phone's [uploads] (times of arrival at the server): the number of questions. */
    private fun watch(uploads: List<Long>, nextS: Int, hours: Int, asked: MutableList<Long>? = null): Int {
        var now = 2000L // the page opens two seconds after the first upload
        var seen = -1L; var empty = 0; var asks = 0
        while (now < hours * 3_600_000L) {
            asks++; asked?.add(now)
            val last = uploads.last { it <= now }
            if (last > seen) { seen = last; empty = 0 } else empty++
            now += LivePolicy.viewerNext(now - last, nextS, empty, false)!!
        }
        return asks
    }
}
