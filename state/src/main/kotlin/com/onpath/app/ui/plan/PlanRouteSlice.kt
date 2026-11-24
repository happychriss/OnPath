package com.onpath.app.ui.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.AppSettings
import com.onpath.app.data.LegFiles
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.RouteSpot
import com.onpath.app.domain.RouteWarnings
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.domain.plan.RunCause
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.time.PlanTimesStore
import com.onpath.app.time.TourTimes
import com.onpath.app.ui.TimeDetailsUi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/**
 * The routing engine and where it comes from, as [PlanRouteSlice] asks for it (rebuild phase 2 piece 8). The app implements
 * it over `EngineProvider` / `RoutingFingerprint` / the routing data store (all Android-bound); tests pass a fake router.
 */
interface RouterPort {
    /** A new engine (the real offline router, or the straight-line stand-in when that cannot start: [isRealRouter] says which). */
    fun create(): RoutingEngine
    /** True when the engine [create] made last is the real offline router. */
    val isRealRouter: Boolean
    /** The routing fingerprint (BRouter, profiles, PLANNER_VERSION); may throw. */
    fun fingerprint(): String
    /** The folder of the stored legs ([LegFiles]). */
    val legsDir: File
    /** The version of one routing data part (a stored leg counts only with the same one). */
    fun partVersion(part: Rd5Parts.Part): String
}

/** The stored model times, as [PlanRouteSlice] reads and writes them (blocking, may throw; the app implements it over the time stores + TourDb). */
interface TimesStore {
    /** The stored points of tour [id]. */
    fun loadSegments(id: Long): List<List<TrackPoint>>
    /** The time groups stored with saved planned route [id] for [distanceM]; null = none. */
    fun planGroups(id: Long, distanceM: Double): DoubleArray?
    fun putPlanGroups(id: Long, distanceM: Double, groups: DoubleArray)
    /** The service's live summary of finished tour [id]; null = none stored. */
    fun tourTimes(id: Long): TourTimes?
}

/** What the slice remembers of the user's choice across app starts (the app: [com.onpath.app.data.Prefs]). */
interface PlanRoutePrefs {
    var activity: Activity
    var sac: Int
}

/** What [PlanRouteSlice] reads of / asks from the rest of the app (main thread). */
interface PlanRouteHost {
    val settings: AppSettings
    /** The plan's route (the run's result; null = none yet); [PlanRouteSlice.retime] sets a re-timed one. */
    var route: PlannedRoute?
    /** The route is being calculated. */
    val routing: Boolean
    /** The question "Plan it anyway?" is open: its estimated duration, else null. */
    val longRouteAsk: Double?
    val mode: AppMode
    /** A tour is being recorded. */
    val touring: Boolean
    /** Plan the points again (the run; piece 9). */
    fun recalc()
    /** Feeds one event to the route run (its effects are not used by the slice). */
    fun routeEvent(e: RouteRun.Event)
    fun refreshTours()
    /** "Activity set to … from the file" is over once the activity / T level changes again. */
    fun clearShownNote()
    /** The map shows all [points] and stops following. */
    fun showWhole(points: List<GeoPoint>)
}

/** The four single routing threads; the slice's owner closes them ([PlanRouteSlice.close]). Each does one thing at a time. */
class RoutingThreads(
    /** The plan run (nobody else's work queues behind it). */
    val routing: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    /**
     * Routing nobody waits for at the screen (browser plans after a pairing, "Plan a route from it"): its own thread, so
     * the user's own plan never queues behind ten of them (review routing H1). BRouter itself runs one search at a time
     * (BRouterEngine.LOCK), so a plan edit waits at most for one search of this work, not all of it.
     */
    val background: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    /**
     * The activity check's one row request (Navigate → activity icon): its own thread, so a plan edit never queues behind
     * it (field bug 2026-11-07: six 15 s checks on [routing] held the plan up to ~90 s). BRouter's LOCK still
     * runs one search at a time; a new tap or closing the sheet terminates the check's search.
     */
    val check: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    /**
     * A hold beside the plan measured on the paths (HoldPaths, owner 2026-11-17 „Always real paths“): its own thread, so
     * the measure never queues behind a plan run or background work (BRouter's LOCK still runs one search at a time – a
     * running plan run makes the measure wait, at most its time limit, then the straight lines decide).
     */
    val hold: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
) {
    fun close() {
        routing.close()
        background.close()
        check.close()
        hold.close()
    }
}

/**
 * The plan's route, first half (rebuild phase 2 piece 8; the run itself – `recalc`, `perform`, long / slow / cancel,
 * routing data arrived – is piece 9 and still in the view model): the routing engine with its leg cache and threads, the
 * activity / T level the route is planned for, the model time estimate, and the route warnings. Constructed with the
 * view model (no work: nothing runs before the first call); the view model forwards its old member names to it.
 */
