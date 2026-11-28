package com.onpath.app.ui.routes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.onpath.app.data.AppSettings
import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.StoredLine
import com.onpath.app.testkit.FakeRoutingEngine
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.ManualExecutor
import com.onpath.app.testkit.VirtualDelayDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.time.TourTimes
import com.onpath.app.ui.Camera
import com.onpath.app.ui.NoticeBoard
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.ShownTrack
import com.onpath.app.ui.TrackThen
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanRouteHost
import com.onpath.app.ui.plan.PlanRoutePrefs
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.app.ui.plan.PlanStore
import com.onpath.app.ui.plan.RouterPort
import com.onpath.app.ui.plan.RoutingThreads
import com.onpath.app.ui.plan.TimesStore
import com.onpath.app.ui.tour.TourController
import com.onpath.app.ui.tour.TourRecorder
import com.onpath.strings.*
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule as JRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.AbstractExecutorService

/**
 * Routes I and II (rebuild phase 2 pieces 13 and 14: the second half at the end of the file) as flows of the REAL [RoutesSlice] / [RoutesGoSlice]: My routes' list read again, a tapped route or tour
 * opened on the map, a track made a plan, Delete into the recycle bin. Real neighbours: [PlanDocument], [RoutesState] /
 * [RecentPlans], [SaveSlice] (a plan that leaves the map goes under Zuletzt), [TourController], [PlanRouteSlice] (activity,
 * leg cache, model time) and the rules of `:core`; the tables are real ([JdbcTours]). Only the edges are fakes: the
 * view model's host (every call recorded in order WITH the thread it ran on and the state at that moment), the camera, the
 * routing engine, the stored times. Three held executors stand for main / io / the background routing thread: nothing
 * runs until the test lets it, and a call that must run on io is seen to run there.
 */
abstract class RoutesFlowBase {
    /** True: main is a virtual-time dispatcher ([VirtualDelayDispatcher]; `app.main.advance(ms)`), so a `delay` / timeout can be reached without waiting. */
    protected open val virtualTime = false
    @get:JRule val problems = ProblemsRule()
    @get:JRule val tmp = TemporaryFolder()

    protected val db = JdbcTours()
    protected val apps = ArrayList<App>()
    @After fun close() { apps.forEach { it.planRoute.close() }; db.close() }

    protected val a = GeoPoint(46.680, 11.140)
    protected val b = GeoPoint(46.700, 11.120)
    protected val c = GeoPoint(46.710, 11.100)

