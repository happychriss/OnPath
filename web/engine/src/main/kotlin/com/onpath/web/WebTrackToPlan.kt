package com.onpath.web

import com.onpath.app.data.Tour
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.data.MultiDay
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.TrackToPlan
import com.onpath.app.ui.Background
import com.onpath.app.ui.NameProposal
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.ShownTrack
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanStore
import com.onpath.app.ui.routes.LeavingPlan
import com.onpath.app.ui.routes.PlannedRow
import com.onpath.app.ui.routes.Recent
import com.onpath.app.ui.routes.RoutesState
import com.onpath.app.ui.routes.RoutesStore
import com.onpath.app.ui.routes.SavePorts
import com.onpath.app.ui.routes.SaveState
import com.onpath.app.ui.routes.SaveStore
import com.onpath.app.ui.routes.TrackFitter
import com.onpath.app.ui.routes.TrackToPlanPorts
import com.onpath.app.ui.routes.TrackToPlanState
import com.onpath.strings.*
import org.json.JSONArray

/**
 * „Als Plan übernehmen“ of the track panel (the app's RoutesSlice.planFromShown → [TrackToPlanState], web-ready): the app's
 * state runs as it is on a scratch plan holding the page's points – an exported plan GPX comes back with its own points,
 * pins and names, any other track is re-traced ([TrackToPlan.fit] with our router, in this routing worker, so [TrackFitter]
 * answers at once); the plan that is replaced goes under Zuletzt ([SaveState.keepAsDraft] → `keepDraft`). The page commits
 * the answer (one Undo step) and keeps the draft itself – the scratch store writes nothing.
 * The routing data along the track is in place before (worker.ts ensureParts), so [TrackToPlanPorts.ensureRoutingData]
 * finds nothing missing.
 */
