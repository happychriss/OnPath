package com.onpath.app.time

import com.onpath.app.data.AppSettings
import com.onpath.app.data.Geo
import com.onpath.app.data.MapPrefs
import com.onpath.app.data.Prefs
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Settings pace: five levels about 15 % apart around DIN 33466 (owner 2026-10-22), migration of the old three. */
class PaceLevelsTest {
    private val dir = File(System.getProperty("java.io.tmpdir"))

    @Test fun fiveLevelsAround15PercentApart() {
        assertEquals(listOf("Relaxed", "Easy", "Average", "Fit", "Very fit"), Pace.entries.map { it.label })
        assertEquals(listOf(0.75, 0.875, 1.0, 1.15, 1.3), Pace.entries.map { it.speed })
        assertEquals(Pace.AVERAGE, Pace.DEFAULT)
        assertEquals(Pace.AVERAGE, AppSettings().hikePace)
        assertEquals(Pace.AVERAGE, AppSettings().bikePace)
        assertEquals(1.0, Pace.AVERAGE.factor, 0.0)
        Pace.entries.forEach { assertEquals(1.0, it.factor * it.speed, 1e-12) }
        Pace.entries.zipWithNext().forEach { (a, b) ->
            val step = b.speed / a.speed
            assertTrue("${a.label} → ${b.label}: $step", step in 1.12..1.18)
            assertTrue(b.factor < a.factor)
        }
    }

    @Test fun din33466KnownCases() {
        // 12 km flat: 3 h
        assertEquals(3 * 3600.0, Din33466.seconds(12_000.0, 0.0, 0.0), 1e-6)
        // 900 m up only: 3 h
        assertEquals(3 * 3600.0, Din33466.seconds(0.0, 900.0, 0.0), 1e-6)
        // 8 km + 900 m up: h = 2, v = 3 → 3 + 2/2 = 4 h
        assertEquals(4 * 3600.0, Din33466.seconds(8_000.0, 900.0, 0.0), 1e-6)
        // 10 km, 600 up, 600 down: h = 2.5, v = 2 + 1.2 = 3.2 → 3.2 + 1.25 = 4.45 h
        assertEquals(4.45 * 3600, Din33466.seconds(10_000.0, 600.0, 600.0), 1e-6)
        // 16 km, 300 up, 500 down: h = 4, v = 2 → 4 + 1 = 5 h
        assertEquals(5 * 3600.0, Din33466.seconds(16_000.0, 300.0, 500.0), 1e-6)
        // the quick estimate for straight lines / imported tracks is this rule
        assertEquals(Din33466.seconds(10_000.0, 600.0, 600.0), Geo.estimateSeconds(10_000.0, 600.0, 600.0, Activity.HIKE), 0.0)
        // the per-segment model has the same flat speed (4 km/h = 15 min per km)
        assertEquals(15.0, PaceModel.hikeMinPerKm(0.0), 1e-9)
    }

    @Test fun meaningForHiking() {
        assertEquals("about 3 km/h · 230 m up per hour", Pace.RELAXED.meaning(Activity.HIKE))
        assertEquals("about 3.5 km/h · 260 m up per hour", Pace.EASY.meaning(Activity.HIKE))
        assertEquals("about 4 km/h · 300 m up per hour", Pace.AVERAGE.meaning(Activity.HIKE))
        assertEquals("about 4.6 km/h · 350 m up per hour", Pace.FIT.meaning(Activity.HIKE))
        assertEquals("about 5.2 km/h · 390 m up per hour", Pace.VERY_FIT.meaning(Activity.HIKE))
    }

    @Test fun meaningForBikesComesFromTheBikeModel() {
        for (a in Activity.entries.filter { it != Activity.HIKE }) {
            val flat = PaceModel.bikeSpeedMs(0.0, a) * 3.6
            val texts = Pace.entries.map { it.meaning(a) }
            println("$a: $texts")
            assertTrue(texts[2], texts[2].startsWith("about ${Math.round(flat)} km/h · "))
            assertEquals(5, texts.distinct().size) // e-bikes: same flat speed from Average up, but climbs differ
        }
        // e-bikes climb faster than the same bike without motor
        fun up(a: Activity) = Pace.AVERAGE.meaning(a).substringAfter("· ").substringBefore(" m").toInt()
        assertTrue(up(Activity.EBIKE) > up(Activity.BIKE_TREKKING))
    }

