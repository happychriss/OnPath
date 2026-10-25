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
        // effort = km + ascent/100; Komoot's bands (owner 2026-10-25): easy ≤ 8 (≈ 2 h) and ≤ 300 m, moderate ≤ 20 and ≤ 900 m
        assertEquals(Difficulty.EASY, RouteSummary.difficulty(Activity.HIKE, 6_000.0, 200.0))       // 8
        assertEquals(Difficulty.EASY, RouteSummary.difficulty(Activity.HIKE, 5_000.0, 300.0))       // 8, cap exactly
        assertEquals(Difficulty.MODERATE, RouteSummary.difficulty(Activity.HIKE, 8_000.0, 300.0))   // 11
        assertEquals(Difficulty.MODERATE, RouteSummary.difficulty(Activity.HIKE, 12_000.0, 0.0))    // flat 3 h walk
        assertEquals(Difficulty.MODERATE, RouteSummary.difficulty(Activity.HIKE, 12_000.0, 800.0))  // 20
        assertEquals(Difficulty.DIFFICULT, RouteSummary.difficulty(Activity.HIKE, 14_000.0, 700.0)) // 21
        assertEquals(Difficulty.DIFFICULT, RouteSummary.difficulty(Activity.HIKE, 14_000.0, 1200.0)) // 26
    }

    @Test fun hikeAscentCapsTheBand() {
        // short but steep: effort alone says easy / moderate, the ascent says more
        assertEquals(Difficulty.MODERATE, RouteSummary.difficulty(Activity.HIKE, 2_000.0, 400.0))   // effort 6, 400 m up
        assertEquals(Difficulty.DIFFICULT, RouteSummary.difficulty(Activity.HIKE, 5_000.0, 1000.0)) // effort 15, 1000 m up
    }

    /** A straight line north, [n] spans of ~111 m, each span with the way of [way] (index → attrs). */
    private fun line(n: Int, way: (Int) -> WayAttrs?): List<GeoPoint> =
        (0..n).map { i -> GeoPoint(46.0 + i * 0.001, 11.0, way = if (i < n) way(i) else null) }

    private fun path(sac: Int = 0, mtb: Int = -1) = WayAttrs("path", "natural_trail", sac, mtb)

    @Test fun hikeT3SectionRaisesToModerate() {
        val pts = line(10) { i -> if (i == 4) path(sac = 3) else path(sac = 1) } // ~1.1 km, one T3 span ≈ 111 m
        val r = RouteSummary.rating(Activity.HIKE, 1_100.0, 50.0, pts)
        assertEquals(Difficulty.MODERATE, r.level)
        assertEquals("T3", r.raisedBy?.grade)
        assertEquals("Moderate · T3", r.text)
        assertEquals(111.0, r.raisedBy!!.meters, 2.0)
    }

    @Test fun hikeT4RaisesToDifficult() {
        val pts = line(10) { i -> if (i in 3..4) path(sac = 4) else path(sac = 2) }
        val r = RouteSummary.rating(Activity.HIKE, 1_100.0, 50.0, pts)
        assertEquals(Difficulty.DIFFICULT, r.level)
        assertEquals("Difficult · T4", r.text)
    }

    @Test fun hikeT2DoesNotRaise() {
        val r = RouteSummary.rating(Activity.HIKE, 1_100.0, 50.0, line(10) { path(sac = 2) })
        assertEquals(RouteSummary.Rating(Difficulty.EASY), r)
        assertEquals("Easy", r.text)
    }

    @Test fun tinyTechnicalBitsAreNoise() {
        // 50 m of T5 (one short span) on a 1 km walk: below TECHNIQUE_MIN_M, the label stays Easy
        val pts = line(10) { path(sac = 1) }.toMutableList()
        pts.add(5, GeoPoint(46.0045, 11.0, way = path(sac = 5)))
        val r = RouteSummary.rating(Activity.HIKE, 1_100.0, 50.0, pts)
        assertEquals(Difficulty.EASY, r.level)
    }

    @Test fun shortPiecesAddUp() {
        // T3 tagged in pieces (60 m each, apart): together ≥ 100 m → counted
        val pts = (0..20).map { i -> GeoPoint(46.0 + i * 0.00054, 11.0, way = path(sac = if (i % 5 == 0) 3 else 1)) }
        assertEquals(Difficulty.MODERATE, RouteSummary.rating(Activity.HIKE, 1_200.0, 0.0, pts).level)
    }

    @Test fun techniqueNeverLowersTheLabel() {
        // a long, high hike with a T3 section: Difficult from fitness, no reason shown
        val pts = line(10) { i -> if (i < 3) path(sac = 3) else path(sac = 1) }
        assertEquals(RouteSummary.Rating(Difficulty.DIFFICULT), RouteSummary.rating(Activity.HIKE, 20_000.0, 1_200.0, pts))
    }

    @Test fun linesWithoutWayAttributesRateOnFitness() {
        val pts = line(10) { null }
        assertEquals(RouteSummary.Rating(Difficulty.EASY), RouteSummary.rating(Activity.HIKE, 1_100.0, 50.0, pts))
        assertEquals(RouteSummary.Rating(Difficulty.EASY), RouteSummary.rating(Activity.MTB, 1_100.0, 50.0, emptyList()))
    }

    @Test fun bikeMtbScale() {
        val s1 = line(10) { path(mtb = 1) }
        val s2 = line(10) { i -> if (i in 2..3) path(mtb = 2) else path(mtb = 0) }
        val s3 = line(10) { i -> if (i in 2..3) path(mtb = 3) else path(mtb = 1) }
        for (a in listOf(Activity.MTB, Activity.EMTB, Activity.BIKE_TREKKING, Activity.BIKE_TOURING, Activity.EBIKE)) {
            assertEquals(a.name, Difficulty.EASY, RouteSummary.rating(a, 1_100.0, 20.0, s1).level)
            assertEquals(a.name, "Moderate · S2", RouteSummary.rating(a, 1_100.0, 20.0, s2).text)
            assertEquals(a.name, "Difficult · S3", RouteSummary.rating(a, 1_100.0, 20.0, s3).text)
        }
    }

    @Test fun bikeOnT3TrailIsDifficult() {
        val pts = line(10) { i -> if (i in 2..3) path(sac = 3) else path(sac = 1) }
        assertEquals("Difficult · T3", RouteSummary.rating(Activity.MTB, 1_100.0, 20.0, pts).text)
        // T2 on a bike alone does not raise it (pushing is in the route warnings)
        assertEquals(Difficulty.EASY, RouteSummary.rating(Activity.MTB, 1_100.0, 20.0, line(10) { path(sac = 2) }).level)
    }

    @Test fun bikeLabelNamesTheMtbGradeFirst() {
        val pts = line(10) { i -> when (i) { in 0..1 -> path(sac = 3, mtb = 1); in 2..3 -> path(mtb = 4); else -> path() } }
        assertEquals("Difficult · S4", RouteSummary.rating(Activity.MTB, 1_100.0, 20.0, pts).text)
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
