package com.onpath.app.time

import com.onpath.app.data.FollowProgress
import com.onpath.app.gpx.GpxParser
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference routes of docs/plan-time-estimate.md §2.2 (real BRouter routes of the Bozen / Meran data with their DEM
 * elevation, exported by tools/routing/jvm-test/ExportTimeRoutes.kt into src/test/resources/time/routes/).
 * Guards the model ("table B" column of the doc) and prints old vs. new times.
 */
class ReferenceRoutesTest {

    class Ref(val id: String, val name: String, val activity: Activity, val points: List<GeoPoint>, val brouterS: Double,
              val ascentM: Double, val descentM: Double, val distanceM: Double)

    companion object {
        val IDS = listOf("H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8", "H8r", "H9", "B1", "B2", "B3")

        fun load(id: String): Ref {
            val text = ReferenceRoutesTest::class.java.getResourceAsStream("/time/routes/$id.gpx")!!.use { it.readBytes() }
            val g = GpxParser.parse(text)
            val desc = String(text).substringAfter("<desc>").substringBefore("</desc>")
            val kv = desc.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
            return Ref(id, g.name ?: id, Activity.valueOf(kv.getValue("activity")), g.allSegments.flatten().map { it.toGeo() },
                kv.getValue("brouterS").toDouble(), kv.getValue("ascentM").toDouble(), kv.getValue("descentM").toDouble(),
                kv.getValue("distanceM").toDouble())
        }

        fun hm(s: Double) = "%d:%02d".format((s / 3600).toInt(), ((s % 3600) / 60).toInt())
    }

    /** "table B" column of the doc (h:mm, rounded down to the minute there). */
    private val expected = mapOf("H1" to "2:03", "H2" to "2:05", "H3" to "2:51", "H4" to "1:57", "H5" to "4:48",
        "H6" to "0:40", "H7" to "4:29", "H8" to "2:20", "H8r" to "1:25", "H9" to "2:57")

    @Test fun hikingRoutesMatchTheDesign() {
        println("route                                     km   BRouter(old plan)  DIN(old to go)  new model")
        for (id in IDS) {
            val r = load(id)
            val t = RouteTimes.of(r.points, r.activity)
            // DIN 33466 on the whole route (the old hiking "to go"), for comparison
            val din = Din33466.seconds(r.distanceM, r.ascentM, r.descentM)
            println("%-40s %5.1f   %8s          %8s        %8s".format(r.name, r.distanceM / 1000, hm(r.brouterS), hm(din), hm(t.total())))
            expected[id]?.let { e ->
                val (h, m) = e.split(':').map { it.toInt() }
                assertEquals("$id table B", (h * 60 + m) * 60.0 + 30, t.total(), 120.0)
            }
            assertEquals(r.distanceM, t.lengthM, r.distanceM * 0.02)
        }
    }

    @Test fun planSheetEqualsNavigateAtTheStartAndAlongTheWay() {
        for (id in IDS) {
            val r = load(id)
            val f = Factors(0.9, 1.0, 1.1) * Pace.RELAXED.factor
            val plan = TimeEstimate.planSeconds(r.points, r.activity, f)
            val t = RouteTimes.of(r.points, r.activity)
            // Navigate at the start: FollowProgress at the first point -> remaining distance = whole line
            val rem = FollowProgress(r.points).remaining(r.points.first())!!
            assertEquals(id, plan, TimeEstimate.round(t.remainingFrom(t.lengthM - rem.distanceM, f)), 60.0)
            // half way: remaining + what was passed = whole
            val half = t.lengthM / 2
            assertEquals(t.total(f), t.remainingFrom(half, f) + (0 until 3).sumOf { g -> f[g] * t.between(0.0, half)[g] }, 1e-6)
        }
    }

    @Test fun climbsTakeLongerThanDescents() {
        val up = RouteTimes.of(load("H8").points, Activity.HIKE).total()
        val down = RouteTimes.of(load("H8r").points, Activity.HIKE).total()
        assertTrue(up > down * 1.4)
        // the mountain routes take longer than BRouter's Tobler times, but less than DIN on the whole route
        for (id in listOf("H3", "H5", "H8", "H9")) {
            val r = load(id)
            val t = RouteTimes.of(r.points, r.activity).total()
            assertTrue(id, t > r.brouterS * 1.25)
            assertTrue(id, t < Din33466.seconds(r.distanceM, r.ascentM, r.descentM))
        }
    }

    @Test fun bikesArePlausible() {
        for (id in listOf("B1", "B2", "B3")) {
            val r = load(id)
            val t = RouteTimes.of(r.points, r.activity).total()
            val kmh = r.distanceM / t * 3.6
            println("$id ${r.activity}: ${hm(t)} (%.1f km/h), BRouter ${hm(r.brouterS)}".format(kmh))
            assertTrue("$id $kmh", kmh in 3.5..25.0)
        }
        val b1 = load("B1")
        assertTrue(RouteTimes.of(b1.points, Activity.EBIKE).total() < RouteTimes.of(b1.points, Activity.BIKE_TREKKING).total())
    }
}
