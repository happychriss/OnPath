package com.onpath.app.time

import com.onpath.app.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** The look back at a done tour (owner 2026-11-15, round 3): what the stored points give, and nothing they do not. */
class TourReviewTest {
    private val t0 = 1_763_200_000_000L
    /** Degrees of longitude per metre at 47° N. */
    private val lonPerM = 1.0 / (111_320.0 * cos(Math.toRadians(47.0)))

    /** [n] fixes walking east, one per [stepS] seconds, [stepM] metres apart; [ele] by index (null = no height). */
    private fun walk(startMs: Long, fromM: Double, n: Int, stepS: Int = 10, stepM: Double = 10.0,
                     ele: (Int) -> Double? = { 500.0 }): List<TrackPoint> =
        (0 until n).map { i -> TrackPoint(47.0, 11.0 + (fromM + i * stepM) * lonPerM, ele(i), startMs + i * stepS * 1000L) }

    /** [n] fixes standing at [atM] for [n] × 10 s. */
    private fun stand(startMs: Long, atM: Double, n: Int) =
        (0 until n).map { i -> TrackPoint(47.0, 11.0 + atM * lonPerM, 500.0, startMs + i * 10_000L) }

    @Test fun aSteadyWalkGivesTimesSpeedAndTheFastestKm() {
        // 3 km at 1 m/s, then a bit over 1 km at 2 m/s (the metres here are ~0.1 % short of Geo's)
        val slow = walk(t0, 0.0, 301)
        val fast = walk(t0 + 3_000_000L + 5_000L, 3010.0, 103, stepS = 5)
        val r = TourReview.of(listOf(slow + fast), times = null, durationS = 0.0, distanceM = 4000.0)
        assertEquals(3515.0, r.totalS!!, 1.0)
        assertEquals(0, r.pauseCount)
        assertEquals(r.totalS!!, r.movingS!!, 1.0)
        assertEquals(0.0, r.pauseS!!, 1.0)
        assertEquals(4000.0 / 1000 / (r.movingS!! / 3600), r.avgKmh!!, 0.01)
        val km = r.fastestKm!!
        assertEquals(4000.0, km.atM, 0.1)
        assertEquals(500.0, km.value, 10.0) // 1000 m at 2 m/s
    }

    @Test fun aStopInTheMiddleIsAPauseAtItsPlace() {
        val a = walk(t0, 0.0, 101)                                   // 1000 m, 1000 s
        val s = stand(t0 + 1_010_000L, 1000.0, 90)                     // 15 min standing
        val b = walk(t0 + 1_910_000L, 1000.0, 101)
        val r = TourReview.of(listOf(a + s + b), times = null, durationS = 0.0, distanceM = 2000.0)
        assertEquals(1, r.pauseCount)
        val p = r.longestPause!!
        assertTrue("about 15 min: ${p.value}", p.value in 800.0..1000.0)
        assertEquals(1000.0, p.atM, 30.0)
        assertTrue(r.movingS!! < r.totalS!! - 700)
        // „Mehr“'s time bar (owner 2026-11-19): the stop as a gap at its clock time
        val span = r.pauseSpans.single()
        assertTrue("starts at the stop: ${span.startMs - t0}", span.startMs - t0 in 900_000L..1_200_000L)
        assertEquals(p.value * 1000, (span.endMs - span.startMs).toDouble(), 1000.0)
    }

    @Test fun anhaltenBetweenTwoPartsIsAPause() {
        val a = walk(t0, 0.0, 101)
        val b = walk(t0 + 1_000_000L + 25 * 60_000L, 1000.0, 101)    // 25 min later, a new part
        val r = TourReview.of(listOf(a, b), times = null, durationS = 0.0, distanceM = 2000.0)
        assertEquals(1, r.pauseCount)
        assertEquals(25 * 60.0, r.longestPause!!.value, 1.0)
        assertEquals(1000.0, r.longestPause!!.atM, 5.0)
        assertEquals(listOf(TourReview.Span(t0 + 1_000_000L, t0 + 1_000_000L + 25 * 60_000L)), r.pauseSpans)
    }

