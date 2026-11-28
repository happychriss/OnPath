package com.onpath.app.ui.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellStore
import com.onpath.app.cells.MapCells
import com.onpath.app.cells.NetworkStatus
import com.onpath.app.data.AppSettings
import com.onpath.app.data.Geo
import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.PlanGpx
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.TourSplit
import com.onpath.app.rides.RideRecord
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.Hotel
import com.onpath.app.routing.NightMark
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.search.Place
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.ManualExecutor
import com.onpath.app.testkit.MemoryMultiDayPrefs
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.testkit.held
import com.onpath.app.time.TourTimes
import kotlinx.coroutines.launch
import com.onpath.app.ui.NoticeBoard
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.TourMapAreas
import com.onpath.app.ui.routes.PlannedRow
import com.onpath.app.ui.routes.RecentPlans
import com.onpath.app.ui.routes.RecentPlansHost
import com.onpath.app.ui.routes.RoutesState
import com.onpath.app.ui.routes.RoutesStore
import com.onpath.app.ui.routes.SaveHost
import com.onpath.app.ui.routes.SaveSlice
import com.onpath.app.ui.routes.ToursStore
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.msg_route_save_failed
import com.onpath.strings.multiday_day_of
import com.onpath.strings.multiday_own_tour
import com.onpath.strings.multiday_own_tour_activity
import com.onpath.strings.multiday_own_tour_level
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.CoroutineContext

/**
 * CORE FEATURE "multi-day tours on the phone" (docs/design/multi-day-tours.md §0, MD-0 … MD-11, [MultiDaySlice], phase 2 piece
 * 20) as flows of the REAL slice: a 3-day tour is stored in the real tables ([JdbcTours]) and one day of it is opened as the plan
 * as My routes' day sheet does it ([App.rideDay]); the plan is the real
 * [PlanDocument], its edits go through the real [PlanEditSlice], Save is the real [SaveSlice] ([RoutesState] / [RecentPlans]),
 * the activity and the pace are the real [PlanRouteSlice], the state is the real [MultiDayFlow] over memory prefs, the map
 * areas the real [MapCells] (only its network is a fake). Only the edges are fakes: the host (what the view model answers: mode,
 * route, a tour records, names, the maps, the backup / sync / list, the day sheet).
 *
 * Three held lanes tell the threads: main (inline, like `Main.immediate`, until a hop to another lane returns), io and default
 * ([Lanes]); every fake records WHICH lane it was called on AND the state it was called with, so an order of two writes is read
 * from the log, not from the test's own sequence. The followed day's state is read back from the prefs after a "restart" (a second
 * [App] over the same preferences, plan store and tables).
 *
 * Tests that use Compose state write it in `Snapshot.withMutableSnapshot` ([ui]): the slice's collectors are `snapshotFlow`s,
 * and a write applied to the global snapshot is what wakes them (no `OwnComposeSnapshot` here, as in `RoutesGoWaitTest`).
 *
 * What these flows do NOT prove: the view model's own members behind the host ([MultiDayWiringTest] pins the adapters), the
 * sheets' Compose code, My routes' real openTour (RoutesSlice; [App.open] does what it does to the plan). Not provable (named, not
 * claimed): the order of the two preference writes after a load (nothing reads between them); the order of the start's hook /
 * cells / collector lines (nothing runs between them).
 */
class MultiDaySliceTest {
    @get:Rule val problems = ProblemsRule()
    @get:Rule val tmp = TemporaryFolder()

    private val db = JdbcTours()
    @After fun close() = db.close()

    // ---------------------------------------------------------------- the world: a 3-day tour, 90 km north

    /** 1 km per line point, climbing 2 m per point (the stored numbers say ascent, never descent). */
    private val line = (0..90).map { GeoPoint(46.0 + it * 0.009, 11.0, 500.0 + it * 2) }
    private val A = GeoPoint(line[0].lat, line[0].lon)
    private val B = GeoPoint(line[20].lat, line[20].lon)
    private val C = GeoPoint(line[30].lat, line[30].lon)
    private val D = GeoPoint(line[60].lat, line[60].lon)
    private val E = GeoPoint(line[90].lat, line[90].lon)
    /** "Planen" on the way of day 2: a point between C and D, off the line. */
    private val X = GeoPoint(46.0 + 45 * 0.009, 11.01)
    private val pts = listOf(A, B, C, D, E)
    private val letters = mapOf(A to "A", B to "B", C to "C", D to "D", E to "E", X to "X")
    private fun ids(l: List<GeoPoint>?) = l?.joinToString("") { letters[it] ?: "?" } ?: "-"
    private val hotel = NightMark(Hotel("Gasthof Post"))
    private val days = MultiDay(3, nights = mapOf(2 to NightMark.OVERNIGHT, 3 to hotel), description = "Drei Tage")

    /** The tour as My routes holds it: line, stops (D is the hotel), pins (D), days. */
    private fun insertTour(uuid: String = "u1", name: String = "Drei Tage", pinned: Set<Int> = setOf(3),
                           activity: Activity = Activity.BIKE_TOURING, anchors: Map<Int, com.onpath.app.routing.Anchors> = emptyMap()): Long = db.insertTour(TourKind.PLANNED, name, activity.name, 2,
        segments = listOf(line.map { TrackPoint(it.lat, it.lon, it.ele) }),
        waypoints = pts.mapIndexed { i, p -> GpxWaypoint(p.lat, p.lon, if (i == 3) "Gasthof Post" else null) },
        distance = 90_000.0, uuid = uuid, pinned = pinned, days = days, anchors = anchors)
    private fun route(l: List<GeoPoint>) = PlannedRoute(listOf(PlannedLeg(l, false)), Geo.length(l), 0.0, 0.0, 3_600.0, null)
    private val cellsAll = listOf(CellId(543, 361), CellId(543, 362), CellId(544, 362))
    private val cellsDay = listOf(CellId(543, 362))

