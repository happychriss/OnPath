package com.onpath.app.gpx

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one plan GPX export, shared by app and web planner (core feature C7): same file as before, re-import rebuilds the plan. */
class PlanGpxTest {
    private val a = GeoPoint(46.6700, 11.1600)
    private val b = GeoPoint(46.6750, 11.1700)
    private val c = GeoPoint(46.6800, 11.1650)
    private val t0 = 1_790_000_000_000L

    /** A routed line through [pts] (straight legs, 20 points each), as the router's line with elevation. */
    private fun line(pts: List<GeoPoint>): List<GeoPoint> = pts.zipWithNext().flatMapIndexed { li, (p, q) ->
        (0..20).drop(if (li == 0) 0 else 1).map { k ->
            val f = k / 20.0
            GeoPoint(p.lat + (q.lat - p.lat) * f, p.lon + (q.lon - p.lon) * f, 300.0 + k)
        }
    }
    private fun trk(l: List<GeoPoint>) = listOf(l.map { TrackPoint(it.lat, it.lon, it.ele) })
    private val names = mapOf(1 to "Leiter Alm & <Hütte>")

    /** What My routes → Export GPX wrote for a planned route before PlanGpx (AppViewModel.exportTour → share). */
    private fun before(name: String, segs: List<List<TrackPoint>>, wpts: List<GpxWaypoint>, activity: String?, sac: Int): GpxFile =
        GpxFile(GpxWriter.fileName(name, GpxWriter.kindSuffix(true, false)),
            GpxWriter.write(name, segs, wpts, t0, type = SportType.typeOf(activity, sac)))

    @Test fun sameFileAsTheAppWroteBefore() {
        val plan = listOf(a, b, c)
        val stops = PlanGpx.stops(plan) { names[it] }
        for (act in Activity.entries) for (sac in listOf(2, 4)) {
            assertEquals("$act T$sac", before("Hilden", trk(line(plan)), stops, act.name, sac),
                PlanGpx.export("Hilden", trk(line(plan)), stops, act.name, sac, t0))
            assertEquals(PlanGpx.export("Hilden", trk(line(plan)), stops, act.name, sac, t0),
                PlanGpx.export("Hilden", trk(line(plan)), stops, act, sac, t0))
        }
        // an unknown stored activity: no <type>, as before
        assertEquals(before("x", trk(line(plan)), stops, "BOAT", 2), PlanGpx.export("x", trk(line(plan)), stops, "BOAT", 2, t0))
        val f = PlanGpx.export("Hilden", trk(line(plan)), stops, Activity.HIKE, 3, t0)
        assertEquals("Hilden - plan.gpx", f.fileName)
        assertTrue(f.text.contains("<type>mountaineering</type>"))
        assertTrue(f.text.contains("<name>Leiter Alm &amp; &lt;Hütte&gt;</name>"))
        assertEquals(3, Regex("<wpt ").findAll(f.text).count())
    }

    /** Export → import → "Plan from track": the same route points in the same order, with their names, and the activity. */
    @Test fun reimportRebuildsTheStops() {
        for (plan in listOf(listOf(a, b, c), PlanEdits.roundTrip(listOf(a, b, c)))) {
            val stops = PlanGpx.stops(plan) { names[it] }
            val f = PlanGpx.export("Tour", trk(line(plan)), stops, Activity.MTB, 2, t0)
            val data = GpxParser.parse(f.text)
            val back = PlanGpx.stopsOf(data.allSegments.flatten().map { it.toGeo() }, data.waypoints)
            assertNotNull("plan of ${plan.size} points", back)
            assertEquals(plan.size, back!!.size)
            back.forEachIndexed { i, w ->
                assertEquals(plan[i].lat, w.lat, 1e-7); assertEquals(plan[i].lon, w.lon, 1e-7)
                assertEquals(names[i], w.name)
            }
            assertEquals(Activity.MTB, SportType.activityOf(data.type)?.activity)
            if (plan.size == 4) assertTrue("a round trip stays a round trip", PlanEdits.isRoundTrip(back.map { GeoPoint(it.lat, it.lon) }))
        }
    }

    /** Waypoints that are not the plan's (POIs along a track, a single mark): the plan is fitted to the line instead. */
    @Test fun otherWaypointsAreNotAPlan() {
        val l = line(listOf(a, b, c))
        val mid = l[20]
        assertNull("not at the start", PlanGpx.stopsOf(l, listOf(GpxWaypoint(mid.lat, mid.lon, "Hut"), GpxWaypoint(c.lat, c.lon, null))))
        assertNull("not at the end", PlanGpx.stopsOf(l, listOf(GpxWaypoint(a.lat, a.lon, null), GpxWaypoint(mid.lat, mid.lon, "Hut"))))
        assertNull("far from the line", PlanGpx.stopsOf(l, listOf(GpxWaypoint(a.lat, a.lon, null), GpxWaypoint(46.70, 11.30, "Peak"),
            GpxWaypoint(c.lat, c.lon, null))))
        assertNull("one waypoint", PlanGpx.stopsOf(l, listOf(GpxWaypoint(a.lat, a.lon, null))))
        assertNull("no line", PlanGpx.stopsOf(emptyList(), listOf(GpxWaypoint(a.lat, a.lon, null), GpxWaypoint(c.lat, c.lon, null))))
    }
}
