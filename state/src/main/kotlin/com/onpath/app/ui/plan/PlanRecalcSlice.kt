package com.onpath.app.ui.plan

import com.onpath.app.data.RoutingDataNotice
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.PlanGap
import com.onpath.app.domain.RouteRules
import com.onpath.app.domain.RouteSpot
import com.onpath.app.domain.RouteWarnings
import com.onpath.app.domain.WaitBar
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.recording.RecordingClient
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Planner
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.WaypointSnap
import com.onpath.app.ui.MapViewSlice
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.Say
import com.onpath.app.ui.SheetRise
import com.onpath.app.ui.sync.PlanLinkFlow
import com.onpath.app.ui.tour.TourEdits
import com.onpath.app.ui.tour.TourFollow
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The map data and routing data the route run asks for (rebuild phase 2 piece 9b). The app implements it over `MapDataFlow`
 * (`:app`, a lazy of the view model: the adapter touches it at the moments the old code did, never at construction).
 */
interface MapDataPort {
    /** What a computed route needs for its corridor: [routingKeys] / [cells] null = that check failed (reported by the app). */
    class PostRoute(val routingKeys: List<String>?, val tilesMissing: Boolean, val cells: List<com.onpath.app.cells.CellId>?)

    /** The corridor work of a result, pure geometry and file checks (`MapDataFlow.postRoute`); blocking, called off the main thread. */
    fun postRoute(r: PlannedRoute, pts: List<GeoPoint>, activity: Activity?): PostRoute
    /** The routing data under a route's corridor loads now. */
    fun routingForLine(keys: List<String>)
    /** Routing data is being fetched. */
    val routingLoading: Boolean
    /** The plan's route settled without a corridor ([line] null) or its corridor follows the plan's line (multi-day): nothing new loads. */
    fun corridorFollowsPlan(line: List<GeoPoint>?, activity: Activity? = null)
    /** The plan's route is computed: its corridor (named after [dest]) loads. */
    fun routeComputed(line: List<GeoPoint>, corridor: List<com.onpath.app.cells.CellId>?, dest: GeoPoint?, activity: Activity?)
    /** Newer map areas along a computed route. */
    fun freshenAlong(line: List<GeoPoint>, corridor: List<com.onpath.app.cells.CellId>?, activity: Activity?)
}

/** How far the router's running search is, 0..1 (`BRouterEngine.searchProgress`, `:app`); null = not known. Asked off the main thread. */
fun interface RouteSearchProgress {
    fun of(engine: RoutingEngine?, thread: Thread): Double?
}

/**
 * What [PlanRecalcSlice] reads of / writes to the rest of the app (main thread). The members that are state of the view model
 * (the route, its error line, the highlight, what reaches the first gap, the last result) are properties here; the names differ
 * on purpose from the view model's functions (`ViewModelInitOrderTest` follows every name an `init` can reach).
 */
interface PlanRecalcHost {
    /** The plan's route (null = none); also written by [PlanRouteSlice.retime]. */
    var route: PlannedRoute?
    /** The one red line of a route that failed (null = none). */
    var routeError: String?
    /** The point the elevation profile's finger marks (null = none). */
    var highlight: GeoPoint?
    /** What reaches the first plan point the route does not reach ([com.onpath.app.routing.PlanReach]); null = all reached / not known. */
    var planReach: com.onpath.app.routing.PlanReach?
    /** The last route result applied. */
    var planResult: com.onpath.app.routing.PlanResult?
    /** The screen: Plan or Navigate (the tour session's). */
    val mode: AppMode
    /** The name of a plan point (a stop the user or a place named); null = none. */
    fun nameOfStop(p: GeoPoint): String?
    /** A multi-day tour's overview is on the map: the corridor follows the plan's line, its map question is the tour's. */
    val multiDayOverview: Boolean
    /** An open point menu follows its point to the snapped position ([PlaceMenusSlice.followSnapped]). */
    fun menuFollowsSnap(moves: Map<GeoPoint, GeoPoint>)
    /** Newer routing data along [line] is looked for. */
    fun checkRoutingDataFor(line: List<GeoPoint>)
}

