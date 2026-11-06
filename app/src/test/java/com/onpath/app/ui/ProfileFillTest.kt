package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The profile's fill (owner 2026-11-06, polish 8): stronger where steep, shading instead of stripes. */
class ProfileFillTest {
    private val top = ProfileFill.LEVELS - 1

    /** Points every [step] m with the given slope (%) per stretch. */
    private fun profile(step: Double, vararg stretches: Pair<Int, Double>): Pair<DoubleArray, DoubleArray> {
        val d = ArrayList<Double>(); val e = ArrayList<Double>()
        var x = 0.0; var h = 500.0
        d += x; e += h
        for ((count, pct) in stretches) repeat(count) { x += step; h += step * pct / 100; d += x; e += h }
        return d.toDoubleArray() to e.toDoubleArray()
    }

    @Test fun flatIsNothingExtra() {
        val (d, e) = profile(10.0, 100 to 2.0)
        assertTrue(ProfileFill.levels(d, e).all { it == 0 })
    }

    @Test fun aLongSteepClimbIsTheStrongest() {
        val (d, e) = profile(10.0, 100 to 20.0)
        assertTrue(ProfileFill.levels(d, e).all { it == top })
    }

    @Test fun inBetweenIsInBetween() {
        val (d, e) = profile(10.0, 100 to 10.0)
        val l = ProfileFill.levels(d, e)
        assertTrue(l.all { it in 1 until top })
    }

    @Test fun aShortRampPaintsNoStripe() {
        // 30 m at 25 % in a long flat way: over the 300 m window it is a gentle bump
        val (d, e) = profile(10.0, 50 to 1.0, 3 to 25.0, 50 to 1.0)
        assertTrue(ProfileFill.levels(d, e).all { it <= 1 })
    }

    @Test fun neighbouringPointsDifferByAtMostOneStepOnAnEvenClimb() {
        val (d, e) = profile(10.0, 60 to 3.0, 60 to 9.0, 60 to 16.0)
        val l = ProfileFill.levels(d, e)
        l.toList().zipWithNext().forEach { (a, b) -> assertTrue("$a → $b", kotlin.math.abs(a - b) <= 1) }
    }

    @Test fun tinyProfilesNeverThrow() {
        assertEquals(0, ProfileFill.levels(DoubleArray(0), DoubleArray(0)).size)
        assertEquals(0, ProfileFill.levels(doubleArrayOf(0.0), doubleArrayOf(5.0))[0])
        ProfileFill.levels(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 2.0, 3.0))
    }

    @Test fun theStrongestStepIsTheMaxExtra() = assertEquals(ProfileFill.MAX_EXTRA_ALPHA, ProfileFill.alpha(top), 1e-6f)
}
