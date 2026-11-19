package com.onpath.app.ui.tour

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.navigation.FollowStore
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.app.recording.RecordingClient
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.ActivityCheckRules
import com.onpath.app.ui.ActivityCheckUi
import com.onpath.app.ui.ActivityRow
import com.onpath.app.ui.ActivityRowState
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The phone's position fix as the activity check asks for it (the app answers from the location tracker): a fix with its
 * altitude when it has one. Not [com.onpath.app.ui.Position]: that one falls back to the tour's last recorded point and has
 * no altitude and no wait.
 */
interface Fixes {
    /** The last fix (altitude when the fix has one); null = no fix yet. */
    fun now(): GeoPoint?
    /** Waits for the first fix (suspends until there is one). */
    suspend fun first(): GeoPoint
}

/** What [ActivityCheckSlice] reads of / asks from the rest of the app (all on the main thread, each at the point of the old code). */
interface ActivityCheckHost {
    /** The app's mode (Plan / Navigate). */
    val mode: AppMode
    /** `enter(NAVIGATE)`: the Take new route / Keep question is asked in Navigate. */
    fun enterNavigate()
    /** The followed line was taken from the plan ("Start navigation"). */
    val followFromPlan: Boolean
    /** How many points the plan has. */
    val planPoints: Int
    /** Feeds one event to the route run. */
    fun routeEvent(e: RouteRun.Event)
    /** The T level is remembered across app starts (`prefs.sac = level`). */
    fun rememberSac(level: Int)
}

/**
 * The activity check while navigating (rebuild phase 2 piece 16b; owner: a wrong activity is a disaster): the sheet
 * (Navigate → tap the activity icon), the one-row-at-a-time routing of the rest of the followed route, and what a found
 * switch does – on the route the dashed proposal and the service's question, never reached the plan planned again, free
 * recording the plain choice. Moved out of the view model as it was; the view model forwards its old names to it. It talks
 * to the service only through [recording] (the ONE client of the view model): the proposal file is written ([follow]) before
 * `proposeSwitch` is sent, with no suspension between the two (tour paper rules 1, 24); a stopped check changes nothing
 * (rule 23). The follow state (`syncNavActivity`, the baseline) is not part of it.
 */
