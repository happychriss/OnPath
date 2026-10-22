package com.onpath.app.rides

import com.onpath.app.gpx.GpxParser
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The owner's field tour of 2026-10-22, "Verdins → Schenna" (backup file as written by 0.9.x, all 335 stored points,
 * tracks/owner-2026-10-22-taser/): standing at the Taser top station 15:49–15:57 CEST, the gondola down 15:57:53–16:03:49
 * (no fix in the cabin: one 356 s / 1645 m / 1500 → 907 m jump), then a car / bus 16:06–16:10 (9–11 m/s, ~2.1 km)
 * that 0.9.2 counted as hike distance. Both must become rides; the waiting at the station is cut off with the lift.
 */
class OwnerTaserTourTest {
    private val text = javaClass.classLoader!!.getResource("tracks/owner-2026-10-22-taser/verdins-schenna.gpx")!!.readText()
    private val segs = GpxParser.parse(text).allSegments
    private val pts = segs.flatten()
    private fun z(s: String) = Instant.parse("2026-10-22T${s}Z").toEpochMilli()

    @Test fun gondolaAndCarAreRides() {
        assertEquals(335, pts.size)
        val rides = RideDetector.detect(segs, Activity.HIKE)
        assertEquals(listOf(RideKind.LIFT, RideKind.VEHICLE), rides.map { it.kind })
        val (lift, car) = rides
        // lift: from arriving at the top station (standing there is not hiking) to the first fix in the valley
        val liftFrom = pts[lift.from].timeMs!!
        assertTrue("lift from ${Instant.ofEpochMilli(liftFrom)}", liftFrom in z("13:45:29")..z("13:50:00"))
        assertEquals(z("14:03:49"), pts[lift.to].timeMs)
        // car: 16:06–16:10 CEST
        val carFrom = pts[car.from].timeMs!!; val carTo = pts[car.to].timeMs!!
        assertTrue("car from ${Instant.ofEpochMilli(carFrom)}", carFrom in z("14:04:00")..z("14:07:00"))
        assertTrue("car to ${Instant.ofEpochMilli(carTo)}", carTo >= z("14:09:30"))
    }

    @Test fun ridesNotCountedTakeTheCarOutOfTheHikeDistance() {
        val rides = RideDetector.detect(segs, Activity.HIKE)
        val recorded = TourStats(2385.4, 12.3, 836.1, 1478.0) // as stored by 0.9.x
        val s = RideView.stats(segs, RideRecord(rides, RideChoice.NOT_COUNTED, recorded, z("14:10:15")))
        println("Taser: rides ${rides.map { "${it.kind} ${Instant.ofEpochMilli(pts[it.from].timeMs!!)}–${Instant.ofEpochMilli(pts[it.to].timeMs!!)}" }}, " +
            "not counted: ${s.distanceM.toInt()} m, ↓${s.descentM.toInt()} m, ${s.durationS.toInt()} s")
        assertTrue("hike distance ${s.distanceM}", s.distanceM < 600)
        assertTrue("descent ${s.descentM}", s.descentM < 250) // the 593 m by gondola and the drive down are not hiked
        val sum = RideView.summary(segs, rides, onFoot = true, zone = java.time.ZoneId.of("Europe/Rome"))!!
        assertEquals(listOf(RideView.RowKind.ON_FOOT, RideView.RowKind.LIFT, RideView.RowKind.ON_FOOT, RideView.RowKind.VEHICLE),
            sum.rows.map { it.kind }.let { k -> if (k.last() == RideView.RowKind.ON_FOOT) k.dropLast(1) else k })
        // the car ride is at the end: "Save only until …" is proposed
        assertEquals(RideChoice.UNTIL, sum.preselected)
    }

    @Test fun standingAtTheTopStationIsNoRide() {
        // only the part before the gondola: hiking up to the station and waiting there
        val before = segs.map { s -> s.filter { it.timeMs!! < z("13:57:54") } }.filter { it.isNotEmpty() }
        assertEquals(emptyList<Ride>(), RideDetector.detect(before, Activity.HIKE))
    }
}
