package com.onpath.app.time

import com.onpath.app.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BreakDetectorTest {
    private val lat0 = 46.67
    private val lon0 = 11.16
    private fun lonAt(m: Double) = lon0 + m / 76_500.0 // ~76.5 km per degree at 46.7° N

    /** Walk 1 m/s east, one fix per second, from [fromS] for [s] seconds starting at [startM]; returns the end position. */
    private fun walk(b: BreakDetector, fromS: Int, s: Int, startM: Double): Double {
        for (i in 0..s) b.onFix((fromS + i).toDouble(), lat0, lonAt(startM + i))
        return startM + s
    }

    private fun stand(b: BreakDetector, fromS: Int, s: Int, atM: Double, jitterM: Double = 6.0) {
        for (i in 0..s step 5) b.onFix((fromS + i).toDouble(), lat0, lonAt(atM + if (i % 10 == 0) jitterM else -jitterM))
    }

    @Test fun shortStopsAreMovingTime() {
        val b = BreakDetector()
        var m = walk(b, 0, 600, 0.0)
        stand(b, 601, 60, m)              // 1 min photo / map stop
        m = walk(b, 670, 600, m + 1)
        assertEquals(0.0, b.breakS(), 0.0)
        assertEquals(0, b.count)
    }

    @Test fun longStopIsABreak() {
        val b = BreakDetector()
        var m = walk(b, 0, 600, 0.0)
        stand(b, 601, 600, m)              // 10 min
        assertTrue(b.inBreak)
        assertTrue("${b.breakS()}", b.breakS() in 595.0..650.0) // counted while still going on (incl. the last ~25 m)
        m = walk(b, 1210, 300, m + 1)
        assertFalse(b.inBreak)
        assertTrue("${b.breakS()}", b.breakS() in 600.0..660.0)
        assertEquals(1, b.count)
    }

    @Test fun gpsGapWithoutMovementIsABreak() {
        val b = BreakDetector()
        val m = walk(b, 0, 300, 0.0)
        // 40 min in the hut without a fix, first fix 30 m away (walked out of the door)
        b.onFix(300.0 + 2400, lat0, lonAt(m + 30))
        assertTrue(b.breakS() >= 2390)
        // ... but a GPS gap while walking on (tunnel / forest) is not
        val c = BreakDetector()
        val m2 = walk(c, 0, 300, 0.0)
        c.onFix(300.0 + 300, lat0, lonAt(m2 + 300))
        assertEquals(0.0, c.breakS(), 0.0)
    }

    @Test fun resetEndsABreakAndSurvivesJson() {
        val b = BreakDetector()
        val m = walk(b, 0, 100, 0.0)
        stand(b, 101, 300, m)
        val copy = BreakDetector.fromJson(org.json.JSONObject(b.toJson().toString()))
        assertEquals(b.breakS(), copy.breakS(), 1e-9)
        assertTrue(copy.inBreak)
        copy.reset()
        assertFalse(copy.inBreak)
        assertEquals(b.breakS(), copy.breakS(), 1e-9)
        assertEquals(0.0, BreakDetector.fromJson(null).breakS(), 0.0)
    }

    @Test fun breaksInStoredSegments() {
        val t0 = 1_760_000_000_000L
        val seg = mutableListOf<TrackPoint>()
        for (i in 0..300) seg += TrackPoint(lat0, lonAt(i.toDouble()), null, t0 + i * 1000L)
        for (i in 0..20) seg += TrackPoint(lat0, lonAt(300.0 + (i % 2) * 5), null, t0 + (301 + i * 30) * 1000L) // 10 min, a point per 30 s
        for (i in 0..100) seg += TrackPoint(lat0, lonAt(330.0 + i), null, t0 + (930 + i) * 1000L)
        val b = BreakDetector.breaksIn(listOf(seg, emptyList(), listOf(TrackPoint(lat0, lon0))))
        assertTrue("$b", b in 560.0..640.0)
    }

    @Test fun tourTimesTextAndStore() {
        val t = TourTimes.of(3.0 * 3600, 25 * 60.0)
        assertEquals("Moving 2:35 · breaks 0:25", t.movingLine)
        assertNull(t.planLine)
        assertEquals("On the route: planned 3:10 – you needed 2:52", TourTimes(1.0, 0.0, 190 * 60.0, 172 * 60.0).planLine)
        assertNull(TourTimes(1.0, 0.0, 300.0, 280.0).planLine) // too little on the route to compare
        assertEquals(0.0, TourTimes.of(100.0, 500.0).movingS, 0.0) // never negative
        val f = File.createTempFile("tour-times", ".json").apply { delete() }
        val store = TourTimesStore(f)
        assertNull(store.get(7))
        store.put(7, TourTimes(100.0, 20.0, null, null))
        store.put(8, TourTimes(1.0, 2.0, 3.0, 4.0))
        assertEquals(TourTimes(100.0, 20.0, null, null), store.get(7))
        assertEquals(TourTimes(1.0, 2.0, 3.0, 4.0), TourTimesStore(f).get(8))
        f.writeText("broken {")
        assertNull(store.get(7))
        store.put(9, TourTimes(5.0, 0.0)) // a broken file is replaced, never throws
        assertEquals(5.0, store.get(9)!!.movingS, 0.0)
        f.delete()
    }
}
