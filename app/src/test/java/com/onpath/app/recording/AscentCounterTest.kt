package com.onpath.app.recording

import com.onpath.app.data.Geo
import com.onpath.app.gpx.GpxParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Ascent of a recording from the terrain model, GPS altitude only where there is no terrain (core feature: recording;
 * owner 2026-10-30). The comparison with real tracks + real terrain tiles: [AscentCompareTest].
 */
class AscentCounterTest {
    private fun run(c: AscentCounter, pts: List<Triple<Double?, Double?, Double>>): Pair<Double, Double> {
        var up = 0.0; var down = 0.0
        for ((dem, gps, moved) in pts) { val (u, d) = c.add(dem, gps, moved); up += u; down += d }
        val (u, d) = c.flush(); up += u; down += d
        return up to down
    }

    /** Deterministic noise in [-1, 1]. */
    private fun noise(i: Int, seed: Int) = sin(i * 12.9898 + seed * 78.233).let { (it * 43758.5453) % 1.0 }

    @Test fun withoutTerrainItCountsGpsAltitudeAsBefore() {
        // the owner's tours: GPS altitude only (no terrain) = exactly the old 5 m hysteresis of the recording
        for (f in listOf("owner-2026-10-19-tour3/track.gpx", "owner-2026-10-19-tour4/track.gpx", "owner-2026-10-22-taser/verdins-schenna.gpx")) {
            val pts = GpxParser.parse(javaClass.classLoader!!.getResource("tracks/$f")!!.readText()).allSegments.flatten()
            val old = Geo.climb(pts.map { it.ele }, 5.0)
            val new = run(AscentCounter(), pts.map { Triple(null, it.ele, 4.0) })
            assertEquals(f, old.first, new.first, 1e-9)
            assertEquals(f, old.second, new.second, 1e-9)
        }
    }

    /**
     * A synthetic Meran ramp: 3 km steadily up 300 m (10 %), a point every 3 m. Terrain under the walked points with
     * ±3 m noise (GPS position error sideways on the slope); GPS altitude wandering ±15 m (no barometer).
     */
    @Test fun syntheticRampTerrainCountsTheRealClimb() {
        val n = 1000
        val pts = (0..n).map { i ->
            val ground = 300.0 + 300.0 * i / n
            val dem = ground + 3.0 * noise(i, 1)
            val gps = ground + 50.0 + 15.0 * sin(i * 2 * PI / 37.0) + 4.0 * noise(i, 2)   // geoid offset + wander
            Triple(dem, gps, 3.0)
        }
        val (up, down) = run(AscentCounter(), pts)
        assertTrue("DEM up $up", abs(up - 300.0) <= 10.0)
        assertTrue("DEM down $down", down <= 5.0)
        val (gUp, gDown) = run(AscentCounter(), pts.map { Triple(null, it.second, 3.0) })
        println("Synthetic Meran ramp (300 m up, 3 km): DEM ${up.toInt()} / ${down.toInt()} m, GPS 5 m hysteresis ${gUp.toInt()} / ${gDown.toInt()} m")
        assertTrue("GPS wander counts as climbing: $gUp", gUp > 400)
    }

    @Test fun terrainNoiseOnAFlatPathIsNoClimb() {
        // flat valley path, terrain ±4 m from GPS jitter on a steep slope beside it
        val pts = (0..2000).map { i -> Triple(500.0 + 4.0 * noise(i, 3), 520.0, 2.0) }
        val (up, down) = run(AscentCounter(), pts)
        // 4 km: a few metres at most (the GPS altitude of a phone without barometer: tens)
        assertTrue("up $up down $down", up <= 10.0 && down <= 10.0)
    }

    @Test fun switchBetweenTerrainAndGpsIsNoClimb() {
        val c = AscentCounter()
        val pts = buildList {
            repeat(50) { add(Triple(1000.0, 1050.0, 5.0)) }          // terrain
            repeat(50) { add(Triple(null, 1050.0, 5.0)) }            // left the downloaded map: GPS 50 m higher
            repeat(50) { add(Triple(1000.0, 1050.0, 5.0)) }          // back on the map
        }
        assertEquals(0.0 to 0.0, run(c, pts))
    }

    @Test fun lastStretchCountsAtTheEnd() {
        val c = AscentCounter()
        c.add(1000.0, null, 0.0)
        repeat(3) { c.add(1010.0, null, 5.0) }                       // 15 m walked: window not full yet
        assertEquals(10.0 to 0.0, c.flush())
        assertEquals(0.0 to 0.0, c.flush())
    }

    @Test fun restartStartsANewReference() {
        val c = AscentCounter()
        c.add(null, 200.0, 0.0)
        c.restart()                                                   // demo location started far away
        assertEquals(0.0 to 0.0, c.add(null, 1500.0, 5.0))
        assertEquals(10.0 to 0.0, c.add(null, 1510.0, 5.0))
    }

    @Test fun noHeightsNothingCounted() {
        assertEquals(0.0 to 0.0, run(AscentCounter(), List(20) { Triple(null, null, 5.0) }))
        assertEquals(0.0 to 0.0, run(AscentCounter(), List(20) { Triple(Double.NaN, Double.NaN, 5.0) }))
    }
}
