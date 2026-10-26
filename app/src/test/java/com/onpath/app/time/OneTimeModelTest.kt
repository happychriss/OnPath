package com.onpath.app.time

import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LongRoute
import com.onpath.app.routing.StraightLineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One time model (captain 2026-10-26, "no surprises"): a line without timestamps – an imported GPX track, its entry in
 * My routes, a straight-line fallback, the "longer than a day?" question – gets its time from the same model as a
 * planned route ([RouteTimes] with the pace level), so the same line shows the same time wherever it appears. Until
 * 2026-10-26 `Geo.estimateSeconds` kept its own older bike speeds (Trekking 16, Touring 20 km/h, …).
 */
class OneTimeModelTest {

    private val activities = listOf(Activity.HIKE, Activity.BIKE_TREKKING, Activity.BIKE_TOURING, Activity.MTB,
        Activity.EBIKE, Activity.EMTB)

    private fun trackOf(points: List<GeoPoint>) = points.map { TrackPoint(it.lat, it.lon, it.ele) }

    @Test fun anImportedTrackTakesAsLongAsTheSameLinePlanned() {
        for (id in ReferenceRoutesTest.IDS) {
            val pts = ReferenceRoutesTest.load(id).points.map { GeoPoint(it.lat, it.lon, it.ele) } // no way attributes in a file
            for (a in activities) for (pace in Pace.entries) {
                val f = pace.factors(a)
                assertEquals("$id $a $pace", TimeEstimate.planSeconds(pts, a, f),
                    TimeEstimate.importedSeconds(listOf(trackOf(pts)), a, f), 0.0)
            }
        }
    }

    @Test fun timestampsInTheFileWin() {
        val t0 = 1_700_000_000_000L
        val pts = listOf(TrackPoint(46.6, 11.1, 300.0, t0), TrackPoint(46.61, 11.1, 300.0, t0 + 3_600_000))
        assertEquals(3600.0, TimeEstimate.importedSeconds(listOf(pts), Activity.HIKE, Factors.ONE), 0.0)
        // a single timestamp (or none) is no time: the model's
        assertNull(TimeEstimate.fileSeconds(listOf(pts.map { it.copy(timeMs = null) })))
        assertNull(TimeEstimate.fileSeconds(listOf(listOf(pts[0]), listOf(pts[1].copy(timeMs = null)))))
    }

    @Test fun theGapBetweenSegmentsIsNotWalked() {
        val a = listOf(GeoPoint(46.60, 11.10, 300.0), GeoPoint(46.65, 11.10, 300.0))
        val b = listOf(GeoPoint(46.70, 11.10, 300.0), GeoPoint(46.75, 11.10, 300.0))
        val one = RouteTimes.of(a, Activity.HIKE).total() + RouteTimes.of(b, Activity.HIKE).total()
        assertEquals(TimeEstimate.round(one),
            TimeEstimate.importedSeconds(listOf(trackOf(a), trackOf(b)), Activity.HIKE, Factors.ONE), 0.0)
    }

    @Test fun straightLinesAndTheLongRouteQuestionUseTheModel() {
        val pts = listOf(GeoPoint(46.60, 11.10, 300.0), GeoPoint(46.78, 11.10, 300.0)) // 20 km, flat
        for (a in activities) {
            val model = RouteTimes.of(pts, a).total()
            assertEquals("$a straight engine", model, StraightLineEngine().route(pts, a, 2).durationS, 1e-6)
            assertEquals("$a long-route estimate", model * LongRoute.DETOUR, LongRoute.estimateS(pts, a), model * 1e-6)
        }
    }

    @Test fun flatSpeedsAreThePaceModels() {
        // Average pace on 20 km of flat: the speeds Settings → Pace shows ("about 4 km/h", bikes from the power model)
        val pts = listOf(GeoPoint(46.60, 11.10, 300.0), GeoPoint(46.78, 11.10, 300.0))
        for (a in activities) {
            val s = TimeEstimate.importedSeconds(listOf(trackOf(pts)), a, Factors.ONE)
            val kmh = if (a == Activity.HIKE) 4.0 else PaceModel.bikeSpeedMs(0.0, a) * 3.6
            val len = com.onpath.app.data.Geo.length(pts)
            assertEquals("$a", TimeEstimate.round(len / (kmh / 3.6)), s, 0.0)
        }
    }
}
