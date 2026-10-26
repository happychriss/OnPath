package com.onpath.app.time

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.WayAttrs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Time estimate P4: way attributes (SAC, surface, smoothness, MTB scale, steps) per span. */
class WayTimesTest {
    private fun path(sac: Int, vis: String? = null, rough: String? = null) =
        WayAttrs("path", if (sac >= 4) "alpine" else "natural_trail", sac = sac, visibility = vis, rough = rough)

    @Test fun hikingFactors() {
        assertEquals(1.0, PaceModel.hikeWayFactor(null), 0.0)
        assertEquals(1.0, PaceModel.hikeWayFactor(WayAttrs("quiet_road", "asphalt")), 0.0)
        assertEquals(1.0, PaceModel.hikeWayFactor(path(1)), 0.0)
        assertEquals(1.0, PaceModel.hikeWayFactor(path(2)), 0.0)
        assertEquals(1.15, PaceModel.hikeWayFactor(path(3)), 1e-9)
        assertEquals(1.35, PaceModel.hikeWayFactor(path(4)), 1e-9)
        assertEquals(1.6, PaceModel.hikeWayFactor(path(5)), 1e-9)
        assertEquals(1.67, PaceModel.hikeWayFactor(path(6)), 1e-9)
        // rough ground and steps: at least the T3 factor; faint trails on top, capped
        assertEquals(1.15, PaceModel.hikeWayFactor(path(0, rough = "scree")), 1e-9)
        assertEquals(1.15, PaceModel.hikeWayFactor(WayAttrs("steps", "paved")), 1e-9)
        assertEquals(1.1 * 1.15, PaceModel.hikeWayFactor(path(3, vis = "bad")), 1e-9)
        assertEquals(1.8, PaceModel.hikeWayFactor(path(6, vis = "no")), 1e-9)
        // seconds: the factor multiplies table B
        assertEquals(900.0 * 1.35, PaceModel.seconds(1000.0, 0.0, Activity.HIKE, path(4)), 0.01)
    }

    @Test fun bikeWithoutWayIsTheOldModel() {
        for (a in Activity.entries.filter { it != Activity.HIKE }) for (g in listOf(-0.1, 0.0, 0.06)) {
            assertEquals(PaceModel.bikeSpeedMs(g, a), PaceModel.bikeSpeedOn(g, a, null), 1e-12)
            // the way's surface is the same as passing the surface key
            assertEquals(PaceModel.bikeSpeedMs(g, a, "gravel"), PaceModel.bikeSpeedOn(g, a, WayAttrs("track", "gravel")), 1e-12)
        }
        // unknown surface key: the activity's usual surface
        assertEquals(PaceModel.bikeSpeedMs(0.0, Activity.MTB), PaceModel.bikeSpeedOn(0.0, Activity.MTB, WayAttrs("path", "unknown")), 1e-12)
    }

    @Test fun bikeSmoothnessMtbScaleAndPushing() {
        fun kmh(a: Activity, g: Double, w: WayAttrs) = PaceModel.bikeSpeedOn(g, a, w) * 3.6
        val track = WayAttrs("track", "gravel")
        // downhill caps get lower with bad smoothness / higher MTB grades
        assertTrue(kmh(Activity.BIKE_TREKKING, -0.08, track.copy(smoothness = "bad")) <= 15.0 + 1e-9)
        assertTrue(kmh(Activity.MTB, -0.1, WayAttrs("path", "natural_trail", mtb = 2)) <= 10.0 + 1e-9)
        assertTrue(kmh(Activity.MTB, -0.1, WayAttrs("path", "natural_trail", mtb = 3)) <= 6.0 + 1e-9)
        assertTrue(kmh(Activity.MTB, 0.0, WayAttrs("path", "natural_trail", mtb = 1)) >
            kmh(Activity.MTB, 0.0, WayAttrs("path", "natural_trail", mtb = 2)))
        // pushed: S4+ on an MTB, S2+ / T3+ / horrible on other bikes = walking × 0.85; steps carried at half that
        val push = PaceModel.pushSpeedMs(0.1) * 3.6
        assertEquals(push, kmh(Activity.MTB, 0.1, WayAttrs("path", "alpine", mtb = 4)), 1e-9)
        assertEquals(push, kmh(Activity.MTB, 0.1, WayAttrs("path", "alpine", sac = 4)), 1e-9)
        assertEquals(push, kmh(Activity.BIKE_TREKKING, 0.1, WayAttrs("path", "natural_trail", sac = 3)), 1e-9)
        assertEquals(push, kmh(Activity.EBIKE, 0.1, track.copy(smoothness = "horrible")), 1e-9)
        assertEquals(push * 0.5, kmh(Activity.BIKE_TOURING, 0.1, WayAttrs("steps", "paved")), 1e-9)
        // an MTB still rides a T3 path (as S2), downhill capped at 10 km/h
        assertTrue(kmh(Activity.MTB, -0.1, WayAttrs("path", "natural_trail", sac = 3)) in 5.0..10.0 + 1e-9)
        // the real surface counts: asphalt is faster than the trekking bike's usual "paved"
        assertTrue(kmh(Activity.BIKE_TREKKING, 0.0, WayAttrs("road", "asphalt")) > PaceModel.bikeSpeedMs(0.0, Activity.BIKE_TREKKING) * 3.6)
    }

