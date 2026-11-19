package com.onpath.app.ui

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.time.TourReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * „Mehr“ of a route's overview (owner 2026-11-19): only what the overview does not show – and nothing of it when the data
 * does not give it; no „Mehr“ at all when nothing is left.
 */
class RouteMoreTest {
    private val start = 1_763_201_520_000L // 10:12 UTC (unit tests run in UTC)
    private val end = start + (4 * 3600L + 35 * 60) * 1000
    private val done = Tour(1, TourKind.RECORDED, "Leiter Alm Runde", 0L, "HIKE", 2, 12_400.0, 620.0, 640.0, 16_500.0, "done",
        emptyList(), hasPlan = true)
    private val planned = Tour(2, TourKind.PLANNED, "Meran – Hirzer", 0L, "HIKE", 3, 14_200.0, 1210.0, 300.0, 6.1 * 3600, "done",
        emptyList())
    private val review = TourReview(totalS = 16_500.0, movingS = 13_080.0, pauseS = 3420.0, pauseCount = 2, startMs = start,
        endMs = end, avgKmh = 3.4, upMh = 410.0, downMh = 590.0, planPct = 12, plannedS = 14_880.0, neededS = 13_080.0,
        pauseSpans = listOf(TourReview.Span(start + 46 * 60_000L, start + 54 * 60_000L),
            TourReview.Span(start + 113 * 60_000L, start + 138 * 60_000L)),
        rank = TourReview.Rank(3, 14))

    @Test fun aDoneTourShowsTheExtrasAsGraphicsAndNoDoubles() {
        val m = More.of(done, review)!!
        val c = m.clock!!
        assertEquals("10:12", c.from); assertEquals("14:47", c.to)
        // the two pauses as gaps where they lie on the way (46 of 275 min, 8 min long …)
        assertEquals(2, c.gaps.size)
        assertEquals(46f / 275, c.gaps[0].first, 0.001f)
        assertEquals(54f / 275, c.gaps[0].second, 0.001f)
        assertEquals("Breaks (2)", c.key)
        assertEquals("On the way from 10:12 to 14:47, Breaks (2) 57 min", c.description)
        assertEquals("↓640 m", m.descent)
        // up / down on one scale: the faster side fills its bar
        val r = m.rates!!
        assertEquals(1f, r.part(590.0)); assertEquals(410f / 590, r.part(410.0), 0.001f)
        assertEquals("Uphill 410 m/h, Downhill 590 m/h", r.description)
        // planned vs needed: one scale, the difference in minutes
        val p = m.plan!!
        assertEquals(1f, p.plannedPart); assertEquals(13_080f / 14_880, p.neededPart, 0.001f)
        assertEquals("30 min faster", p.difference)
        assertEquals("Planned 4:08 h, Needed 3:38 h, 30 min faster, without breaks", p.description)
        assertEquals("Hike T2 · no. 3 by length of 14", m.note)
        // slower than planned: „… longer“; within a minute: as planned
        assertEquals("20 min longer", More.Plan(3600.0, 4800.0).difference)
        assertEquals("Just as planned", More.Plan(3600.0, 3630.0).difference)
        assertEquals(0.75f, More.Plan(3600.0, 4800.0).plannedPart, 0.001f)
    }

    @Test fun aDoneTourWithoutPausesHasAWholeBarAndNoKey() {
        val m = More.of(done, review.copy(pauseS = 0.0, pauseCount = 0, pauseSpans = emptyList(), downMh = null))!!
        val c = m.clock!!
        assertEquals(emptyList<Pair<Float, Float>>(), c.gaps)
        assertNull(c.key)
        assertEquals("On the way from 10:12 to 14:47", c.description)
        // only the known side of up / down
        assertEquals("Uphill 410 m/h", m.rates!!.description)
        // no plan followed: no planned / needed bars
        assertNull(More.of(done, review.copy(plannedS = null, neededS = null))!!.plan)
    }

    @Test fun aPlannedRouteShowsOnlyTheDescentAndTheActivity() {
        // a review is never one of a plan's: ignored
        val m = More.of(planned, review)!!
        assertNull(m.clock); assertNull(m.rates); assertNull(m.plan)
        assertEquals("↓300 m", m.descent)
        assertEquals("Hike T3", m.note)
        assertEquals(m, More.of(planned, null))
    }

    @Test fun aDoneTourStillReadKeepsItsStoredExtras() {
        val m = More.of(done, null)!!
        assertNull(m.clock); assertNull(m.rates); assertNull(m.plan)
        assertEquals("↓640 m", m.descent); assertEquals("Hike T2", m.note)
    }

    private fun span(a: Long, b: Long) = TourReview.Span(start + a * 60_000L, start + b * 60_000L)

