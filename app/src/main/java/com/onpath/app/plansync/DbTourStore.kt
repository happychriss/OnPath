package com.onpath.app.plansync

import android.content.Context
import com.onpath.app.backup.Backups
import com.onpath.app.data.Geo
import com.onpath.app.data.Tour
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint

/**
 * [TourStore] on the tour database. Every change also goes to the GPX backup (Backups.tourChanged, C11). A planned
 * route from the browser is stored with its points as the line (like Save while the route is still calculating) and
 * routed afterwards by the view model; [factors] gives the pace for an imported track's time.
 */
class DbTourStore(
    private val context: Context,
    private val db: TourDb,
    private val factors: (Activity) -> com.onpath.app.time.Factors,
) : TourStore {
    override fun tours(): List<Tour> =
        (db.listTours() + db.listDeleted()).filter { it.kind == TourKind.PLANNED || it.kind == TourKind.IMPORTED }

    override fun segments(id: Long): List<List<TrackPoint>> = db.loadSegments(id)

    override fun addPlan(name: String, plan: PlanContent): Tour {
        val id = db.insertTour(TourKind.PLANNED, name, plan.activity.name, plan.sac, Geo.length(plan.points), 0.0, 0.0, 0.0,
            listOf(plan.points.map { TrackPoint(it.lat, it.lon) }), plan.stops, pinned = plan.pinned)
        Backups.tourChanged(context, id)
        return db.getTour(id) ?: error("planned route $id not found after saving")
    }

    override fun replacePlan(t: Tour, plan: PlanContent): Boolean =
        db.replacePlanned(t.id, plan.activity.name, plan.sac, Geo.length(plan.points), 0.0, 0.0, 0.0,
            listOf(plan.points.map { TrackPoint(it.lat, it.lon) }), plan.stops, plan.pinned)
            .also { if (it) Backups.tourChanged(context, t.id) }

    override fun addTrack(name: String, track: TrackContent): Tour {
        val (dist, up, down, dur) = stats(track)
        val id = db.insertTour(TourKind.IMPORTED, name, track.activity.name, track.sac, dist, up, down, dur, track.segments, track.waypoints)
        Backups.tourChanged(context, id)
        return db.getTour(id) ?: error("imported track $id not found after saving")
    }

    override fun replaceTrack(t: Tour, track: TrackContent): Boolean {
        val (dist, up, down, dur) = stats(track)
        return db.replaceImported(t.id, track.activity.name, track.sac, dist, up, down, dur, track.segments, track.waypoints)
            .also { if (it) Backups.tourChanged(context, t.id) }
    }

    override fun rename(t: Tour, name: String) { db.rename(t.id, name); Backups.tourChanged(context, t.id) }

    override fun toBin(t: Tour) { if (db.setDeleted(t.id, System.currentTimeMillis())) Backups.tourChanged(context, t.id) }

    override fun fromBin(t: Tour) { if (db.setDeleted(t.id, null)) Backups.tourChanged(context, t.id) }

    /** Distance, ascent, descent and time like a GPX import (AppViewModel.importGpx). */
    private fun stats(track: TrackContent): List<Double> {
        val dist = track.segments.sumOf { s -> Geo.length(s.map { GeoPoint(it.lat, it.lon) }) }
        var up = 0.0; var down = 0.0
        track.segments.forEach { s -> val (u, d) = Geo.climb(s.map { it.ele }); up += u; down += d }
        val dur = com.onpath.app.time.TimeEstimate.importedSeconds(track.segments, track.activity, factors(track.activity))
        return listOf(dist, up, down, dur)
    }
}
