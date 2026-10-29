package com.onpath.app.rides

import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CORE FEATURE C4 (never lose a track) – bus / car / lift detection (docs/design/vehicle-detection.md):
 * real-like traces run through the recording's own filter, then the detector.
 */
class RideDetectorTest {

    private fun detect(b: TraceBuilder, a: Activity): Pair<Recorded, List<Ride>> {
        val rec = record(b.fixes)
        return rec to RideDetector.detect(rec.segments, a)
    }

    private fun Recorded.time(i: Int) = points[i].timeMs!!

    @Test fun busRideAtTheEndOfAHike() {
        val b = TraceBuilder()
            .walk(40.0).mark("atStop")
            .stand(6 * 60).mark("busStart")
            .bus(legs = 14).mark("busEnd")
            .walk(3.0, heading = 200.0)
        val (rec, rides) = detect(b, Activity.HIKE)
        assertEquals(1, rides.size)
        val r = rides[0]
        assertEquals(RideKind.VEHICLE, r.kind)
        // proposed end of the hike: arriving at the stop – the waiting is cut off with the ride
        val from = rec.time(r.from)
        assertTrue("from ${(from - b.marks["atStop"]!!) / 1000} s after arriving", from in b.marks["atStop"]!! - 60_000..b.marks["atStop"]!! + 10_000)
        val to = rec.time(r.to)
        assertTrue("to ${(to - b.marks["busEnd"]!!) / 1000} s after the bus", to in b.marks["busEnd"]!! - 90_000..b.marks["busEnd"]!! + 30_000)
        val s = RideView.summary(rec.segments, rides, onFoot = true, zone = java.time.ZoneOffset.UTC)!!
        assertEquals(RideChoice.UNTIL, s.preselected)
        val hhmm = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(from))
        assertTrue(hhmm == "08:39" || hhmm == "08:40")
        assertEquals("Save only until $hhmm", s.options.first { it.choice == RideChoice.UNTIL }.title)
    }

    @Test fun busWithoutGpsThenTheHikeGoesOn() {
        val b = TraceBuilder()
            .walk(30.0).stand(4 * 60)
            .gap(20 * 60, 14_000.0, 150.0, dEle = 300.0).mark("off")
            .walk(45.0, heading = 10.0)
        val (rec, rides) = detect(b, Activity.HIKE)
        assertEquals(1, rides.size)
        assertEquals(RideKind.VEHICLE, rides[0].kind)
        assertTrue(rec.time(rides[0].to) <= b.marks["off"]!! + 5_000)
        val s = RideView.summary(rec.segments, rides, onFoot = true)!!
        assertEquals(RideChoice.NOT_COUNTED, s.preselected)
        assertTrue(s.options.any { it.choice == RideChoice.SPLIT && it.title == "Split into two tracks" })
    }

    @Test fun carOnAPassRoadWhileHiking() {
        val b = TraceBuilder()
            .walk(25.0).stand(3 * 60)
            .passRoad(20 * 60, 45.0, 30.0, vertMs = 0.8)
            .walk(10.0)
        val (_, rides) = detect(b, Activity.HIKE)
        assertEquals(listOf(RideKind.VEHICLE), rides.map { it.kind })
    }

    @Test fun bikeLoadedIntoACarOnTheValleyRoad() {
        val b = TraceBuilder()
            .move(30 * 60, 20.0, 80.0, headingAt = { s -> 80 + 20 * kotlin.math.sin(s / 120.0) })
            .stand(5 * 60)
            .move(15 * 60, 75.0, 200.0, headingAt = { s -> 200 + 10 * kotlin.math.sin(s / 100.0) })
            .stand(60)
        val (_, rides) = detect(b, Activity.MTB)
        assertEquals(listOf(RideKind.VEHICLE), rides.map { it.kind })
    }

    @Test fun cableCarUpIsALift() {
        val b = TraceBuilder()
            .walk(20.0).stand(8 * 60).mark("lift")
            // 6 min, 2.5 km horizontal, +900 m: 2.5 m/s up, on a straight line
            .move(360, 25.0, 340.0, vertMs = 2.5)
            .stand(60).walk(60.0, heading = 300.0, vertMs = 0.15)
        for (a in listOf(Activity.HIKE, Activity.MTB)) {
            val (_, rides) = detect(b, a)
            assertEquals("$a", listOf(RideKind.LIFT), rides.map { it.kind })
        }
    }

    @Test fun fastBikeDescentOnAPassRoadIsNotACar() {
        val b = TraceBuilder(ele0 = 2000.0)
            .move(10 * 60, 18.0, 90.0, vertMs = 0.0)
            // 5 min down the pass at 60 km/h, −7 %, switchbacks
            .passRoad(5 * 60, 60.0, 180.0, vertMs = -1.17, legS = 40)
            // straight valley road down at 55 km/h, −4 %
            .move(3 * 60, 55.0, 200.0, vertMs = -0.6)
            .move(20 * 60, 22.0, 220.0)
        for (a in listOf(Activity.MTB, Activity.BIKE_TOURING, Activity.EBIKE)) {
            val (_, rides) = detect(b, a)
            assertEquals("$a", emptyList<Ride>(), rides)
        }
    }

    @Test fun straightSteepTrailDescentIsNotALift() {
        // MTB, 35 km/h straight down a 25 % fire road: 2.4 m/s vertical for 2 min
        val b = TraceBuilder(ele0 = 1800.0)
            .move(10 * 60, 12.0, 0.0, vertMs = 0.3)
            .move(120, 35.0, 180.0, vertMs = -2.4)
            .move(10 * 60, 15.0, 180.0)
        val (_, rides) = detect(b, Activity.MTB)
        assertEquals(emptyList<Ride>(), rides)
    }

    @Test fun sPedelecAt45IsNotACar() {
        val b = TraceBuilder().move(25 * 60, 45.0, 45.0, headingAt = { s -> 45 + 15 * kotlin.math.sin(s / 200.0) })
        assertEquals(emptyList<Ride>(), detect(b, Activity.EBIKE).second)
    }

    @Test fun runningIsNotABus() {
        val b = TraceBuilder().walk(10.0).move(15 * 60, 13.0, 90.0, headingAt = { s -> 90 + 30 * kotlin.math.sin(s / 60.0) }).walk(10.0)
        assertEquals(emptyList<Ride>(), detect(b, Activity.HIKE).second)
    }

    @Test fun walkingWithGpsJumpsAndAGapIsNotARide() {
        val b = TraceBuilder()
        repeat(30) { k ->
            b.walk(1.5, heading = 60.0 + k * 7)
            // a lone jump 150–300 m away
            b.t += 1000; b.fix(offsetM = 150.0 + (k % 4) * 50, offsetDeg = k * 37.0)
            // multipath in a gorge: 12 s wandering out to ~100 m and back
            if (k % 3 == 0) for (s in 1..12) { b.t += 1000; b.fix(offsetM = 100.0 * kotlin.math.sin(Math.PI * s / 12), offsetDeg = 200.0) }
        }
        // 3 min without GPS under forest, 220 m walked
        b.gap(180, 220.0, 70.0)
        b.walk(20.0)
        val (_, rides) = detect(b, Activity.HIKE)
        assertEquals(emptyList<Ride>(), rides)
    }

    @Test fun trainFasterThanTheFilterStillCounts() {
        // 150 km/h: the recording drops these fixes as jumps until the average speed is plausible again
        val b = TraceBuilder().walk(20.0).stand(5 * 60).move(30 * 60, 150.0, 170.0).stand(120).walk(15.0)
        val (_, rides) = detect(b, Activity.HIKE)
        assertEquals(listOf(RideKind.VEHICLE), rides.map { it.kind })
    }

    @Test fun twoRidesInOneDay() {
        val b = TraceBuilder()
            .walk(15.0).stand(5 * 60).move(300, 25.0, 0.0, vertMs = 3.0) // lift up
            .walk(90.0, heading = 120.0, vertMs = -0.05)
            .stand(5 * 60).bus(legs = 10).walk(2.0)
        val (_, rides) = detect(b, Activity.HIKE)
        assertEquals(listOf(RideKind.LIFT, RideKind.VEHICLE), rides.map { it.kind })
        assertTrue(rides[0].to < rides[1].from)
    }

    @Test fun nothingToJudge() {
        assertEquals(emptyList<Ride>(), RideDetector.detect(emptyList(), Activity.HIKE))
        val noTimes = listOf(TraceBuilder().walk(5.0).fixes.map { com.onpath.app.gpx.TrackPoint(it.lat, it.lon, it.ele, null) })
        assertEquals(emptyList<Ride>(), RideDetector.detect(noTimes, Activity.HIKE))
    }
}
