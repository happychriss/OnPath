package com.onpath.app.ui.tour

import com.onpath.app.data.AppSettings
import com.onpath.app.data.TourKind
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.plan.FollowHistory
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.FollowStore
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavState
import com.onpath.app.navigation.NavStop
import com.onpath.app.recording.RecState
import com.onpath.app.recording.RecordingWire
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.search.Place
import com.onpath.app.testkit.FakeRecordingClient
import com.onpath.app.testkit.FakeRoutingEngine
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
import com.onpath.app.ui.PlanGps
import com.onpath.app.ui.Position
import com.onpath.app.ui.Say
import com.onpath.app.ui.ShownTrack
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanEditHost
import com.onpath.app.ui.plan.PlanEditSlice
import com.onpath.app.ui.plan.PlanRouteHost
import com.onpath.app.ui.plan.PlanRoutePrefs
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.app.ui.plan.PlanRunHost
import com.onpath.app.ui.plan.PlanRunSlice
import com.onpath.app.ui.plan.PlanStore
import com.onpath.app.ui.plan.PointNames
import com.onpath.app.ui.plan.RouterPort
import com.onpath.app.ui.plan.RoutingThreads
import com.onpath.app.ui.plan.TimesStore
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
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
 * What a plan edit means for the tour (rebuild phase 2 piece 18b) as flows of the REAL [TourEdits] with the real [TourFollow], the
 * real [PlanDocument], [PlanEditSlice], [PlanRunSlice], [PlanRouteSlice], the real [FollowStore] on a temp folder and the real
 * [FollowHistory] (docs/design/umbau-phase-2-tour.md, rules 16, 17, 20, 36). The edits come in through `PlanEditSlice`'s own
 * hooks (the app's adapter is written here as the view model writes it: `splitOf`, `oneLeftGoesFromHere`,
 * `endGuidanceIfPlanEmpty` → [TourEdits], `clearFollow` → [TourFollow]). Fakes only at the edges:
 *  - the recording client records, AT THE MOMENT each command arrives, what the service would read: both follow files, the flag
 *    "recording only", the plan copy's queue ([atSend]); the test plays the service ([serviceTakes]: it re-reads the files and
 *    publishes the state);
 *  - the routing thread of the position → ahead route is a [ManualExecutor] the test steps, the engine is the kit's
 *    [FakeRoutingEngine] (its calls are read back); the plan copy's thread is held too;
 *  - the app's own functions the slice asks for (`recalc` as the view model's first two lines of it – the follow line of the old
 *    plan is cancelled –, the maps, the notes, the shown track, "back to Navigate") are a host that logs each call together with
 *    what the files and the plan held at that moment.
 * What the tests do NOT prove: that the view model's `recalc` really calls [TourEdits.followEditedPlan] after the run
 * (`TourWiringTest` pins the line), the Android adapters (`TourWiringTest`), the service itself.
 *
 * Test data: five points a–e a zigzag, two kilometres apart, a hiking tour 7 that has been at a and b (its track), the phone at b, the plan
 * followed from the start, a service that navigates the session it read from the files.
 */
class TourEditsFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private val client = FakeRecordingClient()
    private val db = JdbcTours()
    private val log = ArrayList<String>()
    private val said = ArrayList<String>()

    // ---------------- threads ----------------

    private val routingEx = ManualExecutor("routing")
    private val pcEx = ManualExecutor("planCopy")
    private var onMain = 0
    private val main = VirtualDelayDispatcher { r -> onMain++; try { r.run() } finally { onMain-- } }
    private val scope = CoroutineScope(main + SupervisorJob())
    private fun <T> ui(f: () -> T): T { onMain++; try { return f() } finally { onMain-- } }
    private fun assertMain(what: String) = check(onMain > 0) { "$what must run on the main thread" }
    private fun stepRouting() = routingEx.runAll()
    private fun stepPc() = pcEx.runAll()

    private fun exec(ex: ManualExecutor): ExecutorCoroutineDispatcher = object : java.util.concurrent.AbstractExecutorService() {
        override fun execute(command: Runnable) = ex.execute(command)
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
    }.asCoroutineDispatcher()

    // ---------------- the data ----------------

    private val a = GeoPoint(46.600, 11.100)
    private val b = GeoPoint(46.610, 11.130)
    private val c = GeoPoint(46.620, 11.100)
    private val d = GeoPoint(46.630, 11.130)
    private val e = GeoPoint(46.640, 11.100)
    private val far = GeoPoint(46.700, 11.200)
    private val letters = mapOf(a to "a", b to "b", c to "c", d to "d", e to "e", far to "f")
    private fun ids(l: List<GeoPoint>?) = l?.joinToString("") { letters[it] ?: "." } ?: "-"
    private fun lineOf(pts: List<GeoPoint>): List<GeoPoint> =
        pts.zipWithNext().flatMap { (p, q) -> listOf(p, GeoPoint((p.lat + q.lat) / 2, (p.lon + q.lon) / 2)) } + pts.last()
    private fun routeFor(pts: List<GeoPoint>) = PlannedRoute(listOf(PlannedLeg(lineOf(pts), false)), 4000.0, 100.0, 50.0, 3000.0, null)
    private val names = mapOf(a to "Start hut", b to "Pass", c to "Summit")

    private val tour7 = db.insertTour(TourKind.RECORDED, "Tour 7", "HIKE", 3, emptyList(), state = "recording", uuid = "uuid-7")
    private val tour8 = db.insertTour(TourKind.RECORDED, "Tour 8", "HIKE", 3, emptyList(), state = "recording", uuid = "uuid-8")

    // ---------------- the real neighbours ----------------

    private class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }
    private val planStore = MemoryPlanStore().also { it.points = listOf(a, b, c, d, e) }
    private val plan = PlanDocument(planStore)
    private class MemoryPrefs(override var activity: Activity = Activity.HIKE, override var sac: Int = 3) : PlanRoutePrefs
    private val routePrefs = MemoryPrefs()
    private val engine = FakeRoutingEngine()
    private var routeNow: PlannedRoute? = null
    private var shownNow: ShownTrack? = null
    private var here: GeoPoint? = b
    /** The view model's `recalc` cancels the follow line of the old plan first; a test may switch that off to see the slice's own checks. */
    private var recalcCancels = true

    private lateinit var planRoute: PlanRouteSlice
    private lateinit var planRun: PlanRunSlice
    private lateinit var planEdit: PlanEditSlice
    private lateinit var files: FollowStore
    private lateinit var follow: TourFollow
    private lateinit var edits: TourEdits
    private val history = FollowHistory()
    private val rows = JdbcTourRows(db)
    private val followPrefs = object : FollowPrefs { override var followIsTrack: Boolean = false }

    private fun st() = "[stored=${ids(planStore.points)} files=${ids(files.load().follow?.second?.filter { it in letters })} " +
        "ask=${planEdit.routeBackAsk} ended=${follow.guidanceEndedByEmptyPlan}]"

    private val followHost = object : TourFollowHost {
        override val route: PlannedRoute? get() = routeNow
        override val shown: ShownTrack? get() = shownNow
        override fun placeName(p: GeoPoint): String? = names[p]
        override fun newRouteName(prefix: String) = "$prefix 19 Nov"
        override fun stopsAsSaved(pts: List<GeoPoint>) = pts.mapIndexed { i, p -> com.onpath.app.gpx.GpxWaypoint(p.lat, p.lon, names[p] ?: "stop ${i + 1}") }
        override fun savedTourUuid(id: Long): String? = null
        // the view model's adapter: the requests are the edits slice's
        override fun invalidateSplits() { edits.splitRequests.invalidate() }
        override fun routeEvent(e: RouteRun.Event) = Unit
        override fun tourChanged(id: Long) = Unit
    }

    private val editsHost = object : TourEditsHost {
        override val route: PlannedRoute? get() = routeNow
        override fun replan() {
            assertMain("replan")
            // the view model's `recalc`, its first lines: the tour's follow line of the old plan is stopped
            if (recalcCancels) { edits.followCancel?.cancel(); edits.followCancel = null }
            log += "replan ${st()}"
        }
        override fun mapsForNewPoints(before: List<GeoPoint>, after: List<GeoPoint>) { log += "maps(${ids(before)}→${ids(after)}) ${st()}" }
        override fun noPositionNote(note: PlanGps.Note, text: org.jetbrains.compose.resources.StringResource) { log += "note($note) ${st()}" }
        override fun placeName(p: GeoPoint): String? = names[p]
        override fun newRouteName(prefix: String) = "$prefix 19 Nov"
        override fun routeRunEvent(e: RouteRun.Event) { log += "routeEvent($e) ${st()}" }
        override fun closeShownTrack() { log += "closeShown ${st()}"; shownNow = null }
        override fun resumeNavigation() { log += "resumeNavigation ${st()} shown=${shownNow?.name}" }
    }

    private class AtSend(val action: String, val extras: Map<String, Any>, val onDisk: FollowStore.Followed, val ended: Boolean, val pcQueue: Int,
                         val baseline: List<GeoPoint>?, val state: String)
    private val atSend = ArrayList<AtSend>()
    private val last get() = atSend.last()

    private var created = false
    private fun create() {
        if (created) return
        created = true
        tmp.create()
        files = FollowStore(tmp.root)
        planRoute = PlanRouteSlice(
            prefs = routePrefs,
            router = object : RouterPort {
                override fun create(): RoutingEngine = engine
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
                override var route: PlannedRoute? = null
                override val routing: Boolean get() = false
                override val longRouteAsk: Double? get() = null
                override val mode: AppMode get() = AppMode.NAVIGATE
                override val touring: Boolean get() = client.state.value != null
                override fun recalc() = Unit
                override fun routeEvent(e: RouteRun.Event) = Unit
                override fun refreshTours() = Unit
                override fun clearShownNote() = Unit
                override fun showWhole(points: List<GeoPoint>) = Unit
            },
            threads = RoutingThreads(routing = exec(routingEx), background = exec(ManualExecutor("bg")), check = exec(ManualExecutor("check")),
                hold = exec(ManualExecutor("hold"))),
        )
        planRun = PlanRunSlice(planRoute.runCauses, object : PlanRunHost {
            override fun points() = plan.points.toList()
            override fun clearRoute() = Unit
            override fun noCorridor() = Unit
            override fun loadDeferredMaps(before: List<GeoPoint>) = Unit
            override fun recalc() = Unit
            override fun cancelRun(why: NotRouted) = planEdit.cancelRun(why)
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
            override fun keepAsDraft(why: String) { log += "draft($why) ${st()}" }
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
            override fun sayNoDestination() { if (client.state.value != null) client.say(RecordingWire.SAY_NO_DESTINATION) }
            override fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean) = edits.oneLeftGoesFromHere(before, mayEmpty)
            override fun endGuidanceIfPlanEmpty() = edits.endGuidanceIfPlanEmpty()
            override fun clearFollow() = follow.clearFollow()
            override fun guidanceEndedByEmptyPlan() { follow.guidanceEndedByEmptyPlan = true }
            override fun stopName(p: GeoPoint): String? = names[p]
            override suspend fun pointNames(): PointNames = PointNames { null }
            override var searchMark: Place? = null
        }, scope, io = main)
        follow = TourFollow(plan, planRoute, client, files, followPrefs, rows, followHost, scope, held(pcEx))
        edits = TourEdits(plan, planEdit, planRoute, follow, client, Position { here }, history, editsHost,
            Say { text, kind, place -> said += "$kind/$place: $text" }, scope, planRoute.threads.routing)
        client.onSend = { cmd ->
            atSend += AtSend(cmd.action.substringAfterLast('.'), cmd.extras, files.load(), follow.guidanceEndedByEmptyPlan, pcEx.size,
                follow.planBaseline, st())
        }
    }

    @After fun close() { scope.cancel(); db.close() }

    // ---------------- the service, played by the test ----------------

    private fun recState(id: Long, session: NavSession?, track: List<List<GeoPoint>>) = RecState(
        tourId = id, paused = false, distanceM = 100.0, ascentM = 0.0, descentM = 0.0, elapsedS = 60, gpsLost = false, offRouteM = null,
        offRouteAlert = false, followName = session?.name, track = track,
        nav = session?.let { NavState(it.active, it.version, null, null, null, null, it, 0.0) })

    /** The service has read the files (what it does at START and at FOLLOW_CHANGED) and publishes what it navigates. */
    private fun serviceTakes(id: Long = tour7, track: List<List<GeoPoint>> = listOf(listOf(a, b))) {
        client.publish(recState(id, files.load().session, track))
    }

    /** The tour follows the plan [a,b,c,d,e] and the service has taken it: the starting point of most flows. */
    private fun startTour(track: List<List<GeoPoint>> = listOf(listOf(a, b))) {
        create()
        routeNow = routeFor(plan.points.toList())
        ui { follow.followPlanned() }
        stepPc()
        serviceTakes(tour7, track)
        atSend.clear(); log.clear(); client.sent.clear()
    }

    /** The plan's run is through with the last edit: the route is there and its effect asks the tour to follow the edited plan. */
    private fun routeArrives() {
        routeNow = routeFor(plan.points.toList())
        ui { edits.followEditedPlan() }
    }

    private fun session() = files.load().session!!
    private fun sessionStops() = ids(session().stops.map { it.point })

    // ======================================================================================================================
    // the passed points and the split
    // ======================================================================================================================

    /** The points the track went by are passed; what was found stays passed when the track no longer shows it; a new tour starts over. */
    @Test fun thePassedPointsAreTheTracksTheyStayAndANewTourStartsOver() {
        startTour(track = listOf(listOf(a, b)))
        assertEquals(setOf(0, 1), ui { edits.tourPassed() })
        // the service's track no longer holds them (an edit made it follow position → ahead): found points stay passed
        client.publish(recState(tour7, files.load().session, emptyList()))
        here = far
        assertEquals("what was found stays passed", setOf(0, 1), ui { edits.tourPassed() })
        // another tour: the memory is dropped (tour 8 has been nowhere)
        client.publish(recState(tour8, files.load().session, emptyList()))
        assertEquals("a new tour starts over", emptySet<Int>(), ui { edits.tourPassed() })
        client.publish(recState(tour7, files.load().session, listOf(listOf(a))))
        assertEquals("and the first tour's points are not remembered for it", setOf(0), ui { edits.tourPassed() })
    }

    /** Nothing is passed without a tour, without a followed plan line, for a followed track and for an empty plan. */
    /** A stop the route only passed by is behind the rider for an edit, but stays out of the map's grey – also after the edit. */
    @Test fun aStopPassedByIsBehindForAnEditButNotGreyOnTheMap() {
        startTour(track = listOf(listOf(a, b)))
        val by = files.load().session!!.copy(visited = setOf(0, 1, 3), passedBy = setOf(2))
        client.publish(recState(tour7, by, listOf(listOf(a, b))))
        assertEquals(setOf(0, 1, 2, 3), ui { edits.tourPassed() })
        assertEquals(setOf(0, 1, 3), ui { edits.tourPassedOnMap() })
        // the tour follows position → ahead: its session no longer knows the stop
        client.publish(recState(tour7, by.copy(visited = emptySet(), passedBy = emptySet()), listOf(listOf(a, b))))
        assertEquals("still behind", setOf(0, 1, 2, 3), ui { edits.tourPassed() })
        assertFalse("still not grey", 2 in ui { edits.tourPassedOnMap() })
        client.publish(recState(tour8, by.copy(visited = emptySet(), passedBy = emptySet()), emptyList()))
        assertEquals("a new tour starts over", emptySet<Int>(), ui { edits.tourPassedOnMap() })
    }

    @Test fun nothingIsPassedWithoutATourAFollowedPlanOrForATrack() {
        create()
        routeNow = routeFor(plan.points.toList())
        assertEquals("no tour", emptySet<Int>(), ui { edits.tourPassed() })
        ui { follow.followPlanned() }
        serviceTakes()
        assertEquals("the opposite first: a tour that follows the plan", setOf(0, 1), ui { edits.tourPassed() })
        assertEquals("an empty plan", emptySet<Int>(), ui { edits.tourPassed(emptyList()) })
        assertEquals("a plan given in", setOf(0), ui { edits.tourPassed(listOf(a, d)) })
        shownNow = ShownTrack(tourId = null, name = "Track", segments = listOf(listOf(far, a)), waypoints = emptyList(),
            distanceM = 1.0, ascentM = 0.0, descentM = 0.0, durationS = 1.0)
        ui { follow.followShown() }
        assertEquals("a followed track is not the plan", emptySet<Int>(), ui { edits.tourPassed() })
        ui { follow.clearFollow() }
        assertEquals("nothing followed", emptySet<Int>(), ui { edits.tourPassed() })
    }

    /**
     * A route loaded on the way (a tap in My routes during a tour, owner 2026-11-03 / 2026-11-06) is a whole new plan, not an edit:
     * nothing of it is passed, once; the next edit is an edit again. Every split makes the running follow requests stale.
     */
    @Test fun aRouteLoadedOnTheWayIsAWholePlanNothingPassedAndOnlyOnce() {
        startTour()
        val after = listOf(a, b, c, e)
        assertEquals("an edit: two points passed, the ahead changed", TourSplitFollow(listOf(c, e)), ui { edits.splitOf(plan.points.toList(), after) }.asFollow())
        edits.loadedOnTheWay = true
        assertNull("loaded on the way: a whole plan", ui { edits.splitOf(plan.points.toList(), after) })
        assertFalse("the flag is used up", edits.loadedOnTheWay)
        assertEquals("the next one is an edit again", TourSplitFollow(listOf(c, e)), ui { edits.splitOf(plan.points.toList(), after) }.asFollow())
        // the requests: a request taken is stale after the split
        val g = edits.splitRequests.next(plan.points.toList())
        assertTrue(edits.splitRequests.isCurrent(g, plan.points.toList()))
        ui { edits.splitOf(plan.points.toList(), after) }
        assertFalse("every split makes the running requests stale", edits.splitRequests.isCurrent(g, plan.points.toList()))
    }

    private data class TourSplitFollow(val ahead: List<GeoPoint>)
    private fun com.onpath.app.navigation.TourSplit.Outcome?.asFollow() = (this as? com.onpath.app.navigation.TourSplit.Outcome.Follow)?.let { TourSplitFollow(it.ahead) }

    // ======================================================================================================================
    // rule 16: an edit during a tour – position → ahead
    // ======================================================================================================================

    /**
     * Delete a point ahead with two passed: the tour is asked to follow position → what is ahead – routed on the routing thread,
     * the files and the command only when the result is there; at the command the files hold the new line (stops, pins, the number
     * of the first point ahead, the checking flag), the baseline and "taken from the plan" are set AFTER and the plan copy is queued
     * after the command.
     */
    @Test fun deletingAPointAheadFollowsPositionToWhatIsAheadWhenTheRouteIsThere() {
        startTour()
        val firstToken = session().token
        ui { planEdit.removePoint(3) } // d
        assertEquals("abce", ids(planStore.points))
        routeArrives()
        assertEquals("routed off the main thread, nothing told yet", emptyList<String>(), client.actions)
        assertEquals("the files still hold the old line", "abcde", ids(files.load().follow!!.second.filter { it in letters }))
        assertEquals("the engine has not been asked", 0, engine.calls.size)
        assertEquals(1, routingEx.size)
        assertEquals("the edit itself queued the plan's copy (the copy follows the edit, the follow comes later)", 1, pcEx.size)
        stepPc()
        stepRouting()
        assertEquals("position → the points ahead, leg by leg", listOf(listOf(b, c), listOf(c, e)), engine.calls.map { it.waypoints })
        assertEquals(listOf("FOLLOW_CHANGED"), client.actions)
        val at = last
        assertEquals(mapOf<String, Any>(RecordingWire.EXTRA_CHECK_NOW to true), at.extras)
        val s = at.onDisk.session!!
        assertEquals("the stops ahead", "ce", ids(s.stops.map { it.point }))
        assertEquals("numbered as in the plan: c is point 3", 3, s.firstNumber)
        assertTrue("a newer session than the first", s.token > firstToken)
        assertEquals("from the position through the points ahead", "bce", ids(s.original.filter { it in letters }))
        assertEquals(b, s.original.first()); assertEquals(e, s.original.last())
        assertFalse("the flag is cleared before the command", at.ended)
        assertNull("the baseline is set after the follow (it resets it)", at.baseline)
        assertEquals("the plan's copy of the new follow is queued after the command", 0, at.pcQueue)
        // after: the plan line counts as followed, taken from the plan, the copy queued
        assertEquals(routeNow!!.points, follow.planBaseline)
        assertTrue(follow.followFromPlan)
        assertEquals(1, pcEx.size)
        assertEquals("the plan's name", Txt.s(Res.string.msg_name_route) + " 19 Nov", follow.followName)
    }

    /** A newer edit stops the older follow line: only the newest result is applied, the older changes nothing and tells nothing. */
    /**
     * From FollowLineLegsTest (deleted in the test clean-up G05, 2026-11-19), its class doc word for word:
     * Plan edits during a tour (owner 2026-11-05, docs/design/long-bike-routes.md): the plan run and the tour's follow line
     * (position → points ahead) share one leg cache, so the follow line routes only position → first point ahead; a newer
     * edit stops a follow line still running, so the next plan run never waits behind it (field case 2026-11-04: 15 s plan +
     * 20 s follow line per edit on a 98 km bike plan). Source checks – the flow itself needs the service.
     */
    @Test fun aNewerEditStopsTheOlderFollowLineOnlyTheNewestIsApplied() {
        startTour()
        ui { planEdit.removePoint(3) }; routeArrives()      // d deleted: ahead c, e
        ui { planEdit.removePoint(3) }; routeArrives()      // e deleted: ahead c
        assertEquals("both results wait on the routing thread", 2, routingEx.size)
        stepRouting()
        assertEquals("the older result applied nothing, the newer one the line to c", listOf("FOLLOW_CHANGED"), client.actions)
        assertEquals("c", ids(session().stops.map { it.point }))
        assertEquals(1, problems.traced("tour").count { it.startsWith("The tour follows position → 1 points ahead of") })
        assertEquals("the older run was stopped before it ended", 1, problems.traced("tour").count { it.contains("stopped – a newer edit came") })
    }

    /** The slice's own cancel: an older follow line is stopped when the next one starts, also when nothing else cancelled it. */
    @Test fun theNextFollowLineStopsTheOlderOneItself() {
        startTour()
        recalcCancels = false
        ui { planEdit.removePoint(3) }; routeArrives()
        val first = edits.followCancel!!
        assertFalse(first.isCancelled)
        ui { planEdit.removePoint(3) }; routeArrives()
        assertTrue("the first run's signal fired when the second started", first.isCancelled)
        stepRouting()
        assertEquals("only the newest applies", listOf("FOLLOW_CHANGED"), client.actions)
        assertNull("the finished run's handle is dropped", edits.followCancel)
    }

    /** A guidance that ended while the route was being routed is not brought back by the late result (it is stale), also without a cancel. */
    @Test fun aLateResultAfterTheGuidanceEndedAppliesNothing() {
        startTour()
        recalcCancels = false
        ui { planEdit.removePoint(3) }; routeArrives()
        assertEquals(1, routingEx.size)
        ui { planEdit.set(emptyList()) } // everything deleted: the guidance ends
        assertNull(files.load().follow)
        client.sent.clear()
        stepRouting()
        assertEquals("the late result is stale: no command", emptyList<String>(), client.actions)
        assertNull("and no line comes back", files.load().follow)
        assertNull(follow.followLine)
        assertTrue(follow.guidanceEndedByEmptyPlan)
    }

    /** The tour ended while the route was routed: nothing is followed. */
    @Test fun aResultAfterTheTourEndedAppliesNothing() {
        startTour()
        ui { planEdit.removePoint(3) }; routeArrives()
        val onDisk = files.load().session!!.token
        client.finish(tour7)
        stepRouting()
        assertEquals(emptyList<String>(), client.actions)
        assertEquals("nothing written: the session on disk is the old one", onDisk, files.load().session!!.token)
        assertEquals("abcde", ids(follow.followLine!!.filter { it in letters }))
    }

    /** No route to the points ahead: the guidance so far stays, a red line says which point. */
    @Test fun noRouteAheadKeepsTheGuidanceAndSaysSo() {
        startTour()
        val old = session().token
        engine.fail = { "no way" }
        ui { planEdit.removePoint(3) }; routeArrives()
        stepRouting()
        assertEquals(listOf("ERROR/MAP: " + Txt.s(Res.string.nav_no_route_ahead, 3)), said)
        assertEquals(emptyList<String>(), client.actions)
        assertEquals("the guidance so far stays", old, files.load().session!!.token)
        assertEquals(1, problems.said("tour", com.onpath.app.debug.Problems.Level.WARN).size)
    }

    /** No position and one point ahead: the plan is followed as a whole. */
    @Test fun withoutAPositionAndOnePointAheadThePlanIsFollowedAsAWhole() {
        startTour(track = listOf(listOf(a, b, c)))
        here = null
        ui { planEdit.removePoint(4) } // e: ahead d only, passed a, b, c
        routeArrives()
        assertEquals("no routing: the plan as it is", 0, routingEx.size)
        assertEquals(listOf("FOLLOW_CHANGED"), client.actions)
        assertEquals("abcd", ids(session().stops.map { it.point }))
    }

    /** Only passed points were edited: the guidance ahead stays, the plan line counts as followed, the plan's copy is told. */
    @Test fun editingOnlyPassedPointsKeepsTheGuidanceAndCountsThePlanLineAsFollowed() {
        startTour()
        val before = session().token
        ui { planEdit.removePoint(0) } // a: passed, the ahead stays c d e
        routeArrives()
        assertEquals(emptyList<String>(), client.actions)
        assertEquals(before, files.load().session!!.token)
        assertEquals(routeNow!!.points, follow.planBaseline)
        assertEquals("the plan's copy follows the edit", 1, pcEx.size)
        assertEquals(0, routingEx.size)
    }

    /** Nothing ahead any more: "No destination ahead" is asked and the followed line stays until it is answered. */
    @Test fun noPointAheadAsksAndKeepsTheLine() {
        startTour(track = listOf(listOf(a, b, c, d, e)))
        ui { planEdit.removePoint(4) } // e: all that is left has been passed
        assertTrue("asked", planEdit.routeBackAsk)
        routeArrives()
        assertEquals("only the sentence, no new line", listOf("SAY"), client.actions)
        assertEquals("abcde", ids(follow.followLine!!.filter { it in letters }))
        assertEquals(1, pcEx.size)
    }

    // ======================================================================================================================
    // rule 20: Undo / Redo back to a plan followed before
    // ======================================================================================================================

    /**
     * An edit without passed points: the tour follows the new plan; Undo takes the session of the plan before back exactly (a
     * session, not a new route – no routing, the old line), and the session it replaced is remembered FIRST – so Redo takes that one back.
     */
    @Test fun undoAndRedoFollowTheSessionsExactlyWithoutRoutingWhenNothingIsPassed() {
        startTour(track = emptyList())
        here = a
        val s1 = session()
        ui { planEdit.removePoint(1) } // b
        routeArrives()
        assertEquals("the new plan is followed", listOf("FOLLOW_CHANGED"), client.actions)
        assertEquals("acde", sessionStops())
        serviceTakes(track = emptyList())
        val s2 = session()
        ui { planEdit.undoStep() }
        routeArrives()
        assertEquals("again as it was", "abcde", sessionStops())
        assertEquals(s1.original, session().original)
        assertTrue("a new token and a version above both", session().token > s2.token && session().version > s2.version)
        serviceTakes(track = emptyList())
        val engineCalls = engine.calls.size
        ui { planEdit.redoStep() }
        // the run's line is not the one the replaced session had (a taken re-route kept in it): only the remembered session brings it back
        routeNow = routeFor(plan.points.toList()).let { r -> PlannedRoute(listOf(PlannedLeg(r.points.map { GeoPoint(it.lat + 0.0005, it.lon) }, false)), 4000.0, 0.0, 0.0, 1.0, null) }
        ui { edits.followEditedPlan() }
        assertEquals("the session undo replaced is followed again as it was", s2.original, session().original)
        assertEquals("acde", sessionStops())
        assertEquals("nothing was routed", engineCalls, engine.calls.size)
        assertEquals(0, routingEx.size)
        assertEquals(3, client.actions.size)
    }

    /** The same with passed points (the split's place): Undo of the edit follows the older session, Redo the one it replaced. */
    @Test fun undoAndRedoFollowTheSessionsExactlyWhenPointsArePassed() {
        startTour()
        val s1 = session()
        ui { planEdit.removePoint(3) }; routeArrives(); stepRouting() // d deleted: position → c e routed
        serviceTakes()
        val s2 = session()
        assertEquals("ce", sessionStops())
        val calls = engine.calls.size
        ui { planEdit.undoStep() }; routeArrives()
        assertEquals("Undo: the session as it was, no routing", s1.original, session().original)
        assertEquals(calls, engine.calls.size)
        serviceTakes()
        ui { planEdit.redoStep() }; routeArrives()
        assertEquals("Redo: the session undo replaced", s2.original, session().original)
        assertEquals("ce", sessionStops())
        assertEquals(calls, engine.calls.size)
        assertEquals(0, routingEx.size)
    }

    /** A line that is the same route as the followed one (Undo back to it, no session remembered) keeps the running session. */
    @Test fun aPlanLineThatIsTheFollowedRouteKeepsTheSession() {
        startTour(track = emptyList())
        val token = session().token
        routeNow = routeFor(plan.points.toList())
        ui { edits.followEditedPlan() } // the line the tour follows already
        assertEquals(emptyList<String>(), client.actions)
        assertEquals(token, session().token)
        // the same route again by a re-routed copy of its points: the baseline only
        val again = follow.followOriginal!!.map { GeoPoint(it.lat + 0.00003, it.lon) } // 3 m beside it
        routeNow = PlannedRoute(listOf(PlannedLeg(again, false)), 4000.0, 0.0, 0.0, 1.0, null)
        assertFalse(follow.planFollowed(again))
        ui { edits.followEditedPlan() }
        assertEquals(emptyList<String>(), client.actions)
        assertEquals("the plan line counts as followed", again, follow.planBaseline)
        assertEquals(token, session().token)
    }

    // ======================================================================================================================
    // rule 17: the guidance ends, the recording never
    // ======================================================================================================================

    /** All points deleted: the files go, "recording only" is set after (the clear resets it), nothing stops; Undo follows again with a check. */
    /**
     * From TourEditTest (deleted in the test clean-up G05, 2026-11-19), its class doc word for word:
     * Plan during a tour (owner 2026-11-05, R5 – was the tile bar of 2026-11-02): the plan row's **"✕ Neu planen"** (owner
     * 2026-11-03, was "Änderungen verwerfen" – it doubled Undo), the green "▶ Weiter geht's": the plan is emptied, the recording goes on, the sheet shows how to begin again; a saved route clears without a
     * question (it stays in My routes), an unsaved plan asks "Discard changes?" first; Undo brings the plan back.
     * ☰ → Save route is the one Save, also during a tour.
     * Its check "The recording goes on: an empty plan ends only the guidance (the followed line goes), Undo resumes it." is this one.
     */
    @Test fun anEmptyPlanEndsTheGuidanceNeverTheRecordingAndUndoFollowsAgain() {
        startTour()
        assertFalse("the opposite first", follow.guidanceEndedByEmptyPlan)
        ui { planEdit.set(emptyList()) }
        assertNull(files.load().follow); assertNull(files.load().session)
        assertTrue("recording only", follow.guidanceEndedByEmptyPlan)
        assertNull(follow.followLine)
        assertFalse("the recording goes on", client.actions.contains("STOP"))
        assertNotNull(client.state.value)
        val clearAt = atSend.single { it.action == "FOLLOW_CHANGED" }
        assertNull("at the command the files were gone", clearAt.onDisk.follow)
        assertFalse("and the flag was not set yet (clearFollow resets it, the flag comes after)", clearAt.ended)
        client.sent.clear(); atSend.clear()
        ui { planEdit.undoStep() }
        routeArrives()
        assertEquals("Undo: the plan is followed again, checked at once", listOf("FOLLOW_CHANGED"), client.actions)
        assertEquals(mapOf<String, Any>(RecordingWire.EXTRA_CHECK_NOW to true), last.extras)
        assertEquals("abcde", ids(files.load().follow!!.second.filter { it in letters }))
        assertFalse(follow.guidanceEndedByEmptyPlan)
    }

    /** "✕ Neu planen" during a tour: the plan is kept as a draft first, then the guidance ends – the recording goes on. */
    /**
     * From ClearPlanDuringTourTest (class doc, word for word; the rules are in PlanGuidanceTest):
     * ✕ Neu planen during a tour (owner 2026-11-05: he saw "Navigate without a route" – is that what the app does?): every
     * guidance ends, the recording never stops; owner 2026-11-06: no question any more, the plan goes under Zuletzt, Undo
     * brings it back (E2E CoreFieldE2ETest.clearingThePlanDuringATourAsksNothing). The empty sheet ("Die Tour läuft weiter")
     * and Navigate's free tour line are in the screenshot frames `planEmptyDuringTour` / `navigateFreeTour`.
     */
    @Test fun clearingThePlanDuringATourKeepsADraftThenEndsTheGuidance() {
        startTour()
        ui { planEdit.clearPlan() }
        assertEquals(listOf("draft(cleared) [stored=abcde files=abcde ask=false ended=false]"), log.filter { it.startsWith("draft") })
        assertNull(files.load().follow)
        assertTrue(follow.guidanceEndedByEmptyPlan)
        assertFalse(client.actions.contains("STOP"))
        assertEquals("only follow changes were sent", setOf("FOLLOW_CHANGED"), client.actions.toSet())
    }

    /** "Route back to the start?" – No: the guidance ends as for an empty plan, the recording goes on, Undo brings it back. */
    @Test fun answeringNoToRouteBackEndsTheGuidanceAfterTheClear() {
        startTour(track = emptyList())
        plan.replace(listOf(a, b)); routeNow = routeFor(listOf(a, b)); ui { follow.followPlanned() }; serviceTakes(track = emptyList())
        client.sent.clear(); atSend.clear()
        here = far
        ui { planEdit.removePoint(1) }
        assertTrue("asked", planEdit.routeBackAsk)
        assertEquals("the line stays until it is answered", "ab", ids(follow.followLine!!.filter { it in letters }))
        val g = edits.splitRequests.next(plan.points.toList())
        ui { edits.answerRouteBack(false) }
        assertFalse(planEdit.routeBackAsk)
        assertFalse("stale", edits.splitRequests.isCurrent(g, plan.points.toList()))
        assertNull(files.load().follow)
        assertTrue("the flag is set after the clear", follow.guidanceEndedByEmptyPlan)
        assertFalse(client.actions.contains("STOP"))
        assertEquals("nothing else was done to the plan", "a", ids(planStore.points))
        assertEquals(emptyList<String>(), log.filter { it.startsWith("note") })
    }

    /** A question that is not open is not answered: nothing is touched. */
    @Test fun anAnswerWithoutAQuestionChangesNothing() {
        startTour()
        assertFalse(planEdit.routeBackAsk)
        val g = edits.splitRequests.next(plan.points.toList())
        ui { edits.answerRouteBack(false) }
        assertTrue("the requests are still current", edits.splitRequests.isCurrent(g, plan.points.toList()))
        assertNotNull(files.load().follow)
        assertFalse(follow.guidanceEndedByEmptyPlan)
        assertEquals(emptyList<String>(), client.actions)
    }

    /** The question stays open after the tour ended: the answer only closes it (and makes the requests stale), it changes no plan. */
    @Test fun anAnswerAfterTheTourEndedOnlyClosesTheQuestion() {
        startTour(track = emptyList())
        plan.replace(listOf(a, b)); routeNow = routeFor(listOf(a, b)); ui { follow.followPlanned() }; serviceTakes(track = emptyList())
        here = far
        ui { planEdit.removePoint(1) }
        assertTrue(planEdit.routeBackAsk)
        client.finish(tour7)
        val g = edits.splitRequests.next(plan.points.toList())
        assertTrue(edits.splitRequests.isCurrent(g, plan.points.toList()))
        log.clear()
        ui { edits.answerRouteBack(true) }
        assertFalse(planEdit.routeBackAsk)
        assertFalse("the requests are stale also without a tour", edits.splitRequests.isCurrent(g, plan.points.toList()))
        assertEquals("a", ids(planStore.points))
        assertEquals(emptyList<String>(), log)
    }

    /** Yes with one point left: the plan becomes position → start in one step, the tour follows it (a plan from here, as a whole). */
    @Test fun answeringYesWithOnePointLeftMakesThePlanPositionToStartAndFollowsIt() {
        startTour(track = listOf(listOf(a)))
        plan.replace(listOf(a, b)); routeNow = routeFor(listOf(a, b)); ui { follow.followPlanned() }; serviceTakes(track = listOf(listOf(a)))
        here = far
        client.sent.clear(); atSend.clear(); log.clear()
        ui { planEdit.removePoint(1) } // b: the goal
        assertTrue(planEdit.routeBackAsk)
        log.clear()
        ui { edits.answerRouteBack(true) }
        assertFalse(planEdit.routeBackAsk)
        assertEquals("position → start", "fa", ids(planStore.points))
        // the order: the plan is stored, then the maps, then the run event, then the run
        assertEquals(listOf(
            "maps(a→fa) [stored=fa files=ab ask=false ended=false]",
            "routeEvent(PlanEdited) [stored=fa files=ab ask=false ended=false]",
            "replan [stored=fa files=ab ask=false ended=false]",
        ), log)
        // the run's result: the tour follows the new plan as a whole (nothing of it passed – a stale split must not be taken)
        client.sent.clear()
        routeArrives()
        assertEquals(listOf("FOLLOW_CHANGED"), client.actions)
        assertEquals("fa", sessionStops())
        assertEquals(0, routingEx.size)
    }

    /** Yes with more points: the start is appended as the goal, the passed points stay, the tour follows position → start. */
    @Test fun answeringYesWithMorePointsAppendsTheStartAndFollowsPositionToIt() {
        startTour(track = listOf(listOf(a, b, c)))
        ui { planEdit.set(listOf(a, b, c)) } // d and e deleted: everything left has been passed
        assertTrue("asked", planEdit.routeBackAsk)
        client.sent.clear(); atSend.clear(); log.clear()
        ui { edits.answerRouteBack(true) }
        assertFalse(planEdit.routeBackAsk)
        assertEquals("the start is the goal again", "abca", ids(planStore.points))
        assertEquals(listOf(
            "maps(abc→abca) [stored=abca files=abcde ask=false ended=false]",
            "routeEvent(PlanEdited) [stored=abca files=abcde ask=false ended=false]",
            "replan [stored=abca files=abcde ask=false ended=false]",
        ), log)
        routeArrives()
        assertEquals("routed from the position to the start", 1, routingEx.size)
        stepRouting()
        assertEquals(listOf("FOLLOW_CHANGED"), client.actions)
        assertEquals("a", sessionStops())
        assertEquals(b, session().original.first()); assertEquals(a, session().original.last())
        // one Undo brings the plan from before the delete
        ui { planEdit.undoStep() }
        assertEquals("abcde", ids(planStore.points))
    }

    /** Yes, but there is no position at all: the note says so and the guidance ends as for No. */
    @Test fun answeringYesWithoutAPositionSaysSoAndEndsTheGuidance() {
        startTour(track = emptyList())
        plan.replace(listOf(a, b)); routeNow = routeFor(listOf(a, b)); ui { follow.followPlanned() }; serviceTakes(track = emptyList())
        here = null
        ui { planEdit.removePoint(1) }
        assertTrue(planEdit.routeBackAsk)
        log.clear()
        ui { edits.answerRouteBack(true) }
        assertEquals("the note is said while the line is still there, then the guidance ends",
            listOf("note(ROUTE_BACK_NO_POSITION) [stored=a files=ab ask=false ended=false]"), log)
        assertNull(files.load().follow)
        assertTrue(follow.guidanceEndedByEmptyPlan)
        assertEquals("a", ids(planStore.points))
    }

    // ======================================================================================================================
    // rule 17: one point left
    // ======================================================================================================================

    /** One point left where I stand: the plan empties in the same step (the guidance ends, the recording goes on). */
    @Test fun onePointLeftRightHereEmptiesThePlanAndEndsTheGuidance() {
        startTour(track = listOf(listOf(a)))
        plan.replace(listOf(a, b)); routeNow = routeFor(listOf(a, b)); ui { follow.followPlanned() }; serviceTakes(track = listOf(listOf(a)))
        here = a
        client.sent.clear(); atSend.clear()
        ui { planEdit.removePoint(1) }
        assertEquals("the plan empties", "", ids(planStore.points).replace("-", ""))
        assertFalse("no question", planEdit.routeBackAsk)
        assertNull(files.load().follow)
        assertTrue(follow.guidanceEndedByEmptyPlan)
        assertFalse(client.actions.contains("STOP"))
    }

    /** One point left far from me: the plan is position → that point, followed as a whole new plan (nothing of it passed). */
    @Test fun onePointLeftFarFromMeBecomesPositionToThatPointFollowedAsAWhole() {
        startTour(track = listOf(listOf(a, b)))
        here = b
        client.sent.clear(); atSend.clear(); log.clear()
        // a and b are passed; the user deletes a, b, d, e – leaving c: the split of that edit says "follow c", which must not be taken
        // (the plan is position → c now, a whole new plan: followed at once, not routed again)
        ui { planEdit.set(listOf(c)) }
        assertEquals("position → the point", "bc", ids(planStore.points))
        assertTrue("the maps for the new plan are asked after it is stored", log.any { it.startsWith("maps(") && it.contains("stored=bc") })
        routeArrives()
        assertEquals("the tour follows the new plan as a whole", listOf("FOLLOW_CHANGED"), client.actions)
        assertEquals("bc", sessionStops())
    }

    /** One point left with no position: the note says why and the guidance ends (flag after the clear). */
    @Test fun onePointLeftWithoutAPositionEndsTheGuidanceAndSaysWhy() {
        startTour(track = emptyList())
        here = null
        log.clear()
        ui { planEdit.set(listOf(c)) }
        assertEquals("the plan is as the user left it", "c", ids(planStore.points))
        assertEquals(listOf("note(ONE_POINT_NO_POSITION) [stored=c files=-"), log.filter { it.startsWith("note") }.map { it.substringBefore(" ask=") })
        assertNull(files.load().follow)
        assertTrue(follow.guidanceEndedByEmptyPlan)
    }

    /** Undo / Redo leaving one point right here never empties the plan, but the guidance ends (no line the plan cannot show). */
    @Test fun undoLeavingOnePointRightHereEndsTheGuidanceButKeepsThePlan() {
        create()
        plan.replace(listOf(a))
        routeNow = routeFor(listOf(a, b)); ui { follow.followPlanned() }; serviceTakes(track = emptyList())
        here = far
        ui { planEdit.set(listOf(a, b)) } // an edit: one point more, followed
        assertNotNull(files.load().follow)
        here = a
        client.sent.clear(); atSend.clear()
        ui { planEdit.undoStep() }
        assertEquals("the plan is never emptied by Undo", "a", ids(planStore.points))
        assertNull("but the guidance ends", files.load().follow)
        assertTrue(follow.guidanceEndedByEmptyPlan)
        assertTrue("Redo is still there", plan.canRedo)
        assertFalse(client.actions.contains("STOP"))
        assertEquals("the files were gone at the command", listOf(null), atSend.map { it.onDisk.follow })
        assertFalse("the flag came after the clear", atSend.single().ended)
    }

    /** A followed GPX track is not the plan: an empty plan and an edited plan leave it alone (guidance and line stay). */
    @Test fun aFollowedTrackIsLeftAloneByPlanEdits() {
        startTour()
        shownNow = track()
        ui { follow.followShown() }
        serviceTakes()
        client.sent.clear(); atSend.clear()
        val token = session().token
        ui { planEdit.removePoint(3) }
        routeArrives()
        assertEquals("an edit changes nothing the service reads", emptyList<String>(), client.actions.filter { it == "FOLLOW_CHANGED" })
        assertEquals(token, session().token)
        ui { planEdit.set(emptyList()) }
        assertFalse("the guidance of a track does not end", follow.guidanceEndedByEmptyPlan)
        assertEquals("Dolomiten-Höhenweg", files.load().follow!!.first)
        assertTrue(follow.followIsTrack)
        assertEquals(0, routingEx.size)
    }

    // ======================================================================================================================
    // rule 36: a track followed during a tour
    // ======================================================================================================================

    private fun track() = ShownTrack(tourId = null, name = "Dolomiten-Höhenweg", segments = listOf(listOf(far, GeoPoint(46.71, 11.21))),
        waypoints = emptyList(), distanceM = 9000.0, ascentM = 300.0, descentM = 200.0, durationS = 7200.0)

    /** The plan leaves the map first (kept as a draft), THEN the track is followed, closed as shown, and Navigate comes back. */
    @Test fun aTrackFollowedDuringATourTakesThePlanOffTheMapFirst() {
        startTour()
        shownNow = track()
        ui { edits.followShownNow() }
        val calls = log.filter { it.startsWith("draft") || it.startsWith("closeShown") || it.startsWith("resumeNavigation") }
        assertEquals(listOf("draft(cleared)", "closeShown", "resumeNavigation"), calls.map { it.substringBefore(" ") })
        assertTrue("when the plan left the map the plan's line was still followed", calls[0].contains("files=abcde"))
        assertTrue("when the track was closed it was already followed (files hold it)", calls[1].contains("files=f"))
        assertEquals("", ids(planStore.points).replace("-", ""))
        assertEquals("Dolomiten-Höhenweg", files.load().follow!!.first)
        assertEquals(NavKind.TRACK, session().kind)
        assertNull(shownNow)
        assertTrue("Undo brings the plan back", plan.canUndo)
        assertFalse(client.actions.contains("STOP"))
        assertEquals(listOf("FOLLOW_CHANGED", "FOLLOW_CHANGED"), client.actions)
    }

    /** An empty plan has nothing to take off the map; without a tour nothing happens at all. */
    @Test fun aTrackFollowedWithoutAPlanOrATour() {
        startTour()
        planStore.points = emptyList(); plan.points.clear()
        shownNow = track()
        ui { edits.followShownNow() }
        assertEquals("no draft is kept for nothing", 0, log.count { it.startsWith("draft") })
        assertEquals("Dolomiten-Höhenweg", files.load().follow!!.first)
        // no tour: nothing
        client.finish(tour7)
        shownNow = track(); files.appEnds(); log.clear()
        ui { edits.followShownNow() }
        assertEquals(emptyList<String>(), log)
        assertNull(files.load().follow)
        assertNotNull(shownNow)
    }

    // ======================================================================================================================
    // the way there
    // ======================================================================================================================

    /** The position counts for a new point only while the tour is led to the plan and has never reached it, for a plan followed. */
    @Test fun theWayThereHasAPositionOnlyWhileLedAndNotReached() {
        startTour()
        assertNull("followed from the line: not led", ui { edits.wayThereFrom() })
        val base = session()
        fun publish(s: NavSession) = client.publish(recState(tour7, s, listOf(listOf(a, b))))
        publish(base.copy(lead = true, reached = false))
        assertEquals("led, not reached: my position", b, ui { edits.wayThereFrom() })
        publish(base.copy(lead = true, reached = true))
        assertNull("reached", ui { edits.wayThereFrom() })
        publish(base.copy(lead = false, reached = false))
        assertNull("not led", ui { edits.wayThereFrom() })
        publish(base.copy(lead = true, reached = false, kind = NavKind.TRACK))
        assertNull("a track", ui { edits.wayThereFrom() })
        publish(base.copy(lead = true, reached = false))
        shownNow = track(); ui { follow.followShown() }
        assertNull("the app follows a track", ui { edits.wayThereFrom() })
        ui { follow.clearFollow() }; publish(base.copy(lead = true, reached = false))
        assertEquals(b, ui { edits.wayThereFrom() })
        client.finish(tour7)
        assertNull("no tour", ui { edits.wayThereFrom() })
    }

    // ======================================================================================================================
    // nothing at construction
    // ======================================================================================================================

    @Test fun theSliceDoesNothingUntilAskedTo() {
        create()
        assertEquals(emptyList<String>(), log)
        assertEquals(emptyList<String>(), client.actions)
        assertEquals(0, routingEx.size); assertEquals(0, pcEx.size)
        assertNull(edits.followCancel)
        assertFalse(edits.loadedOnTheWay)
    }

    // ======================================================================================================================
    // test clean-up G05 (2026-11-19): checks of FollowLineLegsTest / RouteBackTest as flows
    // ======================================================================================================================

    /**
     * Owner 2026-11-05 (long bike routes): the plan run and the tour's follow line share ONE leg cache – the follow line routes only
     * the legs the plan run has not routed (field case 2026-11-04: 15 s plan + 20 s follow line per edit on a 98 km plan).
     */
    /**
     * From FollowLineLegsTest (deleted in the test clean-up G05, 2026-11-19), its class doc word for word:
     * Plan edits during a tour (owner 2026-11-05, docs/design/long-bike-routes.md): the plan run and the tour's follow line
     * (position → points ahead) share one leg cache, so the follow line routes only position → first point ahead; a newer
     * edit stops a follow line still running, so the next plan run never waits behind it (field case 2026-11-04: 15 s plan +
     * 20 s follow line per edit on a 98 km bike plan). Source checks – the flow itself needs the service.
     */
    @Test fun theFollowLineRoutesOnlyTheLegsThePlanRunHasNotRoutedYet() {
        startTour()
        val line = listOf(c, GeoPoint(46.625, 11.110), e)
        planRoute.legCache.put(c, e, Activity.HIKE, 3, com.onpath.app.routing.RouteResult(line, 3000.0, 10.0, 10.0, 2000.0))
        ui { planEdit.removePoint(3) } // d deleted: ahead c, e
        routeArrives(); stepPc(); stepRouting()
        assertEquals("c → e came from the plan's cache, only position → c was asked of the engine", listOf(listOf(b, c)), engine.calls.map { it.waypoints })
        assertEquals(listOf("FOLLOW_CHANGED"), client.actions)
        assertEquals("ce", sessionStops())
    }

    /** "Route back to the start?" – No: plain recording; Undo brings the plan and the guidance back (RouteBackTest.noIsPlainRecording…). */
    @Test fun undoAfterNoToRouteBackBringsThePlanAndTheGuidanceBack() {
        startTour(track = emptyList())
        plan.replace(listOf(a, b)); routeNow = routeFor(listOf(a, b)); ui { follow.followPlanned() }; serviceTakes(track = emptyList())
        here = far
        ui { planEdit.removePoint(1) }
        assertTrue(planEdit.routeBackAsk)
        ui { edits.answerRouteBack(false) }
        assertEquals("the start stays in the plan", "a", ids(planStore.points))
        assertNull("no route is followed: Plan shows none", follow.followLine)
        assertTrue(follow.guidanceEndedByEmptyPlan)
        client.sent.clear()
        ui { planEdit.undoStep() }
        routeArrives()
        assertEquals("ab", ids(planStore.points))
        assertFalse("Undo: the tour follows the plan again", follow.guidanceEndedByEmptyPlan)
        assertEquals(listOf("FOLLOW_CHANGED"), client.actions)
        assertEquals("ab", sessionStops())
    }

    /** Yes: one Undo goes back to start → goal (no step of its own), Redo brings the way back again (RouteBackTest.undoAfterYes…). */
    @Test fun undoAfterYesToRouteBackBringsTheOriginalPlanBackAndRedoTheWayBack() {
        startTour(track = emptyList())
        plan.replace(listOf(a, b)); routeNow = routeFor(listOf(a, b)); ui { follow.followPlanned() }; serviceTakes(track = emptyList())
        here = far
        ui { planEdit.removePoint(1) }
        ui { edits.answerRouteBack(true) }
        assertEquals("position → start", "fa", ids(planStore.points))
        ui { planEdit.undoStep() }
        assertEquals("one Undo: the plan before the delete", "ab", ids(planStore.points))
        routeArrives()
        assertEquals("what is followed is the plan before the delete", "ab", sessionStops())
        ui { planEdit.redoStep() }
        assertEquals("Redo: the way back again", "fa", ids(planStore.points))
    }

    /**
     * Undo while "Route back to the start?" is open closes the question by the Undo's own reset (RouteBackTest.undoWhileAskingClosesTheQuestion):
     * the question is open after the delete, the Undo closes it, and the tour follows the plan before the delete again.
     */
    @Test fun undoWhileRouteBackIsAskedClosesTheQuestionAndFollowsThePlanAgain() {
        startTour(track = emptyList())
        plan.replace(listOf(a, b)); routeNow = routeFor(listOf(a, b)); ui { follow.followPlanned() }; serviceTakes(track = emptyList())
        here = far
        ui { planEdit.removePoint(1) }
        assertTrue("asked", planEdit.routeBackAsk)
        ui { planEdit.undoStep() }
        assertFalse("the Undo closed the question (no answer was given)", planEdit.routeBackAsk)
        routeArrives()
        assertEquals("ab", sessionStops())
    }

    /** Yes without any position: plain recording, and Undo brings the guidance back (RouteBackTest.yesWithoutAPositionIsPlainRecording). */
    @Test fun undoAfterYesWithoutAPositionBringsTheGuidanceBack() {
        startTour(track = emptyList())
        plan.replace(listOf(a, b)); routeNow = routeFor(listOf(a, b)); ui { follow.followPlanned() }; serviceTakes(track = emptyList())
        here = null
        ui { planEdit.removePoint(1) }
        ui { edits.answerRouteBack(true) }
        assertTrue(follow.guidanceEndedByEmptyPlan)
        client.sent.clear()
        ui { planEdit.undoStep() }
        routeArrives()
        assertFalse(follow.guidanceEndedByEmptyPlan)
        assertEquals("ab", sessionStops())
    }
}
