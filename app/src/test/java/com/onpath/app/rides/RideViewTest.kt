package com.onpath.app.rides

import com.onpath.app.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four choices of the end-of-tour summary: stats, drawing, file layout, split – the points never change. */
class RideViewTest {
    private val t0 = 1_792_656_000_000L // 2026-10-22 08:00 UTC
    private fun pt(northM: Double, s: Int, ele: Double) = TrackPoint(46.6 + northM / 111_195.0, 11.1, ele, t0 + s * 1000L)

    // 0..10 walk (100 m / min, +10 m each), 11..19 bus (500 m / 30 s), 20..30 walk (100 m / min, −10 m each)
    private val pts = buildList {
        for (i in 0..10) add(pt(i * 100.0, i * 60, 600.0 + 10 * i))
        for (k in 1..10) add(pt(1000.0 + k * 500.0, 600 + k * 30, 700.0))
        for (k in 1..10) add(pt(6000.0 + k * 100.0, 900 + k * 60, 700.0 - 10 * k))
    }
    private val segs = listOf(pts)
    private val ride = Ride(10, 20, RideKind.VEHICLE)
    private val endMs = t0 + 1620_000L
    private val recorded = TourStats(7000.0, 100.0, 100.0, 1620.0)
    private fun rec(c: RideChoice) = RideRecord(listOf(ride), c, recorded, endMs)

    private fun assertStats(exp: TourStats, act: TourStats) {
        assertEquals("distance", exp.distanceM, act.distanceM, 2.0)
        assertEquals("ascent", exp.ascentM, act.ascentM, 0.01)
        assertEquals("descent", exp.descentM, act.descentM, 0.01)
        assertEquals("duration", exp.durationS, act.durationS, 0.01)
    }

    @Test fun statsPerChoice() {
        assertStats(recorded, RideView.stats(segs, rec(RideChoice.AS_RECORDED)))
        // rides not counted: 5 km and 5 min of bus out
        assertStats(TourStats(2000.0, 100.0, 100.0, 1320.0), RideView.stats(segs, rec(RideChoice.NOT_COUNTED)))
        // until getting on: the bus, the walk after it and the time to End tour out
        assertStats(TourStats(1000.0, 100.0, 0.0, 600.0), RideView.stats(segs, rec(RideChoice.UNTIL)))
        // split: this tour is the part before the ride …
        assertStats(TourStats(1000.0, 100.0, 0.0, 600.0), RideView.stats(segs, rec(RideChoice.SPLIT)))
        // … and the part after it is a tour of its own
        val parts = RideView.splitParts(segs, listOf(ride))
        assertEquals(1, parts.size)
        assertEquals(pts.subList(20, 31), parts[0].flatten())
        assertStats(TourStats(1000.0, 0.0, 100.0, 600.0), RideView.partStats(parts[0]))
    }

    @Test fun neverBelowZeroAndNeverMoreThanRecorded() {
        val tiny = RideRecord(listOf(ride), RideChoice.NOT_COUNTED, TourStats(10.0, 0.0, 0.0, 5.0), endMs)
        val s = RideView.stats(segs, tiny)
        assertEquals(0.0, s.distanceM, 0.0); assertEquals(0.0, s.durationS, 0.0)
    }

    @Test fun fileLayoutKeepsEveryPointOnceInOrder() {
        for (c in RideChoice.values()) {
            val l = RideView.layout(segs, rec(c))
            assertEquals("$c", pts, l.trk.flatMap { it.first } + l.dropped.flatten())
        }
        val nc = RideView.layout(segs, rec(RideChoice.NOT_COUNTED))
        assertEquals(listOf(null, RideKind.VEHICLE, null), nc.trk.map { it.second })
        assertEquals(pts.subList(11, 20), nc.trk[1].first)
        val until = RideView.layout(segs, rec(RideChoice.UNTIL))
        assertEquals(listOf(pts.subList(0, 11)), until.trk.map { it.first })
        assertEquals(pts.subList(11, 31), until.dropped.flatten())
        assertEquals(listOf(pts to null), RideView.layout(segs, null).trk)
    }

