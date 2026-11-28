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
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.daypick_days_failed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * My routes' day sheet of a saved multi-day route (owner 2026-11-27, "ja"; MD-2 2026-11-28): a tap on such a card opens it
 * ([open]) – the days along the route's stored line ([DayPick]), the days done ticked off, the days begun marked, the next open
 * day highlighted; one tap on a day loads exactly that day as the plan ([ride]: worked out along the stored line by
 * [MultiDaySlice.prepareDay], opened as a route as My routes always does – the plan on the map goes under Zuletzt – and then
 * followed, [MultiDaySlice.followDay]); never the whole trip as the plan (MD-0). › opens the day's own overview ([detail]).
 * Never during a tour (the sheet shows, nothing loads). Days that cannot be worked out: a plain error line in My routes ([problem]).
 */
class DayPickSlice(
    private val routes: RoutesState,
    private val store: ToursStore,
    private val flow: RoutesSlice,
    private val multiDay: MultiDaySlice,
    private val planRoute: PlanRouteSlice,
    private val touring: () -> Boolean,
    /** An error line in My routes ([daypick_days_failed]): the days of a route could not be worked out. */
    private val problem: (String) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val default: CoroutineDispatcher,
) {
    /** The sheet as worked out at [open] (rows without the ridden marks – [ui] adds them from the list as it is now). */
    private var sheet by mutableStateOf<DayPickUi?>(null)
    /** The day whose own overview shows over the sheet (›); null = none. */
    var detail by mutableStateOf<Int?>(null)
    /** The days' ends of the open sheet ([DayPick.dayEnds]): a ride counts only where they still match. */
    private var ends: Map<Int, Pair<GeoPoint, GeoPoint>> = emptyMap()
    private var job: Job? = null

    /** The sheet with the days done / begun and the next open day as My routes' list holds them now (a tour put in the bin loses its ✓). */
    val ui: DayPickUi? get() = sheet?.let { s ->
        val rode = DayPick.ridden(s.tour.uuid, routes.tours, ends)
        val began = DayPick.started(s.tour.uuid, routes.tours, ends)
        s.copy(rows = s.rows.map { it.copy(riddenMs = rode[it.n], startedMs = began[it.n]) }, next = DayPick.next(s.rows.size, rode.keys))
    }

    /** A day can be loaded now (no tour records). */
    val canRide: Boolean get() = !touring()

    /** The card of route [uuid] (or Gemacht's "Tag 3 von …") tapped: the sheet, once its days are worked out. */
    fun open(uuid: String) { DayPick.route(uuid, routes.tours)?.let(::open) }

    /**
     * The day sheet of route [id] ("‹ Alle Tage" from its day in the plan, a multi-day route opened any other way): from My
     * routes' list, else read from the database.
     */
    fun show(id: Long) {
        routes.tours.firstOrNull { it.id == id && it.days != null && it.deletedMs == null }?.let { open(it); return }
        scope.launchSafe("app", "dayPickShow") {
            val t = withContext(io) {
                runCatching { store.getTour(id) }.onFailure { Problems.warn("routes", "Multi-day route $id not read – no day sheet", it) }.getOrNull()
            } ?: return@launchSafe
            if (t.days != null && t.deletedMs == null) open(t)
        }
    }

    /** The sheet of multi-day route [t], once its days are worked out along its stored line. */
    fun open(t: Tour) {
        job?.cancel()
        Problems.trace("routes", "Day sheet of the multi-day route \"${t.name}\" opened")
        job = scope.launchSafe("app", "dayPickOpen") {
            val line = withContext(io) { store.loadSegments(t.id) }.flatten().map { it.toGeo() }
            val act = planRoute.activityOrNull(t.activity) ?: planRoute.activity
            val f = planRoute.planFactors(act)
            val days = withContext(default) {
                runCatching { DayPick.days(t, line, act, f) }.onFailure { Problems.warn("routes", "Days of \"${t.name}\" not worked out – said in My routes", it) }.getOrNull()
            }
            if (days == null) { failed(t, "days not worked out"); return@launchSafe }
            detail = null
            val rows = DayPick.rows(t, days.first, emptyList())
            ends = DayPick.dayEnds(line, rows)
            sheet = DayPickUi(t, line, rows, null)
        }
    }

    /** Route [id] got its routed line (a plan link's trip was saved with its points as the line): its open sheet is worked out again. */
    fun routed(id: Long) {
        if (sheet?.tour?.id != id) return
        scope.launchSafe("app", "dayPickRouted") {
            val t = withContext(io) {
                runCatching { store.getTour(id) }.onFailure { Problems.warn("routes", "Routed multi-day route $id not read – the sheet keeps its first line", it) }.getOrNull()
            } ?: return@launchSafe
            if (sheet?.tour?.id == id && t.days != null && t.deletedMs == null) open(t)
        }
    }

    private fun failed(t: Tour, why: String) {
        Problems.warn("routes", "Multi-day route \"${t.name}\": $why – no day loaded, never the whole trip as the plan")
        problem(Txt.s(Res.string.daypick_days_failed))
    }

    fun close() { job?.cancel(); sheet = null; detail = null }

    /** One tap on day [n]: exactly that day becomes the plan (ready for "Los geht's"), tied to its trip (MD-2). */
    fun ride(n: Int) {
        val s = sheet ?: return
        if (touring()) return
        val line = s.line
        close()
        Problems.trace("routes", "Day sheet: day $n of \"${s.tour.name}\" loaded as the plan")
        job = scope.launchSafe("app", "dayPickRide") {
            val day = multiDay.prepareDay(s.tour, line, n) ?: run { failed(s.tour, "day $n not worked out"); return@launchSafe }
            flow.openTour(day.standIn, lineOf = listOf(day.dayLine.map { TrackPoint(it.lat, it.lon, it.ele) }), planned = { multiDay.followDay(day) })
        }
    }

    /** The stretch of the stored line day [row] covers (its own overview). */
    fun lineOf(row: DayPickRow): List<GeoPoint> {
        val l = sheet?.line ?: return emptyList()
        if (l.isEmpty()) return l
        val a = row.fromLine.coerceIn(0, l.lastIndex); val b = row.toLine.coerceIn(a, l.lastIndex)
        return l.subList(a, b + 1)
    }
}