class ActivityCheckSlice(
    private val planRoute: PlanRouteSlice,
    private val recording: RecordingClient,
    private val follow: FollowStore,
    private val rows: TourRows,
    private val fixes: Fixes,
    private val host: ActivityCheckHost,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val cpu: CoroutineDispatcher,
) {
    private val activity: Activity get() = planRoute.activity
    private val sac: Int get() = planRoute.sac
    private val checkRouting: CoroutineDispatcher get() = planRoute.threads.check

    // ---------------- activity check while navigating (owner: a wrong activity is a disaster) ----------------

    /** The check-and-switch sheet (Navigate → tap the activity icon); null = closed. Stays until a choice is made. */
    var activityCheck by mutableStateOf<ActivityCheckUi?>(null)
        private set
    private var checkJob: Job? = null
    private var checkCancel: com.onpath.app.routing.RouteCancel? = null
    private var checkSession: NavSession? = null
    private var checkRest: com.onpath.app.navigation.ActivityCheck.Rest? = null

    private fun sacFor(a: Activity) = if (a == Activity.HIKE) checkHikeSac ?: sac else checkSession?.sac ?: sac

    /** The T level the check sheet's Hike chips chose (K1); null = the remembered level. Cleared with the sheet. */
    private var checkHikeSac: Int? = null

    /**
     * Opens the sheet with every other activity, nothing routed yet (owner 2026-11-07: "erst dann, wenn ich wirklich eine
     * Routenart ausgewählt habe" – field bug tour 207: all six rows routed at once, 15 s each, from 700 km away). A tap on
     * a row routes that one ([chooseActivity]). Free recording: plain choices.
     */
    fun openActivityCheck() {
        closeActivityCheck()
        val nav = recording.state.value?.nav
        val session = nav?.session?.takeIf { it.active.size >= 2 }
        val cur = session?.activity ?: activity
        val curSac = session?.sac ?: sac
        val others = Activity.entries.filter { it != cur }
        checkSession = session
        if (session == null) {
            activityCheck = ActivityCheckUi(cur, curSac, others.map { ActivityRow(it, sacFor(it), ActivityRowState.Plain) },
                note = Txt.s(Res.string.msg_check_no_route_followed))
            return
        }
        // the position is needed only once the route was reached (never reached: from the route's start, ActivityCheck.rest)
        activityCheck = ActivityCheckUi(cur, curSac, others.map { ActivityRow(it, sacFor(it), ActivityRowState.Idle) },
            note = if (session.reached && fixes.now() == null) Txt.s(Res.string.msg_check_waiting_gps) else null)
    }

    /**
     * Routes the rest of the followed route for [a] at [level] – this row only (spinner on it); a running check of
     * another row is cancelled (its row is plain again). Found → [applySwitch] as a tap on a checked row; none → the
     * row says so and the sheet stays open. Runs on its own thread ([checkRouting]), so plan edits never queue behind it.
     */
    private fun startCheck(a: Activity, level: Int) {
        val session = checkSession ?: return
        val ui = activityCheck ?: return
        cancelCheck()
        val cancel = com.onpath.app.routing.RouteCancel()
        checkCancel = cancel
        activityCheck = ActivityCheckRules.checking(ui, a, level)
        Problems.trace("navigation", "Activity check: routing the rest for ${a.name}" + (if (a == Activity.HIKE) " T$level" else "") +
            (if (session.reached) " from the position" else " from the route's start (never reached)"))
        checkJob = scope.launchSafe("app", "activityCheck") {
            val rest = checkRest ?: run {
                val pos = if (!session.reached) null else {
                    fixes.now() ?: fixes.first()
                }
                val progress = recording.state.value?.nav?.progressM ?: 0.0
                withContext(cpu) {
                    runCatching { com.onpath.app.navigation.ActivityCheck.rest(session, progress, pos) }
                        .onFailure { Problems.warn("navigation", "Activity check: rest of the route could not be computed", it) }.getOrNull()
                }.also { checkRest = it }
            }
            if (!isActive || cancel.isCancelled) return@launchSafe
            if (rest == null) {
                updateCheck(null) { ActivityRowState.Failed(Txt.s(Res.string.msg_check_nothing_left)) }
                return@launchSafe
            }
            val st = checkRow(rest, a, level, cancel)
            if (!isActive || cancel.isCancelled) return@launchSafe
            if (checkCancel === cancel) checkCancel = null
            updateCheck(a) { st }
            if (st is ActivityRowState.Ready) applySwitch(st.fit)
        }
    }

    /** Stops a running row check (a new tap, Keep, Back): BRouter is terminated, nothing is applied. */
    private fun cancelCheck() {
        checkCancel?.cancel(); checkCancel = null
        checkJob?.cancel(); checkJob = null
    }

    /** How the rest of the tour fits [a] at T level [level] (routed offline on [checkRouting]). */
    private suspend fun checkRow(rest: com.onpath.app.navigation.ActivityCheck.Rest, a: Activity, level: Int,
                                 cancel: com.onpath.app.routing.RouteCancel): ActivityRowState = withContext(checkRouting) {
        try {
            val eng = planRoute.routingEngine()
            if (eng is com.onpath.app.routing.StraightLineEngine) {
                ActivityRowState.Failed(Txt.s(Res.string.msg_check_cannot_route))
            } else {
                val r = eng.route(rest.waypoints, a, level,
                    com.onpath.app.routing.RouteLimits(com.onpath.app.navigation.ActivityCheck.TIMEOUT_MS, cancel))
                if (r.points.size < 2) ActivityRowState.Failed(Txt.s(Res.string.msg_check_no_route))
                else ActivityRowState.Ready(com.onpath.app.navigation.ActivityCheck.evaluate(rest.line, a, level, r, planRoute.planFactors(a)))
            }
        } catch (e: Exception) {
            if (cancel.isCancelled) Problems.trace("routing", "Activity check for ${a.name} cancelled (a new tap or the sheet closed)")
            else Problems.warn("routing", "Activity check: routing for another activity failed", e)
            ActivityRowState.Failed(Txt.s(Res.string.msg_check_no_route))
        }
    }

    /**
     * The check sheet's T1–T5 chips under Hike (owner 2026-11-06, flow map K1: during a tour Hike could only be taken with
     * the remembered level). Free recording: Hike with that level at once, as in the plan's activity dialog. A followed
     * route: a chip is a tap on the Hike row at that level ([ActivityCheckRules.withHikeLevel]) – routed for it alone and
     * switched when found. Hiking already at that level: nothing to switch to (a running Hike check stops).
     */
    fun chooseHikeLevel(level: Int) {
        val ui = activityCheck ?: return
        val l = level.coerceIn(1, 5)
        if (ui.current == Activity.HIKE && l == ui.sac && ui.rows.none { it.activity == Activity.HIKE }) return
        Problems.trace("navigation", "Activity check: Hike T$l chosen (was ${if (ui.current == Activity.HIKE) "Hike T${ui.sac}" else ui.current.name})")
        val session = checkSession
        if (session == null) {
            closeActivityCheck()
            planRoute.selectActivity(Activity.HIKE, userChange = false)
            planRoute.selectSac(l)
            recording.state.value?.tourId?.let { id -> scope.launchSafe("app", "chooseHikeLevel", io) {
                runCatching { rows.updateActivity(id, Activity.HIKE.name, l) }
                    .onFailure { Problems.error("db", "Activity of the recorded tour not updated in the database", it) }
            } }
            return
        }
        checkHikeSac = l
        val hikingRunning = ui.rows.any { it.activity == Activity.HIKE && it.state == ActivityRowState.Checking }
        activityCheck = ActivityCheckRules.withHikeLevel(ui, l)
        if (activityCheck?.rows?.none { it.activity == Activity.HIKE } == true) { // hiking at that level already
            if (hikingRunning) cancelCheck()
            return
        }
        startCheck(Activity.HIKE, l)
    }

    /** Clears the note; [a] null = every row, else only that row. */
    private fun updateCheck(a: Activity?, f: (ActivityRowState) -> ActivityRowState) {
        val ui = activityCheck ?: return
        activityCheck = ui.copy(note = null, rows = ui.rows.map { if (a == null || it.activity == a) it.copy(state = f(it.state)) else it })
    }

    /**
     * A row was chosen: free recording → switch at once; navigating → that activity alone is routed ([startCheck]) and,
     * when found, switched ([applySwitch]). A row already checked switches at once.
     */
    fun chooseActivity(a: Activity) {
        val ui = activityCheck ?: return
        val row = ui.rows.firstOrNull { it.activity == a }?.takeIf { it.choosable } ?: return
        val st = row.state
        val session = checkSession
        if (st == ActivityRowState.Plain || session == null) {
            closeActivityCheck()
            planRoute.selectActivity(a, userChange = false)
            recording.state.value?.tourId?.let { id -> scope.launchSafe("app", "chooseActivity", io) {
                runCatching { rows.updateActivity(id, a.name, sacFor(a)) }
                    .onFailure { Problems.error("db", "Activity of the recorded tour not updated in the database", it) }
            } }
            return
        }
        if (st is ActivityRowState.Ready) applySwitch(st.fit) else startCheck(a, row.sac)
    }

    /**
     * A route was found for the chosen activity ([fit]): on the route → the rest of the tour for it becomes the dashed
     * proposal with the usual question (Take new route / Keep original), asked by the service. Never on the route yet and
     * following the plan → the whole plan is planned again for it, like the plan's activity picker ([switchPlanActivity]).
     */
    private fun applySwitch(fit: com.onpath.app.navigation.ActivityFit) {
        val session = checkSession ?: return
        val rest = checkRest
        val a = fit.activity
        closeActivityCheck()
        if (!session.reached && session.kind == NavKind.PLAN && host.followFromPlan && host.planPoints >= 2) {
            switchPlanActivity(a, fit.sac)
            return
        }
        val left = rest?.stops.orEmpty()
        // on the route the new line starts at the position (an extra first stop); never reached it starts at a stop
        val here = if (session.reached) 1 else 0
        val proposed = runCatching {
            if (session.kind == NavKind.PLAN && left.isNotEmpty()) {
                NavSession.start(NavKind.PLAN, session.name, a, fit.sac, fit.line,
                    stops = (if (here == 1) listOf(fit.line.first()) else emptyList()) + left.map { it.point },
                    names = (if (here == 1) listOf(null) else emptyList()) + left.map { it.name },
                    pinned = left.indices.filter { left[it].pinned }.map { it + here }.toSet())
            } else NavSession.start(session.kind, session.name, a, fit.sac, fit.line)
        }.onFailure { Problems.error("navigation", "Proposed route for the new activity could not be started", it) }.getOrNull() ?: return
        follow.propose(proposed)
        recording.proposeSwitch(fit.timeS)
        // chosen in Plan during the tour: the Take new route / Keep question is asked in Navigate
        if (host.mode == AppMode.PLAN) host.enterNavigate()
    }

    /**
     * Never on the route yet (owner 2026-11-07, tour 207: at home, the T1 line ended 552 m short of point 2): the switch
     * plans the whole plan again with [a] / [level] – the plan path of the activity picker, so the plan line and its gap
     * update – and the tour follows the new plan (a way there is led to again, NavSession.afterPlanEdit). No question:
     * nothing of the route was walked yet.
     */
    private fun switchPlanActivity(a: Activity, level: Int) {
        Problems.trace("navigation", "Activity check: route never reached – the plan is planned again as ${a.name}" +
            (if (a == Activity.HIKE) " T$level" else "") + " and followed")
        if (a == Activity.HIKE) { planRoute.sac = level.coerceIn(1, 5); host.rememberSac(planRoute.sac) }
        host.routeEvent(RouteRun.Event.FollowNext) // the plan's result is followed at once (recalc → followPlanned)
        planRoute.selectActivity(a, userChange = false)
        val storedSac = if (a == Activity.HIKE) sac else level
        recording.state.value?.tourId?.let { id -> scope.launchSafe("app", "switchPlanActivity", io) {
            runCatching { rows.updateActivity(id, a.name, storedSac) }
                .onFailure { Problems.error("db", "Activity of the recorded tour not updated in the database", it) }
        } }
    }

    /** "Keep <activity>" (or Back): close the sheet, stop the checks. Nothing changes. */
    fun closeActivityCheck() {
        cancelCheck() // the running row check stops (BRouter terminated): nothing waits behind it
        checkRest = null
        checkHikeSac = null
        activityCheck = null
    }
}