    @Test fun rideAcrossAGpsGapIsItsOwnPiece() {
        // bus without GPS: points 10 and 11 are the last before / first after the ride, nothing between
        val gapSegs = listOf(pts.subList(0, 11), pts.subList(11, 31))
        val r = RideRecord(listOf(Ride(10, 11, RideKind.VEHICLE)), RideChoice.NOT_COUNTED, recorded, endMs)
        val lines = RideView.lines(gapSegs, r)
        assertEquals(listOf(pts[10], pts[11]), lines.single { it.part == RideView.Part.VEHICLE }.points)
        val l = RideView.layout(gapSegs, r)
        assertEquals(pts, l.trk.flatMap { it.first })
    }

    @Test fun drawing() {
        val lines = RideView.lines(segs, rec(RideChoice.NOT_COUNTED))
        assertEquals(listOf(pts.subList(0, 11), pts.subList(20, 31)), lines.filter { it.part == RideView.Part.KEPT }.map { it.points })
        assertEquals(pts.subList(10, 21), lines.single { it.part == RideView.Part.VEHICLE }.points)
        assertEquals(listOf(pts.subList(0, 11)), RideView.keptSegments(segs, rec(RideChoice.UNTIL)))
        assertEquals(segs, RideView.keptSegments(segs, rec(RideChoice.AS_RECORDED)))
        assertEquals(segs, RideView.keptSegments(segs, null))
    }

    @Test fun summaryTimelineAndChoices() {
        val s = RideView.summary(segs, listOf(ride), onFoot = true, zone = java.time.ZoneOffset.UTC)!!
        assertEquals(listOf(RideView.RowKind.ON_FOOT, RideView.RowKind.VEHICLE, RideView.RowKind.ON_FOOT), s.rows.map { it.kind })
        assertEquals(60.0, s.rows[1].speedKmh!!, 0.5)
        assertEquals(5000.0, s.rows[1].distanceM, 2.0)
        assertEquals(listOf("Save only until 08:10", "Save all, rides not counted", "Split into two tracks", "Save as recorded"),
            s.options.map { it.title })
        // the hike goes on after the ride: not counted is preselected
        assertEquals(RideChoice.NOT_COUNTED, s.preselected)
        // ride at the end: "Save only until" preselected
        val atEnd = RideView.summary(listOf(pts.subList(0, 21)), listOf(ride), onFoot = true)!!
        assertEquals(RideChoice.UNTIL, atEnd.preselected)
        assertTrue(atEnd.options.none { it.choice == RideChoice.SPLIT })
        // lifts are never cut by default
        val lift = RideView.summary(listOf(pts.subList(0, 21)), listOf(ride.copy(kind = RideKind.LIFT)), onFoot = true)!!
        assertEquals(RideChoice.NOT_COUNTED, lift.preselected)
        assertNull(RideView.summary(segs, emptyList(), onFoot = true))
    }

    @Test fun ridesThatDoNotFitThePointsAreIgnored() {
        assertEquals(emptyList<Ride>(), RideView.validRides(listOf(Ride(5, 40, RideKind.VEHICLE), Ride(7, 3, RideKind.LIFT)), pts.size))
        val r = RideRecord(listOf(Ride(5, 40, RideKind.VEHICLE)), RideChoice.UNTIL, recorded, endMs)
        assertStats(recorded, RideView.stats(segs, r))
    }

    @Test fun recordJsonRoundTrip() {
        val r = rec(RideChoice.SPLIT)
        assertEquals(r, RideRecord.fromJson(r.toJson().toString()))
        assertNull(RideRecord.fromJson("{broken"))
        assertNull(RideRecord.fromJson(null))
    }
}
