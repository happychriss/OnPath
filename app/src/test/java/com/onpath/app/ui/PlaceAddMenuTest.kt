package com.onpath.app.ui

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WaypointInsertion
import com.onpath.app.ui.PlaceAddMenu.Item
import com.onpath.app.ui.PlaceAddMenu.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The place menu's add entries (owner 2026-11-07 11:20 / 11:25 / 11:30): clear stop → only "Als Zwischenstopp", doubtful →
 * both (the likelier first), clear goal → only "Als neues Ziel"; the hidden one under "⋯ Mehr".
 */
class PlaceAddMenuTest {

    /** Hilden centre – the owner's field case. */
    private val here = GeoPoint(51.1680, 6.9390)

    private fun at(from: GeoPoint, eastM: Double, northM: Double) =
        GeoPoint(from.lat + Geo.degLat(northM), from.lon + Geo.degLon(eastM, from.lat))

    // ---- the zones, real-world-like ----

    @Test fun aViaOnThePath30mAsideIsOnlyAStop() {
        val goal = at(here, 2000.0, 0.0)
        val via = at(here, 1000.0, 30.0)
        assertEquals(Zone.STOP, PlaceAddMenu.zone(listOf(here, goal), via))
    }

    /**
     * The field case (11:25): position in Hilden centre, goal 1 km to the WEST, the church 800 m to the NORTH-EAST. As a
     * stop it adds ~1.5 km to a 1 km rest (more than doubles it) – only "Als neues Ziel"; the stop waits under "Mehr".
     */
    @Test fun churchNorthEastWithTheGoalWestIsOnlyTheGoal() {
        val goal = at(here, -1000.0, 0.0)
        val church = at(here, 565.0, 565.0)
        val zone = PlaceAddMenu.zone(listOf(here, goal), church)
        assertEquals(Zone.GOAL, zone)
        assertEquals(PlaceAddMenu.Menu(listOf(Item.NEW_DESTINATION), listOf(Item.ADD_STOP)), PlaceAddMenu.menu(2, true, zone))
    }

    /** The same church with a long rest (3 km west): the detour is under half of it – doubtful, both, the stop first. */
    @Test fun theSameChurchWithALongRestIsDoubtful() {
        val goal = at(here, -3000.0, 0.0)
        val church = at(here, 565.0, 565.0)
        assertEquals(Zone.STOP_FIRST, PlaceAddMenu.zone(listOf(here, goal), church))
    }

    @Test fun aPointJustPastTheGoalIsOnlyTheGoal() {
        val goal = at(here, 2000.0, 0.0)
        val past = at(here, 2150.0, 20.0)
        assertEquals(Zone.GOAL, PlaceAddMenu.zone(listOf(here, goal), past))
    }

    @Test fun pastTheGoalButWideOfItIsGoalFirst() {
        val goal = at(here, 2000.0, 0.0)
        val wide = at(here, 2000.0, 1300.0) // farther than the goal, ~33° off its direction
        assertEquals(Zone.GOAL_FIRST, PlaceAddMenu.zone(listOf(here, goal), wide))
    }

    @Test fun besideTheMiddleTheDetourDecides() {
        val goal = at(here, 2000.0, 0.0)
        assertEquals(Zone.STOP, PlaceAddMenu.zone(listOf(here, goal), at(here, 1000.0, 500.0)))       // +0.24 km = 12 %
        assertEquals(Zone.STOP_FIRST, PlaceAddMenu.zone(listOf(here, goal), at(here, 1000.0, 800.0))) // +0.56 km = 28 %
        assertEquals(Zone.GOAL_FIRST, PlaceAddMenu.zone(listOf(here, goal), at(here, 1000.0, 1200.0))) // +1.1 km = 56 %
        assertEquals(Zone.GOAL, PlaceAddMenu.zone(listOf(here, goal), at(here, 1000.0, 2000.0)))      // +2.5 km = 124 %
    }

