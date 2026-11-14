package com.onpath.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A held place's add entries in the browser = the app's PlaceAddMenu (owner 2026-11-07): Engine.placeMenu passes the
 * plan through; „Als Zwischenstopp“ goes between the points, never behind the goal; the planner has no tour.
 */
class WebPlaceMenuTest {
    private val ab = "46.6700,11.1600;46.6700,11.2600" // A → B, ~7.6 km east

    @Test fun emptyPlanOffersStartHereAndGoHere() =
        assertTrue(Engine.placeMenu("", 46.67, 11.16, false).startsWith("{\"main\":[\"START_HERE\",\"GO_HERE\"]"))

    @Test fun onePointSetsTheDestination() =
        assertTrue(Engine.placeMenu("46.67,11.16", 46.68, 11.20, false).startsWith("{\"main\":[\"SET_DESTINATION\"]"))

    @Test fun aPlaceOnTheWayIsAStopFirstAndGoesBetween() {
        val m = Engine.placeMenu(ab, 46.6702, 11.2100, false)
        assertTrue(m, m.startsWith("{\"main\":[\"ADD_STOP\"]"))
        assertTrue(m, m.contains("\"more\":[\"NEW_DESTINATION\"]"))
        assertEquals(m, 1, Regex("\"stop\":(\\d+)").find(m)!!.groupValues[1].toInt())
    }

    @Test fun aPlaceFarPastTheGoalIsTheNewGoal() {
        val m = Engine.placeMenu(ab, 46.6700, 11.6000, false)
        assertTrue(m, m.startsWith("{\"main\":[\"NEW_DESTINATION\"]"))
    }

    // „Als neues Ziel“ in the planner (no tour) keeps the old goal as the last stop – PlaceAddMenu.keepsOldGoal (owner 2026-11-14)
    @Test fun theNewGoalKeepsTheOldOneInThePlanner() {
        val m = Engine.placeMenu(ab, 46.6700, 11.6000, false)
        assertTrue(m, m.contains("\"keepOld\":true"))
    }

    // the locate distance (the app's LocateDistance): only while I am off the visible map
    @Test fun locateLabelOnlyOffTheMap() {
        assertEquals("", Engine.locateLabel(true, 46.67, 11.16, 46.6, 11.1, 46.7, 11.2)) // in view
        assertEquals("", Engine.locateLabel(false, 0.0, 0.0, 46.6, 11.1, 46.7, 11.2))   // no position
        assertTrue(Engine.locateLabel(true, 51.17, 6.93, 46.6, 11.1, 46.7, 11.2).endsWith("km")) // Hilden, map at Meran
    }
}
