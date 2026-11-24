package com.onpath.app.ui.routes

import com.onpath.app.data.MultiDay
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * C18: what the Save flow reads from the app on the caller's thread before its first write (the stops, the dated name) may
 * throw – like the whole flow once did inside its coroutine, that is reported under "app" and the Save guard is released:
 * no exception reaches the tap, and the next Save works.
 */
class SaveGuardTest {
    @get:Rule val composeState = OwnComposeSnapshot()
    @get:Rule val problems = ProblemsRule()

    private val db = JdbcTours()
    @After fun close() = db.close()

    private val a = GeoPoint(46.680, 11.140)
    private val b = GeoPoint(46.700, 11.120)

    private class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
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
    private val saveStore = object : SaveStore {
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

    private val plan = PlanDocument(MemoryPlanStore())
    private val routes = RoutesState(routesStore, clock = { 5_000L })
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private var stopsFail = 0

    private val recentHost = object : RecentPlansHost {
        override fun updateDraft(id: Long, row: RecentPlans.DraftRow) = false
        override fun insertDraft(row: RecentPlans.DraftRow) = 0L
        override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) {}
        override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String? = null
        override fun tourChanged(id: Long) {}
        override fun purged(uuid: String) {}
        override fun syncChanged(why: String) {}
        override fun refreshTours() {}
        override fun problem(text: String) {}
        override var trimmed = false
    }

    private val host = object : SaveHost {
        override val route: PlannedRoute? = null
        override val activity: Activity get() = Activity.HIKE
        override val sac: Int get() = 2
        override fun stops(points: List<GeoPoint>): List<GpxWaypoint> {
            if (stopsFail-- > 0) error("stops broke")
            return points.map { GpxWaypoint(it.lat, it.lon, null) }
        }
        override fun difficulty(route: PlannedRoute?, activity: Activity): String? = null
        override fun days(points: List<GeoPoint>): MultiDay? = null
        override fun keepWhole() = false
        override fun overwriteWhole(name: String, then: (() -> Unit)?) = false
        override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String? = null
        override fun datedName() = "Route 31 Oct, 10:12"
        override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) {}
        override fun forgetTimes(id: Long) {}
        override fun copyTimes(from: Long, to: Long, distanceM: Double) {}
        override fun tourChanged(id: Long) {}
        override fun syncChanged(why: String) {}
        override fun refreshTours() { routes.show(routes.load()) }
        override fun saved(name: String?, id: Long, points: List<GeoPoint>?) { plan.setSaved(name, id, points) }
        override fun shownRenamed(id: Long, name: String) {}
        override fun say(text: String, kind: NoticeKind, place: NoticePlace) {}
    }

    private val save = SaveSlice(plan, routes, RecentPlans(routes, routesStore, recentHost, scope, Dispatchers.Unconfined), saveStore, host, scope, Dispatchers.Unconfined)

    private fun planned() = db.listTours().filter { it.kind == TourKind.PLANNED && !it.draft }

    @Test fun aReadThatThrowsBeforeTheFirstWriteIsReportedAndTheNextSaveWorks() {
        plan.edit { clear(); addAll(listOf(a, b)) }
        stopsFail = 1
        save.saveRoute("first") // no exception reaches the caller
        assertTrue("nothing stored", planned().isEmpty())
        assertTrue("reported under app: ${problems.said()}", problems.said("app").any { it.contains("saveRoute failed") })
        save.saveRoute("second") // the guard was released
        assertEquals(listOf("second"), planned().map { it.name })
    }
}
