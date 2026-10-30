package com.onpath.app.plansync

import com.onpath.app.data.Tour
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.SharedPlan
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncMerge
import com.onpath.app.sync.SyncTrack
import com.onpath.app.sync.TrackCode
import com.onpath.app.sync.TrackMark
import com.onpath.app.sync.TrackPt

/** What a synced plan puts into a planned route: its points (with names) and pins, activity and T level. */
data class PlanContent(val points: List<GeoPoint>, val activity: Activity, val sac: Int, val pinned: Set<Int>,
                       val names: Map<Int, String>) {
    /** The route's stops as saved (GpxWaypoint with the names, like AppViewModel.savedStops). */
    val stops: List<GpxWaypoint> get() = points.mapIndexed { i, p -> GpxWaypoint(p.lat, p.lon, names[i]) }
}

/** What a synced track puts into an imported tour. */
data class TrackContent(val segments: List<List<TrackPoint>>, val waypoints: List<GpxWaypoint>, val activity: Activity, val sac: Int)

/**
 * Saved routes and imported tracks ⇄ sync items (docs/design/web-sync.md §11.3, owner 2026-10-29): a planned route is
 * a plan item (its plan code: points, activity, T level, pins, point names – the same text as a plan link), an imported
 * GPX track a track item ([TrackCode]). The title is the route's name. Recorded tours are not synced.
 */
object RouteItems {
    /** Planned routes and imported tracks are synced; recordings are not. */
    fun synced(t: Tour): Boolean = t.state == "done" && (t.kind == com.onpath.app.data.TourKind.PLANNED || t.kind == com.onpath.app.data.TourKind.IMPORTED)

    fun kindOf(t: Tour): Int = if (t.kind == com.onpath.app.data.TourKind.PLANNED) SyncItem.PLAN else SyncItem.TRACK

    fun activityOf(t: Tour): Activity = t.activity?.let { a -> Activity.entries.firstOrNull { it.name == a } } ?: Activity.HIKE

    /** The plan code of a planned route (the title is the item's, not inside the code); null below two points. */
    fun planOf(t: Tour): String? {
        if (t.waypoints.size < 2) return null
        val pts = t.waypoints.map { GeoPoint(it.lat, it.lon) }
        return PlanCode.encode(SharedPlan(pts, activityOf(t), t.sac.coerceIn(1, 5), t.pinned.filter { it in pts.indices }.toSet(),
            t.waypoints.mapIndexedNotNull { i, w -> w.name?.let { i to it } }.toMap(), title = null))
    }

    /** Same plan (points, activity, T level, pins, names) – a title inside a code made elsewhere does not count. */
    fun samePlan(a: String?, b: String?): Boolean = normal(a) == normal(b)

    private fun normal(code: String?): String? = code?.let(PlanCode::decode)?.copy(title = null)?.let(PlanCode::encode)

    fun planContent(item: SyncItem): PlanContent? {
        val p = item.plan?.let(PlanCode::decode)?.takeIf { it.points.size >= 2 } ?: return null
        return PlanContent(p.points, p.activity, p.sac, p.pinned, p.names)
    }

    /** The track item text of an imported tour (thinned to fit when long); null without points. */
    fun trackOf(t: Tour, segments: List<List<TrackPoint>>): String? = TrackCode.fit(SyncTrack(
        segments.map { s -> s.map { TrackPt(it.lat, it.lon, it.ele) } }, activityOf(t), t.sac.coerceIn(1, 5),
        t.waypoints.map { TrackMark(it.lat, it.lon, it.name) }))

    /** Activity and T level of a track item equal the tour's (a cheap check – the points are compared only on change). */
    fun sameTrackHeader(t: Tour, item: SyncItem): Boolean = TrackCode.header(item.track) == (activityOf(t) to t.sac.coerceIn(1, 5))

    fun trackContent(item: SyncItem): TrackContent? {
        val tr = TrackCode.of(item.track)?.takeIf { s -> s.segments.any { it.isNotEmpty() } } ?: return null
        return TrackContent(tr.segments.filter { it.isNotEmpty() }.map { s -> s.map { TrackPoint(it.lat, it.lon, it.ele) } },
            tr.marks.map { GpxWaypoint(it.lat, it.lon, it.name) }, tr.activity, tr.sac)
    }

    /**
     * The name as the item keeps it (compare with [SyncItem.title]); a copy shown with the local [copyLabel]
     * (" (Kopie)") keeps the stored marker [SyncMerge.COPY].
     */
    fun title(t: Tour, copyLabel: String = SyncMerge.COPY): String? = SyncFormat.cleanTitle(SyncMerge.storedTitle(t.name, copyLabel))
}
