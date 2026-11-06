package com.onpath.app.ui

import com.onpath.app.routing.RouteSummary.Steepness
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The profile's fill (owner 2026-11-06, polish 8): steeper is stronger, a short bump paints no stripe. */
class ProfileFillTest {
    private fun dist(n: Int, stepM: Double) = DoubleArray(n) { it * stepM }

    @Test fun eachSteepnessIsItsLevel() {
        val s = List(40) { Steepness.FLAT } + List(40) { Steepness.STEEP }
        val l = ProfileFill.levels(s, dist(81, 10.0))
        assertEquals(0, l[0]); assertEquals(Steepness.STEEP.ordinal, l[79])
    }

    @Test fun aShortSteepBumpTakesItsNeighboursLevel() {
        // 30 m very steep between two long flat stretches: no stripe
        val s = List(30) { Steepness.FLAT } + List(3) { Steepness.VERY_STEEP } + List(30) { Steepness.FLAT }
        val l = ProfileFill.levels(s, dist(64, 10.0))
        assertArrayEquals(IntArray(63), l)
    }

    @Test fun aShortFlatDipBetweenSteepPartsTakesTheLowerLevel() {
        val s = List(20) { Steepness.MODERATE } + List(2) { Steepness.FLAT } + List(20) { Steepness.STEEP }
        val l = ProfileFill.levels(s, dist(43, 10.0))
        // the 20 m flat run is short: it takes the lower neighbour (MODERATE), never invents steepness
        assertEquals(Steepness.MODERATE.ordinal, l[20]); assertEquals(Steepness.MODERATE.ordinal, l[21])
    }

    @Test fun aLongRunStays() {
        val s = List(20) { Steepness.FLAT } + List(15) { Steepness.VERY_STEEP } + List(20) { Steepness.FLAT }
        val l = ProfileFill.levels(s, dist(56, 10.0))
        assertEquals(Steepness.VERY_STEEP.ordinal, l[25])
    }

    @Test fun emptyAndTinyProfilesNeverThrow() {
        assertEquals(0, ProfileFill.levels(emptyList(), DoubleArray(0)).size)
        assertEquals(1, ProfileFill.levels(listOf(Steepness.STEEP), doubleArrayOf(0.0, 5.0)).size)
    }
}
