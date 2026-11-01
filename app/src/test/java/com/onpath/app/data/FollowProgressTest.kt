package com.onpath.app.data

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowProgressTest {
    // ~10 km west→east, climbing 0 → 500 m
    private val line = (0..100).map { GeoPoint(46.67, 11.10 + it * 0.0013, 500.0 + it * 5.0) }

    @Test fun atStartEverythingRemains() {
        val r = FollowProgress(line).remaining(GeoPoint(46.6701, 11.10))!!
        assertEquals(Geo.length(line), r.distanceM, 120.0)
        assertEquals(500.0, r.ascentM, 20.0) // BRouter's 10 m buffer, like the plan sheet
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

    @Test fun atTheServiceProgressOnTheWayBack() {
        // out-and-back: the service says "3/4 along" (the way back) – no guessing which leg
        val back = line + line.reversed().drop(1)
        val total = Geo.length(back)
        val r = FollowProgress(back).at(total * 0.75, 4.0)!!
        assertEquals(total / 4, r.distanceM, 1.0)
        assertEquals(0.0, r.ascentM, 1.0)
        assertEquals(250.0, r.descentM, 10.0)
        assertEquals(4.0, r.offM, 0.0)
    }

    /**
     * Review routing M1: the plan sheet says "↑ 850 m" (BRouter's 10 m hysteresis), Navigate counted every 4 m bump of the
     * terrain – the number jumped at Start. At the start of the line both say the same.
     */
    @Test fun remainingAscentAtTheStartIsThePlansAscent() {
        // 5 km climbing 200 m with 8 m terrain bumps every 50 m
        val line = (0..100).map { i ->
            com.onpath.app.routing.GeoPoint(46.0 + i * 0.00045, 11.0, 1000.0 + i * 2.0 + if (i % 2 == 0) 0.0 else 8.0)
        }
        val plan = com.onpath.app.routing.Planner.climb(line)
        val start = FollowProgress(line).at(0.0, 0.0)!!
        assertEquals(plan.first, start.ascentM, 1.0)
        assertEquals(plan.second, start.descentM, 1.0)
        // the old GPS filter counted the bumps: clearly more
        assertTrue(Geo.climb(line.map { it.ele }).first > plan.first + 50)
    }
}
