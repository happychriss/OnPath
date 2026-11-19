package com.onpath.app.ui.tour

import com.onpath.app.data.AppSettings
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.FollowStore
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavStop
import com.onpath.app.navigation.NavState
import com.onpath.app.recording.RecState
import com.onpath.app.recording.RecordingWire
import com.onpath.app.rides.TraceBuilder
import com.onpath.app.rides.record
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.StraightLineEngine
import com.onpath.app.testkit.FakeRecordingClient
import com.onpath.app.testkit.JdbcTourRows
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.ManualExecutor
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.testkit.VirtualDelayDispatcher
import com.onpath.app.testkit.held
import com.onpath.app.time.TourTimes
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.Say
import com.onpath.app.ui.ShownTrack
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanRouteHost
import com.onpath.app.ui.plan.PlanRoutePrefs
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.app.ui.plan.PlanStore
import com.onpath.app.ui.plan.RouterPort
import com.onpath.app.ui.plan.RoutingThreads
import com.onpath.app.ui.plan.TimesStore
import com.onpath.app.ui.routes.PlannedRow
import com.onpath.app.ui.routes.RoutesState
import com.onpath.app.ui.routes.RoutesStore
import com.onpath.app.ui.routes.ToursStore
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
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
 * What the tour follows (rebuild phase 2 piece 18a) as flows of the REAL [TourFollow] with the real [PlanDocument], the real
 * [PlanRouteSlice] (activity / T level / `selectActivity`), the real [FollowStore] on a temp folder, the real
 * [com.onpath.app.domain.tour.TourPlanCopy] over the real tables ([JdbcTours], the SQL of `TourDb`) and – for the tour's end – the
 * real [TourEndSlice] (docs/design/umbau-phase-2-tour.md, rules 1, 18–21, 25, 33). Fakes only at the edges:
 *  - the recording client ([FakeRecordingClient]) records, AT THE MOMENT each command arrives, what the app held: both follow
 *    files as the service would read them, the follow state, the plan copy's queue, the stale-request count ([atSend]);
 *  - the plan copy's ONE serial thread is a [ManualExecutor] the test steps (what is written, and in which order, is read
 *    from the rows after a step; the rows and the saved route's uuid are reached only on it);
 *  - the host logs every call with the state it had at the call; the scope is main, inline, with virtual time.
 * What the tests do NOT prove: that the view model hands the slice ONE `limitedParallelism(1)` dispatcher (`TourWiringTest`
 * pins it), the Android stores behind the adapters (`TourWiringTest`, `TourDbMirrorTest`), the service itself.
 *
 * Test data tells things apart: a plan of three stops A, B (pinned), C routed along a five-point line, a hiking activity at
 * T3, a service that already navigates version 4, tours 1 (recorded, with a saved route "Rosellen" planned beside it).
 */
class TourFollowFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private val client = FakeRecordingClient()
    private val db = JdbcTours()
    private val log = ArrayList<String>()

    // ---------------- threads: main (inline, virtual time), the plan copy's serial thread (held), io (held, for the tour's end) ----------------

    private val pcEx = ManualExecutor("planCopy")
    private val ioEx = ManualExecutor("io")
    private var onMain = 0
    private var inPc = false
    private val main = VirtualDelayDispatcher { r -> onMain++; try { r.run() } finally { onMain-- } }
    private val scope = CoroutineScope(main + SupervisorJob())
    private val planCopyDispatcher = held(pcEx)
    private val io = held(ioEx)

    private fun stepPc(): Int { inPc = true; try { return pcEx.runAll() } finally { inPc = false } }
    private fun stepIo() = ioEx.runAll()
    private fun <T> ui(f: () -> T): T { onMain++; try { return f() } finally { onMain-- } }
    private fun threadOf() = if (onMain > 0) "main" else if (inPc) "pc" else "?"
    private fun assertMain(what: String) = check(onMain > 0) { "$what must run on the main thread" }
    private fun assertPc(what: String) = check(inlineCopy || (inPc && onMain == 0)) { "$what must run on the plan copy's thread (main=$onMain pc=$inPc)" }

    // ---------------- the data ----------------

    private val a = GeoPoint(46.600, 11.100)
    private val b = GeoPoint(46.610, 11.110)
    private val c = GeoPoint(46.620, 11.120)
    private val d = GeoPoint(46.630, 11.130)
    private val e = GeoPoint(46.640, 11.140)
    private val routeLine = listOf(a, GeoPoint(46.605, 11.105), b, GeoPoint(46.615, 11.115), c)
    private fun route(line: List<GeoPoint>, km: Double = 5.0) =
        PlannedRoute(listOf(PlannedLeg(line, false)), km * 1000, 300.0, 150.0, km * 720, null)
    private val trackLine = listOf(GeoPoint(46.700, 11.200), GeoPoint(46.710, 11.210), GeoPoint(46.720, 11.220))
    private val names = mapOf(a to "Start hut", b to "Pass", c to "Summit")

    private val walk = record(TraceBuilder().walk(40.0).fixes)
    private fun recorded(name: String, uuid: String) = db.insertTour(TourKind.RECORDED, name, "HIKE", 3, walk.segments, state = "done",
        distance = walk.distanceM, ascent = 120.0, descent = 80.0, duration = 2400.0, uuid = uuid)
    private val tour7 = recorded("Tour 7", "uuid-7")
    private val tour8 = recorded("Tour 8", "uuid-8")
    private val savedRoute = db.insertTour(TourKind.PLANNED, "Rosellen", "HIKE", 3, uuid = "uuid-rosellen")

    // ---------------- the real neighbours ----------------

    private class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }
    private val planStore = MemoryPlanStore().also { it.points = listOf(a, b, c); it.pinned = setOf(b) }
    private val plan = PlanDocument(planStore)

    private class MemoryPrefs(override var activity: Activity = Activity.HIKE, override var sac: Int = 3) : PlanRoutePrefs
    private val routePrefs = MemoryPrefs()
    private var routeNow: PlannedRoute? = route(routeLine)

    /** A routing thread that never runs (nothing here routes). */
    private fun idle(name: String) = object : java.util.concurrent.AbstractExecutorService() {
        override fun execute(command: Runnable) = ManualExecutor(name).execute(command)
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
    }.asCoroutineDispatcher()

    private lateinit var planRoute: PlanRouteSlice
    private fun newPlanRoute() = PlanRouteSlice(
        prefs = routePrefs,
        router = object : RouterPort {
            override fun create(): RoutingEngine = StraightLineEngine()
            override val isRealRouter: Boolean get() = false
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
            override var route: PlannedRoute? = null
            override val routing: Boolean get() = false
            override val longRouteAsk: Double? get() = null
            override val mode: AppMode get() = AppMode.NAVIGATE
            override val touring: Boolean get() = client.state.value != null
            override fun recalc() { log += "recalc ${planRoute.activity.name} T${planRoute.sac} seen=${follow.navActivitySeen}" }
            override fun routeEvent(e: RouteRun.Event) = Unit
            override fun refreshTours() = Unit
            override fun clearShownNote() = Unit
            override fun showWhole(points: List<GeoPoint>) = Unit
        },
        threads = RoutingThreads(routing = idle("routing"), background = idle("background"), check = idle("check"), hold = idle("hold")),
    )

    // ---------------- the edges ----------------

    private class MemoryFollowPrefs(override var followIsTrack: Boolean = false) : FollowPrefs
    private val followPrefs = MemoryFollowPrefs()
    private val prefsWrites = ArrayList<Boolean>()
    private val prefs = object : FollowPrefs {
        override var followIsTrack: Boolean
            get() = followPrefs.followIsTrack
            set(v) { prefsWrites += v; followPrefs.followIsTrack = v }
    }

    private lateinit var files: FollowStore
    private val rows = JdbcTourRows(db) { name -> assertPc("TourRows.$name"); rowCalls += name }
    private val rowCalls = ArrayList<String>()
    private var shownNow: ShownTrack? = null
    private var invalidations = 0
    private var failUuidRead = false
    private var inlineCopy = false

    private val host = object : TourFollowHost {
        override val route: PlannedRoute? get() = routeNow
        override val shown: ShownTrack? get() = shownNow
        override fun placeName(p: GeoPoint): String? = names[p]
        override fun newRouteName(prefix: String) = "$prefix 19 Nov"
        override fun stopsAsSaved(pts: List<GeoPoint>): List<GpxWaypoint> = pts.mapIndexed { i, p -> GpxWaypoint(p.lat, p.lon, names[p] ?: "stop ${i + 1}") }
        override fun savedTourUuid(id: Long): String? {
            assertPc("host.savedTourUuid")
            log += "savedTourUuid($id) pc fromPlan=${follow.followFromPlan}"
            if (failUuidRead) throw java.sql.SQLException("unreadable")
            return db.getTour(id)?.uuid?.takeIf { it.isNotEmpty() }
        }
        override fun invalidateSplits() {
            assertMain("invalidateSplits")
            invalidations++
            log += "invalidateSplits [files=${files.load().follow?.first} session=${files.load().session != null} pcQueue=${pcEx.size}]"
        }
        override fun routeEvent(e: RouteRun.Event) {
            assertMain("routeEvent")
            log += "routeEvent $e original=${if (follow.followOriginal == trackLine) "session's" else "old"}"
        }
        override fun tourChanged(id: Long) { log += "tourChanged($id) ${threadOf()}" }
    }

    private lateinit var follow: TourFollow
    private fun newFollow() = TourFollow(plan, planRoute, client, files, prefs, rows, host, scope, planCopyDispatcher)

    /** What the app held at the moment a command reached the service. */
    private class AtSend(
        val action: String, val extras: Map<String, Any>, val onDisk: FollowStore.Followed, val name: String?, val line: List<GeoPoint>?,
        val original: List<GeoPoint>?, val baseline: List<GeoPoint>?, val isTrack: Boolean, val fromPlan: Boolean, val guidanceEnded: Boolean,
        val prefsTrack: Boolean, val atTrackFollow: List<GeoPoint>?, val invalidations: Int, val pcQueue: Int, val prefsWrites: Int,
    )
    private val atSend = ArrayList<AtSend>()

    init {
        tmp.create()
    }

    private var created = false
    private fun create() {
        if (created) return
        created = true
        files = FollowStore(tmp.root)
        planRoute = newPlanRoute()
        follow = newFollow()
        client.onSend = { cmd ->
            atSend += AtSend(cmd.action.substringAfterLast('.'), cmd.extras, files.load(), follow.followName, follow.followLine, follow.followOriginal,
                follow.planBaseline, follow.followIsTrack, follow.followFromPlan, follow.guidanceEndedByEmptyPlan, followPrefs.followIsTrack,
                follow.planAtTrackFollow, invalidations, pcEx.size, prefsWrites.size)
            log += "send ${cmd.action.substringAfterLast('.')}"
        }
    }

    @After fun close() { scope.cancel(); db.close() }

    private fun recState(id: Long, session: NavSession? = null, version: Int = session?.version ?: 0) = RecState(
        tourId = id, paused = false, distanceM = 100.0, ascentM = 0.0, descentM = 0.0, elapsedS = 60, gpsLost = false, offRouteM = null,
        offRouteAlert = false, followName = session?.name, track = emptyList(),
        nav = session?.let { NavState(it.active, version, null, null, null, null, it, 0.0) })

    private fun touring(id: Long, session: NavSession? = null, version: Int = session?.version ?: 0) = client.publish(recState(id, session, version))

    private fun session(name: String = "Service route", activity: Activity = Activity.HIKE, version: Int = 4, token: Long = 11L, line: List<GeoPoint> = routeLine) =
        NavSession(NavKind.PLAN, name, activity, 3, line, listOf(NavStop(a, 0.0, "Start hut", false), NavStop(c, 1000.0, "Summit", false)),
            version = version, token = token)

    private fun followedOnDisk() = files.load()
    private val last get() = atSend.last()

    // ======================================================================================================================
    // rule 1, 18: followPlanned / setFollow – the files, then the state, then the command (only during a tour)
    // ======================================================================================================================

    /** Without a tour: both files hold the session and the line (names, stops, pins), the state is set, the service is not told. */
    @Test fun followingThePlanWithoutATourWritesTheFilesAndSendsNoCommand() {
        create()
        follow.guidanceEndedByEmptyPlan = true
        follow.planBaseline = listOf(a); follow.planAtTrackFollow = listOf(d); follow.followIsTrack = true; followPrefs.followIsTrack = true
        ui { follow.followPlanned() }
        val on = followedOnDisk()
        assertEquals("${Txt.s(Res.string.msg_name_route)} 19 Nov", on.follow!!.first)
        assertEquals(routeLine, on.follow!!.second)
        val s = on.session!!
        assertEquals(NavKind.PLAN, s.kind); assertEquals(Activity.HIKE, s.activity); assertEquals(3, s.sac)
        assertEquals(routeLine, s.original)
        assertEquals(listOf(a, b, c), s.stops.map { it.point })
        assertEquals(listOf<String?>("Start hut", "Pass", "Summit"), s.stops.map { it.name })
        assertEquals("the pinned stop", listOf(false, true, false), s.stops.map { it.pinned })
        assertEquals("a new token", true, s.token > 0)
        assertEquals("version = the last seen + 1 (none seen: 0 + 1)", 1, s.version)
        assertEquals(on.follow!!.first, follow.followName)
        assertEquals(routeLine, follow.followLine); assertEquals(routeLine, follow.followOriginal)
        assertNull(follow.planBaseline); assertNull(follow.planAtTrackFollow)
        assertTrue(follow.followFromPlan); assertFalse(follow.followIsTrack); assertFalse(follow.guidanceEndedByEmptyPlan)
        assertEquals(listOf(false), prefsWrites)
        assertEquals("the service is not told without a tour", emptyList<String>(), client.actions)
    }

    /** The saved route's name is the name followed, while the plan belongs to it. */
    @Test fun aSavedPlanIsFollowedUnderItsOwnName() {
        create()
        ui { plan.setSaved("Rosellen", savedRoute, listOf(a, b, c)) }
        ui { follow.followPlanned() }
        assertEquals("Rosellen", followedOnDisk().follow!!.first)
        assertEquals("Rosellen", followedOnDisk().session!!.name)
    }

    /** No route yet: nothing is written, nothing sent, the flag stays. */
    @Test fun withoutARouteNothingIsFollowed() {
        create()
        routeNow = null
        follow.guidanceEndedByEmptyPlan = true
        ui { follow.followPlanned() }
        assertNull(followedOnDisk().follow); assertNull(followedOnDisk().session)
        assertNull(follow.followLine); assertTrue("the flag is not touched without a route", follow.guidanceEndedByEmptyPlan)
        assertEquals(emptyList<String>(), client.actions); assertEquals(emptyList<Boolean>(), prefsWrites)
    }

    /**
     * Rule 1 / 18: during a tour at the moment of FOLLOW_CHANGED the files already hold the new session (token above the old,
     * version = the service's + 1) and the line, the state and "is a track" are set, the flag is cleared – the service reads the
     * files when the command arrives (it has no version check on the follow file). `checkNow` goes with the command as passed.
     */
    @Test fun duringATourTheFilesAndTheStateAreWrittenBeforeTheCommandWithTheServicesVersionPlusOne() {
        create()
        ui { follow.followPlanned() }
        val firstToken = followedOnDisk().session!!.token
        touring(tour7, session(version = 4), version = 4)
        follow.guidanceEndedByEmptyPlan = true
        follow.followIsTrack = true; followPrefs.followIsTrack = true
        follow.planBaseline = listOf(a); follow.planAtTrackFollow = listOf(d)
        ui { follow.followPlanned(checkNow = true) }
        assertEquals(listOf("FOLLOW_CHANGED"), client.actions)
        val at = last
        assertEquals(mapOf<String, Any>(RecordingWire.EXTRA_CHECK_NOW to true), at.extras)
        val s = at.onDisk.session!!
        assertEquals("version above the service's", 5, s.version)
        assertTrue("a strictly larger token than the session before", s.token > firstToken)
        assertEquals(routeLine, at.onDisk.follow!!.second)
        assertEquals(at.onDisk.follow!!.first, at.name)
        assertEquals(routeLine, at.line); assertEquals(routeLine, at.original); assertNull("the baseline of the old line is gone", at.baseline)
        assertNull("so is the plan remembered at a track's follow", at.atTrackFollow)
        assertFalse("`is a track` is stored before the command", at.isTrack); assertFalse(at.prefsTrack)
        assertFalse("the flag is cleared before the command", at.guidanceEnded)
        assertEquals("the session and the line on disk carry the same name", at.onDisk.follow!!.first, at.onDisk.session!!.name)
    }

    /** `checkNow` false is passed on as false (not dropped). */
    @Test fun checkNowIsHandedOnAsPassed() {
        create()
        touring(tour7, session(), version = 4)
        ui { follow.followPlanned() }
        ui { follow.followPlanned(checkNow = true) }
        assertEquals(listOf<Any?>(false, true), atSend.map { it.extras[RecordingWire.EXTRA_CHECK_NOW] })
    }

    /** Rule 18: while the tour runs without navigation the last version the app saw is the base; the plain service version wins when there is one. */
    @Test fun theVersionComesFromTheServiceElseFromTheLastOneSeen() {
        create()
        touring(tour7) // a free recording: no navigation state
        follow.navVersion = 6
        ui { follow.followPlanned() }
        assertEquals("the last version the app saw, + 1", 7, followedOnDisk().session!!.version)
        touring(tour7, session(version = 9), version = 9)
        follow.navVersion = 2
        ui { follow.followPlanned() }
        assertEquals("the service's version wins over the last seen", 10, followedOnDisk().session!!.version)
        assertEquals(2, atSend.size)
    }

    /** Rule 18: the token is strictly larger every time, even within one millisecond. */
    @Test fun everyNewSessionTakesAStrictlyLargerToken() {
        create()
        val tokens = (1..4).map { ui { follow.followPlanned() }; followedOnDisk().session!!.token }
        assertEquals(tokens.sorted().distinct(), tokens)
    }

    /**
     * Rule 18 / 1 (P1): the order of the whole write at the moment of the command – flag cleared, session and line on disk, follow
     * state, `is a track`, the plan's copy NOT yet queued (the copy comes after the follow, P13), stale requests untouched.
     */
    @Test fun theCommandComesAfterEverythingTheServiceReadsAndBeforeThePlanCopy() {
        create()
        touring(tour7, session(version = 4), version = 4)
        follow.guidanceEndedByEmptyPlan = true
        ui { follow.followPlanned() }
        val at = last
        assertNotNull(at.onDisk.session); assertNotNull(at.onDisk.follow)
        assertEquals(routeLine, at.line)
        assertEquals("rule 21 / P13: the plan is not copied until the follow is told", 0, at.pcQueue)
        assertFalse("the flag `taken from the plan` is set after the follow (P13)", at.fromPlan)
        assertEquals(0, at.invalidations)
        assertTrue("and then it is", follow.followFromPlan); assertEquals(1, pcEx.size)
    }

    /**
     * Rule 21 (P13a): the plan is marked as followed BEFORE its copy is made. With the copy's thread inline the copy's own read
     * of the saved route's uuid happens inside `copyFollowedPlan`, and sees the state at that moment.
     */
    @Test fun thePlanIsMarkedAsFollowedBeforeItsCopyIsMade() {
        create()
        follow = TourFollow(plan, planRoute, client, files, prefs, rows, host, scope, main)
        inlineCopy = true
        ui { plan.setSaved("Rosellen", savedRoute, listOf(a, b, c)) }
        ui { follow.followPlanned() }
        assertEquals(listOf("savedTourUuid($savedRoute) pc fromPlan=true"), log.filter { it.startsWith("savedTourUuid") })
    }

    // ======================================================================================================================
    // followShown: a track (rule 21, P13b)
    // ======================================================================================================================

    private fun track() = ShownTrack(tourId = null, name = "Dolomiten-Höhenweg", segments = listOf(trackLine), waypoints = emptyList(),
        distanceM = 9000.0, ascentM = 300.0, descentM = 200.0, durationS = 7200.0)

    /** A shown track is followed as a track: its own name and line on disk, `is a track` stored, the plan's copy stays, the plan at that moment is remembered. */
    @Test fun aFollowedTrackIsATrackTheCopyStaysAndThePlanAtThatMomentIsRemembered() {
        create()
        touring(tour7, session(version = 4), version = 4)
        ui { follow.followPlanned() }
        stepPc()
        shownNow = track()
        ui { follow.followShown() }
        val at = last
        assertEquals("FOLLOW_CHANGED", at.action)
        assertEquals(NavKind.TRACK, at.onDisk.session!!.kind)
        assertEquals("Dolomiten-Höhenweg", at.onDisk.follow!!.first); assertEquals(trackLine, at.onDisk.follow!!.second)
        assertTrue("`is a track` is stored before the command", at.isTrack && at.prefsTrack)
        assertNull("set AFTER the follow (the follow clears it, P13b)", at.atTrackFollow)
        assertEquals(listOf(a, b, c), follow.planAtTrackFollow)
        assertFalse("not followed from the plan any more", follow.followFromPlan)
        assertFalse(follow.planReplacesTrack())
        ui { plan.points.add(d) }
        assertTrue("an edited plan replaces the track", follow.planReplacesTrack())
        assertFalse(follow.planGuides)
    }

    /** Rule 21: a track followed after a plan keeps the plan's last version – the end of the tour writes nothing more. */
    @Test fun aTrackFollowedAfterAPlanKeepsThePlansLastVersionAndTheEndWritesNothingMore() {
        create()
        ui { follow.start() }
        touring(tour7, session(version = 4), version = 4)
        stepPc()
        ui { follow.followPlanned() }
        stepPc()
        assertEquals(listOf("setTourPlan"), rowCalls)
        shownNow = track()
        ui { follow.followShown() }
        stepPc()
        client.finish(tour7)
        stepPc()
        assertEquals("the plan's last version is what stays – no write for the track, none at the end", listOf("setTourPlan"), rowCalls)
        assertEquals(3, db.getTourPlan(tour7)!!.waypoints.size)
    }

    @Test fun withNoShownTrackNothingIsFollowed() {
        create()
        ui { follow.followShown() }
        assertNull(followedOnDisk().follow); assertEquals(emptyList<String>(), client.actions); assertNull(follow.planAtTrackFollow)
        assertEquals(0, pcEx.size)
    }

    // ======================================================================================================================
    // rule 1, 19: clearFollow
    // ======================================================================================================================

    /**
     * Rule 19 / 1 (P3): stale first (while the files still exist and nothing is queued), then the copy told, the files gone, the state
     * null – and only then the command: at FOLLOW_CHANGED both files are gone, the state is cleared, the copy was queued.
     */
    @Test fun clearingDuringATourMakesRequestsStaleThenRemovesTheFilesThenSendsTheCommand() {
        create()
        touring(tour7, session(version = 4), version = 4)
        ui { follow.followPlanned() }
        stepPc()
        follow.guidanceEndedByEmptyPlan = true
        follow.planBaseline = routeLine; follow.planAtTrackFollow = listOf(d); follow.followIsTrack = true; followPrefs.followIsTrack = true
        val sent = atSend.size
        val nameBefore = follow.followName
        ui { follow.clearFollow() }
        assertEquals(listOf("FOLLOW_CHANGED", "FOLLOW_CHANGED"), client.actions)
        assertEquals("stale first: both files still there, nothing queued yet",
            "invalidateSplits [files=$nameBefore session=true pcQueue=0]", log.single { it.startsWith("invalidateSplits") })
        val at = atSend[sent]
        assertEquals("the requests were made stale before the command", 1, at.invalidations)
        assertNull("the follow file is gone at the command", at.onDisk.follow); assertNull("the session file is gone", at.onDisk.session)
        assertNull(at.name); assertNull(at.line); assertNull(at.original); assertNull(at.baseline)
        assertFalse(at.fromPlan); assertFalse(at.guidanceEnded); assertFalse(at.isTrack); assertFalse(at.prefsTrack)
        assertNull(at.atTrackFollow)
        assertEquals("the plan's copy was told (queued) before the command", 1, at.pcQueue)
        assertEquals(emptyMap<String, Any>(), at.extras)
    }

    /** Rule 19: "recording only" is false after it (it resets the flag); `is a track` is stored false. */
    @Test fun clearingResetsTheRecordingOnlyFlagAndTheTrackFlag() {
        create()
        shownNow = track()
        ui { follow.followShown() }
        follow.guidanceEndedByEmptyPlan = true
        ui { follow.clearFollow() }
        assertFalse(follow.guidanceEndedByEmptyPlan); assertFalse(follow.followIsTrack); assertFalse(followPrefs.followIsTrack)
        assertNull(follow.planAtTrackFollow); assertNull(follow.followName)
        assertEquals(1, invalidations)
    }

    /** Without a tour nothing is sent. */
    @Test fun clearingWithoutATourSendsNoCommand() {
        create()
        ui { follow.followPlanned() }
        ui { follow.clearFollow() }
        assertEquals(emptyList<String>(), client.actions)
        assertNull(followedOnDisk().follow); assertNull(followedOnDisk().session)
    }

    // ---------------- piece 17's loose end: after a tour's end the service is not told ----------------

    private val endRows = JdbcTourRows(db)
    private class NoStore(private val db: JdbcTours) : ToursStore {
        override fun getTour(id: Long): Tour? = db.getTour(id)
        override fun loadSegments(id: Long): List<List<TrackPoint>> = db.loadSegments(id)
        override fun insertPlanned(name: String, row: PlannedRow): Long = error("not used")
        override fun replacePlanned(id: Long, row: PlannedRow): Boolean = error("not used")
        override fun rename(id: Long, name: String) = error("not used")
        override fun setFavourite(id: Long, favourite: Boolean): Boolean = error("not used")
        override fun planPoints(id: Long) = error("not used")
        override fun tourTimes() = error("not used")
        override fun listTours(): List<Tour> = error("not used")
        override fun getTourPlan(id: Long) = error("not used")
    }

    /**
     * What piece 17 left for this piece (tour paper §6.1 item 7, rules 8 / 9): the REAL end handler calls `clearFollow` with
     * `recording.state == null` (the service sets `finished` before `state = null`; the io hop puts the rest behind it), and the
     * real `clearFollow` then sends NO FOLLOW_CHANGED – no command to a stopped service, no NAVIGATING.
     */
    @Test fun afterATourEndsClearFollowWithNoRecordingSendsNoCommand() {
        create()
        val ctl = TourController(tourRecorder(client))
        val routes = RoutesState(object : RoutesStore {
            override fun listTours() = db.listTours()
            override fun listDeleted() = db.listDeleted()
            override fun setDraft(id: Long, draft: Boolean) = db.setDraft(id, draft)
            override fun setDeleted(id: Long, deletedMs: Long?) = db.setDeleted(id, deletedMs)
            override fun loadSegments(id: Long) = db.loadSegments(id)
            override fun deleteForGood(id: Long) = db.deleteTour(id)
            override fun rename(id: Long, name: String) = db.rename(id, name)
        }, clock = { 5_000L })
        val endLog = ArrayList<String>()
        val end = TourEndSlice(ctl, routes, client, NoStore(db), endRows, object : TourEndHost {
            override val activity: Activity get() = planRoute.activity
            override fun activityOrNull(name: String?): Activity? = Activity.entries.firstOrNull { it.name == name }
            override fun tourTimes(t: Tour): TourTimes? = TourTimes(movingS = 2400.0, breakS = 60.0)
            override fun nameRecordedTour(t: Tour) = Unit
            override fun refreshTours() = Unit
            override fun clearFollow() { endLog += "clearFollow rec=${client.state.value?.tourId}"; follow.clearFollow() }
            override fun enterMode(m: AppMode) { endLog += "enterMode($m) rec=${client.state.value?.tourId}" }
            override fun endGuidanceFlag() { follow.guidanceEndedByEmptyPlan = false }
            override fun kickThumbs() = Unit
            override fun demoEndedWithTour() = Unit
            override fun tourChanged(id: Long) = Unit
            override fun clearInterruptedNote() = Unit
            override val planPoints: Int get() = plan.points.size
            override fun clearPlan() = Unit
            override var routeBackAsk: Boolean = false
            override var planSize: PlanSize = PlanSize.MINI
            override val shownTourId: Long? get() = null
            override fun reopenShownTour(t: Tour) = Unit
        }, Say { _, _, _ -> }, scope, io, main)
        touring(tour7, session(version = 4), version = 4)
        ui { end.start() }
        ui { follow.followPlanned() }
        assertEquals("one command while the tour runs: the follow", listOf("FOLLOW_CHANGED"), client.actions)
        follow.guidanceEndedByEmptyPlan = true
        client.finish(tour7)
        stepIo()
        assertEquals(listOf("clearFollow rec=null", "enterMode(PLAN) rec=null"), endLog)
        assertEquals("no FOLLOW_CHANGED (and no other command) after the end", listOf("FOLLOW_CHANGED"), client.actions)
        assertNull(followedOnDisk().follow); assertNull(followedOnDisk().session)
        assertNull(follow.followLine); assertFalse(follow.guidanceEndedByEmptyPlan)
        assertEquals("the stale requests were told", 1, invalidations)
    }

    // ======================================================================================================================
    // rule 20: followAgain
    // ======================================================================================================================

    private fun earlier() = session("Back at it", activity = Activity.BIKE_TOURING, version = 3, token = 5L).copy(current = trackLine)

    /**
     * Rule 20 / 1 (P2): Undo back to a plan followed before follows THAT session exactly (its activity, stops, the taken re-route)
     * with a version above both and a new token; at the command the files and the state already hold it.
     */
    @Test fun undoingToAPlanFollowedBeforeFollowsThatSessionExactlyWithAHigherVersion() {
        create()
        ui { follow.start() }
        touring(tour7, session(version = 4), version = 4)
        stepPc()
        follow.guidanceEndedByEmptyPlan = true; follow.followIsTrack = true; followPrefs.followIsTrack = true; follow.planAtTrackFollow = listOf(d)
        ui { follow.followAgain(earlier(), planLine = routeLine, versionNow = 9) }
        assertEquals(listOf("FOLLOW_CHANGED"), client.actions)
        val at = last
        assertEquals(emptyMap<String, Any>(), at.extras)
        val s = at.onDisk.session!!
        assertEquals("version above both (its own 3 and the service's 9)", 10, s.version)
        assertTrue("a new token", s.token > 5L)
        assertEquals(Activity.BIKE_TOURING, s.activity); assertEquals("Back at it", s.name)
        assertEquals("the taken re-route is kept", trackLine, s.current)
        assertEquals(routeLine, s.original)
        assertEquals("the line on disk is the session's active one", trackLine, at.onDisk.follow!!.second)
        assertEquals("Back at it", at.onDisk.follow!!.first)
        assertEquals("the state before the command (P2)", trackLine, at.line)
        assertEquals(routeLine, at.original); assertEquals("the baseline = the plan's route line now", routeLine, at.baseline)
        assertFalse(at.guidanceEnded); assertFalse(at.isTrack); assertFalse(at.prefsTrack)
        assertEquals("unlike followPlanned, followAgain queues the copy BEFORE it tells the service (as it always did)", 1, at.pcQueue)
        assertTrue(follow.followFromPlan); assertNull(follow.planAtTrackFollow)
        stepPc()
        assertEquals("the plan's copy is made as the session's activity and T level", "BIKE_TOURING" to 3, copyRow(tour7)!!.let { it.activity to it.sac })
    }

    @Test fun theOlderSessionsVersionWinsWhenItIsTheHigherOne() {
        create()
        ui { follow.followAgain(earlier().copy(version = 12), routeLine, versionNow = 9) }
        assertEquals(13, followedOnDisk().session!!.version)
    }

    /** Latent bug L7 (kept, shown): `followAgain` tells the service without the `recState != null` guard of its siblings. */
    @Test fun L7_followAgainSendsTheCommandEvenWithoutATour() {
        create()
        assertNull(client.state.value)
        ui { follow.followAgain(earlier(), routeLine, 0) }
        assertEquals(listOf("FOLLOW_CHANGED"), client.actions)
        assertEquals("the line on disk was written first, as always", trackLine, last.onDisk.follow!!.second)
    }

    // ======================================================================================================================
    // rule 21: the plan's copy on the one serial thread
    // ======================================================================================================================

    private fun copyRow(id: Long) = db.getTourPlan(id)
    private fun addPoints(vararg extra: GeoPoint) { ui { plan.points.addAll(extra.toList()) } }

    /** Rule 21: a tour id that arrives late takes the plan followed before; nothing is written before the id, nothing on the main thread. */
    @Test fun aTourIdThatArrivesLateTakesThePlanFollowedBeforeIt() {
        create()
        ui { follow.start() }
        ui { follow.followPlanned() }
        stepPc()
        assertEquals("no tour yet: nothing to write to", emptyList<String>(), rowCalls)
        assertNull(copyRow(tour7))
        touring(tour7, session(), version = 4)
        stepPc()
        assertEquals(listOf("setTourPlan"), rowCalls)
        val p = copyRow(tour7)!!
        assertEquals(listOf(a, b, c).map { it.lat to it.lon }, p.waypoints.map { it.lat to it.lon })
        assertEquals(listOf<String?>("Start hut", "Pass", "Summit"), p.waypoints.map { it.name })
        assertEquals(setOf(1), p.pinned)
        assertEquals("the line is the route's", routeLine.map { it.lat to it.lon }, p.line.map { it.lat to it.lon })
        assertEquals("HIKE", p.activity); assertEquals(3, p.sac); assertEquals(5000.0, p.distanceM, 0.1)
        assertNull("no saved route", p.sourceUuid); assertNull(p.name)
        assertTrue(p.updatedMs > 0)
    }

    /** Rule 21: every edit is written in the order the plan changed; the last version is stored. */
    @Test fun everyEditIsWrittenInTheOrderThePlanChangedAndTheLastVersionIsStored() {
        create()
        ui { follow.start() }
        touring(tour7, session(), version = 4)
        stepPc()
        ui { follow.followPlanned() }
        addPoints(d); routeNow = route(routeLine + d)
        ui { follow.followPlanned() }
        addPoints(e); routeNow = route(routeLine + d + e)
        ui { follow.followPlanned() }
        assertEquals("three follows queued, none run yet", 3, pcEx.size)
        val seen = ArrayList<Int>()
        // step one at a time: the row after each is the version of that change
        inPc = true
        try { while (pcEx.runNext()) seen += copyRow(tour7)!!.waypoints.size } finally { inPc = false }
        assertEquals("written in the order of the changes", listOf(3, 4, 5), seen)
        assertEquals(listOf("setTourPlan", "setTourPlan", "setTourPlan"), rowCalls)
        assertEquals(5, copyRow(tour7)!!.waypoints.size)
        assertEquals((routeLine + d + e).size, copyRow(tour7)!!.line.size)
    }

    /** Rule 21: a failed write is loud and tried again with the next change; the end writes the last version once more and tells the backup. */
    @Test fun aFailedWriteIsReportedTriedAgainAndTheEndTellsTheBackup() {
        create()
        ui { follow.start() }
        touring(tour7, session(), version = 4)
        stepPc()
        rows.failOn += "setTourPlan"
        ui { follow.followPlanned() }
        stepPc()
        assertTrue(problems.said("db").any { it == "The followed plan could not be stored with tour $tour7 – tried again with the next change" })
        assertNull("nothing stored", copyRow(tour7))
        addPoints(d); routeNow = route(routeLine + d)
        ui { follow.followPlanned() }
        stepPc()
        assertEquals(4, copyRow(tour7)!!.waypoints.size)
        assertEquals("the change was written (the failed try and this one)", listOf("setTourPlan", "setTourPlan"), rowCalls)
        assertEquals("nothing for the backup while the write held", emptyList<String>(), log.filter { it.startsWith("tourChanged") })
        // the last edit fails, the end writes it once more and tells the backup – on the copy's thread
        addPoints(e); routeNow = route(routeLine + d + e)
        rows.failOn += "setTourPlan"
        ui { follow.followPlanned() }
        stepPc()
        assertEquals("the failed version is not stored", 4, copyRow(tour7)!!.waypoints.size)
        client.finish(tour7)
        stepPc()
        assertEquals("the end wrote the last version", 5, copyRow(tour7)!!.waypoints.size)
        assertEquals(listOf("tourChanged($tour7) pc"), log.filter { it.startsWith("tourChanged") })
    }

    /** Rule 21: when everything was written, the end tells the backup nothing. */
    @Test fun theEndTellsTheBackupNothingWhenNothingChanged() {
        create()
        ui { follow.start() }
        touring(tour7, session(), version = 4)
        ui { follow.followPlanned() }
        stepPc()
        client.finish(tour7)
        stepPc()
        assertEquals("the end writes the last version once more …", listOf("setTourPlan", "setTourPlan"), rowCalls)
        assertEquals("… and tells the backup nothing: it was stored already", emptyList<String>(), log.filter { it.startsWith("tourChanged") })
    }

    /** Rule 21: the next tour follows only what is followed at its own start. */
    @Test fun theNextTourTakesOnlyWhatIsFollowedAtItsOwnStart() {
        create()
        ui { follow.start() }
        touring(tour7, session(), version = 4)
        ui { follow.followPlanned() }
        stepPc()
        client.finish(tour7)
        stepPc()
        touring(tour8)
        stepPc()
        assertNull("the plan followed in tour 7 is not copied to tour 8", copyRow(tour8))
        assertEquals("tour 7: the follow and its end; tour 8: nothing", listOf("setTourPlan", "setTourPlan"), rowCalls)
    }

    /** Rule 21: a plan saved during the tour is copied again, with the saved route's uuid (read on the copy's thread). */
    @Test fun aPlanSavedDuringTheTourIsCopiedAgainWithTheSavedRoutesUuid() {
        create()
        ui { follow.start() }
        touring(tour7, session(), version = 4)
        stepPc()
        ui { follow.followPlanned() }
        stepPc()
        assertNull(copyRow(tour7)!!.sourceUuid)
        assertEquals(listOf("setTourPlan"), rowCalls)
        ui { follow.setSaved("Rosellen", savedRoute, listOf(a, b, c)) }
        assertEquals("Rosellen", plan.savedName); assertEquals(savedRoute, plan.savedId)
        assertEquals("the plan was set saved FIRST (the copy reads its id)", 1, pcEx.size)
        stepPc()
        assertEquals("uuid-rosellen", copyRow(tour7)!!.sourceUuid)
        assertEquals("Rosellen", copyRow(tour7)!!.name)
        assertEquals(listOf("setTourPlan", "setTourPlan"), rowCalls)
        assertEquals(listOf("savedTourUuid($savedRoute) pc fromPlan=true"), log.filter { it.startsWith("savedTourUuid") }.takeLast(1))
    }

    /** Rule 21: the copy is made again only when a plan is followed during a tour – a track, no tour, nothing followed copy nothing. */
    @Test fun savingThePlanCopiesItOnlyWhileATourFollowsThePlan() {
        create()
        // no tour
        ui { follow.followPlanned() }; stepPc()
        ui { follow.setSaved("Rosellen", savedRoute, listOf(a, b, c)) }
        assertEquals("no tour: no copy", 0, pcEx.size)
        // a tour, but a track is followed
        touring(tour7, session(), version = 4)
        shownNow = track()
        ui { follow.followShown() }
        pcEx.runAll()
        ui { follow.setSaved("Rosellen", savedRoute, listOf(a, b, c)) }
        assertEquals("a track is followed: no copy", 0, pcEx.size)
        // a tour, a plan followed, but the follow is cleared
        ui { follow.clearFollow() }; pcEx.runAll()
        ui { follow.setSaved("Rosellen", savedRoute, listOf(a, b, c)) }
        assertEquals("nothing followed: no copy", 0, pcEx.size)
        assertEquals("the plan was set saved every time", "Rosellen", plan.savedName)
    }

    /** Rule 21: a followed line that is not the plan's (restored after a restart: the flag `taken from the plan` is not stored) is not copied when the plan is saved. */
    @Test fun afterARestartTheFollowedLineIsNotCopiedWhenThePlanIsSaved() {
        create()
        ui { follow.followPlanned() }
        follow = newFollow()
        assertNotNull(follow.followLine); assertFalse(follow.followIsTrack); assertFalse(follow.followFromPlan)
        ui { follow.start() }
        touring(tour7, session(), version = 4)
        stepPc()
        ui { follow.setSaved("Rosellen", savedRoute, listOf(a, b, c)) }
        assertEquals("not followed from the plan (since the restart): no copy", 0, pcEx.size)
        assertEquals("Rosellen", plan.savedName)
    }

    /** Rule 21: the saved route's uuid not readable → the copy is kept without it and the problem is loud. */
    @Test fun aSavedRouteWhoseUuidCannotBeReadIsCopiedWithoutIt() {
        create()
        ui { follow.start() }
        touring(tour7, session(), version = 4)
        stepPc()
        ui { plan.setSaved("Rosellen", savedRoute, listOf(a, b, c)) }
        failUuidRead = true
        ui { follow.followPlanned() }
        stepPc()
        assertNull(copyRow(tour7)!!.sourceUuid)
        assertEquals("Rosellen", copyRow(tour7)!!.name)
        assertTrue(problems.said("db", com.onpath.app.debug.Problems.Level.WARN).any { it == "The followed plan's saved route not read – the copy is kept without it" })
    }

    /** No saved route: the uuid is not read at all. */
    @Test fun anUnsavedPlanReadsNoUuid() {
        create()
        ui { follow.followPlanned() }
        stepPc()
        assertTrue(log.none { it.startsWith("savedTourUuid") })
    }

    /** The plan below two points is not copied. */
    @Test fun aPlanBelowTwoPointsIsNotCopied() {
        create()
        ui { plan.points.removeAll(listOf(b, c)) }
        ui { follow.followPlanned() }
        assertEquals(0, pcEx.size)
    }

    /** The slice does nothing at construction and `start()` is what listens for the tour id. */
    @Test fun theSliceDoesNothingUntilStarted() {
        create()
        assertEquals(0, pcEx.size); assertEquals(emptyList<String>(), client.actions); assertEquals(emptyList<String>(), log)
        touring(tour7, session(), version = 4)
        assertEquals("not started: the tour id is not noticed", 0, pcEx.size)
        ui { follow.start() }
        assertEquals("started: the first pass (inline) saw the tour that already runs and queued it", 1, pcEx.size)
        stepPc()
        ui { follow.followPlanned() }
        stepPc()
        assertEquals(listOf("setTourPlan"), rowCalls)
    }

    // ======================================================================================================================
    // rule 25: the service navigates another activity
    // ======================================================================================================================

    /** Rule 25: the followed original is the session's, the next result is a baseline (event), THEN the plan is routed for the activity – once per activity. */
    @Test fun anotherActivityOfTheServiceIsTakenOverOriginalThenBaselineEventThenTheActivity() {
        create()
        ui { follow.followPlanned() }
        log.clear()
        val s = session(activity = Activity.BIKE_TOURING, line = trackLine)
        ui { follow.syncNavActivity(s) }
        assertEquals(trackLine, follow.followOriginal)
        assertEquals(Activity.BIKE_TOURING, follow.navActivitySeen)
        assertEquals("the original first, then the event, then the activity (its recalc, which already knows the activity was seen)",
            listOf("routeEvent ${RouteRun.Event.AdoptBaseline} original=session's", "recalc BIKE_TOURING T3 seen=BIKE_TOURING"), log)
        assertEquals(Activity.BIKE_TOURING, planRoute.activity); assertEquals(Activity.BIKE_TOURING, routePrefs.activity)
        // the same activity again: nothing
        log.clear()
        ui { follow.syncNavActivity(s) }
        assertEquals(emptyList<String>(), log)
        // the user picks the hike again while the service still navigates the bike: the next state of the service does not undo it
        ui { planRoute.selectActivity(Activity.HIKE) }
        log.clear()
        ui { follow.syncNavActivity(s) }
        assertEquals("once per activity: the service's unchanged state takes nothing over", emptyList<String>(), log)
        assertEquals(Activity.HIKE, planRoute.activity)
    }

    @Test fun theActivityTheAppAlreadyHasIsOnlyNotedAndNoSessionChangesNothing() {
        create()
        ui { follow.followPlanned() }
        log.clear()
        val original = follow.followOriginal
        ui { follow.syncNavActivity(session(activity = Activity.HIKE, line = trackLine)) }
        assertEquals(Activity.HIKE, follow.navActivitySeen)
        assertEquals("the original is not replaced when the activity is the app's", original, follow.followOriginal)
        assertEquals(emptyList<String>(), log)
        ui { follow.syncNavActivity(null) }
        assertEquals(Activity.HIKE, follow.navActivitySeen); assertEquals(emptyList<String>(), log)
    }

    // ======================================================================================================================
    // rule 33: what survives the process
    // ======================================================================================================================

    /** A new slice on the same folder and prefs restores the line, its name, the original and "is a track" – and nothing else. */
    @Test fun aNewSliceRestoresTheLineTheNameTheOriginalAndIsATrackAndNothingElse() {
        create()
        touring(tour7, session(version = 4), version = 4)
        ui { follow.followPlanned() }
        shownNow = track()
        ui { follow.followShown() }
        follow.planBaseline = routeLine; follow.navVersion = 8; follow.guidanceEndedByEmptyPlan = true; follow.navActivitySeen = Activity.HIKE
        follow.followFromPlan = true
        assertNotNull("set before: the track follow remembered the plan", follow.planAtTrackFollow)
        pcEx.dropAll()
        val reborn = newFollow()
        assertEquals("Dolomiten-Höhenweg", reborn.followName); assertEquals(trackLine, reborn.followLine)
        assertEquals("the original is the stored session's", trackLine, reborn.followOriginal)
        assertTrue("`is a track` from the prefs", reborn.followIsTrack)
        assertFalse(reborn.followFromPlan); assertNull(reborn.planBaseline); assertEquals(-1, reborn.navVersion)
        assertFalse(reborn.guidanceEndedByEmptyPlan); assertNull(reborn.planAtTrackFollow); assertNull(reborn.navActivitySeen)
        assertEquals("no work at construction", 0, pcEx.size)
    }

    /** No session file (an old install): the original is the line itself. */
    @Test fun withoutASessionFileTheOriginalIsTheLine() {
        create()
        files.appFollows(null, "Plain line", trackLine)
        val reborn = newFollow()
        assertEquals(trackLine, reborn.followLine); assertEquals(trackLine, reborn.followOriginal); assertEquals("Plain line", reborn.followName)
        files.appEnds()
        val none = newFollow()
        assertNull(none.followLine); assertNull(none.followOriginal); assertNull(none.followName)
    }

    // ======================================================================================================================
    // the small readers
    // ======================================================================================================================

    @Test fun thePlanIsFollowedWhenItsLineIsTheOriginalOrTheBaseline() {
        create()
        assertFalse(follow.planFollowed(null)); assertFalse(follow.planFollowed(routeLine))
        ui { follow.followPlanned() }
        assertTrue(follow.planFollowed(routeLine)); assertFalse(follow.planFollowed(routeLine.take(3)))
        follow.planBaseline = routeLine.take(3)
        assertTrue(follow.planFollowed(routeLine.take(3)))
        assertFalse(follow.planFollowed(null))
    }

    @Test fun thePlanGuidesWhileAPlanLineIsFollowedAndThePlanHasPoints() {
        create()
        assertFalse(follow.planGuides)
        ui { follow.followPlanned() }
        assertTrue(follow.planGuides)
        ui { plan.points.clear() }
        assertFalse("no points", follow.planGuides)
        ui { plan.points.addAll(listOf(a, b)) }
        follow.followIsTrack = true
        assertFalse("a track is not the plan", follow.planGuides)
    }
}
