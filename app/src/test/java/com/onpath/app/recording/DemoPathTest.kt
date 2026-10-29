package com.onpath.app.recording

import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Developer tools → Demo: the simulated positions along a line (DemoPath). */
class DemoPathTest {
    /** 1 km north, then 1 km east (Meran-ish), flat. */
    private val ell = listOf(GeoPoint(46.67, 11.16, 300.0), GeoPoint(46.67 + 1000 / 111_195.0, 11.16, 300.0),
        GeoPoint(46.67 + 1000 / 111_195.0, 11.16 + 1000 / (111_195.0 * Math.cos(Math.toRadians(46.679))), 300.0))

    @Test fun constantSpeedPositionsAlongTheLine() {
        val p = DemoPath.constant(ell, 2.0)!!
        assertEquals(2000.0, p.lengthM, 2.0)
        assertEquals(p.lengthM / 2.0, p.durationS, 1e-6)
        val start = p.at(0.0)
        assertEquals(ell[0].lat, start.lat, 1e-9); assertEquals(ell[0].lon, start.lon, 1e-9)
        assertEquals(0.0, start.alongM, 1e-9)
        // 250 s at 2 m/s = 500 m: half way up the first leg, heading north
        val a = p.at(250.0)
        assertEquals(500.0, a.alongM, 1.0)
        assertEquals(500.0, Geo.distance(ell[0].lat, ell[0].lon, a.lat, a.lon), 1.0)
        assertEquals(0.0, a.bearingDeg!!, 0.5)
        assertEquals(2.0, a.speedMs, 1e-6)
        assertFalse(a.ended)
        // on the second leg: heading east
        val b = p.at(750.0)
        assertEquals(1500.0, b.alongM, 1.5)
        assertEquals(90.0, b.bearingDeg!!, 0.5)
        assertEquals(300.0, b.ele!!, 1e-9)
    }

    @Test fun endStandsAtTheLastPoint() {
        val p = DemoPath.constant(ell, 5.0)!!
        for (t in listOf(p.durationS, p.durationS + 1, p.durationS * 10)) {
            val e = p.at(t)
            assertTrue(e.ended)
            assertEquals(ell.last().lat, e.lat, 1e-9); assertEquals(ell.last().lon, e.lon, 1e-9)
            assertNull("standing: no bearing", e.bearingDeg)
            assertEquals(0.0, e.speedMs, 0.0)
            assertEquals(p.lengthM, e.alongM, 1e-6)
        }
        assertFalse(p.at(p.durationS - 0.5).ended)
    }

    @Test fun progressNeverGoesBack() {
        val p = DemoPath.constant(ell, 1.3)!!
        var last = -1.0
        var t = 0.0
        while (t <= p.durationS + 5) { val a = p.at(t).alongM; assertTrue(a >= last - 1e-9); last = a; t += 7.3 }
    }

    @Test fun noLineNoPath() {
        assertNull(DemoPath.constant(emptyList(), 1.0))
        assertNull(DemoPath.constant(listOf(ell[0]), 1.0))
        assertNull(DemoPath.constant(listOf(ell[0], ell[0]), 1.0))
        assertNull(DemoPath.constant(ell, 0.0))
        assertNull(DemoPath.timed(listOf(ell[0]), Activity.HIKE))
    }

    @Test fun duplicateStartPointStillHasABearing() {
        val p = DemoPath.constant(listOf(ell[0]) + ell, 1.0)!!
        assertNotNull(p.at(0.0).bearingDeg)
        assertEquals(0.0, p.at(0.0).bearingDeg!!, 0.5)
    }

    @Test fun timedFollowsTheTimeModelUphillSlowerThanDown() {
        // 2 km straight north: 1 km up 150 m, 1 km down 150 m
        val n = 40
        val line = (0..n).map { i ->
            val d = i * 2000.0 / n
            GeoPoint(46.67 + d / 111_195.0, 11.16, 500.0 + if (d <= 1000) d * 0.15 else (2000 - d) * 0.15)
        }
        val p = DemoPath.timed(line, Activity.HIKE)!!
        val rt = com.onpath.app.time.RouteTimes.of(line, Activity.HIKE)
        assertEquals("the plan's own time", rt.total(), p.durationS, 1.0)
        val up = p.at(p.durationS * 0.25).speedMs
        val down = p.at(p.durationS * 0.9).speedMs
        assertTrue("uphill $up < downhill $down", up < down)
        // heights come from the line
        assertEquals(500.0, p.at(0.0).ele!!, 1.0)
        assertTrue(p.at(p.durationS).ele!! < 510)
    }

    @Test fun bikeIsFasterThanHike() {
        val h = DemoPath.timed(ell, Activity.HIKE)!!
        val b = DemoPath.timed(ell, Activity.BIKE_TOURING)!!
        assertTrue("${b.durationS} < ${h.durationS}", b.durationS < h.durationS / 2)
        // a realistic hiking speed on the flat (3–6 km/h)
        val v = h.lengthM / h.durationS * 3.6
        assertTrue("hike $v km/h", v in 3.0..6.0)
    }
}
