package com.onpath.app.ui.plan

import com.onpath.app.cells.CellId
import com.onpath.app.data.AppSettings
import com.onpath.app.data.RoutingDataNotice
import com.onpath.app.data.TourKind
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.plan.FollowHistory
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.FollowStore
import com.onpath.app.recording.RecState
import com.onpath.app.recording.RecordingWire
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RouteLimits
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.RoutingException
import com.onpath.app.routing.StoredLine
import com.onpath.app.search.Place
import com.onpath.app.testkit.FakeRecordingClient
import com.onpath.app.testkit.FakeRoutingEngine
import com.onpath.app.testkit.JdbcTourRows
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.ManualExecutor
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.testkit.VirtualDelayDispatcher
import com.onpath.app.time.TourTimes
import com.onpath.app.ui.MapViewHost
import com.onpath.app.ui.MapViewSlice
import com.onpath.app.ui.NoticeBoard
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.Position
import com.onpath.app.ui.Say
import com.onpath.app.ui.Camera
import com.onpath.app.ui.ShownTrack
import com.onpath.app.ui.SheetRise
import com.onpath.app.ui.WholeRoute
import com.onpath.app.ui.sync.PlanLinkFlow
import com.onpath.app.ui.sync.PlanLinkHost
import com.onpath.app.ui.tour.FollowPrefs
import com.onpath.app.ui.tour.TourEdits
import com.onpath.app.ui.tour.TourEditsHost
import com.onpath.app.ui.tour.TourFollow
import com.onpath.app.ui.tour.TourFollowHost
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The plan's route run, second part (rebuild phase 2 piece 9b): `recalc` – its synchronous head, the coroutine that routes, the
 * result taken (`snapToRoute`), the failure said, what the tour does next – as flows of the REAL [PlanRecalcSlice] with the real
 * [PlanDocument], [PlanRunSlice], [PlanRouteSlice], [PlanEditSlice], [TourFollow], [TourEdits], [MapViewSlice] and [PlanLinkFlow].
 * Fakes only at the edges, each recording the THREAD it is called on and the state at that moment:
 *  - three threads are held [ManualExecutor]s the test steps (routing, default, io); "main" runs inline on a virtual clock
 *    ([VirtualDelayDispatcher]: the 150 ms debounce, the slow timer, the wait poll, the fit's wait are virtual time);
 *  - the engine is the kit's [FakeRoutingEngine] (wrapped to report where it matched the points, to run a hook per leg or to fail);
 *  - the map data port ([MapDataPort]) and the host ([PlanRecalcHost]) record each call, the thread and the state.
 * The head's order is read through hooks that run synchronously at the moment of each reset: the completion of the old job, the
 * cancel hook of the old router signal and of the tour's follow signal, the host's setters.
 * What these tests do NOT prove: the view model's adapters (`PlanRecalcWiringTest`), the real `MapDataFlow` / `BRouterEngine`.
 */
class PlanRecalcFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private val client = FakeRecordingClient()
    private val db = JdbcTours()
    private val log = ArrayList<String>()
    private val said = ArrayList<String>()

    // ---------------- threads ----------------

    private val where = ArrayList<String>()
    private fun thread() = where.lastOrNull() ?: "test"
    private fun <T> on(name: String, f: () -> T): T { where += name; try { return f() } finally { where.removeAt(where.size - 1) } }

    private val routingEx = ManualExecutor("routing")
    private val defaultEx = ManualExecutor("default")
    private val ioEx = ManualExecutor("io")
    private val pcEx = ManualExecutor("planCopy")
    private val main = VirtualDelayDispatcher { r -> on("main") { r.run() } }
    private val defaultD = VirtualDelayDispatcher { r -> defaultEx.execute { on("default") { r.run() } } }
    private val ioD: CoroutineDispatcher = VirtualDelayDispatcher { r -> ioEx.execute { on("io") { r.run() } } }
    private val scope = CoroutineScope(main + SupervisorJob())
    private fun <T> ui(f: () -> T): T = on("main", f)

    private fun exec(ex: ManualExecutor, name: String): ExecutorCoroutineDispatcher = object : java.util.concurrent.AbstractExecutorService() {
        override fun execute(command: Runnable) = ex.execute { on(name) { command.run() } }
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
    }.asCoroutineDispatcher()

    private fun stepRouting() = routingEx.runAll()
    private fun stepDefault() = defaultEx.runAll()
    private fun stepIo() = ioEx.runAll()
    /** Virtual time moves on all clocks; what falls due runs (the main clock inline, the default clock's work waits for [stepDefault]). */
    private fun advance(ms: Long) { elapsed += ms; main.advance(ms); defaultD.advance(ms) }
    private var elapsed = 0L

    /** A run through to its result: the debounce, the router's thread, the map data's thread, the io thread of the alias. */
    private fun finishRun() {
        advance(150)
        stepRouting(); stepDefault(); stepIo()
    }

    // ---------------- the data ----------------

    private val a = GeoPoint(46.600, 11.100)
    private val b = GeoPoint(46.610, 11.130)
    private val c = GeoPoint(46.620, 11.100)
    private val d = GeoPoint(46.630, 11.130)
    private val letters = mapOf(a to "a", b to "b", c to "c", d to "d")
    private fun ids(l: List<GeoPoint>?) = l?.joinToString("") { letters[it] ?: "." } ?: "-"
    private fun lineOf(pts: List<GeoPoint>): List<GeoPoint> =
        pts.zipWithNext().flatMap { (p, q) -> listOf(p, GeoPoint((p.lat + q.lat) / 2, (p.lon + q.lon) / 2)) } + pts.last()
    private fun routeFor(pts: List<GeoPoint>, distanceM: Double = 4000.0, ascentM: Double = 100.0) =
        PlannedRoute(listOf(PlannedLeg(lineOf(pts), false)), distanceM, ascentM, 50.0, 3000.0, null)
    private var names = mapOf<GeoPoint, String>()
    private val tour7 = db.insertTour(TourKind.RECORDED, "Tour 7", "HIKE", 3, emptyList(), state = "recording", uuid = "uuid-7")

    // ---------------- the engine ----------------

    /** The kit's engine; [snap] says where the router puts a point on a way, [onRoute] runs inside a request (the n-th, from 1). */
    private inner class Engine : RoutingEngine {
        val inner = FakeRoutingEngine()
        var snap: (GeoPoint) -> GeoPoint = { it }
        var onRoute: (Int) -> Unit = {}
        var timeLimit = false
        val threads = ArrayList<String>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult = route(waypoints, activity, sacLimit, RouteLimits(30_000))
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
            threads += thread()
            onRoute(inner.calls.size + 1)
            if (timeLimit) throw RoutingException("too long", timedOut = true)
            return inner.route(waypoints.map(snap), activity, sacLimit, limits).let { it.copy(matched = waypoints.map(snap)) }
        }
    }
    private val engine = Engine()
    private var createFails = false
    private var onCreate: () -> Unit = {}

    // ---------------- the real neighbours ----------------

    private class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }
    private val planStore = MemoryPlanStore()
    private val plan = PlanDocument(planStore)
    private class MemoryPrefs(override var activity: Activity = Activity.HIKE, override var sac: Int = 3) : PlanRoutePrefs
    private val routePrefs = MemoryPrefs()
    private var modeNow = AppMode.PLAN
    private var here: GeoPoint? = null
    private var shownNow: ShownTrack? = null

    private lateinit var planRoute: PlanRouteSlice
    private lateinit var planRun: PlanRunSlice
    private lateinit var planEdit: PlanEditSlice
    private lateinit var files: FollowStore
    private lateinit var follow: TourFollow
    private lateinit var edits: TourEdits
    private lateinit var map: MapViewSlice
    private lateinit var links: PlanLinkFlow
    private lateinit var recalc: PlanRecalcSlice
    private val history = FollowHistory()
    private val rows = JdbcTourRows(db)
    private val followPrefs = object : FollowPrefs { override var followIsTrack: Boolean = false }
    private val fitted = ArrayList<List<GeoPoint>>()

    // the state of the view model the host owns
    private var routeNow: PlannedRoute? = null
    private var errorNow: String? = null
    private var highlightNow: GeoPoint? = null
    private var reachNow: com.onpath.app.routing.PlanReach? = null
    private var resultNow: com.onpath.app.routing.PlanResult? = null
    private var multiDay = false

    /** What the state is at the moment a hook runs (the head's order is read from these). */
    private fun snap() = "job=${planRun.routeJob != null} cancel=${planRun.routeCancel != null} progress=${planRun.routeProgress} share=${planRun.routeShare} " +
        "reach=${reachNow != null} hl=${highlightNow != null} spotLine=${planRoute.spotLine != null} spots=${planRoute.spotsOpen} " +
        "standIn=${if (created) recalc.standIn != null else "-"} follow=${edits.followCancel != null} run=${planRoute.runCauses.run::class.simpleName}"

    /** The port: map data and routing data. Each call with its thread and what the call was given. */
    private var tilesMissing = false
    private var cellsNow: List<CellId>? = listOf(CellId(1, 1))
    private var loadingNow = false
    private val mapData = object : MapDataPort {
        override fun postRoute(r: PlannedRoute, pts: List<GeoPoint>, activity: Activity?): MapDataPort.PostRoute {
            log += "${thread()}: postRoute ${r.points.size}pts for ${pts.size} ${activity?.name}"
            return MapDataPort.PostRoute(listOf("E10_N45"), tilesMissing, cellsNow)
        }
        override fun routingForLine(keys: List<String>) { log += "${thread()}: routingForLine $keys" }
        override val routingLoading: Boolean get() = loadingNow.also { log += "${thread()}: routingLoading read" }
        override fun corridorFollowsPlan(line: List<GeoPoint>?, activity: Activity?) {
            log += "${thread()}: corridorFollowsPlan ${line?.size} ${activity?.name}"
        }
        override fun routeComputed(line: List<GeoPoint>, corridor: List<CellId>?, dest: GeoPoint?, activity: Activity?) {
            log += "${thread()}: routeComputed ${line.size} cells=${corridor?.size} dest=${letters[dest]} ${activity?.name}"
        }
        override fun freshenAlong(line: List<GeoPoint>, corridor: List<CellId>?, activity: Activity?) {
            log += "${thread()}: freshenAlong ${line.size} cells=${corridor?.size} ${activity?.name}"
        }
    }
    private var progressOf: (RoutingEngine?, Thread) -> Double? = { _, _ -> null }

    private val host = object : PlanRecalcHost {
        override var route: PlannedRoute?
            get() = routeNow
            set(v) { log += "${thread()}: route=${v?.points?.size}"; routeNow = v }
        override var routeError: String?
            get() = errorNow
            set(v) { log += "${thread()}: routeError=${v?.take(24)}"; errorNow = v }
        override var highlight: GeoPoint?
            get() = highlightNow
            set(v) { highlightNow = v; log += "${thread()}: highlight=${letters[v]} [${snap()}]" }
        override var planReach: com.onpath.app.routing.PlanReach?
            get() = reachNow
            set(v) { reachNow = v; log += "${thread()}: planReach=${v != null} [${snap()}]" }
        override var planResult: com.onpath.app.routing.PlanResult?
            get() = resultNow
            set(v) { log += "${thread()}: planResult=${v?.points?.let { ids(it) }}"; resultNow = v }
        override val mode: AppMode get() = modeNow
        override fun nameOfStop(p: GeoPoint): String? = names[p]
        override val multiDayOverview: Boolean get() = multiDay
        override fun menuFollowsSnap(moves: Map<GeoPoint, GeoPoint>) { log += "${thread()}: menuFollowsSnap ${moves.size}" }
        override fun checkRoutingDataFor(line: List<GeoPoint>) { log += "${thread()}: checkRoutingDataFor ${line.size}" }
    }

    private fun st() = "[routing=${planRun.routing} slowAsk=${planRun.slowPlanAsk}]"

    private val followHost = object : TourFollowHost {
        override val route: PlannedRoute? get() = routeNow
        override val shown: ShownTrack? get() = shownNow
        override fun placeName(p: GeoPoint): String? = names[p]
        override fun newRouteName(prefix: String) = "$prefix 19 Nov"
        override fun stopsAsSaved(pts: List<GeoPoint>) = pts.mapIndexed { i, p -> com.onpath.app.gpx.GpxWaypoint(p.lat, p.lon, names[p] ?: "stop ${i + 1}") }
        override fun savedTourUuid(id: Long): String? = null
        override fun invalidateSplits() { edits.splitRequests.invalidate() }
        override fun routeEvent(e: RouteRun.Event) = Unit
        override fun tourChanged(id: Long) = Unit
    }
    private val editsHost = object : TourEditsHost {
        override val route: PlannedRoute? get() = routeNow
        override fun replan() { recalc.recalc() }
        override fun mapsForNewPoints(before: List<GeoPoint>, after: List<GeoPoint>) { log += "maps ${ids(before)}→${ids(after)}" }
        override fun noPositionNote(note: com.onpath.app.ui.PlanGps.Note, text: org.jetbrains.compose.resources.StringResource) = Unit
        override fun placeName(p: GeoPoint): String? = names[p]
        override fun newRouteName(prefix: String) = "$prefix 19 Nov"
        override fun routeRunEvent(e: RouteRun.Event) { planRun.routeEvent(e) }
        override fun closeShownTrack() { shownNow = null }
        override fun resumeNavigation() = Unit
    }

    private var created = false
    private fun create() {
        if (created) return
        tmp.create()
        files = FollowStore(tmp.root)
        planRoute = PlanRouteSlice(
            prefs = routePrefs,
            router = object : RouterPort {
                override fun create(): RoutingEngine { onCreate(); if (createFails) throw IllegalStateException("engine cannot start"); return engine }
                override val isRealRouter: Boolean get() = false
                override fun fingerprint() = "fp"
                override val legsDir: File get() = File(tmp.root, "legs")
                override fun partVersion(part: Rd5Parts.Part) = "v1"
            },
            times = object : TimesStore {
                override fun loadSegments(id: Long): List<List<TrackPoint>> = emptyList()
                override fun planGroups(id: Long, distanceM: Double): DoubleArray? = null
                override fun putPlanGroups(id: Long, distanceM: Double, groups: DoubleArray) = Unit
                override fun tourTimes(id: Long): TourTimes? = null
            },
            host = object : PlanRouteHost {
                override val settings: AppSettings get() = AppSettings()
                override var route: PlannedRoute?
                    get() = routeNow
                    set(v) { routeNow = v }
                override val routing: Boolean get() = planRun.routing
                override val longRouteAsk: Double? get() = planRun.longRouteAsk
                override val mode: AppMode get() = modeNow
                override val touring: Boolean get() = client.state.value != null
                override fun recalc() { this@PlanRecalcFlowTest.recalc.recalc() }
                override fun routeEvent(e: RouteRun.Event) { planRun.routeEvent(e) }
                override fun refreshTours() = Unit
                override fun clearShownNote() = Unit
                override fun showWhole(points: List<GeoPoint>) = Unit
            },
            threads = RoutingThreads(routing = exec(routingEx, "routing"), background = exec(ManualExecutor("bg"), "bg"),
                check = exec(ManualExecutor("check"), "check"), hold = exec(ManualExecutor("hold"), "hold")),
        )
        planRun = PlanRunSlice(planRoute.runCauses, object : PlanRunHost {
            override fun points() = plan.points.toList()
            override fun clearRoute() { log += "${thread()}: clearRoute ${st()} [${snap()}]"; routeNow = null; errorNow = null }
            override fun noCorridor() { log += "${thread()}: noCorridor ${st()}"; mapData.corridorFollowsPlan(null) }
            override fun loadDeferredMaps(before: List<GeoPoint>) = Unit
            override fun recalc() { this@PlanRecalcFlowTest.recalc.recalc() }
            override fun cancelRun(why: NotRouted) { planEdit.cancelRun(why) }
        })
        planEdit = PlanEditSlice(plan, planRun, planRoute.runCauses, object : PlanEditHost {
            override fun cancelHoldMeasure(why: String) = Unit
            override fun stopFollowingWhenMapTurns() = Unit
            override fun closePointMenu(why: String) = Unit
            override fun clearFirstPointMenu() = Unit
            override fun clearHoldAddMenu() = Unit
            override fun clearHighlight() = Unit
            override fun removePin(why: String) = Unit
            override fun menuAction(what: String): Int? = null
            override fun setRoutingSpot(spot: GeoPoint?) = Unit
            override fun loadMapsForNewPoints(before: List<GeoPoint>) = editsHost.mapsForNewPoints(before, plan.points.toList())
            override fun keepAsDraft(why: String) = Unit
            override fun routeLine(): List<GeoPoint>? = routeNow?.points
            override fun recalc() = editsHost.replan()
            override fun restoreActivity(c: PlanStatus.Cause.ActivityChange) = Unit
            override fun planChanged() = Unit
            override fun myPosition(): GeoPoint? = here
            override fun touring(): Boolean = client.state.value != null
            override fun following(): Boolean = follow.followLine != null
            override fun followIsTrack(): Boolean = follow.followIsTrack
            override fun followsLine(line: List<GeoPoint>): Boolean = follow.followLine == line
            override fun followFromPlan(): Boolean = follow.followFromPlan
            override fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>) = edits.splitOf(before, after)
            override fun sayNoDestination() = Unit
            override fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean) = edits.oneLeftGoesFromHere(before, mayEmpty)
            override fun endGuidanceIfPlanEmpty() = edits.endGuidanceIfPlanEmpty()
            override fun clearFollow() = follow.clearFollow()
            override fun guidanceEndedByEmptyPlan() { follow.guidanceEndedByEmptyPlan = true }
            override fun stopName(p: GeoPoint): String? = names[p]
            override suspend fun pointNames(): PointNames = PointNames { null }
            override var searchMark: Place? = null
        }, scope, io = main)
        follow = TourFollow(plan, planRoute, client, files, followPrefs, rows, followHost, scope, scopeHeld())
        edits = TourEdits(plan, planEdit, planRoute, follow, client, Position { here }, history, editsHost,
            Say { text, kind, place -> said += "$kind/$place: $text" }, scope, planRoute.threads.routing)
        map = MapViewSlice(
            NoticeBoard(routesOpen = { false }, settingsOpen = { false }, mapShown = { modeNow == AppMode.PLAN }),
            object : Camera {
                override fun fit(points: List<GeoPoint>) { log += "${thread()}: fit ${points.size} t=${main.now}"; fitted += points }
                override fun centerOn(point: GeoPoint, zoom: Double) = Unit
                override fun panTo(point: GeoPoint) = Unit
            },
            { here }, object : MapViewHost {
                override val mode: AppMode get() = modeNow
                override val activity: Activity get() = planRoute.activity
                override val headingUp: Boolean get() = false
                override fun setHeadingUp(on: Boolean) = Unit
                override var navStartZoom: Double? = null
                override val routePoints: List<GeoPoint>? get() = routeNow?.points
                override val waypoints: List<GeoPoint> get() = plan.points
                override val shown: ShownTrack? get() = shownNow
                override val followLine: List<GeoPoint>? get() = follow.followLine
                override val navOriginal: List<GeoPoint>? get() = null
                override val touring: Boolean get() = client.state.value != null
                override val keepCameraUntilMs: Long get() = 0L
                override fun highlight(at: GeoPoint?) = Unit
                override fun closeSpots() = Unit
                override fun spotLine(piece: List<GeoPoint>) = Unit
                override fun appReturned(inPlan: Boolean) = Unit
                override fun appLeft() = Unit
            },
            scope, uptimeMs = { main.now }, elapsedMs = { main.now })
        links = PlanLinkFlow(object : PlanLinkHost {
            override fun backToMap() = Unit
            override fun sayError(text: String, place: NoticePlace) = Unit
            override fun receivePairing(secret: ByteArray, via: String) = Unit
            override fun openSyncSettings() = Unit
            override suspend fun startModeKnown() = Unit
            override fun navigating(): Boolean = false
            override fun planPoints(): List<GeoPoint> = plan.points.toList()
            override fun savedPoints(): List<GeoPoint>? = plan.savedPoints
            override fun open(plan: com.onpath.app.routing.SharedPlan, via: String) = Unit
            override fun share(link: String, title: String?) = Unit
            override var storedLinkPoints: List<GeoPoint>? = null
        }, scope)
        recalc = PlanRecalcSlice(
            plan = plan, planRun = planRun, planRoute = planRoute, planEdit = planEdit, tourEdits = edits, tourFollow = follow,
            map = map, links = links, recording = client, mapData = mapData,
            searchProgress = RouteSearchProgress { eng, t -> progressOf(eng, t).also { log += "${thread()}: searchProgress -> $it" } },
            say = Say { text, kind, place -> said += "$kind/$place: $text" }, host = host,
            scope = scope, routingDispatcher = planRoute.threads.routing, io = ioD, main = main, default = defaultD,
            elapsedMs = { elapsed },
        )
        client.onSend = { cmd -> log += "${thread()}: send ${cmd.action.substringAfterLast('.')} ${st()}" }
        created = true
    }

    private fun scopeHeld(): CoroutineDispatcher = VirtualDelayDispatcher { r -> pcEx.execute(r) }

    @After fun close() { scope.cancel(); db.close() }

    private fun plan(vararg p: GeoPoint) { create(); plan.replace(p.toList()) }
    /** The caller's turn: the main thread asks for the run (the view model's forwarder, a `change`, the init). */
    private fun recalcNow() = ui { recalc.recalc() }
    private fun logOf(prefix: String) = log.filter { it.contains(prefix) }
    private fun recState(id: Long = tour7) = RecState(
        tourId = id, paused = false, distanceM = 100.0, ascentM = 0.0, descentM = 0.0, elapsedS = 60, gpsLost = false, offRouteM = null,
        offRouteAlert = false, followName = null, track = listOf(listOf(a, b)), nav = null)

    // ======================================================================================================================
    // the head: the resets and their order
    // ======================================================================================================================

    /**
     * Everything a run was holding is let go before the new one starts, in the old order. Each hook runs at the moment of its reset:
     * the old job's completion, the old router signal's cancel hook, the tour's follow signal's cancel hook, the host's setters.
     */
    @Test fun theHeadLetsGoOfTheOldRunInItsOrder() {
        plan(a, b); ui { recalc.recalc() } // a run is under way: job + router signal
        val oldJob = (planRun.routeJob as JobHandle).job; val oldCancel = planRun.routeCancel!!
        highlightNow = c; reachNow = com.onpath.app.routing.PlanReach(1, com.onpath.app.routing.PlanReach.Fix.None, emptyList())
        planRoute.spotLine = listOf(a, b); planRoute.spotsOpen = true
        planRun.routeProgress = 1 to 3; planRun.routeShare = 0.4f
        recalc.standIn = PlanRecalcSlice.StandIn(listOf(a, b), Activity.HIKE, 3, StoredLine.Preview(routeFor(listOf(a, b)), 0, 0))
        val followSignal = RouteCancel().also { edits.followCancel = it }
        planRoute.runCauses.pointEdit()
        log.clear()
        oldJob.invokeOnCompletion { log += "job cancelled [${snap()}]" }
        oldCancel.onCancel { log += "router signal [${snap()}]" }
        followSignal.onCancel { log += "follow signal [${snap()}]" }
        planRoute.runCauses.pointEdit() // the cause start() takes (pointEdit again: the first run started above consumed it)

        recalcNow()

        // the cause is started first (the job's hook already sees the run's cause); the old job is cancelled with its handle still set
        // and nothing else reset (highlight, spots, router signal, the wait card, the follow signal all still there)
        val job = log.first { it.startsWith("job cancelled") }
        assertEquals("job cancelled [job=true cancel=true progress=(1, 3) share=0.4 reach=true hl=true spotLine=true spots=true standIn=true follow=true run=PointEdit]", job)
        // then the highlight, the spot line and the spots go (the host's setter sees the spot line still there: highlight first)
        val hl = log.first { it.contains("highlight=") }
        assertEquals("main: highlight=null [job=false cancel=true progress=(1, 3) share=0.4 reach=true hl=false spotLine=true spots=true standIn=true follow=true run=PointEdit]", hl)
        // the stand-in is taken (and cleared) and the router signal fires: spot line / spots are gone, the stand-in is gone, the job handle is gone
        val router = log.first { it.startsWith("router signal") }
        assertEquals("router signal [job=false cancel=true progress=(1, 3) share=0.4 reach=true hl=false spotLine=false spots=false standIn=false follow=true run=PointEdit]", router)
        // the router signal's handle is cleared right after it fired; then what reaches the first gap goes – the wait card is still there
        val reach = log.first { it.contains("planReach=") }
        assertEquals("main: planReach=false [job=false cancel=false progress=(1, 3) share=0.4 reach=false hl=false spotLine=false spots=false standIn=false follow=true run=PointEdit]", reach)
        // the wait card goes before the tour's follow signal fires
        val fol = log.first { it.startsWith("follow signal") }
        assertEquals("follow signal [job=false cancel=false progress=null share=null reach=false hl=false spotLine=false spots=false standIn=false follow=true run=PointEdit]", fol)
        assertNull(edits.followCancel)
        // and the order of these five is the log's order
        val order = listOf("job cancelled", "main: highlight=", "router signal", "main: planReach=", "follow signal").map { p -> log.indexOfFirst { it.startsWith(p) } }
        assertEquals(order.sorted(), order)
        assertTrue(oldJob.isCancelled); assertTrue(oldCancel.isCancelled); assertTrue(followSignal.isCancelled)
    }

    @Test fun fewerThanTwoPointsRouteNothingAndTheHeadsResetsAreDoneWhenTheEventsEffectsRun() {
        plan(a)
        planRun.routeProgress = 2 to 3; planRun.routeShare = 0.2f; planRoute.spotsOpen = true
        highlightNow = b; reachNow = com.onpath.app.routing.PlanReach(1, com.onpath.app.routing.PlanReach.Fix.None, emptyList())
        recalc.standIn = PlanRecalcSlice.StandIn(listOf(a), Activity.HIKE, 3, StoredLine.Preview(routeFor(listOf(a, b)), 0, 0))
        val followSignal = RouteCancel().also { edits.followCancel = it }
        routeNow = routeFor(listOf(a, b)); errorNow = "old error"
        log.clear()
        recalcNow()
        // the Recalc event's effects (the line and its error go, no corridor) come after every reset
        assertEquals("main: clearRoute [routing=false slowAsk=null] [job=false cancel=false progress=null share=null reach=false hl=false spotLine=false spots=false standIn=false follow=false run=Other]",
            log.first { it.contains("clearRoute") })
        assertTrue("the tour's follow signal fired", followSignal.isCancelled)
        assertNull(routeNow); assertNull(errorNow)
        // the early return: nothing was launched, no handle is held, no engine was asked, nothing was traced about a run
        assertNull(planRun.routeJob); assertNull(planRun.routeCancel)
        advance(10_000); stepRouting(); stepDefault(); stepIo()
        assertEquals(0, engine.inner.calls.size)
        assertEquals(emptyList<String>(), problems.traced("routing").filter { it.startsWith("Route #") })
        assertEquals(0, routingEx.size + defaultEx.size + ioEx.size)
        assertFalse(planRun.routing)
    }

    @Test fun theHeadAsksForARunWithItsHandlesSetAndNothingFiredBeforeTheDebounce() {
        plan(a, b)
        recalcNow()
        assertTrue(planRun.routing)
        assertNotNull(planRun.routeJob); assertNotNull(planRun.routeCancel)
        assertFalse("the signal is fresh", planRun.routeCancel!!.isCancelled)
        // the debounce: 150 ms before anything is routed
        advance(149); stepRouting()
        assertEquals(0, engine.inner.calls.size)
        advance(1); stepRouting()
        assertEquals(1, engine.inner.calls.size)
        assertEquals("the router runs on its own thread", listOf("routing"), engine.threads)
    }

    // ======================================================================================================================
    // the stored line shown at once
    // ======================================================================================================================

    private fun standIn(pts: List<GeoPoint>, act: Activity = Activity.HIKE, level: Int = 3, line: List<GeoPoint> = lineOf(pts), stale: Int = 2, fromLine: Int = 1) {
        recalc.standIn = PlanRecalcSlice.StandIn(pts, act, level,
            StoredLine.Preview(PlannedRoute(listOf(PlannedLeg(line, false)), 1234.0, 100.0, 50.0, 3000.0, null), stale, fromLine))
    }

    @Test fun aLoadedRoutesStoredLineIsShownAtOnceThenReplacedByTheRunsLine() {
        plan(a, b); create()
        standIn(listOf(a, b))
        recalcNow()
        // at once, before the debounce: the stored line with the model time of THIS run's pace
        assertEquals(lineOf(listOf(a, b)), routeNow?.points)
        assertEquals(listOf("Route #1: the stored line shown at once (2 legs on older routing data, 1 from the saved line) – routed again"),
            problems.traced("routing").filter { it.startsWith("Route #1:") })
        assertNull("taken once", recalc.standIn)
        assertEquals(0, engine.inner.calls.size)
        finishRun()
        assertEquals("the run's line replaces it", engine.inner.calls.size, 1)
        assertTrue(routeNow!!.distanceM != 1234.0)
    }

    @Test fun aStandInForOtherPointsOrAnotherActivityOrLevelIsDroppedNotShown() {
        plan(a, b); create()
        for (s in listOf(
            { standIn(listOf(a, c)) }, { standIn(listOf(a, b), act = Activity.BIKE_TOURING) }, { standIn(listOf(a, b), level = 4) },
        )) {
            routeNow = null; s()
            recalcNow()
            assertNull("not shown", routeNow)
            assertNull("and gone: it is for the next run of exactly those points only", recalc.standIn)
        }
        assertEquals(emptyList<String>(), problems.traced("routing").filter { it.contains("stored line shown") })
    }

    @Test fun aStoredLineThatDiffersFromTheRunsLineSaysTheDataIsNewerAndOneThatDoesNotSaysNothing() {
        plan(a, b); create()
        // a very different stored line (the way changed on newer routing data)
        standIn(listOf(a, b), line = listOf(a, GeoPoint(46.7, 11.4), b))
        recalcNow(); finishRun()
        assertEquals(Txt.s(Res.string.msg_route_updated_newer_data), planRoute.routeChangedNotice)
        // the same line: nothing is said
        planRoute.routeChangedNotice = null
        standIn(listOf(a, b), line = engine.inner.route(listOf(a, b), Activity.HIKE, 3).points)
        recalcNow(); finishRun()
        assertNull(planRoute.routeChangedNotice)
    }

    // ======================================================================================================================
    // superseded runs and progress
    // ======================================================================================================================

    @Test fun twoQuickEditsTheFirstRunNeverReachesTheRouterAndItsHandlesAreFired() {
        plan(a, b); create()
        recalcNow()
        val first = planRun.routeCancel!!; val firstJob = (planRun.routeJob as JobHandle).job
        advance(100) // inside the debounce
        plan.replace(listOf(a, b, c))
        recalcNow()
        assertTrue("the older request stops at once", first.isCancelled); assertTrue(firstJob.isCancelled)
        assertTrue("a new signal for the new run", planRun.routeCancel !== first)
        assertFalse("the new one is not cancelled", planRun.routeCancel!!.isCancelled)
        finishRun()
        // the router was asked only for the second run's legs (the plan has two legs, one request each)
        assertEquals(listOf(listOf(a, b), listOf(b, c)), engine.inner.calls.map { it.waypoints })
        assertEquals(c, routeNow!!.points.last())
        assertFalse(planRun.routing)
        // the first run's result was never traced as a result
        assertEquals(emptyList<String>(), problems.traced("routing").filter { it.startsWith("Route #1 ") })
        assertEquals(1, problems.traced("routing").count { it.startsWith("Route #2 for 3 points ready") })
    }

    @Test fun aRunSupersededWhileItsRouterWorksTakesNothingWhenItReturnsAndTheNewerRunIsTaken() {
        plan(a, b); create()
        recalcNow(); advance(150) // the first run has asked its router thread
        val firstCancel = planRun.routeCancel!!
        routeNow = routeFor(listOf(a, b), distanceM = 777.0)
        plan.replace(listOf(a, b, c))
        recalcNow() // the second run supersedes it: the first is cancelled
        assertTrue(firstCancel.isCancelled)
        stepRouting() // the first run's router task is run now (the router stops at once); its coroutine resumes cancelled
        stepDefault()
        assertEquals("nothing of the first run was taken", 777.0, routeNow!!.distanceM, 0.0)
        assertEquals(emptyList<String>(), log.filter { it.contains("postRoute") })
        assertTrue("the newer run goes on", planRun.routing)
        finishRun()
        assertFalse(planRun.routing)
        assertEquals(c, routeNow!!.points.last())
        assertEquals(emptyList<String>(), problems.traced("routing").filter { it.startsWith("Route #1 ") })
    }

    /**
     * The branch "superseded or cleared": a result whose generation is not the newest. A newer generation without the old job being
     * cancelled (the user's Cancel performs StopRun first, an edit cancels in the head) is only reached by feeding the event – the
     * branch is a guard; its text and what it leaves alone are pinned here.
     */
    @Test fun aResultOfAnOlderGenerationIsIgnoredWithItsTraceAndTouchesNothing() {
        plan(a, b); create()
        routeNow = routeFor(listOf(a, b), distanceM = 777.0)
        engine.onRoute = { planRun.routeEvent(RouteRun.Event.Unrouted(NotRouted.STOPPED)) } // a newer generation while it routes
        recalcNow(); finishRun()
        assertEquals(listOf("Route #1 for 2 points superseded – result ignored"), problems.traced("routing").filter { it.contains("superseded") })
        assertEquals("the line is the one that was there", 777.0, routeNow!!.distanceM, 0.0)
        assertEquals(emptyList<String>(), log.filter { it.contains("routeComputed") || it.contains("freshenAlong") || it.contains("planResult") })
        assertEquals(emptyList<String>(), problems.traced("routing").filter { it.contains(" ready ") || it.contains(" failed ") })
        assertEquals("not shown as a result", NotRouted.STOPPED, planRun.notRouted)
    }

    @Test fun theWaitCardShowsTheLegsOfTheRunAndGoesWhenItReturns() {
        plan(a, b, c); create()
        val seen = ArrayList<String>()
        engine.onRoute = { n -> seen += "leg $n progress=${planRun.routeProgress} thread=${thread()}" }
        recalcNow(); finishRun()
        // the planner reports (0 of 2) before the first leg and (1 of 2) after it: the card shows it (on the main thread, at once)
        assertEquals(listOf("leg 1 progress=(0, 2) thread=routing", "leg 2 progress=(1, 2) thread=routing"), seen)
        assertNull("gone when the run returned", planRun.routeProgress)
        assertNull(planRun.routeShare)
    }

    /**
     * The same guards with the generation changed and the run still "calculating" (the event of a newer run fed without its head's
     * cancels – the thread hops of the poll and of the progress are where a stale value could still arrive): neither the leg
     * count nor the bar's share of the old generation shows.
     */
    @Test fun progressAndTheBarsShareAreOnlyForTheCurrentGeneration() {
        plan(a, b, c); create()
        progressOf = { _, _ -> 0.5 }
        val seen = ArrayList<String>()
        engine.onRoute = { n ->
            if (n == 1) planRun.routeEvent(RouteRun.Event.Recalc(listOf(a, b, c), Activity.HIKE, planMode = true, newPlan = false))
            if (n == 2) {
                stepDefault(); advance(250); stepDefault() // the poll's pass: the planner's thread is known, the card has legs
                seen += "progress=${planRun.routeProgress} share=${planRun.routeShare}"
            }
        }
        recalcNow(); advance(150); stepRouting()
        // the first leg's (0 of 2) came before the new generation and showed; the (1 of 2) after it must not replace it, nor the share appear
        assertEquals(listOf("progress=(0, 2) share=null"), seen)
        // the poll did ask the router (off the main thread)
        assertEquals(listOf("default: searchProgress -> 0.5"), log.filter { it.contains("searchProgress") })
    }

    /** The wait bar moves within one long leg (owner 2026-11-05): the router is asked off the main thread, for the plan's thread only. (From FollowLineLegsTest.theWaitBarAsksTheRouterOffTheMainThread, test clean-up G05.) */
    @Test fun theWaitBarIsPolledOffTheMainThreadAndItsShareGrowsWithinTheLegAndIsClearedWhenEveryLegIsRouted() {
        plan(a, b, c); create()
        val askedFor = ArrayList<Thread>()
        progressOf = { _, t -> askedFor += t; 0.5 }
        val routedOn = ArrayList<Thread>()
        val seen = ArrayList<String>()
        engine.onRoute = { n ->
            routedOn += Thread.currentThread() // the thread the planner (the plan's run) is on
            if (n == 2) {
                // one leg done (1 of 2) and the router half way in the second: the poll runs on the default thread
                stepDefault(); advance(250); stepDefault()
                seen += "share=${planRun.routeShare}"
            }
        }
        recalcNow(); advance(150); stepRouting()
        assertEquals(listOf("share=0.75"), seen)
        assertEquals("the router is asked for the plan's own thread (the one the planner runs on), nobody else's", listOf(routedOn.last()), askedFor.distinct())
        assertEquals(listOf("default: searchProgress -> 0.5"), log.filter { it.contains("searchProgress") })
        // every leg is routed: the share is cleared (the wait window checks the route), the poll is stopped
        assertNull(planRun.routeShare)
        advance(1_000); stepDefault()
        assertEquals("the poll is stopped with the run", 1, log.count { it.contains("searchProgress") })
    }

    @Test fun theCancelButtonAppearsOnlyAfterTheSlowLimitAndGoesWithTheResult() {
        plan(a, b); create()
        recalcNow(); advance(150)
        val seen = ArrayList<Boolean>()
        engine.onRoute = { advance(2_499); seen += planRun.routingSlow; advance(1); seen += planRun.routingSlow }
        stepRouting()
        assertEquals("not before the limit, then offered", listOf(false, true), seen)
        stepDefault(); stepIo()
        assertFalse("taken back when the result came", planRun.routingSlow)
        assertFalse(planRun.routing)
    }

    // ======================================================================================================================
    // success
    // ======================================================================================================================

    @Test fun aRouteIsTakenItsStateAndItsCorridorInTheOldOrderOnTheOldThreads() {
        plan(a, b); create()
        recalcNow()
        val job = (planRun.routeJob as JobHandle).job
        finishRun()
        assertTrue("the run's job is through: its slow timer and its poll ended with it", job.isCompleted)
        // the order: the corridor work is done (default thread) before the result is taken (main), the route is set before the reach,
        // the plan result with it; then the corridor is asked for, the error line decided, the corridor named, the freshening
        val marks = listOf("default: postRoute", "main: route=", "main: planReach=", "main: planResult=", "main: routingForLine", "main: routingLoading",
            "main: routeError=", "main: routeComputed", "main: freshenAlong", "main: checkRoutingDataFor")
        val postAt = log.indexOfFirst { it.startsWith("default: postRoute") }
        val order = marks.map { p -> log.withIndex().indexOfFirst { (i, l) -> i >= postAt && l.startsWith(p) } }
        assertTrue(log.toString(), order.all { it >= 0 })
        assertEquals(log.toString(), order.sorted(), order)
        val n = routeNow!!.points.size
        assertEquals("default: postRoute ${n}pts for 2 HIKE", log.first { it.contains("postRoute") })
        assertEquals("main: routeComputed $n cells=1 dest=b HIKE", log.first { it.contains("routeComputed") })
        assertEquals("main: freshenAlong $n cells=1 HIKE", log.first { it.contains("freshenAlong") })
        assertEquals("main: routingForLine [E10_N45]", log.first { it.contains("routingForLine") })
        assertEquals("main: checkRoutingDataFor $n", log.first { it.contains("checkRoutingDataFor") })
        assertEquals(planRoute.spotsMemo!!.first, Triple(routeNow!!.points, Activity.HIKE, 3))
        assertNull(planRoute.routeChangedNotice)
        assertFalse(planRun.routing); assertNull(planRun.routeProgress)
        val line = problems.traced("routing").single { it.startsWith("Route #1 for 2 points ready after 150 ms (plan now 2 points): HIKE, ") }
        assertTrue(line, line.contains(" km straight, ") && line.contains(" km routed, ") && line.contains(" line points, 0 straight legs"))
    }

    @Test fun routingDataMissingUnderTheRouteGivesTheNoticeInPlainWordsAndASaneNoticeOtherwiseTheRoutersError() {
        plan(a, b); create()
        tilesMissing = true; loadingNow = true
        recalcNow(); finishRun()
        assertEquals(RoutingDataNotice.LOADING, errorNow)
        tilesMissing = true; loadingNow = false
        recalcNow(); finishRun()
        assertEquals(RoutingDataNotice.NOT_YET, errorNow)
        tilesMissing = false
        recalcNow(); finishRun()
        assertNull("no notice, no error", errorNow)
    }

    @Test fun aMultiDayTourLetsTheCorridorFollowThePlansLineInsteadOfRouteComputed() {
        plan(a, b); create()
        multiDay = true
        recalcNow(); finishRun()
        assertEquals(listOf("main: corridorFollowsPlan ${routeNow!!.points.size} HIKE"), log.filter { it.contains("corridorFollowsPlan") })
        assertEquals(emptyList<String>(), log.filter { it.contains("routeComputed") })
        assertEquals(1, log.count { it.contains("freshenAlong") })
    }

    @Test fun theFirstRouteInPlanRisesTheSheetOnceAndTheFitWaitsForIt() {
        plan(a, b); create()
        planEdit.planSize = PlanSize.COLLAPSED
        ui { planRun.routeEvent(RouteRun.Event.FitNext) }
        recalcNow(); finishRun()
        assertEquals(SheetRise.TO, planEdit.planSize)
        assertEquals(listOf("First route of the plan: the sheet rises to ${SheetRise.TO}"), problems.traced("plan").filter { it.startsWith("First route") })
        // the fit waits for the sheet: the settle time plus the rise's wait
        val settle = WholeRoute.SETTLE_MS + SheetRise.FIT_WAIT_MS
        advance(settle - 1)
        assertEquals(emptyList<String>(), log.filter { it.contains("fit ") })
        advance(1)
        assertEquals(1, log.count { it.contains(": fit ") })
        // once per plan: the next route does not rise again nor fit
        planEdit.planSize = PlanSize.COLLAPSED; log.clear()
        recalcNow(); finishRun(); advance(5_000)
        assertEquals(PlanSize.COLLAPSED, planEdit.planSize)
        assertEquals(emptyList<String>(), log.filter { it.contains(": fit ") })
    }

    @Test fun aSheetThatStandsAtTheStepAlreadyNeitherRisesNorWaitsLongForTheFit() {
        plan(a, b); create()
        planEdit.planSize = SheetRise.TO
        ui { planRun.routeEvent(RouteRun.Event.FitNext) }
        recalcNow(); finishRun()
        assertEquals(emptyList<String>(), problems.traced("plan").filter { it.startsWith("First route") })
        advance(WholeRoute.SETTLE_MS)
        assertEquals("no extra wait", 1, log.count { it.contains(": fit ") })
    }

    @Test fun noSheetRiseInNavigateOrDuringATour() {
        plan(a, b); create()
        planEdit.planSize = PlanSize.COLLAPSED
        modeNow = AppMode.NAVIGATE
        recalcNow(); finishRun()
        assertEquals(PlanSize.COLLAPSED, planEdit.planSize)
        modeNow = AppMode.PLAN; client.publish(recState())
        plan.replace(listOf(a, c)); ui { planRun.routeEvent(RouteRun.Event.NewPlan) }
        recalcNow(); finishRun()
        assertEquals(PlanSize.COLLAPSED, planEdit.planSize)
    }

    @Test fun aRouteAfterAnActivityChangeSaysItChangedOnlyWhenItDidAndOtherwiseDropsTheOldOne() {
        plan(a, b); create()
        // before: a short, flat route; the run's result is the fake's (a different length): changed noticeably
        planRoute.activityBefore = routeFor(listOf(a, b), distanceM = 100.0, ascentM = 0.0)
        ui { planRun.routeEvent(RouteRun.Event.CompareActivity(true)) }
        routePrefs.activity = Activity.HIKE
        recalcNow(); finishRun()
        assertNull("compared once", planRoute.activityBefore)
        assertEquals(com.onpath.app.domain.RouteWarnings.changedText(Activity.HIKE, 3), planRoute.routeChangedNotice)
        // a second run is no activity change: nothing is compared, the notice stays as it is
        planRoute.routeChangedNotice = null; planRoute.activityBefore = routeFor(listOf(a, b), distanceM = 100.0)
        recalcNow(); finishRun()
        assertNull(planRoute.routeChangedNotice)
        assertNotNull("not taken: the run was not an activity change", planRoute.activityBefore)
        // the same route as before: no notice, the old one is dropped
        val same = engine.inner.route(listOf(a, b), Activity.HIKE, 3)
        planRoute.activityBefore = PlannedRoute(listOf(PlannedLeg(same.points, false)), same.distanceM, same.ascentM, same.descentM, same.durationS, null)
        ui { planRun.routeEvent(RouteRun.Event.CompareActivity(true)) }
        recalcNow(); finishRun()
        assertNull(planRoute.activityBefore); assertNull(planRoute.routeChangedNotice)
    }

    @Test fun aRunOnNewerRoutingDataSaysSoOnlyWhenTheWayChangedAndNeverFreshens() {
        plan(a, b); create()
        recalc.routingDataBefore = listOf(a, GeoPoint(46.7, 11.4), b)
        ui { planRun.routeEvent(RouteRun.Event.NewerData) }
        recalcNow(); finishRun()
        assertEquals(Txt.s(Res.string.msg_route_updated_newer_data), planRoute.routeChangedNotice)
        assertNull("compared once", recalc.routingDataBefore)
        assertEquals("a run on newer data does not freshen (the data is the new data)", emptyList<String>(), log.filter { it.contains("freshenAlong") || it.contains("checkRoutingDataFor") })
        // the way did not change: nothing is said
        planRoute.routeChangedNotice = null
        recalc.routingDataBefore = engine.inner.route(listOf(a, b), Activity.HIKE, 3).points
        ui { planRun.routeEvent(RouteRun.Event.NewerData) }
        recalcNow(); finishRun()
        assertNull(planRoute.routeChangedNotice); assertNull(recalc.routingDataBefore)
    }

    @Test fun anActivitySwitchedWhileNavigatingAdoptsTheRoutesLineAsTheBaselineOnce() {
        plan(a, b); create()
        assertNull(follow.planBaseline)
        ui { planRun.routeEvent(RouteRun.Event.AdoptBaseline) }
        recalcNow(); finishRun()
        assertEquals(routeNow!!.points, follow.planBaseline)
        // adopted once: the next run leaves it alone
        follow.planBaseline = null
        recalcNow(); finishRun()
        assertNull(follow.planBaseline)
    }

    // ---------------- snapToRoute ----------------

    private fun snapTo(f: (GeoPoint) -> GeoPoint) { engine.snap = f }
    private val near = { p: GeoPoint -> GeoPoint(p.lat + 0.0003, p.lon) } // ~33 m: moved onto the way

    @Test fun aFreeUnnamedPointMovesOntoTheWayNamedAndPinnedPointsKeepTheirPlaceAndEveryoneWhoKeepsTheOldPointsFollows() {
        create()
        ui { plan.edit { addAll(listOf(a, b)) }; plan.edit { add(c) } } // two undo steps
        names = mapOf(b to "Pass")
        plan.pin(c)
        links.opened("Link plan", listOf(a, b, c))
        snapTo(near)
        recalcNow(); finishRun()
        assertEquals("only the free point a moved", listOf(near(a), b, c), plan.points.toList())
        assertEquals("stored at once", listOf(near(a), b, c), planStore.points)
        assertEquals(listOf(near(a), b, c), resultNow!!.points)
        assertEquals("an open menu follows its point", listOf("main: menuFollowsSnap 1"), log.filter { it.contains("menuFollowsSnap") })
        assertEquals("what the link brought follows (the Save dialog's name)", "Link plan", links.title(listOf(near(a), b, c)))
        assertNull(links.title(listOf(a, b, c)))
        // Undo shows the snapped points too (the history snapshots got the same values)
        assertEquals(listOf(near(a), b), plan.undo())
    }

    @Test fun anEditMeanwhileMeansNoSnapOnlyTheResultIsKept() {
        plan(a, b); create()
        snapTo(near)
        recalcNow(); advance(150)
        stepRouting()
        plan.points.add(c) // an edit that has not asked for its run yet (the run is not superseded; the plan is no longer its points)
        stepDefault()
        assertEquals("not snapped: the plan is not the routed points any more", listOf(a, b, c), plan.points.toList())
        assertEquals("the result is kept as it is", listOf(a, b), resultNow!!.routed)
        assertEquals(1, log.count { it.contains("planResult=") })
        assertEquals(emptyList<String>(), log.filter { it.contains("menuFollowsSnap") })
        assertEquals("the route itself is taken", c.let { routeNow!!.points.last() }, near(b))
        assertEquals("no alias work was queued", 0, ioEx.size)
    }

    @Test fun aSnapKeepsTheLegsUnderTheMovedPointsOnTheIoThreadAndTracesHowMany() {
        plan(a, b, c); create()
        snapTo(near)
        recalcNow(); advance(150); stepRouting(); stepDefault()
        // the alias waits for the io thread
        assertEquals(emptyList<String>(), problems.traced("routing").filter { it.contains("legs kept under") })
        assertEquals(1, ioEx.size)
        val legsBefore = planRoute.legCache.size
        stepIo()
        assertEquals(listOf("2 of 2 legs kept under their points moved onto the line"), problems.traced("routing").filter { it.contains("legs kept under") })
        assertTrue("the aliases are in the cache now", planRoute.legCache.size > legsBefore)
        assertEquals("the leg under the moved points is the same line", 0, ioEx.size)
    }

    /**
     * A point put in ON the routed line (owner 2026-11-20: the app cuts like the planner – a night, a stop at a line point):
     * the run cuts the known leg there ([com.onpath.app.routing.LegSplit]) – no router call, the same line and numbers, three
     * points now. A point a few metres beside the line is routed as before (its two legs).
     */
    @Test fun aPointPutInOnTheRoutedLineCutsItsLegWithoutRoutingAndOneBesideItIsRouted() {
        plan(a, b, c); create()
        recalcNow(); finishRun()
        val before = routeNow!!
        val calls = engine.inner.calls.size
        val spot = before.legs[0].points.let { it[it.size / 2] }
        ui { planEdit.change { add(1, spot) } }
        finishRun()
        assertEquals("no router call", calls, engine.inner.calls.size)
        assertEquals(listOf("Route #2: 1 leg(s) cut at a point put in on the line – not routed"),
            problems.traced("routing").filter { it.contains("cut at a point") })
        assertEquals(listOf(a, spot, b, c), plan.points.toList())
        assertEquals(before.points, routeNow!!.points)
        assertEquals(before.distanceM, routeNow!!.distanceM, 1e-6)
        assertEquals(before.durationS, routeNow!!.durationS, 1e-6)
        assertEquals(3, routeNow!!.legs.size)
        // both halves are kept under their own points: the same plan again routes nothing
        assertNotNull(planRoute.legCache.get(a, spot, Activity.HIKE, 3))
        assertNotNull(planRoute.legCache.get(spot, b, Activity.HIKE, 3))

        // a point 5 m beside the second leg's line: routed as before
        val q = routeNow!!.legs[2].points.let { it[it.size / 2] }
        val beside = GeoPoint(q.lat + 5.0 / 111_195.0, q.lon)
        ui { planEdit.change { add(3, beside) } }
        finishRun()
        assertEquals(listOf(listOf(b, beside), listOf(beside, c)), engine.inner.calls.drop(calls).map { it.waypoints })
        assertEquals(1, problems.traced("routing").count { it.contains("cut at a point") })
    }

    @Test fun aSnappedPlanThatIsTheSavedOneIsSavedAgainAsSnapped() {
        plan(a, b); create()
        plan.setSaved("Mine", 5L, listOf(a, b))
        snapTo(near)
        recalcNow(); finishRun()
        assertEquals("the saved copy follows the snap (the plan stays the saved one, not an unsaved change)", listOf(near(a), near(b)), plan.savedPoints)
        assertEquals("Mine", plan.linkName); assertEquals(5L, plan.linkId)
    }

    @Test fun anUnsavedPlanIsNotSavedBySnappingIt() {
        plan(a, b); create()
        plan.setSaved("Other", 6L, listOf(c, d)) // the saved route is another one: the plan is unsaved
        snapTo(near)
        recalcNow(); finishRun()
        assertEquals("the snapped points are the plan's", listOf(near(a), near(b)), plan.points.toList())
        assertEquals("the saved route is not touched by a snap of an unsaved plan", listOf(c, d), plan.savedPoints)
    }

    // ======================================================================================================================
    // failure
    // ======================================================================================================================

    @Test fun theTimeLimitInPlanAsksAndNothingElseChanges() {
        plan(a, b); create()
        routeNow = routeFor(listOf(a, b), distanceM = 555.0); errorNow = null
        engine.timeLimit = true
        recalcNow(); finishRun()
        assertEquals("asks to wait on", 1, planRun.slowPlanAsk)
        assertEquals(listOf("Plan reached its time limit after 1 period(s) – asking to wait on"), problems.said("routing").filter { it.startsWith("Plan reached") })
        assertEquals("the line stays", 555.0, routeNow!!.distanceM, 0.0)
        assertNull("no error line", errorNow)
        assertEquals(emptyList<String>(), log.filter { it.contains("corridorFollowsPlan") || it.contains("routeComputed") })
        assertTrue("no error was reported", problems.none("routing"))
        assertEquals("a straight route is not made: the box stays until answered", emptyList<String>(), said)
    }

    @Test fun aFailedRunWithoutATourTakesTheLineOffTheMapAndSaysOurOwnText() {
        plan(a, b); create()
        routeNow = routeFor(listOf(a, b))
        createFails = true
        recalcNow(); finishRun()
        assertNull(routeNow)
        assertEquals(com.onpath.app.debug.UserText.of(IllegalStateException("engine cannot start"), Txt.s(Res.string.msg_routing_failed)), errorNow)
        assertEquals("main: corridorFollowsPlan null null", log.last { it.contains("corridorFollowsPlan") })
        assertEquals(listOf("Route calculation failed"), problems.said("routing", com.onpath.app.debug.Problems.Level.ERROR))
        assertFalse(planRun.routing)
        assertNull(planRun.slowPlanAsk)
        assertTrue(problems.traced("routing").any { it.startsWith("Route #1 for 2 points failed after 150 ms") })
    }

    @Test fun aFailedRunDuringATourKeepsTheWholePlanLineOnTheMap() {
        plan(a, b); create()
        client.publish(recState())
        routeNow = routeFor(listOf(a, b), distanceM = 4242.0)
        createFails = true
        recalcNow(); finishRun()
        assertEquals("one failed run must not take the plan line off the map", 4242.0, routeNow!!.distanceM, 0.0)
        assertNotNull(errorNow)
        assertEquals(1, log.count { it.contains("corridorFollowsPlan null null") })
    }

    /**
     * The two early returns of different scope (`return@onSuccess` without a route to take, `return@onFailure` without a failure to
     * show). Both need a run whose phase left "calculating" while it ran (a guard: the time-limit question of this very run is fed
     * in); the success has taken the line by then (the first statements of `onSuccess`), nothing after them; the failure has none.
     */
    @Test fun aSuccessOfARunThatIsNotCalculatingAnyMoreTakesTheLineAndNothingElse() {
        plan(a, b); create()
        routeNow = routeFor(listOf(a, b), distanceM = 31.0)
        engine.onRoute = { planRun.routeEvent(RouteRun.Event.TimeLimit(planRun.routeRun.gen, 1)) }
        recalcNow(); finishRun()
        assertTrue("the line is taken (it is the first statement)", routeNow!!.distanceM != 31.0)
        assertEquals(1, log.count { it.contains("planResult=") })
        assertEquals("nothing after the early return", emptyList<String>(),
            log.filter { it.contains("routeComputed") || it.contains("freshenAlong") || it.contains("routingForLine") || it.contains("routeError=") })
        assertNull(planRoute.spotsMemo)
        // the run itself goes on to its end: the trace line comes before the branches
        assertEquals(1, problems.traced("routing").count { it.startsWith("Route #1 for 2 points ready") })
    }

    @Test fun aFailureOfARunThatIsNotCalculatingAnyMoreShowsNothingAndKeepsTheLine() {
        plan(a, b); create()
        routeNow = routeFor(listOf(a, b), distanceM = 31.0)
        createFails = true
        onCreate = { planRun.routeEvent(RouteRun.Event.TimeLimit(planRun.routeRun.gen, 1)) }
        recalcNow(); finishRun()
        assertEquals("the line stays (no failure was shown)", 31.0, routeNow!!.distanceM, 0.0)
        assertNull(errorNow)
        assertEquals(emptyList<String>(), log.filter { it.contains("corridorFollowsPlan") })
        assertEquals(emptyList<String>(), problems.said("routing", com.onpath.app.debug.Problems.Level.ERROR))
        assertEquals(1, problems.traced("routing").count { it.startsWith("Route #1 for 2 points failed") })
    }

    // ======================================================================================================================
    // what the tour does with the result; Done last
    // ======================================================================================================================

    @Test fun continueNavigationWaitedForTheResultTheTourFollowsItAndDoneComesLast() {
        plan(a, b); create()
        ui { planRun.routeEvent(RouteRun.Event.FollowNext) }
        client.publish(recState())
        recalcNow(); finishRun()
        assertEquals("the tour follows the new plan line", routeNow!!.points, follow.followLine)
        assertTrue(follow.followFromPlan)
        assertTrue("followed while the run still said calculating (Done is the last event)", log.any { it.startsWith("main: send FOLLOW_CHANGED [routing=true") })
        assertFalse(planRun.routing)
        assertEquals(emptyList<String>(), said)
    }

    @Test fun continueNavigationWithoutARouteSaysThereIsNoNewRoute() {
        plan(a, b); create()
        ui { planRun.routeEvent(RouteRun.Event.FollowNext) }
        createFails = true
        recalcNow(); finishRun()
        assertNull(follow.followLine)
        assertEquals(listOf("ERROR/MAP: ${Txt.s(Res.string.msg_no_new_route)}"), said)
        assertFalse(planRun.routing)
    }

    @Test fun continueNavigationAfterAFailedRunDuringATourFollowsTheLineThatStayed() {
        plan(a, b); create()
        client.publish(recState())
        routeNow = routeFor(listOf(a, b), distanceM = 4242.0)
        ui { planRun.routeEvent(RouteRun.Event.FollowNext) }
        createFails = true
        recalcNow(); finishRun()
        // the failure keeps the line during a tour, so "Continue navigation" follows it (no "no new route")
        assertEquals(routeNow!!.points, follow.followLine)
        assertEquals(emptyList<String>(), said)
        assertNotNull(errorNow)
    }

    @Test fun anEditDuringATourMakesTheTourFollowTheNewPlanAfterTheRouteIsTakenAndBeforeDone() {
        plan(a, b, c); create()
        client.publish(recState())
        routeNow = routeFor(listOf(a, b, c))
        ui { follow.followPlanned() }
        pcEx.runAll(); client.sent.clear(); log.clear()
        plan.replace(listOf(a, b, c, d))
        ui { planRun.routeEvent(RouteRun.Event.PlanEdited) }
        recalcNow(); finishRun()
        // the tour follows the edited plan's route, asked while the run still says calculating
        assertEquals(routeNow!!.points, follow.followLine)
        val send = log.first { it.contains("send FOLLOW_CHANGED") }
        assertEquals("main: send FOLLOW_CHANGED [routing=true slowAsk=null]", send)
        assertTrue(log.indexOf(send) > log.indexOfFirst { it.startsWith("main: route=") })
        assertFalse(planRun.routing)
    }

    @Test fun anAdoptedBaselineDoesNotMakeTheTourFollowAnEdit() {
        plan(a, b, c); create()
        client.publish(recState())
        routeNow = routeFor(listOf(a, b, c)); ui { follow.followPlanned() }; pcEx.runAll()
        client.sent.clear()
        plan.replace(listOf(a, b, c, d))
        ui { planRun.routeEvent(RouteRun.Event.PlanEdited); planRun.routeEvent(RouteRun.Event.AdoptBaseline) }
        recalcNow(); finishRun()
        assertEquals(emptyList<String>(), client.actions.filter { it == "FOLLOW_CHANGED" })
        assertEquals(routeNow!!.points, follow.planBaseline)
    }

    // ======================================================================================================================
    // aliasSnappedLegs, findReach
    // ======================================================================================================================

    @Test fun aPointTheRouteDoesNotReachGetsWhatReachesItAfterThePlanRouteOnTheRoutingThreadAndItsTimeIsTraced() {
        plan(a, b); create()
        snapTo { p -> if (p == b) GeoPoint(p.lat + 0.007, p.lon) else p } // the router ends 780 m from b: it does not reach it
        recalcNow(); finishRun()
        assertTrue(engine.threads.toString(), engine.threads.size >= 2 && engine.threads.all { it == "routing" })
        assertEquals(engine.inner.calls.toString(), 1, engine.inner.calls.count { it.sac == 3 && it.waypoints == listOf(a, GeoPoint(b.lat + 0.007, b.lon)) })
        val trace = problems.traced("routing").single { it.startsWith("Point 2 not reached at HIKE T3: ") }
        assertTrue(trace, trace.endsWith(" after 0 ms"))
        assertNotNull("set with the route of exactly these points", reachNow)
        assertEquals(1, reachNow!!.index)
    }

    @Test fun whatReachesThePointCannotBeWorkedOutIsAWarningAndTheSheetSaysTheMissingMetres() {
        plan(a, b); create()
        snapTo { p -> if (p == b) GeoPoint(p.lat + 0.007, p.lon) else p }
        var creates = 0
        onCreate = { if (++creates == 2) throw IllegalStateException("router cannot start for the search") } // the plan run got its router; the search's start fails
        recalcNow(); finishRun()
        assertNull(reachNow)
        val warn = problems.said("routing", com.onpath.app.debug.Problems.Level.WARN).filter { it.startsWith("What reaches point 2 could not be worked out") }
        assertEquals(problems.said("routing").toString(), 1, warn.size)
        assertEquals("the route itself is taken", 1, log.count { it.contains("planResult=") })
    }

    private fun assertNotSame(a: Any?, b: Any?) = assertTrue("same object", a !== b)
}
