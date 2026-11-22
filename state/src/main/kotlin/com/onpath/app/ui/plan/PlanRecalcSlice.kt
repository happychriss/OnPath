package com.onpath.app.ui.plan

import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.RouteSpot
import com.onpath.app.domain.WaitBar
import com.onpath.app.recording.RecordingClient
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Planner
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.ui.Cancellable
import com.onpath.app.ui.CoroutineBackground
import com.onpath.app.ui.MapViewSlice
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.Say
import com.onpath.app.ui.sync.PlanLinkFlow
import com.onpath.app.ui.tour.TourEdits
import com.onpath.app.ui.tour.TourFollow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's [Cancellable] for the route run: stops the coroutine [job] ([PlanRunSlice.routeJob]; `cancel()` on that same job). */
class JobHandle(val job: Job) : Cancellable {
    override fun cancel() = job.cancel()
}

/** How far the router's running search is, 0..1 (`BRouterEngine.searchProgress`, `:app`); null = not known. Asked off the main thread. */
fun interface RouteSearchProgress {
    fun of(engine: RoutingEngine?, thread: Thread): Double?
}

/** The app's [PlanRecalcPorts]: each member goes to the slice that owns it (read at call time, never at construction). */
private class RecalcPorts(
    private val planRoute: PlanRouteSlice,
    private val planEdit: PlanEditSlice,
    private val tourFollow: TourFollow,
    private val map: MapViewSlice,
    private val links: PlanLinkFlow,
) : PlanRecalcPorts {
    override val runCauses get() = planRoute.runCauses
    override val activity: Activity get() = planRoute.activity
    override val sac: Int get() = planRoute.sac
    override val legCache get() = planRoute.legCache
    override fun planFactors(a: Activity): com.onpath.app.time.Factors = planRoute.planFactors(a)
    override fun withModelTime(r: PlannedRoute, a: Activity, f: com.onpath.app.time.Factors): PlannedRoute = planRoute.withModelTime(r, a, f)
    override var activityBefore: PlannedRoute?
        get() = planRoute.activityBefore
        set(v) { planRoute.activityBefore = v }
    override var spotsOpen: Boolean
        get() = planRoute.spotsOpen
        set(v) { planRoute.spotsOpen = v }
    override var spotLine: List<GeoPoint>?
        get() = planRoute.spotLine
        set(v) { planRoute.spotLine = v }
    override var spotsMemo: Pair<Triple<List<GeoPoint>, Activity, Int>, List<RouteSpot>>?
        get() = planRoute.spotsMemo
        set(v) { planRoute.spotsMemo = v }
    override var routeChangedNotice: String?
        get() = planRoute.routeChangedNotice
        set(v) { planRoute.routeChangedNotice = v }
    override var planSize: PlanSize
        get() = planEdit.planSize
        set(v) { planEdit.planSize = v }
    override fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) = tourFollow.setSaved(name, id, points)
    override fun fitFirstRoute(line: List<GeoPoint>, pts: List<GeoPoint>, extraMs: Long) = map.fitFirstRoute(line, pts, extraMs)
    override fun linkSnapped(routed: List<GeoPoint>, snapped: List<GeoPoint>) = links.snapped(routed, snapped)
}

/** The app's [PlanTourPort]: the tour's edits / follow and the recording (read at call time, never at construction). */
private class RecalcTour(
    private val tourEdits: TourEdits,
    private val tourFollow: TourFollow,
    private val recordingClient: RecordingClient,
) : PlanTourPort {
    override var followCancel: RouteCancel?
        get() = tourEdits.followCancel
        set(v) { tourEdits.followCancel = v }
    override fun followEditedPlan() = tourEdits.followEditedPlan()
    override var planBaseline: List<GeoPoint>?
        get() = tourFollow.planBaseline
        set(v) { tourFollow.planBaseline = v }
    override fun followPlanned(checkNow: Boolean) = tourFollow.followPlanned(checkNow)
    override val recording: Boolean get() = recordingClient.state.value != null
}

/**
 * The plan's route run in the app (rebuild phase 2 piece 9b): [PlanRecalcState] (listed: the stages) with the coroutines –
 * [recalc] is its `begin` and the coroutine that routes (debounce, the slow timer, the wait bar's poll, the router on its own
 * thread, then `taken`). The view model keeps `recalc()` as a one-line forwarder for the callers in `:app` (the big `init`, the
 * activity restore, the arrival of routing data) and for the hosts of other slices.
 *
 * The head runs synchronously in the caller's turn (the big `init`'s first statement, [PlanEditSlice.change], the tour's
 * re-plan, multi-day). No work in the constructor, no `init`: constructed after [TourEdits] (it reaches `followCancel`) and above
 * the big `init`.
 */
