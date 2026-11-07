package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the goal lies relative to the way ridden (owner 2026-11-07: "Das Ziel ist rechts von dir"). */
class GoalSideTest {
    private val here = GeoPoint(46.0, 11.0)
    private fun off(northM: Double, eastM: Double) =
        GeoPoint(46.0 + northM / 111_195.0, 11.0 + eastM / (111_195.0 * Math.cos(Math.toRadians(46.0))))

    @Test fun sidesRelativeToTheHeading() {
        assertEquals(GoalSide.AHEAD, GoalSide.of(here, 0.0, off(30.0, 5.0)))
        assertEquals(GoalSide.HALF_RIGHT, GoalSide.of(here, 0.0, off(20.0, 20.0)))
        assertEquals(GoalSide.RIGHT, GoalSide.of(here, 0.0, off(0.0, 20.0)))
        assertEquals(GoalSide.BACK_RIGHT, GoalSide.of(here, 0.0, off(-20.0, 15.0)))
        assertEquals(GoalSide.BEHIND, GoalSide.of(here, 0.0, off(-20.0, 0.0)))
        assertEquals(GoalSide.BACK_LEFT, GoalSide.of(here, 0.0, off(-20.0, -15.0)))
        assertEquals(GoalSide.LEFT, GoalSide.of(here, 0.0, off(0.0, -20.0)))
        assertEquals(GoalSide.HALF_LEFT, GoalSide.of(here, 0.0, off(20.0, -20.0)))
        // riding east: a goal to the north is on the left
        assertEquals(GoalSide.LEFT, GoalSide.of(here, 90.0, off(20.0, 0.0)))
    }

    @Test fun noSideWithoutAHeadingOrRightAtTheGoal() {
        assertNull(GoalSide.of(here, null, off(0.0, 20.0)))
        assertNull("closer than ${GoalSide.MIN_M} m it is GPS noise", GoalSide.of(here, 0.0, off(0.0, 3.0)))
    }

    /** The heading is the bearing of the last metres ridden – a corner just taken counts, standing gives none. */
    @Test fun headingFromTheTrack() {
        val track = (0..10).map { off(it * 5.0 - 50.0, 0.0) }              // northwards, every 5 m, up to 0 m
        assertEquals(0.0, GoalSide.heading(track, here, 4.0)!!, 1.0)
        val corner = (0..10).map { off(-100.0, it * 10.0 - 100.0) } + (1..4).map { off(-100.0 + it * 5.0, 0.0) }
        assertEquals("east, then 20 m north: north", 0.0, GoalSide.heading(corner, off(-75.0, 0.0), 4.0)!!, 1.0)
        assertNull("standing", GoalSide.heading(track, here, 0.3))
        assertNull("speed unknown", GoalSide.heading(track, here, null))
        assertNull("too little track", GoalSide.heading(listOf(off(-5.0, 0.0)), here, 4.0))
    }
}
