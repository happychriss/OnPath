package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.ui.CoroutineBackground
import com.onpath.app.ui.CoroutineNameProposal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * What [RecentPlans] asks of the view model (the Android-bound and the plan-bound parts): the draft row's write, the
 * model times and the place name of a kept plan, and the side effects of a change in My routes (backup file, browser
 * sync, the list read again, the line at the top). The view model passes a small adapter; tests pass a fake.
 */
interface RecentPlansHost {
    /** Writes [row] as a plan under Zuletzt (TourDb.insertTour, kind PLANNED, draft); returns its id (blocking, may throw). */
    fun insertDraft(row: RecentPlans.DraftRow): Long
    /**
     * Writes [row] over the draft [id] – points, line, stats; its name and date stay (TourDb.replacePlanned). False when
     * the draft is gone (blocking, may throw).
     */
    fun updateDraft(id: Long, row: RecentPlans.DraftRow): Boolean
    /** Keeps the model times of the kept plan [id] for the route list (blocking; reports its own failures). */
    fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity)
    /** The place name proposed for a plan ("Algund → Meran"); null = none. */
    suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String?
    /** The tour [id] changed (saved, moved to / out of the recycle bin): its backup file follows. */
    fun tourChanged(id: Long)
    /** The tour with [uuid] is gone for good: its backup file too. */
    fun purged(uuid: String)
    /** Something the browser sync carries changed. */
    fun syncChanged(why: String)
    /** My routes' list is read again. */
    fun refreshTours()
    /** The line at the top of My routes ([text] = what failed). */
    fun problem(text: String)
    /** The one-plan rule's clean-up ran once ([RecentPlans.trimOnce]; a pref). */
    var trimmed: Boolean
}

/**
 * "Zuletzt" and the recycle bin as the app runs it: [RecentPlansState] with the coroutines – the work on [scope] (the view
 * model's), the blocking calls on [io], the place name from [RecentPlansHost.proposeName] waited for at most [nameWaitMs].
 * Name and constructor are the old ones (RecentPlansTest).
 */
class RecentPlans(
    routes: RoutesState,
    store: RoutesStore,
    host: RecentPlansHost,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    /** How long a kept plan waits for its place name (it has its date name meanwhile). */
    nameWaitMs: Long = NAME_WAIT_MS,
) : RecentPlansState(routes, store, HostAsPorts(host), CoroutineBackground(scope, io),
    CoroutineNameProposal(scope) { pts, r -> host.proposeName(pts, r) }, nameWaitMs) {
    /** A plan as it leaves the map: what [keep] writes. */
    class Leaving(
        val points: List<GeoPoint>,
        /** Never saved, or a saved plan changed since. */
        val unsaved: Boolean,
        /** The saved route / draft it belongs to; -1 = none. */
        val savedId: Long,
        val route: PlannedRoute?,
        val activity: Activity,
        val sac: Int,
        /** Its points as stored stops (with their place names). */
        val stops: List<GpxWaypoint>,
        val pins: Set<Int>,
        /** The date name it is kept under until a place name comes ("Route 26 Oct, 10:12"). */
        val dated: String,
        /** A multi-day tour's days (stage 4) – kept with it; null = a normal plan. */
        val days: com.onpath.app.data.MultiDay? = null,
        /** The difficulty level stored with it; asked only when a row is written. */
        val difficulty: () -> String?,
    )

    /** The row of a plan kept under Zuletzt ([RecentPlansHost.insertDraft]). */
    class DraftRow(
        val name: String, val activity: String?, val sac: Int,
        val distanceM: Double, val ascentM: Double, val descentM: Double, val durationS: Double,
        val segments: List<List<TrackPoint>>, val waypoints: List<GpxWaypoint>, val pinned: Set<Int>, val difficulty: String?,
        /** A multi-day tour's days; null = a normal plan. */
        val days: com.onpath.app.data.MultiDay? = null,
    )

    /** [RecentPlansState.keep] for a plan as [Leaving]. */
    fun keep(plan: Leaving, why: String): Long? =
        keep(LeavingPlan(plan.points, plan.unsaved, plan.savedId, plan.route, plan.activity, plan.sac, plan.stops, plan.pins,
            plan.dated, plan.days, plan.difficulty), why)

    companion object {
        /** How long a new route's name waits for its place proposal (it shows the date name meanwhile). */
        const val NAME_WAIT_MS = RecentPlansState.NAME_WAIT_MS
    }
}

/** [RecentPlansHost] as the [RecentPlansPorts] of [RecentPlansState] (the row converted field by field). */
private class HostAsPorts(private val host: RecentPlansHost) : RecentPlansPorts {
    private fun DraftRow.toApp() = RecentPlans.DraftRow(name, activity, sac, distanceM, ascentM, descentM, durationS,
        segments, waypoints, pinned, difficulty, days)

    override fun insertDraft(row: DraftRow): Long = host.insertDraft(row.toApp())
    override fun updateDraft(id: Long, row: DraftRow): Boolean = host.updateDraft(id, row.toApp())
    override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) = host.storeTimes(id, route, activity)
    override fun tourChanged(id: Long) = host.tourChanged(id)
    override fun purged(uuid: String) = host.purged(uuid)
    override fun syncChanged(why: String) = host.syncChanged(why)
    override fun refreshTours() = host.refreshTours()
    override fun problem(text: String) = host.problem(text)
    override var trimmed: Boolean
        get() = host.trimmed
        set(value) { host.trimmed = value }
}

