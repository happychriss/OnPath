package com.onpath.app.ui.sync

import androidx.compose.runtime.snapshots.Snapshot
import com.onpath.app.data.AppSettings
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.plansync.SyncMark
import com.onpath.app.plansync.SyncPassResult
import com.onpath.app.plansync.SyncReport
import com.onpath.app.plansync.SyncRouting
import com.onpath.app.plansync.SyncState
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.sync.SyncRound
import com.onpath.app.testkit.FakeRoutingEngine
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.ManualExecutor
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.time.TourTimes
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanRouteHost
import com.onpath.app.ui.plan.PlanRoutePrefs
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.app.ui.plan.PlanStore
import com.onpath.app.ui.plan.RouterPort
import com.onpath.app.ui.plan.RoutingThreads
import com.onpath.app.ui.plan.TimesStore
import com.onpath.app.ui.routes.PlannedRow
import com.onpath.app.ui.routes.SaveStore
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.sync_routing_other
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.AbstractExecutorService

/**
 * Core feature C16, the phone's half of a plan that comes from the browser (rebuild phase 2 piece 15): [SyncedRoutingSlice] –
 * the routing of the plans a pass brought, what opening one says, the open plan following its saved route – driven for real
 * with the real neighbours ([PlanDocument], [PlanRouteSlice] with its leg cache and the planner, the rules of `:core`, the
 * tables as [JdbcTours] rows) and, in the last flows, the real [SyncDriver] handing a pass's report on. Fakes only at the edges: the
 * view model's host and the router. Three held executors stand for main / io / the background routing thread, so each call is
 * seen on the thread it ran on; every port call is logged with its thread, its arguments and the state at that moment.
 *
 * Not here (their own tests): the rule what may be stored ([com.onpath.app.plansync.SyncedRouting], core SyncedRoutingTest),
 * the pass itself and the ledger (`CorePlanSyncTest`, `SyncMailboxE2ETest` in :app), the triggers (`SyncDriverTest`), the
 * "changed elsewhere" copy on Save (`SaveFlowTest`). The view model's wiring of the slice is `SyncWiringTest`.
 */
class SyncedRoutingFlowTest {
    @get:Rule val problems = ProblemsRule()
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val composeState = com.onpath.app.testkit.OwnComposeSnapshot()

    private val db = JdbcTours()
    private val rigs = ArrayList<Rig>()
    @After fun close() { rigs.forEach { it.planRoute.close() }; db.close() }

    private val a = GeoPoint(46.680, 11.140)
    private val b = GeoPoint(46.700, 11.120)
    private val c = GeoPoint(46.710, 11.100)

