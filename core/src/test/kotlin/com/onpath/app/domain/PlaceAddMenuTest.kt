package com.onpath.app.domain

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WaypointInsertion
import com.onpath.app.domain.PlaceAddMenu.Item
import com.onpath.app.domain.PlaceAddMenu.Zone
import com.onpath.strings.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The place menu's add entries (owner 2026-11-07 11:20 / 11:25 / 11:30; 2026-11-15; 2026-11-17): on a tour / a round trip
 * both „Einfügen“ and „Neues Ziel“, the likelier one first (by the zone) and emphasised; while planning only the matching
 * row in a clear case, both in between (the hold rule [PlaceAddMenu.planningHold]); one line each, only the label.
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
     * stop it adds ~1.5 km to a 1 km rest (more than doubles it) – „Neues Ziel“ first, „Einfügen“ under it.
     */
    @Test fun churchNorthEastWithTheGoalWestIsOnlyTheGoal() {
        val goal = at(here, -1000.0, 0.0)
        val church = at(here, 565.0, 565.0)
        val zone = PlaceAddMenu.zone(listOf(here, goal), church)
        assertEquals(Zone.GOAL, zone)
        assertEquals(PlaceAddMenu.Menu(listOf(Item.NEW_DESTINATION, Item.ADD_STOP)), PlaceAddMenu.menu(2, true, zone))
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

    /** Owner 2026-11-15 (after a hike): both rows always, the likelier first and emphasised – nothing under „⋯ Mehr“. */
    @Test fun theMenuPerZone() {
        assertEquals(PlaceAddMenu.Menu(listOf(Item.ADD_STOP, Item.NEW_DESTINATION)), PlaceAddMenu.menu(3, false, Zone.STOP))
        assertEquals(PlaceAddMenu.Menu(listOf(Item.ADD_STOP, Item.NEW_DESTINATION)), PlaceAddMenu.menu(3, false, Zone.STOP_FIRST))
        assertEquals(PlaceAddMenu.Menu(listOf(Item.NEW_DESTINATION, Item.ADD_STOP)), PlaceAddMenu.menu(3, true, Zone.GOAL_FIRST))
        assertEquals(PlaceAddMenu.Menu(listOf(Item.NEW_DESTINATION, Item.ADD_STOP)), PlaceAddMenu.menu(3, true, Zone.GOAL))
    }

    @Test fun bothAreAlwaysShownTheFirstEmphasised() {
        for (z in Zone.values()) {
            val m = PlaceAddMenu.menu(2, true, z)
            assertEquals("$z", setOf(Item.ADD_STOP, Item.NEW_DESTINATION), m.main.toSet())
            assertEquals("$z", m.main.first(), m.emphasised)
        }
        // one point or none: nothing to choose between, nothing emphasised
        assertEquals(null, PlaceAddMenu.menu(1, false, null).emphasised)
        assertEquals(null, PlaceAddMenu.menu(0, false, null).emphasised)
    }

    /** The owner's words (2026-11-15), one line each – where it goes shows on the map. */
    @Test fun inGermanTheOwnersWords() {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            assertEquals("Einfügen", Txt.s(Res.string.plan_add_as_stop))
            assertEquals("Neues Ziel", Txt.s(Res.string.plan_make_destination))
        } finally {
            java.util.Locale.setDefault(before)
        }
        assertEquals("Insert", Txt.s(Res.string.plan_add_as_stop))
        assertEquals("New destination", Txt.s(Res.string.plan_make_destination))
    }

    @Test fun fewerThanTwoPointsKeepTheFirstPointWording() {
        assertEquals(listOf(Item.START_HERE, Item.GO_HERE), PlaceAddMenu.menu(0, false, null).main)
        assertEquals(listOf(Item.GO_HERE), PlaceAddMenu.menu(0, true, null).main) // never "Hier starten" during a tour
        assertEquals(listOf(Item.SET_DESTINATION), PlaceAddMenu.menu(1, true, null).main)
    }

    // ---- „Einfügen“ keeps the goal ----

    @Test fun aStopNeverReplacesOrPassesTheGoal() {
        val goal = at(here, 2000.0, 0.0)
        val past = at(here, 2500.0, 0.0)
        val d = WaypointInsertion.decide(listOf(here, goal), past, null, 30.0, stopOnly = true)
        assertNotEquals(WaypointInsertion.Kind.DESTINATION, d.kind)
        assertEquals(1, d.index)
        // without stopOnly the same place is appended (today's smart add)
        assertEquals(WaypointInsertion.Kind.DESTINATION, WaypointInsertion.decide(listOf(here, goal), past, null, 30.0).kind)
    }

    @Test fun aNewDestinationKeepsTheOldGoalWhilePlanningAndReplacesItOnTheWay() {
        // owner 2026-11-14: „Beim Planen anhängen, unterwegs ersetzen“
        org.junit.Assert.assertTrue(PlaceAddMenu.keepsOldGoal(touring = false))
        org.junit.Assert.assertFalse(PlaceAddMenu.keepsOldGoal(touring = true))
    }

    // ---- a hold on the empty map (owner 2026-11-15, planning on his Pixel: „ich werde überhaupt nie gefragt“) ----

    /**
     * The owner's case: two points 2 km apart, a hold 800 m beside the middle – as a stop it adds about a quarter, it could
     * as well be where the route goes on: doubtful. Before it was set silently; now it asks („Einfügen“ first).
     */
    @Test fun aHoldInADoubtfulSpotAsks() {
        val goal = at(here, 2000.0, 0.0)
        val zone = PlaceAddMenu.zone(listOf(here, goal), at(here, 1000.0, 800.0))
        assertEquals(Zone.STOP_FIRST, zone)
        assertTrue("a doubtful hold asks", PlaceAddMenu.asksOnHold(zone))
        assertEquals(Item.ADD_STOP, PlaceAddMenu.menu(2, false, zone).emphasised)
        // farther aside: still doubtful, „Neues Ziel“ first
        val far = PlaceAddMenu.zone(listOf(here, goal), at(here, 1000.0, 1200.0))
        assertEquals(Zone.GOAL_FIRST, far)
        assertTrue(PlaceAddMenu.asksOnHold(far))
        assertEquals(Item.NEW_DESTINATION, PlaceAddMenu.menu(2, false, far).emphasised)
    }

    /** Clear cases stay as they were: set at once where they fit best (WaypointInsertion), no menu. */
    @Test fun aHoldInAClearSpotIsSetAtOnce() {
        val goal = at(here, 2000.0, 0.0)
        org.junit.Assert.assertFalse(PlaceAddMenu.asksOnHold(PlaceAddMenu.zone(listOf(here, goal), at(here, 1000.0, 30.0)))) // on the way
        org.junit.Assert.assertFalse(PlaceAddMenu.asksOnHold(PlaceAddMenu.zone(listOf(here, goal), at(here, 2150.0, 20.0)))) // past the goal
        org.junit.Assert.assertFalse(PlaceAddMenu.asksOnHold(PlaceAddMenu.zone(listOf(here, goal), at(here, 1000.0, 2000.0)))) // far off: only a goal
        org.junit.Assert.assertFalse(PlaceAddMenu.asksOnHold(null)) // fewer than 2 points / no route ahead
    }

    // ---- while planning: „die echte Routendifferenz … die kürzere gewinnt; nur fast gleich → fragen“ (owner 2026-11-17
    // 21:40 – replaces the band of 2026-11-16 (ASK_BAND 2) and way 3 of 2026-11-17 (the 60° cone behind the goal, an
    // earlier leg at most half of appending); their tests are rewritten below with the owner's newer expectations) ----

    private fun planning(points: List<GeoPoint>, s: GeoPoint, lineDp: Double? = 100.0) =
        PlaceAddMenu.planningHold(lineDp, PlaceAddMenu.detours(points, s)!!)

    private fun hold(insertM: Double, appendM: Double) = PlaceAddMenu.planningHold(100.0, PlaceAddMenu.Detours(insertM, appendM))

    /** The owner's field cases with the numbers the app measured (2026-11-17 21:40 expectations). */
    @Test fun theOwnersFieldCasesTheShorterWins() {
        // his hold 2026-11-17 21:33:42, on the paths
        assertEquals(PlaceAddMenu.HoldAction.NEW_GOAL, hold(205.0, 104.0))
        // Feldstraße (Hilden), on the paths
        assertEquals(PlaceAddMenu.HoldAction.NEW_GOAL, hold(120.0, 60.0))
        // St.-Konrad-Allee 19:51
        assertEquals(PlaceAddMenu.HoldAction.NEW_GOAL, hold(172.0, 95.0))
        // Hilden Süd 2026-11-16 23:27, straight lines: on 2026-11-16 it asked („ich wurde nicht gefragt, ob es ein
        // Zwischenpunkt sein soll“) – the owner's newer word (2026-11-17 21:40) wins: appending is clearly shorter
        assertEquals(PlaceAddMenu.HoldAction.NEW_GOAL, hold(62.0, 32.0))
        // the 22:57 case from the phone: inserting clearly shorter
        assertEquals(PlaceAddMenu.HoldAction.STOP, hold(177.0, 225.0))
        // the Färberweg junction beside the goal: about equal → it asks
        assertEquals(PlaceAddMenu.HoldAction.ASK, hold(100.0, 90.0))
        // near equal the other way round
        assertEquals(PlaceAddMenu.HoldAction.ASK, hold(100.0, 110.0))
    }

    /** „Fast gleich“ = the longer at most [PlaceAddMenu.SIMILAR] (20 %) × the shorter, the same both ways. */
    @Test fun theSimilarBandIsNamed() {
        assertEquals(1.2, PlaceAddMenu.SIMILAR, 0.0)
        assertEquals(PlaceAddMenu.HoldAction.ASK, hold(100.0, 120.0))
        assertEquals(PlaceAddMenu.HoldAction.STOP, hold(100.0, 121.0))
        assertEquals(PlaceAddMenu.HoldAction.ASK, hold(120.0, 100.0))
        assertEquals(PlaceAddMenu.HoldAction.NEW_GOAL, hold(121.0, 100.0))
        assertEquals(PlaceAddMenu.HoldAction.ASK, hold(0.0, 0.0)) // both nothing: nothing to tell apart
        assertEquals(PlaceAddMenu.HoldAction.STOP, hold(0.0, 50.0)) // on the straight leg, off the drawn line
    }

    /** In doubt the menu has both, the cheaper first and tinted (as the hold menu). */
    @Test fun inDoubtTheCheaperFirst() {
        assertEquals(listOf(Item.NEW_DESTINATION, Item.ADD_STOP), PlaceAddMenu.planningMenu(PlaceAddMenu.Detours(100.0, 90.0)).main)
        assertEquals(Item.ADD_STOP, PlaceAddMenu.planningMenu(PlaceAddMenu.Detours(100.0, 110.0)).emphasised)
    }

    /** The 22:57 case from the phone as drawn: inserting ≈ 177 m, appending ≈ 225 m – inserting clearly shorter, a stop. */
    @Test fun theTwoLegCaseFromThePhoneIsAStop() {
        val plan = listOf(GeoPoint(51.16985, 6.92581), GeoPoint(51.172954, 6.933397), GeoPoint(51.17107966, 6.93123043))
        val spot = GeoPoint(51.171181, 6.934443)
        val d = PlaceAddMenu.detours(plan, spot)!!
        assertEquals(177.0, d.insertM, 10.0)
        assertEquals(225.0, d.appendM, 10.0)
        assertEquals(PlaceAddMenu.HoldAction.STOP, planning(plan, spot))
    }

    /** Beside the middle of a long last leg: a short detour in, far to append – inserted at once (never appended). */
    @Test fun besideTheMiddleOfALongLegIsInsertedAtOnce() {
        val plan = listOf(here, at(here, 0.0, -2000.0))
        assertEquals(PlaceAddMenu.HoldAction.STOP, planning(plan, at(here, 150.0, -1000.0)))
    }

    @Test fun beyondTheGoalIsAppendedAtOnce() {
        val plan = listOf(here, at(here, 0.0, -2000.0))
        assertEquals(PlaceAddMenu.HoldAction.NEW_GOAL, planning(plan, at(here, 0.0, -2300.0)))
    }

    @Test fun onTheLineIsTheSmartInsertion() {
        val plan = listOf(here, at(here, 0.0, -2000.0))
        assertEquals(PlaceAddMenu.HoldAction.ON_LINE, planning(plan, at(here, 5.0, -1000.0), lineDp = 10.0))
    }

    /**
     * St.-Konrad-Allee as drawn (2026-11-17 19:51): a hold ~95 m straight on behind the goal – inserting ≈ 185 m, appending
     * ≈ 95 m: appending clearly shorter, the new goal at once (no cone needed any more).
     */
    @Test fun straightOnBehindTheGoalIsTheNewGoalAtOnce() {
        val goal = at(here, 0.0, -2000.0)
        val spot = at(goal, -30.0, -90.0)
        assertEquals(PlaceAddMenu.HoldAction.NEW_GOAL, planning(listOf(here, goal), spot))
    }

    /** Beside an earlier part of the route (an out-and-back), inserting there is far shorter than appending: a stop at once. */
    @Test fun besideAnEarlierPartIsAStopAtOnce() {
        val plan = listOf(here, at(here, 0.0, 1000.0), at(here, 200.0, 0.0))
        val spot = at(here, -30.0, 500.0)
        val d = PlaceAddMenu.detours(plan, spot)!!
        assertEquals("the earlier leg is the cheapest insertion", d.earlyInsertM!!, d.insertM, 0.01)
        assertEquals(PlaceAddMenu.HoldAction.STOP, planning(plan, spot))
    }

    // ---- 2026-11-17: a held place's menu while planning shows only the matching row in a clear case ----

    /** „wenn ich da nicht einfügen muss, dann braucht das auch im Menü nicht angezeigt zu werden“ (owner 2026-11-17). */
    @Test fun aHeldPlaceShowsOnlyTheMatchingRowInAClearCase() {
        val d = PlaceAddMenu.Detours(insertM = 100.0, appendM = 90.0)
        assertEquals(listOf(Item.ADD_STOP), PlaceAddMenu.planningRows(PlaceAddMenu.HoldAction.ON_LINE, d).main)
        assertEquals(listOf(Item.ADD_STOP), PlaceAddMenu.planningRows(PlaceAddMenu.HoldAction.STOP, d).main)
        assertEquals(listOf(Item.NEW_DESTINATION), PlaceAddMenu.planningRows(PlaceAddMenu.HoldAction.NEW_GOAL, d).main)
        assertEquals(listOf(Item.NEW_DESTINATION, Item.ADD_STOP), PlaceAddMenu.planningRows(PlaceAddMenu.HoldAction.ASK, d).main)
        // one row alone is not tinted (nothing to choose between); in doubt the cheaper is
        assertEquals(null, PlaceAddMenu.planningRows(PlaceAddMenu.HoldAction.STOP, d).emphasised)
        assertEquals(null, PlaceAddMenu.planningRows(PlaceAddMenu.HoldAction.NEW_GOAL, d).emphasised)
        assertEquals(Item.NEW_DESTINATION, PlaceAddMenu.planningRows(PlaceAddMenu.HoldAction.ASK, d).emphasised)
    }

    /** The rows by the place's distance to the line and its numbers – the same rule as a hold on the empty map. */
    @Test fun aHeldPlaceUsesTheHoldRule() {
        val plan = listOf(here, at(here, 0.0, -2000.0))
        fun rows(s: GeoPoint, lineDp: Double) = PlaceAddMenu.planningPlaceMenu(lineDp, PlaceAddMenu.detours(plan, s)!!).main
        assertEquals("a POI on the line", listOf(Item.ADD_STOP), rows(at(here, 5.0, -1000.0), 10.0))
        assertEquals("beside the middle", listOf(Item.ADD_STOP), rows(at(here, 150.0, -1000.0), 100.0))
        assertEquals("past the goal", listOf(Item.NEW_DESTINATION), rows(at(here, 0.0, -2300.0), 100.0))
        assertEquals("about equal: both", setOf(Item.ADD_STOP, Item.NEW_DESTINATION),
            PlaceAddMenu.planningPlaceMenu(100.0, PlaceAddMenu.Detours(100.0, 95.0)).main.toSet())
    }

    /** A round trip is never dropped by one hold: a spot far off its loop asks („Neues Ziel“ first, never silently). */
    @Test fun aHoldFarOffARoundTripAsks() {
        val loop = listOf(here, at(here, 2000.0, 0.0), at(here, 2000.0, 2000.0), here)
        assertTrue(PlaceAddMenu.asksOnHold(PlaceAddMenu.zone(loop, at(here, -3000.0, -3000.0), loop = true)))
    }

    // ---- 2026-11-17: „Always real paths“ – the decision on the router's lengths ----

    /**
     * The Feldstraße shape (Hilden, 13 points, goal at Poststraße / Feldstraße, the hold north of the goal, the last leg
     * arriving from the east): on straight lines inserting +68 m, appending +63 m – about equal, it asked. [paths] = the
     * plan, the spot and a fake router: from point 12 there is no direct way to the spot, it goes past the goal
     * (12 → spot 480 m, the leg 12 → goal 420 m, spot ↔ goal 60 m) – inserting ≈ +120 m, appending 60 m.
     */
    object Feldstrasse {
        private val goal = GeoPoint(51.1650, 6.9300)
        private fun at(eastM: Double, northM: Double) =
            GeoPoint(goal.lat + Geo.degLat(northM), goal.lon + Geo.degLon(eastM, goal.lat))
        /** 12 points coming in from the east along a street, the 13th the goal. */
        val plan: List<GeoPoint> = (11 downTo 1).map { k -> at(394.4 + 300.0 * k, if (k % 2 == 0) 40.0 else -40.0) } +
            listOf(at(394.4, 0.0), goal)
        val spot = at(0.0, 63.0)
        private val p12 = plan[plan.size - 2]
        /** The fake router: the field's lengths around the goal, 1.3 × the straight line elsewhere. */
        fun length(a: GeoPoint, b: GeoPoint): Double = when {
            a == p12 && b == spot -> 480.0
            a == p12 && b == goal -> 420.0
            (a == spot && b == goal) || (a == goal && b == spot) -> 60.0
            else -> 1.3 * Geo.distance(a, b)
        }
    }

    @Test fun feldstrasseOnStraightLinesAsks() {
        val d = PlaceAddMenu.detours(Feldstrasse.plan, Feldstrasse.spot)!!
        assertEquals(68.0, d.insertM, 2.0)
        assertEquals(63.0, d.appendM, 2.0)
        assertEquals(PlaceAddMenu.HoldAction.ASK, PlaceAddMenu.planningHold(100.0, d))
    }

    @Test fun feldstrasseOnThePathsIsTheNewGoalAtOnce() {
        val straight = PlaceAddMenu.detours(Feldstrasse.plan, Feldstrasse.spot)!!
        val d = PlaceAddMenu.pathDetours(Feldstrasse.plan, Feldstrasse.spot, straight, Feldstrasse::length)
        assertEquals(120.0, d.insertM, 1.0)
        assertEquals(60.0, d.appendM, 0.1)
        assertEquals(PlaceAddMenu.HoldAction.NEW_GOAL, PlaceAddMenu.planningHold(100.0, d))
    }

    /** The router's work is capped: the last leg first, then the 3 legs with the smallest straight detour. */
    @Test fun thePathsMeasureTheLastLegAndThreeMore() {
        val c = PlaceAddMenu.pathCandidates(Feldstrasse.plan, Feldstrasse.spot)
        assertEquals(Feldstrasse.plan.size - 2, c.first())
        assertEquals(1 + PlaceAddMenu.PATH_CANDIDATES - 1, c.size) // the last leg is one of the three nearest
        val calls = ArrayList<Pair<GeoPoint, GeoPoint>>()
        PlaceAddMenu.pathDetours(Feldstrasse.plan, Feldstrasse.spot, PlaceAddMenu.Detours(1.0, 1.0)) { a, b -> calls += a to b; Feldstrasse.length(a, b) }
        assertEquals("append first", Feldstrasse.plan.last() to Feldstrasse.spot, calls.first())
        assertEquals(1 + 3 * c.size, calls.size)
        // a far leg in the middle of a long plan is a candidate when it is among the nearest, the last leg always
        val two = listOf(here, at(here, 0.0, -2000.0))
        assertEquals(listOf(0), PlaceAddMenu.pathCandidates(two, at(here, 100.0, -500.0)))
    }

    /** Beside an earlier part on the paths too: inserting there at most half of appending → a stop at once. */
    @Test fun anEarlierLegOnThePathsIsAStop() {
        val plan = listOf(here, at(here, 0.0, 1000.0), at(here, 200.0, 0.0))
        val spot = at(here, -30.0, 500.0)
        val straight = PlaceAddMenu.detours(plan, spot)!!
        val d = PlaceAddMenu.pathDetours(plan, spot, straight) { a, b -> 1.2 * Geo.distance(a, b) }
        assertTrue("early ${d.earlyInsertM}", d.earlyInsertM != null && d.earlyInsertM!! < d.appendM / PlaceAddMenu.SIMILAR)
        assertEquals(PlaceAddMenu.HoldAction.STOP, PlaceAddMenu.planningHold(100.0, d))
    }
}