    /** 2 km north, flat; second km on [w]. */
    private fun line(w: WayAttrs?, first: WayAttrs? = WayAttrs("quiet_road", "asphalt")) = (0..40).map { i ->
        GeoPoint(46.0 + i * 0.00045, 11.0, 500.0, if (i < 20) first else w)
    }

    @Test fun routeTimesUseTheWayPerSpan() {
        val plain = RouteTimes.of(line(null, null), Activity.HIKE)
        assertTrue(!plain.hasWays)
        val easy = RouteTimes.of(line(path(1)), Activity.HIKE)
        assertEquals(plain.total(), easy.total(), 1e-6) // T1 + road = as before
        val t4 = RouteTimes.of(line(path(4)), Activity.HIKE)
        assertEquals(plain.total() * (1 + 1.35) / 2, t4.total(), plain.total() * 0.01)
        // remaining time from half way = the T4 km
        assertEquals(plain.total() / 2 * 1.35, t4.remainingFrom(t4.lengthM / 2), plain.total() * 0.02)
        // the lines compare equal (way is ignored by equals) – nothing else in the app changes
        assertEquals(line(null, null), line(path(4)))
    }

    @Test fun breakdownHasWayRows() {
        val r = TimeBreakdown.of(line(path(4)), Activity.HIKE, Factors.ONE)
        assertEquals(listOf("Road", "Trail T4").sorted(), r.wayRows.map { it.label }.sorted())
        val t4 = r.wayRows.first { it.label == "Trail T4" }
        assertEquals(1.35, t4.factor, 0.02)
        assertEquals(r.baseS, r.wayRows.sumOf { it.wayS }, 1.0)
        assertEquals(r.slopeOnlyS, r.wayRows.sumOf { it.slopeS }, 1.0)
        assertTrue(r.baseS > r.slopeOnlyS)
        // no way attributes: no second table
        assertTrue(TimeBreakdown.of(line(null, null), Activity.HIKE, Factors.ONE).wayRows.isEmpty())
        // bikes: by surface, pushing named
        assertEquals("Pushed (S5)", TimeBreakdown.wayLabel(WayAttrs("path", "alpine", mtb = 5), Activity.MTB))
        assertEquals("Gravel", TimeBreakdown.wayLabel(WayAttrs("track", "gravel"), Activity.BIKE_TREKKING))
    }

    @Test fun joinOfLegsKeepsTheNextLegsWay() {
        val a = GeoPoint(46.0, 11.0, 1.0, WayAttrs("road", "asphalt"))
        val b1 = GeoPoint(46.001, 11.0, 1.0, WayAttrs("road", "asphalt"))
        val b2 = GeoPoint(46.001, 11.0, 1.0, path(3))
        val c = GeoPoint(46.002, 11.0, 1.0, path(3))
        val r = PlannedRoute(listOf(PlannedLeg(listOf(a, b1), false), PlannedLeg(listOf(b2, c), false)), 0.0, 0.0, 0.0, 0.0, null)
        assertEquals(3, r.points.size)
        assertEquals(3, r.points[1].way?.sac)
    }

    @Test fun codeRoundTrip() {
        val w = WayAttrs("path", "natural_trail", 3, 2, "bad", "intermediate", "rock")
        assertEquals(w, WayAttrs.parse(w.code()))
        assertEquals(WayAttrs("track", "gravel"), WayAttrs.parse(WayAttrs("track", "gravel").code()))
        assertEquals(null, WayAttrs.parse("garbage"))
        // bike access (2026-10-22): round trip, and older 7-field codes read as unknown
        val bike = w.copy(bicycle = "dismount")
        assertEquals("dismount", WayAttrs.parse(bike.code())?.bicycle)
        assertEquals(null, WayAttrs.parse("path|natural_trail|3|-1||bad|")?.bicycle)
        assertEquals(null, WayAttrs.parse(null))
        assertNotEquals(w, WayAttrs.parse(w.copy(sac = 4).code()))
        // cycle route (2026-10-25): round trip, and older 8-field codes read as "not on a route"
        assertEquals(true, WayAttrs.parse(bike.copy(cycleRoute = true).code())?.cycleRoute)
        assertEquals(false, WayAttrs.parse(bike.code())?.cycleRoute)
        assertEquals(false, WayAttrs.parse("footway|paved|0|-1||||")?.cycleRoute)
        // pedestrian zone (2026-10-26): a flag beside "r"; codes with only "r" read as no pedestrian zone
        val zone = bike.copy(cycleRoute = true, pedestrian = true)
        assertEquals(zone, WayAttrs.parse(zone.code()))
        assertEquals(true, WayAttrs.parse(bike.copy(pedestrian = true).code())?.pedestrian)
        assertEquals(false, WayAttrs.parse(bike.copy(pedestrian = true).code())?.cycleRoute)
        assertEquals(false, WayAttrs.parse("footway|paved|0|-1|||||r")?.pedestrian)
        // route hints (2026-10-26): ford / construction flags and the barrier field; older codes read as none
        val hints = zone.copy(ford = true, construction = true, barrier = "stile")
        assertEquals(hints, WayAttrs.parse(hints.code()))
        assertEquals(WayAttrs("track", "gravel", ford = true), WayAttrs.parse(WayAttrs("track", "gravel", ford = true).code()))
        val old = WayAttrs.parse("footway|paved|0|-1|||||rp")!!
        assertEquals(false, old.ford); assertEquals(false, old.construction); assertEquals(null, old.barrier)
    }
}