    private class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }

    private class MemoryRoutePrefs : PlanRoutePrefs {
        override var activity: Activity = Activity.BIKE_TOURING
        override var sac: Int = 2
    }

    /** What survives a "restart": the preferences, the plan's store, the tables. */
    private val prefs = MemoryMultiDayPrefs()
    private val planStore = MemoryPlanStore()
    private val routePrefs = MemoryRoutePrefs()

    // ---------------------------------------------------------------- the three lanes

    /** main runs inline while the code is on main (like `Main.immediate`), io and default hold what is dispatched to them until [settle]. */
    private class Lanes {
        var at = "main"
        val mainEx = ManualExecutor("main")
        val ioEx = ManualExecutor("io")
        val defEx = ManualExecutor("default")
        val main = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = at != "main"
            override fun dispatch(context: CoroutineContext, block: Runnable) = mainEx.execute(block)
        }
        val io = held(ioEx)
        val default = held(defEx)
        private fun run(lane: String, ex: ManualExecutor) { val was = at; at = lane; try { ex.runAll() } finally { at = was } }
        fun settle() {
            var n = 0
            while (true) {
                Snapshot.sendApplyNotifications()
                if (mainEx.size + ioEx.size + defEx.size == 0) break
                run("main", mainEx); run("io", ioEx); run("default", defEx)
                check(++n < 100) { "settle: tasks keep coming" }
            }
        }
        fun <T> on(lane: String, f: () -> T): T { val was = at; at = lane; try { return f() } finally { at = was } }
    }

    /** A routing thread nothing ever runs on (the multi-day slice routes nothing). */
    private fun exec(): kotlinx.coroutines.ExecutorCoroutineDispatcher = object : java.util.concurrent.AbstractExecutorService() {
        override fun execute(command: Runnable) = error("nothing routes here")
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
    }.asCoroutineDispatcher()

    private fun <T> ui(f: () -> T): T = Snapshot.withMutableSnapshot { f() }

    private val net = object : NetworkStatus {
        var wifi = true
        override fun connected() = true
        override fun unmetered() = wifi
    }

    // ---------------------------------------------------------------- the app: the real slices around the multi-day slice

    private inner class App(val started: Boolean = true) {
        val lanes = Lanes()
        val scope = CoroutineScope(SupervisorJob() + lanes.main)
        val log = ArrayList<String>()
        fun at() = lanes.at
        fun settle() = lanes.settle()

        // the edges' facts
        var routeNow by mutableStateOf<PlannedRoute?>(null)
        var modeNow = AppMode.PLAN
        var touringNow = false
        val names = HashMap<GeoPoint, String>()
        /** The trips whose day sheet My routes was asked to show (MultiDayHost.showDays). */
        val shown = ArrayList<Long>()
        var saveProblem: String? = null
        var wholeMissing = emptyList<CellId>()
        var dayMissing = emptyList<CellId>()
        var mapsFail = false
        var tourMapsCalls = 0
        var cellsFail = false
        val loads = ArrayList<String>()
        /** What the plan held when the edit asked for the route run (the real recalc reads the pins at once). */
        val atRecalc = ArrayList<String>()
        /** The store's failures, by call name: the NEXT call of that name throws once. */
        val failOn = HashSet<String>()
        var replaced = 0

        val cells = MapCells(CellStore(tmp.newFolder()), null, net, CoroutineScope(SupervisorJob()), "0.8.0", regionPackages = false)

        // ---- the plan, the activity, the edits (real)
        val plan = PlanDocument(planStore)
        val planRoute = PlanRouteSlice(
            prefs = routePrefs,
            router = object : RouterPort {
                override fun create(): RoutingEngine = error("the multi-day slice routes nothing")
                override val isRealRouter: Boolean get() = false
                override fun fingerprint() = "fp"
                override val legsDir: java.io.File get() = tmp.root
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
                override val routing: Boolean get() = false
                override val longRouteAsk: Double? get() = null
                override val mode: AppMode get() = modeNow
                override val touring: Boolean get() = touringNow
                override fun recalc() = Unit
                override fun routeEvent(e: RouteRun.Event) = Unit
                override fun refreshTours() = Unit
                override fun clearShownNote() = Unit
                override fun showWhole(points: List<GeoPoint>) = Unit
            },
            threads = RoutingThreads(exec(), exec(), exec(), exec()),
        )
        val planRun = PlanRunSlice(planRoute.runCauses, object : PlanRunHost {
            override fun points() = plan.points.toList()
            override fun clearRoute() = Unit
            override fun noCorridor() = Unit
            override fun loadDeferredMaps(before: List<GeoPoint>) = Unit
            override fun recalc() = Unit
            override fun cancelRun(why: NotRouted) = planEdit.cancelRun(why)
        })
        val planEdit: PlanEditSlice = PlanEditSlice(plan, planRun, planRoute.runCauses, object : PlanEditHost {
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
            override fun keepAsDraft(why: String) = save.keepAsDraft(why)
            override fun routeLine(): List<GeoPoint>? = routeNow?.points
            override fun recalc() { atRecalc += "points=${ids(plan.points)} pinned=${plan.pinned.mapNotNull { letters[it] }.sorted().joinToString("")}" }
            override fun restoreActivity(c: PlanStatus.Cause.ActivityChange) = Unit
            override fun planChanged() = Unit
            override fun myPosition(): GeoPoint? = null
            override fun touring(): Boolean = touringNow
            override fun following(): Boolean = false
            override fun followIsTrack(): Boolean = false
            override fun followsLine(line: List<GeoPoint>): Boolean = false
            override fun followFromPlan(): Boolean = false
            override fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>): TourSplit.Outcome? = null
            override fun sayNoDestination() = Unit
            override fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean) = Unit
            override fun endGuidanceIfPlanEmpty() = Unit
            override fun clearFollow() = Unit
            override fun guidanceEndedByEmptyPlan() = Unit
            override fun stopName(p: GeoPoint): String? = names[p]
            override suspend fun pointNames(): com.onpath.app.ui.plan.PointNames = PointNames { null }
            override var searchMark: Place? = null
        }, scope, lanes.io)

        // ---- the store: the real tables behind the calls, each call recorded with its lane
        private fun gate(name: String) {
            log += "store.$name@${lanes.at}"
            if (failOn.remove(name)) throw java.sql.SQLException("database or disk is full ($name)")
        }
        val store = object : ToursStore {
            override fun getTour(id: Long): Tour? { gate("getTour"); return db.getTour(id) }
            override fun loadSegments(id: Long): List<List<TrackPoint>> { gate("loadSegments"); return db.loadSegments(id) }
            override fun insertPlanned(name: String, row: PlannedRow): Long = db.insertTour(TourKind.PLANNED, name, row.activity, row.sac, row.segments, row.stops,
                distance = row.distanceM, ascent = row.ascentM, descent = row.descentM, duration = row.durationS, pinned = row.pins,
                difficulty = row.difficulty, days = row.days, anchors = row.anchors)
            override fun replacePlanned(id: Long, row: PlannedRow): Boolean {
                gate("replacePlanned"); replaced++
                return db.replacePlanned(id, row.activity, row.sac, row.distanceM, row.ascentM, row.descentM, row.durationS, row.segments, row.stops,
                    row.pins, difficulty = row.difficulty, days = row.days, anchors = row.anchors)
            }
            override fun rename(id: Long, name: String) { gate("rename"); db.rename(id, name) }
            override fun setFavourite(id: Long, favourite: Boolean) = db.setFavourite(id, favourite)
            override fun planPoints(id: Long): List<GpxWaypoint>? = error("not used")
            override fun tourTimes(): Map<Long, TourTimes> = error("not used")
            override fun listTours(): List<Tour> = error("not used")
            override fun getTourPlan(id: Long): com.onpath.app.data.TourPlan? = error("not used")
        }

        // ---- the host (what the view model answers)
        val host = object : MultiDayHost {
            override val mode: AppMode get() = modeNow
            override val route: PlannedRoute? get() = routeNow
            override val touring: Boolean get() = touringNow
            override val cells: MapCells get() { if (cellsFail) throw IllegalStateException("cells not ready"); return this@App.cells }
            override fun placeNameOf(p: GeoPoint): String? = names[p]
            override fun showDays(id: Long) { log += "days $id@${lanes.at}"; shown += id }
            override fun reloadTourList() { log += "refresh@${lanes.at}"; routes.show(routes.load()) }
            override fun showSaveProblem(text: String) { saveProblem = text; log += "problem@${lanes.at}" }
            override fun tourChanged(id: Long) { log += "backup $id@${lanes.at} stored=${ids(db.getTour(id)?.waypoints?.map { GeoPoint(it.lat, it.lon) })}" }
            override fun syncChanged(why: String) { log += "sync: $why" }
            override fun tourMaps(line: List<GeoPoint>): TourMapAreas? {
                tourMapsCalls++; log += "tourMaps(${line.size})@${lanes.at}"
                if (mapsFail) return null
                return if (line.size == this@MultiDaySliceTest.line.size) TourMapAreas(cellsAll, wholeMissing, wholeMissing.size * 10_000_000L)
                else TourMapAreas(cellsDay, dayMissing, dayMissing.size * 10_000_000L)
            }
            override fun loadTourMaps(want: List<CellId>, line: List<GeoPoint>) {
                loads += "${want.joinToString { it.id }} line=${line.size}"
                log += "loadTourMaps@${lanes.at}"
            }
        }

        // ---- Save: the real slice; the multi-day hooks of its host are the slice's own
        val routes = RoutesState(object : RoutesStore {
            override fun listTours() = db.listTours()
            override fun listDeleted() = db.listDeleted()
            override fun setDraft(id: Long, draft: Boolean) = db.setDraft(id, draft)
            override fun setDeleted(id: Long, deletedMs: Long?) = db.setDeleted(id, deletedMs)
            override fun loadSegments(id: Long) = db.loadSegments(id)
            override fun deleteForGood(id: Long) = db.deleteTour(id)
            override fun rename(id: Long, name: String) = db.rename(id, name)
        }, clock = { 5_000L })
        val board = NoticeBoard(routesOpen = { false }, settingsOpen = { false }, mapShown = { true })
        val recent = RecentPlans(routes, object : RoutesStore {
            override fun listTours() = db.listTours()
            override fun listDeleted() = db.listDeleted()
            override fun setDraft(id: Long, draft: Boolean) = db.setDraft(id, draft)
            override fun setDeleted(id: Long, deletedMs: Long?) = db.setDeleted(id, deletedMs)
            override fun loadSegments(id: Long) = db.loadSegments(id)
            override fun deleteForGood(id: Long) = db.deleteTour(id)
            override fun rename(id: Long, name: String) = db.rename(id, name)
        }, object : RecentPlansHost {
            var created = 1_000L
            override fun updateDraft(id: Long, row: RecentPlans.DraftRow): Boolean =
                db.replacePlanned(id, row.activity, row.sac, row.distanceM, row.ascentM, row.descentM, row.durationS, row.segments, row.waypoints,
                    row.pinned, difficulty = row.difficulty, days = row.days, clearDays = row.days == null)
            override fun insertDraft(row: RecentPlans.DraftRow): Long =
                db.insertTour(TourKind.PLANNED, row.name, row.activity, row.sac, row.segments, row.waypoints, distance = row.distanceM,
                    createdMs = created++, draft = true)
            override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) = Unit
            override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String? = null
            override fun tourChanged(id: Long) { log += "recent backup $id" }
            override fun purged(uuid: String) = Unit
            override fun syncChanged(why: String) { log += "recent sync: $why" }
            override fun refreshTours() { log += "recent refresh" }
            override fun problem(text: String) = Unit
            override var trimmed = false
        }, scope, lanes.io)
        val saveHost = object : SaveHost {
            override val route: PlannedRoute? get() = routeNow
            override val activity: Activity get() = planRoute.activity
            override val sac: Int get() = planRoute.sac
            override fun stops(points: List<GeoPoint>) = PlanGpx.stops(points) { names[points[it]] }
            override fun difficulty(route: PlannedRoute?, activity: Activity): String? = null
            override fun days(points: List<GeoPoint>) = multiDay.daysFor(points)
            override fun overwriteWhole(name: String, then: (() -> Unit)?) = multiDay.overwriteWhole(name, then)
            override fun leavesTrip() = multiDay.leavesTrip()
            override fun leavesTripNote() = multiDay.leavesTripNote() ?: super.leavesTripNote()
            override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String? = null
            override fun datedName() = "Route 19 Nov"
            override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) = Unit
            override fun forgetTimes(id: Long) = Unit
            override fun copyTimes(from: Long, to: Long, distanceM: Double) = Unit
            override fun tourChanged(id: Long) { log += "save backup $id" }
            override fun syncChanged(why: String) { log += "save sync: $why" }
            override fun refreshTours() { log += "save refresh"; routes.show(routes.load()) }
            override fun saved(name: String?, id: Long, points: List<GeoPoint>?) = plan.setSaved(name, id, points)
            override fun shownRenamed(id: Long, name: String) = Unit
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) = board.say(text, kind, place)
        }
        val save = SaveSlice(plan, routes, recent, store, saveHost, scope, lanes.io)

        // ---- the slice under test
        val multiDay: MultiDaySlice = MultiDaySlice(
            plan, planRoute, store, prefs, host,
            setSaved = { name, id, points ->
                log += "saved $name $id [points=${ids(plan.points)}]"
                plan.setSaved(name, id, points)
            },
            scope = scope, io = lanes.io, default = lanes.default,
        )

        init {
            routes.show(routes.load())
            if (started) multiDay.start()
            settle()
        }

        /** My routes' open of a saved route: its points become the plan, linked, its stops named, the multi-day state told. */
        fun open(t: Tour) {
            val p = t.waypoints.map { GeoPoint(it.lat, it.lon) }
            t.waypoints.forEach { w -> w.name?.let { names[GeoPoint(w.lat, w.lon)] = it } }
            routes.show(routes.load())
            plan.arriving(p, com.onpath.app.routing.AnchorSet.of(t.anchors, planRoute.activityOrNull(t.activity), t.sac)) // as the view model's openTour does
            plan.replace(p); plan.pin(t.pinned.map { p[it] }); plan.setSaved(t.name, t.id, p)
            multiDay.opened(t, p)
        }

        /** The whole trip is the plan, as an older version kept it (no day picked): only its days are kept safe for Save. */
        fun openOverview(id: Long = tourId) {
            ui { open(db.getTour(id)!!) }
            ui { routeNow = route(line) }
            settle()
        }

        /** My routes' day sheet: day [n] of trip [id] worked out along its stored line (null = not). */
        fun prepare(id: Long = tourId, n: Int = 2): MultiDaySlice.DayStart? {
            var s: MultiDaySlice.DayStart? = null
            val t = db.getTour(id)!!
            val l = db.loadSegments(id).flatten().map { GeoPoint(it.lat, it.lon, it.ele) }
            scope.launch { s = multiDay.prepareDay(t, l, n) }
            settle()
            return s
        }

        /** Day [n] of trip [id] is the plan, as My routes' day sheet loads it: the day opened as a route (openTour), then followed. */
        fun rideDay(id: Long = tourId, n: Int = 2, dayRoute: List<GeoPoint>? = null) {
            val s = prepare(id, n)!!
            ui { open(s.standIn) }
            ui { multiDay.followDay(s) }
            dayRoute?.let { ui { routeNow = route(it) } }
            settle()
        }

        /** The followed day 2 is the plan (the day's route is the stretch of the line from C to D). */
        fun rideDay2() = rideDay(dayRoute = dayLine)

        fun planIds() = ids(plan.points)
        fun stored(id: Long = tourId) = ids(db.getTour(id)?.waypoints?.map { GeoPoint(it.lat, it.lon) })
        fun storedLine(id: Long = tourId) = db.loadSegments(id).flatten().map { GeoPoint(it.lat, it.lon, it.ele) }
        fun planned() = db.listTours().filter { it.kind == TourKind.PLANNED && !it.draft }.sortedBy { it.id }
        fun drafts() = db.listTours().filter { it.draft }
    }

    /** The day's route as the router would find it: the line from C to D, the new point X on the way. */
    private val dayLine = line.subList(30, 45) + X + line.subList(45, 61)
    private val tourId by lazy { insertTour() }

    // ---------------------------------------------------------------- 1. a day becomes the plan

    /**
     * MD-2 (owner 2026-11-28): a tap on day 2 in My routes' day sheet puts exactly that day into the plan – never the whole trip
     * first. The day's stand-in route is the trip's (its id and name: Save writes the trip, MD-3) with the day's points, names,
     * pins; no days, no uuid (no trip of its own); followed once it is the plan, kept for a restart.
     */
    @Test fun aDayFromTheDaySheetIsThePlanDirectlyAndTiedToItsTrip() {
        val app = App()
        val s = app.prepare()!!
        assertEquals("only the stored tour and its line were read, nothing written", emptyList<String>(), app.log.filter { it.startsWith("store.") })
        assertEquals("the day's points", listOf(C, D), s.part.points)
        val stand = s.standIn
        assertEquals(tourId, stand.id); assertEquals("Drei Tage", stand.name)
        assertEquals(listOf(null, "Gasthof Post"), stand.waypoints.map { it.name })
        assertEquals("D is pinned", setOf(1), stand.pinned)
        assertNull("a day is no trip", stand.days); assertEquals("", stand.uuid)
        assertEquals("the day's stretch of the trip's line", line.subList(30, 61), s.dayLine)
        assertEquals("nothing on the map yet", "", app.planIds())
        ui { app.open(stand) }
        assertFalse("opened, not yet followed", app.multiDay.flow.following)
        ui { app.multiDay.followDay(s) }; app.settle()
        assertEquals("CD", app.planIds())
        assertEquals(tourId, app.plan.linkId); assertEquals(listOf(C, D), app.plan.savedPoints)
        assertTrue(app.multiDay.flow.following); assertFalse(app.multiDay.overview)
        assertEquals("the trip's night marks by value", hotel, app.multiDay.flow.nightOf(D))
        assertEquals("ABCDE", ids(app.multiDay.flow.day!!.whole.points))
        assertEquals(Txt.s(Res.string.multiday_day_of, "2", "3"), app.multiDay.dayLabel())
        assertEquals(com.onpath.app.ui.DayHeadUi(Txt.s(Res.string.multiday_day_of, "2", "3"), true, false), app.multiDay.dayHeadUi())
        assertEquals("u1" to 2, app.multiDay.followedDay())
        // kept: a restart follows it
        val again = App()
        assertTrue(again.multiDay.flow.following); assertEquals("CD", again.planIds())
        assertEquals(2, again.multiDay.flow.day!!.part.first)
        assertTrue("a followed day opens no day sheet at the start", again.shown.isEmpty())
    }

    @Test fun aDayThatCannotBeWorkedOutGivesNothingAndAnotherPlanMeanwhileIsNotFollowed() {
        val app = App()
        assertNull("no such day", app.prepare(n = 4))
        val plain = db.insertTour(TourKind.PLANNED, "Normal", "HIKE", 2, segments = listOf(line.map { TrackPoint(it.lat, it.lon, it.ele) }),
            waypoints = listOf(GpxWaypoint(A.lat, A.lon, null), GpxWaypoint(E.lat, E.lon, null)))
        assertNull("a route without days", app.prepare(plain))
        val s = app.prepare()!!
        ui { app.plan.replace(listOf(A, B)); app.plan.setSaved("Other", 99L, listOf(A, B)) }
        ui { app.multiDay.followDay(s) }; app.settle()
        assertFalse(app.multiDay.flow.following)
        assertTrue(problems.said("plan", Problems.Level.WARN).any { it.contains("the plan is another one now – not followed") })
    }

    /** MD-11 (owner 2026-11-28): opening a day loads the maps of ALL days by itself – no question – and keeps them asked after a restart. */
    @Test fun openingADayLoadsTheMapsOfAllDaysWithoutAQuestion() {
        val app = App(); app.wholeMissing = cellsAll
        app.rideDay2()
        assertEquals("the whole trip's line, worked out on default, loaded on main", listOf("${cellsAll.joinToString { it.id }} line=${line.size}"), app.loads)
        assertTrue(app.log.contains("tourMaps(${line.size})@default")); assertTrue(app.log.contains("loadTourMaps@main"))
        assertEquals(cellsAll.map { it.id }.toSet(), prefs.multiDayMapCells)
        // all there: nothing loads
        val there = App(); there.rideDay2()
        assertTrue(there.loads.isEmpty())
    }

    @Test fun aDayThatEndsBetweenPlanPointsIsItsTwoAddedEnds() {
        // a tour with two plan points only, three days of about 30 km: every day end is a proposal on the line, none is a plan point
        val id = db.insertTour(TourKind.PLANNED, "Ohne Nacht", Activity.BIKE_TOURING.name, 2,
            segments = listOf(line.map { TrackPoint(it.lat, it.lon, it.ele) }), waypoints = listOf(GpxWaypoint(A.lat, A.lon, null), GpxWaypoint(E.lat, E.lon, null)),
            distance = 90_000.0, uuid = "u3", days = MultiDay(3))
        val app = App(); app.rideDay(id)
        val part = app.multiDay.flow.day!!.part
        assertNotNull(part.startAdded); assertNotNull(part.endAdded)
        assertEquals("the day is its two added ends", listOf(part.startAdded!!, part.endAdded!!), app.plan.points.toList())
    }

    // ---------------------------------------------------------------- 2. an edit of the day is written back into the whole tour

    @Test fun anEditOfTheDayIsWrittenBackIntoTheWholeTourAndKeptForARestart() {
        val app = App(); app.rideDay2()
        assertEquals("CD", app.planIds())
        assertEquals("the whole tour before the edit", "ABCDE", ids(app.multiDay.flow.day!!.whole.points))
        // "Planen" on the way: a point between – the real edit
        ui { app.planEdit.change { add(1, X) } }
        app.settle()
        assertEquals("CXD", app.planIds())
        val c = app.multiDay.flow.day!!
        assertEquals("the whole tour has it, every other point where it was", "ABCXDE", ids(c.whole.points))
        assertEquals("the night marks went with their points (by value)", mapOf(2 to NightMark.OVERNIGHT, 4 to hotel), c.whole.nights)
        assertEquals(3, c.whole.days)
        assertEquals("the day's points are the plan's now", listOf(C, X, D), c.part.points)
        assertEquals("the stored tour is not touched by an edit (only Save writes it)", "ABCDE", app.stored())
        // kept: the app restarts
        val again = App()
        assertTrue(again.multiDay.flow.following)
        assertEquals("ABCXDE", ids(again.multiDay.flow.day!!.whole.points))
        // Undo is an edit too: the whole tour is as before
        ui { app.planEdit.undoStep() }
        app.settle()
        assertEquals("CD", app.planIds())
        assertEquals("ABCDE", ids(app.multiDay.flow.day!!.whole.points))
        assertEquals(mapOf(2 to NightMark.OVERNIGHT, 3 to hotel), app.multiDay.flow.day!!.whole.nights)
    }

    /**
     * MD-5 (owner 2026-11-28): the recording stays tied to its day while its start and goal stay – a point added, moved or
     * deleted in between keeps it (followedDay is what TourFollow stamps on the tour's plan copy; TourFollowFlowTest: null
     * there = no planDay / planUuid). Replaces the review fix of 2026-11-27 that dropped the day on any edit.
     */
    @Test fun aDayStaysTiedToTheRecordingThroughEditsBetweenItsStartAndGoal() {
        val app = App(); app.rideDay2()
        assertEquals("the day as picked", "u1" to 2, app.multiDay.followedDay())
        // the router's snap moves a point onto its way: no edit
        val moved = GeoPoint(C.lat + 0.00001, C.lon)
        // as PlanRecalcState.snapToRoute does: the points moved, then the history and the multi-day state told
        ui { app.plan.moveSnapped(listOf(C, D), listOf(moved, D), null); app.plan.snappedHistory(mapOf(C to moved)) }; app.settle()
        assertEquals("CD".length, app.plan.points.size)
        assertEquals("a snap keeps the day", "u1" to 2, app.multiDay.followedDay())
        // a point inserted between start and goal: still that day
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        assertEquals("a point between keeps the day", "u1" to 2, app.multiDay.followedDay())
        assertTrue(app.multiDay.flow.following)
        // kept so across a restart
        assertEquals("u1" to 2, App().multiDay.followedDay())
        // deleted again: still the day
        ui { app.planEdit.change { removeAt(1) } }; app.settle()
        assertEquals("u1" to 2, app.multiDay.followedDay())
    }

    @Test fun aMovedStartOrGoalDropsTheDayAStepWithin200mDoesNot() {
        val app = App(); app.rideDay2()
        // the goal nudged about 100 m: the same day (DayPick.SAME_END_M)
        val near = GeoPoint(D.lat + 0.0009, D.lon)
        ui { app.planEdit.change { set(lastIndex, near) } }; app.settle()
        assertEquals("a goal within 200 m stays the day", "u1" to 2, app.multiDay.followedDay())
        // the goal moved to X (km away): no longer the day
        ui { app.planEdit.change { set(lastIndex, X) } }; app.settle()
        assertNull("a moved goal drops the day", app.multiDay.followedDay())
        val start = App(); start.rideDay2()
        ui { start.planEdit.change { set(0, X) } }; start.settle()
        assertNull("a moved start drops the day", start.multiDay.followedDay())
    }

    @Test fun theWriteBackWatchesOnlyAFollowedDayAndNeverFailsLoudly() {
        val app = App(); app.openOverview()
        // the overview: an edit of the whole tour is no day's edit
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        assertEquals("the whole tour is edited, no day exists to write into", "AXBCDE", app.planIds())
        // a day: a change that leaves fewer than two points is not written back
        val day = App(); day.rideDay2()
        ui { day.planEdit.change { removeAt(1) } }; day.settle()
        assertEquals("C", day.planIds())
        assertEquals("one point is no plan: the whole tour keeps its last state", "ABCDE", ids(day.multiDay.flow.day!!.whole.points))
    }

    @Test fun thePlansSavedLinkReachesTheNightMarksOnlyThroughTheStartedSlice() {
        val on = App(); on.openOverview()
        assertNotNull(on.multiDay.flow.tour)
        ui { on.plan.setSaved("Other", 99L, pts) }
        assertNull("another route leaves the tour", on.multiDay.flow.tour)
        assertNull("also in the prefs", prefs.planMultiDay)
        // not started: the hooks are not there (they are set by start(), the place the view model called it)
        val off = App(started = false)
        ui { off.open(db.getTour(tourId)!!) }
        ui { off.plan.setSaved("Other", 99L, pts) }
        assertNotNull(off.multiDay.flow.tour)
    }

    @Test fun theRoutersSnapMovesTheNightMarksWithTheirPoints() {
        val app = App(); app.openOverview()
        val moved = GeoPoint(C.lat + 0.00001, C.lon)
        assertEquals(NightMark.OVERNIGHT, app.multiDay.flow.nightOf(C))
        ui { app.plan.snappedHistory(mapOf(C to moved)) }
        assertEquals(NightMark.OVERNIGHT, app.multiDay.flow.nightOf(moved))
        assertNull(app.multiDay.flow.nightOf(C))
    }

    // ---------------------------------------------------------------- 3. Save while a day is the plan writes the whole tour

    private fun App.saveAs(name: String) { ui { save.request() }; ui { save.confirm(name) }; settle() }

    @Test fun saveWhileADayIsThePlanWritesTheWholeTourWithTheDaysChange() {
        val app = App(); app.rideDay2()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        app.log.clear()
        app.saveAs("Drei Tage")
        // the whole tour, with the day's change, over the saved tour
        assertEquals("ABCXDE", app.stored())
        val t = db.getTour(tourId)!!
        assertEquals("one route, still", listOf(tourId), app.planned().map { it.id })
        assertEquals(setOf(4), t.pinned); assertEquals("Gasthof Post", t.waypoints[4].name)
        assertEquals("its days with the marks on the new indices", MultiDay(3, nights = mapOf(2 to NightMark.OVERNIGHT, 4 to hotel), description = "Drei Tage"), t.days)
        assertEquals(Activity.BIKE_TOURING.name, t.activity); assertEquals(2, t.sac)
        // the stored line: the old one with the day's new line spliced in
        val l = app.storedLine()
        assertEquals(A, l.first().let { GeoPoint(it.lat, it.lon) }); assertEquals(E, GeoPoint(l.last().lat, l.last().lon))
        assertTrue("the day's new point is on the stored line", X in l)
        assertEquals("the numbers are the new line's", Geo.length(l), t.distanceM, 0.01)
        // the order of what the user's Save does: the line is read, the tour written, then backup (seeing the new row), sync, list
        assertEquals(listOf(
            "store.loadSegments@io", "store.replacePlanned@io",
            "backup $tourId@main stored=ABCXDE", "sync: multi-day tour saved", "refresh@main",
        ), app.log.filter { it.startsWith("store.") || it.startsWith("backup") || it.startsWith("sync") || it.startsWith("refresh") })
        // the plan is the day, saved as the tour: nothing unsaved, the saved link on the day's points
        assertEquals("CXD", app.planIds())
        assertFalse(app.plan.unsaved); assertEquals(listOf(C, X, D), app.plan.savedPoints)
        assertEquals(tourId, app.plan.savedId)
        assertNull(app.saveProblem)
        // the multi-day state knows the tour as saved: a later Save compares with the new stored points
        assertEquals("ABCXDE", ids(app.multiDay.flow.day!!.wholeOpened))
        assertTrue("the day is still followed", app.multiDay.flow.following)
    }

    @Test fun theTourStartsSaveWritesThenAfterEverything() {
        val app = App(); app.rideDay2()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        val order = ArrayList<String>()
        app.log.clear()
        ui { assertTrue(app.multiDay.overwriteWhole("Drei Tage") { order += "then ${app.log.last()} stored=${app.stored()}" }) }
        assertTrue("the write runs on io, nothing is done before", order.isEmpty() && app.replaced == 0)
        app.settle()
        assertEquals(listOf("then refresh@main stored=ABCXDE"), order)
    }

    @Test fun aRenameIsWrittenAfterTheTourAndOnlyWhenTheNameChanged() {
        val app = App(); app.rideDay2(); app.log.clear()
        app.saveAs("Drei Tage")
        assertEquals("the same name: no rename", listOf("store.loadSegments@io", "store.replacePlanned@io"), app.log.filter { it.startsWith("store.") })
        ui { app.planEdit.change { add(1, X) } }; app.settle(); app.log.clear()
        app.saveAs("Drei Tage am Meer")
        assertEquals(listOf("store.loadSegments@io", "store.replacePlanned@io", "store.rename@io"), app.log.filter { it.startsWith("store.") })
        assertEquals("Drei Tage am Meer", db.getTour(tourId)!!.name)
        assertEquals("the multi-day state knows its new name", "Drei Tage am Meer", app.multiDay.flow.day!!.tour.name)
        assertEquals("ABCXDE", app.stored())
    }

    @Test fun aSecondTapOnSaveWhileTheFirstRunsMakesNoSecondWrite() {
        val app = App(); app.rideDay2()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        assertTrue(ui { app.multiDay.overwriteWhole("Drei Tage", null) })
        assertTrue("the second is taken care of, too", ui { app.multiDay.overwriteWhole("Drei Tage", null) })
        app.settle()
        assertEquals("ONE write", 1, app.replaced)
        // finished: Save works again
        ui { app.planEdit.change { add(2, GeoPoint(X.lat + 0.001, X.lon)) } }; app.settle()
        assertTrue(ui { app.multiDay.overwriteWhole("Drei Tage", null) }); app.settle()
        assertEquals(2, app.replaced)
    }

    @Test fun aFailedWriteSaysSoKeepsTheDayAndLetsTheUserTryAgain() {
        val app = App(); app.rideDay2()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        app.failOn += "replacePlanned"; app.log.clear()
        app.saveAs("Drei Tage")
        assertEquals("the dialog that reports it, until OK", Txt.s(Res.string.msg_route_save_failed), app.saveProblem)
        assertEquals("nothing is stored", "ABCDE", app.stored())
        assertTrue("no backup, no sync, no list, no saved link", app.log.none { it.startsWith("backup") || it.startsWith("sync") || it.startsWith("refresh") })
        assertTrue(problems.said("db", Problems.Level.ERROR).any { it.contains("Saving the multi-day tour with the day's change failed") })
        assertTrue("the plan is still unsaved", app.plan.unsaved)
        // again: the lock was released
        app.saveProblem = null
        app.saveAs("Drei Tage")
        assertEquals("ABCXDE", app.stored()); assertNull(app.saveProblem)
    }

    @Test fun aTourThatIsGoneMeanwhileIsReportedAsAFailedSaveToo() {
        val app = App(); app.rideDay2()
        db.deleteTour(tourId)
        app.log.clear()
        app.saveAs("Drei Tage")
        assertEquals(Txt.s(Res.string.msg_route_save_failed), app.saveProblem)
        assertTrue(app.log.none { it.startsWith("backup") || it.startsWith("sync") })
    }

    @Test fun withoutAFollowedDaySaveIsTheOrdinaryOneAndWritesTheNormalPlan() {
        val app = App(); app.openOverview()
        assertFalse(app.multiDay.flow.following)
        app.replaced = 0; app.log.clear()
        ui { assertFalse("no day: the view model saves as always", app.multiDay.overwriteWhole("Drei Tage", null)) }
        assertEquals("nothing written by the multi-day slice", 0, app.replaced)
        assertTrue(app.log.none { it.startsWith("store.") })
        // the ordinary save writes the whole tour's plan (an older version's whole-trip state: its days with it)
        app.saveAs("Drei Tage")
        assertEquals("ABCDE", app.stored())
        assertEquals(days, db.getTour(tourId)!!.days)
    }

    @Test fun theNewestPointsAreWrittenBackBeforeSaveWritesEvenWhenNoCollectorSawThem() {
        val app = App(started = false)
        app.rideDay()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        assertEquals("no collector runs: the flow has not seen the edit", "ABCDE", ids(app.multiDay.flow.day!!.whole.points))
        ui { app.multiDay.overwriteWhole("Drei Tage", null) }; app.settle()
        assertEquals("Save wrote the edit into the whole tour first", "ABCXDE", app.stored())
    }

    @Test fun theDaysRouteIsTheDaysLineAndWithoutOneTheDaysPointsStandIn() {
        val withRoute = App(); withRoute.rideDay2()
        ui { withRoute.planEdit.change { add(1, X) } }; withRoute.settle()
        withRoute.saveAs("Drei Tage")
        val t = db.getTour(tourId)!!
        val l = withRoute.storedLine()
        val (up, down) = Geo.routeClimbAlong(l.map { it.ele }, com.onpath.app.routing.Planner.ELE_FILTER_M)
        assertTrue("ascent", t.ascentM > 100.0); assertEquals(up.last(), t.ascentM, 0.0)
        assertEquals("descent", 0.0, t.descentM, 0.0); assertEquals(down.last(), t.descentM, 0.0)
        assertTrue("the time of the new line", t.durationS > 0.0)
        assertTrue("the day's new line is in: its point beside the old line, its point on it", X in l && dayLine[3] in l)
    }

    @Test fun withoutADayRouteYetTheDaysPointsAreSplicedIn() {
        val app = App(); app.rideDay2()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        ui { app.routeNow = null }; app.settle()
        app.saveAs("Drei Tage")
        val l = app.storedLine()
        assertTrue("the day's points are the line between the day's ends", X in l)
        assertFalse("the day's route points are not (there was none)", dayLine[3] in l)
    }

    // ---------------------------------------------------------------- 4. a lost day state: the plan is unlinked, the day sheet opens

    /** A restart after the day's state was damaged: the plan is the day's points, still linked to the tour. */
    private fun lostDayApp(): Pair<App, List<GeoPoint>> {
        val first = App(); first.rideDay2()
        prefs.planDayFollow = "{\"tour\": broken"
        return App(started = false) to listOf(C, D)
    }

    @Test fun aLostDayStateUnlinksThePlanAndOpensTheDaySheetNeverADayOverTheTour() {
        val (first, day) = lostDayApp()
        assertEquals(day, first.plan.points.toList())
        assertEquals("the plan is the day's, linked to the tour", tourId, first.plan.linkId)
        first.multiDay.start(); first.settle()
        assertTrue("the loss is known", first.multiDay.flow.dayLost)
        // read on io, then on main: the plan unlinked (its points stay), the trip's day sheet asked for – never the whole trip as the plan (MD-0)
        assertEquals(listOf("store.getTour@io", "saved null -1 [points=CD]", "days $tourId@main"),
            first.log.filter { it.startsWith("store.") || it.startsWith("days") || it.startsWith("saved") })
        assertEquals("CD", first.planIds()); assertEquals(-1L, first.plan.linkId)
        assertTrue(problems.said("plan", Problems.Level.WARN).any { it.contains("the plan is unlinked from \"Drei Tage\"") })
        // a Save of what is on the map now makes a route of its own, never a day (two points) over the trip (five)
        first.saveAs("Mein Tag")
        assertEquals("ABCDE", first.stored())
        assertEquals("the trip and the day's own route", 2, first.planned().size)
    }

    @Test fun aLostDayStateDuringATourKeepsThePlanAndSaveWritesACopyNotTheTour() {
        val (app, day) = lostDayApp()
        app.touringNow = true
        app.multiDay.start(); app.settle()
        assertTrue("the tour was read, then nothing opened", app.log.contains("store.getTour@io") && app.shown.isEmpty())
        assertEquals(day, app.plan.points.toList())
        assertTrue(problems.said("plan", Problems.Level.WARN).any { it.contains("a tour records – plan kept, Save writes a copy") })
        app.touringNow = false
        app.saveAs("Drei Tage")
        assertEquals("the tour itself is untouched: the day was never saved over it", "ABCDE", app.stored())
        assertEquals("a copy of the day", 2, app.planned().size)
    }

    @Test fun aMissingDayStateWithADaysPointsReloadsWhileTheTourItselfAndOtherRoutesDoNot() {
        // the state is simply missing (nothing was written): the plan's saved points are the day's, not the tour's
        val first = App(); first.rideDay2()
        prefs.planDayFollow = null
        val missing = App(); missing.settle()
        assertEquals("CD", missing.planIds()); assertEquals(listOf(tourId), missing.shown); assertEquals(-1L, missing.plan.linkId)
        // the trip's own points kept as the plan by an older version: not unlinked (Save keeps its days), its day sheet opens
        val whole = App(); whole.openOverview(); whole.log.clear()
        val restartWhole = App(); restartWhole.settle()
        assertEquals("the whole trip: its day sheet", listOf(tourId), restartWhole.shown)
        assertEquals("still linked – the trip is no day", tourId, restartWhole.plan.linkId)
        assertTrue(problems.said("plan", Problems.Level.WARN).any { it.contains("is the plan (kept by an older version) – its day sheet opens") })
        // a followed day (its state is there): nothing reloaded
        prefs.planMultiDay = null; prefs.planDayFollow = null
        val dayApp = App(); dayApp.rideDay2()
        val restartDay = App(); restartDay.settle()
        assertTrue(restartDay.shown.isEmpty()); assertTrue(restartDay.multiDay.flow.following)
        assertTrue("a followed day is known: the tour is not even read", restartDay.log.none { it.startsWith("store.getTour") })
    }

    @Test fun theLostDayCheckSkipsPlansWithoutALinkOrWithOnePointAndToursThatAreGoneOrNotMultiDay() {
        // a plan linked to nothing
        val unlinked = App(); ui { unlinked.plan.replace(listOf(C, D)); unlinked.plan.setSaved(null, -1L, null) }
        val a = App(); a.settle()
        assertTrue(a.log.none { it.startsWith("store.getTour") })
        // saved points but no route behind them
        ui { unlinked.plan.replace(listOf(C, D)); unlinked.plan.setSaved(null, -1L, listOf(C, D)) }
        val a2 = App(); a2.settle()
        assertTrue(a2.log.none { it.startsWith("store.getTour") })
        // one point
        ui { unlinked.plan.replace(listOf(C)); unlinked.plan.setSaved("Drei Tage", tourId, listOf(C, D)) }
        val b = App(); b.settle()
        assertTrue(b.log.none { it.startsWith("store.getTour") })
        // a link without saved points
        ui { unlinked.plan.replace(listOf(C, D)); unlinked.plan.setSaved("Drei Tage", tourId, null) }
        val c = App(); c.settle()
        assertTrue(c.log.none { it.startsWith("store.getTour") })
        // the opposite of all three: the day's points linked to the tour – read once
        ui { unlinked.plan.setSaved("Drei Tage", tourId, listOf(C, D)) }
        val d = App(); d.settle()
        assertEquals(1, d.log.count { it == "store.getTour@io" })
        // the tour is in the bin: not reloaded; a route without days: not reloaded (the day's plan is in the store again: the case above reloaded the tour)
        ui { unlinked.plan.replace(listOf(C, D)); unlinked.plan.setSaved("Drei Tage", tourId, listOf(C, D)) }
        db.setDeleted(tourId, 5L)
        val e = App(); e.settle()
        assertTrue("deleted: read, not opened", e.log.contains("store.getTour@io") && e.shown.isEmpty())
        db.setDeleted(tourId, null)
        ui { unlinked.plan.replace(listOf(C, D)) }
        val plain = db.insertTour(TourKind.PLANNED, "Normal", "HIKE", 2, waypoints = listOf(GpxWaypoint(C.lat, C.lon, null), GpxWaypoint(D.lat, D.lon, null)))
        ui { unlinked.plan.setSaved("Normal", plain, listOf(C, D)) }
        val f = App(); f.settle()
        assertTrue("no days: the plan stays", f.log.contains("store.getTour@io") && f.shown.isEmpty())
    }

    @Test fun aTourThatCannotBeReadAtTheStartIsReportedAndLeavesThePlan() {
        val (app, day) = lostDayApp()
        app.failOn += "getTour"
        app.multiDay.start(); app.settle()
        assertEquals(day, app.plan.points.toList()); assertTrue(app.shown.isEmpty())
        assertTrue(problems.said("db", Problems.Level.WARN).any { it.contains("Saved route not read at the start – no multi-day check") })
    }

    // ---------------------------------------------------------------- 5. "‹ Alle Tage": back to the trip's day sheet (MD-2)

    @Test fun allDaysAsksForTheTripsDaySheetAndKeepsTheDayAsThePlan() {
        val app = App(); app.rideDay2()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        app.log.clear()
        ui { app.multiDay.allDays() }
        assertEquals(listOf(tourId), app.shown)
        assertEquals("the day stays the plan, never the whole trip", "CXD", app.planIds())
        assertTrue(app.multiDay.flow.following)
        // without a followed day (the plan is another route now): nothing
        ui { app.plan.setSaved("Other", 99L, listOf(C, D)) }
        ui { app.multiDay.allDays() }
        assertEquals(listOf(tourId), app.shown)
    }

    // ---------------------------------------------------------------- 6. MD-4: start or goal moved – Save makes the day its own tour

    @Test fun aDayWithItsGoalMovedIsSavedAsItsOwnTourTheTripStays() {
        val app = App(); app.rideDay2()
        assertFalse(app.multiDay.leavesTrip())
        ui { app.planEdit.change { set(lastIndex, X) } }; app.settle()
        assertTrue(app.multiDay.leavesTrip())
        assertTrue("the plan sheet says so before saving", app.multiDay.dayHeadUi()!!.leavesTrip)
        assertNull("the recording no longer counts for the day", app.multiDay.followedDay())
        app.saveAs("Mein Tag")
        assertEquals("the trip as it was", "ABCDE", app.stored())
        val own = app.planned().single { it.id != tourId }
        assertEquals("Mein Tag", own.name); assertEquals("CX", ids(own.waypoints.map { GeoPoint(it.lat, it.lon) }))
        assertNull("a standalone tour, no days", own.days)
        assertEquals(Txt.s(Res.string.multiday_own_tour), app.board.mapNote?.text)
        assertFalse("linked to its own tour now: no day of the trip", app.multiDay.flow.following)
    }

    /**
     * MD-12 (owner's decision card 2026-11-28 09:37, via the captain – replaces the 10:13 reading „the day stays in the trip“): a
     * day whose activity or T level is not the trip's is like a moved start / goal (MD-4) – the plan sheet and Save say so, Save
     * makes it a standalone tour with the new profile, the trip stays identical (points, line, activity, T level, days); the
     * recording no longer counts for the trip's day (as with MD-4).
     */
    @Test fun aDayWithAnotherActivityAndTLevelIsSavedAsItsOwnTourTheTripStays() {
        val app = App(); app.rideDay2()
        val before = db.getTour(tourId)!!; val lineBefore = app.storedLine()
        ui { app.planRoute.selectActivity(Activity.MTB) }; app.settle()
        ui { app.planRoute.selectActivity(Activity.HIKE) }; app.settle()
        ui { app.planRoute.selectSac(4) }; app.settle()
        assertEquals(MultiDaySlice.Leave.ACTIVITY, app.multiDay.leaveReason())
        assertTrue("another profile leaves the trip", app.multiDay.leavesTrip())
        assertEquals("the plan sheet says why before saving", Txt.s(Res.string.multiday_own_tour_activity), app.multiDay.dayHeadUi()!!.leaveNote)
        assertNull("the recording no longer counts for the trip's day (as MD-4)", app.multiDay.followedDay())
        app.saveAs("Mein Tag")
        assertEquals(Txt.s(Res.string.multiday_own_tour_activity), app.board.mapNote?.text)
        // the trip identical before and after Save
        val after = db.getTour(tourId)!!
        assertEquals(before.waypoints, after.waypoints); assertEquals(before.activity, after.activity); assertEquals(before.sac, after.sac)
        assertEquals(before.days, after.days); assertEquals(before.name, after.name); assertEquals(lineBefore, app.storedLine())
        assertEquals("ABCDE", app.stored())
        // the copy: the day with the new profile, no days
        val own = app.planned().single { it.id != tourId }
        assertEquals("Mein Tag", own.name); assertEquals("CD", ids(own.waypoints.map { GeoPoint(it.lat, it.lon) }))
        assertEquals(Activity.HIKE.name, own.activity); assertEquals(4, own.sac); assertNull(own.days)
        assertFalse("linked to its own tour now: no day of the trip", app.multiDay.flow.following)
    }

    /** MD-12 for the T level alone (a hiking trip): „Schwierigkeit geändert – …“; back on the trip's level it belongs again. */
    @Test fun aHikingDayWithAnotherTLevelLeavesTheTripAndBackOnItsLevelItBelongsAgain() {
        routePrefs.activity = Activity.HIKE
        val id = insertTour(activity = Activity.HIKE)
        val app = App(); app.rideDay(id, dayRoute = dayLine)
        assertNull(app.multiDay.leaveReason())
        ui { app.planRoute.selectSac(3) }; app.settle()
        assertEquals(MultiDaySlice.Leave.LEVEL, app.multiDay.leaveReason())
        assertEquals(Txt.s(Res.string.multiday_own_tour_level), app.multiDay.leavesTripNote())
        ui { app.planRoute.selectSac(2) }; app.settle()
        assertNull("the trip's T2 again", app.multiDay.leaveReason())
        assertEquals("u1" to 2, app.multiDay.followedDay())
        // a moved goal and another level: the start / goal line is said (one plain line)
        ui { app.planRoute.selectSac(5) }; app.settle()
        ui { app.planEdit.change { set(lastIndex, X) } }; app.settle()
        assertEquals(MultiDaySlice.Leave.ENDS, app.multiDay.leaveReason())
        assertEquals(Txt.s(Res.string.multiday_own_tour), app.multiDay.leavesTripNote())
    }

    /** MD-1: a followed day leaving the map unsaved goes under Zuletzt as the day – never the whole trip. */
    @Test fun aFollowedDaysUnsavedChangeGoesUnderZuletztAsTheDay() {
        val app = App(); app.rideDay2()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        ui { app.save.keepAsDraft("replaced") }; app.settle()
        assertEquals("CXD", ids(app.drafts().single().waypoints.map { GeoPoint(it.lat, it.lon) }))
        assertNull(app.drafts().single().days)
        assertEquals("the trip untouched", "ABCDE", app.stored())
    }

    // ---------------------------------------------------------------- 7. the kept map areas

    @Test fun theKeptAreasAreAskedForAgainAfterARestartAndABadEntryDoesNotStopTheStart() {
        prefs.multiDayMapCells = setOf("10-543-361", "garbage", "10-543-362")
        val app = App()
        assertEquals(setOf(CellId(543, 361), CellId(543, 362)), app.cells.tourCells)
        // the collectors still run after that
        app.rideDay2()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        assertEquals("ABCXDE", ids(app.multiDay.flow.day!!.whole.points))
    }

    @Test fun aMapAreaStoreThatIsNotReadyIsReportedAndTheRestOfTheStartRuns() {
        prefs.multiDayMapCells = setOf("10-543-361")
        val first = App(started = false); first.cellsFail = true
        first.multiDay.start(); first.settle()
        assertTrue(problems.said("cells", Problems.Level.WARN).any { it.contains("Kept multi-day map areas not restored") })
        // the write-back collector was started after it
        first.rideDay2()
        ui { first.planEdit.change { add(1, X) } }; first.settle()
        assertEquals("ABCXDE", ids(first.multiDay.flow.day!!.whole.points))
    }

    // ---------------------------------------------------------------- 8. "Karten fehlen" in My routes

    @Test fun myRoutesTagsAMultiDayTourWhoseMapsAreNotAllThereAndOnlyThat() {
        val app = App(); val t = db.getTour(tourId)!!
        app.wholeMissing = cellsAll
        val tagged = app.lanes.on("io") { app.multiDay.withMaps(t) }
        assertEquals(true, tagged.mapsMissing)
        assertTrue("the stored line was read for it", app.log.contains("store.loadSegments@io"))
        app.wholeMissing = emptyList()
        assertEquals(false, app.lanes.on("io") { app.multiDay.withMaps(t) }.mapsMissing)
        // the guards: a recorded tour, a route without days, a deleted tour – returned as they are (and nothing read)
        app.wholeMissing = cellsAll; app.log.clear()
        val recorded = t.copy(kind = TourKind.RECORDED)
        assertTrue(app.multiDay.withMaps(recorded) === recorded)
        val plain = t.copy(days = null)
        assertTrue(app.multiDay.withMaps(plain) === plain)
        val gone = t.copy(deletedMs = 1L)
        assertTrue(app.multiDay.withMaps(gone) === gone)
        assertTrue(app.log.none { it.startsWith("store.") })
    }

    @Test fun aLineThatCannotBeReadOrMapsThatCannotBeWorkedOutLeaveNoTag() {
        val app = App(); val t = db.getTour(tourId)!!
        app.failOn += "loadSegments"
        assertNull(app.lanes.on("io") { app.multiDay.withMaps(t) }.mapsMissing)
        assertTrue(problems.said("routes", Problems.Level.WARN).any { it.contains("Maps of the multi-day tour $tourId not checked – no tag") })
        app.mapsFail = true
        assertNull(app.lanes.on("io") { app.multiDay.withMaps(t) }.mapsMissing)
    }

    // ---------------------------------------------------------------- 9. what the screens show

    @Test fun theDayHeadCanLeaveOnlyWhenNoTourRuns() {
        val app = App(); app.rideDay2()
        assertTrue(app.multiDay.dayHeadUi()!!.canLeave)
        app.touringNow = true
        assertFalse(app.multiDay.dayHeadUi()!!.canLeave)
        app.touringNow = false
        ui { app.plan.setSaved("Other", 99L, listOf(C, D)) }
        assertNull("no day, no head", app.multiDay.dayHeadUi())
    }

    @Test fun aNormalPlanShowsNothingOfMultiDay() {
        val app = App()
        assertNull(app.multiDay.dayHeadUi()); assertNull(app.multiDay.daysFor(listOf(A, B))); assertFalse(app.multiDay.leavesTrip())
        assertFalse(app.multiDay.overview)
        assertEquals(days, App().also { it.openOverview() }.multiDay.daysFor(pts))
        assertNull("a tour that is not the plan any more has no days of its own", App().also { it.openOverview(); ui { it.plan.replace(listOf(GeoPoint(1.0, 1.0), GeoPoint(2.0, 2.0))) } }.multiDay.daysFor(listOf(A, B)))
    }

    // ---------------------------------------------------------------- detour points belong to a profile (review finding 2)

    /**
     * A saved multi-day tour HIKE T2 with detour points: day 2 picked, T3, back to T2, Save – the tour keeps its detour points
     * (they lie on the T2 line; under T3 they only do not count).
     */
    @Test fun aDayPickedUnderAnotherProfileKeepsTheToursDetourPointsForItsOwn() {
        fun on(km: Int) = GeoPoint(line[km].lat, line[km].lon)
        val anchors = mapOf(2 to com.onpath.app.routing.Anchors(after = listOf(on(35))), 3 to com.onpath.app.routing.Anchors(before = listOf(on(50))))
        routePrefs.activity = Activity.HIKE
        val id = insertTour(activity = Activity.HIKE, anchors = anchors)
        val app = App()
        app.rideDay(id, dayRoute = dayLine)
        assertEquals(anchors, db.getTour(id)!!.anchors)
        ui { app.planRoute.selectSac(3) }; app.settle()
        ui { app.planRoute.selectSac(2) }; app.settle()
        assertEquals("the day's plan counts them again on T2", 2, app.plan.anchorsFor(Activity.HIKE, 2).size)
        assertTrue(ui { app.multiDay.overwriteWhole("Drei Tage", null) }); app.settle()
        assertEquals("T2", 2, db.getTour(id)!!.sac)
        assertEquals("the saved tour keeps its detour points", anchors, db.getTour(id)!!.anchors)
    }

    /**
     * "Ganz neu berechnen" on a followed day (review of W3): the day's plan keeps its points and loses its detour points – that must
     * reach the whole tour (Speichern writes it), and so must the Undo of it.
     */
    @Test fun aClearAndItsUndoOnAFollowedDayReachTheWholeTour() {
        fun on(km: Int) = GeoPoint(line[km].lat, line[km].lon)
        val anchors = mapOf(2 to com.onpath.app.routing.Anchors(after = listOf(on(35))), 3 to com.onpath.app.routing.Anchors(before = listOf(on(50))))
        routePrefs.activity = Activity.HIKE
        val id = insertTour(activity = Activity.HIKE, anchors = anchors)
        val app = App()
        app.rideDay(id, dayRoute = dayLine)
        assertTrue("the day carries its detour points", app.plan.hasDetours(Activity.HIKE, 2))
        ui { app.plan.clearDetours() }; app.settle()
        assertEquals("the whole tour heard the clear", emptyMap<Int, com.onpath.app.routing.Anchors>(), app.multiDay.flow.day!!.whole.anchors)
        ui { app.plan.replace(app.plan.undo()!!) }; app.settle()
        assertEquals("and the undo of it", anchors, app.multiDay.flow.day!!.whole.anchors)
    }

    /**
     * MD-12: a write over the whole trip always keeps the trip's own profile and its detour points – the phone never changes the
     * whole trip's activity / T level (before: saved as T3, the T2 points were dropped). Save itself never gets here with another
     * profile ([MultiDaySlice.leavesTrip] → a standalone copy).
     */
    @Test fun aWriteOverTheWholeTripKeepsItsProfileAndDetourPoints() {
        fun on(km: Int) = GeoPoint(line[km].lat, line[km].lon)
        val anchors = mapOf(2 to com.onpath.app.routing.Anchors(after = listOf(on(35))))
        routePrefs.activity = Activity.HIKE
        val id = insertTour(activity = Activity.HIKE, anchors = anchors)
        val app = App()
        app.rideDay(id, dayRoute = dayLine)
        ui { app.planRoute.selectSac(3) }; app.settle()
        assertTrue(ui { app.multiDay.overwriteWhole("Drei Tage", null) }); app.settle()
        assertEquals(2, db.getTour(id)!!.sac)
        assertEquals(Activity.HIKE.name, db.getTour(id)!!.activity)
        assertEquals(anchors, db.getTour(id)!!.anchors)
    }

    /**
     * A followed day with unsaved edits leaves the map for a track or round trip of another profile (review problem 2): the day goes
     * under Zuletzt ONCE (MD-1: the day, never the whole trip), with the plan's own activity / T level (handed over, the live ones
     * were switched already).
     */
    @Test fun aFollowedDayLeavingUnderAnotherProfileGivesOneDraftWithItsOwnProfileAndAnchors() {
        fun on(km: Int) = GeoPoint(line[km].lat, line[km].lon)
        // outside the picked day 2 (C to D): the day's own edit leaves them alone
        val anchors = mapOf(0 to com.onpath.app.routing.Anchors(after = listOf(on(10))), 4 to com.onpath.app.routing.Anchors(before = listOf(on(80))))
        routePrefs.activity = Activity.HIKE
        val id = insertTour(activity = Activity.HIKE, anchors = anchors)
        val app = App()
        app.rideDay(id, dayRoute = dayLine)
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        val own = com.onpath.app.ui.routes.OwnProfile(Activity.HIKE, 2, app.routeNow) // taken before the switch, with the day's route
        ui { app.planRoute.selectActivity(Activity.BIKE_TOURING) } // the track's activity, switched before the plan leaves
        ui { app.save.keepAsDraft("a track takes the plan", own) }; app.settle()
        val draft = app.drafts().single()
        assertEquals("the tour's own activity, not the track's", "HIKE", draft.activity)
        assertEquals(2, draft.sac)
        assertEquals("the day's points", 3, draft.waypoints.size)
    }
}
