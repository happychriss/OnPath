package com.onpath.app.ui.routes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.RouteRules
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.gpx.SportType
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.ui.Camera
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.Say
import com.onpath.app.ui.ShownTrack
import com.onpath.app.ui.TrackThen
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.app.ui.tour.TourController
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * What [RoutesSlice] asks of the rest of the app (all on the main thread unless said: [tourChanged], [syncForget] and
 * [syncChanged] are called on io, [withMaps] on io while the list is read). Implemented by the view model; tests
 * pass a fake that records the calls in order. Everything that starts, follows or ends a tour stays behind it (pieces 14, 16–19).
 */
interface RoutesHost : Say {
    /** A tour is being recorded. */
    val touring: Boolean
    /** The id of the tour being recorded, null = none. */
    val recordingTourId: Long?
    val mode: AppMode
    /** Switches to [m] (Plan / Navigate). */
    fun enterMode(m: AppMode)
    /** The browser sync marks tour [t] seen (opened from My routes). */
    fun syncMarkSeen(t: Tour)
    /** The activity of an opened route is the plan's now: remembered in the preferences. */
    fun rememberActivity(a: Activity)
    /** The T level of an opened track is the plan's now: remembered in the preferences. */
    fun rememberSac(level: Int)
    /** A route loaded during a tour: its first route is followed from its point ① (the next route run reads it once). */
    fun loadedOnTheWay(on: Boolean)
    fun routeEvent(e: RouteRun.Event)
    /** The plan's points are changed by [block] – one Undo step, stored, routed. */
    fun change(block: MutableList<GeoPoint>.() -> Unit)
    fun rememberPlaceName(p: GeoPoint, name: String)
    /** The plan was saved / loaded as [name] (route [id], [points]): the plan's link and a running tour's plan copy. */
    fun saved(name: String?, id: Long, points: List<GeoPoint>?)
    /** A planned route was opened as the plan (its multi-day state follows). */
    fun opened(t: Tour, pts: List<GeoPoint>)
    /** A synced plan opened: its routing fingerprint is checked against ours. */
    fun syncRoutingCheck(t: Tour, pts: List<GeoPoint>)
    /** The stored line of the loaded route, shown at once by the next route run in place of the first routing. */
    fun standIn(pts: List<GeoPoint>, act: Activity, level: Int, preview: com.onpath.app.routing.StoredLine.Preview)
    /** A multi-day tour's days as maps on its row (blocking; also called on the io thread). */
    fun withMaps(t: Tour): Tour
    /** The row pictures are made again. */
    fun kickThumbs()
    fun clearHighlight()
    /** The shown track is followed in the running tour (tour code, pieces 16–19: stays in the view model). */
    fun followShownNow()
    /** Routing data along [parts] is checked / asked for: the parts still missing. */
    fun ensureRoutingData(parts: List<String>): List<String>
    /** The routing data parts [track] crosses with [act]. */
    fun routingParts(track: List<GeoPoint>, act: Activity): List<String>
    /** The tour [id] changed (deleted into the bin): its backup file follows. */
    fun tourChanged(id: Long)
    /** The browser sync forgets the mark of tour [uuid] (blocking; on the io thread). */
    fun syncForget(uuid: String)
    fun syncChanged(why: String)

    // ---- Routes II (piece 14) ----
    /** The plan's route (null = none yet); read when a "Los geht's" waits for its line and for the ⋮ menu. */
    val route: PlannedRoute?
    /** A route run is going on. */
    val routing: Boolean
    /** The followed line is a shown track (tour code: [RoutesGoSlice.undoNewPlan] asks, the view model knows). */
    val followIsTrack: Boolean
    /** One Undo step of the plan (lane A: PlanEdit). */
    fun undoStep()
    /** Back to Navigate while a tour runs: the tour follows the plan again (tour code; the view model's). */
    fun continueNavigation()
    /** Writes the GPX [gpx] builds (off the main thread) and opens the share sheet; a failure is reported by the host. */
    suspend fun share(name: String, gpx: () -> com.onpath.app.gpx.GpxFile)
    /** Name of a route point added from a place, or null. */
    fun stopName(p: GeoPoint): String?
    /** The start camera keeps away from the position after a shared plan / place was shown. */
    fun holdCameraForSharedPlace()
    /** The map follows the position. */
    var followMe: Boolean
    /** Navigate free look. */
    var freeLookAt: GeoPoint?
}