    @Test fun theRecordingsOwnTimesWinAndThePlanLineFollowsItsRule() {
        val pts = walk(t0, 0.0, 301)
        val times = TourTimes(movingS = 2400.0, breakS = 300.0, plannedS = 3000.0, neededS = 2640.0, startMs = t0, endMs = t0 + 3_600_000L)
        val r = TourReview.of(listOf(pts), times, durationS = 0.0, distanceM = 3000.0)
        assertEquals(3600.0, r.totalS!!, 0.1)
        assertEquals(2400.0, r.movingS!!, 0.1)
        assertEquals(1200.0, r.pauseS!!, 0.1) // on the way − moving: stops and Anhalten
        assertEquals(12, r.planPct)             // 3000 planned, 2640 needed
        // slower than planned: negative; under 10 min on the route: no line (TourTimes.planLine's rule)
        assertEquals(-10, TourReview.of(listOf(pts), times.copy(neededS = 3300.0), 0.0, 3000.0).planPct)
        assertNull(TourReview.of(listOf(pts), times.copy(plannedS = 500.0), 0.0, 3000.0).planPct)
        assertNull(TourReview.of(listOf(pts), times.copy(plannedS = null), 0.0, 3000.0).planPct)
    }

    /**
     * My routes' row (owner 2026-11-16) shows the overview's „Ø Tempo“ from the stored times alone: the same number as
     * the review whenever the times know start and end – else nothing (never a number computed another way).
     */
    @Test fun theRowsAverageIsTheReviewsFromTheStoredTimes() {
        val pts = walk(t0, 0.0, 301)
        val times = TourTimes(movingS = 2400.0, breakS = 300.0, startMs = t0, endMs = t0 + 3_600_000L)
        val review = TourReview.of(listOf(pts), times, durationS = 0.0, distanceM = 3000.0)
        assertEquals(4.5, review.avgKmh!!, 0.001) // 3 km in 40 min moving
        assertEquals(review.avgKmh!!, TourReview.averageFromTimes(times, 3000.0)!!, 1e-9)
        // moving longer than on the way: both cap it at on the way
        val over = times.copy(movingS = 5000.0)
        assertEquals(TourReview.of(listOf(pts), over, 0.0, 3000.0).avgKmh!!, TourReview.averageFromTimes(over, 3000.0)!!, 1e-9)
        // start / end not known (tours before 2026-11-01): the review needs the points – the row says nothing
        assertNull(TourReview.averageFromTimes(times.copy(startMs = null), 3000.0))
        assertNull(TourReview.averageFromTimes(times.copy(endMs = t0), 3000.0))
        assertNull(TourReview.averageFromTimes(null, 3000.0))
        // under a minute of moving time, no length: no average, as in the review
        assertNull(TourReview.averageFromTimes(times.copy(movingS = 50.0), 3000.0))
        assertNull(TourReview.of(listOf(pts), times.copy(movingS = 50.0), 0.0, 3000.0).avgKmh)
        assertNull(TourReview.averageFromTimes(times, 0.0))
    }

    @Test fun noTimesAtAllSaysNothingAboutMovingOrPauses() {
        val pts = walk(t0, 0.0, 301).map { it.copy(timeMs = null) }
        val r = TourReview.of(listOf(pts), times = null, durationS = 5400.0, distanceM = 3000.0)
        assertEquals(5400.0, r.totalS!!, 0.1) // the tour's stored duration
        assertNull(r.movingS); assertNull(r.pauseS); assertNull(r.pauseCount); assertNull(r.avgKmh)
        assertNull(r.fastestKm); assertNull(r.longestPause); assertNull(r.upMh)
        assertTrue(r.profile.isNotEmpty()) // heights without times still draw the profile
    }

    @Test fun onePointOrNothingNeverThrows() {
        val one = TourReview.of(listOf(listOf(TrackPoint(47.0, 11.0, null, t0))), null, 0.0, 0.0)
        assertNull(one.totalS); assertNull(one.highest); assertTrue(one.profile.isEmpty()); assertNull(one.fastestKm)
        val none = TourReview.of(emptyList(), null, 120.0, 0.0)
        assertEquals(120.0, none.totalS!!, 0.1)
        assertNull(none.pauseCount)
        TourReview.of(listOf(emptyList(), emptyList()), null, 0.0, 0.0)
    }

    @Test fun standingTheWholeTimeIsAllPause() {
        val r = TourReview.of(listOf(stand(t0, 0.0, 180)), null, 0.0, 0.0)
        assertEquals(1, r.pauseCount)
        assertNull(r.movingS) // nothing left moving
        assertNull(r.avgKmh)
    }