    @Test fun aPauseAtTheVeryStartAndAtTheVeryEndFillsTheBarsEdges() {
        val c = More.of(done, review.copy(pauseSpans = listOf(span(0, 10), span(265, 275))))!!.clock!!
        assertEquals(2, c.gaps.size)
        assertEquals(0f, c.gaps[0].first, 0.0001f); assertEquals(10f / 275, c.gaps[0].second, 0.001f)
        assertEquals(265f / 275, c.gaps[1].first, 0.001f); assertEquals(1f, c.gaps[1].second, 0.0001f)
    }

    @Test fun pausesOutsideOrBackwardsOrEmptyNeverDrawNegativeOrPastTheEnd() {
        val odd = listOf(span(-30, -5), span(300, 320), span(-10, 20), span(250, 400), span(50, 50), span(80, 60))
        val c = More.of(done, review.copy(pauseSpans = odd))!!.clock!!
        // before the start and after the end: nothing; partly outside: cut to the bar; empty / backwards: nothing
        assertEquals(2, c.gaps.size)
        assertEquals(0f, c.gaps[0].first, 0.0001f); assertEquals(20f / 275, c.gaps[0].second, 0.001f)
        assertEquals(250f / 275, c.gaps[1].first, 0.001f); assertEquals(1f, c.gaps[1].second, 0.0001f)
        c.gaps.forEach { (a, b) -> assertEquals(true, a >= 0f && b <= 1f && b > a) }
    }

    @Test fun aTourWithOddTimesAndNoTimesDrawsNoBarOrOnlyOneInsideItsLength() {
        val t0 = start
        fun pt(i: Int, ms: Long?) = com.onpath.app.gpx.TrackPoint(47.0, 11.0 + i * 0.0001, null, ms)
        // equal times: no length, no bar
        val same = TourReview.of(listOf((0 until 5).map { pt(it, t0) }), null, 0.0, 100.0)
        assertNull(More.of(done.copy(activity = null, descentM = 0.0), same))
        // out of order: whatever comes out stays inside 0..1
        val odd = TourReview.of(listOf((0 until 40).map { pt(it, t0 + (if (it in 10..20) 30 - it else it) * 10_000L) }), null, 0.0, 400.0)
        More.of(done, odd)?.clock?.gaps?.forEach { (a, b) -> assertEquals(true, a >= 0f && b <= 1f && b > a) }
        // no times (GPX import without times): no time bar
        val none = TourReview.of(listOf((0 until 40).map { pt(it, null) }), null, 3600.0, 400.0)
        assertNull(More.of(done.copy(activity = null, descentM = 0.0), none))
        assertNull(More.of(done, TourReview.of(emptyList(), null, 0.0, 0.0))?.clock)
    }

    @Test fun aTourAcrossMidnightAndASummerTimeChangeHasTheSameBarInEveryZoneOnlyTheLabelsDiffer() {
        val startUtc = java.time.ZonedDateTime.of(2026, 10, 24, 23, 30, 0, 0, java.time.ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        val rv = TourReview(totalS = 5 * 3600.0, movingS = 4 * 3600.0, pauseS = 3600.0, pauseCount = 1, startMs = startUtc,
            endMs = startUtc + 5 * 3600_000L, pauseSpans = listOf(TourReview.Span(startUtc + 3600_000L, startUtc + 2 * 3600_000L)))
        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Berlin"))
            val berlin = More.of(done, rv)!!.clock!!
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val utc = More.of(done, rv)!!.clock!!
            // Berlin: 23:30 → (clock back at 03:00) 03:30 local, 5 h later in real time; UTC: 21:30 → 02:30
            assertEquals("23:30", berlin.from); assertEquals("03:30", berlin.to)
            assertEquals("21:30", utc.from); assertEquals("02:30", utc.to)
            assertEquals(berlin.gaps, utc.gaps)
            assertEquals(1f / 5, berlin.gaps.single().first, 0.0001f); assertEquals(2f / 5, berlin.gaps.single().second, 0.0001f)
        } finally { java.util.TimeZone.setDefault(saved) }
    }

    @Test fun nothingLeftMeansNoMore() {
        val flat = planned.copy(kind = TourKind.IMPORTED, activity = null, descentM = 0.0)
        assertNull(More.of(flat, null))
        // a done tour whose review knows nothing extra and has no activity / descent: no „Mehr“ either
        assertNull(More.of(done.copy(activity = null, descentM = 0.0), TourReview(totalS = 100.0, movingS = 90.0)))
        assertNotNull(More.of(done.copy(activity = null, descentM = 0.0), TourReview(upMh = 300.0)))
    }
}
