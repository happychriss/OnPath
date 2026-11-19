package com.onpath.app.ui.tour

import com.onpath.app.data.AppSettings
import com.onpath.app.data.TourKind
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.ActivityCheck
import com.onpath.app.navigation.FollowStore
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavSessionStore
import com.onpath.app.navigation.NavState
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.recording.RecState
import com.onpath.app.recording.RecordingWire
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RouteLimits
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.RoutingException
import com.onpath.app.routing.Share
import com.onpath.app.routing.StraightLineEngine
import com.onpath.app.testkit.FakeRecordingClient
import com.onpath.app.testkit.FakeRoutingEngine
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.ManualExecutor
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.testkit.VirtualDelayDispatcher
import com.onpath.app.time.TourTimes
import com.onpath.app.ui.ActivityCheckUi
import com.onpath.app.ui.ActivityRowState
import com.onpath.app.ui.plan.PlanRouteHost
import com.onpath.app.ui.plan.PlanRoutePrefs
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.app.ui.plan.RouterPort
import com.onpath.app.ui.plan.RoutingThreads
import com.onpath.app.ui.plan.TimesStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The activity check sheet (rebuild phase 2 piece 16b) as flows of the REAL [ActivityCheckSlice] with the real
 * [PlanRouteSlice] (the engine, its threads, the activity / T level) and the real [FollowStore] on a temp folder
 * (docs/design/umbau-phase-2-tour.md §4.4, rules 1, 23, 24). Fakes only at the edges: the recording client records WHAT IS
 * ON DISK AT THE MOMENT a command arrives ([FakeRecordingClient.onSend]); the rows are routed by a scripted engine on a
 * HELD "check thread" (the test steps it); the database writes run on a held "io" executor; the activity host logs what the
 * plan was told, in order. The scope is main, inline, with virtual time. What the tests do NOT prove: BRouter itself (the
 * engine is scripted), the sheet's Compose code (UxReviewFixesTest pins its wiring).
 *
 * Test data tells things apart: the session is a plan of three stops (A, B pinned, C), T2 hiking / T2 bike, the user's
 * remembered level is T3, every row's scripted answer differs (steps, a shorter way, no way, a longer way).
 */
class ActivityCheckFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private val client = FakeRecordingClient()
    private val db = JdbcTours()
    private val log = ArrayList<String>()

    // the three threads of the check: main (the scope, inline), io (held), the check's routing thread (held), the pure computation (held)
    /** A [ManualExecutor] as the executor service a [RoutingThreads] dispatcher wants. */
    private class HeldService(val ex: ManualExecutor) : java.util.concurrent.AbstractExecutorService() {
        override fun execute(command: Runnable) = ex.execute(command)
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
    }
    private fun held(ex: ManualExecutor) = HeldService(ex).asCoroutineDispatcher()

    private val ioEx = ManualExecutor("io")
    private val checkEx = ManualExecutor("check")
    private val cpuEx = ManualExecutor("cpu")
    private val main = VirtualDelayDispatcher { it.run() }
    private val scope = CoroutineScope(main + SupervisorJob())
    private var inIo = false

    private fun stepIo(): Int { inIo = true; try { return ioEx.runAll() } finally { inIo = false } }
    private fun stepCpu() = cpuEx.runAll()
    private fun stepRow() = checkEx.runNext()

    // ---------------- the edges ----------------

    /** Scripted answers per activity; [onRoute] runs INSIDE the engine call (= "while BRouter is routing"). */
    private class RowEngine : RoutingEngine {
        val inner = FakeRoutingEngine()
        val calls get() = inner.calls
        /** Every request, also the ones the script lets fail. */
        val asked = ArrayList<Activity>()
        val cancels = ArrayList<RouteCancel?>()
        var onRoute: (Activity) -> Unit = {}
        /** The engine does not look at the cancel (a result that is late). */
        var ignoreCancel = false
        var script: (Activity, Int, RouteResult) -> RouteResult = { _, _, r -> r }
        var terminatedAt = ArrayList<String>()
        var fails: (Activity) -> RoutingException? = { null }
        var tooShort = false

        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
            route(waypoints, activity, sacLimit, RouteLimits(30_000))

        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
            asked += activity
            cancels += limits.cancel
            limits.cancel?.onCancel { terminatedAt += "terminated" }
            onRoute(activity)
            fails(activity)?.let { throw it }
            val r = if (ignoreCancel) inner.route(waypoints, activity, sacLimit) else inner.route(waypoints, activity, sacLimit, limits)
            if (tooShort) return r.copy(points = r.points.take(1))
            return script(activity, sacLimit, r)
        }
    }

    private val engine = RowEngine()
    private var straight = false

    private class MemoryPrefs(override var activity: Activity = Activity.HIKE, override var sac: Int = 3) : PlanRoutePrefs
    private val prefs = MemoryPrefs()
    private var mode = AppMode.NAVIGATE
    private var followFromPlan = true
    private var planPoints = 3

    private lateinit var planRoute: PlanRouteSlice
    private fun newPlanRoute() = PlanRouteSlice(
        prefs = prefs,
        router = object : RouterPort {
            override fun create(): RoutingEngine = if (straight) StraightLineEngine() else engine
            override val isRealRouter: Boolean get() = !straight
            override fun fingerprint() = "fp"
            override val legsDir: File get() = File(tmp.root, "legs")
            override fun partVersion(part: Rd5Parts.Part) = "v1"
        },
        times = object : TimesStore {
            override fun loadSegments(id: Long): List<List<TrackPoint>> = db.loadSegments(id)
            override fun planGroups(id: Long, distanceM: Double): DoubleArray? = null
            override fun putPlanGroups(id: Long, distanceM: Double, groups: DoubleArray) = Unit
            override fun tourTimes(id: Long): TourTimes? = null
        },
        host = object : PlanRouteHost {
            override val settings: AppSettings get() = AppSettings()
            override var route: com.onpath.app.routing.PlannedRoute? = null
            override val routing: Boolean get() = false
            override val longRouteAsk: Double? get() = null
            override val mode: AppMode get() = this@ActivityCheckFlowTest.mode
            override val touring: Boolean get() = client.state.value != null
            override fun recalc() { log += "recalc ${planRoute.activity.name} T${planRoute.sac} prefs=${prefs.activity.name}/T${prefs.sac}" }
            override fun routeEvent(e: RouteRun.Event) { log += "event $e" }
            override fun refreshTours() = Unit
            override fun clearShownNote() { log += "clearShownNote" }
            override fun showWhole(points: List<GeoPoint>) = Unit
        },
        threads = RoutingThreads(
            routing = held(ManualExecutor("routing")), background = held(ManualExecutor("background")),
            check = held(checkEx), hold = held(ManualExecutor("hold"))),
    )

    private lateinit var followStore: FollowStore
    private val proposalFile get() = File(tmp.root, ActivityCheck.PROPOSAL_FILE)

    private class Gps : Fixes {
        var here: GeoPoint? = null
        var firsts = 0
        val waiting = ArrayList<CompletableDeferred<GeoPoint>>()
        override fun now(): GeoPoint? = here
        override suspend fun first(): GeoPoint { firsts++; val d = CompletableDeferred<GeoPoint>(); waiting += d; return d.await() }
        fun arrive(p: GeoPoint) { here = p; waiting.toList().forEach { it.complete(p) }; waiting.clear() }
    }
    private val gps = Gps()

    private val host = object : ActivityCheckHost {
        override val mode: AppMode get() = this@ActivityCheckFlowTest.mode
        override fun enterNavigate() { log += "enterNavigate"; this@ActivityCheckFlowTest.mode = AppMode.NAVIGATE }
        override val followFromPlan: Boolean get() = this@ActivityCheckFlowTest.followFromPlan
        override val planPoints: Int get() = this@ActivityCheckFlowTest.planPoints
        override fun routeEvent(e: RouteRun.Event) { log += "FollowNext-host $e" }
        override fun rememberSac(level: Int) { log += "rememberSac($level)"; prefs.sac = level }
    }

    private val tourId = db.insertTour(TourKind.RECORDED, "Tour", "HIKE", 2, state = "active")
    private val rows = object : TourRows {
        override fun findActiveRecording(): com.onpath.app.data.Tour? = error("the activity check does not look for an active tour")
        override fun updateActivity(id: Long, activity: String, sac: Int) {
            check(inIo) { "TourRows.updateActivity must run on the io thread" }
            log += "updateActivity($id, $activity, T$sac)"
            db.updateActivity(id, activity, sac)
        }
        // the tour's end (piece 17) writes through the same port; the activity check never does
        override fun updateStats(id: Long, distance: Double, ascent: Double, descent: Double, duration: Double, state: String): Int =
            error("the activity check does not write stats")
        override fun setDeleted(id: Long, deletedMs: Long?): Boolean = error("the activity check does not bin a tour")
        override fun setTourPlan(id: Long, plan: com.onpath.app.data.TourPlan) = error("the activity check does not copy a plan")
        override fun rides(): com.onpath.app.rides.RideStore = error("the activity check does not touch the rides")
    }

    private lateinit var slice: ActivityCheckSlice

    /** What was on disk / on screen at the moment a command arrived. */
    private class AtSend(val action: String, val extras: Map<String, Any>, val proposal: NavSession?, val sheetOpen: Boolean, val mode: AppMode, val ioWaiting: Int)
    private val atSend = ArrayList<AtSend>()

    /** The slices are built when the rules have made the temp folder (a field initialiser runs before them). */
    @Before fun setUp() {
        planRoute = newPlanRoute()
        followStore = FollowStore(tmp.root)
        slice = ActivityCheckSlice(planRoute, client, followStore, rows, gps, host, scope, ioEx.asCoroutineDispatcher(), cpuEx.asCoroutineDispatcher())
        client.onSend = { c ->
            atSend += AtSend(c.action.substringAfterLast('.'), c.extras, NavSessionStore(proposalFile).load(), slice.activityCheck != null, mode, ioEx.size)
            log += "send ${c.action.substringAfterLast('.')}"
        }
    }

    @After fun close() { scope.cancel(); db.close() }

    // ---------------- the tour ----------------

    private val tourLine = line(0.0 to 0.0, 1500.0 to 0.0, 1500.0 to 1500.0)
    private val stopA = pt(0.0, 0.0); private val stopB = pt(1500.0, 0.0); private val stopC = pt(1500.0, 1500.0)

    private fun session(activity: Activity = Activity.BIKE_TOURING, sac: Int = 2, reached: Boolean = true, kind: NavKind = NavKind.PLAN) =
        (if (kind == NavKind.PLAN)
            NavSession.start(NavKind.PLAN, "Plan", activity, sac, tourLine, stops = listOf(stopA, stopB, stopC),
                names = listOf("A", "B", "C"), pinned = setOf(1))
        else NavSession.start(kind, "Track", activity, sac, tourLine)).copy(reached = reached, token = 7)

    private fun recState(session: NavSession?, progressM: Double = 400.0) = RecState(
        tourId = tourId, paused = false, distanceM = 400.0, ascentM = 0.0, descentM = 0.0, elapsedS = 300, gpsLost = false, offRouteM = null,
        offRouteAlert = false, followName = session?.name, track = emptyList(),
        nav = session?.let { NavState(it.active, 1, null, null, null, null, it, progressM) })

    private fun touring(session: NavSession?, progressM: Double = 400.0) = client.publish(recState(session, progressM))

    private val fix = GeoPoint(pt(400.0, 0.0).lat, pt(400.0, 0.0).lon, 612.0)

    private fun rowsOf(ui: ActivityCheckUi? = slice.activityCheck): Map<Activity, ActivityRowState> = ui!!.rows.associate { it.activity to it.state }
    private fun ready(a: Activity) = rowsOf()[a] as ActivityRowState.Ready
    private fun traced() = problems.traced("navigation")
    private fun tourRow() = db.getTour(tourId)!!.let { it.activity to it.sac }
    private fun sentActions() = client.actions

    /** The way the user taps a row on the sheet: the cpu step (the rest of the route), then the row's routing on the check thread. */
    private fun tapAndRoute(a: Activity) { slice.chooseActivity(a); stepCpu(); stepRow() }

    // ======================================================================================================================
    // open: nothing is routed before a row is chosen (rule 23; field bug tour 207)
    // ======================================================================================================================

    @Test fun openingTheSheetListsTheOtherActivitiesAndRoutesNothing() {
        touring(session(Activity.BIKE_TOURING, sac = 2)); gps.here = fix
        slice.openActivityCheck()
        val ui = slice.activityCheck!!
        assertEquals(Activity.BIKE_TOURING, ui.current); assertEquals(2, ui.sac)
        assertEquals(listOf(Activity.HIKE, Activity.BIKE_TREKKING, Activity.EBIKE, Activity.MTB, Activity.EMTB).sortedBy { it.ordinal },
            ui.rows.map { it.activity }.sortedBy { it.ordinal })
        assertTrue("every row is waiting for a tap", ui.rows.all { it.state == ActivityRowState.Idle })
        assertNull("a fix is known: no note", ui.note)
        // nothing was handed to any thread, nothing routed, nothing said to the service
        assertEquals(0, cpuEx.size); assertEquals(0, checkEx.size); assertEquals(0, ioEx.size)
        stepCpu(); while (checkEx.runNext()) Unit
        assertEquals(0, engine.calls.size)
        assertTrue(client.sent.isEmpty())
        assertEquals(0, gps.firsts)
    }

    @Test fun theHikeRowShowsTheRememberedLevelTheOtherRowsTheSessionsOwn() {
        touring(session(Activity.BIKE_TOURING, sac = 2)); gps.here = fix
        slice.openActivityCheck()
        assertEquals("the remembered level (T3), not the session's T2", 3, slice.activityCheck!!.rows.single { it.activity == Activity.HIKE }.sac)
        assertEquals(setOf(2), slice.activityCheck!!.rows.filter { it.activity != Activity.HIKE }.map { it.sac }.toSet())
    }

    @Test fun reachedWithoutAFixTheNoteSaysWaitingForGps() {
        touring(session(reached = true)); gps.here = null
        slice.openActivityCheck()
        assertEquals("Waiting for GPS…", slice.activityCheck!!.note)
        assertEquals("only a note: the rows still wait", true, slice.activityCheck!!.rows.all { it.state == ActivityRowState.Idle })
    }

    @Test fun neverReachedNeedsNoFixAndSaysNothingAboutIt() {
        touring(session(reached = false)); gps.here = null
        slice.openActivityCheck()
        assertNull(slice.activityCheck!!.note)
    }

    @Test fun noRouteFollowedGivesPlainRowsAndTheNote() {
        // (1) free recording: the service has a state without a navigation; (2) a followed line of one point; (3) no tour at all
        val note = "No route is followed – the new activity applies to the tour and the plan."
        touring(null)
        slice.openActivityCheck()
        assertEquals(note, slice.activityCheck!!.note)
        assertTrue(slice.activityCheck!!.rows.all { it.state == ActivityRowState.Plain })
        assertEquals("the current activity is the plan's", Activity.HIKE, slice.activityCheck!!.current)
        assertEquals(3, slice.activityCheck!!.sac)
        assertTrue("every row at the remembered level", slice.activityCheck!!.rows.all { it.sac == 3 })

        touring(session().copy(original = listOf(stopA)))
        slice.openActivityCheck()
        assertEquals(note, slice.activityCheck!!.note)
        assertTrue(slice.activityCheck!!.rows.all { it.state == ActivityRowState.Plain })

        client.publish(null)
        slice.openActivityCheck()
        assertEquals(note, slice.activityCheck!!.note)
        assertEquals(0, engine.calls.size)
    }

    @Test fun openingAgainClosesTheOldSheetAndStopsItsCheck() {
        touring(session()); gps.here = fix
        slice.openActivityCheck()
        slice.chooseActivity(Activity.MTB)
        assertEquals(ActivityRowState.Checking, rowsOf()[Activity.MTB])
        slice.openActivityCheck()
        assertTrue("fresh rows, the running check is gone", slice.activityCheck!!.rows.all { it.state == ActivityRowState.Idle })
        stepCpu(); while (checkEx.runNext()) Unit
        assertEquals("the stopped check never reached the engine", 0, engine.calls.size)
        assertTrue(client.sent.isEmpty())
    }

    // ======================================================================================================================
    // a row: routed alone, on its own thread; found on the route → the proposal (rules 1, 24)
    // ======================================================================================================================

    private fun stepsForTrekking(a: Activity, @Suppress("UNUSED_PARAMETER") sac: Int, r: RouteResult) =
        if (a == Activity.BIKE_TREKKING) r.copy(wayTypes = listOf(Share("steps", 40.0)), distanceM = r.distanceM + 800.0) else r

    @Test fun aTapRoutesThatRowAloneFromThePositionOnTheCheckThread() {
        touring(session(Activity.BIKE_TOURING, sac = 2)); gps.here = fix
        engine.script = ::stepsForTrekking
        slice.openActivityCheck()
        slice.chooseActivity(Activity.BIKE_TREKKING)
        // the row spins at once, the others wait; the line says where the rest starts
        assertEquals(ActivityRowState.Checking, rowsOf()[Activity.BIKE_TREKKING])
        assertEquals(4, rowsOf().count { it.value == ActivityRowState.Idle })
        assertEquals(listOf("Activity check: routing the rest for BIKE_TREKKING from the position"), traced().filter { it.startsWith("Activity check") })
        // the rest of the route is computed on the pure thread (held), THEN the row is routed on the check thread (held)
        assertEquals(1, cpuEx.size); assertEquals(0, checkEx.size); assertEquals(0, engine.calls.size)
        stepCpu()
        assertEquals(1, checkEx.size); assertEquals(0, engine.calls.size)
        stepRow()
        val call = engine.calls.single()
        assertEquals(Activity.BIKE_TREKKING, call.activity)
        assertEquals("the rest: the fix with its altitude, then the stops not passed", listOf(fix, stopB, stopC), call.waypoints)
        assertEquals(612.0, call.waypoints.first().ele!!, 0.0)
    }

    @Test fun foundOnTheRouteTheProposalFileIsWrittenBeforeTheCommandAndTheSheetIsClosed() {
        touring(session(Activity.BIKE_TOURING, sac = 2)); gps.here = fix
        engine.script = ::stepsForTrekking
        mode = AppMode.NAVIGATE
        slice.openActivityCheck()
        tapAndRoute(Activity.BIKE_TREKKING)

        // the command went out in the same turn as the routing result: no hop, nothing waiting on io
        assertEquals(listOf("PROPOSE_SWITCH"), sentActions())
        val s = atSend.single()
        assertEquals("the sheet is closed when the service hears", false, s.sheetOpen)
        assertEquals(0, s.ioWaiting)
        val p = s.proposal
        assertNotNull("the proposal file is on disk at the moment the command arrives", p)
        assertEquals(Activity.BIKE_TREKKING, p!!.activity); assertEquals(2, p.sac)
        assertEquals(NavKind.PLAN, p.kind); assertEquals("Plan", p.name)
        // on the route the new line starts at the position: an extra first stop; the names and the pin move with it
        assertEquals(listOf(p.original.first(), stopB, stopC), p.stops.map { it.point })
        assertEquals(listOf(null, "B", "C"), p.stops.map { it.name })
        assertEquals(listOf(false, true, false), p.stops.map { it.pinned })
        // the time is the fit's, as the engine's result gives it for that activity
        val timeS = s.extras[RecordingWire.EXTRA_TIME_S] as Double
        assertEquals(ActivityCheck.timeS(RouteResult(p.original, 0.0, 0.0, 0.0, 0.0), Activity.BIKE_TREKKING, planRoute.planFactors(Activity.BIKE_TREKKING)), timeS, 0.0)
        // the service takes it once
        assertEquals(p, followStore.takeProposal())
        assertNull(followStore.takeProposal())
        // in Navigate nothing else happens; the plan is not touched
        assertFalse(log.any { it.startsWith("recalc") || it == "enterNavigate" })
        assertTrue(client.sent.size == 1 && ioEx.size == 0)
        assertEquals(Activity.HIKE, planRoute.activity)
        assertEquals(Triple(Activity.HIKE, 3, 3), Triple(prefs.activity, prefs.sac, planRoute.sac))
        assertEquals(tourRow(), "HIKE" to 2)
    }

    @Test fun chosenInPlanDuringATourTheQuestionIsAskedInNavigateAfterTheCommand() {
        touring(session()); gps.here = fix; mode = AppMode.PLAN
        slice.openActivityCheck()
        tapAndRoute(Activity.MTB)
        assertEquals(listOf("send PROPOSE_SWITCH", "enterNavigate"), log.filter { it.startsWith("send") || it == "enterNavigate" })
        assertEquals("the mode was still Plan when the command went out", AppMode.PLAN, atSend.single().mode)
        assertEquals(AppMode.NAVIGATE, mode)
    }

    @Test fun aFollowedTrackProposesTheNewLineWithoutStops() {
        touring(session(Activity.HIKE, sac = 2, kind = NavKind.TRACK)); gps.here = fix
        slice.openActivityCheck()
        tapAndRoute(Activity.EBIKE)
        val p = atSend.single().proposal!!
        assertEquals(NavKind.TRACK, p.kind); assertEquals("Track", p.name); assertEquals(Activity.EBIKE, p.activity)
        assertTrue("a track has no stops", p.stops.isEmpty())
        assertEquals("the waypoints routed were the position, vias along the track and its end", fix, engine.calls.single().waypoints.first())
    }

    @Test fun eachRowGetsItsOwnAnswerAndTheFailedOnesStayOnTheSheet() {
        touring(session(Activity.BIKE_TOURING, sac = 2)); gps.here = fix
        engine.script = ::stepsForTrekking
        engine.fails = { if (it == Activity.EBIKE) RoutingException("No way here") else null }
        slice.openActivityCheck()
        tapAndRoute(Activity.EBIKE)
        assertEquals("a failed row says so and stays", ActivityRowState.Failed("No route found for it"), rowsOf()[Activity.EBIKE])
        assertTrue("…the sheet stays open and nothing was sent", slice.activityCheck != null && client.sent.isEmpty())
        assertEquals(listOf("Activity check: routing for another activity failed"), problems.said("routing", com.onpath.app.debug.Problems.Level.WARN))
        assertNull(proposalFile.takeIf { it.exists() })
        // …and the next tap routes the next row: the failed one is not tapped again (not choosable)
        slice.chooseActivity(Activity.EBIKE)
        assertEquals(ActivityRowState.Failed("No route found for it"), rowsOf()[Activity.EBIKE])
        assertEquals(0, cpuEx.size)
        tapAndRoute(Activity.BIKE_TREKKING)
        assertEquals(listOf("PROPOSE_SWITCH"), sentActions())
        assertEquals(Activity.BIKE_TREKKING, atSend.single().proposal!!.activity)
        assertEquals(listOf(Activity.EBIKE, Activity.BIKE_TREKKING), engine.asked)
        assertTrue("the finished requests were not told to stop afterwards", engine.terminatedAt.isEmpty())
    }

    @Test fun aFinishedRowIsNotCancelledAgainWhenTheSheetCloses() {
        touring(session()); gps.here = fix
        engine.fails = { RoutingException("none") }
        slice.openActivityCheck(); tapAndRoute(Activity.MTB)
        slice.closeActivityCheck()
        assertTrue("its search was over: nothing to terminate", engine.terminatedAt.isEmpty())
        assertFalse(engine.cancels.single()!!.isCancelled)
    }

    @Test fun theRestOfTheRouteIsComputedOncePerSheetAndForgottenWithIt() {
        touring(session()); gps.here = fix
        engine.fails = { RoutingException("none") }
        slice.openActivityCheck()
        slice.chooseActivity(Activity.MTB); assertEquals(1, cpuEx.size); stepCpu(); stepRow()
        slice.chooseActivity(Activity.EBIKE); assertEquals("kept for the next row", 0, cpuEx.size); stepRow()
        slice.chooseHikeLevel(5)
        assertEquals(5, slice.activityCheck!!.rows.single { it.activity == Activity.HIKE }.sac)
        slice.closeActivityCheck()
        slice.openActivityCheck()
        assertEquals("the chip's level is forgotten with the sheet", 3, slice.activityCheck!!.rows.single { it.activity == Activity.HIKE }.sac)
        slice.chooseActivity(Activity.MTB)
        assertEquals("the rest is computed again for the new sheet", 1, cpuEx.size)
    }

    // ======================================================================================================================
    // a new tap, Keep and Back stop the running check; a result of a stopped check changes nothing (rule 23)
    // ======================================================================================================================

    @Test fun aSecondTapBeforeTheFirstReturnsStopsTheFirstAndOnlyTheSecondIsRouted() {
        touring(session()); gps.here = fix
        slice.openActivityCheck()
        slice.chooseActivity(Activity.BIKE_TREKKING)
        stepCpu()
        slice.chooseActivity(Activity.MTB)             // the first row is waiting for the check thread
        assertEquals("the first row is plain again, only the second spins", ActivityRowState.Idle, rowsOf()[Activity.BIKE_TREKKING])
        assertEquals(ActivityRowState.Checking, rowsOf()[Activity.MTB])
        val cancels = engine.cancels
        stepCpu()
        while (checkEx.runNext()) Unit
        assertEquals("the first row never reached the engine", listOf(Activity.MTB), engine.calls.map { it.activity })
        assertEquals(listOf("PROPOSE_SWITCH"), sentActions())
        assertEquals(Activity.MTB, atSend.single().proposal!!.activity)
        assertEquals(1, cancels.size)
    }

    @Test fun aTapWhileBrouterIsRoutingTerminatesItsSearchBeforeTheJobIsCancelled() {
        touring(session()); gps.here = fix
        slice.openActivityCheck()
        slice.chooseActivity(Activity.BIKE_TREKKING); stepCpu()
        var childrenCancelledWhenRouterStopped = -1
        engine.onRoute = { a ->
            if (a == Activity.BIKE_TREKKING) {
                engine.cancels.last()!!.onCancel { childrenCancelledWhenRouterStopped = scope.coroutineContext.job.children.count { it.isCancelled } }
                engine.onRoute = {}
                slice.chooseActivity(Activity.MTB)      // the new tap, while the first search runs
            }
        }
        stepRow()
        assertEquals("the router stopped first: the check's job was not cancelled yet", 0, childrenCancelledWhenRouterStopped)
        assertTrue("the first search was told to stop", engine.cancels.first()!!.isCancelled)
    }

    @Test fun theLateResultOfAStoppedCheckChangesNothingAndSendsNothing() {
        touring(session()); gps.here = fix
        engine.ignoreCancel = true       // BRouter does not look at the cancel: its result comes anyway
        slice.openActivityCheck()
        slice.chooseActivity(Activity.BIKE_TREKKING); stepCpu()
        engine.onRoute = { a ->
            if (a == Activity.BIKE_TREKKING) { engine.onRoute = {}; slice.chooseActivity(Activity.MTB) }
        }
        stepRow()
        // the first result is back (a good route, "found") – and was dropped: no sheet change, no file, no command
        assertEquals(listOf(Activity.BIKE_TREKKING), engine.calls.map { it.activity })
        assertTrue(client.sent.isEmpty()); assertFalse(proposalFile.exists())
        assertEquals(ActivityRowState.Idle, rowsOf()[Activity.BIKE_TREKKING])
        assertEquals(ActivityRowState.Checking, rowsOf()[Activity.MTB])
        assertEquals("nothing of the stopped check was reported as a problem", true, problems.none())
    }

    @Test fun keepOrBackWhileTheRowWaitsForTheCheckThreadAppliesNothing() {
        touring(session()); gps.here = fix
        slice.openActivityCheck()
        slice.chooseActivity(Activity.BIKE_TREKKING); stepCpu()
        slice.closeActivityCheck()
        assertNull(slice.activityCheck)
        while (checkEx.runNext()) Unit; stepCpu(); stepIo()
        assertEquals(0, engine.calls.size)
        assertTrue(client.sent.isEmpty()); assertFalse(proposalFile.exists()); assertEquals(0, ioEx.size)
        assertEquals(Triple(Activity.HIKE, 3, 3), Triple(prefs.activity, prefs.sac, planRoute.sac))
    }

    @Test fun keepWhileBrouterRoutesAppliesNothingEvenWhenItsResultComesAnyway() {
        touring(session()); gps.here = fix
        engine.ignoreCancel = true
        slice.openActivityCheck()
        slice.chooseActivity(Activity.MTB); stepCpu()
        engine.onRoute = { slice.closeActivityCheck() }
        stepRow()
        assertEquals(listOf(Activity.MTB), engine.calls.map { it.activity })
        assertNull(slice.activityCheck)
        assertTrue(client.sent.isEmpty()); assertFalse(proposalFile.exists())
        assertEquals("the router was told to stop", listOf("terminated"), engine.terminatedAt)
        assertTrue(problems.none())
    }

    @Test fun aCancelledSearchIsTracedNotReportedAsAProblem() {
        touring(session()); gps.here = fix
        slice.openActivityCheck()
        slice.chooseActivity(Activity.MTB); stepCpu()
        engine.onRoute = { slice.closeActivityCheck() }      // the engine then sees the cancel and throws "cancelled"
        stepRow()
        assertTrue("nothing at WARN or above", problems.none())
        assertEquals(listOf("Activity check for MTB cancelled (a new tap or the sheet closed)"), problems.traced("routing"))
        assertTrue(client.sent.isEmpty())
    }

    @Test fun keepWhileTheRestOfTheRouteIsComputedStopsItBeforeAnythingIsRouted() {
        touring(session()); gps.here = fix
        slice.openActivityCheck()
        slice.chooseActivity(Activity.MTB)           // the cpu step is waiting
        slice.closeActivityCheck()
        stepCpu(); while (checkEx.runNext()) Unit
        assertEquals(0, engine.calls.size); assertEquals(0, checkEx.size)
        assertTrue(client.sent.isEmpty())
    }

    // ======================================================================================================================
    // no fix, no routing data, nothing left
    // ======================================================================================================================

    @Test fun withoutAFixTheCheckWaitsForTheFirstOneAndThenRoutesFromIt() {
        touring(session(reached = true)); gps.here = null
        slice.openActivityCheck()
        assertEquals("Waiting for GPS…", slice.activityCheck!!.note)
        slice.chooseActivity(Activity.MTB)
        assertEquals(1, gps.firsts)
        assertEquals("nothing is computed or routed while there is no fix", 0, cpuEx.size + checkEx.size + engine.calls.size)
        assertEquals("the row spins; the note stays until a result", ActivityRowState.Checking, rowsOf()[Activity.MTB])
        val first = GeoPoint(fix.lat, fix.lon, null)    // a fix without altitude
        gps.arrive(first)
        stepCpu(); stepRow()
        assertEquals(first, engine.calls.single().waypoints.first())
        assertNull(engine.calls.single().waypoints.first().ele)
        assertEquals(listOf("PROPOSE_SWITCH"), sentActions())
    }

    @Test fun backWhileWaitingForTheFirstFixStopsTheCheck() {
        touring(session(reached = true)); gps.here = null
        slice.openActivityCheck()
        slice.chooseActivity(Activity.MTB)
        slice.closeActivityCheck()
        gps.arrive(fix)
        stepCpu(); while (checkEx.runNext()) Unit
        assertEquals(0, engine.calls.size)
        assertTrue(client.sent.isEmpty()); assertNull(slice.activityCheck)
    }

    @Test fun neverReachedRoutesFromTheRoutesStartWithoutAFix() {
        touring(session(reached = false)); gps.here = null
        slice.openActivityCheck()
        slice.chooseActivity(Activity.MTB)
        assertEquals(0, gps.firsts)
        assertEquals(listOf("Activity check: routing the rest for MTB from the route's start (never reached)"),
            traced().filter { it.startsWith("Activity check") })
        stepCpu(); stepRow()
        assertEquals("never reached: from the first stop, not from where the user stands", listOf(stopA, stopB, stopC), engine.calls.single().waypoints)
    }

    @Test fun noRoutingDataSaysSoOnTheRowAndTheSheetStays() {
        touring(session()); gps.here = fix; straight = true
        slice.openActivityCheck()
        tapAndRoute(Activity.MTB)
        assertEquals(ActivityRowState.Failed("Can't check routes right now"), rowsOf()[Activity.MTB])
        assertTrue(client.sent.isEmpty()); assertTrue(problems.none())
        assertEquals("the stand-in engine was never asked", 0, engine.calls.size)
    }

    @Test fun aRouteOfOnePointIsNoRoute() {
        touring(session()); gps.here = fix; engine.tooShort = true
        slice.openActivityCheck()
        tapAndRoute(Activity.MTB)
        assertEquals(ActivityRowState.Failed("No route found for it"), rowsOf()[Activity.MTB])
        assertTrue(client.sent.isEmpty()); assertTrue(problems.none())
    }

    @Test fun nothingLeftOfTheRouteSaysSoOnEveryRow() {
        touring(session(), progressM = 99_999.0); gps.here = fix     // at the end of the line
        slice.openActivityCheck()
        slice.chooseActivity(Activity.MTB); stepCpu()
        assertEquals("every row, not only the tapped one", setOf(ActivityRowState.Failed("Nothing left of the route to check")),
            rowsOf().values.toSet())
        assertNull(slice.activityCheck!!.note)
        assertEquals(0, checkEx.size); assertTrue(client.sent.isEmpty())
    }

    // ======================================================================================================================
    // a found switch never reached the route and following the plan → the plan is planned again (rule 24)
    // ======================================================================================================================

    private fun neverReachedPlan() { touring(session(Activity.BIKE_TOURING, sac = 2, reached = false)); gps.here = null; followFromPlan = true; planPoints = 3 }

    @Test fun neverReachedFollowingThePlanPlansThePlanAgainAndFollowsItWithoutAQuestion() {
        neverReachedPlan(); mode = AppMode.PLAN
        slice.openActivityCheck()
        tapAndRoute(Activity.BIKE_TREKKING)
        assertTrue("no proposal, no question, no command", client.sent.isEmpty() && !proposalFile.exists() && atSend.isEmpty())
        // the order: "follow the next result" BEFORE the activity change that starts the run; the activity reaches the prefs; no mode change
        val seen = log.filterNot { it.startsWith("clearShownNote") }
        assertEquals(listOf(
            "FollowNext-host ${RouteRun.Event.FollowNext}",
            "event ${RouteRun.Event.CompareActivity(false)}",
            "recalc BIKE_TREKKING T3 prefs=BIKE_TREKKING/T3"), seen)
        assertEquals(Activity.BIKE_TREKKING, planRoute.activity); assertEquals(Activity.BIKE_TREKKING, prefs.activity)
        assertEquals("not the user's own picker: Cancel then only stops", PlanStatus.Cause.Other, planRoute.runCauses.next)
        assertNull("the sheet closed", slice.activityCheck)
        assertEquals(AppMode.PLAN, mode)
        assertEquals("the tour's row follows only when io runs", "HIKE" to 2, tourRow())
        assertEquals(1, ioEx.size)
        stepIo()
        assertEquals("the row of My routes: the new activity at the session's level", "BIKE_TREKKING" to 2, tourRow())
        assertEquals("updateActivity($tourId, BIKE_TREKKING, T2)", log.last())
        assertEquals(listOf("Activity check: routing the rest for BIKE_TREKKING from the route's start (never reached)",
            "Activity check: route never reached – the plan is planned again as BIKE_TREKKING and followed"), traced().filter { it.startsWith("Activity check") })
    }

    @Test fun neverReachedHikeAtALevelStoresTheLevelFirstInThePlanAndInThePrefs() {
        neverReachedPlan()
        slice.openActivityCheck()
        slice.chooseHikeLevel(9)                  // out of range: T5
        assertEquals(5, slice.activityCheck!!.rows.single { it.activity == Activity.HIKE }.sac)
        stepCpu(); stepRow()
        assertEquals(listOf(
            "rememberSac(5)",
            "FollowNext-host ${RouteRun.Event.FollowNext}",
            "event ${RouteRun.Event.CompareActivity(false)}",
            "recalc HIKE T5 prefs=HIKE/T5"), log.filterNot { it.startsWith("clearShownNote") })
        assertEquals(5, planRoute.sac); assertEquals(5, prefs.sac)
        stepIo()
        assertEquals("HIKE" to 5, tourRow())
    }

    @Test fun neverReachedButNotFollowingThePlanAsksTheServiceLikeOnTheRoute() {
        neverReachedPlan(); followFromPlan = false
        slice.openActivityCheck(); tapAndRoute(Activity.MTB)
        assertEquals(listOf("PROPOSE_SWITCH"), sentActions())
        val p = atSend.single().proposal!!
        assertEquals("never reached: the stops start at the first one", listOf(stopA, stopB, stopC), p.stops.map { it.point })
        assertEquals(listOf("A", "B", "C"), p.stops.map { it.name }); assertEquals(listOf(false, true, false), p.stops.map { it.pinned })
        assertEquals(0, ioEx.size); assertFalse(log.any { it.startsWith("recalc") })
    }

    @Test fun neverReachedWithAPlanOfOnePointOrATrackAsksTheServiceToo() {
        neverReachedPlan(); planPoints = 1
        slice.openActivityCheck(); tapAndRoute(Activity.MTB)
        assertEquals(listOf("PROPOSE_SWITCH"), sentActions())
        client.sent.clear(); atSend.clear(); log.clear()
        touring(session(reached = false, kind = NavKind.TRACK)); planPoints = 3
        slice.openActivityCheck(); tapAndRoute(Activity.EBIKE)
        assertEquals(listOf("PROPOSE_SWITCH"), sentActions())
        assertTrue(atSend.single().proposal!!.stops.isEmpty())
        assertFalse(log.any { it.startsWith("recalc") })
    }

    @Test fun reachedFollowingThePlanStillAsksTheService() {
        touring(session(reached = true)); gps.here = fix; followFromPlan = true; planPoints = 3
        slice.openActivityCheck(); tapAndRoute(Activity.MTB)
        assertEquals(listOf("PROPOSE_SWITCH"), sentActions())
        assertEquals(0, ioEx.size)
    }

    // ======================================================================================================================
    // free recording (no route followed): plain choices, no routing
    // ======================================================================================================================

    @Test fun inAFreeRecordingARowSwitchesAtOnceAndTheToursRowFollowsOnIo() {
        touring(null)
        slice.openActivityCheck()
        slice.chooseActivity(Activity.EBIKE)
        assertNull("the sheet closed", slice.activityCheck)
        assertEquals(Activity.EBIKE, planRoute.activity); assertEquals(Activity.EBIKE, prefs.activity)
        assertEquals("taken over from the sheet, not the user's own picker: Cancel then only stops", PlanStatus.Cause.Other, planRoute.runCauses.next)
        assertEquals("the plan's activity, as the picker does it: no switch in the tour's session", true, client.sent.isEmpty())
        assertEquals(0, engine.calls.size + cpuEx.size + checkEx.size)
        assertEquals("not stored before io runs", "HIKE" to 2, tourRow())
        stepIo()
        assertEquals("the remembered level goes with it", "EBIKE" to 3, tourRow())
        assertEquals(listOf("event ${RouteRun.Event.CompareActivity(false)}", "recalc EBIKE T3 prefs=EBIKE/T3", "clearShownNote", "updateActivity($tourId, EBIKE, T3)"), log)
        assertFalse(proposalFile.exists())
    }

    @Test fun withoutATourThereIsNothingToStore() {
        client.publish(null)
        slice.openActivityCheck()
        slice.chooseActivity(Activity.MTB)
        assertEquals(Activity.MTB, planRoute.activity)
        assertEquals(0, ioEx.size)
        assertEquals("HIKE" to 2, tourRow())
    }

    @Test fun inAFreeRecordingAHikeChipSelectsHikeAtThatLevelAndStoresIt() {
        touring(null); planRoute.selectActivity(Activity.MTB); log.clear()
        slice.openActivityCheck()
        assertEquals(Activity.MTB, slice.activityCheck!!.current)
        slice.chooseHikeLevel(4)
        assertNull(slice.activityCheck)
        assertEquals(Activity.HIKE, planRoute.activity); assertEquals(4, planRoute.sac); assertEquals(4, prefs.sac)
        stepIo()
        assertEquals("HIKE" to 4, tourRow())
        assertTrue(log.indexOf("recalc HIKE T3 prefs=HIKE/T3") < log.indexOf("recalc HIKE T4 prefs=HIKE/T4"))
        assertEquals("the Hike chip was traced", listOf("Activity check: Hike T4 chosen (was MTB)"), traced().filter { it.startsWith("Activity check") })
        assertTrue(client.sent.isEmpty())
    }

    @Test fun aHikeChipAtTheRememberedLevelSelectsHikeAsTakenOverNotAsTheUsersOwnPick() {
        touring(null); planRoute.selectActivity(Activity.MTB)
        slice.openActivityCheck()
        slice.chooseHikeLevel(3)                       // the remembered level: selectSac does nothing, the activity change stands alone
        assertEquals(Activity.HIKE, planRoute.activity)
        assertEquals("Cancel then only stops", PlanStatus.Cause.Other, planRoute.runCauses.next)
        stepIo()
        assertEquals("HIKE" to 3, tourRow())
    }

    @Test fun aWriteThatFailsIsReportedAndTheAppKeepsTheChoice() {
        touring(null)
        slice.openActivityCheck(); slice.chooseActivity(Activity.MTB)
        db.failWrites = 1
        stepIo()
        assertEquals(listOf("Activity of the recorded tour not updated in the database"), problems.said("db", com.onpath.app.debug.Problems.Level.ERROR))
        assertEquals(Activity.MTB, planRoute.activity)
        assertEquals("HIKE" to 2, tourRow())
    }

    @Test fun aChoiceOnAClosedSheetDoesNothing() {
        touring(session()); gps.here = fix
        slice.chooseActivity(Activity.MTB); slice.chooseHikeLevel(3); slice.closeActivityCheck()
        assertEquals(0, cpuEx.size + ioEx.size + checkEx.size)
        assertEquals(Activity.HIKE, planRoute.activity)
        assertTrue(client.sent.isEmpty() && log.isEmpty() && traced().none { it.startsWith("Activity check") })
    }

    @Test fun aRowThatIsCheckingOrFailedIsNotChoosableAgain() {
        touring(session()); gps.here = fix
        engine.fails = { RoutingException("none") }
        slice.openActivityCheck(); tapAndRoute(Activity.MTB)
        assertEquals(1, engine.asked.size)
        slice.chooseActivity(Activity.MTB)                                    // Failed: not choosable
        assertEquals(0, checkEx.size); assertEquals(1, engine.asked.size)
        slice.chooseActivity(Activity.BIKE_TREKKING)                          // Idle: routed (the rest of the route is kept from the first tap: no cpu step)
        slice.chooseActivity(Activity.BIKE_TREKKING)                          // Checking: not routed a second time
        assertEquals("one request for the tap", 1, checkEx.size); assertEquals(0, cpuEx.size)
        assertEquals(1, slice.activityCheck!!.rows.count { it.state == ActivityRowState.Checking })
    }

    // ======================================================================================================================
    // the Hike chips on the route (K1)
    // ======================================================================================================================

    @Test fun aHikeChipOnTheRouteRoutesTheHikeRowAloneForThatLevel() {
        touring(session(Activity.BIKE_TOURING, sac = 2)); gps.here = fix
        slice.openActivityCheck()
        slice.chooseHikeLevel(4)
        val hike = slice.activityCheck!!.rows.single { it.activity == Activity.HIKE }
        assertEquals(4, hike.sac); assertEquals(ActivityRowState.Checking, hike.state)
        assertEquals(listOf("Activity check: Hike T4 chosen (was BIKE_TOURING)", "Activity check: routing the rest for HIKE T4 from the position"),
            traced().filter { it.startsWith("Activity check") })
        stepCpu(); stepRow()
        assertEquals(Activity.HIKE to 4, engine.calls.single().let { it.activity to it.sac })
        val p = atSend.single().proposal!!
        assertEquals(Activity.HIKE, p.activity); assertEquals("the level of the chip", 4, p.sac)
    }

    @Test fun hikingAtThatLevelAlreadyHasNoHikeRowAndAHikeCheckRunningStops() {
        touring(session(Activity.HIKE, sac = 2)); gps.here = fix
        slice.openActivityCheck()
        assertTrue("hiking now: no Hike row", slice.activityCheck!!.rows.none { it.activity == Activity.HIKE })
        slice.chooseHikeLevel(2)                                   // the level hiked: nothing to switch to, nothing traced
        assertTrue(traced().none { it.startsWith("Activity check: Hike") })
        slice.chooseHikeLevel(4)                                   // another level: a Hike row for it, routed
        assertEquals(ActivityRowState.Checking, rowsOf()[Activity.HIKE])
        stepCpu()
        slice.chooseHikeLevel(2)                                   // back to the level hiked while the Hike row routes: it goes, the check stops
        assertTrue(slice.activityCheck!!.rows.none { it.activity == Activity.HIKE })
        while (checkEx.runNext()) Unit
        assertEquals(0, engine.calls.size)
        assertTrue(client.sent.isEmpty())
    }

    @Test fun aChipLevelIsKeptWithinOneToFive() {
        touring(session(Activity.BIKE_TOURING, sac = 2)); gps.here = fix
        slice.openActivityCheck()
        slice.chooseHikeLevel(0)
        assertEquals(1, slice.activityCheck!!.rows.single { it.activity == Activity.HIKE }.sac)
        assertEquals(listOf("Activity check: Hike T1 chosen (was BIKE_TOURING)"), traced().filter { it.startsWith("Activity check: Hike") })
        slice.chooseHikeLevel(99)
        assertEquals(5, slice.activityCheck!!.rows.single { it.activity == Activity.HIKE }.sac)
    }

    @Test fun theChipLevelIsTheRowsLevelForTheSwitchOfAnotherHikeRowToo() {
        // sacFor: once a chip chose T5, a Hike row of a free choice is not possible (free closes the sheet) – on the route the row keeps it
        touring(session(Activity.BIKE_TOURING, sac = 2)); gps.here = fix
        slice.openActivityCheck()
        slice.chooseHikeLevel(5)
        slice.chooseActivity(Activity.MTB)
        assertEquals("a tap on another row stops the Hike row: plain again, at the chip's level", ActivityRowState.Idle, rowsOf()[Activity.HIKE])
        assertEquals(5, slice.activityCheck!!.rows.single { it.activity == Activity.HIKE }.sac)
    }

    // ======================================================================================================================
    // what the service answers
    // ======================================================================================================================

    @Test fun theServiceTakesTheProposalOnceAndANewSheetShowsItsActivity() {
        touring(session(Activity.BIKE_TOURING, sac = 2)); gps.here = fix
        slice.openActivityCheck(); tapAndRoute(Activity.MTB)
        val proposed = followStore.takeProposal()!!          // the service's turn
        assertEquals(Activity.MTB, proposed.activity); assertNull(followStore.takeProposal())
        touring(proposed.copy(reached = true))                // the service navigates it now ("Take new route")
        slice.openActivityCheck()
        assertEquals(Activity.MTB, slice.activityCheck!!.current)
        assertTrue("the activity navigated is not offered", slice.activityCheck!!.rows.none { it.activity == Activity.MTB })
        assertTrue(slice.activityCheck!!.rows.any { it.activity == Activity.BIKE_TOURING })
        assertEquals("the sheet itself never changes the app's activity here (the service's switch is followed by the follow state)", Activity.HIKE, planRoute.activity)
        assertEquals(1, client.sent.size)
    }

    @Test fun aProposalThatCannotBeWrittenIsReportedByTheStoreAndTheCommandStillGoesOut() {
        // kept as it is today (the service then finds no file and says nothing): the file cannot be written – the store reports it
        touring(session(Activity.BIKE_TOURING, sac = 2)); gps.here = fix
        assertTrue(proposalFile.mkdir())                      // a folder where the file belongs
        slice.openActivityCheck(); tapAndRoute(Activity.MTB)
        assertEquals(listOf("Navigation session file could not be saved; a restart may lose the route"),
            problems.said("navigation", com.onpath.app.debug.Problems.Level.ERROR))
        assertEquals(listOf("PROPOSE_SWITCH"), sentActions())
        assertNull("no proposal was on disk when it was sent", atSend.single().proposal)
        assertNull(slice.activityCheck)
    }
}
