package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.OpenAsk
import com.onpath.app.domain.RouteMenuState
import com.onpath.app.domain.RouteRules
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.Camera
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.TrackThen
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.app.ui.sync.PlanLinkFlow
import com.onpath.app.ui.tour.TourController
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Routes II (cut out of `AppViewModel`, rebuild phase 2 piece 14; the sibling of [RoutesSlice], which it uses): what a tap or
 * a "Los geht's" in My routes does ([requestOpen], [goFromRoutes], [undoNewPlan]), a recorded tour's followed plan
 * ([planFollowedPlan], [goFollowedPlan], [followedPlanLine], [exportFollowedPlan]), the look back at a done tour
 * ([tourReview]), "Als Plan übernehmen" ([planFromTour]) and the plan links in and out ([openSharedPlan], [sharePlan],
 * [shareRoute]).
 *
 * THE BORDER TO THE TOUR: starting or changing what a tour follows (`startNavigation`, `setFollow`, `followShown`,
 * `continueNavigation`, the recording service) is tour code of pieces 16–19 and stays in the view model behind [RoutesHost].
 * This slice decides – may the route be followed now, which plan is the followed one, what Rückgängig undoes – and then calls
 * the host at the same point with the same arguments; the "start" of [goFromRoutes] is the caller's lambda.
 * Not moved (:app-bound: the place index, the demo mark, the backup files): `proposeName`, `nameRecordedTour`, `defaultName`.
 */
