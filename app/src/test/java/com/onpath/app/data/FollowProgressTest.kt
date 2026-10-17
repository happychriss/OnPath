package com.onpath.app.data

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FollowProgressTest {
    // ~10 km west→east, climbing 0 → 500 m
    private val line = (0..100).map { GeoPoint(46.67, 11.10 + it * 0.0013, 500.0 + it * 5.0) }

    @Test fun atStartEverythingRemains() {
        val r = FollowProgress(line).remaining(GeoPoint(46.6701, 11.10))!!
        assertEquals(Geo.length(line), r.distanceM, 120.0)
        assertEquals(500.0, r.ascentM, 10.0)
        assertEquals(11.0, r.offM, 3.0)
    }

    @Test fun halfwayHalfRemains() {
        val r = FollowProgress(line).remaining(GeoPoint(46.67, 11.10 + 50 * 0.0013))!!
        assertEquals(Geo.length(line) / 2, r.distanceM, 150.0)
        assertEquals(250.0, r.ascentM, 10.0)
    }

    @Test fun outAndBackPrefersLaterLegWithHint() {
        val back = line + line.reversed().drop(1)
        val p = GeoPoint(46.67, 11.10 + 20 * 0.0013)
        val fp = FollowProgress(back)
        val first = fp.remaining(p)!!
        val later = fp.remaining(p, hint = 150)!!
        assertEquals(true, later.distanceM < first.distanceM)
    }

    @Test fun tooShortLine() {
        assertNull(FollowProgress(listOf(GeoPoint(1.0, 1.0))).remaining(GeoPoint(1.0, 1.0)))
        assertNotNull(FollowProgress(line.take(2)).remaining(GeoPoint(46.67, 11.10)))
    }
}
