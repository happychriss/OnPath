package com.onpath.web

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.OfflineCorridor
import com.onpath.app.routing.Rd5Parts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser loads routing parts in the phone's corridor for the plan's activity (donald 2026-11-07, app 501db30d,
 * owner: 10 km on foot, 20 km by bike): Engine.partsNeeded follows OfflineCorridor; an unknown activity gets the largest.
 */
class WebCorridorTest {
    private val pts = "46.67,11.16;46.75,11.40" // Meran → towards Sarntal: crosses part boundaries
    /** [["E10_N45.rd5",6],…] → {"E10_N45.rd5#6", …} */
    private fun parts(json: String): Set<String> =
        Regex("""\["([^"]+)",(\d+)\]""").findAll(json).map { it.groupValues[1] + "#" + it.groupValues[2] }.toSet()
    private fun expect(marginM: Double): Set<String> =
        Rd5Parts.forCorridor(listOf(GeoPoint(46.67, 11.16), GeoPoint(46.75, 11.40)), marginM).map { it.tile + "#" + it.index }.toSet()

    @Test fun hikeUsesTheTenKilometreCorridor() =
        assertEquals(expect(OfflineCorridor.HIKE_M), parts(Engine.partsNeeded(pts, "HIKE")))

    @Test fun everyBikeUsesTwentyKilometres() {
        for (a in listOf("BIKE_TOURING", "BIKE_TREKKING", "EBIKE", "MTB", "EMTB"))
            assertEquals(a, expect(OfflineCorridor.BIKE_M), parts(Engine.partsNeeded(pts, a)))
    }

    @Test fun unknownActivityGetsTheLargest() {
        assertEquals(expect(OfflineCorridor.MAX_M), parts(Engine.partsNeeded(pts, "")))
        assertEquals(expect(OfflineCorridor.MAX_M), parts(Engine.partsNeeded(pts, "NOPE")))
    }

    @Test fun hikeNeedsFewerPartsHere() {
        // Meran (11.16° E): 10 km stays east of 11° E, 20 km reaches the 10° E part – the corridors really differ here
        val hike = parts(Engine.partsNeeded(pts, "HIKE")); val bike = parts(Engine.partsNeeded(pts, "BIKE_TOURING"))
        assertTrue(bike.containsAll(hike) && bike.size > hike.size)
    }
}
