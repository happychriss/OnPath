package com.onpath.app.ui.plan

import com.onpath.app.debug.Problems
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
    private val FAIL_CAP = 2
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

    // ---------------- idle settling (detour-stitch.md §8.15, piece W2) ----------------

    /** One leg of the settling by its exact ends and profile: what the cap counts. */
    private data class LegId(val a: GeoPoint, val b: GeoPoint, val act: Activity, val sac: Int)

    /** The settling goes on the plan run's own routing thread: a run that starts waits behind it only for the cancel, never beside it. */
    private val settleThread: CoroutineDispatcher get() = routingDispatcher
    // atomic: stopSettle also runs from the cache-clear hook on other threads; a plain null-out could wipe a newer settling's handle
    private val settleJob = java.util.concurrent.atomic.AtomicReference<Job?>(null)
    private val settleCancel = java.util.concurrent.atomic.AtomicReference<RouteCancel?>(null)
    /** true while the settling itself asks for the engine: its own engine start clears the cache and must not cancel it. */
    @Volatile private var ownEngine = false
    /** Failures of a leg in the settling; 2 = skipped (the cap) until new routing data ([capEpoch]) or the anchors are cleared. */
    private val failures = java.util.concurrent.ConcurrentHashMap<LegId, Int>()
    @Volatile private var capEpoch = 0

    /** Stops the settling (main thread or the routing thread): its cancel signal and its coroutine; whatever is written stays. */
    private fun stopSettle() {
        settleCancel.getAndSet(null)?.cancel()
        settleJob.getAndSet(null)?.cancel()
    }

    /**
     * A tour is recorded, followed or Navigate is on. Read between legs on the routing thread: three plain flags, a stale read
     * costs one leg more – the main thread's [takeQuiet] check ([tourRuns]) is the one that decides what is shown.
     */
    private fun tourRuns() = planRoute.touring || recordingTour || navigating

    /** Every edit (every [begin]) stops the settling first – it is the lowest priority; the cache clear stops it too. */
    override fun beginning() {
        stopSettle()
        // set here, not in a constructor or init (no work there): the hook is in place before any run can start an engine
        planRoute.beforeClear = { if (!ownEngine) stopSettle() }
    }

    override fun routeTaken(run: Run, res: com.onpath.app.routing.PlanResult) {
        if (run.anchors.isEmpty()) { failures.clear(); return } // anchors cleared: the cap starts anew; no anchors, no settling
        // not during a tour: the tour follows its line and must not see it shift under the position (the plan's re-run is
        // never told to the tour either); a tour's own edits re-plan and settle after it
        if (tourRuns()) { Problems.trace("routing", "Settling skipped: a tour runs"); return }
        val now = planPointsNow()
        val cache = planRoute.legCache
        if (cache.epoch != capEpoch) { failures.clear(); capEpoch = cache.epoch } // new routing data: every leg may try again
        val expanded = com.onpath.app.routing.PlanAnchors.expand(now, run.anchors, run.pins)
        val act = run.act; val level = run.level
        // legs the taken run drew straight (no route): a cold device gets the same, nothing to align – never asked again
        val noRoute = com.onpath.app.routing.Planner.skipNoRoute(expanded, res.route)
        // the settled line is a re-run of the plan as it stands (its points: those of the run, or snapped onto its line)
        val again = Run(run.gen, now, run.pins, act, level, 1, false, run.f, run.started, null, null, run.anchors)
        val cancel = RouteCancel()
        stopSettle()
        settleCancel.set(cancel)
        // the routing thread is the plan run's own: a run that starts waits behind a leg still routing for the time its cancel
        // takes, never works beside it – so the settling goes only while no run is going
        settleJob.set(scope.launchSafe("app", "settle") {
            val out = withContext(settleThread) {
                exceptions {
                    if (cancel.isCancelled) return@exceptions null
                    ownEngine = true
                    val eng = try { planRoute.routingEngine() } finally { ownEngine = false }
                    if (cancel.isCancelled) return@exceptions null
                    Problems.trace("routing", "Settling started: ${expanded.points.size - 1} legs of the plan with detour points")
                    val s = Planner(eng, cancel, legs = cache).settle(expanded, act, level) { a, b ->
                        tourRuns() || (a to b) in noRoute || (failures[LegId(a, b, act, level)] ?: 0) >= FAIL_CAP // a tour started: no further leg
                    }
                    // a sub-leg that always falls back to the full leg counts like a failing one (it is asked again every time)
                    for ((a, b) in s.failedLegs + s.fellBackLegs) {
                        val n = failures.merge(LegId(a, b, act, level), 1, Int::plus) ?: 1
                        if (n == FAIL_CAP) Problems.warn("routing", "A leg of the plan could not be settled twice – skipped until new routing data or a plan without detour points")
                    }
                    Problems.trace("routing", "Settling: routed ${s.routed} / failed ${s.failed}" + if (s.cancelled) " / cancelled" else "")
                    if (s.routed < 1 || s.cancelled || cancel.isCancelled) return@exceptions null
                    if (tourRuns()) { Problems.trace("routing", "Settling stopped: a tour began"); return@exceptions null }
                    // the quiet re-run comes from the cache ALONE (an engine that refuses every request): nothing is routed again, and
                    // a leg the cache lacks would be straight – a line worse than the taken one – so then nothing is shown
                    Problems.trace("routing", "Settling: the plan is shown again from the settled legs")
                    // a cache miss for any leg that is not a known no-route leg cancels the quiet run's OWN signal (never the settle's or a newer run's):
                    // no fallback without anchors, no worse line – the taken line stays
                    val quietCancel = RouteCancel()
                    val missed = java.util.concurrent.atomic.AtomicBoolean(false) // atomic: the planner may ask legs on more than one thread
                    val cacheOnly = com.onpath.app.routing.CacheOnlyEngine(known = { x, y -> (x to y) in noRoute }, onMiss = { _, _ -> missed.set(true); quietCancel.cancel() })
                    val quiet = try { PlanRecalcState.route(again, cacheOnly, cache, quietCancel, { _, _ -> }) } catch (e: Exception) {
                        if (!missed.get()) throw e
                        Problems.trace("routing", "Settling: a leg is missing from the cache – the settled line is not shown, the line stays as it is")
                        return@exceptions null
                    }
                    if (missed.get()) { Problems.trace("routing", "Settling: a leg is missing from the cache – the settled line is not shown, the line stays as it is"); null } else quiet
                }
            }
            out.onFailure { Problems.warn("routing", "Settling the plan's line failed – the line stays as it is", it) }
            val r = out.getOrNull() ?: return@launchSafe
            withContext(main) {
                if (!cancel.isCancelled && !tourRuns() && !takeQuiet(again, r.result)) Problems.trace("routing", "Settling: a newer run began – the settled line is not shown")
            }
        })
    }

    private val recalcHost = host
    private val planDoc = plan
    private val recordingClient = recording

    /** The detour hint's background step ([PlanDetourCheck]): after a route is shown, on the thread nobody waits for. */
    private val detourCheck = PlanDetourCheck(scope, { planRoute.threads.background }, main)

    /** A new run: the older detour check stops at once and its result goes. */
    override fun newRunStarts() = detourCheck.stop()

    /** The detours of the route now on the map; none while it is calculated or beside a failed leg (the more serious line). */
    fun detours(): List<com.onpath.app.routing.PlanDetour> =
        if (planRun.routing || recalcHost.routeError != null) emptyList() else detourCheck.detoursFor(recalcHost.route, planDoc.points)

    /** What a hold and a tour start reach through [PlanRunSlice.besideRoute]; handed over when a check starts. */
    private val besideRoute = object : BesideRoute {
        /** A hold's measure has the router first; when it is over, a check it stopped looks again at the same route. */
        override fun holdMeasures(running: Boolean) {
            if (running) detourCheck.pause() else if (detourCheck.takePaused()) checkDetours(recalcHost.planResult)
        }
        override fun stop() = detourCheck.stop()
    }

    /** The plan sheet's red line for a detour ("② macht 4,2 km Umweg"), null = none. */
    fun detourText(): String? = com.onpath.app.domain.PlanGaps.detourText(detours())

    /** What a tap on that line shows: the drawn route around the point. */
    fun detourWay(): List<GeoPoint>? = detours().firstOrNull()?.way

    /**
     * Looks for a detour the T level causes, once this run's [routed] result is the one on the map, in Plan only: no routing
     * beside a tour (its re-route needs the router) or a multi-day overview, none after a failed leg.
     */
    private fun checkDetours(routed: com.onpath.app.routing.PlanResult?) {
        val res = recalcHost.planResult ?: return
        if (routed == null || res.route.legs !== routed.route.legs) return // superseded: not this run's result
        if (recalcHost.mode != com.onpath.app.domain.AppMode.PLAN || recordingClient.state.value != null || recalcHost.multiDayOverview) return
        if (recalcHost.routeError != null || res.points != planDoc.points.toList()) return
        planRun.besideRoute = besideRoute
        detourCheck.start(res, recalcHost.planReach) { planRoute.routingEngine() }
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
