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

    // owner 2026-11-17 („wenn ich da nicht einfügen muss, dann braucht das auch im Menü nicht angezeigt zu werden“): a
    // place on the line offers only „Einfügen“ (PlaceAddMenu.planningPlaceMenu) – nothing under „⋯ Mehr“
    @Test fun aPlaceOnTheWayIsAStopFirstAndGoesBetween() {
        val m = Engine.placeMenu(ab, 46.6702, 11.2100, false)
        assertTrue(m, m.startsWith("{\"main\":[\"ADD_STOP\"]"))
        assertTrue(m, m.contains("\"more\":[]"))
        assertEquals(m, 1, Regex("\"stop\":(\\d+)").find(m)!!.groupValues[1].toInt())
    }

    // straight on far past the goal: appending (~26 km) is clearly shorter than inserting (~52 km) → only „Neues Ziel“
    @Test fun aPlaceFarPastTheGoalIsTheNewGoal() {
        val m = Engine.placeMenu(ab, 46.6700, 11.6000, false)
        assertTrue(m, m.startsWith("{\"main\":[\"NEW_DESTINATION\"]"))
    }

    // a round trip keeps the zone: both rows (the loop is never dropped by one tap)
    @Test fun aRoundTripPlaceMenuKeepsBothRows() {
        val m = Engine.placeMenu("46.6700,11.1600;46.6800,11.2000;46.6700,11.1600", 46.6900, 11.2200, true)
        assertTrue(m, Regex("\"main\":\\[\"(ADD_STOP|NEW_DESTINATION)\",\"(ADD_STOP|NEW_DESTINATION)\"]").containsMatchIn(m))
    }

    // „Als neues Ziel“ in the planner (no tour) keeps the old goal as the last stop – PlaceAddMenu.keepsOldGoal (owner 2026-11-14)
    @Test fun theNewGoalKeepsTheOldOneInThePlanner() {
        val m = Engine.placeMenu(ab, 46.6700, 11.6000, false)
        assertTrue(m, m.contains("\"keepOld\":true"))
    }

    // ---- a click on the empty map with 2+ points (the app's holdOnMap, F-PLAN-15; owner 2026-11-17 21:40: „die kürzere
    // gewinnt; nur fast gleich → fragen“ – PlaceAddMenu.planningHold, SIMILAR 1.2). Without an activity the engine keeps
    // the straight lines (the numbers below); with one it measures the paths first (holdOnPathsFallsBackToStraightLines) ----
    private val ns = "46.6000,11.2000;46.7000,11.2000" // A → B, ~11 km north, on a meridian
    private fun action(j: String) = Regex("\"action\":\"(\\w+)\"").find(j)!!.groupValues[1]
    private fun index(j: String) = Regex("\"index\":(-?\\d+)").find(j)!!.groupValues[1].toInt()

    @Test fun onTheLineGoesIntoTheLegAtOnce() {
        val j = Engine.holdOnMap(ns, 46.6500, 11.2001, false, 24.0)
        assertEquals(j, "ADD", action(j)); assertEquals(j, 1, index(j))
    }

    // straight on past the goal: appending (~3.3 km) is clearly shorter than inserting (~6.7 km) → the new goal, appended
    @Test fun straightOnPastTheGoalIsTheNewGoalAtOnce() {
        val j = Engine.holdOnMap(ns, 46.7300, 11.2000, false, 24.0)
        assertEquals(j, "ADD", action(j)); assertEquals(j, 2, index(j))
        assertTrue(j, j.contains("appending clearly shorter"))
    }

    @Test fun besideTheMiddleIsAStopAtOnce() {
        val j = Engine.holdOnMap(ns, 46.6500, 11.2100, false, 24.0) // ~760 m east of the middle: insert ~100 m, append ~5.6 km
        assertEquals(j, "ADD", action(j)); assertEquals(j, 1, index(j))
        assertTrue(j, j.contains("inserting clearly shorter"))
    }

    // beside the goal, a little on: inserting ~1.5 km, appending ~0.94 km – more than 1.2× apart → the new goal at once
    // (since 2026-11-17 21:40; under the band before it asked)
    @Test fun besideTheGoalAsksTheCheaperFirst() {
        val j = Engine.holdOnMap(ns, 46.7050, 11.2100, false, 24.0)
        assertEquals(j, "ADD", action(j)); assertEquals(j, 2, index(j))
        assertTrue(j, j.contains("appending clearly shorter"))
    }

    // near equal (inserting ~1.20 km, appending ~1.14 km: within 1.2×) → the menu, the cheaper („Neues Ziel“) first
    @Test fun nearEqualDetoursAskTheCheaperFirst() {
        val j = Engine.holdOnMap(ns, 46.7000, 11.2150, false, 24.0)
        assertEquals(j, "ASK", action(j))
        assertTrue(j, j.contains("\"main\":[\"NEW_DESTINATION\",\"ADD_STOP\"]"))
        assertTrue(j, j.contains("\"stop\":1"))
    }

    // „Always real paths“ (owner 2026-11-17): with an activity the engine measures on the paths first; no routing data
    // here → the straight lines decide, the same answer, and the why says so (never an error)
    @Test fun holdOnPathsFallsBackToStraightLines() {
        val j = Engine.holdOnMap(ns, 46.6500, 11.2100, false, 24.0, "HIKE", 2)
        assertEquals(j, "ADD", action(j)); assertEquals(j, 1, index(j))
        assertTrue(j, j.contains("paths failed"))
        val m = Engine.placeMenu(ns, 46.7300, 11.2000, false, 24.0, "HIKE", 2)
        assertTrue(m, m.startsWith("{\"main\":[\"NEW_DESTINATION\"]"))
    }

    // owner 2026-11-20 („Skip the click measure on long legs“): a candidate leg over LONG_LEG_M (30 km straight) → the
    // straight lines decide at once, no path measure started (on such legs it always ran into its 1.5 s cap and then
    // decided by straight lines anyway – docs/team/items/2026-11-20-add-point-speed.md); a short leg still measures
    @Test fun aLongLegDecidesByStraightLinesWithoutMeasuring() {
        val long = "46.6000,11.2000;46.9600,11.2000" // A → B, ~40 km north
        val before = Engine.pathMeasures
        val t0 = System.currentTimeMillis()
        val j = Engine.holdOnMap(long, 46.7800, 11.2300, false, 24.0, "HIKE", 2) // ~2.3 km east of the middle
        val ms = System.currentTimeMillis() - t0
        assertEquals(j, before, Engine.pathMeasures)
        assertEquals(j, "ADD", action(j)); assertEquals(j, 1, index(j))
        assertTrue(j, j.contains("leg over 30 km"))
        assertTrue("$ms ms: $j", ms < 1_000)
        val m = Engine.placeMenu(long, 46.7800, 11.2300, false, 24.0, "HIKE", 2)
        assertEquals(m, before, Engine.pathMeasures)
        assertTrue(m, m.startsWith("{\"main\":[\"ADD_STOP\"]"))
    }

    @Test fun aShortLegStillMeasuresOnThePaths() {
        val before = Engine.pathMeasures
        val j = Engine.holdOnMap(ns, 46.6500, 11.2100, false, 24.0, "HIKE", 2)
        assertEquals(j, before + 1, Engine.pathMeasures)
        assertTrue(j, !j.contains("leg over 30 km"))
    }

    @Test fun fewerThanTwoPointsIsTheSmartInsertion() {
        val j = Engine.holdOnMap("46.6,11.2", 46.7, 11.2, false, 24.0)
        assertEquals(j, "ADD", action(j)); assertEquals(j, 1, index(j))
    }

    // the place menu while planning = the hold's decision (AppViewModel.placeMenu → PlaceAddMenu.planningPlaceMenu): one
    // row in a clear case, both (the cheaper first) only when near equal
    @Test fun placeMenuWhilePlanningOrdersByTheDetours() {
        assertTrue(Engine.placeMenu(ns, 46.7050, 11.2100, false).startsWith("{\"main\":[\"NEW_DESTINATION\"]"))
        assertTrue(Engine.placeMenu(ns, 46.6500, 11.2100, false).startsWith("{\"main\":[\"ADD_STOP\"]"))
        assertTrue(Engine.placeMenu(ns, 46.7000, 11.2150, false).startsWith("{\"main\":[\"NEW_DESTINATION\",\"ADD_STOP\"]"))
    }

    @Test fun theHoldMenuGoesBesideTheSpot() {
        // room right of the spot → right of it (gap 22), centred on the spot vertically
        assertEquals("222,180", Engine.placeBeside(200.0, 200.0, 150, 40, 12, 0, 1000, 800, 22, 0))
        // no room right → left of the spot
        assertEquals("828,180", Engine.placeBeside(1000.0, 200.0, 150, 40, 12, 0, 1100, 800, 22, 0))
    }

    // the locate distance (the app's LocateDistance): only while I am off the visible map
    @Test fun locateLabelOnlyOffTheMap() {
        assertEquals("", Engine.locateLabel(true, 46.67, 11.16, 46.6, 11.1, 46.7, 11.2)) // in view
        assertEquals("", Engine.locateLabel(false, 0.0, 0.0, 46.6, 11.1, 46.7, 11.2))   // no position
        assertTrue(Engine.locateLabel(true, 51.17, 6.93, 46.6, 11.1, 46.7, 11.2).endsWith("km")) // Hilden, map at Meran
    }

    // ---- an add from a menu / the place card (WebApi.placeEdit = the app's PlaceAddState on the plan shown now) ----
    private fun pts(j: String) = Regex("\"points\":\"([^\"]*)\"").find(j)?.groupValues?.get(1)

    @Test fun theHoldMenusRowsAddWhereTheAppDoes() {
        // „Einfügen“ beside the middle → between A and B; „Neues Ziel“ → appended, the old goal stays (planning)
        assertEquals("46.600000,11.200000;46.650000,11.210000;46.700000,11.200000",
            pts(Engine.placeEdit("holdStop", ns, 46.65, 11.21, 24.0, "", "")))
        assertEquals("46.600000,11.200000;46.700000,11.200000;46.650000,11.210000",
            pts(Engine.placeEdit("holdGoal", ns, 46.65, 11.21, 24.0, "", "")))
    }

    @Test fun aPlaceAlreadyPlannedMovesOrOpensItsMenu() {
        // the place card on a planned place: „As destination“ moves it to the end, „As start“ makes it the start
        assertEquals("46.700000,11.200000;46.600000,11.200000", pts(Engine.placeEdit("DESTINATION", ns, 46.6, 11.2, 30.0, "A", "")))
        assertEquals("46.700000,11.200000;46.600000,11.200000", pts(Engine.placeEdit("START", ns, 46.7, 11.2, 30.0, "B", "")))
        // a held place that is a stop already: its point menu, nothing added
        assertEquals("{\"pointMenu\":1}", Engine.placeEdit("poiStop", ns, 46.7, 11.2, 30.0, "B", "hut"))
    }

    @Test fun aNewPlaceGoesWhereTheCardSaid() {
        assertEquals("46.550000,11.200000;46.600000,11.200000;46.700000,11.200000",
            pts(Engine.placeEdit("START", ns, 46.55, 11.2, 30.0, "C", "")))
        assertEquals("46.600000,11.200000;46.700000,11.200000;46.750000,11.200000",
            pts(Engine.placeEdit("poiGoal", ns, 46.75, 11.2, 30.0, "C", "hut")))
    }
}
