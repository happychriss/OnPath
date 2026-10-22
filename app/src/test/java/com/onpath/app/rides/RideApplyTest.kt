package com.onpath.app.rides

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** End tour → summary choice → applied when the recording ends; raw points never touched (core feature C4). */
class RideApplyTest {

    private class FakeStore : RideStore {
        val tours = LinkedHashMap<Long, Tour>()
        val points = LinkedHashMap<Long, List<List<TrackPoint>>>()
        var failSetRides = false
        private var next = 100L
        override fun tour(id: Long) = tours[id]
        override fun segments(id: Long) = points[id] ?: emptyList()
        override fun setRides(id: Long, rec: RideRecord?, stats: TourStats): Boolean {
            if (failSetRides) throw IllegalStateException("disk full")
            val t = tours[id] ?: return false
            tours[id] = t.copy(rides = rec, distanceM = stats.distanceM, ascentM = stats.ascentM, descentM = stats.descentM, durationS = stats.durationS)
            return true
        }
        override fun insertPart(from: Tour, name: String, segments: List<List<TrackPoint>>, stats: TourStats, createdMs: Long): Long {
            val id = next++
            tours[id] = Tour(id, TourKind.RECORDED, name, createdMs, from.activity, from.sac, stats.distanceM, stats.ascentM,
                stats.descentM, stats.durationS, "done", emptyList())
            points[id] = segments
            return id
        }
    }

    // hike, wait, bus (14 legs), hike on for 45 min
    private val trace = TraceBuilder().walk(40.0).stand(6 * 60).bus(legs = 14).walk(45.0, heading = 300.0)
    private val rec = record(trace.fixes)
    private val endMs = trace.t + 60_000

    private fun store(): FakeStore = FakeStore().apply {
        tours[1] = Tour(1, TourKind.RECORDED, "Tour 22 Oct 2026, 08:00", trace.fixes.first().t, "HIKE", 3,
            rec.distanceM, rec.ascentM, rec.descentM, rec.durationS, "active", emptyList())
        points[1] = rec.segments
    }

    private fun rides() = RideDetector.detect(rec.segments, Activity.HIKE).also { assertEquals(1, it.size) }

    @Test fun choiceStoredBeforeStopIsAppliedAtTheEnd() {
        val s = store()
        assertTrue(RideApply.choose(s, 1, rides(), RideChoice.NOT_COUNTED))
        val parts = RideApply.finish(s, 1, rec.stats, endMs, Activity.HIKE)
        assertEquals(emptyList<Long>(), parts)
        val t = s.tours[1]!!
        assertEquals(RideChoice.NOT_COUNTED, t.rides!!.choice)
        assertEquals(rec.stats, t.rides!!.recorded)
        assertTrue("bus km out: ${t.distanceM} of ${rec.distanceM}", t.distanceM < rec.distanceM - 5_000)
        assertTrue(t.durationS < rec.durationS - 20 * 60)
        assertEquals(rec.segments, s.points[1]) // raw points untouched
    }

    @Test fun splitMakesASecondTourAndKeepsEveryPointInTheFirst() {
        val s = store()
        RideApply.choose(s, 1, rides(), RideChoice.SPLIT)
        val parts = RideApply.finish(s, 1, rec.stats, endMs, Activity.HIKE, nameAt = { "Part" })
        assertEquals(1, parts.size)
        val second = s.tours[parts[0]]!!
        assertEquals("HIKE", second.activity)
        assertTrue(second.distanceM in 2_500.0..4_500.0) // 45 min walking
        assertEquals(rec.segments, s.points[1])
        val r = rides()[0]
        assertEquals(rec.points.subList(r.to, rec.points.size), s.points[parts[0]]!!.flatten())
        assertTrue(s.tours[1]!!.durationS < 45 * 60) // first tour: 40 min hike (+ a little)
    }

    @Test fun withoutAChoiceRidesAreOnlyMarked() {
        val s = store()
        RideApply.finish(s, 1, rec.stats, endMs, Activity.HIKE)
        val t = s.tours[1]!!
        assertEquals(RideChoice.AS_RECORDED, t.rides!!.choice)
        assertEquals(rec.distanceM, t.distanceM, 0.0)
        // "Edit rides" later
        assertEquals(emptyList<Long>(), RideApply.edit(s, 1, RideChoice.UNTIL))
        assertTrue(s.tours[1]!!.distanceM < rec.distanceM)
        RideApply.edit(s, 1, RideChoice.AS_RECORDED)
        assertEquals(rec.distanceM, s.tours[1]!!.distanceM, 1e-6)
    }

    @Test fun plainHikeGetsNoRecord() {
        val walk = record(TraceBuilder().walk(60.0).fixes)
        val s = FakeStore().apply {
            tours[1] = Tour(1, TourKind.RECORDED, "T", 0, "HIKE", 3, walk.distanceM, 0.0, 0.0, 3600.0, "done", emptyList())
            points[1] = walk.segments
        }
        RideApply.finish(s, 1, walk.stats, endMs, Activity.HIKE)
        assertNull(s.tours[1]!!.rides)
        assertNull(RideApply.edit(s, 1, RideChoice.UNTIL))
    }

    @Test fun aFailureLeavesTheTourAsRecorded() {
        val s = store()
        RideApply.choose(s, 1, rides(), RideChoice.UNTIL)
        val before = s.tours[1]
        s.failSetRides = true
        assertEquals(emptyList<Long>(), RideApply.finish(s, 1, rec.stats, endMs, Activity.HIKE))
        assertNotNull(s.tours[1])
        assertEquals(before, s.tours[1])
        assertEquals(rec.segments, s.points[1])
    }
}
