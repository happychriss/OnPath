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

    private fun path(sac: Int = 0, mtb: Int = -1, surface: String = "natural_trail") = WayAttrs("path", surface, sac, mtb)

    /** A line north made of runs (metres, way); each run is one span of exactly that length (haversine, R = 6371 km). */
    private fun runs(vararg r: Pair<Double, WayAttrs?>): List<GeoPoint> {
        val degPerM = 180.0 / (Math.PI * 6_371_000.0)
        var lat = 46.0
        val pts = ArrayList<GeoPoint>()
        for ((m, w) in r) { pts += GeoPoint(lat, 11.0, way = w); lat += m * degPerM }
        pts += GeoPoint(lat, 11.0)
        return pts
    }

    private fun road(surface: String = "asphalt", smoothness: String? = null) =
        WayAttrs("track", surface, smoothness = smoothness)

    // ---- long T2 raises a hike (owner 2026-10-26) ----

    @Test fun hikeLongT2RaisesToModerate() {
        // 2 km at T2 on a 3 km walk with little climbing: fitness says Easy, the long mountain path Moderate
        // (+1 m: a span measured back from degrees may come out a hair short of the exact threshold)
        val pts = runs(1_000.0 to path(sac = 1), RouteSummary.HIKE_T2_MIN_M + 1.0 to path(sac = 2))
        val r = RouteSummary.rating(Activity.HIKE, 3_000.0, 100.0, pts)
        assertEquals(Difficulty.MODERATE, r.level)
        assertEquals("Moderate · T2", r.text)
        assertEquals(2_001.0, r.raisedBy!!.meters, 1.0)
        assertEquals("Moderate because of 2.0 km at T2 or harder", r.because)
    }

    @Test fun hikeShortT2StaysEasy() {
        // just under 2 km at T2: no raise
        val pts = runs(1_000.0 to path(sac = 1), 1_990.0 to path(sac = 2))
        assertEquals(RouteSummary.Rating(Difficulty.EASY), RouteSummary.rating(Activity.HIKE, 3_000.0, 100.0, pts))
    }

    @Test fun hikeT2AndT3PiecesAddUpForTheT2Rule() {
        // 1.95 km T2 + 60 m T3 (under the 100 m noise floor for T3): together ≥ 2 km at T2 or harder → Moderate · T2
        val pts = runs(1_950.0 to path(sac = 2), 60.0 to path(sac = 3), 500.0 to path(sac = 1))
        assertEquals("Moderate · T2", RouteSummary.rating(Activity.HIKE, 2_500.0, 50.0, pts).text)
    }

    @Test fun hikeT3StillNamesT3() {
        // a long T2 stretch and a real T3 section: the label names the harder grade
        val pts = runs(3_000.0 to path(sac = 2), 200.0 to path(sac = 3))
        assertEquals("Moderate · T3", RouteSummary.rating(Activity.HIKE, 3_200.0, 100.0, pts).text)
    }

    @Test fun longT2DoesNotRaiseBikes() {
        // T2 on a bike stays in the route warnings (pushing); only Bike / E-bike count it as unpaved below
        val pts = runs(2_500.0 to path(sac = 2, surface = "asphalt"), 5_000.0 to road())
        for (a in Activity.entries.filter { it != Activity.HIKE })
            assertEquals(a.name, Difficulty.EASY, RouteSummary.rating(a, 7_500.0, 20.0, pts).level)
    }

    // ---- unpaved Bike / E-bike routes (owner 2026-10-26) ----

    @Test fun bikeUnpavedShareRaisesToModerate() {
        // 10 km, 2.5 km gravel (25 %): Bike and E-bike → Moderate · unpaved
        val pts = runs(7_500.0 to road(), 2_500.0 to road("gravel"))
        for (a in listOf(Activity.BIKE_TOURING, Activity.EBIKE)) {
            val r = RouteSummary.rating(a, 10_000.0, 50.0, pts)
            assertEquals(a.name, "Moderate · unpaved", r.text)
            assertEquals(a.name, 2_500.0, r.raisedBy!!.meters, 1.0)
            assertEquals(a.name, "Moderate because 25 % is unpaved (2.5 km)", r.because)
        }
    }

    @Test fun bikeUnpavedShareJustBelowStaysEasy() {
        // 2.4 km of 10 km (24 %), none of it loose: Easy
        val pts = runs(7_600.0 to road(), 2_400.0 to road("gravel"))
        for (a in listOf(Activity.BIKE_TOURING, Activity.EBIKE))
            assertEquals(a.name, RouteSummary.Rating(Difficulty.EASY), RouteSummary.rating(a, 10_000.0, 50.0, pts))
    }

    @Test fun everyUnpavedSurfaceCounts() {
        for (s in listOf("gravel", "unpaved", "ground", "natural_trail", "alpine")) {
            val pts = runs(3_000.0 to road(), 1_000.0 to road(s))
            assertEquals(s, Difficulty.MODERATE, RouteSummary.rating(Activity.BIKE_TOURING, 4_000.0, 10.0, pts).level)
        }
        // paved, asphalt and unknown surfaces are not unpaved
        val pts = runs(1_000.0 to road("paved"), 1_000.0 to road("unknown"), 1_000.0 to road())
        assertEquals(Difficulty.EASY, RouteSummary.rating(Activity.BIKE_TOURING, 3_000.0, 10.0, pts).level)
    }

    @Test fun bikeLongLooseStretchRaisesToModerate() {
        // E-bike, 60 km flat (Easy by fitness), 5 km on loose ground (8 %): the share is small, the distance is not
        val loose = RouteSummary.BIKE_LOOSE_MIN_M + 1.0
        val pts = runs(60_000.0 - loose to road(), loose to road("ground"))
        val r = RouteSummary.rating(Activity.EBIKE, 60_000.0, 0.0, pts)
        assertEquals("Moderate · unpaved", r.text)
        assertEquals("Moderate because of 5.0 km on loose ground", r.because)
        // just under 5 km: Easy
        val less = runs(55_100.0 to road(), 4_900.0 to road("ground"))
        assertEquals(RouteSummary.Rating(Difficulty.EASY), RouteSummary.rating(Activity.EBIKE, 60_000.0, 0.0, less))
        // Bike, 30 km (Easy by fitness), 5 km loose (17 %): Moderate; 4.9 km: Easy
        val bike = runs(30_000.0 - loose to road(), loose to road("ground"))
        assertEquals("Moderate · unpaved", RouteSummary.rating(Activity.BIKE_TOURING, 30_000.0, 0.0, bike).text)
        val bikeLess = runs(25_100.0 to road(), 4_900.0 to road("ground"))
        assertEquals(Difficulty.EASY, RouteSummary.rating(Activity.BIKE_TOURING, 30_000.0, 0.0, bikeLess).level)
    }

    @Test fun compactedGravelIsNotLooseUnlessRough() {
        // 6 km gravel (compacted folds in) of 60 km = 10 %: not loose, not a quarter → E-bike stays Easy
        val gravel = runs(54_000.0 to road(), 6_000.0 to road("gravel"))
        assertEquals(Difficulty.EASY, RouteSummary.rating(Activity.EBIKE, 60_000.0, 0.0, gravel).level)
        // the same gravel with bad smoothness is loose
        val bad = runs(54_000.0 to road(), 6_000.0 to road("gravel", smoothness = "bad"))
        assertEquals("Moderate · unpaved", RouteSummary.rating(Activity.EBIKE, 60_000.0, 0.0, bad).text)
    }

    @Test fun unpavedLeavesGravelBikeAndMtbAlone() {
        val pts = runs(2_000.0 to road(), 8_000.0 to road("ground"))
        for (a in listOf(Activity.BIKE_TREKKING, Activity.MTB, Activity.EMTB))
            assertEquals(a.name, RouteSummary.Rating(Difficulty.EASY), RouteSummary.rating(a, 10_000.0, 20.0, pts))
    }

    @Test fun unpavedNeverLowersAndMtbGradeComesFirst() {
        // S2 on a Bike route that is also all unpaved: the grade is named
        val s2 = runs(1_000.0 to path(mtb = 2), 1_000.0 to road("ground"))
        assertEquals("Moderate · S2", RouteSummary.rating(Activity.BIKE_TOURING, 2_000.0, 10.0, s2).text)
        // Difficult from fitness stays Difficult, no reason
        val long = runs(100_000.0 to road("ground"))
        assertEquals(RouteSummary.Rating(Difficulty.DIFFICULT), RouteSummary.rating(Activity.BIKE_TOURING, 100_000.0, 0.0, long))
    }

    @Test fun unpavedWithoutWayAttributesIsUnknown() {
        // straight legs / imported tracks have no surfaces: nothing raised; a partly known line counts the known
        // unpaved metres against the whole length (3 km gravel of 15 km = 20 %)
        assertEquals(RouteSummary.Rating(Difficulty.EASY),
            RouteSummary.rating(Activity.BIKE_TOURING, 10_000.0, 0.0, runs(10_000.0 to null)))
        val part = runs(12_000.0 to null, 3_000.0 to road("gravel"))
        assertEquals(Difficulty.EASY, RouteSummary.rating(Activity.BIKE_TOURING, 15_000.0, 0.0, part).level)
    }

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
        // paved, so only the grade counts (an all-unpaved line raises Bike / E-bike on its own, see bikeUnpaved*)
        val s1 = line(10) { path(mtb = 1, surface = "paved") }
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
