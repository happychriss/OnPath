package com.onpath.app.ui.plan

import com.onpath.app.data.RoutingDataNotice
import com.onpath.app.debug.Problems
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.PlanGap
import com.onpath.app.domain.RouteRules
import com.onpath.app.domain.RouteSpot
import com.onpath.app.domain.RouteWarnings
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.domain.plan.RunCause
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Planner
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.WaypointSnap
import com.onpath.app.ui.Background
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.Say
import com.onpath.app.ui.SheetRise
import com.onpath.strings.*
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

    /** No map data on this side (a planner without offline maps): nothing is missing, nothing loads. */
    object None : MapDataPort {
        override fun postRoute(r: PlannedRoute, pts: List<GeoPoint>, activity: Activity?) = PostRoute(emptyList(), false, emptyList())
        override fun routingForLine(keys: List<String>) = Unit
        override val routingLoading: Boolean get() = false
        override fun corridorFollowsPlan(line: List<GeoPoint>?, activity: Activity?) = Unit
        override fun routeComputed(line: List<GeoPoint>, corridor: List<com.onpath.app.cells.CellId>?, dest: GeoPoint?, activity: Activity?) = Unit
        override fun freshenAlong(line: List<GeoPoint>, corridor: List<com.onpath.app.cells.CellId>?, activity: Activity?) = Unit
    }
}

/**
 * What [PlanRecalcState] reads of / writes to the rest of the app (main thread). The members that are state of the view model
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
 * What [PlanRecalcState] asks of the neighbours that are not listed (route slice, edit slice, tour edits / follow, map view, plan
 * link, recording): one forwarder each, main thread. The app's adapter goes to the slices; the planner's to its own.
 */
interface PlanRecalcPorts {
    /** What started the run ([RunCause]). */
    val runCauses: RunCause
    val activity: Activity
    val sac: Int
    /** The legs routed so far ([LegCache]); the router's thread reads it. */
    val legCache: LegCache
    fun planFactors(a: Activity): com.onpath.app.time.Factors
    fun withModelTime(r: PlannedRoute, a: Activity, f: com.onpath.app.time.Factors): PlannedRoute
    /** The route before an activity / T level change, compared with the run's result. */
    var activityBefore: PlannedRoute?
    var spotsOpen: Boolean
    var spotLine: List<GeoPoint>?
    var spotsMemo: Pair<Triple<List<GeoPoint>, Activity, Int>, List<RouteSpot>>?
    var routeChangedNotice: String?
    /** The plan sheet's size. */
    var planSize: PlanSize
    fun setSaved(name: String?, id: Long, points: List<GeoPoint>?)
    fun fitFirstRoute(line: List<GeoPoint>, pts: List<GeoPoint>, extraMs: Long)
    /** The router moved the plan's points onto their ways: what a plan link brought follows. */
    fun linkSnapped(routed: List<GeoPoint>, snapped: List<GeoPoint>)
}

/**
 * The tour's side of the route run ([PlanRecalcState]): the app's tour follows the plan, its recording keeps a failed run's line.
 * A planner without a tour passes [None] (laptop's ask 2026-11-20).
 */
interface PlanTourPort {
    /** The tour's follow line of the old plan: stopped when a new run starts. */
    var followCancel: RouteCancel?
    /** The tour follows the plan as edited. */
    fun followEditedPlan()
    /** The plan's baseline of a tour (an activity switched while navigating is not an edit). */
    var planBaseline: List<GeoPoint>?
    /** "Continue navigation": the tour follows the planned route. */
    fun followPlanned(checkNow: Boolean)
    /** A tour is being recorded. */
    val recording: Boolean

    /** No tour (the planner): nothing follows, nothing records. */
    object None : PlanTourPort {
        override var followCancel: RouteCancel?
            get() = null
            set(_) = Unit
        override fun followEditedPlan() = Unit
        override var planBaseline: List<GeoPoint>?
            get() = null
            set(_) = Unit
        override fun followPlanned(checkNow: Boolean) = Unit
        override val recording: Boolean get() = false
    }
}

/**
 * A loaded route's stand-in line ([com.onpath.app.routing.StoredLine]), taken by the next run for exactly these points,
 * activity and T level: on the map at once while the run routes the legs it could not reuse. (`open`: the app's
 * `PlanRecalcSlice.StandIn` is one.)
 */