class RoutesGoSlice(
    private val plan: PlanDocument,
    private val routes: RoutesState,
    private val tour: TourController,
    private val planRoute: PlanRouteSlice,
    private val save: SaveSlice,
    private val store: ToursStore,
    private val links: PlanLinkFlow,
    private val flow: RoutesSlice,
    private val camera: Camera,
    private val host: RoutesHost,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private fun sayError(text: String, place: NoticePlace) = host.say(text, NoticeKind.ERROR, place)

    /** Navigate mode or a tour recording: no loading into the plan, no Clear. */
    fun navigating(recording: Boolean): Boolean = open.navigating(recording)

    /** State of the ⋮ menu items (RouteRules.menu). */
    fun menuState(recording: Boolean): RouteMenuState =
        RouteRules.menu(host.route != null, plan.points.size, routes.tours.size, navigating(recording), recording)

    fun openRoutes() { flow.refreshTours(); routes.open = true }

    /**
     * Open / load from My routes ([RouteRules.openAsk]). During a tour no question (owner 2026-11-06 22:05 – the three
     * answers "Abbrechen · Tour beenden und laden · Mit diesem Plan weiter" were "super verwirrend"; plan and tour are two
     * things): a planned route is followed at once from its point ① ([OpenAsk.FOLLOW_PLAN_DURING_TOUR]), a done tour /
     * track too ([OpenAsk.FOLLOW_DURING_TOUR]); the recording goes on, Navigate says so (the tour's newPlanNote) and "Rückgängig"
     * brings the plan from before ([undoNewPlan]; it is also under Zuletzt). Ending the tour stays in the pause. Without a
     * tour the current plan goes under "Zuletzt" (owner 2026-11-06) – no "Replace current plan?" any more.
     */
    fun requestOpen(t: Tour, why: String = "My routes tapped") = open.requestOpen(t, why)

    /**
     * "Rückgängig" on the "Neuer Plan" line: the plan from before comes back in one Undo step (loading the new plan / clearing the
     * plan for a followed track was one step) and the tour follows it again – a followed track gives way to it as at
     * "Weiter geht's" (the host's continueNavigation). Only offered when there was a plan before.
     */
    fun undoNewPlan() = open.undoNewPlan()

    /**
     * "Los geht's" in My routes (owner 2026-11-07, [com.onpath.app.domain.routes.RoutesGo]): [t] is loaded as the plan like
     * "Planen" (an unsaved plan on the map goes under Zuletzt – no question), then, once its line is calculated, [start]
     * runs – the plan sheet's own Los geht's (phone check → startNavigation: the plan is followed, Hinführen when the
     * start is far). During a tour it is "Folgen" ([requestOpen]).
     */
    fun goFromRoutes(t: Tour, lineOf: List<List<TrackPoint>>? = null, start: () -> Unit) {
        if (host.touring) { requestOpen(t, "Los geht's in My routes during a tour"); return }
        Problems.trace("routes", "Los geht's on \"${t.name}\" in My routes: loaded as the plan, the tour starts once its line is there")
        goJob?.cancel()
        flow.openTour(t, lineOf = lineOf) {
            val loaded = plan.points.toList()
            goJob = scope.launchSafe("app", "goFromRoutes") {
                val step = withTimeoutOrNull(com.onpath.app.domain.routes.RoutesGo.MAX_WAIT_MS) {
                    androidx.compose.runtime.snapshotFlow {
                        com.onpath.app.domain.routes.RoutesGo.step(loaded, plan.points.toList(), host.routing, host.route != null, host.touring)
                    }.first { it != com.onpath.app.domain.routes.RoutesGo.Step.WAIT }
                } ?: com.onpath.app.domain.routes.RoutesGo.Step.DROP
                if (step == com.onpath.app.domain.routes.RoutesGo.Step.START) {
                    Problems.trace("routes", "Los geht's from My routes: the line of \"${t.name}\" is there – the normal start")
                    start()
                } else Problems.trace("routes", "Los geht's from My routes: no tour started for \"${t.name}\" (changed meanwhile, " +
                    "no line, or waited too long) – the plan sheet's Los geht's stays")
            }
        }
    }

    private var goJob: Job? = null

    /**
     * Gemacht → a recorded tour's "Planen" (owner 2026-11-07): the plan it followed (its last version,
     * [com.onpath.app.data.TourPlan]) is loaded like a planned route's "Planen" – the plan on the map goes under Zuletzt,
     * no question. Its saved route is loaded while that is still the same plan, else the stored copy as a new plan. Not
     * during a tour (the row offers it only without one).
     */
    fun planFollowedPlan(t: Tour) = withFollowedPlan(t, "Planen") { plan, line -> flow.openTour(plan, lineOf = line) }

    /** Gemacht → "Los geht's": the followed plan loaded as at [planFollowedPlan], then the normal start ([goFromRoutes]). */
    fun goFollowedPlan(t: Tour, start: () -> Unit) = withFollowedPlan(t, "Los geht's") { plan, line -> goFromRoutes(plan, line, start) }

    private fun withFollowedPlan(t: Tour, what: String, go: (Tour, List<List<TrackPoint>>?) -> Unit) {
        if (host.touring) { Problems.trace("routes", "$what on tour \"${t.name}\" during a tour: nothing loaded"); return }
        scope.launchSafe("app", "followedPlan") {
            val found = withContext(io) {
                runCatching { followedPlanTour(t) }
                    .onFailure { Problems.error("db", "The plan tour ${t.id} followed could not be read", it) }.getOrNull()
            }
            if (found == null) { save.problem = Txt.s(Res.string.routes_followed_plan_missing); return@launchSafe }
            val (plan, line) = found
            Problems.trace("routes", "$what on tour \"${t.name}\": its followed plan " +
                (if (line == null) "is the saved route \"${plan.name}\" (unchanged)" else "from the tour's copy") +
                ", ${plan.waypoints.size} points")
            go(plan, line)
        }
    }

    /**
     * The plan [t] followed as a planned route to load: its saved route while that is the same plan (points, activity, T
     * level) – loaded as itself, no second route; else a stand-in (id -1) from the stored copy with the copy's line.
     * Null = no copy.
     */
    private fun followedPlanTour(t: Tour): Pair<Tour, List<List<TrackPoint>>?>? {
        val p = store.getTourPlan(t.id) ?: return null
        val key = p.waypoints.map { it.lat to it.lon }
        val source = p.sourceUuid?.let { u ->
            store.listTours().firstOrNull { it.uuid == u && it.kind == TourKind.PLANNED && !it.draft && it.deletedMs == null }
        }?.takeIf { it.waypoints.map { w -> w.lat to w.lon } == key && it.activity == p.activity && it.sac == p.sac }
        if (source != null) return source to null
        val line = p.line.ifEmpty { p.waypoints.map { TrackPoint(it.lat, it.lon, it.ele) } }
        return Tour(-1L, TourKind.PLANNED, p.name ?: t.name, t.createdMs, p.activity, p.sac, p.distanceM, p.ascentM,
            p.descentM, p.durationS, "done", p.waypoints, pinned = p.pinned) to listOf(line)
    }

    /** The overview of a recorded tour: the line of the plan it followed (thin under the track); empty = none. */
    suspend fun followedPlanLine(t: Tour): List<GeoPoint> {
        if (t.kind != TourKind.RECORDED || !t.hasPlan) return emptyList()
        return withContext(io) {
            runCatching { store.getTourPlan(t.id)?.line?.map { GeoPoint(it.lat, it.lon) }.orEmpty() }
                .onFailure { Problems.warn("db", "The followed plan's line of tour ${t.id} not read – the overview shows the track alone", it) }
                .getOrDefault(emptyList())
        }
    }

    /**
     * The look back at a done tour in its large overview (owner 2026-11-15, round 3 of the tour analysis): times, pauses,
     * speeds, the plan comparison, highlights, the profile and its place among the user's done tours of the same activity
     * (and T level for a hike) – [com.onpath.app.time.TourReview]. Null when the points cannot be read (reported); the
     * overview then shows the tour's stored numbers only.
     */
    suspend fun tourReview(t: Tour): com.onpath.app.time.TourReview? {
        if (t.kind != TourKind.RECORDED) return null
        // the comparable tours, read on the main thread (the list is UI state)
        val lengths = routes.tours.filter { o ->
            o.kind == TourKind.RECORDED && o.deletedMs == null && o.state == "done" && o.activity == t.activity &&
                (t.activity != Activity.HIKE.name || o.sac == t.sac)
        }.map { it.distanceM }.let { if (t.state == "done") it else it + t.distanceM }
        return withContext(io) {
            try {
                val all = store.loadSegments(t.id)
                val rides = t.rides // rides taken out as chosen at End tour (vehicle detection): the review is of what was walked / ridden
                val segs = if (rides != null && rides.choice != com.onpath.app.rides.RideChoice.AS_RECORDED)
                    com.onpath.app.rides.RideView.keptSegments(all, rides) else all
                com.onpath.app.time.TourReview.of(segs, planRoute.tourTimes(t), t.durationS, t.distanceM, lengths)
            } catch (e: Exception) {
                Problems.warn("time", "Review of tour ${t.id} not computed – the overview shows its stored numbers only", e)
                null
            }
        }
    }

    /**
     * The overview's share → "Plan exportieren" (owner 2026-11-15): the plan a recorded tour followed, as the one plan
     * export ([com.onpath.app.gpx.PlanGpx]: its line, its named route points, its activity) – "<name> - plan.gpx".
     */
    fun exportFollowedPlan(t: Tour) {
        if (t.kind != TourKind.RECORDED || !t.hasPlan) return
        scope.launchSafe("app", "exportFollowedPlan") {
            val plan = withContext(io) {
                runCatching { store.getTourPlan(t.id) }
                    .onFailure { Problems.warn("db", "The followed plan of tour ${t.id} not read for the export", it) }.getOrNull()
            }
            if (plan == null || !plan.usable) {
                Problems.warn("gpx", "Plan export of tour ${t.id}: no usable followed plan")
                sayError(Txt.s(Res.string.routes_followed_plan_missing), NoticePlace.ROUTES)
                return@launchSafe
            }
            host.share(t.name) {
                val line = plan.line.ifEmpty { plan.waypoints.map { w -> com.onpath.app.gpx.TrackPoint(w.lat, w.lon, w.ele) } }
                com.onpath.app.gpx.PlanGpx.export(t.name, listOf(line),
                    com.onpath.app.gpx.PlanGpx.withPins(plan.waypoints, plan.pinned), plan.activity, plan.sac)
            }
        }
    }

    /** My routes ⋮ → "Als Plan übernehmen" (a done tour / track, owner 2026-11-06 F1): opened, then made a plan of it. */
    fun planFromTour(t: Tour) = flow.unlessUnsaved("\"${t.name}\" as the plan") { flow.openTour(t, trackThen = TrackThen.PLAN) }

    /** Puts a plan from a link into the plan: points, activity, T level, point names, pinned points – a new, unsaved plan. */
    fun openSharedPlan(plan: com.onpath.app.routing.SharedPlan, via: String = "after asking") = open.openSharedPlan(plan, via)

    private val open = RoutesOpenState(plan, routes, save, camera, object : RoutesOpenPorts {
        override val touring: Boolean get() = host.touring
        override val mode: AppMode get() = host.mode
        override fun enterMode(m: AppMode) = host.enterMode(m)
        override fun openTour(t: Tour, duringTour: Boolean, trackThen: TrackThen) = flow.openTour(t, duringTour, trackThen)
        override fun noteNewPlan(undo: Boolean) = tour.noteNewPlan(undo = undo)
        override val newPlanUndo: Boolean? get() = tour.newPlanNote?.undo
        override fun dropNewPlanNote() = tour.dropNewPlanNote()
        override fun undoStep() = host.undoStep()
        override val followIsTrack: Boolean get() = host.followIsTrack
        override fun continueNavigation() = host.continueNavigation()
        override fun clearShown() { flow.shown = null }
        override fun setActivity(a: Activity) { planRoute.activity = a; host.rememberActivity(a) }
        override fun setSac(level: Int) { planRoute.sac = level; host.rememberSac(planRoute.sac) }
        override fun change(block: MutableList<GeoPoint>.() -> Unit) = host.change(block)
        override fun rememberPlaceName(p: GeoPoint, name: String) = host.rememberPlaceName(p, name)
        override fun saved(name: String?, id: Long, points: List<GeoPoint>?) = host.saved(name, id, points)
        override fun linkOpened(title: String?, points: List<GeoPoint>) = links.opened(title, points)
        override fun holdCameraForSharedPlace() = host.holdCameraForSharedPlace()
        override var followMe: Boolean
            get() = host.followMe
            set(v) { host.followMe = v }
        override var freeLookAt: GeoPoint?
            get() = host.freeLookAt
            set(v) { host.freeLookAt = v }
        override fun routeEvent(e: RouteRun.Event) = host.routeEvent(e)
    })

    /**
     * The open plan as a plan.onpath.earth link (PlanLinks) in the share sheet; from two points. No button since 0.13.5
     * (the sheet's ⋯ is gone – My routes → ⋮ → Share plan, [shareRoute]); kept for the plan-link round trip
     * (`CorePlanLinkE2ETest`) and the same link shape.
     */
    fun sharePlan() {
        if (plan.points.size < 2) return
        val pts = plan.points.toList()
        // the route's name: as saved, else the name a link brought while its points are unchanged
        val title = plan.savedName ?: links.title(pts)
        links.share(com.onpath.app.routing.PlanLinks.share(pts, planRoute.activity, planRoute.sac, plan.pinnedIndices(), host::stopName, title), pts.size, title)
    }

    /**
     * My routes → ⋮ → Share plan (planned routes; owner 2026-10-31, was the plan sheet's ⋯): the saved route as a
     * plan.onpath.earth link (PlanLinks) in the share sheet – its points, names, pins, activity and name.
     */
    fun shareRoute(t: Tour) {
        if (t.kind != TourKind.PLANNED || t.waypoints.size < 2) return
        val pts = t.waypoints.map { GeoPoint(it.lat, it.lon, it.ele) }
        val names = t.waypoints.associate { GeoPoint(it.lat, it.lon, it.ele) to it.name }
        val act = planRoute.activityOrNull(t.activity) ?: Activity.HIKE
        links.share(com.onpath.app.routing.PlanLinks.share(pts, act, t.sac, t.pinned, { names[it] }, t.name), pts.size, t.name)
    }
}
