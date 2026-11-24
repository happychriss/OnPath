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
import com.onpath.app.ui.EndNamer
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
 * CORE FEATURE "multi-day tours on the phone" (docs/design/multi-day-tours.md §3 Phone, [MultiDaySlice], phase 2 piece 20) as
 * flows of the REAL slice: a 3-day tour is stored in the real tables ([JdbcTours]) and opened as the plan; the plan is the real
 * [PlanDocument], its edits go through the real [PlanEditSlice], Save is the real [SaveSlice] ([RoutesState] / [RecentPlans]),
 * the activity and the pace are the real [PlanRouteSlice], the state is the real [MultiDayFlow] over memory prefs, the map
 * areas the real [MapCells] (only its network is a fake). Only the edges are fakes: the host (what the view model answers: mode,
 * route, a tour records, names, the maps, the backup / sync / list), the camera, the place index.
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
 * sheets' Compose code. Not provable (named, not claimed): `plan.pin(part.pinned)` of "Tag n fahren" (the day's pins are a subset of
 * the plan's own, taken from it by `pinnedIndices()`: pinning them again changes nothing); the order of the two preference writes after a load (nothing reads between them); the order of the start's hook / cells /
 * collector lines (nothing runs between them); the order `dayMapsMissing = null` before `dayMapsCells = null` in the two day actions
 * (two assignments of the same moment).
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
    private fun insertTour(uuid: String = "u1", name: String = "Drei Tage", pinned: Set<Int> = setOf(3)): Long = db.insertTour(TourKind.PLANNED, name, Activity.BIKE_TOURING.name, 2,
        segments = listOf(line.map { TrackPoint(it.lat, it.lon, it.ele) }),
        waypoints = pts.mapIndexed { i, p -> GpxWaypoint(p.lat, p.lon, if (i == 3) "Gasthof Post" else null) },
        distance = 90_000.0, uuid = uuid, pinned = pinned, days = days)
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
        val remembered = ArrayList<String>()
        val reopened = ArrayList<Tour>()
        var saveProblem: String? = null
        var wholeMissing = emptyList<CellId>()
        var dayMissing = emptyList<CellId>()
        var mapsFail = false
        var tourMapsCalls = 0
        var cellsFail = false
        var indexFails = false
        var namerCalls = 0
        val endNames = HashMap<GeoPoint, String>()
        val fitted = ArrayList<List<GeoPoint>>()
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
                difficulty = row.difficulty, days = row.days)
            override fun replacePlanned(id: Long, row: PlannedRow): Boolean {
                gate("replacePlanned"); replaced++
                return db.replacePlanned(id, row.activity, row.sac, row.distanceM, row.ascentM, row.descentM, row.durationS, row.segments, row.stops,
                    row.pins, difficulty = row.difficulty, days = row.days)
            }
            override fun rename(id: Long, name: String) { gate("rename"); db.rename(id, name) }
            override fun setFavourite(id: Long, favourite: Boolean) = db.setFavourite(id, favourite)
            override fun planPoints(id: Long): List<GpxWaypoint>? = error("not used")
            override fun tourTimes(): Map<Long, TourTimes> = error("not used")
            override fun listTours(): List<Tour> = error("not used")
            override fun getTourPlan(id: Long): com.onpath.app.data.TourPlan? = error("not used")
            override fun insertDraftPlanned(name: String, row: PlannedRow): Long {
                gate("insertDraftPlanned")
                return db.insertTour(TourKind.PLANNED, name, row.activity, row.sac, row.segments, row.stops, distance = row.distanceM, ascent = row.ascentM,
                    descent = row.descentM, duration = row.durationS, pinned = row.pins, difficulty = row.difficulty, draft = true, days = row.days)
            }
        }

        // ---- the host (what the view model answers)
        val camera = object : com.onpath.app.ui.Camera {
            override fun fit(points: List<GeoPoint>) { log += "fit ${points.size}"; fitted += points }
            override fun centerOn(point: GeoPoint, zoom: Double) = Unit
            override fun panTo(point: GeoPoint) = Unit
        }
        val host = object : MultiDayHost {
            override val mode: AppMode get() = modeNow
            override val route: PlannedRoute? get() = routeNow
            override val touring: Boolean get() = touringNow
            override val cells: MapCells get() { if (cellsFail) throw IllegalStateException("cells not ready"); return this@App.cells }
            override fun placeNameOf(p: GeoPoint): String? = names[p]
            override fun reopenTour(t: Tour) { log += "reopen ${t.id}@${lanes.at}"; reopened += t; ui { open(t) } }
            override fun reloadTourList() { log += "refresh@${lanes.at}"; routes.show(routes.load()) }
            override fun showSaveProblem(text: String) { saveProblem = text; log += "problem@${lanes.at}" }
            override fun tourChanged(id: Long) { log += "backup $id@${lanes.at} stored=${ids(db.getTour(id)?.waypoints?.map { GeoPoint(it.lat, it.lon) })}" }
            override fun syncChanged(why: String) { log += "sync: $why" }
            override suspend fun endNamer(): EndNamer {
                log += "endNamer@${lanes.at}"; namerCalls++
                if (indexFails) throw IllegalStateException("no index")
                return EndNamer { p -> check(lanes.at == "io") { "the end's name is read on io, not ${lanes.at}" }; endNames[p] }
            }
            override fun tourMaps(line: List<GeoPoint>): TourMapAreas? {
                tourMapsCalls++; log += "tourMaps(${line.size})@${lanes.at}"
                if (mapsFail) return null
                return if (line.size == this@MultiDaySliceTest.line.size) TourMapAreas(cellsAll, wholeMissing, wholeMissing.size * 10_000_000L)
                else TourMapAreas(cellsDay, dayMissing, dayMissing.size * 10_000_000L)
            }
            override fun loadTourMaps(want: List<CellId>, line: List<GeoPoint>, wifiWait: Boolean) {
                loads += "${want.joinToString { it.id }} line=${line.size} wifiWait=$wifiWait"
                log += "loadTourMaps wifiWait=$wifiWait [prefs cells=${prefs.multiDayMapCells.size} wifi=${prefs.multiDayWifiCells.size} ask=${multiDay.tourMapsAsk?.tourUuid} asked=${prefs.multiDayMapsAsked}]"
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
            override fun keepWhole() = multiDay.keepWhole()
            override fun overwriteWhole(name: String, then: (() -> Unit)?) = multiDay.overwriteWhole(name, then)
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
            plan, planRoute, store, prefs, camera, host,
            change = { block ->
                log += "change [points=${ids(plan.points)} pinned=${ids(plan.pinned.toList())} remembered=${remembered.joinToString()}]"
                planEdit.change(block)
            },
            setSaved = { name, id, points ->
                log += "saved $name $id [points=${ids(plan.points)} undo=${plan.canUndo} pinned=${ids(plan.pinned.toList())}]"
                plan.setSaved(name, id, points)
            },
            rememberPlaceName = { p, n -> remembered += "${letters[p]}=$n"; names[p] = n },
            nearbyNames = { planEdit.nearbyNames },
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
            plan.replace(p); plan.pin(t.pinned.map { p[it] }); plan.setSaved(t.name, t.id, p)
            multiDay.opened(t, p)
        }

        /** The tour is open and the route (the whole line) is calculated: the overview shows its days. */
        fun openOverview(id: Long = tourId) {
            ui { open(db.getTour(id)!!) }
            ui { routeNow = route(line) }
            settle()
            check(multiDay.flow.view != null) { "the overview's days were worked out" }
        }

        /** The followed day 2 is the plan (the day's route is the stretch of the line from C to D). */
        fun rideDay2() {
            openOverview()
            ui { multiDay.tapDay(2) }
            ui { multiDay.rideDays() }
            ui { routeNow = route(dayLine) }
            settle()
        }

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

    @Test fun aPickedDayBecomesThePlanInTheOrderOfTheOldBody() {
        val app = App(); app.openOverview()
        ui { app.multiDay.tapDay(2) }
        assertTrue("the overview shows", app.multiDay.overview)
        assertEquals("A..E", "ABCDE", app.planIds())
        app.log.clear()
        ui { app.multiDay.rideDays() }
        assertEquals("the day is the plan", "CD", app.planIds())
        assertEquals(listOf("D=Gasthof Post"), app.remembered)
        // the order: the pin and the name first, then the plan's one edit, then the history forgotten, then the saved link, then the camera
        assertEquals(listOf(
            "change [points=ABCDE pinned=D remembered=D=Gasthof Post]",
            "saved Drei Tage $tourId [points=CD undo=false pinned=D]",
            "fit 31",
        ), app.log.filter { !it.startsWith("tourMaps") })
        assertEquals(line.subList(30, 61), app.fitted.single())
        assertEquals("the saved link is the tour, its points the day's", listOf(C, D), app.plan.savedPoints)
        assertEquals(tourId, app.plan.linkId); assertEquals("Drei Tage", app.plan.linkName)
        assertFalse("the day as the plan is not unsaved", app.plan.unsaved)
        assertFalse("no Undo across the two plans", app.plan.canUndo)
        assertTrue(app.multiDay.flow.following); assertFalse(app.multiDay.overview)
        assertEquals(Txt.s(Res.string.multiday_day_of, "2", "3"), app.multiDay.dayLabel())
        assertEquals(com.onpath.app.ui.DayHeadUi(Txt.s(Res.string.multiday_day_of, "2", "3"), true, null), app.multiDay.dayHeadUi())
        // the day's state is kept: a restart follows it
        assertNotNull(prefs.planDayFollow)
        app.settle()
        val again = App()
        assertTrue(again.multiDay.flow.following); assertEquals("CD", again.planIds())
        assertEquals(2, again.multiDay.flow.day!!.part.first)
    }

    @Test fun theDaysMapsAreCheckedAtOnceOnTheDefaultLaneAndTheMissingOnesAreSaidBeforeTheStart() {
        val app = App(); app.dayMissing = listOf(CellId(543, 362)); app.openOverview()
        ui { app.multiDay.tapDay(2) }
        app.log.clear()
        ui { app.multiDay.rideDays() }
        assertNull("nothing is known before the check ran", app.multiDay.dayMapsMissing)
        assertEquals("the check waits on the default lane", 1, app.lanes.defEx.size)
        app.settle()
        assertTrue(app.log.toString(), app.log.contains("tourMaps(31)@default"))
        assertEquals(10_000_000L, app.multiDay.dayMapsMissing)
        assertEquals(10_000_000L, app.multiDay.dayHeadUi()!!.mapsBytes)
        // "Laden": now, also on mobile data, the day's areas and its line; the question is gone
        net.wifi = false
        app.multiDay.loadDayMaps()
        assertEquals(listOf("10-543-362 line=31 wifiWait=false"), app.loads)
        assertNull(app.multiDay.dayMapsMissing)
        assertEquals(setOf("10-543-362"), prefs.multiDayMapCells)
        assertEquals("the user asked: no Wi-Fi wait kept", emptySet<String>(), prefs.multiDayWifiCells)
    }

    @Test fun aDayWhoseMapsAreAllThereSaysNothing() {
        val app = App(); app.openOverview()
        ui { app.multiDay.tapDay(2) }; ui { app.multiDay.rideDays() }; app.settle()
        assertNull(app.multiDay.dayMapsMissing)
        app.multiDay.loadDayMaps()
        assertTrue("nothing to load", app.loads.isEmpty())
    }

    @Test fun aDayCheckThatEndsAfterTheWayBackToAllDaysSaysNothing() {
        val app = App(); app.dayMissing = listOf(CellId(543, 362)); app.openOverview()
        ui { app.multiDay.tapDay(2) }; ui { app.multiDay.rideDays() }
        ui { app.multiDay.allDays() }
        app.settle()
        assertFalse(app.multiDay.flow.following)
        assertNull("the day is not followed any more: no question about its maps", app.multiDay.dayMapsMissing)
    }

    @Test fun rideDaysDoesNothingDuringATourWithoutAnOverviewOrWithoutAPickedDay() {
        val app = App(); app.openOverview()
        // nothing picked: no day
        ui { app.multiDay.rideDays() }
        assertEquals("ABCDE", app.planIds()); assertFalse(app.multiDay.flow.following)
        // a tour records
        ui { app.multiDay.tapDay(2) }; app.touringNow = true
        ui { app.multiDay.rideDays() }
        assertEquals("ABCDE", app.planIds()); assertFalse(app.multiDay.flow.following)
        // the same picked day with no tour: it becomes the plan (the opposite of both)
        app.touringNow = false
        ui { app.multiDay.rideDays() }
        assertEquals("CD", app.planIds()); assertTrue(app.multiDay.flow.following)
        // a normal plan (no tour opened) has no overview
        val normal = App()
        ui { normal.plan.replace(listOf(A, B)); normal.plan.setSaved("x", 99, listOf(A, B)) }
        ui { normal.multiDay.tapDay(2) }; ui { normal.multiDay.rideDays() }
        assertEquals("AB", normal.planIds())
    }

    @Test fun aDayThatEndsBetweenPlanPointsIsItsTwoAddedEnds() {
        // a tour with two plan points only, three days of about 30 km: every day end is a proposal on the line, none is a plan point
        val id = db.insertTour(TourKind.PLANNED, "Ohne Nacht", Activity.BIKE_TOURING.name, 2,
            segments = listOf(line.map { TrackPoint(it.lat, it.lon, it.ele) }), waypoints = listOf(GpxWaypoint(A.lat, A.lon, null), GpxWaypoint(E.lat, E.lon, null)),
            distance = 90_000.0, uuid = "u3", days = MultiDay(3))
        val app = App(); app.openOverview(id)
        ui { app.multiDay.tapDay(2) }
        ui { app.multiDay.rideDays() }
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
        val app = App(); app.rideDay2()
        ui { app.multiDay.allDays() }; app.settle()
        assertFalse(app.multiDay.flow.following)
        app.replaced = 0; app.log.clear()
        ui { assertFalse("no day: the view model saves as always", app.multiDay.overwriteWhole("Drei Tage", null)) }
        assertEquals("nothing written by the multi-day slice", 0, app.replaced)
        assertTrue(app.log.none { it.startsWith("store.") })
        // the ordinary save writes the whole tour's plan (the overview's days with it)
        app.saveAs("Drei Tage")
        assertEquals("ABCDE", app.stored())
        assertEquals(days, db.getTour(tourId)!!.days)
    }

    @Test fun theNewestPointsAreWrittenBackBeforeSaveWritesEvenWhenNoCollectorSawThem() {
        val app = App(started = false)
        ui { app.open(db.getTour(tourId)!!) }; ui { app.routeNow = route(line) }
        app.multiDay.flow.show(app.multiDay.flow.compute(app.multiDay.flow.plan(pts, Activity.BIKE_TOURING, 2, setOf(3), mapOf(3 to "Gasthof Post"))!!, line,
            app.planRoute.planFactors(), "r"))
        ui { app.multiDay.tapDay(2) }; ui { app.multiDay.rideDays() }
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

    // ---------------------------------------------------------------- 4. a lost day state reloads the whole tour

    /** A restart after the day's state was damaged: the plan is the day's points, still linked to the tour. */
    private fun lostDayApp(): Pair<App, List<GeoPoint>> {
        val first = App(); first.rideDay2()
        prefs.planDayFollow = "{\"tour\": broken"
        return App(started = false) to listOf(C, D)
    }

    @Test fun aLostDayStateReloadsTheWholeTourNeverADayOverTheTour() {
        val (first, day) = lostDayApp()
        assertEquals(day, first.plan.points.toList())
        assertEquals("the plan is the day's, linked to the tour", tourId, first.plan.linkId)
        first.multiDay.start(); first.settle()
        assertTrue("the loss is known", first.multiDay.flow.dayLost)
        // the whole stored tour is the plan again – read on io, opened on main, once
        assertEquals(listOf("store.getTour@io", "reopen $tourId@main"), first.log.filter { it.startsWith("store.") || it.startsWith("reopen") })
        assertEquals("ABCDE", first.planIds())
        assertTrue("the overview of the tour", first.multiDay.flow.overview)
        assertTrue(problems.said("plan", Problems.Level.WARN).any { it.contains("the whole tour \"Drei Tage\" is loaded again") })
        // a Save of what is on the map now writes the whole tour (five points), never a day (two) over it
        first.settle(); ui { first.routeNow = route(line) }; first.settle()
        first.saveAs("Drei Tage")
        assertEquals("ABCDE", first.stored())
        assertEquals("one route", listOf(tourId), first.planned().map { it.id })
    }

    @Test fun aLostDayStateDuringATourKeepsThePlanAndSaveWritesACopyNotTheTour() {
        val (app, day) = lostDayApp()
        app.touringNow = true
        app.multiDay.start(); app.settle()
        assertTrue("the tour was read, then nothing opened", app.log.contains("store.getTour@io") && app.reopened.isEmpty())
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
        assertEquals("ABCDE", missing.planIds()); assertEquals(1, missing.reopened.size)
        // the tour's own points (opened, no day): the overview, nothing reloaded
        val whole = App(); whole.openOverview(); whole.log.clear()
        val restartWhole = App(); restartWhole.settle()
        assertTrue("the tour itself is no day", restartWhole.reopened.isEmpty())
        // a followed day (its state is there): nothing reloaded
        prefs.planMultiDay = null; prefs.planDayFollow = null
        val dayApp = App(); dayApp.rideDay2()
        val restartDay = App(); restartDay.settle()
        assertTrue(restartDay.reopened.isEmpty()); assertTrue(restartDay.multiDay.flow.following)
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
        assertTrue("deleted: read, not opened", e.log.contains("store.getTour@io") && e.reopened.isEmpty())
        db.setDeleted(tourId, null)
        ui { unlinked.plan.replace(listOf(C, D)) }
        val plain = db.insertTour(TourKind.PLANNED, "Normal", "HIKE", 2, waypoints = listOf(GpxWaypoint(C.lat, C.lon, null), GpxWaypoint(D.lat, D.lon, null)))
        ui { unlinked.plan.setSaved("Normal", plain, listOf(C, D)) }
        val f = App(); f.settle()
        assertTrue("no days: the plan stays", f.log.contains("store.getTour@io") && f.reopened.isEmpty())
    }

    @Test fun aTourThatCannotBeReadAtTheStartIsReportedAndLeavesThePlan() {
        val (app, day) = lostDayApp()
        app.failOn += "getTour"
        app.multiDay.start(); app.settle()
        assertEquals(day, app.plan.points.toList()); assertTrue(app.reopened.isEmpty())
        assertTrue(problems.said("db", Problems.Level.WARN).any { it.contains("Saved route not read at the start – no multi-day check") })
    }

    // ---------------------------------------------------------------- 5. "‹ Alle Tage": the whole tour is the plan again

    @Test fun allDaysPutsTheWholeTourWithTheDaysEditsBackInTheOrderOfTheOldBody() {
        val app = App(); app.rideDay2()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        app.log.clear(); app.remembered.clear()
        ui { app.multiDay.allDays() }
        assertEquals("ABCXDE", app.planIds())
        assertEquals(listOf(
            "change [points=CXD pinned=D remembered=D=Gasthof Post]",
            "saved Drei Tage $tourId [points=ABCXDE undo=false pinned=D]",
        ), app.log.filter { it.startsWith("change") || it.startsWith("saved") })
        assertEquals("the saved link is the tour as it was opened: the plan is unsaved (the day's edit)", listOf(A, B, C, D, E), app.plan.savedPoints)
        assertTrue(app.plan.unsaved)
        assertFalse("no Undo across the two plans", app.plan.canUndo)
        assertFalse(app.multiDay.flow.following)
        assertEquals("the day's rows stay picked", 2..2, app.multiDay.flow.selected)
        assertEquals("the marks are by value on the new indices", NightMark.OVERNIGHT, app.multiDay.flow.nightOf(C))
        assertEquals(hotel, app.multiDay.flow.nightOf(D))
        assertTrue(problems.traced("plan").any { it.contains("back to all days (6 points)") })
        assertEquals("pinned D", "D", ids(app.plan.pinned.toList()))
    }

    @Test fun allDaysTakesThePinsOfTheWholeTourBackThatTheDaysEditsForgot() {
        val id = insertTour(uuid = "u4", name = "Mit Pins", pinned = setOf(1, 3))
        val app = App(); app.openOverview(id)
        ui { app.multiDay.tapDay(2) }; ui { app.multiDay.rideDays() }
        app.settle()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        assertEquals("B is not in the day and nothing can bring it back: its pin is forgotten by the edit", setOf(D), app.plan.pinned)
        app.atRecalc.clear()
        ui { app.multiDay.allDays() }
        assertEquals("the whole tour's pins are all back", setOf(B, D), app.plan.pinned)
        assertEquals("the route run of the whole tour already sees both pins", listOf("points=ABCXDE pinned=BD"), app.atRecalc)
    }

    @Test fun allDaysForgetsTheQuestionAboutTheDaysMaps() {
        val app = App(); app.dayMissing = cellsDay; app.openOverview()
        ui { app.multiDay.tapDay(2) }; ui { app.multiDay.rideDays() }; app.settle()
        assertEquals("the day's maps are missing", 10_000_000L, app.multiDay.dayMapsMissing)
        ui { app.multiDay.allDays() }
        assertNull("the whole tour is the plan: no question about a day's maps", app.multiDay.dayMapsMissing)
        app.multiDay.loadDayMaps()
        assertTrue("and nothing left to load", app.loads.isEmpty())
    }

    @Test fun pickingADayAgainAfterTheTourWasOpenedAgainForgetsTheEarlierDaysMapQuestion() {
        val app = App(); app.dayMissing = cellsDay; app.openOverview()
        ui { app.multiDay.tapDay(2) }; ui { app.multiDay.rideDays() }; app.settle()
        assertEquals("day 2's maps are missing", 10_000_000L, app.multiDay.dayMapsMissing)
        // the tour is opened again from My routes (not "Alle Tage"): the overview, the question still stands
        app.openOverview()
        assertFalse(app.multiDay.flow.following)
        app.dayMissing = emptyList()
        ui { app.multiDay.tapDay(1) }; ui { app.multiDay.rideDays() }
        app.settle()
        assertNull("day 1's maps are all there: no question", app.multiDay.dayMapsMissing)
        app.multiDay.loadDayMaps()
        assertTrue("nothing of day 2 is loaded for day 1", app.loads.isEmpty())
    }

    @Test fun allDaysDoesNothingDuringATourOrWithoutAFollowedDay() {
        val none = App(); none.openOverview()
        ui { none.multiDay.allDays() }
        assertEquals("ABCDE", none.planIds())
        val app = App(); app.rideDay2()
        app.touringNow = true
        ui { app.multiDay.allDays() }
        assertEquals("a tour records: the day stays", "CD", app.planIds()); assertTrue(app.multiDay.flow.following)
        app.touringNow = false
        ui { app.multiDay.allDays() }
        assertEquals("ABCDE", app.planIds())
    }

    // ---------------------------------------------------------------- 6. the plan leaves the map: the whole tour goes under Zuletzt

    @Test fun aFollowedDaysUnsavedChangeKeepsTheWholeTourUnderZuletztNotTheDayAlone() {
        val app = App(); app.rideDay2()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        app.log.clear()
        ui { app.planEdit.clearPlan() }; app.settle()
        val draft = app.drafts().single()
        assertEquals("the tour's name, the whole tour's points", "Drei Tage", draft.name)
        assertEquals("ABCXDE", ids(draft.waypoints.map { GeoPoint(it.lat, it.lon) }))
        assertEquals("with its days and pins", MultiDay(3, nights = mapOf(2 to NightMark.OVERNIGHT, 4 to hotel), description = "Drei Tage"), draft.days)
        assertEquals(setOf(4), draft.pinned)
        assertEquals("the activity and the level of the plan", Activity.BIKE_TOURING.name to 2, draft.activity to draft.sac)
        assertEquals("the saved tour is as it was", "ABCDE", app.stored())
        assertTrue("the list is read again after the write", app.log.indexOf("store.insertDraftPlanned@main") < app.log.indexOf("refresh@main"))
        assertTrue(problems.traced("plan").any { it.contains("kept under Zuletzt") })
        assertEquals("one draft only", 1, app.drafts().size)
    }

    @Test fun theNewestPointsAreKeptUnderZuletztEvenWhenNoCollectorSawThem() {
        val app = App(started = false)
        ui { app.open(db.getTour(tourId)!!) }; ui { app.routeNow = route(line) }
        app.multiDay.flow.show(app.multiDay.flow.compute(app.multiDay.flow.plan(pts, Activity.BIKE_TOURING, 2, setOf(3), mapOf(3 to "Gasthof Post"))!!, line,
            app.planRoute.planFactors(), "r"))
        ui { app.multiDay.tapDay(2) }; ui { app.multiDay.rideDays() }
        ui { app.planEdit.change { add(1, X) } }
        assertEquals("no collector runs: the flow has not seen the edit", "ABCDE", ids(app.multiDay.flow.day!!.whole.points))
        ui { app.planEdit.clearPlan() }; app.settle()
        assertEquals("the draft has the edit", "ABCXDE", ids(app.drafts().single().waypoints.map { GeoPoint(it.lat, it.lon) }))
    }

    @Test fun aFollowedDayWithoutChangesLeavesNothingUnderZuletzt() {
        val app = App(); app.rideDay2()
        assertFalse(app.plan.unsaved)
        ui { app.planEdit.clearPlan() }; app.settle()
        assertTrue(app.drafts().isEmpty())
        assertTrue(app.log.none { it.startsWith("store.insertDraftPlanned") })
    }

    @Test fun aFailedKeepIsReportedAndTheDayAloneIsKeptInsteadNothingIsLost() {
        val app = App(); app.rideDay2()
        ui { app.planEdit.change { add(1, X) } }; app.settle()
        app.failOn += "insertDraftPlanned"
        ui { app.planEdit.clearPlan() }; app.settle()
        assertTrue(problems.said("db", Problems.Level.ERROR).any { it.contains("The multi-day tour with the day's change could not be kept under Zuletzt") })
        assertEquals("the ordinary keep took the day: three points", 3, app.drafts().single().waypoints.size)
    }

    // ---------------------------------------------------------------- 7. the maps question (§7)

    @Test fun theFirstOpeningAsksOnceWhenMapsAreMissingWithTheSizesOfTheWholeTourAndTheFirstDay() {
        val app = App(); app.wholeMissing = cellsAll; app.dayMissing = cellsDay
        app.openOverview()
        val ask = app.multiDay.tourMapsAsk!!
        assertEquals("u1", ask.tourUuid)
        assertEquals(30_000_000L, ask.wholeBytes); assertEquals(10_000_000L, ask.dayBytes)
        assertEquals(1, ask.day); assertTrue("on Wi-Fi", ask.onWifi)
        assertEquals(cellsAll, ask.whole); assertEquals(cellsDay, ask.dayCells)
        assertEquals(line, ask.line); assertEquals(31, ask.dayLine.size)
        assertTrue(app.log.toString(), app.log.containsAll(listOf("tourMaps(91)@default", "tourMaps(31)@default")))
        // not asked again while it is open, however often the days are worked out again
        val calls = app.tourMapsCalls
        ui { app.routeNow = route(line).copy(durationS = 7_200.0) }; app.settle()
        assertEquals("the days were worked out again", 7_200.0, (app.multiDay.flow.view!!.key as PlannedRoute).durationS, 0.0)
        assertEquals(calls, app.tourMapsCalls)
        assertEquals(ask, app.multiDay.tourMapsAsk)
        // the picked day is the one the question is about
        assertTrue(problems.traced("cells").any { it.contains("asking once") })
    }

    @Test fun theQuestionKnowsMobileDataAndAPickedDay() {
        net.wifi = false
        val app = App(); app.wholeMissing = cellsAll; app.dayMissing = cellsDay
        ui { app.open(db.getTour(tourId)!!) }; ui { app.multiDay.tapDay(2) }; ui { app.routeNow = route(line) }; app.settle()
        val ask = app.multiDay.tourMapsAsk!!
        assertFalse(ask.onWifi); assertEquals(2, ask.day)
        assertEquals("day 2's line", 31, ask.dayLine.size)
    }

    @Test fun noMissingMapsMeansNoQuestionAndTheTourIsMarkedAsked() {
        val app = App(); app.openOverview()
        assertNull(app.multiDay.tourMapsAsk)
        assertEquals(setOf("u1"), prefs.multiDayMapsAsked)
        // asked once: no second look at the maps
        val calls = app.tourMapsCalls
        app.wholeMissing = cellsAll
        ui { app.routeNow = route(line).copy(durationS = 7_200.0) }; app.settle()
        assertEquals("the days were worked out again", 7_200.0, (app.multiDay.flow.view!!.key as PlannedRoute).durationS, 0.0)
        assertEquals(calls, app.tourMapsCalls); assertNull(app.multiDay.tourMapsAsk)
    }

    @Test fun aTourWithoutAnIdentityOrWithMapsNotWorkedOutAsksNothing() {
        val noUuid = App(); noUuid.wholeMissing = cellsAll
        val id = insertTour(uuid = "", name = "Ohne")
        noUuid.openOverview(id)
        assertNull(noUuid.multiDay.tourMapsAsk); assertEquals(0, noUuid.tourMapsCalls)
        val broken = App(); broken.wholeMissing = cellsAll; broken.mapsFail = true
        broken.openOverview(insertTour(uuid = "u2", name = "Zwei"))
        assertNull("not worked out (reported by the maps flow): no question, no mark", broken.multiDay.tourMapsAsk)
        assertEquals(emptySet<String>(), prefs.multiDayMapsAsked)
        assertTrue(broken.tourMapsCalls > 0)
    }

    private fun asked(): App { val app = App(); app.wholeMissing = cellsAll; app.dayMissing = cellsDay; app.openOverview(); return app }

    @Test fun answeringTheWholeTourLoadsItsAreasThenKeepsThemAndNeverAsksAgain() {
        val app = asked(); app.log.clear()
        ui { app.multiDay.answerTourMaps(MultiDaySlice.TourMapsAnswer.WHOLE) }
        assertNull("the question is gone", app.multiDay.tourMapsAsk)
        assertEquals(setOf("u1"), prefs.multiDayMapsAsked)
        assertEquals(listOf("10-543-361, 10-543-362, 10-544-362 line=91 wifiWait=false"), app.loads)
        assertEquals("the question closed and counted first, the areas loaded, kept after", listOf("loadTourMaps wifiWait=false [prefs cells=0 wifi=0 ask=null asked=[u1]]"), app.log.filter { it.startsWith("loadTourMaps") })
        assertEquals(setOf("10-543-361", "10-543-362", "10-544-362"), prefs.multiDayMapCells)
        assertEquals(emptySet<String>(), prefs.multiDayWifiCells)
        assertTrue(problems.traced("cells").any { it.contains("Multi-day tour maps: whole") })
    }

    @Test fun onMobileDataTheAreasWaitForWifiAndAreKeptSo() {
        net.wifi = false
        val app = asked()
        assertFalse("asked on mobile data", app.multiDay.tourMapsAsk!!.onWifi)
        net.wifi = true // the phone is on Wi-Fi when the answer comes: the question's own state counts
        ui { app.multiDay.answerTourMaps(MultiDaySlice.TourMapsAnswer.WHOLE) }
        assertEquals(listOf("10-543-361, 10-543-362, 10-544-362 line=91 wifiWait=true"), app.loads)
        assertEquals(setOf("10-543-361", "10-543-362", "10-544-362"), prefs.multiDayMapCells)
        assertEquals("those that wait for Wi-Fi are kept apart", setOf("10-543-361", "10-543-362", "10-544-362"), prefs.multiDayWifiCells)
        assertTrue(problems.traced("cells").any { it.contains("(mobile data: wait for Wi-Fi)") })
    }

    @Test fun onlyTheDayLoadsTheDaysAreasAndItsLine() {
        val app = asked()
        ui { app.multiDay.answerTourMaps(MultiDaySlice.TourMapsAnswer.DAY) }
        assertEquals(listOf("10-543-362 line=31 wifiWait=false"), app.loads)
        assertEquals(setOf("10-543-362"), prefs.multiDayMapCells)
    }

    @Test fun laterLoadsNothingButCountsAsAnswered() {
        val app = asked()
        ui { app.multiDay.answerTourMaps(MultiDaySlice.TourMapsAnswer.LATER) }
        assertTrue(app.loads.isEmpty()); assertEquals(emptySet<String>(), prefs.multiDayMapCells)
        assertEquals("the question is not asked again", setOf("u1"), prefs.multiDayMapsAsked)
        assertNull(app.multiDay.tourMapsAsk)
        val calls = app.tourMapsCalls
        ui { app.routeNow = route(line).copy(durationS = 7_200.0) }; app.settle()
        assertEquals("the days were worked out again", 7_200.0, (app.multiDay.flow.view!!.key as PlannedRoute).durationS, 0.0)
        assertNull("the days are worked out again, the maps are still missing: no second question", app.multiDay.tourMapsAsk)
        assertEquals("and not even looked at", calls, app.tourMapsCalls)
        // after a restart the same
        val again = App(); again.wholeMissing = cellsAll; again.openOverview()
        assertNull(again.multiDay.tourMapsAsk)
    }

    @Test fun anAnswerWithNoQuestionOpenDoesNothing() {
        val app = App(); app.openOverview()
        ui { app.multiDay.answerTourMaps(MultiDaySlice.TourMapsAnswer.WHOLE) }
        assertTrue(app.loads.isEmpty()); assertEquals(setOf("u1"), prefs.multiDayMapsAsked)
        assertEquals(emptySet<String>(), prefs.multiDayMapCells)
    }

    @Test fun theKeptAreasAreAskedForAgainAfterARestartAndABadEntryDoesNotStopTheStart() {
        prefs.multiDayMapCells = setOf("10-543-361", "garbage", "10-543-362"); prefs.multiDayWifiCells = setOf("10-543-362")
        val app = App()
        assertEquals(setOf(CellId(543, 361), CellId(543, 362)), app.cells.tourCells)
        assertEquals(setOf(CellId(543, 362)), app.cells.tourWifiCells)
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
        // the write-back collector and the overview's days were started after it
        ui { first.open(db.getTour(tourId)!!) }; ui { first.routeNow = route(line) }; first.settle()
        assertNotNull(first.multiDay.flow.view)
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

    @Test fun theOverviewsRowsShowFromToOnlyWhereBothPlacesAreKnown() {
        val app = App(); app.openOverview()
        ui { app.multiDay.tapDay(2) }
        val shown = app.multiDay.daysUi()!!
        assertEquals("Drei Tage", shown.name); assertEquals(3, shown.count); assertEquals(2..2, shown.selected)
        assertEquals(listOf(1, 2, 3), shown.rows.map { it.n })
        assertEquals("no name at the start or the first end: no second line, no km; the hotel at the only known end shows",
            listOf(null, com.onpath.app.routing.DayRowText.places(null, "Gasthof Post", toIsHotel = true),
                com.onpath.app.routing.DayRowText.places("Gasthof Post", null, fromIsHotel = true)), shown.rows.map { it.places })
        app.names[A] = "Start"; app.names[C] = "Bad Tölz"
        val named = app.multiDay.daysUi()!!.rows
        assertEquals("Start – Bad Tölz", named[0].places)
        assertEquals("a stop with a name of its own ends day 2 and starts day 3", "Bad Tölz – Gasthof Post", named[1].places)
        assertEquals("the finish has no name: day 3 starts at the hotel",
            com.onpath.app.routing.DayRowText.places("Gasthof Post", null, fromIsHotel = true), named[2].places)
        assertEquals("the night of day 1 is a night out, day 2's a hotel", listOf(com.onpath.app.routing.DayEndKind.OVERNIGHT, com.onpath.app.routing.DayEndKind.HOTEL, null),
            shown.rows.map { it.end })
        assertEquals(2, shown.borders.size)
        assertEquals(line, shown.profile)
    }

    @Test fun aHotelsNameIsItsEndsPlaceEvenWhenTheStopHasNoName() {
        val app = App(); app.openOverview()
        app.names.remove(D); app.names[C] = "Bad Tölz"; app.names[E] = "Ziel"
        val rows = app.multiDay.daysUi()!!.rows
        assertEquals("Bad Tölz – Gasthof Post", rows[1].places)
        assertEquals("Gasthof Post – Ziel", rows[2].places)
    }

    @Test fun aPlainStopAtTheOnlyKnownEndStaysWithoutASecondLine() {
        val app = App(); app.openOverview()
        app.names.remove(D); app.names[A] = "Start"
        val rows = app.multiDay.daysUi()!!.rows
        assertNull("day 1 knows only its start, and that is no hotel", rows[0].places)
    }

    @Test fun theDayEndsAreNamedFromTheOfflineIndexOnceAndNeverTwiceAtTheSameTime() {
        val app = App(); app.openOverview()
        app.multiDay.daysUi()
        assertEquals("the index is opened at once, on main", listOf("endNamer@main"), app.log.filter { it.startsWith("endNamer") })
        app.multiDay.daysUi()
        assertEquals("a second look while it runs starts no second lookup", 1, app.namerCalls)
        assertEquals("the names are read on io, held", 1, app.lanes.ioEx.size)
        app.endNames[C] = "Bad Tölz"; app.endNames[A] = "Start"
        app.settle()
        val after = app.multiDay.daysUi()!!
        assertEquals("the day's start and end are named", "Start – Bad Tölz", after.rows[0].places)
        assertEquals("and the next day starts there", "Bad Tölz – Gasthof Post", after.rows[1].places)
        assertEquals("an end the index does not know: no „von – nach“, only the hotel the day starts at",
            com.onpath.app.routing.DayRowText.places("Gasthof Post", null, fromIsHotel = true), after.rows[2].places)
        app.settle()
        assertEquals("every end has an answer now (even an empty one): nothing is looked up again", 1, app.namerCalls)
    }

    @Test fun anIndexThatCannotBeOpenedIsReportedAndTheRowsHaveNoPlacesAndItIsTriedAgainLater() {
        val app = App(); app.indexFails = true; app.openOverview()
        app.multiDay.daysUi(); app.settle()
        assertEquals(1, app.namerCalls)
        assertTrue(problems.said("plan", Problems.Level.INFO).any { it.contains("No place index for the day ends; their rows without places") })
        val rows = app.multiDay.daysUi()!!.rows
        assertNull("no names, no second line", rows[0].places)
        app.settle()
        assertEquals("tried again by the next look", 2, app.namerCalls)
    }

    @Test fun theMapShowsDayEndsOnlyInPlanAndWhileTheOverviewShows() {
        val app = App(); app.openOverview()
        ui { app.multiDay.tapDay(2) }
        val (ends, band) = app.multiDay.dayEndsOnMap()
        assertEquals(2, ends.size); assertNotNull(band)
        assertEquals("day 2's band runs from the first end to the second", line.subList(30, 61), band)
        app.modeNow = AppMode.NAVIGATE
        assertEquals(emptyList<Any>() to null, app.multiDay.dayEndsOnMap())
        app.modeNow = AppMode.PLAN
        ui { app.multiDay.tapDay(2) } // picked again: none picked
        assertNull(app.multiDay.dayEndsOnMap().second)
        assertEquals(2, app.multiDay.dayEndsOnMap().first.size)
        // a followed day: no overview, no ends
        ui { app.multiDay.tapDay(2) }; ui { app.multiDay.rideDays() }
        assertEquals(emptyList<Any>() to null, app.multiDay.dayEndsOnMap())
    }

    @Test fun theDayHeadCanLeaveOnlyWhenNoTourRuns() {
        val app = App(); app.rideDay2()
        assertTrue(app.multiDay.dayHeadUi()!!.canLeave)
        app.touringNow = true
        assertFalse(app.multiDay.dayHeadUi()!!.canLeave)
        app.touringNow = false
        ui { app.multiDay.allDays() }
        assertNull("no day, no head", app.multiDay.dayHeadUi())
    }

    @Test fun aNormalPlanShowsNothingOfMultiDay() {
        val app = App()
        assertNull(app.multiDay.daysUi()); assertNull(app.multiDay.dayHeadUi()); assertNull(app.multiDay.daysFor(listOf(A, B)))
        assertFalse(app.multiDay.overview)
        assertEquals(days, App().also { it.openOverview() }.multiDay.daysFor(pts))
        assertNull("a tour that is not the plan any more has no days of its own", App().also { it.openOverview(); ui { it.plan.replace(listOf(GeoPoint(1.0, 1.0), GeoPoint(2.0, 2.0))) } }.multiDay.daysFor(listOf(A, B)))
    }
}
