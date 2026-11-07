package com.onpath.app.domain.map

import com.onpath.app.domain.map.MapOrientation.Follow
import com.onpath.app.domain.map.MapOrientation.Tap
import com.onpath.app.domain.map.Orientation.NORTH
import com.onpath.app.domain.map.Orientation.TRAVEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One orientation for the whole session (owner 2026-11-07): the table of every trigger. */
class MapOrientationTest {

    @Test fun theSettingIsTheOrientation() {
        assertEquals(TRAVEL, MapOrientation.of(headingUp = true))
        assertEquals(NORTH, MapOrientation.of(headingUp = false))
    }

    @Test fun followingIsTheSameInPlanAndNavigate() {
        // locate in Plan, entering Navigate, return to the app, free-look return: all follow by the orientation alone
        assertEquals(Follow.TRAVEL, MapOrientation.follow(TRAVEL))
        assertEquals(Follow.NORTH, MapOrientation.follow(NORTH))
    }

    @Test fun cameraMovesEndNorthUpOnlyWithNorth() {
        // fit to route, whole route, plan shown whole, plan link, fly to a search result, centre at the start
        assertTrue(MapOrientation.moveNorthUp(NORTH))
        assertFalse(MapOrientation.moveNorthUp(TRAVEL))
    }

    @Test fun leavingNavigateTurnsNorthOnlyWithNorthAndWithoutFollowing() {
        assertTrue(MapOrientation.northOnLeavingNavigate(NORTH, following = false))
        assertFalse("following north up is north already", MapOrientation.northOnLeavingNavigate(NORTH, following = true))
        assertFalse("TRAVEL keeps the bearing", MapOrientation.northOnLeavingNavigate(TRAVEL, following = false))
        assertFalse(MapOrientation.northOnLeavingNavigate(TRAVEL, following = true))
    }

    @Test fun compassTapTable() {
        val rows = listOf(
            // orientation, map turned, following, fix → result
            Row(NORTH, turned = true, following = false, fix = true, Tap(NORTH, turnNorth = true)),
            Row(NORTH, turned = true, following = false, fix = false, Tap(NORTH, turnNorth = true)),
            Row(NORTH, turned = true, following = true, fix = true, Tap(NORTH, follow = true)),
            Row(NORTH, turned = false, following = false, fix = true, Tap(TRAVEL, follow = true)),
            Row(NORTH, turned = false, following = true, fix = true, Tap(TRAVEL, follow = true)),
            Row(NORTH, turned = false, following = false, fix = false, Tap(NORTH, noFix = true)),
            Row(TRAVEL, turned = true, following = true, fix = true, Tap(NORTH, follow = true)),
            Row(TRAVEL, turned = true, following = false, fix = true, Tap(NORTH, turnNorth = true)),
            Row(TRAVEL, turned = false, following = false, fix = false, Tap(NORTH, turnNorth = true)),
            Row(TRAVEL, turned = true, following = false, fix = false, Tap(NORTH, turnNorth = true)),
        )
        for (r in rows) assertEquals(r.toString(), r.want, MapOrientation.tap(r.o, r.turned, r.following, r.fix))
    }

    @Test fun onlyTheTapChangesTheOrientation() {
        // two taps always come back: NORTH → TRAVEL → NORTH
        val first = MapOrientation.tap(NORTH, mapTurned = false, following = false, hasFix = true)
        val second = MapOrientation.tap(first.orientation, mapTurned = true, following = true, hasFix = true)
        assertEquals(TRAVEL, first.orientation)
        assertEquals(NORTH, second.orientation)
        // a north-up snap of a hand rotation never leaves NORTH
        assertEquals(NORTH, MapOrientation.tap(NORTH, mapTurned = true, following = false, hasFix = true).orientation)
    }

    private data class Row(val o: Orientation, val turned: Boolean, val following: Boolean, val fix: Boolean, val want: Tap)
}