    @Test fun highestPointAndClimbRates() {
        // 2 km up 300 m in 40 min, 2 km down 300 m in 20 min
        val up = walk(t0, 0.0, 201, stepS = 12, ele = { i -> 500.0 + i * 1.5 })
        val down = walk(t0 + 2_412_000L, 2010.0, 200, stepS = 6, ele = { i -> 800.0 - (i + 1) * 1.5 })
        val r = TourReview.of(listOf(up + down), null, 0.0, 4000.0)
        assertEquals(800.0, r.highest!!.value, 0.1)
        assertEquals(2000.0, r.highest!!.atM, 15.0)
        assertEquals(450.0, r.upMh!!, 25.0)   // 300 m in 40 min
        assertEquals(900.0, r.downMh!!, 50.0) // 300 m in 20 min
        assertTrue(r.profile.size <= TourReview.PROFILE_MAX + 1)
        assertTrue(r.profile.any { it.ele == 800.0 })
    }

    @Test fun aFlatWalkHasNoClimbRates() {
        val r = TourReview.of(listOf(walk(t0, 0.0, 301)), null, 0.0, 3000.0)
        assertNull(r.upMh); assertNull(r.downMh)
    }

    @Test fun rankAmongComparableToursFromThreeOn() {
        assertNull(TourReview.rankOf(5000.0, listOf(5000.0, 9000.0)))
        assertEquals(TourReview.Rank(2, 3), TourReview.rankOf(5000.0, listOf(5000.0, 9000.0, 3000.0)))
        assertEquals(TourReview.Rank(1, 4), TourReview.rankOf(9000.0, listOf(5000.0, 9000.0, 3000.0, 9000.0)))
    }

    @Test fun profileIsThinnedButKeepsItsEnds() {
        val pts = walk(t0, 0.0, 1001, ele = { i -> 400.0 + (i % 50) })
        val p = TourReview.profileOf(TourReview.alongTrack(listOf(pts)))
        assertTrue(p.size in 200..TourReview.PROFILE_MAX + 2)
        assertEquals(0.0, p.first().atM, 0.1)
        assertEquals(10_000.0, p.last().atM, 30.0)
        assertNotNull(p.maxByOrNull { it.ele })
    }

    // ---- „Mehr“'s time bar: the pause spans at their edges (owner 2026-11-19) ----

    @Test fun aStopRightAtTheStartIsAGapThatBeginsWithTheTour() {
        val s = stand(t0, 0.0, 30)                          // 5 min standing, then walking on
        val w = walk(t0 + 300_000L, 0.0, 60)
        val r = TourReview.of(listOf(s + w), null, 0.0, 600.0)
        val span = r.pauseSpans.single()
        assertEquals(t0, span.startMs)
        assertTrue("ends about when walking starts: ${span.endMs - t0}", span.endMs - t0 in 280_000L..320_000L)
        assertEquals(1, r.pauseCount)
        assertEquals(t0, r.startMs)
    }

    @Test fun aStopRightAtTheEndIsAGapThatEndsWithTheTour() {
        val w = walk(t0, 0.0, 60)
        val s = stand(t0 + 600_000L, 590.0, 30)             // 5 min standing, the recording stops there
        val r = TourReview.of(listOf(w + s), null, 0.0, 600.0)
        val span = r.pauseSpans.single()
        assertEquals(r.endMs!!, span.endMs)
        assertTrue("starts at the stop: ${span.startMs - t0}", span.startMs - t0 in 580_000L..620_000L)
    }

    @Test fun aTourWithoutPausesDrawsNoGapAndCountsZero() {
        val r = TourReview.of(listOf(walk(t0, 0.0, 200)), null, 0.0, 2000.0)
        assertEquals(emptyList<TourReview.Span>(), r.pauseSpans)
        assertEquals(0, r.pauseCount)
    }

    @Test fun onePointOrNoPointsOrNoTimesDrawNoGapAndNeverThrow() {
        assertEquals(emptyList<TourReview.Span>(),
            TourReview.of(listOf(listOf(TrackPoint(47.0, 11.0, null, t0))), null, 0.0, 0.0).pauseSpans)
        assertEquals(emptyList<TourReview.Span>(), TourReview.of(emptyList(), null, 0.0, 0.0).pauseSpans)
        val untimed = TourReview.of(listOf(walk(t0, 0.0, 100).map { it.copy(timeMs = null) }), null, 3600.0, 1000.0)
        assertEquals(emptyList<TourReview.Span>(), untimed.pauseSpans)
        assertNull(untimed.startMs); assertNull(untimed.endMs)
    }