/**
 * Routes I (cut out of `AppViewModel`, rebuild phase 2 piece 13): My routes' list read again ([refreshTours]), a tour or
 * route tapped in it opened on the map ([openTour]: a planned route becomes the plan, a done tour / track is shown),
 * the shown track ([shown], [closeShown]), a track made a plan ([planFromShown], [planFromLine], [cancelPlanFromTrack])
 * and Delete ([deleteTour]: into the recycle bin). The plan, My routes, Save, the tour box and the plan's route are the
 * other slices, the database is [store], the rest of the app is [host]; the work runs on [scope] (the view model's) with the
 * blocking calls on [io]; [main] is where the list is shown and Delete's follow-up runs.
 */
class RoutesSlice(
    private val plan: PlanDocument,
    private val routes: RoutesState,
    private val save: SaveSlice,
    private val tour: TourController,
    private val planRoute: PlanRouteSlice,
    private val store: ToursStore,
    private val camera: Camera,
    private val host: RoutesHost,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val main: CoroutineDispatcher,
    /** Elapsed real time in ms (the duration of the track fit in the log). */
    private val elapsedMs: () -> Long,
) {
    /** The track shown on the map (an imported GPX, a recorded or saved tour); null = none. The view model writes it too. */
    var shown by mutableStateOf<ShownTrack?>(null)

    private fun sayError(text: String, place: NoticePlace) = host.say(text, NoticeKind.ERROR, place)

    fun refreshTours() {
        // the newest read wins: an older one ending late must not hide a row added since (RoutesState.show)
        val load = routes.beginLoad()
        scope.launchSafe("app", "refreshTours", io) {
            // a done tour's row shows the overview's average speed (owner 2026-11-16) – from the stored times, read once
            val times = store.tourTimes()
            val list = routes.load { t -> host.withMaps(planRoute.withModelTime(t)).let { m ->
                // a recorded tour's followed plan: a round trip for certain when its points end at its start (RoundTrip)
                if (m.kind == TourKind.RECORDED && m.hasPlan) m.copy(roundTrip = runCatching { store.planPoints(m.id) }
                    .onFailure { e -> Problems.warn("routes", "The followed plan's points of tour ${m.id} not read – its row guesses from the track", e) }
                    .getOrNull()?.let { w -> com.onpath.app.routing.PlanEdits.isRoundTrip(w.map { GeoPoint(it.lat, it.lon) }) })
                else m
            }.let { m ->
                // rides taken out: the overview recomputes the times from the kept points – the row does not know them
                val asRecorded = m.rides?.choice.let { it == null || it == com.onpath.app.rides.RideChoice.AS_RECORDED }
                if (m.kind == TourKind.RECORDED && asRecorded)
                    m.copy(avgKmh = com.onpath.app.time.TourReview.averageFromTimes(times[m.id], m.distanceM))
                else m
            } }
            withContext(main) { routes.show(list, load); host.kickThumbs() }
        }
    }

    /**
     * [duringTour] (a plan tapped in My routes during a tour, owner 2026-11-03 / 2026-11-06 without a question): the running tour follows the loaded route – its
     * activity stays, the plan is replaced like an edit during the tour (followEditedPlan), and the app goes to Navigate.
     */
    fun openTour(t: Tour, duringTour: Boolean = false, trackThen: TrackThen = TrackThen.SHOW,
                 /** A stand-in with no stored route behind it (a recorded tour's followed plan): its line. */
                 lineOf: List<List<TrackPoint>>? = null, planned: (() -> Unit)? = null) {
        Problems.trace("routes", "Tour ${t.id} on the map (${if (duringTour) "during the tour, " else ""}then $trackThen)")
        host.syncMarkSeen(t)
        scope.launchSafe("app", "openTour") {
            val segs = lineOf ?: withContext(io) { store.loadSegments(t.id) }
            routes.open = false
            if (RouteRules.replacesPlan(t.kind, t.waypoints.size)) {
                // Back into planning with its via points (recalculated with the current engine).
                if (!duringTour) {
                    planRoute.activityOrNull(t.activity)?.let { planRoute.activity = it; host.rememberActivity(it) } // unknown: the current one stays
                    planRoute.sac = t.sac
                }
                shown = null
                val pts = t.waypoints.map { GeoPoint(it.lat, it.lon) }
                // stop names and POI pins saved with the plan come back too – the pins
                // before the points, so the first route already passes them exactly (recalc reads them at once)
                t.pinned.mapNotNull { pts.getOrNull(it) }.takeIf { it.isNotEmpty() }?.let { plan.pin(it) }
                // during a tour: the loaded route is followed from its point ① (owner 2026-11-03), not from the position
                host.loadedOnTheWay(duringTour && host.touring)
                // saved routes load without routing again (owner 2026-11-07): its legs from the leg store, else its line
                // as a stand-in while the run routes; a route routed before is no "very long plan" question again
                if (!duringTour) loadStoredLine(t, pts, segs)
                if (!duringTour) host.routeEvent(RouteRun.Event.NewPlan) // a new plan: its first route may raise the sheet ([SheetRise])
                save.keepAsDraft("replaced by \"${t.name}\"")
                host.change { clear(); addAll(pts) }
                t.waypoints.forEachIndexed { i, w -> w.name?.let { host.rememberPlaceName(pts[i], it) } }
                // a stand-in (no stored route behind it): an unsaved plan – Los geht's saves it like every new plan
                if (t.id >= 0) host.saved(t.name, t.id, pts) else host.saved(null, -1L, null); host.opened(t, pts)
                camera.fit(segs.flatten().map { it.toGeo() })
                host.syncRoutingCheck(t, pts)
                if (duringTour && host.touring) host.enterMode(AppMode.NAVIGATE)
                planned?.invoke()
            } else {
                // a recorded or imported tour brings its activity, like the import: the T level stays inside the
                // tour's band, not during a tour (the followed route keeps its activity), a change is said in the
                // track panel; an unknown / missing activity keeps the current one
                val choice = SportType.openChoice(t.activity, t.sac, planRoute.activity, planRoute.sac, touring = host.touring)
                // before the track is shown: an activity change clears the panel's line, this one sets it
                if (choice.switch) {
                    if (choice.pick.activity == Activity.HIKE) { planRoute.sac = choice.pick.sac.coerceIn(1, 5); host.rememberSac(planRoute.sac) }
                    planRoute.selectActivity(choice.pick.activity, userChange = false)
                }
                // rides (vehicle detection): the tour as chosen; rides not counted beside it, dropped parts not drawn
                val lines = com.onpath.app.rides.RideView.lines(segs, t.rides).filter { it.part != com.onpath.app.rides.RideView.Part.DROPPED }
                val kept = lines.filter { it.part == com.onpath.app.rides.RideView.Part.KEPT }.map { l -> l.points.map { it.toGeo() } }
                val rides = lines.filter { it.part != com.onpath.app.rides.RideView.Part.KEPT }.map { l ->
                    l.points.map { it.toGeo() } to (if (l.part == com.onpath.app.rides.RideView.Part.LIFT) com.onpath.app.rides.RideKind.LIFT
                        else com.onpath.app.rides.RideKind.VEHICLE)
                }
                showTrack(ShownTrack(t.id, t.name, kept, t.waypoints, t.distanceM, t.ascentM, t.descentM, t.durationS, rides,
                    note = choice.note))
                when (trackThen) {
                    // a done tour tapped during a tour (owner 2026-11-06, no question): exactly what the track sheet's "Tour nachfahren" did, without the sheet
                    TrackThen.FOLLOW -> host.followShownNow()
                    TrackThen.PLAN -> planFromShownNow()
                    TrackThen.SHOW -> Unit
                }
            }
        }
    }

    /**
     * Saved routes load without routing again (owner 2026-11-07, [com.onpath.app.routing.StoredLine],
     * docs/design/long-bike-routes.md "Saved routes"): every leg fresh in the leg store → the plan run takes them (no
     * router run, the same line); else the stored legs / the saved line [segs] as a stand-in for the next run ([RoutesHost.standIn]).
     * The saved line counts only when [t]'s activity and T level are the ones the plan is routed with. Either way the
     * route was routed before: no "very long plan" question for it ([RouteRun.longOk]).
     */
    private suspend fun loadStoredLine(t: Tour, pts: List<GeoPoint>, segs: List<List<TrackPoint>>) {
        if (pts.size < 2) return
        val act = planRoute.activity; val level = planRoute.sac
        val lineFits = planRoute.activityOrNull(t.activity) == act && (act != Activity.HIKE || t.sac == level)
        val line = if (lineFits) segs.flatten().map { it.toGeo() } else emptyList()
        val pins = t.pinned.filter { it in pts.indices }.toSet()
        val found = withContext(io) {
            try {
                val fresh = (0 until pts.size - 1).all { planRoute.legCache.lookup(pts[it], pts[it + 1], act, level) != null }
                fresh to if (fresh) null else com.onpath.app.routing.StoredLine.preview(pts, act, level, pins, planRoute.legCache, line)
            } catch (e: Exception) {
                Problems.warn("routing", "The stored line of \"${t.name}\" could not be read – the route is calculated", e); false to null
            }
        }
        val (fresh, preview) = found
        Problems.trace("routes", "Loading \"${t.name}\": " + when {
            fresh -> "all ${pts.size - 1} legs stored – no routing"
            preview != null -> "stored line shown at once (${preview.stale} legs on older routing data, ${preview.fromLine} from the saved line), routed again"
            else -> "no stored line for these points – routed"
        })
        if (preview != null) host.standIn(pts, act, level, preview)
        if (fresh || preview != null) host.routeEvent(RouteRun.Event.RoutedBefore(pts))
    }

    fun showTrack(s: ShownTrack) {
        // A GPX opened from another app while navigating: show it in Plan (the recording goes on).
        if (host.mode == AppMode.NAVIGATE) host.enterMode(AppMode.PLAN)
        shown = s
        camera.fit(s.points)
    }

    fun closeShown() { shown = null; host.clearHighlight() }

    /**
     * A track is being turned into a plan ([planFromShown]): a modal box with Cancel over everything (owner 2026-11-01 –
     * nothing else at the same time; review routing M3: minutes for a long track, points set meanwhile were overwritten).
     */
    val planFromTrackBusy: Boolean get() = trackToPlan.busy

    fun cancelPlanFromTrack() = trackToPlan.cancel()

    /** The track sheet's "Als Plan übernehmen" ([TrackToPlanState.planFromShown]). */
    fun planFromShown() = trackToPlan.planFromShown()

    /** See [com.onpath.app.ui.routes.unlessUnsaved] (RoutesOpenState.kt). */
    fun unlessUnsaved(what: String, go: () -> Unit) = com.onpath.app.ui.routes.unlessUnsaved(what, go)

    private fun planFromShownNow() = trackToPlan.planFromShownNow()

    /** [track] → a plan (routing/TrackToPlan) with [act] / [level], [parts] = its routing data; [done] true just before the plan replaces the old one, false when nothing changes. */
    fun planFromLine(track: List<GeoPoint>, parts: List<String>, act: Activity, level: Int, done: (Boolean) -> Unit) =
        trackToPlan.planFromLine(track, parts, act, level, done)

    private val trackToPlan = TrackToPlanState(plan, save, object : TrackToPlanPorts {
        override fun say(text: String, kind: NoticeKind, place: NoticePlace) = host.say(text, kind, place)
        override val shown: ShownTrack? get() = this@RoutesSlice.shown
        override fun closeShown() = this@RoutesSlice.closeShown()
        override val activity: Activity get() = planRoute.activity
        override val sac: Int get() = planRoute.sac
        override fun routingParts(track: List<GeoPoint>, act: Activity) = host.routingParts(track, act)
        override fun ensureRoutingData(parts: List<String>) = host.ensureRoutingData(parts)
        override fun change(block: MutableList<GeoPoint>.() -> Unit) = host.change(block)
        override fun rememberPlaceName(p: GeoPoint, name: String) = host.rememberPlaceName(p, name)
    }, CoroutineTrackFitter(scope, planRoute), elapsedMs)

    fun deleteTour(t: Tour) {
        // never the tour being recorded: the service keeps writing into it (the list hides Delete for it too)
        if (host.recordingTourId == t.id) { sayError(Txt.s(Res.string.msg_track_recording_now), NoticePlace.ROUTES); return }
        scope.launchSafe("app", "deleteTour", io) {
            // into the recycle bin (30 days, My routes → ⋮ → Recycle bin); its backup file moves to Deleted/
            val ok = runCatching { routes.toBin(t.id) }
                .onFailure { Problems.error("db", "Moving a tour to the recycle bin failed", it) }.getOrDefault(false)
            if (ok) { host.tourChanged(t.id); host.syncForget(t.uuid); host.syncChanged("moved to the recycle bin") }
            withContext(main) {
                if (!ok) { save.problem = Txt.s(Res.string.msg_tour_delete_failed, t.name); return@withContext }
                if (shown?.tourId == t.id) shown = null
                if (plan.linkId == t.id) host.saved(null, -1, null) // the plan is no longer saved anywhere
                if (tour.finishedTour?.id == t.id) tour.closeFinished()
                refreshTours()
            }
        }
    }
}

/**
 * The app's [TrackFitter]: a coroutine on [scope] that re-traces the track on the routing background thread (the straight-line
 * engine gives null; a failure is reported unless the user cancelled), then calls `got` on the scope's thread.
 */
class CoroutineTrackFitter(private val scope: CoroutineScope, private val planRoute: PlanRouteSlice) : TrackFitter {
    override fun fit(track: List<GeoPoint>, act: Activity, level: Int, cancel: com.onpath.app.routing.RouteCancel,
                     got: (com.onpath.app.routing.TrackToPlan.Result?) -> Unit) {
        scope.launchSafe("app", "planFromLine") {
            val r = withContext(planRoute.threads.background) {
                try {
                    val eng = planRoute.routingEngine()
                    if (eng is com.onpath.app.routing.StraightLineEngine) null
                    else com.onpath.app.routing.TrackToPlan.fit(track, eng, act, level, cancel = cancel)
                } catch (e: Exception) {
                    if (!cancel.isCancelled) Problems.error("routing", "Track could not be turned into a plan", e); null
                }
            }
            got(r)
        }
    }
}