    @Test fun oldThreeLevelsMapOntoTheFive() {
        assertEquals(Pace.RELAXED, Pace.fromOld("RELAXED")) // ×1.25 time ≈ Relaxed (×1.33)
        assertEquals(Pace.AVERAGE, Pace.fromOld("NORMAL"))
        assertEquals(Pace.VERY_FIT, Pace.fromOld("FIT"))     // ×0.8 time ≈ Very fit (×0.77)
        assertEquals(Pace.AVERAGE, Pace.fromOld(null))
        assertEquals(Pace.AVERAGE, Pace.fromOld("junk"))
        assertEquals(Pace.AVERAGE, Pace.of("NORMAL")) // old fixtures / files naming the middle level
        // the nearest new level to each old factor
        mapOf("RELAXED" to 1.25, "FIT" to 0.8, "NORMAL" to 1.0).forEach { (old, f) ->
            val nearest = Pace.entries.minBy { kotlin.math.abs(kotlin.math.ln(it.factor / f)) }
            assertEquals(old, nearest, Pace.fromOld(old))
        }
    }

    @Test fun storedOldValuesAreMigratedAndNewValuesWin() {
        val old = MapPrefs(mutableMapOf("hikePace" to "FIT", "bikePace" to "RELAXED"))
        val s = Prefs(old, dir).settings
        assertEquals(Pace.VERY_FIT, s.hikePace)
        assertEquals(Pace.RELAXED, s.bikePace)
        // saving writes the new keys; the old "FIT" is not read as the new Fit any more
        Prefs(old, dir).settings = s.copy(bikePace = Pace.FIT)
        val again = Prefs(old, dir).settings
        assertEquals(Pace.VERY_FIT, again.hikePace)
        assertEquals(Pace.FIT, again.bikePace)
        // fresh install: Average
        assertEquals(Pace.AVERAGE, Prefs(MapPrefs(), dir).settings.hikePace)
        // a new key with the old middle name or junk: Average
        assertEquals(Pace.AVERAGE, Prefs(MapPrefs(mutableMapOf("hikePaceLevel" to "NORMAL")), dir).settings.hikePace)
    }

    @Test fun planFactorsFollowTheLevel() {
        for (p in Pace.entries) {
            assertEquals(Factors.ONE * p.factor, TimeEstimate.planFactors(Activity.HIKE, AppSettings(hikePace = p)))
            assertEquals(Factors.ONE * p.factor, TimeEstimate.planFactors(Activity.MTB, AppSettings(bikePace = p)))
            assertEquals(p.factors(Activity.EBIKE), TimeEstimate.planFactors(Activity.EBIKE, AppSettings(bikePace = p)))
        }
    }

    /** Owner 2026-10-22: the motor stops at 25 km/h – e-bikes are no faster on the flat at any level, but climb faster. */
    @Test fun eBikesStayAtAbout25KmhOnTheFlat() {
        for (a in listOf(Activity.EBIKE, Activity.EMTB)) {
            val flat = PaceModel.bikeSpeedMs(0.0, a) * 3.6
            for (p in Pace.entries) {
                val f = TimeEstimate.planFactors(a, AppSettings(bikePace = p))
                assertTrue("$a $p", flat / f.flat <= Pace.E_FLAT_CAP_KMH + 1e-9)
                assertEquals(p.factor, f.up, 1e-12)
                assertEquals(p.factor, f.down, 1e-12)
                val kmh = p.meaning(a).substringAfter("about ").substringBefore(" km/h").toDouble()
                assertTrue("$a $p: ${p.meaning(a)}", kmh <= 25.0)
            }
            // slower levels stay slower on the flat; Fit / Very fit = the capped speed, still faster uphill
            assertTrue(TimeEstimate.planFactors(a, AppSettings(bikePace = Pace.RELAXED)).flat > 1.0)
            assertEquals("about 25 km/h", Pace.VERY_FIT.meaning(a).substringBefore(" ·"))
            fun up(p: Pace) = p.meaning(a).substringAfter("· ").substringBefore(" m").toInt()
            assertTrue(up(Pace.VERY_FIT) > up(Pace.FIT) && up(Pace.FIT) > up(Pace.AVERAGE))
        }
        // other bikes are not capped
        assertEquals(Factors.ONE * Pace.VERY_FIT.factor, TimeEstimate.planFactors(Activity.BIKE_TOURING, AppSettings(bikePace = Pace.VERY_FIT)))
    }
}