    @Test fun aStopReportedInManyStepsIsOneGapStartingAtItsFirstFix() {
        // a fix every 10 s for 20 min: the detector extends one running break with every fix
        val a = walk(t0, 0.0, 61)                                   // 600 s
        val s = stand(t0 + 610_000L, 600.0, 120)                      // 1190 s of standing
        val b = walk(t0 + 610_000L + 1_200_000L, 800.0, 30)
        val r = TourReview.of(listOf(a + s + b), null, 0.0, 900.0)
        val span = r.pauseSpans.single()
        assertEquals(1, r.pauseCount)
        assertTrue("starts at the first standing fix: ${span.startMs - t0}", span.startMs - t0 in 590_000L..620_000L)
        assertTrue("ends at the last standing fix: ${span.endMs - t0}", span.endMs - t0 in 1_780_000L..1_820_000L)
        assertEquals(r.longestPause!!.value * 1000, (span.endMs - span.startMs).toDouble(), 1000.0)
    }

    @Test fun aStopAcrossARecordingGapIsGapsThatNeverOverlapAndKeepTheGap() {
        // standing 4 min, the recording is paused 10 min (Anhalten), standing 4 min more at the same place
        val a = walk(t0, 0.0, 31)
        val s1 = stand(t0 + 310_000L, 300.0, 24)
        val s2 = stand(t0 + 310_000L + 240_000L + 600_000L, 300.0, 24)
        val r = TourReview.of(listOf(a + s1, s2), null, 0.0, 300.0)
        val spans = r.pauseSpans
        assertTrue("the stop(s) and the gap: $spans", spans.size >= 2)
        spans.forEach { assertTrue("no negative width: $it", it.endMs > it.startMs) }
        spans.sortedBy { it.startMs }.zipWithNext { x, y -> assertTrue("no overlap: $x $y", y.startMs >= x.endMs - 1000) }
        assertTrue(spans.any { it.startMs <= t0 + 550_000L && it.endMs >= t0 + 1_130_000L })
        assertEquals(spans.size, r.pauseCount)
    }

    @Test fun fixesOutOfOrderOrWithEqualTimesNeverThrowAndGiveNoNegativeGap() {
        val w = walk(t0, 0.0, 40)
        val back = w.mapIndexed { i, p -> if (i in 10..20) p.copy(timeMs = t0 + (30 - i) * 10_000L) else p }
        val same = w.map { it.copy(timeMs = t0) }
        val shuffled = (stand(t0 + 500_000L, 300.0, 20) + stand(t0, 300.0, 20)).shuffled(java.util.Random(7))
        for (pts in listOf(back, same, shuffled, back.reversed())) {
            val r = TourReview.of(listOf(pts), null, 0.0, 400.0)
            r.pauseSpans.forEach { assertTrue("no negative width: $it", it.endMs >= it.startMs) }
            r.totalS?.let { assertTrue(it > 0) }
            r.movingS?.let { assertTrue(it > 0) }
        }
    }

    @Test fun theSpansAndDurationsOfATourAcrossMidnightAndASummerTimeChangeDoNotDependOnTheZone() {
        // Berlin: summer time ends 2026-10-25 03:00 local; the tour starts 23:30 local on the night of it
        val startUtc = java.time.ZonedDateTime.of(2026, 10, 24, 23, 30, 0, 0, java.time.ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        val a = walk(startUtc, 0.0, 61)
        val s = stand(startUtc + 610_000L, 600.0, 120)
        val b = walk(startUtc + 610_000L + 1_200_000L, 600.0, 60)
        val pts = listOf(a + s + b)
        val saved = java.util.TimeZone.getDefault()
        try {
            val results = listOf("UTC", "Europe/Berlin", "America/New_York", "Pacific/Auckland").map { z ->
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(z))
                TourReview.of(pts, null, 0.0, 1200.0).let { Triple(it.pauseSpans, it.totalS, it.pauseS) }
            }
            assertEquals(1, results.first().first.size)
            results.forEach { assertEquals(results.first(), it) }
        } finally { java.util.TimeZone.setDefault(saved) }
    }
}