class PlanRecalcSlice(
    plan: PlanDocument,
    private val planRun: PlanRunSlice,
    private val planRoute: PlanRouteSlice,
    planEdit: PlanEditSlice,
    tourEdits: TourEdits,
    tourFollow: TourFollow,
    map: MapViewSlice,
    links: PlanLinkFlow,
    recording: RecordingClient,
    private val mapData: MapDataPort,
    private val searchProgress: RouteSearchProgress,
    say: Say,
    host: PlanRecalcHost,
    private val scope: CoroutineScope,
    private val routingDispatcher: CoroutineDispatcher,
    io: CoroutineDispatcher,
    private val main: CoroutineDispatcher,
    private val default: CoroutineDispatcher,
    /** Milliseconds of real time since boot (the app: `SystemClock.elapsedRealtime`). */
    private val elapsedMs: () -> Long,
) : PlanRecalcState(plan, planRun, RecalcPorts(planRoute, planEdit, tourFollow, map, links), RecalcTour(tourEdits, tourFollow, recording), mapData, say, host,
    CoroutineBackground(scope, io), elapsedMs) {
    private val ROUTING_SLOW_MS = 2_500L
    /** How often the wait bar asks the router how far it is (ms). */
    private val WAIT_POLL_MS = 250L

    /**
     * A loaded route's stand-in line ([com.onpath.app.routing.StoredLine]), taken by the next [recalc] for exactly these
     * points, activity and T level: on the map at once while the run routes the legs it could not reuse. A [PlanStandIn].
     */
    class StandIn(points: List<GeoPoint>, activity: Activity, sac: Int, preview: com.onpath.app.routing.StoredLine.Preview) :
        PlanStandIn(points, activity, sac, preview)

    private var routeJob: Job?
        get() = (planRun.routeJob as? JobHandle)?.job
        set(v) { planRun.routeJob = v?.let(::JobHandle) }

    private val recalcHost = host
    private val planDoc = plan
    private val recordingClient = recording

    /** The detour hint's background step ([PlanDetourCheck]): after a route is shown, on the thread nobody waits for. */
    val detourCheck = PlanDetourCheck(scope, { planRoute.threads.background }, main)

    /** A new run: the older detour check stops at once and its result goes. */
    override fun newRunStarts() = detourCheck.stop()

    /**
     * Looks for a detour the T level causes, once this run's [routed] result is the one on the map, in Plan only: no routing
     * beside a tour (its re-route needs the router) or a multi-day overview, none after a failed leg.
     */
    private fun checkDetours(routed: com.onpath.app.routing.PlanResult?) {
        val res = recalcHost.planResult ?: return
        if (routed == null || res.route.legs !== routed.route.legs) return // superseded: not this run's result
        if (recalcHost.mode != com.onpath.app.domain.AppMode.PLAN || recordingClient.state.value != null || recalcHost.multiDayOverview) return
        if (recalcHost.routeError != null || res.points != planDoc.points.toList()) return
        detourCheck.start(res) { planRoute.routingEngine() }
    }

    fun recalc() {
        val run = begin() ?: return
        val gen = run.gen
        // begin() made it just now; never null – if it were, the run would stay "calculating" unseen
        val cancel = planRun.routeCancel ?: run { com.onpath.app.debug.Problems.error("routing", "Route #$gen: no cancel signal after the head – not routed"); return }
        routeJob = scope.launchSafe("app", "recalc") {
            delay(150) // debounce quick taps
            // the Cancel button appears only when it takes a while (no flicker for normal routes)
            val slowTimer = launch { delay(ROUTING_SLOW_MS); slow(gen) }
            // the wait bar within a leg (owner 2026-11-05): the router's search, polled off the main thread
            val planThread = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
            val bar = WaitBar()
            val poll = launch(default) {
                while (isActive) {
                    delay(WAIT_POLL_MS)
                    val t = planThread.get() ?: continue
                    val legs = planRun.routeProgress ?: continue
                    val barShare = bar.next(legs.first, legs.second, searchProgress.of(planRoute.engine, t))
                    withContext(main) { share(gen, barShare) }
                }
            }
            var planner: Planner? = null
            var reach: com.onpath.app.routing.PlanReach? = null
            val result = withContext(routingDispatcher) {
                exceptions {
                    PlanRecalcState.route(run, planRoute.routingEngine(), planRoute.legCache, cancel,
                        progress = { done, total -> planThread.set(Thread.currentThread()); showProgress(gen, done, total) },
                        onPlanner = { planner = it })
                }.also { r ->
                    // a stop not reached: what does reach it ("② erst ab T2 erreichbar") – only the missing piece,
                    // after the plan route, on the same thread; the next edit cancels it with the route ([cancel])
                    r.getOrNull()?.result?.gaps?.firstOrNull()?.let { g ->
                        reach = PlanRecalcState.findReach(g, run.act, run.level, cancel, { planRoute.routingEngine() }, elapsedMs)
                    }
                }
            }
            slowTimer.cancel(); poll.cancel(); legsRouted() // every leg routed: the wait window checks the route (RouteShares)
            // the corridor work of a result off the main thread
            val post = result.getOrNull()?.let { r -> withContext(default) { mapData.postRoute(r.result.route, run.pts, run.act) } }
            taken(run, result.map { it.result }, reach, planner?.describeTries()?.takeIf { it.isNotEmpty() }, post, isActive)
            checkDetours(result.getOrNull()?.result) // after the route is on the map: it never waits for this
        }
    }

    /**
     * From the planning thread ([Planner]'s progress): the wait card's legs done / to route – only while
     * the run [gen] is the current one.
     */
    private fun showProgress(gen: Int, done: Int, total: Int) {
        scope.launchSafe("app", "showProgress", main) { progress(gen, done, total) }
    }

    /**
     * Like runCatching, but only for exceptions (review Arch 9): a cancellation goes on to the coroutine, an Error
     * (OutOfMemoryError) is not taken for a failed route.
     */
    private inline fun <T> exceptions(block: () -> T): Result<T> = try { Result.success(block()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}
