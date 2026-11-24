package com.onpath.app.ui.routes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.TrackToPlan
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.Say
import com.onpath.app.ui.ShownTrack
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.strings.*

/**
 * Re-traces a track off the caller's thread ([TrackToPlan.fit] with our router): [got] runs on the caller's thread with the
 * fit, or null (the straight-line engine, or a failure – reported as before unless [cancel] was asked). The app:
 * `CoroutineTrackFitter` (the routing background thread); the planner: its routing worker.
 */
fun interface TrackFitter {
    fun fit(track: List<GeoPoint>, act: Activity, level: Int, cancel: RouteCancel, got: (TrackToPlan.Result?) -> Unit)
}

/** What [TrackToPlanState] asks of the rest of the app (the main thread). */
interface TrackToPlanPorts : Say {
    /** The track shown on the map; null = none. */
    val shown: ShownTrack?
    /** Closes the shown track. */
    fun closeShown()
    /** The plan's activity. */
    val activity: Activity
    /** The plan's T level. */
    val sac: Int
    /** The routing data parts [track] crosses with [act]. */
    fun routingParts(track: List<GeoPoint>, act: Activity): List<String>
    /** Routing data along [parts] is checked / asked for: the parts still missing. */
    fun ensureRoutingData(parts: List<String>): List<String>
    /** The plan's points are changed by [block] – one Undo step, stored, routed. */
    fun change(block: MutableList<GeoPoint>.() -> Unit)
    fun rememberPlaceName(p: GeoPoint, name: String)
}

/**
 * A track made a plan (cut out of `RoutesSlice`): "Als Plan übernehmen" ([planFromShown]) – an exported plan GPX comes back with
 * its own points, any other track is re-traced by [fitter] with the current activity; the result replaces the plan (Undo brings
 * it back, the replaced plan goes under Zuletzt). [busy] = a modal box with Cancel over everything (owner 2026-11-01 – nothing
 * else at the same time; review routing M3: minutes for a long track, points set meanwhile were overwritten).
 * Android-free and coroutine-free: the browser planner takes this file as it is.
 */
class TrackToPlanState(
    private val plan: PlanDocument,
    private val save: SaveState,
    private val ports: TrackToPlanPorts,
    private val fitter: TrackFitter,
    /** Elapsed real time in ms (the duration of the track fit in the log). */
    private val elapsedMs: () -> Long,
) {
    var busy by mutableStateOf(false)
        private set
    private var cancelRun: RouteCancel? = null

    private fun sayError(text: String, place: NoticePlace) = ports.say(text, NoticeKind.ERROR, place)

    fun cancel() {
        Problems.trace("plan", "Track → plan cancelled by the user")
        cancelRun?.cancel()
        busy = false
    }

    /**
     * The track sheet's "Als Plan übernehmen" (owner; routing/TrackToPlan): our router and the current activity re-trace
     * the track; the result replaces the plan (Undo brings it back). An unsaved plan asks first ([unlessUnsaved], P3).
     */
    fun planFromShown() = unlessUnsaved("Track → plan") { planFromShownNow() }

    fun planFromShownNow() {
        val sh = ports.shown ?: return
        val track = sh.segments.flatten().takeIf { it.size >= 2 } ?: return
        // an exported OnPath plan (gpx/PlanGpx): its <wpt> are the route points – the plan comes back exactly, with names
        com.onpath.app.gpx.PlanGpx.stopsOf(track, sh.waypoints)?.let { stops ->
            Problems.trace("plan", "Track → plan: the file's ${stops.size} route points (a plan GPX), no fitting")
            val pts = stops.map { GeoPoint(it.lat, it.lon) }
            ports.closeShown()
            // pinned route points come back pinned (review routing L1)
            plan.pin(com.onpath.app.gpx.PlanGpx.pinnedOf(stops).map { pts[it] })
            save.keepAsDraft("replaced by a track's plan")
            ports.change { clear(); addAll(pts) }
            stops.forEachIndexed { i, w -> w.name?.let { ports.rememberPlaceName(pts[i], it) } }
            return
        }
        planFromLine(track, ports.routingParts(track, ports.activity), ports.activity, ports.sac) { if (it) ports.closeShown() }
    }

    /** [track] → a plan (routing/TrackToPlan) with [act] / [level], [parts] = its routing data; [done] true just before the plan replaces the old one, false when nothing changes. */
    fun planFromLine(track: List<GeoPoint>, parts: List<String>, act: Activity, level: Int, done: (Boolean) -> Unit) {
        if (busy) return done(false)
        // routing data along the track first: without it every leg fails
        val missing = try { ports.ensureRoutingData(parts) } catch (e: Exception) {
            Problems.warn("routing", "Routing data along the track not checked; planning from the track anyway", e); emptyList()
        }
        if (missing.isNotEmpty()) { sayError(Txt.s(Res.string.plan_from_track_wait), NoticePlace.MAP); return done(false) }
        busy = true
        val cancel = RouteCancel().also { cancelRun = it }
        val t0 = elapsedMs()
        fitter.fit(track, act, level, cancel) { r ->
            if (cancelRun === cancel) cancelRun = null
            if (cancel.isCancelled) done(false) // the user cancelled: nothing changes
            else {
                busy = false
                if (r == null || r.points.size < 2) { sayError(Txt.s(Res.string.plan_from_track_failed), NoticePlace.MAP); done(false) }
                else {
                    Problems.trace("plan", "Track → plan: ${track.size} track points → ${r.points.size} route points, follows ${(r.follows * 100).toInt()} %, " +
                        "${r.calls} routing calls, ${r.failedLegs} legs without a route, ${elapsedMs() - t0} ms")
                    done(true)
                    save.keepAsDraft("replaced by a track's plan")
                    ports.change { clear(); addAll(r.points) }
                }
            }
        }
    }
}
