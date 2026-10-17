package com.onpath.app.routing

import com.onpath.app.routing.RouteSummary.Difficulty
import com.onpath.app.routing.RouteSummary.Steepness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSummaryTest {

    @Test fun mergeSumsPerKeyLargestFirst() {
        val m = RouteSummary.mergeShares(listOf(
            listOf(Share("path", 1000.0), Share("road", 300.0)),
            listOf(Share("road", 900.0), Share("track", 50.0), Share("steps", 0.0)),
        ))
        assertEquals(listOf(Share("road", 1200.0), Share("path", 1000.0), Share("track", 50.0)), m)
    }

    @Test fun mergeOfNothingIsEmpty() {
        assertTrue(RouteSummary.mergeShares(emptyList()).isEmpty())
        assertTrue(RouteSummary.mergeShares(listOf(emptyList(), emptyList())).isEmpty())
    }

    @Test fun hikeDifficulty() {
        // effort = km + ascent/100
        assertEquals(Difficulty.EASY, RouteSummary.difficulty(Activity.HIKE, 8_000.0, 300.0))       // 11
        assertEquals(Difficulty.MODERATE, RouteSummary.difficulty(Activity.HIKE, 12_000.0, 800.0))  // 20
        assertEquals(Difficulty.DIFFICULT, RouteSummary.difficulty(Activity.HIKE, 14_000.0, 1200.0)) // 26
    }

    @Test fun bikeDifficultyDependsOnActivity() {
        // 50 km, 500 m: effort 70 (bikes) / 75 (MTB) → moderate trekking & e-bike, difficult MTB, moderate e-MTB
        assertEquals(Difficulty.MODERATE, RouteSummary.difficulty(Activity.BIKE_TREKKING, 50_000.0, 500.0))
        assertEquals(Difficulty.MODERATE, RouteSummary.difficulty(Activity.EBIKE, 50_000.0, 500.0))
        // 40 km, 400 m: effort 56 → moderate trekking, easy e-bike
        assertEquals(Difficulty.MODERATE, RouteSummary.difficulty(Activity.BIKE_TOURING, 40_000.0, 400.0))
        assertEquals(Difficulty.EASY, RouteSummary.difficulty(Activity.EBIKE, 40_000.0, 400.0))
        assertEquals(Difficulty.DIFFICULT, RouteSummary.difficulty(Activity.MTB, 50_000.0, 500.0))
        assertEquals(Difficulty.MODERATE, RouteSummary.difficulty(Activity.EMTB, 50_000.0, 500.0))
    }

    @Test fun steepnessThresholds() {
        assertEquals(Steepness.FLAT, RouteSummary.steepnessOf(4.9))
        assertEquals(Steepness.MODERATE, RouteSummary.steepnessOf(-7.0))
        assertEquals(Steepness.STEEP, RouteSummary.steepnessOf(12.0))
        assertEquals(Steepness.VERY_STEEP, RouteSummary.steepnessOf(-20.0))
    }

    @Test fun steepnessUsesWindowAgainstNoise() {
        // 10 m samples, flat with one 3 m spike: a 100 m window keeps it flat (3 % at most)
        val dist = DoubleArray(30) { it * 10.0 }
        val ele = DoubleArray(30) { if (it == 15) 503.0 else 500.0 }
        assertTrue(RouteSummary.steepness(dist, ele).all { it == Steepness.FLAT })
        // a steady 12 % climb is "steep" everywhere
        val climb = DoubleArray(30) { 500.0 + it * 1.2 }
        assertTrue(RouteSummary.steepness(dist, climb).all { it == Steepness.STEEP })
        assertEquals(29, RouteSummary.steepness(dist, climb).size)
    }

    @Test fun steepnessOfTinyProfile() {
        assertTrue(RouteSummary.steepness(doubleArrayOf(0.0), doubleArrayOf(1.0)).isEmpty())
        assertEquals(listOf(Steepness.VERY_STEEP), RouteSummary.steepness(doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 2.0)))
    }
}
