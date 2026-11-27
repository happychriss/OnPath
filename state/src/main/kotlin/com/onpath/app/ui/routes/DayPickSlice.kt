package com.onpath.app.ui.routes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.plan.MultiDaySlice
import com.onpath.app.ui.plan.PlanRouteSlice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * My routes' day sheet of a saved multi-day route (owner 2026-11-27, "ja"): a tap on such a card opens it instead of the
 * overview ([open]) – the days along the route's stored line ([DayPick]), the days ridden ticked off, the next open day
 * highlighted; one tap on a day loads exactly that day as the plan ([ride]: the route is opened as the plan as My routes
 * always does – the plan on the map goes under Zuletzt – then [MultiDaySlice.rideDayAlong] makes the day the plan, as
 * "Tag n fahren"); › opens the day's own overview ([detail]). Never during a tour (the sheet shows, nothing loads).
 */
class DayPickSlice(
    private val routes: RoutesState,
    private val store: ToursStore,
    private val flow: RoutesSlice,
    private val multiDay: MultiDaySlice,
    private val planRoute: PlanRouteSlice,
    private val touring: () -> Boolean,
    /** No stored line / days not worked out: the route opens the normal way (as a tap did before). */
    private val openWhole: (Tour) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val default: CoroutineDispatcher,
) {
    /** The sheet as worked out at [open] (rows without the ridden marks – [ui] adds them from the list as it is now). */
    private var sheet by mutableStateOf<DayPickUi?>(null)
    /** The day whose own overview shows over the sheet (›); null = none. */
    var detail by mutableStateOf<Int?>(null)
    private var segs: List<List<TrackPoint>> = emptyList()
    /** The days' ends of the open sheet ([DayPick.dayEnds]): a ride counts only where they still match. */
    private var ends: Map<Int, Pair<GeoPoint, GeoPoint>> = emptyMap()
    private var job: Job? = null

    /** The sheet with the days ridden and the next open day as My routes' list holds them now (a tour put in the bin loses its ✓). */
    val ui: DayPickUi? get() = sheet?.let { s ->
        val rode = DayPick.ridden(s.tour.uuid, routes.tours, ends)
        s.copy(rows = s.rows.map { it.copy(riddenMs = rode[it.n]) }, next = DayPick.next(s.rows.size, rode.keys))
    }

    /** A day can be loaded now (no tour records). */
    val canRide: Boolean get() = !touring()

    /** The card of route [uuid] (or Gemacht's "Tag 3 von …") tapped: the sheet, once its days are worked out. */
    fun open(uuid: String) {
        val t = DayPick.route(uuid, routes.tours) ?: return
        job?.cancel()
        Problems.trace("routes", "Day sheet of the multi-day route \"${t.name}\" opened")
        job = scope.launchSafe("app", "dayPickOpen") {
            val s = withContext(io) { store.loadSegments(t.id) }
            val line = s.flatten().map { it.toGeo() }
            val act = planRoute.activityOrNull(t.activity) ?: planRoute.activity
            val f = planRoute.planFactors(act)
            val days = withContext(default) {
                runCatching { DayPick.days(t, line, act, f) }.onFailure { Problems.warn("routes", "Days of \"${t.name}\" not worked out – opened as a whole route", it) }.getOrNull()
            }
            if (days == null) { openWhole(t); return@launchSafe }
            segs = s
            detail = null
            val rows = DayPick.rows(t, days.first, emptyList())
            ends = DayPick.dayEnds(line, rows)
            sheet = DayPickUi(t, line, rows, null)
        }
    }

    fun close() { job?.cancel(); sheet = null; detail = null }

    /** One tap on day [n]: the route becomes the plan and day [n] of it the plan (ready for "Los geht's"). */
    fun ride(n: Int) {
        val s = sheet ?: return
        if (touring()) return
        val line = s.line; val lineOf = segs
        close()
        Problems.trace("routes", "Day sheet: day $n of \"${s.tour.name}\" loaded as the plan")
        flow.openTour(s.tour, lineOf = lineOf, planned = {
            scope.launchSafe("app", "dayPickRide") {
                if (!multiDay.rideDayAlong(line, n)) Problems.warn("routes", "Day $n of \"${s.tour.name}\" not taken – the whole route is the plan")
            }
        })
    }

    /** The stretch of the stored line day [row] covers (its own overview). */
    fun lineOf(row: DayPickRow): List<GeoPoint> {
        val l = sheet?.line ?: return emptyList()
        if (l.isEmpty()) return l
        val a = row.fromLine.coerceIn(0, l.lastIndex); val b = row.toLine.coerceIn(a, l.lastIndex)
        return l.subList(a, b + 1)
    }
}