open class PlanStandIn(val points: List<GeoPoint>, val activity: Activity, val sac: Int, val preview: com.onpath.app.routing.StoredLine.Preview)

/**
 * The plan's route run in stages (rebuild phase 2 piece 9b, cut for the planner): plain state and one plain member per stage; the app
 * strings the stages together with coroutines, the planner with its routing worker and promises.
 *  1. [begin] (main): the synchronous head – the resets, the `Recalc` event, the stand-in line – gives the [Run] or null.
 *  2. [route] / [findReach] (the routing thread; pure: engine, leg cache and cancel signal are passed in).
 *  3. [progress] / [share] / [slow] (main, while it routes), [legsRouted].
 *  4. [taken] (main): the result taken or the failure said, what the tour does next.
 * The state of the run is [PlanRunSlice]'s; the route, its notes and the highlight are the neighbours' (ports, host).
 */
open class PlanRecalcState(
    private val plan: PlanDocument,
    private val planRun: PlanRunSlice,
    private val ports: PlanRecalcPorts,
    /** The tour's side; the planner: [PlanTourPort.None]. */
    private val tour: PlanTourPort,
    /** The map data; the planner: [MapDataPort.None]. */
    private val mapData: MapDataPort,
    private val say: Say,
    private val host: PlanRecalcHost,
    private val background: Background,
    /** Milliseconds of real time since boot (the app: `SystemClock.elapsedRealtime`). */
    private val elapsedMs: () -> Long,
) {
    /** One run, as plain data (it crosses to the routing worker): what [begin] decided and the later stages need. */
    class Run(
        val gen: Int, val pts: List<GeoPoint>, val pins: Set<Int>, val act: Activity, val level: Int,
        val rounds: Int, val askAtLimit: Boolean, val f: com.onpath.app.time.Factors,
        val started: Long, val changeBefore: PlannedRoute?, val standBefore: List<GeoPoint>?,
        /** The plan's hidden via points by index of [pts] (docs/design/detour-stitch.md): routed too, never shown. */
        val anchors: Map<Int, com.onpath.app.routing.Anchors> = emptyMap(),
    )

    /**
     * The visible legs' lines of the route last taken as the plan's own ([taken]): what a point put on a long leg is
     * stitched from (§1). Handed to [PlanDocument.stitchLines] below; [linesFor] gives it back only for exactly the
     * plan it was routed for and the profile it was routed with.
     */
    private var stitched: com.onpath.app.routing.VisibleLines? = null

    /** The lines of the plan [before] under [profile], or null (no route taken for exactly these points and this profile). */
    fun linesFor(before: List<GeoPoint>, profile: com.onpath.app.routing.AnchorProfile): com.onpath.app.routing.VisibleLines? =
        stitched?.takeIf { it.points == before && it.profile == profile }

    /**
     * Lets the plan stitch an edit from [linesFor], under the profile the plan has at that moment (an activity switch
     * leaves the old lines unused). Set at the start of every run ([begin]): lines exist only after a run was taken, so
     * no earlier wiring is needed, and no constructor or init does the work.
     */
    private fun offerLines() {
        plan.stitchLines = { before -> linesFor(before, com.onpath.app.routing.AnchorProfile.of(activity, sac)) }
    }

    /** What [route] gives back: the plan result and the planner's tries line (null = none). */
    class Routed(val result: com.onpath.app.routing.PlanResult, val tries: String?)

    // The names the moved bodies use: each forwards to the state's owner (the view model's old members were forwarders too).
    private val waypoints get() = plan.points
    private fun pinnedIndices(): Set<Int> = plan.pinnedIndices()
    private val linkName: String? get() = plan.linkName
    private val linkId: Long get() = plan.linkId
    private val savedPoints: List<GeoPoint>? get() = plan.savedPoints
    private val runCauses get() = ports.runCauses
    private val activity: Activity get() = ports.activity
    private val sac: Int get() = ports.sac
    private val legCache get() = ports.legCache
    private fun planFactors(a: Activity = activity): com.onpath.app.time.Factors = ports.planFactors(a)
    private fun withModelTime(r: PlannedRoute, a: Activity, f: com.onpath.app.time.Factors): PlannedRoute = ports.withModelTime(r, a, f)
    private var activityBefore: PlannedRoute?
        get() = ports.activityBefore
        set(v) { ports.activityBefore = v }
    private var spotsOpen: Boolean
        get() = ports.spotsOpen
        set(v) { ports.spotsOpen = v }
    private var spotLine: List<GeoPoint>?
        get() = ports.spotLine
        set(v) { ports.spotLine = v }
    private var spotsMemo: Pair<Triple<List<GeoPoint>, Activity, Int>, List<RouteSpot>>?
        get() = ports.spotsMemo
        set(v) { ports.spotsMemo = v }
    private var routeChangedNotice: String?
        get() = ports.routeChangedNotice
        set(v) { ports.routeChangedNotice = v }
    private val routeRun get() = planRun.routeRun
    private val routing: Boolean get() = planRun.routing
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
        get() = ports.planSize
        set(v) { ports.planSize = v }
    private var planBaseline: List<GeoPoint>?
        get() = tour.planBaseline
        set(v) { tour.planBaseline = v }
    private fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) = ports.setSaved(name, id, points)
    private fun followPlanned(checkNow: Boolean = false) = tour.followPlanned(checkNow)
    private fun fitFirstRoute(line: List<GeoPoint>, pts: List<GeoPoint>, extraMs: Long = 0L) = ports.fitFirstRoute(line, pts, extraMs)
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

    /** The stand-in line the next run takes (see [PlanStandIn]). */
    var standIn: PlanStandIn? = null

    /** The route before a run on newer routing data ([routingDataRouteChanged] compares with it); set by the app when it starts such a run. */
    var routingDataBefore: List<GeoPoint>? = null

    /** After a route was computed in Plan: newer map areas along it ([MapDataPort.freshenAlong]) and newer routing data. */
    private fun freshenAfterRoute(line: List<GeoPoint>, corridor: List<com.onpath.app.cells.CellId>?, act: com.onpath.app.routing.Activity) {
        mapData.freshenAlong(line, corridor, act)
        host.checkRoutingDataFor(line)
    }

    /**
     * Every [begin] starts with this (main thread): the app's slice stops the idle settling here (§8.15) – an edit goes first.
     * Nothing in the planner's chain.
     */
    protected open fun beginning() {}

    /**
     * A run's route was TAKEN as the plan's route, successful and current (main thread, after the run is done): the app's
     * slice starts the idle settling of a plan with detour points here. [run] is the run, [res] its result.
     */
    protected open fun routeTaken(run: Run, res: com.onpath.app.routing.PlanResult) {}

    /** The plan's points now (a copy; main thread) – for the slice's settling, which routes the plan as it stands. */
    protected fun planPointsNow(): List<GeoPoint> = waypoints.toList()

    /** The app is in Navigate (the tour or a destination is followed): the slice does not settle then. */
    protected val navigating: Boolean get() = mode == AppMode.NAVIGATE

    /** A tour is recorded ([PlanTourPort.recording]). */
    protected val recordingTour: Boolean get() = tour.recording

    /**
     * The re-run of the plan after the idle settling (§8.15), QUIET: it is not an edit and not a run – no [RouteRun] event, so
     * no history step, no fit, no sheet rise, no "route changed" notice, no wait card, and the tour is not told. It only
     * shows the settled line: the route, its warnings, the lines the next stitch lies on. Main thread. Taken only while the
     * plan still is exactly the run's points and no run began or is going (else the newer run's line wins); false = dropped.
     * Points are never moved by it (no snap): a plan point sits where the run before put it.
     */
    fun takeQuiet(run: Run, res: com.onpath.app.routing.PlanResult): Boolean {
        if (!routeRun.isCurrent(run.gen) || routing || waypoints.toList() != run.pts) return false
        route = withModelTime(res.route, run.act, run.f)
        planResult = res.withNamed(namedStops(run.pts))
        spotsMemo = Triple(res.route.points, run.act, run.level) to res.spots
        rememberLines(run, res)
        return true
    }

    /**
     * Stage 1 (main thread, synchronous): the resets, the `Recalc` event and the stored line shown at once; null = nothing is
     * routed (fewer than two points, or a plan far beyond a day is asked about first). With a [Run] given, the run's
     * [RouteCancel] is [PlanRunSlice.routeCancel]. [askLong] false: a plan beyond a day is routed without the question (the
     * planner's multi-day tour – owner 2026-11-18: its day rows show a long day). Not kept: the host passes the same [askLong]
     * on every [begin] of that plan, also the reruns of [RouteRun.Effect.Rerun] ("Weiter warten", "Plan anyway").
     */
    fun begin(askLong: Boolean = true): Run? {
        runCauses.start()
        beginning()
        offerLines()
        planRun.routeJob?.cancel(); planRun.routeJob = null
        highlight = null; spotLine = null; spotsOpen = false
        val pts = waypoints.toList()
        val pins = pinnedIndices()
        val stand = standIn?.takeIf { it.points == pts && it.activity == activity && it.sac == sac }; standIn = null
        routeCancel?.cancel(); routeCancel = null // an older request stops at once
        planReach = null
        routeProgress = null; routeShare = null
        tour.followCancel?.cancel(); tour.followCancel = null // the tour's follow line of the old plan: a new one comes with this run
        val act = activity; val level = sac
        // what this run is ([RouteRun.Event.Recalc]): fewer than two points → no route; a plan far beyond a day (a point in
        // another country by mistake, owner) is asked about first – nothing is routed or loaded until the answer; else
        // it is routed. A new run (an edit, an activity change …) starts at one time-limit period and drops an open
        // "Weiter warten?"; in Plan the time limit asks (SlowPlan), a run for Navigate keeps the straight lines
        val effects = routeEvent(RouteRun.Event.Recalc(pts, act, planMode = mode == AppMode.PLAN, newPlan = SheetRise.resets(pts.size), askLong = askLong))
        perform(effects)
        val start = effects.filterIsInstance<RouteRun.Effect.StartRouting>().firstOrNull() ?: return null
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
        routeCancel = RouteCancel()
        return Run(gen, pts, pins, act, level, rounds, askAtLimit, f, started, changeBefore, standBefore, plan.anchorsFor(act, level))
    }

    /** Stage 3 (main): the wait card's legs done / to route ([routeProgress]) – only while the run [gen] is the current one. */
    fun progress(gen: Int, done: Int, total: Int) { if (routeRun.isCurrent(gen) && routing) routeProgress = done to total }

    /** Stage 3 (main): the wait bar's [share] – only while the run [gen] is the current one. */
    fun share(gen: Int, share: Float?) { if (routeRun.isCurrent(gen) && routing) routeShare = share }

    /** Stage 3 (main): the run [gen] has been calculating for a while: the Cancel button appears. */
    fun slow(gen: Int) { routeEvent(RouteRun.Event.Slow(gen)) }

    /** Stage 3 (main): every leg routed – the wait window checks the route (RouteShares). */
    fun legsRouted() { routeShare = null }

    /**
     * Stage 4 (main): the run's end. [result] is [route]'s (or its failure), [reach] what [findReach] found, [tries] the planner's
     * tries line, [post] the corridor work of the result, [active] = the run was not cancelled meanwhile.
     */
    fun taken(run: Run, result: Result<com.onpath.app.routing.PlanResult>, reach: com.onpath.app.routing.PlanReach?, tries: String?,
              post: MapDataPort.PostRoute?, active: Boolean) {
        val gen = run.gen; val pts = run.pts; val act = run.act; val level = run.level
        val changeBefore = run.changeBefore; val standBefore = run.standBefore; val started = run.started
        val back = routeEvent(RouteRun.Event.Returned(gen, active))
        if (RouteRun.Effect.ClearProgress in back) { routeProgress = null; routeShare = null }
        if (RouteRun.Effect.Superseded in back) { // superseded or cleared
            Problems.trace("routing", "Route #$gen for ${pts.size} points superseded – result ignored")
            return
        }
        // one line per route: distance, size, profile, each try's time and outcome
        val done = result.getOrNull()?.route
        Problems.trace("routing", "Route #$gen for ${pts.size} points " + (if (result.isSuccess) "ready" else "failed") +
            " after ${elapsedMs() - started} ms (plan now ${waypoints.size} points): " +
            "${act.name}, ${"%.1f".format(Locale.ROOT, com.onpath.app.routing.RouteTimeLimit.straightKm(pts))} km straight" +
            (done?.let { ", ${"%.1f".format(Locale.ROOT, it.distanceM / 1000)} km routed, ${it.points.size} line points, " +
                "${it.legs.count { l -> l.straight }} straight legs" } ?: "") +
            (tries?.let { "; tries: $it" } ?: ""))
        // what the tour does with this result, after it is taken ([RouteRun.Effect.FollowNewRoute] / FollowEditedPlan)
        var after = emptyList<RouteRun.Effect>()
        var takenRes: com.onpath.app.routing.PlanResult? = null
        result.onSuccess { res ->
            val it = res.route
            route = it; planReach = reach; snapToRoute(pts, res, run)
            rememberLines(run, res)
            // the plan's first route: the sheet rises once to its middle step (owner 2026-11-08, [SheetRise] – once per
            // plan is the run's: [RouteRun.sheetRisen])
            after = routeEvent(RouteRun.Event.Succeeded(gen, planMode = mode == AppMode.PLAN,
                sheetCanRise = SheetRise.rises(risen = false, planSize, mode == AppMode.PLAN, tour.recording)))
            val take = after.filterIsInstance<RouteRun.Effect.TakeRoute>().firstOrNull() ?: return@onSuccess
            takenRes = res
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
                stitched = null // no line is shown from this run: a later edit must not stitch from an older one
                if (it is com.onpath.app.routing.SlowPlan.TimeLimit) {
                    // the time limit in Plan: ask (the box stays until answered); the points and the line stay as
                    // they are – no straight lines, no error line
                    Problems.info("routing", "Plan reached its time limit after ${it.rounds} period(s) – asking to wait on")
                    routeEvent(RouteRun.Event.TimeLimit(gen, it.rounds))
                    return
                }
                // no fit of a later route: the points were fitted when they came (link) or are where the user held
                after = routeEvent(RouteRun.Event.Failed(gen))
                if (RouteRun.Effect.ShowFailure !in after) return@onFailure
                Problems.error("routing", "Route calculation failed", it)
                // during a tour one failed run must not take the whole plan line off the map (fix-tour-edit-line.md)
                if (!tour.recording) route = null
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
            RouteRun.Effect.FollowEditedPlan -> tour.followEditedPlan()
            else -> Unit
        }
        routeEvent(RouteRun.Event.Done(gen)) // "calculating" ends once the result is taken
        takenRes?.let { routeTaken(run, it) } // after Done: the idle settling starts only when no run is going
    }

    /**
     * Keeps the lines of the route just taken for the stitch of the next edit ([stitched]). Only while the plan still is
     * the run's (or its snapped form: a snap moves visible points onto the way, never an anchor); else none, and a stitch
     * would have no line to lie on. A route that does not fit the expanded plan (a leg routed without its anchors, §8.5) gives none.
     */
    private fun rememberLines(run: Run, res: com.onpath.app.routing.PlanResult) {
        val now = waypoints.toList()
        // after the snap the plan holds the NAMED result's points (a named stop keeps its place while res.points has it snapped)
        val ours = now == run.pts || (res.moved && (now == res.points || now == planResult?.points))
        val expanded = com.onpath.app.routing.PlanAnchors.expand(run.pts, run.anchors, run.pins)
        val got = if (ours) com.onpath.app.routing.PlanStitch.linesOf(res.route, expanded, com.onpath.app.routing.AnchorProfile.of(run.act, run.level)) else null
        stitched = got?.let { com.onpath.app.routing.VisibleLines(now, it.profile, it.lines) }
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
    private fun snapToRoute(routed: List<GeoPoint>, res0: com.onpath.app.routing.PlanResult, run: Run) {
        if (waypoints.toList() != routed) { planResult = res0; return }
        // the named stops keep their place: their names are read here, on the main thread (a stop named while the
        // route was calculated counts too)
        val res = res0.withNamed(namedStops(routed)).also { planResult = it }
        val far = res.far
        if (far.isNotEmpty()) Problems.trace("routing", "Route does not reach point(s) ${far.sorted().joinToString { "${it + 1}" }}")
        val snapped = res.points.takeIf { res.moved } ?: return
        // detour points of another profile than this run's (the live set, a step of Undo, the multi-day tour) were not routed by it: a
        // point it moves leaves them (§2); those of its own profile stay with the moved point
        val runProfile = com.onpath.app.routing.AnchorProfile.of(run.act, run.level)
        plan.moveSnapped(routed, snapped, runProfile)
        aliasSnappedLegs(routed, snapped, res.activity, res.sac, run)
        if (routed.size >= 2 && !RouteRules.isUnsaved(routed, savedPoints)) plan.relinking { setSaved(linkName, linkId, snapped) } // no save: the saved detour points stay
        ports.linkSnapped(routed, snapped)
        val moves = WaypointSnap.moves(routed, snapped)
        plan.snappedHistory(moves, runProfile)
        // an open menu follows its point to the snapped position (it must still find it for Delete)
        host.menuFollowsSnap(moves)
    }

    /**
     * The points were moved onto the line ([snapToRoute]): the run's legs under the moved points too
     * ([com.onpath.app.routing.LegCache.alias]) – the plan as saved now loads its legs, and the next edit routes only its
     * new legs (before, the first edit after a snap routed every leg again from the moved points).
     */
    private fun aliasSnappedLegs(routed: List<GeoPoint>, snapped: List<GeoPoint>, act: Activity, level: Int, run: Run) {
        if (routed.size != snapped.size) return
        val moved = (0 until routed.size - 1).filter { snapped[it] != routed[it] || snapped[it + 1] != routed[it + 1] }
        if (moved.isEmpty()) return
        if (run.anchors.isNotEmpty()) { // the legs are those of the expanded lists, old and new (detour-stitch.md §8.2)
            val old = com.onpath.app.routing.PlanAnchors.expand(routed, run.anchors, run.pins)
            val new = com.onpath.app.routing.PlanAnchors.expand(snapped, run.anchors, run.pins)
            background.run("aliasSnappedLegs", { legCache.aliasExpanded(old, new, act, level) }) { n ->
                Problems.trace("routing", "$n legs of the plan with detour points kept under their points moved onto the line")
            }
            return
        }
        background.run("aliasSnappedLegs", { moved.count { legCache.alias(routed[it], routed[it + 1], snapped[it], snapped[it + 1], act, level) } }) { n ->
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

    companion object {
        /**
         * Stage 2 (the routing thread; blocking; throws on failure): route, model time, snapped points, gaps, warnings – the one
         * computation the web planner shares. Pure: the engine, the leg cache and the cancel signal are passed in. [onPlanner]
         * gets the planner as soon as it exists (the app reads its tries line even when the run then fails).
         */
        fun route(run: Run, eng: RoutingEngine, legs: LegCache, cancel: RouteCancel, progress: (done: Int, total: Int) -> Unit,
                  onPlanner: (Planner) -> Unit = {}): Routed {
            val gen = run.gen; val pts = run.pts; val act = run.act; val level = run.level
            // a point put in ON the routed line (a night, a stop at a line point – owner 2026-11-20, as the
            // planner does): its leg cut there from the known line, nothing routed for it
            // a plan with detour points is cut too (a point put on the line of a stitched leg, a night in an anchor span): the
            // cut leg is the editing device's stand-in, the idle settling (§8.15, piece W2) brings it back to the router's own
            // answer so every device ends on the same line
            val expanded = com.onpath.app.routing.PlanAnchors.expand(pts, run.anchors, run.pins)
            val cut = if (expanded.hasAnchors) com.onpath.app.routing.LegSplit.seed(legs, expanded, act, level)
                else com.onpath.app.routing.LegSplit.seed(legs, pts, act, level)
            if (cut > 0) Problems.trace("routing", "Route #$gen: $cut leg(s) cut at a point put in on the line – not routed")
            // route, model time, snapped points, gaps, warnings: the one computation the web planner shares
            // (named stops: read on the main thread when the result is taken – snapToRoute → PlanResult.withNamed)
            // askAtLimit: in Plan the time limit asks "Weiter warten?" (SlowPlan); a run for Navigate keeps the straight lines
            val planner = Planner(eng, cancel, askAtLimit = run.askAtLimit, rounds = run.rounds, legs = legs, progress = progress).also(onPlanner)
            val result = com.onpath.app.routing.PlanResult.plan(planner, expanded, act, level, emptySet(), run.f)
            return Routed(result, planner.describeTries().takeIf { it.isNotEmpty() })
        }

        /** [com.onpath.app.routing.PlanReach.find] for [g] (routing thread); null when it could not be worked out. */
        fun findReach(g: PlanGap, act: Activity, level: Int, cancel: RouteCancel, engine: () -> RoutingEngine, elapsedMs: () -> Long): com.onpath.app.routing.PlanReach? {
            val t0 = elapsedMs()
            return try {
                com.onpath.app.routing.PlanReach.find(engine(), g, act, level, cancel).also { r ->
                    Problems.trace("routing", "Point ${g.index + 1} not reached at ${act.name} T$level: " +
                        "${r?.fix ?: "search cancelled / not done"} after ${elapsedMs() - t0} ms")
                }
            } catch (e: Exception) {
                Problems.warn("routing", "What reaches point ${g.index + 1} could not be worked out; the sheet says the missing metres", e)
                null
            }
        }
    }
}
