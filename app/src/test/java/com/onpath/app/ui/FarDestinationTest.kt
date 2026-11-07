package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Field 2026-11-07 (owner, S10, Play 0.17.3, Hilden): "When I pick a destination outside my map region, e.g. Mechernich,
 * the app loads the new map but then forgets the destination." A destination the user chose survives map and routing
 * data loading: the pin stays until the user acts on it, an added point stays (and is routed once data is there –
 * RoutingLoadsTest), and when the app itself takes an add back the place comes back as its pin.
 */
class FarDestinationTest {
    private val hilden = GeoPoint(51.1693, 6.9393)
    private val bonn = GeoPoint(50.7374, 7.0982)
    private val mechernich = Place("photon:1", "Mechernich", "place", 50.5947, 6.6517, sub = "town")
    private val at = GeoPoint(mechernich.lat, mechernich.lon)

    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()
    private fun body(s: String, start: String) = s.substringAfter(start).substringBefore("\n    }\n")

    /** Cancel at the very-long-plan question undid the add: the place is no point any more → its pin comes back. */
    @Test fun anAddTakenBackBringsThePinBack() {
        val taken = TakenPin(mechernich, at)
        assertEquals(mechernich, TakenPin.back(taken, listOf(hilden, bonn), mark = null))
    }

    @Test fun theAddStillInThePlanBringsNoPin() {
        assertNull(TakenPin.back(TakenPin(mechernich, at), listOf(hilden, bonn, at), mark = null))
    }

    @Test fun aNewerSearchPickWins() {
        val other = Place("photon:2", "Euskirchen", "place", 50.66, 6.79)
        assertNull(TakenPin.back(TakenPin(mechernich, at), listOf(hilden, bonn), mark = other))
        assertNull(TakenPin.back(null, listOf(hilden, bonn), mark = null))
    }

    /**
     * Hilden → Bonn (50 km straight) plus Mechernich after it is a plan of more than a day on foot: adding the far place
     * asks "Plan anyway?" – the case where Cancel took the place back.
     */
    @Test fun aFarPlaceAfterTheDayTripMakesAVeryLongPlan() {
        assertTrue(com.onpath.app.routing.LongRoute.needsAsk(listOf(hilden, bonn, at), com.onpath.app.routing.Activity.HIKE))
    }

    /** The ViewModel wiring: every add from the pin remembers it, Cancel's undo asks [TakenPin.back], a newer edit forgets it. */
    @Test fun cancelOfARunShowsThePinOfTheAddItTookBack() {
        val vm = src("ui/AppViewModel.kt")
        for (f in listOf("fun addPoiPoint(", "fun poiFromMyLocation(", "private fun addPoiWith(", "fun addPlace(")) {
            val b = body(vm, f)
            assertTrue("$f takes the pin", b.contains("takePin("))
            assertTrue("$f remembers it", b.contains("pinTaken = pin?.let { TakenPin(it, p) }"))
        }
        val cancel = body(vm, "private fun cancelRun(")
        assertTrue(cancel, cancel.contains("val taken = pinTaken; pinTaken = null"))
        assertTrue(cancel, cancel.contains("TakenPin.back(taken, waypoints.toList(), searchMark)"))
        assertTrue(body(vm, "private fun change(").contains("pinTaken = null"))
    }

    /**
     * A fly to a found place is an intended camera move (CLAUDE.md "Map camera"): the start camera, still waiting for a
     * first fix, must not jump back to the position once the place's map has loaded.
     */
    @Test fun aFlyToAFoundPlaceEndsTheStartCamera() {
        val vm = src("ui/AppViewModel.kt")
        val fly = vm.substringAfter("override fun flyTo(at: GeoPoint, zoom: Double) {").substringBefore("\n            }\n")
        assertTrue(fly, fly.contains("startCameraDone = true; userMovedMap = true"))
        assertEquals(StartCamera.Step.GIVE_UP, StartCamera.decide(5_000, modeKnown = true, plan = true, userMoved = true, fixAgeS = 1))
    }

    /** The pin survives a map style reload: MapScreen sets it again on every new map controller (one per style load). */
    @Test fun thePinIsSetAgainAfterEveryStyleLoad() {
        assertTrue(src("ui/MapScreen.kt").contains("LaunchedEffect(c, vm.searchMark, vm.mode) {"))
        assertTrue(src("ui/MapHost.kt").contains("val c = MapController(context, map, style)"))
    }
}
