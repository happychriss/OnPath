package com.onpath.app.time

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceModelTest {

    private fun kmh(grade: Double) = 60 / PaceModel.hikeMinPerKm(grade)

    @Test fun hikingTableAnchors() {
        assertEquals(4.0, kmh(0.0), 0.01)                     // 4 km/h on the flat (DIN / AVS)
        assertEquals(13.5, PaceModel.hikeMinPerKm(-0.07), 0.01) // gentle descents a bit faster than the flat
        assertTrue(kmh(-0.07) > kmh(0.0))
        assertEquals(37.0, PaceModel.hikeMinPerKm(0.20), 0.01)
        assertEquals(27.0, PaceModel.hikeMinPerKm(-0.25), 0.01) // linear in between (22 … 32)
        // vertical speeds on steady slopes: ~300–340 m/h up, ~500–550 m/h down
        val up20 = 200.0 * 60 / PaceModel.hikeMinPerKm(0.20)
        val up30 = 300.0 * 60 / PaceModel.hikeMinPerKm(0.30)
        val down20 = 200.0 * 60 / PaceModel.hikeMinPerKm(-0.20)
        assertTrue(up20 in 300.0..345.0); assertTrue(up30 in 300.0..345.0)
        assertTrue(down20 in 500.0..560.0)
        // very steep descents get slower again (horizontal)
        assertTrue(kmh(-0.40) < kmh(-0.20))
        // beyond the table: still growing, never zero or negative
        assertTrue(PaceModel.hikeMinPerKm(0.8) > PaceModel.hikeMinPerKm(0.5))
        assertTrue(PaceModel.hikeMinPerKm(-0.8) > PaceModel.hikeMinPerKm(-0.5))
        assertEquals(15.0, PaceModel.hikeMinPerKm(Double.NaN), 0.0)
    }

    @Test fun groups() {
        assertEquals(PaceModel.DOWN, PaceModel.group(-0.06))
        assertEquals(PaceModel.FLAT, PaceModel.group(-0.05))
        assertEquals(PaceModel.FLAT, PaceModel.group(0.05))
        assertEquals(PaceModel.UP, PaceModel.group(0.051))
    }

    @Test fun segmentSeconds() {
        assertEquals(900.0, PaceModel.seconds(1000.0, 0.0, Activity.HIKE), 0.01)
        assertEquals(0.0, PaceModel.seconds(0.0, 5.0, Activity.HIKE), 0.0)
        assertEquals(900.0, PaceModel.seconds(1000.0, Double.NaN, Activity.HIKE), 0.01)
    }

    @Test fun bikeSpeedsArePlausible() {
        fun kmh(a: Activity, g: Double, s: String? = null) = PaceModel.bikeSpeedMs(g, a, s) * 3.6
        // flat: trekking ~18–22 km/h, touring the same rider on its usual asphalt a bit faster (~21–22), MTB on unpaved slower
        assertTrue(kmh(Activity.BIKE_TREKKING, 0.0) in 17.0..23.0)
        assertTrue(kmh(Activity.BIKE_TOURING, 0.0) > kmh(Activity.BIKE_TREKKING, 0.0))
        assertTrue(kmh(Activity.BIKE_TOURING, 0.0) in 20.0..22.5) // a normal bike, not a road bike (~24)
        assertEquals(kmh(Activity.BIKE_TREKKING, 0.0, "asphalt"), kmh(Activity.BIKE_TOURING, 0.0, "asphalt"), 1e-9)
        assertTrue(kmh(Activity.MTB, 0.0) < kmh(Activity.BIKE_TREKKING, 0.0))
        // climbing 8 %: slow without motor, e-bike holds ~15+ km/h
        assertTrue(kmh(Activity.BIKE_TREKKING, 0.08) in 3.5..8.0)
        assertTrue(kmh(Activity.EBIKE, 0.08) > 10.0)
        // e-bike never assisted above 25 km/h on the flat
        assertTrue(kmh(Activity.EBIKE, 0.0) <= 25.01)
        // steep climb: never below pushing speed
        assertTrue(kmh(Activity.MTB, 0.30) >= PaceModel.pushSpeedMs(0.30) * 3.6 - 1e-9)
        assertTrue(kmh(Activity.MTB, 0.30) < 2.5) // ~1.1 km/h walking at 30 %: no "riding" 700 m/h up
        // downhill: capped by surface
        assertEquals(40.0, kmh(Activity.BIKE_TOURING, -0.10, "asphalt"), 0.01)
        assertEquals(15.0, kmh(Activity.MTB, -0.10, "natural_trail"), 0.01)
        // rougher surface = slower on the flat
        assertTrue(kmh(Activity.BIKE_TREKKING, 0.0, "gravel") < kmh(Activity.BIKE_TREKKING, 0.0, "asphalt"))
        // unknown surface key: the activity's usual surface
        assertEquals(kmh(Activity.BIKE_TREKKING, 0.0), kmh(Activity.BIKE_TREKKING, 0.0, "whatever"), 1e-9)
    }

    @Test fun paceAndFactors() {
        assertEquals(1 / 0.75, Pace.RELAXED.factor, 1e-12)
        assertEquals(1 / 1.15, Pace.FIT.factor, 1e-12)
        assertEquals(Pace.AVERAGE, Pace.of(null))
        assertEquals(Pace.AVERAGE, Pace.of("nonsense"))
        assertEquals(Pace.FIT, Pace.of("FIT"))
        val f = Factors(0.9, 1.0, 1.2) * 1.25
        assertEquals(1.5, f.up, 1e-9)
        assertEquals(1.2, f[PaceModel.UP] / 1.25, 1e-9)
    }

    @Test fun roundingAndClock() {
        assertEquals(0.0, TimeEstimate.round(-5.0), 0.0)
        assertEquals(13 * 60.0, TimeEstimate.round(13 * 60 + 20.0), 0.0)
        assertEquals(190 * 60.0, TimeEstimate.round(188 * 60.0), 0.0)
        assertEquals("1:12", TimeEstimate.hm(72 * 60 + 30.0))
        assertEquals("0:00", TimeEstimate.hm(-3.0))
        val now = 1_000_000_000L
        assertEquals(now + 1_100_000L, TimeEstimate.arrivalMs(now, 1000.0))
    }

    @Test fun routeTimesOnALine() {
        // 10 km east, first 5 km climbing 500 m (10 %), then 5 km flat
        val pts = (0..100).map { i -> GeoPoint(46.67, 11.10 + i * 0.0013, if (i <= 50) 500.0 + i * 10.0 else 1000.0) }
        val t = RouteTimes.of(pts, Activity.HIKE)
        val g = t.groupTotals()
        assertEquals(t.total(), g.sum(), 1e-6)
        assertEquals(t.total(), t.remainingFrom(0.0), 1e-6)
        assertEquals(0.0, t.remainingFrom(t.lengthM), 1e-6)
        assertEquals(0.0, t.remainingFrom(t.lengthM + 500), 1e-6)
        assertEquals(t.total(), t.remainingFrom(-50.0), 1e-6)
        // the climb is slower than the flat half, and it is in group "up"
        assertTrue(g[PaceModel.UP] > g[PaceModel.FLAT])
        assertEquals(PaceModel.UP, t.groupAt(1000.0))
        assertEquals(PaceModel.FLAT, t.groupAt(t.lengthM - 1000))
        // remaining falls steadily
        var last = Double.MAX_VALUE
        for (d in 0..t.lengthM.toInt() step 37) { val r = t.remainingFrom(d.toDouble()); assertTrue(r <= last + 1e-9); last = r }
        // between() and remainingFrom() agree
        val mid = t.lengthM / 3
        assertEquals(t.total() - t.remainingFrom(mid), t.between(0.0, mid).sum(), 1e-6)
        // factors per group
        val f = Factors(1.0, 2.0, 0.5)
        assertEquals(g[0] + 2 * g[1] + 0.5 * g[2], t.total(f), 1e-6)
    }

    @Test fun routeTimesRobust() {
        assertEquals(0.0, RouteTimes.of(emptyList(), Activity.HIKE).total(), 0.0)
        assertEquals(0.0, RouteTimes.of(listOf(GeoPoint(46.0, 11.0)), Activity.HIKE).remainingFrom(10.0), 0.0)
        assertEquals(0.0, RouteTimes.of(listOf(GeoPoint(46.0, 11.0), GeoPoint(46.0, 11.0)), Activity.HIKE).total(), 0.0)
        // no elevation at all = flat
        val flat = RouteTimes.of(listOf(GeoPoint(46.0, 11.0), GeoPoint(46.0, 11.013)), Activity.HIKE)
        assertEquals(flat.lengthM / 1000 * 900, flat.total(), 1.0)
        // short straight leg (2 points) still gets a time
        val short = RouteTimes.of(listOf(GeoPoint(46.0, 11.0), GeoPoint(46.0, 11.0002)), Activity.HIKE)
        assertTrue(short.total() > 0)
    }
}