/**
 * The plan's route run, second part (rebuild phase 2 piece 9b): `recalc` – its synchronous head (the resets, the `Recalc` event,
 * the stored line shown at once) and the coroutine that routes (debounce, the slow timer, the wait bar's poll, the router on its
 * own thread, the result taken or the failure said, what the tour does next) – with `snapToRoute` (the points move onto the way
 * the router used), `aliasSnappedLegs`, `findReach`, `freshenAfterRoute` and the note "newer routing data". The state of the run is
 * [PlanRunSlice]'s; the route, its notes and the highlight are the neighbours' (see the private properties: each forwards to its
 * owner). Moved out of the view model as it was; the view model keeps `recalc()` as a one-line forwarder for the callers in `:app`
 * (the big `init`, the activity restore, the arrival of routing data) and for the hosts of other slices.
 *
 * The head runs synchronously in the caller's turn (the big `init`'s first statement, [PlanEditSlice.change], the tour's
 * re-plan, multi-day). No work in the constructor, no `init`: constructed after [TourEdits] (it reaches `followCancel`) and above
 * the big `init`.
 */
class PlanRecalcSlice(
    private val plan: PlanDocument,
    private val planRun: PlanRunSlice,
    private val planRoute: PlanRouteSlice,
    private val planEdit: PlanEditSlice,
    private val tourEdits: TourEdits,
    private val tourFollow: TourFollow,
    private val map: MapViewSlice,
    private val links: PlanLinkFlow,
    private val recording: RecordingClient,
    private val mapData: MapDataPort,
    private val searchProgress: RouteSearchProgress,
    private val say: Say,
    private val host: PlanRecalcHost,
    private val scope: CoroutineScope,
    private val routingDispatcher: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val main: CoroutineDispatcher,
    private val default: CoroutineDispatcher,
    /** Milliseconds of real time since boot (the app: `SystemClock.elapsedRealtime`). */
    private val elapsedMs: () -> Long,
) {
    // The names the moved bodies use: each forwards to the state's owner (the view model's old members were forwarders too).
    private val waypoints get() = plan.points
    private fun pinnedIndices(): Set<Int> = plan.pinnedIndices()
    private val linkName: String? get() = plan.linkName
    private val linkId: Long get() = plan.linkId
    private val savedPoints: List<GeoPoint>? get() = plan.savedPoints
    private val runCauses get() = planRoute.runCauses
    private val activity: Activity get() = planRoute.activity
    private val sac: Int get() = planRoute.sac
    private val legCache get() = planRoute.legCache
    private val engine: RoutingEngine? get() = planRoute.engine
    private fun routingEngine(): RoutingEngine = planRoute.routingEngine()
    private fun planFactors(a: Activity = activity): com.onpath.app.time.Factors = planRoute.planFactors(a)
    private fun withModelTime(r: PlannedRoute, a: Activity, f: com.onpath.app.time.Factors): PlannedRoute = planRoute.withModelTime(r, a, f)
    private var activityBefore: PlannedRoute?
        get() = planRoute.activityBefore
        set(v) { planRoute.activityBefore = v }
    private var spotsOpen: Boolean
        get() = planRoute.spotsOpen
        set(v) { planRoute.spotsOpen = v }
    private var spotLine: List<GeoPoint>?
        get() = planRoute.spotLine
        set(v) { planRoute.spotLine = v }
    private var spotsMemo: Pair<Triple<List<GeoPoint>, Activity, Int>, List<RouteSpot>>?
        get() = planRoute.spotsMemo
        set(v) { planRoute.spotsMemo = v }
    private var routeChangedNotice: String?
        get() = planRoute.routeChangedNotice
        set(v) { planRoute.routeChangedNotice = v }
    private val routeRun get() = planRun.routeRun
    private val routing: Boolean get() = planRun.routing
    private var routeJob: Job?
        get() = planRun.routeJob
        set(v) { planRun.routeJob = v }
    private var routeCancel: RouteCancel?
        get() = planRun.routeCancel
        set(v) { planRun.routeCancel = v }
    private var routeProgress: Pair<Int, Int>?
        get() = planRun.routeProgress
        set(v) { planRun.routeProgress = v }
    private var routeShare: Float?
        get() = planRun.routeShare
        set(v) { planRun.routeShare = v }
    private fun routeEvent(e: RouteRun.Event): List<RouteRun.Effect> = planRun.routeEvent(e)
    private fun perform(effects: List<RouteRun.Effect>) = planRun.perform(effects)
    private var planSize: PlanSize
        get() = planEdit.planSize
        set(v) { planEdit.planSize = v }
    private var planBaseline: List<GeoPoint>?
        get() = tourFollow.planBaseline
        set(v) { tourFollow.planBaseline = v }
    private fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) = tourFollow.setSaved(name, id, points)
    private fun followPlanned(checkNow: Boolean = false) = tourFollow.followPlanned(checkNow)
    private fun fitFirstRoute(line: List<GeoPoint>, pts: List<GeoPoint>, extraMs: Long = 0L) = map.fitFirstRoute(line, pts, extraMs)
    private val recState get() = recording.state
    private fun sayError(text: String, place: NoticePlace) = say.say(text, NoticeKind.ERROR, place)
    private val mode: AppMode get() = host.mode
    private var route: PlannedRoute?
        get() = host.route
        set(v) { host.route = v }
    private var routeError: String?
        get() = host.routeError
        set(v) { host.routeError = v }
    private var highlight: GeoPoint?
        get() = host.highlight
        set(v) { host.highlight = v }
    private var planReach: com.onpath.app.routing.PlanReach?
        get() = host.planReach
        set(v) { host.planReach = v }
    private var planResult: com.onpath.app.routing.PlanResult?
        get() = host.planResult
        set(v) { host.planResult = v }
    private fun stopName(p: GeoPoint): String? = host.nameOfStop(p)

    private val ROUTING_SLOW_MS = 2_500L
    /** How often the wait bar asks the router how far it is (ms). */
    private val WAIT_POLL_MS = 250L

    /**
     * A loaded route's stand-in line ([com.onpath.app.routing.StoredLine]), taken by the next [recalc] for exactly these
     * points, activity and T level: on the map at once while the run routes the legs it could not reuse.
     */
    class StandIn(val points: List<GeoPoint>, val activity: Activity, val sac: Int, val preview: com.onpath.app.routing.StoredLine.Preview)
    var standIn: StandIn? = null

    /** The route before a run on newer routing data ([routingDataRouteChanged] compares with it); set by the app when it starts such a run. */
    var routingDataBefore: List<GeoPoint>? = null

    /** After a route was computed in Plan: newer map areas along it ([MapDataPort.freshenAlong]) and newer routing data. */
    private fun freshenAfterRoute(line: List<GeoPoint>, corridor: List<com.onpath.app.cells.CellId>?, act: com.onpath.app.routing.Activity) {
        mapData.freshenAlong(line, corridor, act)
        host.checkRoutingDataFor(line)
    }

    /** [com.onpath.app.routing.PlanReach.find] for [g] (routing thread); null when it could not be worked out. */
    private fun findReach(g: PlanGap, act: Activity, level: Int, cancel: com.onpath.app.routing.RouteCancel): com.onpath.app.routing.PlanReach? {
        val t0 = elapsedMs()
        return try {
            com.onpath.app.routing.PlanReach.find(routingEngine(), g, act, level, cancel).also { r ->
                Problems.trace("routing", "Point ${g.index + 1} not reached at ${act.name} T$level: " +
                    "${r?.fix ?: "search cancelled / not done"} after ${elapsedMs() - t0} ms")
            }
        } catch (e: Exception) {
            Problems.warn("routing", "What reaches point ${g.index + 1} could not be worked out; the sheet says the missing metres", e)
            null
        }
    }

    /**
     * From the planning thread ([Planner]'s progress): the wait card's legs done / to route ([routeProgress]) – only while
     * the run [gen] is the current one.
     */
    private fun showProgress(gen: Int, done: Int, total: Int) {
        scope.launchSafe("app", "showProgress", main) { if (routeRun.isCurrent(gen) && routing) routeProgress = done to total }
    }

    fun recalc() {
        runCauses.start()
        routeJob?.cancel(); routeJob = null
        highlight = null; spotLine = null; spotsOpen = false
        val pts = waypoints.toList()
        val pins = pinnedIndices()
        val stand = standIn?.takeIf { it.points == pts && it.activity == activity && it.sac == sac }; standIn = null
        routeCancel?.cancel(); routeCancel = null // an older request stops at once
        planReach = null
        routeProgress = null; routeShare = null
        tourEdits.followCancel?.cancel(); tourEdits.followCancel = null // the tour's follow line of the old plan: a new one comes with this run
        val act = activity; val level = sac
        // what this run is ([RouteRun.Event.Recalc]): fewer than two points → no route; a plan far beyond a day (a point in
        // another country by mistake, owner) is asked about first – nothing is routed or loaded until the answer; else
        // it is routed. A new run (an edit, an activity change …) starts at one time-limit period and drops an open
        // "Weiter warten?"; in Plan the time limit asks (SlowPlan), a run for Navigate keeps the straight lines
        val effects = routeEvent(RouteRun.Event.Recalc(pts, act, planMode = mode == AppMode.PLAN, newPlan = SheetRise.resets(pts.size)))
        perform(effects)
        val start = effects.filterIsInstance<RouteRun.Effect.StartRouting>().firstOrNull() ?: return
        val gen = start.gen; val rounds = start.rounds; val askAtLimit = start.askAtLimit
        // the route before an activity / T level change, compared with this run's result ("Route changed for …")
        val changeBefore = activityBefore.takeIf { start.compareActivity }
        val f = planFactors(act)
        // a loaded route (owner 2026-11-07): its stored line at once while this run routes the legs it could not reuse;
        // replaced by the run's line (said only when the way differs) – never no line while routing
        val standBefore = stand?.let { s ->
            route = withModelTime(s.preview.route, act, f)
            Problems.trace("routing", "Route #$gen: the stored line shown at once (${s.preview.stale} legs on older routing data, " +
                "${s.preview.fromLine} from the saved line) – routed again")
            route?.points
        }
        val started = elapsedMs()
        val cancel = com.onpath.app.routing.RouteCancel().also { routeCancel = it }
        routeJob = scope.launchSafe("app", "recalc") {
            delay(150) // debounce quick taps
            // the Cancel button appears only when it takes a while (no flicker for normal routes)
            val slow = launch { delay(ROUTING_SLOW_MS); routeEvent(RouteRun.Event.Slow(gen)) }
            // the wait bar within a leg (owner 2026-11-05): the router's search, polled off the main thread
            val planThread = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
            val bar = WaitBar()
            val poll = launch(default) {
                while (isActive) {
                    delay(WAIT_POLL_MS)
                    val t = planThread.get() ?: continue
                    val legs = routeProgress ?: continue
                    val share = bar.next(legs.first, legs.second, searchProgress.of(engine, t))
                    withContext(main) { if (routeRun.isCurrent(gen) && routing) routeShare = share }
                }
            }
            var planner: Planner? = null
            var reach: com.onpath.app.routing.PlanReach? = null
            val result = withContext(routingDispatcher) {
                exceptions {
                    val eng = routingEngine()
                    // route, model time, snapped points, gaps, warnings: the one computation the web planner shares
                    // (named stops: read on the main thread when the result is taken – snapToRoute → PlanResult.withNamed)
                    // askAtLimit: in Plan the time limit asks "Weiter warten?" (SlowPlan); a run for Navigate keeps the straight lines
                    com.onpath.app.routing.PlanResult.plan(Planner(eng, cancel, askAtLimit = askAtLimit, rounds = rounds, legs = legCache,
                        progress = { done, total -> planThread.set(Thread.currentThread()); showProgress(gen, done, total) }).also { planner = it },
                        pts, act, level, pins, emptySet(), f)
                }.also { r ->
                    // a stop not reached: what does reach it ("② erst ab T2 erreichbar") – only the missing piece,
                    // after the plan route, on the same thread; the next edit cancels it with the route ([cancel])
                    r.getOrNull()?.gaps?.firstOrNull()?.let { g -> reach = findReach(g, act, level, cancel) }
                }
            }
            slow.cancel(); poll.cancel(); routeShare = null // every leg routed: the wait window checks the route (RouteShares)
            // the corridor work of a result off the main thread
            val post = result.getOrNull()?.let { r -> withContext(default) { mapData.postRoute(r.route, pts, act) } }
            val back = routeEvent(RouteRun.Event.Returned(gen, isActive))
            if (RouteRun.Effect.ClearProgress in back) { routeProgress = null; routeShare = null }
            if (RouteRun.Effect.Superseded in back) { // superseded or cleared
                Problems.trace("routing", "Route #$gen for ${pts.size} points superseded – result ignored")
                return@launchSafe
            }
            // one line per route: distance, size, profile, each try's time and outcome
            val done = result.getOrNull()?.route
            Problems.trace("routing", "Route #$gen for ${pts.size} points " + (if (result.isSuccess) "ready" else "failed") +
                " after ${elapsedMs() - started} ms (plan now ${waypoints.size} points): " +
                "${act.name}, ${"%.1f".format(Locale.ROOT, com.onpath.app.routing.RouteTimeLimit.straightKm(pts))} km straight" +
                (done?.let { ", ${"%.1f".format(Locale.ROOT, it.distanceM / 1000)} km routed, ${it.points.size} line points, " +
                    "${it.legs.count { l -> l.straight }} straight legs" } ?: "") +
                (planner?.describeTries()?.takeIf { it.isNotEmpty() }?.let { "; tries: $it" } ?: ""))
            // what the tour does with this result, after it is taken ([RouteRun.Effect.FollowNewRoute] / FollowEditedPlan)
            var after = emptyList<RouteRun.Effect>()
            result.onSuccess { res ->
                val it = res.route
                route = it; planReach = reach; snapToRoute(pts, res)
                // the plan's first route: the sheet rises once to its middle step (owner 2026-11-08, [SheetRise] – once per
                // plan is the run's: [RouteRun.sheetRisen])
                after = routeEvent(RouteRun.Event.Succeeded(gen, planMode = mode == AppMode.PLAN,
                    sheetCanRise = SheetRise.rises(risen = false, planSize, mode == AppMode.PLAN, recState.value != null)))
                val take = after.filterIsInstance<RouteRun.Effect.TakeRoute>().firstOrNull() ?: return@onSuccess
                if (take.rises) {
                    planSize = SheetRise.TO
                    Problems.trace("plan", "First route of the plan: the sheet rises to ${SheetRise.TO}")
                }
                // a rising sheet: the fit waits until its new height is known (MapScreen hears it once it rests)
                if (take.fit) fitFirstRoute(it.points, pts, if (take.rises) SheetRise.FIT_WAIT_MS else 0L)
                // the route warnings of this result (computed with it, off the main thread): routeSpots reads them
                spotsMemo = Triple(it.points, act, level) to res.spots
                if (changeBefore != null) {
                    activityBefore = null
                    if (RouteWarnings.changedNoticeably(changeBefore.points, changeBefore.distanceM, changeBefore.ascentM,
                            it.points, it.distanceM, it.ascentM)) routeChangedNotice = RouteWarnings.changedText(act, level)
                }
                // routing data missing in the route's corridor (hike 10 km, bike 20 km – OfflineCorridor): it loads now; the notice (plain words, no path)
                // only when a part under a planned point is missing – those make the straight line
                post?.routingKeys?.let { k -> mapData.routingForLine(k) }
                routeError = RoutingDataNotice.forRoute(it.error, post?.tilesMissing == true, mapData.routingLoading)
                // T4: the corridor – named after the plan's destination (round trip: its start) in the loading chip
                // the last point: a round trip closes at its ① (also with stops on the way before the loop)
                val dest = pts.lastOrNull()
                if (host.multiDayOverview) mapData.corridorFollowsPlan(it.points, act) else mapData.routeComputed(it.points, post?.cells, dest, act) // a multi-day tour: its map question (§7)
                if (take.freshen) freshenAfterRoute(it.points, post?.cells, act)
                if (take.viaData) routingDataRouteChanged(it)
                else if (standBefore != null && com.onpath.app.data.RoutingDataCheck.differs(standBefore, it.points))
                    routeChangedNotice = Txt.s(Res.string.msg_route_updated_newer_data)
                // the plan re-computed for an activity switched while navigating: not an edit (Continue keeps the tour)
                if (take.adoptBaseline) planBaseline = it.points
            }
                .onFailure {
                    if (it is com.onpath.app.routing.SlowPlan.TimeLimit) {
                        // the time limit in Plan: ask (the box stays until answered); the points and the line stay as
                        // they are – no straight lines, no error line
                        Problems.info("routing", "Plan reached its time limit after ${it.rounds} period(s) – asking to wait on")
                        routeEvent(RouteRun.Event.TimeLimit(gen, it.rounds))
                        return@launchSafe
                    }
                    // no fit of a later route: the points were fitted when they came (link) or are where the user held
                    after = routeEvent(RouteRun.Event.Failed(gen))
                    if (RouteRun.Effect.ShowFailure !in after) return@onFailure
                    Problems.error("routing", "Route calculation failed", it)
                    // during a tour one failed run must not take the whole plan line off the map (fix-tour-edit-line.md)
                    if (recState.value == null) route = null
                    // our own routing texts only – never an exception's raw text (review Arch 4)
                    routeError = com.onpath.app.debug.UserText.of(it, Txt.s(Res.string.msg_routing_failed))
                    mapData.corridorFollowsPlan(null)
                }
            for (fx in after) when (fx) {
                // "Continue navigation" waited for this result
                RouteRun.Effect.FollowNewRoute ->
                    if (route != null) followPlanned(checkNow = true) else sayError(Txt.s(Res.string.msg_no_new_route), NoticePlace.MAP)
                // an edit of the plan during a tour: the tour follows the new plan (owner rule, no question) – not a plan
                // re-computed for an activity switched while navigating (its baseline)
                RouteRun.Effect.FollowEditedPlan -> tourEdits.followEditedPlan()
                else -> Unit
            }
            routeEvent(RouteRun.Event.Done(gen)) // "calculating" ends once the result is taken
        }
    }

    /** Indices of the named stops of [pts] (they keep their coordinates when the route is snapped). */
    private fun namedStops(pts: List<GeoPoint>): Set<Int> = pts.indices.filter { stopName(pts[it]) != null }.toSet()

    /**
     * Takes the snapped plan points of [res0] ([com.onpath.app.routing.PlanResult.snap]: free points onto the way the
     * router used – owner: no off-road guidance – so marker, stored plan and route line agree; POI-pinned and named
     * stops and a stop the route does not reach or skips keep their coordinates, its gap is shown). Amends the current
     * state (no undo step; history snapshots get the same values) and does not re-route: [route] already
     * belongs to the snapped points. Only for the newest result of exactly these points.
     */
    private fun snapToRoute(routed: List<GeoPoint>, res0: com.onpath.app.routing.PlanResult) {
        if (waypoints.toList() != routed) { planResult = res0; return }
        // the named stops keep their place: their names are read here, on the main thread (a stop named while the
        // route was calculated counts too)
        val res = res0.withNamed(namedStops(routed)).also { planResult = it }
        val far = res.far
        if (far.isNotEmpty()) Problems.trace("routing", "Route does not reach point(s) ${far.sorted().joinToString { "${it + 1}" }}")
        val snapped = res.points.takeIf { res.moved } ?: return
        plan.moveSnapped(routed, snapped)
        aliasSnappedLegs(routed, snapped, res.activity, res.sac)
        if (routed.size >= 2 && !RouteRules.isUnsaved(routed, savedPoints)) setSaved(linkName, linkId, snapped)
        links.snapped(routed, snapped)
        val moves = WaypointSnap.moves(routed, snapped)
        plan.snappedHistory(moves)
        // an open menu follows its point to the snapped position (it must still find it for Delete)
        host.menuFollowsSnap(moves)
    }

    /**
     * The points were moved onto the line ([snapToRoute]): the run's legs under the moved points too
     * ([com.onpath.app.routing.LegCache.alias]) – the plan as saved now loads its legs, and the next edit routes only its
     * new legs (before, the first edit after a snap routed every leg again from the moved points).
     */
    private fun aliasSnappedLegs(routed: List<GeoPoint>, snapped: List<GeoPoint>, act: Activity, level: Int) {
        if (routed.size != snapped.size) return
        val moved = (0 until routed.size - 1).filter { snapped[it] != routed[it] || snapped[it + 1] != routed[it + 1] }
        if (moved.isEmpty()) return
        scope.launchSafe("app", "aliasSnappedLegs", io) {
            val n = moved.count { legCache.alias(routed[it], routed[it + 1], snapped[it], snapped[it + 1], act, level) }
            Problems.trace("routing", "$n of ${moved.size} legs kept under their points moved onto the line")
        }
    }

    /** The plan computed again on newer routing data: say so only when the way changed. */
    private fun routingDataRouteChanged(r: com.onpath.app.routing.PlannedRoute) {
        val before = routingDataBefore ?: return
        routingDataBefore = null
        // the plan sheet's grey "changed" line (a tap shows the whole route), not a message (owner)
        if (com.onpath.app.data.RoutingDataCheck.differs(before, r.points)) routeChangedNotice = Txt.s(Res.string.msg_route_updated_newer_data)
    }

    /**
     * Like runCatching, but only for exceptions (review Arch 9): a cancellation goes on to the coroutine, an Error
     * (OutOfMemoryError) is not taken for a failed route.
     */
    private inline fun <T> exceptions(block: () -> T): Result<T> = try { Result.success(block()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}