    /** Out 3 km north and back to 1 km north: a place on the way out is a stop, not "past the goal". */
    @Test fun aPlaceOnTheWayOutOfAnOutAndBackIsAStop() {
        val turn = at(here, 0.0, 3000.0)
        val goal = at(here, 0.0, 1000.0)
        assertEquals(Zone.STOP, PlaceAddMenu.zone(listOf(here, turn, goal), at(here, 20.0, 2500.0)))
    }

    /** A few hundred metres to go: a place a few steps aside stays a stop, one nearby never becomes "only the goal". */
    @Test fun aShortRestKeepsTheFloors() {
        val goal = at(here, 80.0, 0.0)
        assertEquals(Zone.STOP, PlaceAddMenu.zone(listOf(here, goal), at(here, 40.0, 40.0)))
        assertNotEquals(Zone.GOAL, PlaceAddMenu.zone(listOf(here, goal), at(here, 40.0, -150.0)))
    }

    @Test fun aRoundTripIsNeverOnlyTheGoal() {
        val loop = listOf(here, at(here, 2000.0, 0.0), at(here, 2000.0, 2000.0), here)
        val far = at(here, -3000.0, -3000.0)
        assertEquals(Zone.GOAL_FIRST, PlaceAddMenu.zone(loop, far, loop = true))
    }

    @Test fun noRouteAheadNoZone() {
        assertEquals(null, PlaceAddMenu.zone(listOf(here), at(here, 100.0, 0.0)))
    }

    // ---- the menu ----

    @Test fun theMenuPerZone() {
        assertEquals(PlaceAddMenu.Menu(listOf(Item.ADD_STOP), listOf(Item.NEW_DESTINATION)), PlaceAddMenu.menu(3, false, Zone.STOP))
        assertEquals(PlaceAddMenu.Menu(listOf(Item.ADD_STOP, Item.NEW_DESTINATION)), PlaceAddMenu.menu(3, false, Zone.STOP_FIRST))
        assertEquals(PlaceAddMenu.Menu(listOf(Item.NEW_DESTINATION, Item.ADD_STOP)), PlaceAddMenu.menu(3, true, Zone.GOAL_FIRST))
        assertEquals(PlaceAddMenu.Menu(listOf(Item.NEW_DESTINATION), listOf(Item.ADD_STOP)), PlaceAddMenu.menu(3, true, Zone.GOAL))
    }

    @Test fun bothAreAlwaysReachable() {
        for (z in Zone.values()) {
            val m = PlaceAddMenu.menu(2, true, z)
            assertTrue("$z", Item.ADD_STOP in m.main + m.more && Item.NEW_DESTINATION in m.main + m.more)
        }
    }

    @Test fun fewerThanTwoPointsKeepTheFirstPointWording() {
        assertEquals(listOf(Item.START_HERE, Item.GO_HERE), PlaceAddMenu.menu(0, false, null).main)
        assertEquals(listOf(Item.GO_HERE), PlaceAddMenu.menu(0, true, null).main) // never "Hier starten" during a tour
        assertEquals(listOf(Item.SET_DESTINATION), PlaceAddMenu.menu(1, true, null).main)
    }

    // ---- "Als Zwischenstopp" keeps the goal ----

    @Test fun aStopNeverReplacesOrPassesTheGoal() {
        val goal = at(here, 2000.0, 0.0)
        val past = at(here, 2500.0, 0.0)
        val d = WaypointInsertion.decide(listOf(here, goal), past, null, 30.0, stopOnly = true)
        assertNotEquals(WaypointInsertion.Kind.DESTINATION, d.kind)
        assertEquals(1, d.index)
        // without stopOnly the same place is appended (today's smart add)
        assertEquals(WaypointInsertion.Kind.DESTINATION, WaypointInsertion.decide(listOf(here, goal), past, null, 30.0).kind)
    }
}
