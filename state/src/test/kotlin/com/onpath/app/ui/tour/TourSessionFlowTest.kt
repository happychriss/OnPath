package com.onpath.app.ui.tour

import com.onpath.app.data.AppSettings
import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.plan.FollowHistory
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.gpx.PlanGpx
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.FollowStore
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavState
import com.onpath.app.recording.RecState
import com.onpath.app.recording.RecordingCommand
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
import com.onpath.app.ui.Camera
import com.onpath.app.ui.MapViewHost
import com.onpath.app.ui.MapViewSlice
import com.onpath.app.ui.NoticeBoard
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.PlanGps
import com.onpath.app.ui.PlanSize
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
import com.onpath.app.ui.routes.PlannedRow
import com.onpath.app.ui.routes.RecentPlans
import com.onpath.app.ui.routes.RecentPlansHost
import com.onpath.app.ui.routes.RoutesState
import com.onpath.app.ui.routes.RoutesStore
import com.onpath.app.ui.routes.SaveHost
import com.onpath.app.ui.routes.SaveSlice
import com.onpath.app.ui.routes.SaveStore
import com.onpath.app.ui.routes.ToursStore
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * The tour's session (rebuild phase 2 piece 19) as flows of the REAL [TourSession] with the real [TourFollow], [TourEdits],
 * [TourEndSlice], [TourController], [PlanDocument], [PlanEditSlice], [PlanRunSlice], [PlanRouteSlice], [SaveSlice], [MapViewSlice]
 * and [FollowStore] (docs/design/umbau-phase-2-tour.md rules 2–6, 13–15, 22, 31 and the whole tour 8 / 9). Fakes only at the edges:
 *  - the recording client ([FakeRecordingClient]) ends a tour in the SERVICE'S order and records, AT THE MOMENT a command arrives,
 *    what the app held (the files on disk, the mode, Save's dialog, the plan's size, the map following – [atSend]);
 *  - the scope is main, inline, with VIRTUAL time; `io` is HELD (a [VirtualDelayDispatcher] that posts to a [ManualExecutor] the test
 *    steps – so the start-up check's 1.5 s is a clock too, [advance] moves both); the tour rows only on `io`, the hosts only on main;
 *  - the hosts of the session / the follow / the edits / the end are fakes that log each call with the state AT the call.
 * What the tests do NOT prove: that the view model's big `init` calls `recalc()` BEFORE `tourSession.start()` and the mode flip is
 * the slice's (`TourWiringTest` pins the lines), that `unfinishedPending = true` stands directly before its launch (the held io
 * cannot show a swap: `TourWiringTest`), the Android adapters (`TourWiringTest`), the service itself.
 *
 * Test data: five points a–e a zigzag, a hiking plan with its route, tour 7 recorded (and, in the start-up flows, tour 8 left
 * "active" – an interrupted tour), a service that navigates the session it read from the files.
 */
class TourSessionFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private val client = FakeRecordingClient()
    private val db = JdbcTours()
    private val log = ArrayList<String>()
    private val said = ArrayList<Triple<String, NoticeKind, NoticePlace>>()

    // ---------------- threads: main (inline, virtual time), io (held, virtual time), the plan copy's, the routing's ----------------

    private val ioEx = ManualExecutor("io")
    private val pcEx = ManualExecutor("planCopy")
    private val routingEx = ManualExecutor("routing")
    private var onMain = 0
    private var inIo = false
    private val main = VirtualDelayDispatcher { r -> onMain++; try { r.run() } finally { onMain-- } }
    private val io = VirtualDelayDispatcher { r -> ioEx.execute(r) }
    private val scope = CoroutineScope(main + SupervisorJob())

    private fun stepIo(): Int { inIo = true; try { return ioEx.runAll() } finally { inIo = false } }
    private fun stepPc() = pcEx.runAll()
    private fun stepRouting() = routingEx.runAll()
    /** Both clocks move (the main one: the start's six seconds; the io one: the start-up check's wait), then what became due on io runs. */
    private fun advance(ms: Long) { main.advance(ms); io.advance(ms); stepIo() }
    private fun <T> ui(f: () -> T): T { onMain++; try { return f() } finally { onMain-- } }
    private fun assertMain(what: String) = check(onMain > 0) { "$what must run on the main thread" }
    private fun assertIo(what: String) = check(onMain == 0 && inIo) { "$what must run on io (main=$onMain io=$inIo)" }

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
    private fun routeFor(pts: List<GeoPoint>, straight: Boolean = false) =
        PlannedRoute(listOf(PlannedLeg(lineOf(pts), straight)), 4000.0, 100.0, 50.0, 3000.0, null)
    private val names = mapOf(a to "Start hut", b to "Pass", c to "Summit")

    private val tour7 = db.insertTour(TourKind.RECORDED, "Tour 7", "HIKE", 3, listOf(listOf(TrackPoint(46.6, 11.1, 600.0, 1_000L), TrackPoint(46.61, 11.13, 600.0, 400_000L))),
        state = "recording", distance = 3000.0, ascent = 100.0, descent = 50.0, duration = 400.0, uuid = "uuid-7")
    /** An interrupted recording: the row still says "active" (what the start-up check looks for). */
    private val unfinishedId get() = db.insertTour(TourKind.RECORDED, "Tour 8", "HIKE", 3,
        listOf(listOf(TrackPoint(46.6, 11.1, 600.0, 1_000L), TrackPoint(46.605, 11.11, 600.0, 90_000L))), state = "active", uuid = "uuid-8")

    // ---------------- the real neighbours ----------------

    private class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }
    private val planStore = MemoryPlanStore()
    private var startPoints = listOf(a, b, c, d, e)
    private class MemoryPrefs(override var activity: Activity = Activity.HIKE, override var sac: Int = 3) : PlanRoutePrefs
    private val routePrefs = MemoryPrefs()
    private val engine = FakeRoutingEngine()
    private var routeNow: PlannedRoute? = null
    private var shownNow: ShownTrack? = null
    private var here: GeoPoint? = b
    private var gpsFix = true
    private var headingUp = false
    private var hasErrorBox = false

    private lateinit var plan: PlanDocument
    private lateinit var planRoute: PlanRouteSlice
    private lateinit var planRun: PlanRunSlice
    private lateinit var planEdit: PlanEditSlice
    private lateinit var files: FollowStore
    private lateinit var follow: TourFollow
    private lateinit var edits: TourEdits
    private lateinit var end: TourEndSlice
    private lateinit var save: SaveSlice
    private lateinit var map: MapViewSlice
    private lateinit var session: TourSession
    private val board = NoticeBoard(routesOpen = { false }, settingsOpen = { false }, mapShown = { true })
    private val ctl = TourController(tourRecorder(client))
    private val history = FollowHistory()
    /** What a read of the tour rows may throw (the name of the row call → once). */
    private val rows = JdbcTourRows(db) { name -> if (name == "findActiveRecording") { assertIo("TourRows.$name"); onFindActive?.invoke() } }
    private var onFindActive: (() -> Unit)? = null
    private val followPrefs = object : FollowPrefs { override var followIsTrack: Boolean = false }
    private var failSegments = false
    private var failClearHints = false
    private val tours = object : ToursStore {
        override fun getTour(id: Long): Tour? { assertIo("tours.getTour"); return db.getTour(id) }
        override fun loadSegments(id: Long): List<List<TrackPoint>> {
            assertIo("tours.loadSegments"); if (failSegments) throw java.sql.SQLException("unreadable"); return db.loadSegments(id)
        }
        override fun insertPlanned(name: String, row: PlannedRow): Long = error("the session does not save a plan")
        override fun replacePlanned(id: Long, row: PlannedRow): Boolean = error("the session does not save a plan")
        override fun rename(id: Long, name: String) = error("the session does not rename")
        override fun setFavourite(id: Long, favourite: Boolean): Boolean = error("the session does not star")
        override fun planPoints(id: Long) = error("not used")
        override fun tourTimes() = error("not used")
        override fun listTours(): List<Tour> = error("not used")
        override fun getTourPlan(id: Long) = error("not used")
        override fun insertDraftPlanned(name: String, row: PlannedRow): Long = error("not used")
    }
    private val routesStore = object : RoutesStore {
        override fun listTours() = db.listTours()
        override fun listDeleted() = db.listDeleted()
        override fun setDraft(id: Long, draft: Boolean) = db.setDraft(id, draft)
        override fun setDeleted(id: Long, deletedMs: Long?) = db.setDeleted(id, deletedMs)
        override fun loadSegments(id: Long) = db.loadSegments(id)
        override fun deleteForGood(id: Long) = db.deleteTour(id)
        override fun rename(id: Long, name: String) = db.rename(id, name)
    }
    private val routes = RoutesState(routesStore, clock = { 5_000L })

    private fun planned() = db.listTours().filter { it.kind == TourKind.PLANNED && !it.draft }
    private fun filesNow() = if (::files.isInitialized) ids(files.load().follow?.second?.filter { it in letters }) else "-"
    /** What the app holds, for a log line: the mode, Save's dialog, the files, the recording. */
    private fun st() = "mode=${session.mode} save=${save.ask != null} files=${filesNow()} rec=${client.state.value?.tourId}"

    private val followHost = object : TourFollowHost {
        override val route: PlannedRoute? get() = routeNow
        override val shown: ShownTrack? get() = shownNow
        override fun placeName(p: GeoPoint): String? = names[p]
        override fun newRouteName(prefix: String) = "$prefix 19 Nov"
        override fun stopsAsSaved(pts: List<GeoPoint>) = pts.mapIndexed { i, p -> com.onpath.app.gpx.GpxWaypoint(p.lat, p.lon, names[p] ?: "stop ${i + 1}") }
        override fun savedTourUuid(id: Long): String? = null
        override fun invalidateSplits() { edits.splitRequests.invalidate() }
        /** `AdoptBaseline` is what `syncNavActivity` says: the line already followed, the history and the arrival box at that moment. */
        override fun routeEvent(e: RouteRun.Event) {
            if (e == RouteRun.Event.AdoptBaseline) switchCalls++
            if (throwInSync) throw IllegalStateException("the route run broke")
            log += "routeEvent($e) [line=${ids(follow.followLine?.filter { it in letters })} v=${follow.navVersion} arrival=${ctl.arrivalAsk} history=${history.find(plan.points.toList()) != null}]"
        }
        override fun tourChanged(id: Long) = Unit
    }
    private var throwInSync = false
    private var switchCalls = 0

    private val editsHost = object : TourEditsHost {
        override val route: PlannedRoute? get() = routeNow
        override fun replan() {
            assertMain("replan")
            edits.followCancel?.cancel(); edits.followCancel = null
            log += "replan ${st()}"
        }
        override fun mapsForNewPoints(before: List<GeoPoint>, after: List<GeoPoint>) = Unit
        override fun noPositionNote(note: PlanGps.Note, text: org.jetbrains.compose.resources.StringResource) = Unit
        override fun placeName(p: GeoPoint): String? = names[p]
        override fun newRouteName(prefix: String) = "$prefix 19 Nov"
        override fun routeRunEvent(e: RouteRun.Event) { log += "routeEvent($e)" }
        override fun closeShownTrack() { shownNow = null }
        override fun resumeNavigation() { session.continueNavigation() }
    }

    private val sessionHost = object : TourSessionHost {
        override val errorCard: String? get() = board.errorCard
        override val route: PlannedRoute? get() = routeNow
        override var shown: ShownTrack?
            get() = shownNow
            set(v) { assertMain("shown"); log += "shown=${v?.name} ${st()}"; shownNow = v }
        override val headingUp: Boolean get() = this@TourSessionFlowTest.headingUp
        override val hasGpsFix: Boolean get() = gpsFix
        override fun clearHints() {
            assertMain("clearHints")
            if (failClearHints) error("hints broke")
            log += "clearHints [${st()} followMe=${map.followMe} size=${planEdit.planSize}]"
        }
        override fun closeMenusForNavigate() {
            assertMain("closeMenusForNavigate")
            log += "closeMenus [followMe=${map.followMe} zoom=${session.navStartZoom}]"
        }
        override fun secureTourCorridor(line: List<GeoPoint>, last: GeoPoint?, activity: Activity) {
            assertMain("secureTourCorridor")
            log += "secureTourCorridor(${line.size} points, last=${ids(listOf(last!!))}, $activity) [${st()} planned=${planned().size} sent=${client.actions}]"
        }
    }

    private class AtSend(val action: String, val extras: Map<String, Any>, val mode: AppMode, val saveOpen: Boolean, val followMe: Boolean,
                         val size: PlanSize, val files: FollowStore.Followed, val menusClosed: Int, val zoom: Double?, val shown: ShownTrack?)
    private val atSend = ArrayList<AtSend>()

    private var created = false
    private fun create() {
        if (created) return
        created = true
        tmp.create()
        planStore.points = startPoints
        plan = PlanDocument(planStore)
        if (!::files.isInitialized) files = FollowStore(tmp.root)
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
                override val mode: AppMode get() = session.mode
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
            override fun loadMapsForNewPoints(before: List<GeoPoint>) = Unit
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
        edits = TourEdits(plan, planEdit, planRoute, follow, client, Position { here }, history, editsHost, Say(::say), scope, planRoute.threads.routing)
        val recentHost = object : RecentPlansHost {
            override fun insertDraft(row: RecentPlans.DraftRow): Long = 0L
            override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) = Unit
            override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String? = null
            override fun tourChanged(id: Long) = Unit
            override fun purged(uuid: String) = Unit
            override fun syncChanged(why: String) = Unit
            override fun refreshTours() = Unit
            override fun problem(text: String) = Unit
            override var trimmed = false
        }
        val saveStore = object : SaveStore {
            override fun getTour(id: Long) = db.getTour(id)
            override fun loadSegments(id: Long) = db.loadSegments(id)
            override fun insertPlanned(name: String, row: PlannedRow) =
                db.insertTour(TourKind.PLANNED, name, row.activity, row.sac, row.segments, row.stops, distance = row.distanceM,
                    ascent = row.ascentM, descent = row.descentM, duration = row.durationS, pinned = row.pins, difficulty = row.difficulty, days = row.days)
            override fun replacePlanned(id: Long, row: PlannedRow) =
                db.replacePlanned(id, row.activity, row.sac, row.distanceM, row.ascentM, row.descentM, row.durationS, row.segments, row.stops,
                    row.pins, difficulty = row.difficulty, days = row.days)
            override fun rename(id: Long, name: String) = db.rename(id, name)
            override fun setFavourite(id: Long, favourite: Boolean) = db.setFavourite(id, favourite)
        }
        save = SaveSlice(plan, routes, RecentPlans(routes, routesStore, recentHost, scope, Dispatchers.Unconfined), saveStore, object : SaveHost {
            override val route: PlannedRoute? get() = routeNow
            override val activity: Activity get() = Activity.HIKE
            override val sac: Int get() = 3
            override fun stops(points: List<GeoPoint>) = PlanGpx.stops(points) { names[points[it]] }
            override fun difficulty(route: PlannedRoute?, activity: Activity): String? = route?.let { "MODERATE" }
            override fun days(points: List<GeoPoint>): MultiDay? = null
            override fun keepWhole(): Boolean = false
            override fun overwriteWhole(name: String, then: (() -> Unit)?): Boolean = false
            override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String? {
                log += "proposeName [${st()} corridor=${log.any { it.startsWith("secureTourCorridor") }} sent=${client.actions}]"; return null
            }
            override fun datedName() = "Route 19 Nov, 10:12"
            override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) = Unit
            override fun forgetTimes(id: Long) = Unit
            override fun copyTimes(from: Long, to: Long, distanceM: Double) = Unit
            override fun tourChanged(id: Long) = Unit
            override fun syncChanged(why: String) = Unit
            override fun refreshTours() = Unit
            override fun saved(name: String?, id: Long, points: List<GeoPoint>?) { follow.setSaved(name, id, points) }
            override fun shownRenamed(id: Long, name: String) = Unit
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) = this@TourSessionFlowTest.say(text, kind, place)
        }, scope, io)
        map = MapViewSlice(board, object : Camera {
            override fun fit(points: List<GeoPoint>) { log += "camera.fit" }
            override fun centerOn(point: GeoPoint, zoom: Double) { log += "camera.centerOn" }
            override fun panTo(point: GeoPoint) = Unit
        }, Position { here }, object : MapViewHost {
            override val mode: AppMode get() = session.mode
            override val activity: Activity get() = planRoute.activity
            override val headingUp: Boolean get() = this@TourSessionFlowTest.headingUp
            override fun setHeadingUp(on: Boolean) { this@TourSessionFlowTest.headingUp = on }
            override var navStartZoom: Double?
                get() = session.navStartZoom
                set(v) { session.navStartZoom = v }
            override val routePoints: List<GeoPoint>? get() = routeNow?.points
            override val waypoints: List<GeoPoint> get() = plan.points.toList()
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
        }, scope, { main.now }, { main.now })
        end = TourEndSlice(ctl, routes, client, tours, rows, object : TourEndHost {
            override val activity: Activity get() = Activity.HIKE
            override fun activityOrNull(name: String?): Activity? = Activity.entries.firstOrNull { it.name == name }
            override fun tourTimes(t: Tour): TourTimes? = TourTimes(movingS = 400.0, breakS = 0.0)
            override fun nameRecordedTour(t: Tour) { log += "nameRecordedTour(${t.id})" }
            override fun refreshTours() { assertMain("refreshTours"); log += "refreshTours [rec=${client.state.value?.tourId}]" }
            override fun clearFollow() { assertMain("clearFollow"); log += "clearFollow [rec=${client.state.value?.tourId}]"; follow.clearFollow() }
            override fun enterMode(m: AppMode) { assertMain("enterMode"); log += "enterMode($m) [rec=${client.state.value?.tourId}]"; session.enter(m) }
            override fun endGuidanceFlag() { follow.guidanceEndedByEmptyPlan = false }
            override fun kickThumbs() = Unit
            override fun demoEndedWithTour() { log += "demoEndedWithTour [rec=${client.state.value?.tourId}]" }
            override fun tourChanged(id: Long) = Unit
            override fun clearInterruptedNote() = Unit
            override val planPoints: Int get() = plan.points.size
            override fun clearPlan() { log += "clearPlan"; planEdit.clearPlan() }
            override var routeBackAsk: Boolean
                get() = planEdit.routeBackAsk
                set(v) { planEdit.routeBackAsk = v }
            override var planSize: PlanSize
                get() = planEdit.planSize
                set(v) { planEdit.planSize = v }
            override val shownTourId: Long? get() = shownNow?.tourId
            override fun reopenShownTour(t: Tour) = Unit
        }, Say(::say), scope, io, main)
        session = TourSession(plan, planEdit, planRun, planRoute, save, map, ctl, follow, client, rows, tours, history, sessionHost,
            Say(::say), scope, io, main)
        client.onSend = { cmd ->
            atSend += AtSend(cmd.action.substringAfterLast('.'), cmd.extras, session.mode, save.ask != null, map.followMe, planEdit.planSize,
                files.load(), log.count { it.startsWith("closeMenus") }, session.navStartZoom, shownNow)
            log += "send ${cmd.action.substringAfterLast('.')} ${cmd.extras.values.joinToString(",")} [${st()}]"
        }
    }

    private val startFailedAtSay = ArrayList<String?>()
    private fun say(text: String, kind: NoticeKind, place: NoticePlace) {
        if (text == "say breaks") throw IllegalStateException("the notice board broke")
        if (text == "Recording could not start: permission missing") startFailedAtSay += client.startFailed.value
        said += Triple(text, kind, place)
        board.say(text, kind, place)
    }
    private fun error(text: String, place: NoticePlace) = Triple(text, NoticeKind.ERROR, place)
    private val notStarted get() = error(Txt.s(Res.string.msg_recording_not_started), NoticePlace.MAP)

    @After fun close() { scope.cancel(); db.close() }

    // ---------------- the service, played by the test ----------------

    private fun recState(id: Long, session: NavSession? = null, arrived: Boolean = false, saveFailed: Boolean = false, track: List<List<GeoPoint>> = emptyList()) = RecState(
        tourId = id, paused = false, distanceM = 100.0, ascentM = 0.0, descentM = 0.0, elapsedS = 60, gpsLost = false, offRouteM = null,
        offRouteAlert = false, followName = session?.name, track = track, arrived = arrived, saveFailed = saveFailed,
        nav = session?.let { NavState(it.active, it.version, null, null, null, null, it, 0.0) })

    /** The service has read the files (what it does at START and at FOLLOW_CHANGED) and publishes what it navigates. */
    private fun serviceTakes(id: Long = tour7, arrived: Boolean = false) = client.publish(recState(id, files.load().session, arrived = arrived))

    /** A tour that follows the plan a–e and the service has taken it; the app is in Navigate (started the real way: no collector yet). */
    private fun runningTour() {
        create()
        routeNow = routeFor(plan.points.toList())
        ui { follow.followPlanned() }
        stepPc()
        serviceTakes()
        ui { session.enter(AppMode.NAVIGATE) }
        atSend.clear(); log.clear(); client.sent.clear()
    }

    private val last get() = atSend.last()
    private fun actions() = client.actions

    // ======================================================================================================================
    // Start (rules 2, 3, 15)
    // ======================================================================================================================

    /**
     * Rule 2 / 3 / 15: "Los geht's" on a plan: what is followed is written (the files), the corridor is asked for, the plan is saved
     * (`atTourStart`), THEN `START` goes out – with the activity and the T level; the mode stays Plan until the service's first state,
     * then Navigate, and the service is told `NAVIGATING on=true` only AFTER that state and with the mode already set.
     */
    @Test fun losGehtsOnAPlanFollowsItSecuresTheCorridorSavesThenSendsSTART() {
        create(); routeNow = routeFor(plan.points.toList())
        assertEquals("a plan never saved", 0, planned().size)
        ui { session.startNavigation() }
        assertEquals("the order of the start: the files, the corridor, the save – START last", listOf(
            "secureTourCorridor(9 points, last=e, HIKE) [mode=PLAN save=false files=abcde rec=null planned=0 sent=[]]",
            "proposeName [mode=PLAN save=false files=abcde rec=null corridor=true sent=[]]",
            "send START HIKE,3 [mode=PLAN save=false files=abcde rec=null]",
        ), log)
        assertEquals("START carries the activity and the level", RecordingCommand.start("HIKE", 3), client.sent.single())
        assertEquals("the session is on disk before the command", "abcde", ids(last.files.session!!.stops.map { it.point }))
        assertEquals("not in Navigate before the service records", AppMode.PLAN, session.mode)
        stepIo()
        assertEquals("the plan was saved at the start (the Neu planen way, no dialog)", 1, planned().size)
        // the service records: Navigate follows, and NAVIGATING comes after the state, with the mode already set
        serviceTakes()
        assertEquals(AppMode.NAVIGATE, session.mode)
        assertEquals(listOf("START", "NAVIGATING"), actions())
        assertEquals(mapOf<String, Any>(RecordingWire.EXTRA_ON to true), client.sent.last().extras)
        assertEquals("told with the mode set, the menus closed and the map following", Triple(AppMode.NAVIGATE, 1, true), Triple(last.mode, last.menusClosed, last.followMe))
        assertEquals("the start zoom of a hike", 17.0, session.navStartZoom!!, 0.0)
    }

    /** Rule 3: no first state in 6 s → the message and the mode stays Plan; the constant is 6,000 ms. */
    @Test fun noStateInSixSecondsSaysSoAndStaysInPlanAndAnOpenErrorBoxGetsNoSecondOne() {
        create(); routeNow = routeFor(plan.points.toList())
        ui { session.startNavigation() }
        advance(5_999)
        assertEquals("still waiting at 5,999 ms", emptyList<Any>(), said)
        advance(1)
        assertEquals(listOf(notStarted), said)
        assertEquals(AppMode.PLAN, session.mode)
        // a box already open: no second one (opposite first: the box was empty above)
        ui { session.startNavigation() }
        assertNotNull(board.errorCard)
        advance(6_000)
        assertEquals("no second box over the open one", listOf(notStarted), said)
        assertEquals(AppMode.PLAN, session.mode)
    }

    /** Rule 3: a newer start cancels the older wait – the older one's six seconds fire nothing. */
    @Test fun aSecondStartWithinTheWaitCancelsTheFirst() {
        create(); routeNow = routeFor(plan.points.toList())
        ui { session.startNavigation() }
        advance(3_000)
        ui { session.startNavigation() }
        advance(3_000) // 6,000 ms after the FIRST start
        assertEquals("the first wait is cancelled", emptyList<Any>(), said)
        advance(2_999)
        assertEquals(emptyList<Any>(), said)
        advance(1) // 6,000 ms after the second
        assertEquals("exactly one message", listOf(notStarted), said)
        assertEquals(listOf("START", "START"), actions())
    }

    /** Rule 2: a start while a tour records is Continue – no second START, the check goes out. */
    @Test fun aStartWhileATourRecordsIsContinueNotASecondTour() {
        runningTour()
        ui { session.enter(AppMode.PLAN) }
        client.sent.clear()
        ui { session.startNavigation() }
        assertEquals("no START: continue, the plan is the followed one → check now, then Navigate", listOf("CHECK_NOW", "NAVIGATING"), actions())
        assertEquals(AppMode.NAVIGATE, session.mode)
    }

    /** Rule 2: an interrupted tour still checked / asked about holds the start – nothing sent, the plan and the files untouched. */
    @Test fun anInterruptedTourHoldsTheStartAndNothingIsTouched() {
        create(); routeNow = routeFor(plan.points.toList())
        ctl.unfinishedPending = true
        ui { session.startNavigation() }
        assertEquals(emptyList<String>(), actions())
        assertEquals("the plan is untouched", "abcde", ids(plan.points.toList()))
        assertEquals("nothing is followed", "-", filesNow())
        assertTrue(problems.traced("tour").contains("Tour start held: an interrupted tour is still checked / asked about"))
        ctl.unfinishedPending = false
        ctl.showUnfinished(db.getTour(tour7)!!, emptyList())
        ui { session.startNavigation() }
        assertEquals("the question open holds it too", emptyList<String>(), actions())
        ctl.closeUnfinished()
        ui { session.startNavigation() }
        assertEquals("the opposite: nothing holds it any more", listOf("START"), actions())
    }

    /** Rule 2: a plan with straight legs asks first – nothing sent, nothing followed; "Navigate anyway" starts; Record only never asks. */
    @Test fun aPlanWithStraightLegsAsksFirstAndNothingIsSentOrFollowed() {
        create(); routeNow = routeFor(plan.points.toList(), straight = true)
        assertFalse(session.straightAsk)
        ui { session.startNavigation() }
        assertTrue(session.straightAsk)
        assertEquals(emptyList<String>(), actions())
        assertEquals("nothing followed yet", "-", filesNow())
        ui { session.straightConfirmed() }
        assertFalse("the question is closed", session.straightAsk)
        assertEquals(listOf("START"), actions())
        assertEquals("abcde", filesNow())
    }

    @Test fun recordOnlyNeverAsksAboutStraightLegsAndFollowsNothing() {
        create(); routeNow = routeFor(plan.points.toList(), straight = true)
        ui { follow.followPlanned() } // something was followed before: it goes
        assertEquals("abcde", filesNow())
        shownNow = ShownTrack(null, "Shown", listOf(listOf(far, a)), emptyList(), 1.0, 0.0, 0.0, 1.0)
        ui { session.startNavigation(recordOnly = true) }
        assertFalse(session.straightAsk)
        assertEquals("nothing is followed (cleared before START)", "-", last.files.follow?.let { ids(it.second) } ?: "-")
        assertNull("the shown track closed before START", last.shown)
        assertEquals(listOf("START"), actions())
    }

    /** Rule 2: with a track shown the start follows that track (not the plan), closes it as "shown", then START. */
    @Test fun startingWithATrackShownFollowsTheTrackClosesItThenSTART() {
        create(); routeNow = routeFor(plan.points.toList())
        shownNow = ShownTrack(null, "Shown track", listOf(listOf(far, a, b)), emptyList(), 1.0, 0.0, 0.0, 1.0)
        ui { session.startNavigation() }
        assertEquals("the files hold the track at START", "fab", ids(last.files.follow!!.second))
        assertNull("the shown track is closed by then", last.shown)
        assertTrue("the plan is not followed", follow.followIsTrack)
        assertEquals(listOf("START"), actions())
    }

    /** A plan not routed yet (button waits): record, nothing followed. */
    @Test fun aPlanWithoutARouteYetRecordsAndFollowsNothing() {
        create(); routeNow = null
        ui { session.startNavigation() }
        assertEquals(listOf("START"), actions())
        assertEquals("-", filesNow())
    }

    // ======================================================================================================================
    // enter (rule 15)
    // ======================================================================================================================

    /** Rule 15: the mode is set first, Save's dialog is closed before the service is told, the service last. */
    @Test fun enteringAModeClosesSavesDialogAndTheHintsBeforeAnythingElse() {
        runningTour()
        ui { save.request() }
        assertNotNull("Save's dialog is open (the opposite first)", save.ask)
        ui { session.enter(AppMode.PLAN) }
        assertTrue("the hints are cleared with the mode already set (and the dialog not yet closed)", log.any { it.startsWith("clearHints [mode=PLAN save=true") })
        assertNull("Save's dialog does not stay open behind another mode", save.ask)
        assertFalse("and it was closed when the service was told", last.saveOpen)
        assertEquals(AppMode.PLAN, last.mode)
        assertEquals("NAVIGATING off", mapOf<String, Any>(RecordingWire.EXTRA_ON to false), client.sent.last().extras)
    }

    /** Rule 15: entering Navigate closes the menus THEN follows the position; the zoom is taken once per start; Plan clears it. */
    @Test fun enteringNavigateClosesTheMenusThenFollowsAndTakesTheStartZoomOnce() {
        create()
        assertFalse("not following before", map.followMe)
        ui { session.enter(AppMode.NAVIGATE) }
        assertEquals("the menus were closed while the map did not follow yet", listOf("clearHints [mode=NAVIGATE save=false files=- rec=null followMe=false size=${planEdit.planSize}]",
            "closeMenus [followMe=false zoom=17.0]"), log)
        assertTrue(map.followMe)
        assertNotNull(session.navStartZoom)
        ui { session.enter(AppMode.PLAN) }
        assertNull("Plan clears a zoom not taken", session.navStartZoom)
        ui { session.enter(AppMode.NAVIGATE) }
        assertNotNull(session.navStartZoom)
        session.navStartZoom = null // the map took it
        ui { session.enter(AppMode.NAVIGATE) }
        assertNull("once per start: Navigate again takes none", session.navStartZoom)
        assertEquals("no tour: the service is not told", emptyList<String>(), actions())
    }

    /** Rule 15: back in Plan with the compass on walking direction keeps following only with a fix; north up stops. */
    @Test fun backInPlanKeepsFollowingOnlyWithTheCompassOnTravelAndAFix() {
        create()
        for ((up, fix, expected) in listOf(Triple(true, true, true), Triple(true, false, false), Triple(false, true, false))) {
            headingUp = up; gpsFix = fix
            ui { session.enter(AppMode.NAVIGATE) }
            assertTrue(map.followMe)
            ui { session.enter(AppMode.PLAN) }
            assertEquals("headingUp=$up fix=$fix", expected, map.followMe)
        }
    }

    /**
     * Rule 15: Navigate → Plan during a tour: the sheet is one row and the plan comes into view ("from navigate"), and the
     * service is told LAST (the size is already stepped when `NAVIGATING off` arrives); after a tour's end the moment is TOUR_END.
     */
    @Test fun backInPlanDuringATourStepsTheSheetShowsThePlanAndTellsTheServiceLast() {
        runningTour()
        planEdit.planSize = PlanSize.FULL
        ui { session.enter(AppMode.PLAN) }
        assertEquals("the sheet is one row", PlanSize.backInPlan(PlanSize.FULL, true), planEdit.planSize)
        assertTrue("the size was stepped when the service was told", last.size != PlanSize.FULL)
        assertEquals(listOf("NAVIGATING"), actions())
        main.advance(500); stepIo()
        assertTrue(problems.traced("map").any { it.startsWith("Plan shown whole (from_navigate, 5 points)") })
        // no tour any more: back in Plan from Navigate is the tour's end
        ui { session.enter(AppMode.NAVIGATE) }
        client.publish(null)
        client.sent.clear()
        ui { session.enter(AppMode.PLAN) }
        main.advance(500)
        assertTrue(problems.traced("map").any { it.startsWith("Plan shown whole (tour_end, 5 points)") })
        assertEquals("no tour: no command", emptyList<String>(), actions())
    }

    // ======================================================================================================================
    // Continue (rule 22)
    // ======================================================================================================================

    /** Rule 22: the plan is the followed one → only "check now"; THEN Navigate (the mode was still Plan at the command). */
    @Test fun continueOnTheFollowedPlanChecksNowThenEntersNavigate() {
        runningTour()
        ui { session.enter(AppMode.PLAN) }
        client.sent.clear(); atSend.clear()
        ui { session.continueNavigation() }
        assertEquals(listOf("CHECK_NOW", "NAVIGATING"), actions())
        assertEquals("the check goes out before the mode changes", AppMode.PLAN, atSend.first().mode)
        assertEquals(AppMode.NAVIGATE, session.mode)
    }

    /** Rule 22: an edited plan is followed with "check now" (the files first), THEN Navigate. */
    @Test fun continueOnAnEditedPlanFollowsItWithCheckNowThenEntersNavigate() {
        runningTour()
        ui { session.enter(AppMode.PLAN) }
        client.sent.clear(); atSend.clear()
        ui { planEdit.removePoint(3) }
        routeNow = routeFor(plan.points.toList())
        ui { session.continueNavigation() }
        assertEquals(listOf("FOLLOW_CHANGED", "NAVIGATING"), actions())
        assertEquals(mapOf<String, Any>(RecordingWire.EXTRA_CHECK_NOW to true), client.sent.first().extras)
        assertEquals("the files hold the edited plan at the command", "abce", ids(atSend.first().files.session!!.stops.map { it.point }))
        assertEquals("the mode is still Plan at the command", AppMode.PLAN, atSend.first().mode)
        assertEquals(AppMode.NAVIGATE, session.mode)
    }

    /** Rule 22: an edited plan still routing: the result is followed when it is there ("follow next"), meanwhile only Navigate. */
    @Test fun continueOnAPlanStillRoutingAsksTheRunToFollowTheResultAndEntersNavigate() {
        runningTour()
        ui { session.enter(AppMode.PLAN) }
        client.sent.clear()
        ui { planEdit.removePoint(3) }
        planRun.routeEvent(RouteRun.Event.Recalc(plan.points.toList(), Activity.HIKE, planMode = true, newPlan = false))
        assertTrue("the plan is routing (the opposite of the next step)", planRun.routing)
        assertFalse(planRun.routeRun.followNext)
        ui { session.continueNavigation() }
        assertTrue("the result will be followed", planRun.routeRun.followNext)
        assertEquals("no check and no follow yet: the result decides", listOf("NAVIGATING"), actions())
        assertEquals(AppMode.NAVIGATE, session.mode)
    }

    /** Rule 22: an edited plan with straight legs asks first (nothing sent, mode unchanged); `force` goes on. */
    @Test fun continueOnAnEditedPlanWithStraightLegsAsksFirst() {
        runningTour()
        ui { session.enter(AppMode.PLAN) }
        client.sent.clear()
        ui { planEdit.removePoint(3) }
        routeNow = routeFor(plan.points.toList(), straight = true)
        ui { session.continueNavigation() }
        assertTrue(session.straightAsk)
        assertEquals(emptyList<String>(), actions())
        assertEquals(AppMode.PLAN, session.mode)
        // „Trotzdem navigieren“ is the dialog's own way: straightConfirmed → startNavigation(force) → continue with that force
        ui { session.straightConfirmed() }
        assertFalse("the question is answered, not asked again", session.straightAsk)
        assertEquals(listOf("FOLLOW_CHANGED", "NAVIGATING"), actions())
        assertEquals(AppMode.NAVIGATE, session.mode)
    }

    /** A followed track with the plan unchanged since: Continue only checks. */
    @Test fun continueOnAFollowedTrackOnlyChecks() {
        runningTour()
        follow.followIsTrack = true; follow.planAtTrackFollow = plan.points.toList()
        ui { session.enter(AppMode.PLAN) }
        client.sent.clear()
        ui { session.continueNavigation() }
        assertEquals(listOf("CHECK_NOW", "NAVIGATING"), actions())
    }

    // ======================================================================================================================
    // the seven answers (rule 31)
    // ======================================================================================================================

    @Test fun theSevenAnswersPassTheQuestionsIdThroughAndChangeNothingInTheApp() {
        runningTour()
        val before = Triple(session.mode, filesNow(), plan.points.toList())
        ui {
            session.takeNewRoute(1); session.takeViaRoute(2); session.keepOriginalRoute(3); session.leadToStart(4)
            session.justRecord(5); session.joinHere(6); session.leadFailedOk(7)
        }
        assertEquals(listOf(RecordingCommand.takeRoute(1), RecordingCommand.takeVia(2), RecordingCommand.keepRoute(3), RecordingCommand.leadThere(4),
            RecordingCommand.justRecord(5), RecordingCommand.joinHere(6), RecordingCommand.leadFailedOk(7)), client.sent.toList())
        assertEquals("the app is as it was", before, Triple(session.mode, filesNow(), plan.points.toList()))
    }

    // ======================================================================================================================
    // the start-up work (rules 4, 5, 6)
    // ======================================================================================================================

    /** Rule 4: with a service already recording at construction the mode is Navigate at once, before any io, and no command is sent by the flip. */
    @Test fun aServiceAlreadyRecordingMakesItNavigateAtOnceWithoutACommand() {
        create(); routeNow = routeFor(plan.points.toList())
        serviceTakes()
        assertEquals("Plan at construction (recalc saw Plan)", AppMode.PLAN, session.mode)
        ui { session.start() }
        assertEquals("the flip is inline", AppMode.NAVIGATE, session.mode)
        assertEquals("the flip sends nothing (it is not `enter`)", emptyList<String>(), actions())
        assertFalse("the start mode is not known before the check", map.startModeKnown)
        assertTrue("the check holds new tours meanwhile", ctl.unfinishedPending)
        stepIo()
        assertTrue(map.startModeKnown)
        assertFalse(ctl.unfinishedPending)
        assertEquals("the check's `enter(Navigate)` tells the running service", listOf("NAVIGATING"), actions())
        assertNull("a tour that runs asks no question", ctl.unfinished)
    }

    /** Rule 5: an interrupted tour, no service: Navigate and "start mode known" BEFORE the wait; no question at 1,499 ms; at 1,500 the question with the track. */
    @Test fun anInterruptedTourAsksItsQuestionAfterTheStickyWaitNotBefore() {
        val id = unfinishedId
        create()
        ui { session.start() }
        assertTrue("held from the first moment", ctl.unfinishedPending)
        assertFalse("the start mode is not known before io ran", map.startModeKnown)
        stepIo()
        assertEquals("Navigate and known before the wait", Triple(AppMode.NAVIGATE, true, null), Triple(session.mode, map.startModeKnown, ctl.unfinished))
        assertTrue("still held while it waits", ctl.unfinishedPending)
        assertFalse(ctl.canStartNew)
        advance(1_499)
        assertNull("no question at 1,499 ms", ctl.unfinished)
        assertTrue(ctl.unfinishedPending)
        advance(1)
        assertEquals("the question for the interrupted tour", id, ctl.unfinished!!.id)
        assertEquals("with its track drawn behind it", 2, ctl.unfinishedTrack.single().size)
        assertFalse("the hold is released after it", ctl.unfinishedPending)
        assertEquals("the service is told only the mode (running: none)", emptyList<String>(), actions())
    }

    /** Rule 5: the service comes back by itself within the wait → no question; the hold is released. */
    @Test fun aServiceThatComesBackWithinTheWaitAsksNothing() {
        unfinishedId
        create(); routeNow = routeFor(plan.points.toList())
        ui { session.start() }
        stepIo()
        assertTrue(ctl.unfinishedPending)
        serviceTakes()
        advance(1_500)
        assertNull("the second state check: no question for a tour that records", ctl.unfinished)
        assertFalse(ctl.unfinishedPending)
    }

    /** Rule 5: nothing interrupted, nothing running → Plan, "known", no question. */
    @Test fun noInterruptedTourAndNoServiceIsPlan() {
        create()
        ui { session.start() }
        stepIo()
        assertEquals(AppMode.PLAN, session.mode)
        assertTrue(map.startModeKnown)
        assertNull(ctl.unfinished)
        assertFalse(ctl.unfinishedPending)
        assertTrue("`enter(Plan)` ran (the hints are cleared)", log.any { it.startsWith("clearHints [mode=PLAN") })
        advance(5_000)
        assertNull(ctl.unfinished)
    }

    /** Rule 5: the hold is released whatever happens – a track that cannot be read still asks (empty line), a cancelled check releases it too. */
    @Test fun theHoldIsReleasedWhateverHappensAndAnUnreadableTrackStillAsks() {
        val id = unfinishedId
        create()
        failSegments = true
        ui { session.start() }
        stepIo(); advance(1_500)
        assertEquals("the question without the track", id, ctl.unfinished!!.id)
        assertEquals(emptyList<List<GeoPoint>>(), ctl.unfinishedTrack)
        assertTrue(problems.said("db").any { it == "Track of the unfinished tour could not be read – not drawn" })
        assertFalse(ctl.unfinishedPending)
    }

    @Test fun aCancelledCheckStillReleasesTheHold() {
        unfinishedId
        create()
        ui { session.start() }
        stepIo()
        assertTrue("held while it waits (the opposite first)", ctl.unfinishedPending)
        scope.cancel()
        stepIo()
        assertFalse("`finally` is not cancellable", ctl.unfinishedPending)
    }

    /** L2 (fixed 2026-11-19): a failing read of the active tour is reported and read as "none found" – the start mode is entered and known. */
    @Test fun aFailingActiveTourReadIsReportedAndTheAppStillStartsInItsStartMode() {
        create()
        rows.failOn += "findActiveRecording"
        assertFalse("the opposite first", map.startModeKnown)
        ui { session.start() }
        assertTrue(ctl.unfinishedPending)
        stepIo()
        assertTrue("known", map.startModeKnown)
        assertEquals("the start mode of the nothing-found case", AppMode.PLAN, session.mode)
        assertTrue("`enter(Plan)` ran", log.any { it.startsWith("clearHints [mode=PLAN") })
        assertNull("no question for a tour that could not be read", ctl.unfinished)
        assertFalse("the hold is released", ctl.unfinishedPending)
        assertTrue("reported", problems.said("app").any { it.contains("Active tour could not be read") })
        assertEquals("nothing sent to the service", emptyList<String>(), actions())
        advance(5_000)
        assertNull(ctl.unfinished)
    }

    /** L2: the read fails while a service records: Navigate, known, the running tour is not disturbed (no question). */
    @Test fun aFailingActiveTourReadWhileTheServiceRecordsStaysInNavigate() {
        create(); routeNow = routeFor(plan.points.toList())
        serviceTakes()
        rows.failOn += "findActiveRecording"
        ui { session.start() }
        stepIo(); advance(5_000)
        assertEquals(AppMode.NAVIGATE, session.mode)
        assertTrue(map.startModeKnown)
        assertNull(ctl.unfinished)
        assertFalse(ctl.unfinishedPending)
    }

    /** L2: `enter(start)` itself throws – reported, and the start mode is still known. */
    @Test fun aThrowingEnterAtStartStillMakesTheStartModeKnown() {
        create()
        failClearHints = true
        assertFalse("the opposite first", map.startModeKnown)
        ui { session.start() }
        stepIo()
        assertTrue(map.startModeKnown)
        assertFalse(ctl.unfinishedPending)
        assertTrue("reported", problems.said("app").isNotEmpty())
    }

    /** L2: the read ends in a cancellation (the job is cancelled while it is pending; the scope itself stays alive here, so only the rethrow keeps `enter` away) – nothing reported, nothing entered, not known (a cancellation is not a failure); the hold still goes. */
    @Test fun aCancelledStartUpCheckReportsNothingAndEntersNothing() {
        create()
        onFindActive = { throw java.util.concurrent.CancellationException("cancelled while reading") }
        assertFalse("the opposite first", map.startModeKnown)
        ui { session.start() }
        stepIo()
        assertFalse(map.startModeKnown)
        assertTrue(problems.said("app").none { it.contains("Active tour could not be read") })
        assertTrue(log.none { it.startsWith("clearHints") })
        assertFalse("`finally` still releases the hold", ctl.unfinishedPending)
    }

    /** Rule 6: an empty plan and a followed line left over, no active tour in the database, nothing recording → the line goes. */
    @Test fun aLeftOverLineWithAnEmptyPlanAndNoTourIsRemoved() {
        tmp.create(); files = FollowStore(tmp.root); files.appFollows(null, "left over", listOf(a, b, c))
        startPoints = emptyList()
        create()
        assertEquals("the line was restored at construction", "abc", ids(follow.followLine))
        ui { session.start() }
        assertEquals("still there before io ran", "abc", ids(follow.followLine))
        stepIo()
        assertNull("gone", follow.followLine)
        assertEquals("-", filesNow())
        assertTrue(problems.traced("plan").contains("Empty plan, no tour: the followed line left from before is removed"))
    }

    private fun leftOverLine() {
        tmp.create(); files = FollowStore(tmp.root); files.appFollows(null, "left over", listOf(a, b, c))
        startPoints = emptyList()
    }

    @Test fun aLeftOverLineStaysWhenAnActiveTourIsInTheDatabase() {
        leftOverLine(); unfinishedId; create()
        ui { session.start() }; stepIo()
        assertEquals("abc", ids(follow.followLine))
        assertEquals("the files too", "abc", filesNow())
    }

    @Test fun aLeftOverLineStaysWhenTheServiceRecordsByTheTimeTheDatabaseIsRead() {
        leftOverLine(); create()
        ui { session.start() }
        serviceTakes() // the service came back while the database was read (held)
        stepIo()
        assertEquals("abc", ids(follow.followLine))
    }

    @Test fun aLeftOverLineStaysWhenTheActiveTourCannotBeChecked() {
        leftOverLine(); create()
        rows.failOn += "findActiveRecording"
        ui { session.start() }; stepIo()
        assertEquals("abc", ids(follow.followLine))
        assertTrue(problems.said("db").any { it == "Active tour not checked at start; the followed line stays" })
    }

    @Test fun aLeftOverTrackAndAPlanWithPointsAreNotRemoved() {
        leftOverLine(); startPoints = listOf(a, b); create()
        ui { session.start() }; stepIo()
        assertEquals("a plan with points: the line is the plan's guidance", "abc", ids(follow.followLine))
    }

    // ======================================================================================================================
    // the service's own: startFailed, the state (rules 13, 14)
    // ======================================================================================================================

    /** Rule 13: the service stopped by itself: marked seen BEFORE the reason is said; back to Plan only from Navigate. */
    @Test fun aStartThatFailedInTheServiceIsSeenThenSaidThenBackToPlanOnlyFromNavigate() {
        create()
        val board0 = said
        ui { session.start() }
        stepIo()
        // from Navigate
        ui { session.enter(AppMode.NAVIGATE) }
        log.clear()
        client.fail("Recording could not start: permission missing")
        assertNull("seen: the service's value is cleared", client.startFailed.value)
        assertEquals("and it was cleared BEFORE the reason was said", listOf<String?>(null), startFailedAtSay.toList())
        assertEquals(listOf(Triple("Recording could not start: permission missing", NoticeKind.ERROR, NoticePlace.BACKGROUND)), board0.toList())
        assertEquals("back to Plan", AppMode.PLAN, session.mode)
        // from Plan: nothing to leave
        said.clear(); log.clear()
        client.fail("again")
        assertEquals(listOf(Triple("again", NoticeKind.ERROR, NoticePlace.BACKGROUND)), said.toList())
        assertEquals("Plan stays Plan, `enter` did not run", emptyList<String>(), log)
    }

    /** L1 shape in the `startFailed` collector (also: the mode step after the say) (fixed 2026-11-19): a throw while saying the reason is reported; the next failed start is still said. */
    @Test fun aThrowWhileSayingAFailedStartIsReportedAndTheNextOneIsStillSaid() {
        create()
        ui { session.start() }
        stepIo()
        ui { session.enter(AppMode.NAVIGATE) }
        client.fail("say breaks")
        assertTrue("reported", problems.said("app").any { it.contains("Saying why the recording could not start failed") })
        assertEquals("the mode step still ran: not left in Navigate with no recording", AppMode.PLAN, session.mode)
        client.fail("next")
        assertEquals(listOf("next"), said.map { it.first })
    }

    /** L4 (kept, shown): a failed start can show two boxes – the service's reason, and after 6 s "did not start" once the first was closed. */
    @Test fun L4_aFailedStartCanShowTwoBoxes() {
        create(); routeNow = routeFor(plan.points.toList())
        ui { session.start() }; stepIo()
        ui { session.startNavigation() }
        client.fail("Foreground refused")
        assertEquals(1, said.size)
        board.errorCard = null // the user closed it
        advance(6_000)
        assertEquals(listOf("Foreground refused", Txt.s(Res.string.msg_recording_not_started)), said.map { it.first })
    }

    /** Rule 14: a new tour id clears the follow history; the same tour's next state does not; a tour's end resets "recording only". */
    @Test fun aNewTourClearsTheFollowHistoryAndATourEndResetsTheGuidanceFlag() {
        runningTour()
        val s = files.load().session!!
        history.remember(plan.points.toList(), s)
        ui { session.start() }
        stepIo()
        follow.guidanceEndedByEmptyPlan = true
        // the first pass of the collector saw tour 7 (recording): its id was new → cleared at the start
        assertNull("the first state of a tour clears the history", history.find(plan.points.toList()))
        history.remember(plan.points.toList(), s)
        serviceTakes() // the same tour, another state
        assertNotNull("the same tour keeps its history", history.find(plan.points.toList()))
        assertTrue("and the flag", follow.guidanceEndedByEmptyPlan)
        client.publish(null)
        assertFalse("the tour's end resets it", follow.guidanceEndedByEmptyPlan)
        assertTrue(problems.traced("tour").contains("Tour $tour7 recording ended"))
        history.remember(plan.points.toList(), s)
        serviceTakes(tour7 + 1)
        assertNull("another tour: cleared", history.find(plan.points.toList()))
    }

    /** Rule 14: "the track cannot be saved" once per failure: true, true, false, true → two errors. */
    @Test fun theSaveFailureIsSaidOncePerFailure() {
        create()
        ui { session.start() }
        for (failed in listOf(true, true, false, true)) client.publish(recState(tour7, saveFailed = failed))
        assertEquals(2, said.count { it.first == Txt.s(Res.string.msg_track_save_failing) })
        assertEquals(NoticePlace.BACKGROUND, said.first().third)
    }

    /** Rule 14: the arrival question once per arrival. */
    @Test fun theArrivalBoxComesOncePerArrival() {
        create()
        ui { session.start() }
        client.publish(recState(tour7, arrived = false)); assertFalse(ctl.arrivalAsk)
        client.publish(recState(tour7, arrived = true)); assertTrue(ctl.arrivalAsk)
        ctl.arrivalAsk = false
        client.publish(recState(tour7, arrived = true)); assertFalse("once per arrival", ctl.arrivalAsk)
        client.publish(recState(tour7, arrived = false))
        client.publish(recState(tour7, arrived = true)); assertTrue("a new arrival", ctl.arrivalAsk)
    }

    /**
     * Rule 14: a new navigation version makes the service's line the followed line – and that is done BEFORE the service's other
     * activity is taken over (the route run's event sees the new line); the same version is not read again.
     */
    @Test fun aNewNavVersionMakesTheServicesLineTheFollowedLineBeforeTheActivityIsAdopted() {
        runningTour()
        ui { session.start() }
        val s = files.load().session!!
        val rerouted = s.copy(version = s.version + 1, current = listOf(far, a, b), activity = Activity.BIKE_TOURING)
        client.publish(recState(tour7).copy(nav = NavState(rerouted.active, rerouted.version, null, null, null, null, rerouted, 0.0)))
        assertEquals("the service's line is followed", "fab", ids(follow.followLine))
        assertEquals(rerouted.version, follow.navVersion)
        assertEquals("the line was already the new one when the activity was adopted (the run's event)",
            1, log.count { it.startsWith("routeEvent(${RouteRun.Event.AdoptBaseline}) [line=fab") })
        // same version, another line: not taken
        client.publish(recState(tour7).copy(nav = NavState(listOf(e, d), rerouted.version, null, null, null, null, rerouted, 0.0)))
        assertEquals("fab", ids(follow.followLine))
    }

    /** Rule 14: a state without navigation forgets the seen activity – the next state of the same other activity is adopted again. */
    @Test fun aStateWithoutNavigationForgetsTheSeenActivityAndTheNextOneIsAdoptedAgain() {
        runningTour()
        ui { session.start() }
        val s = files.load().session!!.copy(activity = Activity.BIKE_TOURING)
        fun navState() = recState(tour7).copy(nav = NavState(s.active, s.version, null, null, null, null, s, 0.0))
        client.publish(navState())
        assertEquals("adopted once", 1, log.count { it.startsWith("routeEvent(${RouteRun.Event.AdoptBaseline})") })
        client.publish(navState().copy(elapsedS = 61))
        assertEquals("not again for the same activity", 1, log.count { it.startsWith("routeEvent(${RouteRun.Event.AdoptBaseline})") })
        assertEquals(Activity.BIKE_TOURING, follow.navActivitySeen)
        client.publish(recState(tour7)) // no navigation
        assertNull("forgotten", follow.navActivitySeen)
        planRoute.selectActivity(Activity.HIKE, userChange = false)
        client.publish(navState())
        assertEquals("adopted again", 2, log.count { it.startsWith("routeEvent(${RouteRun.Event.AdoptBaseline})") })
    }

    /** L1 (fixed 2026-11-19): a throw inside one pass of the state collector is reported; the next state is still followed. */
    @Test fun aThrowInOneStatePassIsReportedAndTheNextStateIsStillFollowed() {
        runningTour()
        ui { session.start() }
        val s = files.load().session!!.copy(activity = Activity.BIKE_TOURING)
        throwInSync = true
        client.publish(recState(tour7).copy(nav = NavState(s.active, s.version, null, null, null, null, s, 0.0)))
        assertTrue("reported", problems.said("app").any { it.contains("state") && it.contains("pass") })
        throwInSync = false
        client.publish(recState(tour7, arrived = true))
        assertTrue("the collector lives: the arrival is seen", ctl.arrivalAsk)
    }

    private fun bikeState(): RecState {
        val s = files.load().session!!.copy(activity = Activity.BIKE_TOURING)
        return recState(tour7).copy(nav = NavState(s.active, s.version, null, null, null, null, s, 0.0))
    }

    /** A failed activity switch is repeated by the next state (the same activity again): the mark "seen" is forgotten after the throw. */
    @Test fun aFailedActivitySwitchIsDoneByTheNextStateWithTheSameActivity() {
        runningTour()
        ui { session.start() }
        throwInSync = true
        client.publish(bikeState())
        assertEquals(1, switchCalls)
        throwInSync = false
        client.publish(bikeState().copy(elapsedS = 61L)) // same nav, same activity
        assertEquals("the switch was tried again and done", 2, switchCalls)
        assertEquals("adopted", 1, log.count { it.startsWith("routeEvent(${RouteRun.Event.AdoptBaseline})") })
    }

    /** A throw every time: three repeats, then quiet; every try reported. */
    @Test fun aSwitchThatAlwaysFailsIsRepeatedThreeTimesThenLeftQuiet() {
        runningTour()
        ui { session.start() }
        throwInSync = true
        repeat(8) { i -> client.publish(bikeState().copy(elapsedS = 61L + i)) }
        assertEquals("the first try and exactly 3 repeats", 4, switchCalls)
        assertTrue("reported (equal messages are folded by Problems)", problems.said("app").any { it.contains("State pass") })
    }

    // ======================================================================================================================
    // the whole tour on the real slices together (rules 8, 9, in the order of the rules)
    // ======================================================================================================================

    /**
     * Start → follow → edit → end, every slice real: "Los geht's" writes the files and sends START, the service's state makes it
     * Navigate (NAVIGATING after the state), an edit ahead follows position → ahead (FOLLOW_CHANGED with the new line), the end is
     * stopped (STOP) and handled in the service's order: everything after the io hop runs with no tour recording – the end sends
     * NOTHING more, the mode is Plan, the camera moment is TOUR_END, the plan is cleared, the followed line gone.
     */
    @Test fun aWholeTourStartFollowEditEndOnTheRealSlicesInTheOrderOfTheRules() {
        create(); routeNow = routeFor(plan.points.toList())
        ui { session.start() }; stepIo()
        ui { session.startNavigation() }
        stepIo(); stepPc()
        serviceTakes()
        assertEquals(AppMode.NAVIGATE, session.mode)
        assertEquals(listOf("START", "NAVIGATING"), actions())
        // an edit ahead: delete d with a and b passed
        client.publish(recState(tour7, files.load().session, track = listOf(listOf(a, b))))
        ui { planEdit.removePoint(3) }
        routeNow = routeFor(plan.points.toList())
        ui { edits.followEditedPlan() }
        stepPc(); stepRouting()
        assertEquals(listOf("START", "NAVIGATING", "FOLLOW_CHANGED"), actions())
        assertEquals("the files hold the edited line at the command", "ce", ids(last.files.session!!.stops.map { it.point }))
        // the end: stop, then the service finishes (parts → finished → state null)
        val ended = ArrayList<String>()
        ui { end.start() }
        ui { end.endTour() }
        assertEquals("STOP after the decision", "STOP", actions().last())
        client.finish(tour7)
        ended += actions()
        stepIo()
        assertEquals("nothing is sent after STOP: no FOLLOW_CHANGED, no NAVIGATING (rule 9)", ended, actions())
        assertEquals(AppMode.PLAN, session.mode)
        assertNull("the followed line of the ended tour is gone", follow.followLine)
        assertEquals("-", filesNow())
        assertEquals("the plan was cleared the Neu planen way", 0, plan.points.size)
        val order = log.filter { it.startsWith("refreshTours") || it.startsWith("clearFollow") || it.startsWith("enterMode") || it.startsWith("clearPlan") }
            .map { it.substringBefore(" [").substringBefore(" ") + if ("rec=null" in it) " [rec=null]" else "" }
        assertEquals(listOf("refreshTours [rec=null]", "clearFollow [rec=null]", "enterMode(PLAN) [rec=null]", "clearPlan"), order)
        main.advance(500)
        assertTrue("the camera moment is the tour's end", problems.traced("map").any { it.startsWith("Plan shown whole (tour_end") || it.startsWith("Plan not shown whole (tour_end") })
        assertEquals("the saved box shows the tour", tour7, ctl.finishedTour?.id)
    }
}
