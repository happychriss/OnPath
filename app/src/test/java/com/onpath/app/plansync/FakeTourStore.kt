package com.onpath.app.plansync

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint

/** The tour database for sync tests: planned routes and imported tracks in memory, like TourDb does it. */
class FakeTourStore : TourStore {
    val list = ArrayList<Tour>()
    val lines = HashMap<Long, List<List<TrackPoint>>>()
    private var next = 1L
    /** Throws on the next write (a full database) when > 0. */
    var failWrites = 0
    var now = 1_000L

    override fun tours(): List<Tour> = list.toList()
    override fun segments(id: Long) = lines[id] ?: emptyList()

    fun byName(name: String) = list.single { it.name == name }
    fun live() = list.filter { it.deletedMs == null }

    private fun write() { if (failWrites > 0) { failWrites--; error("database is full") } }

    fun savePlanned(name: String, points: List<GeoPoint>, activity: Activity = Activity.HIKE, sac: Int = 2, names: Map<Int, String> = emptyMap(),
                    pinned: Set<Int> = emptySet()): Tour {
        val t = Tour(next++, TourKind.PLANNED, name, now, activity.name, sac, 0.0, 0.0, 0.0, 0.0, "done",
            points.mapIndexed { i, p -> GpxWaypoint(p.lat, p.lon, names[i]) }, uuid = "uuid-$next", pinned = pinned)
        list += t; lines[t.id] = listOf(points.map { TrackPoint(it.lat, it.lon) })
        return t
    }

    fun saveImported(name: String, segs: List<List<TrackPoint>>, wpts: List<GpxWaypoint> = emptyList(), activity: Activity = Activity.MTB): Tour {
        val t = Tour(next++, TourKind.IMPORTED, name, now, activity.name, 2, 0.0, 0.0, 0.0, 0.0, "done", wpts, uuid = "uuid-$next")
        list += t; lines[t.id] = segs
        return t
    }

    fun saveRecorded(name: String): Tour {
        val t = Tour(next++, TourKind.RECORDED, name, now, Activity.HIKE.name, 2, 0.0, 0.0, 0.0, 0.0, "done", emptyList(), uuid = "uuid-$next")
        list += t; lines[t.id] = listOf(listOf(TrackPoint(46.0, 11.0), TrackPoint(46.1, 11.1)))
        return t
    }

    fun update(t: Tour, f: (Tour) -> Tour) { val i = list.indexOfFirst { it.id == t.id }; list[i] = f(list[i]) }
    fun deleteForGood(t: Tour) { list.removeAll { it.id == t.id }; lines.remove(t.id) }

    override fun addPlan(name: String, plan: PlanContent): Tour {
        write()
        return savePlanned(name, plan.points, plan.activity, plan.sac, plan.names, plan.pinned)
    }

    override fun replacePlan(t: Tour, plan: PlanContent): Boolean {
        write()
        update(t) { it.copy(activity = plan.activity.name, sac = plan.sac, waypoints = plan.stops, pinned = plan.pinned) }
        lines[t.id] = listOf(plan.points.map { TrackPoint(it.lat, it.lon) })
        return true
    }

    override fun addTrack(name: String, track: TrackContent): Tour {
        write()
        return saveImported(name, track.segments, track.waypoints, track.activity).also { t -> update(t) { it.copy(sac = track.sac) } }
            .let { t -> list.single { it.id == t.id } }
    }

    override fun replaceTrack(t: Tour, track: TrackContent): Boolean {
        write()
        update(t) { it.copy(activity = track.activity.name, sac = track.sac, waypoints = track.waypoints) }
        lines[t.id] = track.segments
        return true
    }

    override fun rename(t: Tour, name: String) { write(); update(t) { it.copy(name = name) } }
    override fun toBin(t: Tour) { write(); update(t) { it.copy(deletedMs = now) } }
    override fun fromBin(t: Tour) { write(); update(t) { it.copy(deletedMs = null) } }
}