object WebTrackToPlan {
    /**
     * [plan] = the page's points ("lat,lon;…"), [segments] = the shown track (JSON [[[lat,lon,ele?],…],…]), [waypoints] = its
     * <wpt> (JSON [{lat,lon,name,type}]). JSON {"points":[[lat,lon],…],"names":[…],"pinned":[…],"keepDraft":bool} or {"error":…}.
     */
    fun plan(plan: String, segments: String, waypoints: String, activity: String, sac: Int,
             progress: (Int, Int) -> Unit = { _, _ -> }): String {
        val said = ArrayList<String>()
        try {
            val act = Activity.valueOf(activity)
            val segs = JSONArray(segments).let { a ->
                (0 until a.length()).map { i -> a.getJSONArray(i).let { s ->
                    (0 until s.length()).map { j -> s.getJSONArray(j).let { p -> GeoPoint(p.getDouble(0), p.getDouble(1)) } } } }
            }
            val wpts = JSONArray(waypoints).let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { w ->
                    GpxWaypoint(w.getDouble("lat"), w.getDouble("lon"), w.optString("name").takeIf { !w.isNull("name") && it.isNotEmpty() },
                        type = w.optString("type").takeIf { !w.isNull("type") && it.isNotEmpty() }) } }
            }
            var keepDraft = false
            var result: List<GeoPoint>? = null
            val names = HashMap<GeoPoint, String>()
            val store = object : PlanStore {
                override var points: List<GeoPoint> = Engine.parsePoints(plan)
                override var pinned: Set<GeoPoint> = emptySet()
                override var savedName: String? = null
                override var savedId: Long = -1L
                override var savedPoints: List<GeoPoint>? = null
            }
            val doc = PlanDocument(store)
            val save = SaveState(doc, RoutesState(NoRoutes), object : Recent {
                override fun keep(plan: LeavingPlan, why: String): Long? { keepDraft = true; return null }
                override fun keepReplaced(old: Tour, segments: List<List<TrackPoint>>) = Unit
            }, NoSaves, ScratchSavePorts(act, sac), Now, NameProposal { _, _, _, _, always, got -> got(null); always() }, 0L)
            val shown = ShownTrack(null, "", segs, wpts, 0.0, 0.0, 0.0, 0.0)
            val ports = object : TrackToPlanPorts {
                override val shown: ShownTrack? = shown
                override fun closeShown() = Unit // the page closes its panel when it commits
                override val activity = act
                override val sac = sac
                override fun routingParts(track: List<GeoPoint>, act: Activity) = emptyList<String>()
                override fun ensureRoutingData(parts: List<String>) = emptyList<String>()
                override fun change(block: MutableList<GeoPoint>.() -> Unit) { result = ArrayList<GeoPoint>().apply(block) }
                override fun rememberPlaceName(p: GeoPoint, name: String) { names[p] = name }
                override fun say(text: String, kind: NoticeKind, place: NoticePlace) { said += text }
            }
            val fitter = TrackFitter { track, a, level, cancel, got ->
                got(try { TrackToPlan.fit(track, Engine.engine(), a, level, cancel = cancel, progress = progress) } catch (e: Exception) {
                    Problems.error("routing", "Track could not be turned into a plan", e); null
                })
            }
            TrackToPlanState(doc, save, ports, fitter, elapsedMs = { System.currentTimeMillis() }).planFromShownNow()
            val pts = result
            if (pts == null || pts.size < 2)
                return "{\"error\":" + Engine.str(said.firstOrNull() ?: Txt.s(Res.string.plan_from_track_failed)) + "}"
            // the pins the state set (PlanDocument.pin, before its change) – by place, on the new points
            val pinnedAt = store.pinned
            val pinned = pts.indices.filter { pts[it] in pinnedAt }.toSet()
            return "{\"points\":" + pts.joinToString(",", "[", "]") { "[" + Engine.num(it.lat, 6) + "," + Engine.num(it.lon, 6) + "]" } +
                ",\"names\":" + pts.joinToString(",", "[", "]") { names[it]?.let { n -> Engine.str(n) } ?: "null" } +
                ",\"pinned\":" + pts.indices.joinToString(",", "[", "]") { (it in pinned).toString() } +
                ",\"keepDraft\":" + keepDraft + "}"
        } catch (e: Exception) {
            Problems.error("routing", "Track could not be turned into a plan", e)
            return "{\"error\":" + Engine.str(Txt.s(Res.string.plan_from_track_failed)) + "}"
        }
    }

    /** The scratch plan's My routes: empty – nothing is listed or written here. */
    private object NoRoutes : RoutesStore {
        override fun listTours(): List<Tour> = emptyList()
        override fun listDeleted(): List<Tour> = emptyList()
        override fun setDraft(id: Long, draft: Boolean) = false
        override fun setDeleted(id: Long, deletedMs: Long?) = false
        override fun loadSegments(id: Long): List<List<TrackPoint>> = emptyList()
        override fun deleteForGood(id: Long) = Unit
        override fun rename(id: Long, name: String) = Unit
    }

    /** No saved route is written from here (only [SaveState.keepAsDraft] is asked, through [Recent]). */
    private object NoSaves : SaveStore {
        override fun getTour(id: Long): Tour? = null
        override fun loadSegments(id: Long): List<List<TrackPoint>> = emptyList()
        override fun insertPlanned(name: String, row: PlannedRow): Long = -1L
        override fun replacePlanned(id: Long, row: PlannedRow) = false
        override fun rename(id: Long, name: String) = Unit
        override fun setFavourite(id: Long, favourite: Boolean) = false
    }

    private object Now : Background {
        override fun <T> run(what: String, work: () -> T, always: () -> Unit, then: (T) -> Unit) {
            try { then(work()) } catch (e: Exception) { Problems.warn("routing", "$what failed", e) } finally { always() }
        }
    }

    /** What keepAsDraft reads of the plan: the activity; the rest is the page's own Zuletzt entry. */
    private class ScratchSavePorts(override val activity: Activity, override val sac: Int) : SavePorts {
        override val route: PlannedRoute? = null
        override fun stops(points: List<GeoPoint>): List<GpxWaypoint> = emptyList()
        override fun difficulty(route: PlannedRoute?, activity: Activity): String? = null
        override fun days(points: List<GeoPoint>): MultiDay? = null
        override fun keepWhole() = false
        override fun overwriteWhole(name: String, then: (() -> Unit)?) = false
        override fun datedName() = ""
        override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) = Unit
        override fun forgetTimes(id: Long) = Unit
        override fun copyTimes(from: Long, to: Long, distanceM: Double) = Unit
        override fun tourChanged(id: Long) = Unit
        override fun syncChanged(why: String) = Unit
        override fun refreshTours() = Unit
        override fun saved(name: String?, id: Long, points: List<GeoPoint>?) = Unit
        override fun shownRenamed(id: Long, name: String) = Unit
        override fun say(text: String, kind: NoticeKind, place: NoticePlace) = Unit
    }
}