class PlanRouteSlice(
    private val prefs: PlanRoutePrefs,
    private val router: RouterPort,
    private val times: TimesStore,
    private val host: PlanRouteHost,
    val threads: RoutingThreads = RoutingThreads(),
) {
    // ---------------- activity ----------------

    // Every app start begins with the last chosen activity and hiking level (owner; fresh install Hike T2).
    var activity by mutableStateOf(prefs.activity)
    var sac by mutableStateOf(prefs.sac)

    /** What started the routing run: Cancel takes back only that ([RunCause]). */
    val runCauses = RunCause()

    /**
     * The activity chosen in the picker: the route is planned again; Cancel while it is calculated puts the previous
     * activity back. [userChange] false: taken over from elsewhere (the service switched, the switch sheet) – Cancel
     * then only stops.
     */
    fun selectActivity(a: Activity, userChange: Boolean = true) {
        noteActivityChange(own = userChange)
        if (userChange) runCauses.activity(activityCause()) else runCauses.other()
        activity = a; prefs.activity = a; host.recalc()
        host.clearShownNote()
    }

    /** A stored activity name; null for none or an unknown / old one (expected: the caller falls back). */
    fun activityOrNull(name: String?): Activity? = com.onpath.app.debug.enumOrNull<Activity>(name, "app")

    /** Cause of an activity / T level change; a change still being calculated keeps the activity from before it. */
    private fun activityCause(): PlanStatus.Cause =
        runCauses.activityChange(PlanStatus.Cause.ActivityChange(activity, sac), previousStillOpen = host.routing || host.longRouteAsk != null)

    /** T level from the activity picker (Hike): remembered, the route is planned again at once. */
    fun selectSac(level: Int) {
        val l = level.coerceIn(1, 5)
        if (l == sac) return
        if (activity == Activity.HIKE) { noteActivityChange(own = true); runCauses.activity(activityCause()) }
        sac = l; prefs.sac = l; if (activity == Activity.HIKE) host.recalc()
        host.clearShownNote()
    }

    // ---------------- engine, leg cache ----------------

    @Volatile var engine: RoutingEngine? = null

    /** The router (on the routing thread); only a real one is kept, so a failed start is retried next time. */
    @Synchronized // two routing threads ([RoutingThreads.background]): one engine
    fun routingEngine(): RoutingEngine =
        engine ?: router.create().also {
            // a new engine = new routing data (or a failed start): the legs of the old one are routed again
            if (router.isRealRouter) { engine = it; legCache.clear() }
        }

    /**
     * Legs of earlier plan runs (owner 2026-11-05, docs/design/long-bike-routes.md): an edit routes only the legs it
     * changed; the plan, the tour's follow line and browser plans share it. Cleared with the engine ([routingEngine]).
     */
    val legCache = LegCache(store = LegFiles(
        router.legsDir, fingerprint = { legFingerprint?.let { officialFingerprint(it, host.settings.officialRouteStrength) } },
        partVersion = { router.partVersion(it) }))

    /**
     * "Prefer official routes" (Settings → Map) routes differently: a stored leg counts only with the same strength. Off
     * keeps the plain fingerprint, so the legs stored before the setting existed stay valid.
     */
    internal fun officialFingerprint(fingerprint: String, strength: Int): String =
        if (strength <= 0) fingerprint else "$fingerprint|official=$strength"

    /** "Prefer official routes" changed: the legs in memory were routed with the old strength – the plan is routed again. */
    fun officialRoutesChanged() {
        legCache.clear()
        host.recalc()
    }

    /**
     * The routing fingerprint (BRouter, profiles, PLANNER_VERSION) the phone routes with – stored legs ([legCache]'s
     * store, data/LegFiles) count only with the same one. Read on a routing thread at the first leg.
     */
    private val legFingerprint: String? by lazy {
        try { router.fingerprint() } catch (e: Exception) {
            Problems.warn("routing", "Routing fingerprint not computed – stored legs are routed again", e); null
        }
    }

    // ---------------- time estimate (docs/plan-time-estimate.md) ----------------

    /** Model times of saved planned routes, by tour id: (factors, stored distance) they were computed for → seconds. */
    val plannedTimes = java.util.concurrent.ConcurrentHashMap<Long, Pair<Pair<com.onpath.app.time.Factors, Double>, Double>>()

    /** The pace setting for planning with [a] (learning per tour only: nothing from earlier tours). */
    fun planFactors(a: Activity = activity): com.onpath.app.time.Factors =
        com.onpath.app.time.TimeEstimate.planFactors(a, host.settings)

    /** Route sheet ⋯ → Time details (debug view of the estimate); null = closed. */
    var timeDetails by mutableStateOf<TimeDetailsUi?>(null)

    /** [technical]: Settings → Diagnostics → Developer tools (formulas, tables); else the plain lines of sheet ⋯. */
    fun openTimeDetails(technical: Boolean = false) {
        val r = host.route ?: return
        val pace = com.onpath.app.time.TimeEstimate.pace(activity, host.settings)
        timeDetails = TimeDetailsUi(
            com.onpath.app.time.TimeBreakdown.of(r.points, activity, planFactors()),
            pace.label, pace.factor, technical,
        )
    }

    /**
     * The plan's time from OnPath's own model (time/RouteTimes), not the router's: the same number as the route list
     * and Navigate "to go" at the start. Moving time, rounded for display.
     */
    fun withModelTime(r: PlannedRoute, a: Activity, f: com.onpath.app.time.Factors): PlannedRoute =
        com.onpath.app.routing.PlanResult.withModelTime(r, a, f)

    /**
     * The level of the plan sheet's difficulty badge for [r] ([com.onpath.app.routing.RouteSummary.rating] – the same call
     * as RouteBreakdown), stored with a saved route ([Tour.difficulty], owner 2026-11-12: for sorting by difficulty later).
     * Only here, while the line still has its way details; null without a line or when it cannot be computed.
     */
    fun difficultyOf(r: PlannedRoute?, a: Activity): String? = r?.let {
        runCatching { com.onpath.app.routing.RouteSummary.rating(a, it.distanceM, it.ascentM, it.points).level.name }
            .onFailure { e -> Problems.warn("plan", "Difficulty of a saved route not computed; stored as not known", e) }
            .getOrNull()
    }

    /** Times of a finished tour: the service's live summary, else recomputed from the stored points. */
    fun tourTimes(t: Tour): com.onpath.app.time.TourTimes? = try {
        val rides = t.rides // rides taken out (vehicle detection): moving time / breaks of what is left, not the service's live summary
        if (rides != null && rides.choice != com.onpath.app.rides.RideChoice.AS_RECORDED)
            com.onpath.app.time.TourTimes.of(t.durationS, com.onpath.app.time.BreakDetector.breaksIn(
                com.onpath.app.rides.RideView.keptSegments(times.loadSegments(t.id), rides)))
        else times.tourTimes(t.id)
            ?: com.onpath.app.time.TourTimes.of(t.durationS, com.onpath.app.time.BreakDetector.breaksIn(times.loadSegments(t.id)))
    } catch (e: Exception) {
        Problems.warn("time", "Times of a finished tour could not be computed; shown without", e)
        null
    }

    /** Pace or learned factors changed: new times for the plan and the route list (the route itself stays). */
    fun retime() {
        host.route?.let { host.route = withModelTime(it, activity, planFactors()) }
        host.refreshTours()
    }

    /** A planned route was saved as [id]: keep its model time per group (with way attributes) for the route list. */
    fun storePlanTimes(id: Long, r: PlannedRoute?, a: Activity) {
        if (r == null || r.points.size < 2) return
        try { times.putPlanGroups(id, r.distanceM, com.onpath.app.time.RouteTimes.of(r.points, a).groupTotals()) } catch (e: Exception) {
            Problems.warn("time", "Plan times not stored for a saved route; list time falls back to the stored points", e)
        }
    }

    /**
     * Planned routes and imported tracks without timestamps show the model time with the current pace (recomputed, the
     * stored time is ignored: one time model); recorded tours and timed imports as stored.
     */
    fun withModelTime(t: Tour): Tour {
        if (t.kind != TourKind.PLANNED && t.kind != TourKind.IMPORTED) return t
        val a = activityOrNull(t.activity) ?: Activity.HIKE
        val f = planFactors(a)
        val key = f to t.distanceM // an overwritten route has a new distance
        plannedTimes[t.id]?.takeIf { it.first == key }?.let { return t.copy(durationS = it.second) }
        if (t.kind == TourKind.IMPORTED) return try {
            val s = com.onpath.app.time.TimeEstimate.importedSeconds(times.loadSegments(t.id), a, f)
            plannedTimes[t.id] = key to s
            t.copy(durationS = s)
        } catch (e: Exception) {
            Problems.warn("time", "Model time of an imported track could not be computed; stored time shown", e)
            t
        }
        // stored at save with the way attributes; the stored points alone have none
        times.planGroups(t.id, t.distanceM)?.let { g ->
            val s = PlanTimesStore.seconds(g, f)
            plannedTimes[t.id] = key to s
            return t.copy(durationS = s)
        }
        return try {
            val pts = times.loadSegments(t.id).flatten().map { it.toGeo() }
            if (pts.size < 2) t else {
                val s = com.onpath.app.time.TimeEstimate.planSeconds(pts, a, f)
                plannedTimes[t.id] = key to s
                t.copy(durationS = s)
            }
        } catch (e: Exception) {
            Problems.warn("time", "Model time of a planned route could not be computed; stored time shown", e)
            t
        }
    }

    // ---------------- route warnings ----------------

    /**
     * "Route changed for gravel bike – please check": the activity / T level changed and the new route differs
     * noticeably ([RouteWarnings.changedNoticeably]). Stays until the amber line is tapped or the plan changes – never
     * dismissed by time.
     */
    var routeChangedNotice by mutableStateOf<String?>(null)
    /**
     * Grey INFO line in the plan sheet: the open plan came from the browser, routed there with another routing
     * fingerprint ("Planned in the browser with other routing – the route may differ slightly"). Nothing to do; it goes
     * with the next edit of the plan or another plan (docs/design/routing-fingerprint.md).
     */
    var syncRoutingNote by mutableStateOf<String?>(null)
    /** The route before the activity / T level change whose result is still pending (null = none). */
    var activityBefore: PlannedRoute? = null
    /** The list of spots (tap on the amber line) is open. */
    var spotsOpen by mutableStateOf(false)
    /** The piece of route of the spot tapped in that list, drawn amber on the map; null = none. */
    var spotLine by mutableStateOf<List<GeoPoint>?>(null)
    var spotsMemo: Pair<Triple<List<GeoPoint>, Activity, Int>, List<RouteSpot>>? = null

    /**
     * An activity / T level change: the route before it is kept for the comparison ("New route for … – check it") –
     * not for the user's [own] choice in Plan with no tour running (owner 2026-11-07: they changed it themselves).
     */
    fun noteActivityChange(own: Boolean) {
        routeChangedNotice = null
        if (!RouteWarnings.saysChanged(own, inPlan = host.mode == AppMode.PLAN, touring = host.touring)) {
            activityBefore = null; host.routeEvent(RouteRun.Event.CompareActivity(false)); return
        }
        if (activityBefore == null) activityBefore = host.route?.takeIf { !host.routing }
        host.routeEvent(RouteRun.Event.CompareActivity(activityBefore != null))
    }

    /** A plan edit (points changed): the "route changed" notice and its pending comparison go. */
    fun planChanged() {
        routeChangedNotice = null; activityBefore = null; host.routeEvent(RouteRun.Event.CompareActivity(false))
        syncRoutingNote = null // edited here: the route is the phone's own now
    }

    /** Spots worth knowing on the current route ([RouteWarnings.detect]); none while it is being calculated. */
    val routeSpots: List<RouteSpot>
        get() {
            val r = host.route?.takeIf { !host.routing } ?: return emptyList()
            val key = Triple(r.points, activity, sac)
            spotsMemo?.let { (k, v) -> if (k.first === key.first && k.second == key.second && k.third == key.third) return v }
            val v = try { RouteWarnings.detect(r.points, activity, sac) } catch (e: Exception) {
                Problems.warn("routing", "Route warnings could not be worked out; none shown", e); emptyList()
            }
            spotsMemo = key to v
            return v
        }

    /** The amber line of the plan sheet: spots on the way only (pushing, steps, above T, steep). */
    fun warningLineText(): String? = RouteWarnings.text(routeSpots, sac)

    /** How many hints the amber line sums up: the ⚠ count of the rolled-up sheet. */
    fun warningCount(): Int = RouteWarnings.count(routeSpots)

    /**
     * Grey INFO line "New route for … – check it": after an activity / T level change during a tour or from elsewhere
     * ([RouteWarnings.saysChanged]), or "Plan recalculated with newer map data – …".
     */
    fun routeChangedLineText(): String? = routeChangedNotice

    /** Tap on the "New route for …" line: it goes, the map shows the whole route. */
    fun tapRouteChangedLine() {
        routeChangedNotice = null
        val r = host.route ?: return
        host.showWhole(r.points)
    }

    /** Tap on the amber line: the notice goes; the list of spots opens (none: the map shows the whole route). */
    fun tapWarningLine() {
        routeChangedNotice = null
        if (routeSpots.isNotEmpty()) { spotsOpen = true; return }
        val r = host.route ?: return
        host.showWhole(r.points)
    }

    fun closeSpots() { spotsOpen = false }

    /** The view model's end: the routing threads stop with it. */
    fun close() = threads.close()
}