    protected class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }

    protected class MemoryPrefs(override var activity: Activity = Activity.HIKE, override var sac: Int = 2) : PlanRoutePrefs

    protected class FakeRouter(val legs: File) : RouterPort {
        val engine = FakeRoutingEngine()
        /** No router data on the phone: the straight-line stand-in. */
        var straight = false
        var createFails = false
        /** isRealRouter is false: the engine is not kept, so asking for it never clears the leg cache of a test. */
        override fun create(): RoutingEngine {
            if (createFails) throw IllegalStateException("router could not start")
            return if (straight) com.onpath.app.routing.StraightLineEngine() else object : RoutingEngine by engine {
                override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int) = engine.route(waypoints, activity, sacLimit).also { onRoute() }
                override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: com.onpath.app.routing.RouteLimits) =
                    engine.route(waypoints, activity, sacLimit, limits).also { limits.cancel?.let { c -> cancels += c }; onRoute() }
            }
        }
        /** The cancel handle of every request that had one. */
        val cancels = ArrayList<com.onpath.app.routing.RouteCancel>()
        /** Told on every routing request, on the thread it runs on. */
        var onRoute: () -> Unit = {}
        override val isRealRouter: Boolean get() = false
        override fun fingerprint() = "fp1"
        override val legsDir: File get() = legs
        override fun partVersion(part: Rd5Parts.Part) = "v1"
    }

    protected fun seg(vararg pts: GeoPoint) = pts.map { TrackPoint(it.lat, it.lon, 500.0) }

    protected fun planned(name: String = "Alm", activity: String? = "HIKE", sac: Int = 2, vararg via: GeoPoint = arrayOf(a, b, c),
                        pinned: Set<Int> = emptySet(), stopNames: Map<Int, String> = emptyMap()): Tour {
        val stops = via.mapIndexed { i, p -> GpxWaypoint(p.lat, p.lon, stopNames[i]) }
        // the saved line has a point between every two via points (a routed line is denser than its plan)
        val line = via.toList().zipWithNext { p, q -> listOf(p, GeoPoint((p.lat + q.lat) / 2, (p.lon + q.lon) / 2)) }.flatten() + via.last()
        val id = db.insertTour(TourKind.PLANNED, name, activity, sac, listOf(seg(*line.toTypedArray())), stops, distance = 4_000.0, pinned = pinned)
        return db.getTour(id)!!
    }

    protected fun recorded(name: String = "Ride", activity: String? = "HIKE", sac: Int = 2, vararg line: GeoPoint = arrayOf(a, b, c)): Tour {
        val id = db.insertTour(TourKind.RECORDED, name, activity, sac, listOf(seg(*line)), distance = 3_000.0)
        return db.getTour(id)!!
    }

    protected inner class App {
        val mainEx = ManualExecutor("main"); val ioEx = ManualExecutor("io"); val bgEx = ManualExecutor("bg")
        /** The thread the running task belongs to ("test" = none of the three). */
        var where = "test"
        private fun disp(name: String, ex: ManualExecutor): ExecutorCoroutineDispatcher = object : AbstractExecutorService() {
            override fun execute(r: Runnable) { ex.execute { val before = where; where = name; try { r.run() } finally { where = before } } }
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = ArrayList()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
        }.asCoroutineDispatcher()
        val main: CoroutineDispatcher = if (virtualTime) VirtualDelayDispatcher { r -> mainEx.execute { val before = where; where = "main"; try { r.run() } finally { where = before } } } else disp("main", mainEx)
        val io = disp("io", ioEx); val bg = disp("bg", bgEx)
        val scope = CoroutineScope(SupervisorJob() + main)
        /** Runs what waits on main / io / background until nothing is left. */
        fun settle() {
            var n = 0
            while (mainEx.size + ioEx.size + bgEx.size > 0) {
                mainEx.runAll(); ioEx.runAll(); bgEx.runAll()
                check(++n < 100) { "settle: tasks keep coming" }
            }
        }

        /** What the host was told, in order: "thread: what". */
        val log = ArrayList<String>()
        fun said(what: String) = log.filter { it.endsWith(what) || it.contains(": $what") }
        val notes = ArrayList<Triple<String, NoticeKind, NoticePlace>>()
        val fitted = ArrayList<List<GeoPoint>>()
        var refreshes = 0

        // the edges
        val planStore = MemoryPlanStore()
        val prefs = MemoryPrefs()
        val router = FakeRouter(File(tmp.root, "legs")).also { r -> r.onRoute = { log += "$where: router call" } }
        val plansOf = HashMap<Long, List<GpxWaypoint>>()
        /** Every row written under Zuletzt, in order. */
        val draftRows = ArrayList<RecentPlans.DraftRow>()
        val timesOf = HashMap<Long, TourTimes>()
        val ridesOf = HashMap<Long, com.onpath.app.rides.RideRecord>()
        var failPlanPoints = false
        /** The followed plan stored with a recorded tour (tour_plan: the JDBC kit has no table access for it). */
        val copies = HashMap<Long, com.onpath.app.data.TourPlan>()
        var failTourPlan = false
        var followIsTrack = false
        var routingNow by androidx.compose.runtime.mutableStateOf(false)
        val stopNames = HashMap<GeoPoint, String>()
        /** What the share sheet was given: the file's name and text, in order (the gpx lambda is run on the thread the host runs it on). */
        val shared = ArrayList<com.onpath.app.gpx.GpxFile>()
        val links = ArrayList<Pair<String, String?>>()
        var followMeNow = true
        var freeLook: GeoPoint? = GeoPoint(1.0, 1.0)
        var touring by androidx.compose.runtime.mutableStateOf(false)
        var recordingId: Long? = null
        var mode = AppMode.PLAN
        var missingParts = emptyList<String>()
        var routeNow: PlannedRoute? by androidx.compose.runtime.mutableStateOf(null)
        /** Runs once, in the middle of the first list read (on io, after its list was read). */
        var onFirstRow: (() -> Unit)? = null
        var failSegments = false
        /** The note's undo flag (null = no note) at the moment each open started (the host is told first thing). */
        val noteAtOpen = ArrayList<Boolean?>()
        /** Lets the open run, task by task, up to its last host call – the "planned" callback of "Los geht's" ran in that task and queued its wait. */
        fun untilTheWaitIsQueued() {
            var n = 0
            while (log.none { it.contains("syncRoutingCheck") }) { if (!mainEx.runNext() && !ioEx.runNext()) error("nothing left to run"); check(++n < 50) }
            check(mainEx.size > 0) { "the wait is queued" }
        }
        /** Runs a suspend call of the slice on main (held) and lets the three executors settle; its result. */
        fun <T> runOn(block: suspend () -> T): T {
            var r: Result<T>? = null
            scope.launch { r = runCatching { block() } }
            settle()
            return r!!.getOrThrow()
        }

        val routesStore = object : RoutesStore {
            /** The kit's reader leaves out the rides column: what the app's TourDb would read is put on the rows here. */
            override fun listTours() = db.listTours().map { t -> ridesOf[t.id]?.let { t.copy(rides = it) } ?: t }
            override fun listDeleted() = db.listDeleted()
            override fun setDraft(id: Long, draft: Boolean) = db.setDraft(id, draft)
            override fun setDeleted(id: Long, deletedMs: Long?) = db.setDeleted(id, deletedMs)
            override fun loadSegments(id: Long) = db.loadSegments(id)
            override fun deleteForGood(id: Long) = db.deleteTour(id)
            override fun rename(id: Long, name: String) = db.rename(id, name)
        }
        val store = object : ToursStore {
            override fun getTour(id: Long) = db.getTour(id)
            override fun loadSegments(id: Long): List<List<TrackPoint>> {
                log += "$where: store.loadSegments $id"
                if (failSegments) throw IllegalStateException("db closed")
                return db.loadSegments(id)
            }
            override fun insertPlanned(name: String, row: PlannedRow) =
                db.insertTour(TourKind.PLANNED, name, row.activity, row.sac, row.segments, row.stops, distance = row.distanceM,
                    ascent = row.ascentM, descent = row.descentM, duration = row.durationS, pinned = row.pins, difficulty = row.difficulty, days = row.days)
            override fun replacePlanned(id: Long, row: PlannedRow) =
                db.replacePlanned(id, row.activity, row.sac, row.distanceM, row.ascentM, row.descentM, row.durationS, row.segments, row.stops,
                    row.pins, difficulty = row.difficulty, days = row.days)
            override fun rename(id: Long, name: String) = db.rename(id, name)
            override fun setFavourite(id: Long, favourite: Boolean) = db.setFavourite(id, favourite)
            override fun planPoints(id: Long): List<GpxWaypoint>? {
                log += "$where: store.planPoints $id"
                if (failPlanPoints) throw IllegalStateException("db closed")
                return plansOf[id]
            }
            override fun tourTimes(): Map<Long, TourTimes> { log += "$where: store.tourTimes"; return timesOf }
            override fun listTours(): List<Tour> { log += "$where: store.listTours"; return routesStore.listTours() }
            override fun getTourPlan(id: Long): com.onpath.app.data.TourPlan? {
                log += "$where: store.getTourPlan $id"
                if (failTourPlan) throw IllegalStateException("db closed")
                return copies[id]
            }
            override fun insertDraftPlanned(name: String, row: PlannedRow): Long = error("not used")
        }

        val plan = PlanDocument(planStore)
        val routes = RoutesState(routesStore, clock = { 5_000L })
        val board = NoticeBoard(routesOpen = { routes.open }, settingsOpen = { false }, mapShown = { true })
        val recent = RecentPlans(routes, routesStore, object : RecentPlansHost {
            override fun updateDraft(id: Long, row: RecentPlans.DraftRow): Boolean =
                db.replacePlanned(id, row.activity, row.sac, row.distanceM, row.ascentM, row.descentM, row.durationS, row.segments, row.waypoints,
                    row.pinned, difficulty = row.difficulty, days = row.days, clearDays = row.days == null, anchors = row.anchors)
            override fun insertDraft(row: RecentPlans.DraftRow): Long =
                db.insertTour(TourKind.PLANNED, row.name, row.activity, row.sac, row.segments, row.waypoints, distance = row.distanceM, draft = true,
                    pinned = row.pinned, anchors = row.anchors)
                    .also { draftRows += row }
            override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) {}
            override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String? = null
            override fun tourChanged(id: Long) {}
            override fun purged(uuid: String) {}
            override fun syncChanged(why: String) {}
            override fun refreshTours() { refreshes++ }
            override fun problem(text: String) {}
            override var trimmed = false
        }, scope, io)
        val save = SaveSlice(plan, routes, recent, store, object : SaveHost {
            override val route: PlannedRoute? get() = routeNow
            override val activity: Activity get() = planRoute.activity
            override val sac: Int get() = planRoute.sac
            override fun stops(points: List<GeoPoint>) = points.map { GpxWaypoint(it.lat, it.lon, null) }
            override fun difficulty(route: PlannedRoute?, activity: Activity): String? = null
            override fun days(points: List<GeoPoint>): MultiDay? = null
            override fun overwriteWhole(name: String, then: (() -> Unit)?) = false
            override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String? = null
            override fun datedName() = "Route 31 Oct, 10:12"
            override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) {}
            override fun forgetTimes(id: Long) {}
            override fun copyTimes(from: Long, to: Long, distanceM: Double) {}
            override fun tourChanged(id: Long) {}
            override fun syncChanged(why: String) {}
            override fun refreshTours() { refreshes++ }
            override fun saved(name: String?, id: Long, points: List<GeoPoint>?) { plan.setSaved(name, id, points) }
            override fun shownRenamed(id: Long, name: String) {}
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) {}
        }, scope, io)
        val tour = TourController(object : TourRecorder {
            override fun start(activity: String, sac: Int) {}
            override fun continueTour(tourId: Long) {}
            override fun pause(pause: Boolean) {}
            override fun stop() {}
        })
        val planRoute: PlanRouteSlice = PlanRouteSlice(prefs, router, object : TimesStore {
            override fun loadSegments(id: Long) = db.loadSegments(id)
            override fun planGroups(id: Long, distanceM: Double): DoubleArray? = null
            override fun putPlanGroups(id: Long, distanceM: Double, groups: DoubleArray) {}
            override fun tourTimes(id: Long): TourTimes? = null
        }, object : PlanRouteHost {
            override val settings = AppSettings()
            override var route: PlannedRoute? = null
            override val routing = false
            override val longRouteAsk: Double? = null
            override val mode: AppMode get() = this@App.mode
            override val touring: Boolean get() = this@App.touring
            override fun recalc() { log += "$where: recalc ${planRoute.activity.name} T${planRoute.sac}" }
            override fun routeEvent(e: RouteRun.Event) {}
            override fun refreshTours() {}
            override fun clearShownNote() {}
            override fun showWhole(points: List<GeoPoint>) {}
        }, RoutingThreads(routing = disp("routing", ManualExecutor("r")), background = bg,
            check = disp("check", ManualExecutor("c")), hold = disp("hold", ManualExecutor("h"))))

        lateinit var slice: RoutesSlice
        val host = object : RoutesHost {
            override val touring get() = this@App.touring
            override val recordingTourId get() = recordingId
            override val mode get() = this@App.mode
            override fun enterMode(m: AppMode) { log += "$where: enter $m"; this@App.mode = m }
            override fun syncMarkSeen(t: Tour) { log += "$where: markSeen ${t.id}"; noteAtOpen += tour.newPlanNote?.undo }
            override fun rememberActivity(a: Activity) { log += "$where: pref activity $a"; prefs.activity = a }
            override fun rememberSac(level: Int) { log += "$where: pref sac $level"; prefs.sac = level }
            override fun loadedOnTheWay(on: Boolean) { log += "$where: onTheWay $on" }
            override fun routeEvent(e: RouteRun.Event) { log += "$where: event ${e::class.simpleName}" }
            override fun change(block: MutableList<GeoPoint>.() -> Unit) {
                plan.edit(block)
                // the order is the point: shown track gone, the old plan already kept under Zuletzt, the plan's activity already set
                log += "$where: change ${plan.points.size} points; shown=${slice.shown?.name}; drafts=${db.listTours().count { it.draft }}; " +
                    "activity=${planRoute.activity.name}/T${planRoute.sac}; pins=${plan.pinned.size}; routesOpen=${routes.open}"
            }
            override fun rememberPlaceName(p: GeoPoint, name: String) { log += "$where: place name $name" }
            override fun saved(name: String?, id: Long, points: List<GeoPoint>?) { log += "$where: saved $name $id"; plan.setSaved(name, id, points) }
            override fun opened(t: Tour, pts: List<GeoPoint>) { log += "$where: opened ${t.id}" }
            override fun openDays(t: Tour) { log += "$where: days of ${t.id}" }
            override fun syncRoutingCheck(t: Tour, pts: List<GeoPoint>) { log += "$where: syncRoutingCheck ${t.id}" }
            override fun standIn(pts: List<GeoPoint>, act: Activity, level: Int, preview: StoredLine.Preview) {
                log += "$where: standIn ${pts.size} ${act.name} T$level stale=${preview.stale} fromLine=${preview.fromLine}"
            }
            override fun withMaps(t: Tour): Tour { log += "$where: withMaps ${t.id} duration=${t.durationS.toLong()}"; onFirstRow?.let { onFirstRow = null; it() }; return t }
            override fun kickThumbs() { log += "$where: kickThumbs" }
            override fun clearHighlight() { log += "$where: clearHighlight" }
            override fun followShownNow() { log += "$where: followShownNow shown=${slice.shown?.name}" }
            override fun ensureRoutingData(parts: List<String>): List<String> { log += "$where: ensure $parts"; return missingParts }
            override fun routingParts(track: List<GeoPoint>, act: Activity) = listOf("part-${act.name}")
            override fun tourChanged(id: Long) { log += "$where: backup $id; bin=${db.listDeleted().map { it.id }}" }
            override fun syncForget(uuid: String) { log += "$where: forget mark $uuid" }
            override fun syncChanged(why: String) { log += "$where: sync $why; bin=${db.listDeleted().map { it.id }}" }
            override val route: PlannedRoute? get() = routeNow
            override val routing: Boolean get() = routingNow
            override val followIsTrack: Boolean get() = this@App.followIsTrack
            override fun undoStep() {
                log += "$where: undoStep; note=${tour.newPlanNote}; plan=${plan.points.size}"
                plan.undo()?.let { plan.replace(it) }
            }
            override fun continueNavigation() { log += "$where: continueNavigation" }
            override suspend fun share(name: String, gpx: () -> com.onpath.app.gpx.GpxFile) {
                log += "$where: share $name"
                shared += gpx()
            }
            override fun stopName(p: GeoPoint) = stopNames[p]
            override fun holdCameraForSharedPlace() { log += "$where: holdCamera" }
            override var followMe: Boolean
                get() = followMeNow
                set(v) { log += "$where: followMe $v"; followMeNow = v }
            override var freeLookAt: GeoPoint?
                get() = freeLook
                set(v) { log += "$where: freeLookAt $v"; freeLook = v }
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) { notes += Triple(text, kind, place); board.say(text, kind, place) }
        }
        val camera = object : Camera {
            override fun fit(points: List<GeoPoint>) { log += "$where: fit ${points.size}"; fitted += points }
            override fun centerOn(point: GeoPoint, zoom: Double) {}
            override fun panTo(point: GeoPoint) {}
        }
        var clock = 1_000L
        val linkFlow = com.onpath.app.ui.sync.PlanLinkFlow(object : com.onpath.app.ui.sync.PlanLinkHost {
            override fun backToMap() {}
            override fun sayError(text: String, place: NoticePlace) {}
            override fun receivePairing(secret: ByteArray, via: String) {}
            override fun openSyncSettings() {}
            override suspend fun startModeKnown() {}
            override fun navigating() = false
            override fun planPoints() = plan.points.toList()
            override fun savedPoints() = plan.savedPoints
            override fun open(plan: com.onpath.app.routing.SharedPlan, via: String) {}
            override fun share(link: String, title: String?) { log += "$where: link share"; links += link to title }
            override var storedLinkPoints: List<GeoPoint>? = null
        }, scope)
        lateinit var go: RoutesGoSlice
        init {
            slice = RoutesSlice(plan, routes, save, tour, planRoute, store, camera, host, scope, io, main) { clock }
            go = RoutesGoSlice(plan, routes, tour, planRoute, save, store, linkFlow, slice, camera, host, scope, io)
            apps += this
        }

        fun draw(vararg pts: GeoPoint) { plan.edit { clear(); addAll(pts) } }
        fun drafts() = db.listTours().filter { it.draft }
    }
}