    private class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }

    private class MemoryPrefs : PlanRoutePrefs {
        override var activity: Activity = Activity.HIKE
        override var sac: Int = 2
    }

    /** A browser plan as the pass stores it: its via points, a line that is only those points, its own uuid. */
    private fun fromBrowser(name: String = "Alm", vararg via: GeoPoint = arrayOf(a, b, c), kind: TourKind = TourKind.PLANNED,
                            uuid: String = "uuid-$name", activity: String? = "HIKE", sac: Int = 2, pinned: Set<Int> = emptySet(),
                            anchors: Map<Int, com.onpath.app.routing.Anchors> = emptyMap()): Tour {
        val id = db.insertTour(kind, name, activity, sac, listOf(via.map { TrackPoint(it.lat, it.lon, 0.0) }),
            via.map { GpxWaypoint(it.lat, it.lon, null) }, uuid = uuid, pinned = pinned, anchors = anchors)
        return db.getTour(id)!!
    }

    private inner class Rig {
        val mainEx = ManualExecutor("main"); val ioEx = ManualExecutor("io"); val bgEx = ManualExecutor("bg")
        var where = "test"
        private fun disp(name: String, ex: ManualExecutor): ExecutorCoroutineDispatcher = object : AbstractExecutorService() {
            override fun execute(r: Runnable) { ex.execute { val before = where; where = name; try { r.run() } finally { where = before } } }
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = ArrayList()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
        }.asCoroutineDispatcher()
        val main: CoroutineDispatcher = disp("main", mainEx)
        val io = disp("io", ioEx); val bg = disp("bg", bgEx)
        val scope = CoroutineScope(SupervisorJob() + main)
        fun settle() {
            var n = 0
            while (mainEx.size + ioEx.size + bgEx.size > 0) {
                mainEx.runAll(); ioEx.runAll(); bgEx.runAll()
                check(++n < 100) { "settle: tasks keep coming" }
            }
        }

        /** Every port call in order: "thread: what". */
        val log = ArrayList<String>()
        fun said(what: String) = log.filter { it.contains(what) }

        val planStore = MemoryPlanStore()
        val plan = PlanDocument(planStore)
        val engine = FakeRoutingEngine()
        var straight = false
        var createFails = false
        /** What the scripted router says of every route (the kit engine gives 0 / 0). */
        var ascent = 0.0
        var descent = 0.0
        /** Runs inside every routing request, on the thread it runs on (a change made "meanwhile"). */
        var onRoute: () -> Unit = {}
        val router = object : RouterPort {
            override fun create(): RoutingEngine {
                if (createFails) throw IllegalStateException("router could not start")
                return if (straight) com.onpath.app.routing.StraightLineEngine() else object : RoutingEngine by engine {
                    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int) = engine.route(waypoints, activity, sacLimit).copy(ascentM = ascent, descentM = descent).also { hit() }
                    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: com.onpath.app.routing.RouteLimits) =
                        engine.route(waypoints, activity, sacLimit, limits).copy(ascentM = ascent, descentM = descent).also { hit() }
                    private fun hit() { log += "$where: router call"; onRoute() }
                }
            }
            override val isRealRouter: Boolean get() = false
            override fun fingerprint() = "fp1"
            override val legsDir: File get() = File(tmp.root, "legs")
            override fun partVersion(part: Rd5Parts.Part) = "v1"
        }
        val planGroups = ArrayList<String>()
        val prefs = MemoryPrefs()
        val planRoute: PlanRouteSlice = PlanRouteSlice(prefs, router, object : TimesStore {
            override fun loadSegments(id: Long) = db.loadSegments(id)
            override fun planGroups(id: Long, distanceM: Double): DoubleArray? = null
            override fun putPlanGroups(id: Long, distanceM: Double, groups: DoubleArray) { planGroups += "$where: times $id ${distanceM.toLong()}" }
            override fun tourTimes(id: Long): TourTimes? = null
        }, object : PlanRouteHost {
            override val settings = AppSettings()
            override var route: PlannedRoute? = null
            override val routing = false
            override val longRouteAsk: Double? = null
            override val mode: AppMode = AppMode.PLAN
            override val touring: Boolean = false
            override fun recalc() {}
            override fun routeEvent(e: RouteRun.Event) {}
            override fun refreshTours() {}
            override fun clearShownNote() {}
            override fun showWhole(points: List<GeoPoint>) {}
        }, RoutingThreads(routing = disp("routing", ManualExecutor("r")), background = bg,
            check = disp("check", ManualExecutor("c")), hold = disp("hold", ManualExecutor("h"))))

        var failRead: (Long) -> Boolean = { false }
        var failReplace = false
        val store = object : SaveStore {
            override fun getTour(id: Long): Tour? {
                log += "$where: store.getTour $id"
                if (failRead(id)) throw IllegalStateException("db closed")
                return db.getTour(id)
            }
            override fun loadSegments(id: Long) = db.loadSegments(id)
            override fun insertPlanned(name: String, row: PlannedRow): Long = error("not used by the slice")
            override fun replacePlanned(id: Long, row: PlannedRow): Boolean {
                log += "$where: store.replacePlanned $id ${row.activity} T${row.sac} d=${row.distanceM.toLong()} line=${row.segments.sumOf { it.size }} " +
                    "stops=${row.stops.size} pins=${row.pins}"
                if (failReplace) throw IllegalStateException("database or disk is full")
                return db.replacePlanned(id, row.activity, row.sac, row.distanceM, row.ascentM, row.descentM, row.durationS, row.segments, row.stops,
                    row.pins, difficulty = row.difficulty, days = row.days, anchors = row.anchors)
            }
            override fun rename(id: Long, name: String) = db.rename(id, name)
            override fun setFavourite(id: Long, favourite: Boolean) = db.setFavourite(id, favourite)
        }

        /** What `PlanSync.opened` answers; throws when [openedFails]. */
        var openedAnswer: SyncRouting.Opened? = null
        var openedFails = false
        val host = object : SyncedRoutingHost {
            override fun opened(uuid: String, points: List<GeoPoint>): SyncRouting.Opened? {
                log += "$where: opened $uuid ${points.size}"
                if (openedFails) throw IllegalStateException("sync files unreadable")
                return openedAnswer
            }
            override fun refreshRoutingTiles(tiles: List<String>, why: String) { log += "$where: tiles $tiles ($why)" }
            override fun refreshTours() { log += "$where: refreshTours" }
            override fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) {
                log += "$where: saved $name $id ${points?.size}"; plan.setSaved(name, id, points)
            }
            override fun tourChanged(id: Long) { log += "$where: backup $id" }
        }
        val slice = SyncedRoutingSlice(plan, planRoute, store, host, scope, io)

        /** The plan as the user left it: [pts] linked to saved route [t] (name, id, the points as saved). */
        fun open(t: Tour, pts: List<GeoPoint> = t.waypoints.map { GeoPoint(it.lat, it.lon) }) {
            plan.edit { clear(); addAll(pts) }; plan.setSaved(t.name, t.id, pts)
        }
        init { rigs += this }
    }


    private fun rig(block: Rig.() -> Unit = {}) = Rig().apply(block)
    /** The log without the router's own lines (how many legs the planner asks for is not this piece's business). */
    private fun Rig.calls() = log.filter { !it.contains("router call") }
    private fun Rig.routerThreads() = log.filter { it.contains("router call") }.map { it.substringBefore(":") }.distinct()
    private val saved = com.onpath.app.debug.Problems.Level.ERROR

    // ---------------- routing the plans a pass brought ----------------

    @Test fun aPlanFromTheBrowserIsRoutedOnTheBackgroundThreadAndStoredWithItsRealLine() {
        val t = fromBrowser()
        val app = rig()
        val routed = ArrayList<Long>()
        app.slice.routeSyncedPlans(listOf(t.id), routed = { routed += it })
        assertTrue("nothing runs before its thread is let go", app.log.isEmpty())
        app.settle()
        assertEquals("the caller hears the stored line (a plan link's trip: its day sheet follows)", listOf(t.id), routed)
        // the order of effects and the thread of each: read on io, routed on bg (never main, never the user's routing thread),
        // read again and written on io, backup and list on main
        val stored = db.getTour(t.id)!!
        val line = db.loadSegments(t.id).single()
        assertEquals(listOf(
            "io: store.getTour 1", "io: store.getTour 1",
            "io: store.replacePlanned 1 HIKE T2 d=${stored.distanceM.toLong()} line=${line.size} stops=3 pins=[]",
            "main: backup 1", "main: refreshTours",
        ), app.calls())
        assertEquals(listOf("bg"), app.routerThreads())
        assertTrue("routed between the first read and the second", app.log.indexOf("bg: router call") in 1 until app.log.lastIndexOf("io: store.getTour 1"))
        // what is stored: the real line instead of the three points, the stats, the plan's own via points, name and uuid kept
        assertTrue("a routed line, denser than its three points (${line.size})", line.size > 3)
        assertTrue(stored.distanceM > 0)
        assertEquals(t.waypoints, stored.waypoints)
        assertEquals("Alm", stored.name); assertEquals("uuid-Alm", stored.uuid)
        assertEquals(listOf("io: times 1 ${stored.distanceM.toLong()}"), app.planGroups)
    }

    /** An activity name this app does not know: the route is routed as a hike, so detour points of another profile do not belong to it (review finding 3). */
    @Test fun aPlanOfAnUnknownActivityIsRoutedAndStoredWithoutItsDetourPoints() {
        val mid = GeoPoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
        val t = fromBrowser("Alm", activity = "SKI_TOURING", anchors = mapOf(1 to com.onpath.app.routing.Anchors(before = listOf(mid))))
        assertEquals(1, db.getTour(t.id)!!.anchors.size)
        val app = rig()
        app.slice.routeSyncedPlans(listOf(t.id))
        app.settle()
        assertTrue("it was routed and stored", app.said("store.replacePlanned").isNotEmpty())
        assertEquals("routed as a hike", "HIKE", db.getTour(t.id)!!.activity)
        assertEquals(emptyMap<Int, com.onpath.app.routing.Anchors>(), db.getTour(t.id)!!.anchors)
    }

    @Test fun aPlanEditedAgainMeanwhileIsNotOverwrittenAndNotBackedUp() {
        val t = fromBrowser()
        val app = rig()
        var once = true
        // a newer sync pass changes the route's stops while the old one is being routed on the background thread
        app.onRoute = { if (once) { once = false; db.replacePlanned(t.id, "HIKE", 2, 1.0, 0.0, 0.0, 1.0, listOf(listOf(TrackPoint(a.lat, a.lon, 0.0), TrackPoint(c.lat, c.lon, 0.0))),
            listOf(GpxWaypoint(a.lat, a.lon, null), GpxWaypoint(c.lat, c.lon, null))) } }
        app.slice.routeSyncedPlans(listOf(t.id))
        app.settle()
        assertEquals(listOf("io: store.getTour 1", "io: store.getTour 1", "main: refreshTours"), app.calls())
        val stored = db.getTour(t.id)!!
        assertEquals("the newer version stays as it is: its own routing will come", 2, stored.waypoints.size)
        assertEquals(1.0, stored.distanceM, 0.0)
        assertTrue(app.planGroups.isEmpty())
        assertTrue(problems.none())
    }

    /**
     * The user opens the browser's route while it is still being routed, changes only the activity / the T level / a pinned
     * point (the stops stay) and saves: the routing's late write must not put the old activity, level and pins back
     * (found in the release hardening for 0.18.2; the check compared the stops only).
     */
    @Test fun aSaveThatKeepsTheStopsButChangesActivityLevelOrPinsMeanwhileIsNotOverwritten() {
        data class Saved(val what: String, val activity: String, val sac: Int, val pins: Set<Int>)
        val cases = listOf(Saved("activity", "BIKE_TOURING", 2, emptySet()), Saved("T level", "HIKE", 4, emptySet()), Saved("pinned point", "HIKE", 2, setOf(1)))
        cases.forEachIndexed { n, case ->
            // its own three points per case: a leg routed before is not asked of the router again
            val via = listOf(a, b, c).map { GeoPoint(it.lat + 0.05 * n, it.lon) }
            val t = fromBrowser("Alm ${case.what}", *via.toTypedArray())
            val app = rig()
            var saves = 0
            app.onRoute = { if (saves++ == 0) db.replacePlanned(t.id, case.activity, case.sac, 777.0, 0.0, 0.0, 1.0,
                listOf(via.map { TrackPoint(it.lat, it.lon, 0.0) }), via.map { GpxWaypoint(it.lat, it.lon, null) }, case.pins) }
            app.slice.routeSyncedPlans(listOf(t.id))
            app.settle()
            assertTrue("${case.what}: the save fell into the routing", saves > 0)
            val stored = db.getTour(t.id)!!
            assertEquals("${case.what}: the user's save stays", case.activity, stored.activity)
            assertEquals("${case.what}: the user's save stays", case.sac, stored.sac)
            assertEquals("${case.what}: the user's save stays", case.pins, stored.pinned)
            assertEquals("${case.what}: with its own line and numbers", 777.0, stored.distanceM, 0.0)
            assertTrue("${case.what}: nothing written, nothing backed up", app.said("store.replacePlanned").isEmpty() && app.said("backup").isEmpty())
            assertTrue(app.planGroups.isEmpty())
        }
        assertTrue(problems.none())
    }

    @Test fun aRouteDeletedMeanwhileStaysInTheBinNotWrittenNotBackedUp() {
        val t = fromBrowser()
        val app = rig { onRoute = { db.setDeleted(t.id, 5_000L) } }
        app.slice.routeSyncedPlans(listOf(t.id))
        app.settle()
        assertEquals("the write was tried on the row (it said no: the route is in the bin)", 1, app.said("store.replacePlanned").size)
        assertTrue("no backup, no stored times", app.said("backup").isEmpty() && app.planGroups.isEmpty())
        assertEquals(5_000L, db.getTour(t.id)!!.deletedMs)
        assertEquals("its line is still the browser's three points", 3, db.loadSegments(t.id).single().size)
    }

    @Test fun whatIsNotALiveBrowserPlanIsSkippedWithoutRouting() {
        val gone = fromBrowser("gone"); db.setDeleted(gone.id, 1L)
        val ride = fromBrowser("ride", kind = TourKind.RECORDED, uuid = "u-ride")
        val one = fromBrowser("one", a, uuid = "u-one")
        val app = rig()
        app.slice.routeSyncedPlans(listOf(gone.id, ride.id, one.id, 999L))
        app.settle()
        assertTrue("no router call for any of them", app.routerThreads().isEmpty())
        assertEquals(listOf("io: store.getTour ${gone.id}", "io: store.getTour ${ride.id}", "io: store.getTour ${one.id}", "io: store.getTour 999", "main: refreshTours"), app.calls())
        assertEquals("the stored rows stay as they were", 1, db.loadSegments(one.id).single().size)
        assertTrue(app.said("backup").isEmpty())
    }

    @Test fun noIdsNothingAtAll() {
        val app = rig()
        app.slice.routeSyncedPlans(emptyList())
        app.settle()
        assertTrue(app.log.isEmpty())
    }

    @Test fun theSameIdTwiceIsRoutedOnce() {
        val t = fromBrowser()
        val app = rig()
        app.slice.routeSyncedPlans(listOf(t.id, t.id))
        app.settle()
        assertEquals(1, app.said("store.replacePlanned").size)
        assertEquals(1, app.said("backup").size)
    }

    @Test fun anActivityTheAppDoesNotKnowIsRoutedAsHike() {
        val t = fromBrowser(activity = "SKATEBOARD")
        val app = rig()
        app.slice.routeSyncedPlans(listOf(t.id))
        app.settle()
        assertEquals("HIKE", app.engine.calls.first().activity.name)
        assertTrue(app.said("replacePlanned").single().contains("HIKE T2"))
    }

    @Test fun withoutRoutingDataThePointsStayAndTheRouteIsRoutedOnceWhenTheDataArrives() {
        val t = fromBrowser()
        val app = rig { engine.fail = { "No detailed map here yet." } }
        app.slice.routeSyncedPlans(listOf(t.id))
        app.settle()
        // straight legs are no route: nothing stored, nothing backed up, the list is read again
        assertEquals(listOf("io: store.getTour 1", "main: refreshTours"), app.calls())
        assertEquals(3, db.loadSegments(t.id).single().size)
        assertEquals(0.0, db.getTour(t.id)!!.distanceM, 0.0)
        assertTrue(problems.traced("sync").any { it.contains("no routing data there yet") })
        // data arrives, but not for this region: it waits again (never lost)
        app.log.clear()
        app.slice.routeWaiting(); app.settle()
        assertEquals(listOf("io: store.getTour 1", "main: refreshTours"), app.calls())
        // the data is there now: routed and stored, once
        app.engine.fail = { null }
        app.log.clear()
        app.slice.routeWaiting(); app.settle()
        assertEquals(1, app.said("store.replacePlanned").size)
        assertEquals(listOf("main: backup 1"), app.said("backup"))
        assertTrue(db.loadSegments(t.id).single().size > 3)
        // a second burst of arrivals routes nothing again
        app.log.clear()
        app.slice.routeWaiting(); app.settle()
        assertTrue(app.log.isEmpty())
    }

    @Test fun aRouteThatWaitsIsKeptOnlyOnceNoMatterHowOftenItCame() {
        val t = fromBrowser()
        val app = rig { engine.fail = { "No detailed map here yet." } }
        app.slice.routeSyncedPlans(listOf(t.id)); app.settle()
        app.slice.routeSyncedPlans(listOf(t.id)); app.settle()
        app.engine.fail = { null }
        app.log.clear()
        app.slice.routeWaiting(); app.settle()
        assertEquals(1, app.said("store.replacePlanned").size)
    }

    /**
     * Kept verbatim: the straight-line stand-in engine only appears when the router could not START (the BRouter classes failed to
     * load; with no routing data BRouter answers with straight legs and the plan IS queued – the test above). Then the plan is
     * skipped, not queued: it keeps its points until it is opened.
     */
    @Test fun whenTheRouterCouldNotStartAndTheStandInIsUsedAPlanIsSkippedAndNotQueued() {
        val t = fromBrowser()
        val app = rig { straight = true }
        app.slice.routeSyncedPlans(listOf(t.id)); app.settle()
        assertEquals(listOf("io: store.getTour 1", "main: refreshTours"), app.calls())
        app.straight = false
        app.planRoute.engine = null
        app.log.clear()
        app.slice.routeWaiting(); app.settle()
        assertTrue("nothing waits for the data: the route is routed when it is opened", app.log.isEmpty())
        assertEquals(3, db.loadSegments(t.id).single().size)
    }

    @Test fun aRouterThatCannotStartKeepsThePointsAndTheNextRouteIsStillTried() {
        val t1 = fromBrowser("one", uuid = "u1"); val t2 = fromBrowser("two", uuid = "u2")
        val app = rig { createFails = true }
        app.slice.routeSyncedPlans(listOf(t1.id, t2.id)); app.settle()
        assertTrue(problems.said("sync").any { it.contains("could not be routed") }) // the log folds the same text
        assertEquals("both were tried", listOf("io: store.getTour ${t1.id}", "io: store.getTour ${t2.id}"), app.calls().filter { it.startsWith("io") })
        assertTrue("a warning, not an error: the route keeps its points until it is opened", problems.said("sync", saved).isEmpty())
        assertEquals(3, db.loadSegments(t1.id).single().size); assertEquals(3, db.loadSegments(t2.id).single().size)
        assertTrue(app.said("backup").isEmpty())
        assertEquals("the list is read again", 1, app.said("refreshTours").size)
    }

    @Test fun aRouteTheDatabaseCannotReadIsLeftForLaterAndTheNextIsRouted() {
        val t1 = fromBrowser("one", uuid = "u1"); val t2 = fromBrowser("two", uuid = "u2")
        val app = rig { failRead = { it == t1.id } }
        app.slice.routeSyncedPlans(listOf(t1.id, t2.id)); app.settle()
        assertTrue(problems.said("db").any { it.contains("not read; routed when opened") })
        assertEquals(listOf("main: backup ${t2.id}"), app.said("backup"))
        assertEquals(3, db.loadSegments(t1.id).single().size)
    }

    @Test fun aFullDiskIsReportedAsAnErrorNothingIsBackedUpAndTheNextRouteIsStillWritten() {
        val t1 = fromBrowser("one", uuid = "u1"); val t2 = fromBrowser("two", uuid = "u2")
        val app = rig()
        db.failWrites = 1
        app.slice.routeSyncedPlans(listOf(t1.id, t2.id)); app.settle()
        assertEquals(1, problems.said("db", saved).count { it.contains("routed route from the browser could not be saved") })
        assertEquals("only the second was written and backed up", listOf("main: backup ${t2.id}"), app.said("backup"))
        assertEquals(3, db.loadSegments(t1.id).single().size)
        assertTrue(db.loadSegments(t2.id).single().size > 3)
    }

    /**
     * Every column of the write: a route whose ascent, descent, time and level all differ, stored with a non-default T level, a
     * non-hike activity and pinned points, while the user's prefs hold ANOTHER activity and level.
     */
    @Test fun everyColumnOfTheWriteComesFromTheRoutedRouteOrTheStoredRowNeverFromThePrefs() {
        val t = fromBrowser(activity = "MTB", sac = 4, pinned = setOf(1))
        val app = rig { ascent = 321.0; descent = 123.0; prefs.activity = Activity.BIKE_TOURING; prefs.sac = 1 }
        // the route list's cached time of this plan: gone after the write
        app.planRoute.withModelTime(t)
        assertTrue("cached before", app.planRoute.plannedTimes.containsKey(t.id))
        app.slice.routeSyncedPlans(listOf(t.id)); app.settle()
        val row = db.getTour(t.id)!!
        val line = db.loadSegments(t.id).single()
        val pts = line.map { GeoPoint(it.lat, it.lon) }
        assertEquals("two legs, each 321 up / 123 down", 642.0, row.ascentM, 0.0); assertEquals(246.0, row.descentM, 0.0)
        assertEquals("MTB", row.activity); assertEquals(4, row.sac); assertEquals(setOf(1), row.pinned)
        assertEquals(t.waypoints, row.waypoints)
        assertTrue(row.distanceM > 0); assertTrue(row.durationS > 0); assertNotEquals(row.distanceM, row.durationS, 0.0)
        assertEquals(com.onpath.app.data.Geo.length(pts), row.distanceM, 1.0)
        assertTrue("the routed line", line.size > 3 && pts.first() == a && pts.last() == c)
        assertEquals(com.onpath.app.routing.RouteSummary.rating(Activity.MTB, row.distanceM, 642.0, pts).level.name, row.difficulty)
        assertEquals("the router was asked for the stored activity and level", Activity.MTB, app.engine.calls.first().activity)
        assertEquals(4, app.engine.calls.first().sac)
        assertFalse("the cached list time of the old line is gone", app.planRoute.plannedTimes.containsKey(t.id))
        assertEquals("the user's own prefs are not touched", Activity.BIKE_TOURING, app.prefs.activity)
    }

    /** The open plan was edited: its saved points are the old ones, its points the new – the link follows with the SAVED points. */
    @Test fun theLinkFollowsTheNewNameWithTheSavedPointsNotTheEditedOnes() {
        val t = fromBrowser()
        val app = rig { open(t) }
        app.plan.edit { add(GeoPoint(46.72, 11.08)) }
        db.rename(t.id, "Alm neu")
        app.slice.followSyncedSavedPlan(); app.settle()
        assertEquals("main: saved Alm neu 1 3", app.said("saved").single())
        assertEquals(3, app.plan.savedPoints!!.size); assertEquals(4, app.plan.points.size)
    }

    /** An edit of the open plan neither cancels nor changes the routing of a browser plan (it is guarded by the stored stops only). */
    @Test fun editingTheOpenPlanWhileABrowserPlanIsRoutedDoesNotCancelOrChangeIt() {
        val other = fromBrowser("other", uuid = "u-other")
        val mine = fromBrowser("mine", uuid = "u-mine")
        val app = rig { open(mine) }
        app.slice.routeSyncedPlans(listOf(other.id))
        app.plan.edit { add(GeoPoint(46.72, 11.08)) }
        app.settle()
        assertEquals(listOf("main: backup ${other.id}"), app.said("backup"))
        assertEquals(4, app.plan.points.size)
        assertEquals(mine.id, app.plan.linkId)
        assertEquals(3, db.loadSegments(mine.id).single().size)
    }

    // ---------------- opening a synced plan ----------------

    @Test fun openingASyncedPlanRoutedElsewhereShowsTheGreyLineAndChecksItsTiles() {
        val t = fromBrowser()
        val app = rig { open(t); openedAnswer = SyncRouting.Opened(otherRouting = true, refresh = listOf("E10_N45")); planRoute.syncRoutingNote = "old" }
        app.slice.syncRoutingCheck(t, app.plan.points.toList())
        assertNull("the old note goes at once, before the check runs", app.planRoute.syncRoutingNote)
        app.settle()
        assertEquals(listOf("io: opened uuid-Alm 3", "main: tiles [E10_N45] (opened a synced plan)"), app.log)
        assertEquals(Txt.s(Res.string.sync_routing_other), app.planRoute.syncRoutingNote)
        assertTrue(problems.said("sync").any { it.contains("another routing fingerprint") })
    }

    @Test fun openingASyncedPlanRoutedTheSameWayAsksOnlyForTheTiles() {
        val t = fromBrowser()
        val app = rig { open(t); openedAnswer = SyncRouting.Opened(otherRouting = false, refresh = listOf("E10_N45")) }
        app.slice.syncRoutingCheck(t, app.plan.points.toList()); app.settle()
        assertNull(app.planRoute.syncRoutingNote)
        assertEquals(1, app.said("tiles").size)
    }

    @Test fun nothingToCheckNothingAsked() {
        val t = fromBrowser()
        val app = rig { open(t); openedAnswer = SyncRouting.Opened(otherRouting = false, refresh = emptyList()) }
        app.slice.syncRoutingCheck(t, app.plan.points.toList()); app.settle()
        assertEquals(listOf("io: opened uuid-Alm 3"), app.log)
        assertNull(app.planRoute.syncRoutingNote)
    }

    @Test fun aPlanThatIsNoSyncedPlanAsksNothing() {
        val t = fromBrowser()
        val app = rig { open(t); openedAnswer = null }
        app.slice.syncRoutingCheck(t, app.plan.points.toList()); app.settle()
        assertEquals(listOf("io: opened uuid-Alm 3"), app.log)
        assertNull(app.planRoute.syncRoutingNote)
        // a route without a uuid never reaches the sync files
        val own = fromBrowser("own", uuid = "")
        app.log.clear()
        app.slice.syncRoutingCheck(own, app.plan.points.toList()); app.settle()
        assertTrue(app.log.isEmpty())
    }

    @Test fun anAnswerForAnEditedPlanOrForAnotherPlanIsDropped() {
        val t = fromBrowser()
        val app = rig { open(t); openedAnswer = SyncRouting.Opened(otherRouting = true, refresh = listOf("E10_N45")) }
        val pts = app.plan.points.toList()
        // the user edits the plan while the sync files are read
        app.slice.syncRoutingCheck(t, pts)
        app.plan.edit { add(GeoPoint(46.72, 11.08)) }
        app.settle()
        assertNull(app.planRoute.syncRoutingNote); assertTrue(app.said("tiles").isEmpty())
        // another saved route is the plan by then
        app.plan.edit { removeAt(lastIndex) }
        app.slice.syncRoutingCheck(t, pts)
        app.plan.setSaved("Other", 77, pts)
        app.settle()
        assertNull(app.planRoute.syncRoutingNote); assertTrue(app.said("tiles").isEmpty())
    }

    @Test fun anUnreadableSyncFolderIsAWarningAndNoNote() {
        val t = fromBrowser()
        val app = rig { open(t); openedFails = true }
        app.slice.syncRoutingCheck(t, app.plan.points.toList()); app.settle()
        assertTrue(problems.said("sync").any { it.contains("Routing of the opened synced plan not compared") })
        assertNull(app.planRoute.syncRoutingNote)
        assertTrue(app.said("tiles").isEmpty())
    }

    // ---------------- the open plan follows its saved route ----------------

    @Test fun aRouteRenamedInTheBrowserRenamesSavedAsAndKeepsTheSavedPoints() {
        val t = fromBrowser()
        val app = rig { open(t) }
        db.rename(t.id, "Alm neu")
        app.slice.followSyncedSavedPlan(); app.settle()
        assertEquals(listOf("io: store.getTour 1", "main: saved Alm neu 1 3"), app.log)
        assertEquals("Alm neu", app.plan.linkName); assertEquals(1L, app.plan.linkId)
        assertEquals(3, app.plan.savedPoints!!.size)
    }

    @Test fun aRouteDeletedInTheBrowserUnlinksThePlanButKeepsItsPoints() {
        val t = fromBrowser()
        val app = rig { open(t) }
        db.setDeleted(t.id, 9_000L)
        app.slice.followSyncedSavedPlan(); app.settle()
        assertEquals(listOf("io: store.getTour 1", "main: saved null -1 null"), app.log)
        assertEquals(-1L, app.plan.linkId); assertNull(app.plan.linkName); assertNull(app.plan.savedPoints)
        assertEquals("the user's plan stays on the map", 3, app.plan.points.size)
    }

    @Test fun aRouteGoneForGoodUnlinksToo() {
        val t = fromBrowser()
        val app = rig { open(t) }
        db.deleteTour(t.id)
        app.slice.followSyncedSavedPlan(); app.settle()
        assertEquals(-1L, app.plan.linkId)
    }

    @Test fun anUnchangedRouteIsLeftAlone() {
        val t = fromBrowser()
        val app = rig { open(t) }
        app.slice.followSyncedSavedPlan(); app.settle()
        assertEquals(listOf("io: store.getTour 1"), app.log)
    }

    @Test fun aDatabaseErrorIsNotADeleteThePlanStaysLinked() {
        val t = fromBrowser()
        val app = rig { open(t); failRead = { true } }
        app.slice.followSyncedSavedPlan(); app.settle()
        assertEquals(1L, app.plan.linkId); assertEquals("Alm", app.plan.linkName)
        assertTrue(app.said("saved").isEmpty())
        assertTrue(problems.said("db").any { it.contains("plan stays linked") })
    }

    @Test fun aPlanThatIsLinkedToNothingAsksNothing() {
        val app = rig()
        app.slice.followSyncedSavedPlan(); app.settle()
        assertTrue(app.log.isEmpty())
    }

    @Test fun anotherPlanByTheTimeTheAnswerComesIsLeftAlone() {
        val t = fromBrowser()
        val app = rig { open(t) }
        db.rename(t.id, "Alm neu")
        app.slice.followSyncedSavedPlan()
        app.plan.setSaved("Other", 77, app.plan.points.toList()) // the user opened another route meanwhile
        app.settle()
        assertTrue(app.said("saved").isEmpty())
        assertEquals("Other", app.plan.linkName)
    }

    // ---------------- a pass's report, through the real driver ----------------

    private class Engine : SyncEngine {
        override val state: StateFlow<SyncState> = MutableStateFlow(SyncState(paired = true))
        override val marks: StateFlow<Map<String, SyncMark>> = MutableStateFlow(emptyMap())
        override fun loadMarks() {}
        override fun markSeen(uuid: String) {}
        override fun start() {}
        override fun quiet() = false
        override fun busy() = false
        override fun request(why: String, manual: Boolean) {}
        override fun poll(why: String) = true
    }

    /** The driver wired as the view model wires it: `toursChanged` → the list, the open plan follows, the plans are routed. */
    private fun Rig.driver(): SyncDriver = SyncDriver(Engine(), object : SyncHost {
        override val screen = emptyFlow<Pair<Boolean, Boolean>>()
        override val shown = emptyFlow<com.onpath.app.plansync.SyncPoll.Shown>()
        override fun ended() { log += "$where: ended" }
        override fun placesChanged() { log += "$where: places" }
        override fun toursChanged(toRoute: List<Long>) { host.refreshTours(); slice.followSyncedSavedPlan(); slice.routeSyncedPlans(toRoute) }
        override fun newerData(tiles: List<String>) { log += "$where: newer $tiles" }
    }, emptyFlow(), { false }, scope, io, clock = { 0L })

    private fun done(toRoute: List<Long> = emptyList(), changed: Boolean = false) =
        SyncReport(false, SyncPassResult(SyncRound.Outcome.Done(1, 0, 0), toRoute = toRoute, toursChanged = changed))

    @Test fun aChangedRouteWhileTheSameOneIsOpenAndEditedHereFollowsItsNameKeepsTheEditAndIsRouted() {
        val t = fromBrowser()
        val app = rig { open(t) }
        app.plan.edit { add(GeoPoint(46.72, 11.08)) } // the user's edit on the phone
        // the pass: the browser renamed the route and moved its middle point; the pass stored both (as PlanSyncPass does)
        db.rename(t.id, "Alm neu")
        db.replacePlanned(t.id, "HIKE", 2, 0.0, 0.0, 0.0, 0.0, listOf(listOf(TrackPoint(a.lat, a.lon, 0.0), TrackPoint(c.lat, c.lon, 0.0))),
            listOf(GpxWaypoint(a.lat, a.lon, null), GpxWaypoint(c.lat, c.lon, null)))
        app.driver().onReport(done(toRoute = listOf(t.id), changed = true))
        app.settle()
        assertEquals(1, app.said("refreshTours").size - 1) // once on report, once after the routing
        // what wins: the user's edit stays on the map; the link follows the new name; the saved points stay the old ones, so
        // the plan counts as unsaved and Save would ask (SaveFlowTest: sync changed the stored route -> a copy)
        assertEquals(4, app.plan.points.size)
        assertEquals("Alm neu", app.plan.linkName)
        assertEquals(3, app.plan.savedPoints!!.size)
        assertTrue(app.plan.unsaved)
        // the changed route itself is routed with the browser's points and stored with a real line
        assertEquals(listOf("main: backup 1"), app.said("backup"))
        assertEquals(listOf(a, c), db.getTour(t.id)!!.waypoints.map { GeoPoint(it.lat, it.lon) })
        assertTrue(db.loadSegments(t.id).single().size > 2)
    }

    @Test fun aRouteDeletedOnTheOtherSideIsNotRoutedAndTheOpenPlanLetsGo() {
        val t = fromBrowser()
        val app = rig { open(t) }
        db.setDeleted(t.id, 9_000L)
        app.driver().onReport(done(toRoute = listOf(t.id), changed = true))
        app.settle()
        assertTrue("no routing for a route in the bin", app.routerThreads().isEmpty())
        assertTrue(app.said("backup").isEmpty())
        assertEquals(-1L, app.plan.linkId)
        assertEquals(3, app.plan.points.size)
        assertEquals(9_000L, db.getTour(t.id)!!.deletedMs)
    }

    @Test fun aPassThatBroughtNothingTouchesNothing() {
        val t = fromBrowser()
        val app = rig { open(t) }
        val d = app.driver()
        d.onReport(done())
        d.onReport(SyncReport(false, SyncPassResult(SyncRound.Outcome.Offline)))
        d.onReport(SyncReport(false, null, error = true))
        app.settle()
        assertTrue(app.log.isEmpty())
    }

    @Test fun thePairingEndedAndKeptPlacesAndNewerDataAreHandedOnButNothingIsRouted() {
        val app = rig()
        val d = app.driver()
        d.onReport(SyncReport(false, SyncPassResult(SyncRound.Outcome.Ended, placesChanged = true, newerData = listOf("E10_N45")), ended = true))
        app.settle()
        assertEquals(listOf("test: ended", "test: places", "test: newer [E10_N45]"), app.log)
    }
}
