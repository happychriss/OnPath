package com.onpath.app.ui.tour

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.PlanCamera
import com.onpath.app.domain.PlanStart
import com.onpath.app.domain.plan.FollowHistory
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.recording.RecordingClient
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.Say
import com.onpath.app.ui.MapViewSlice
import com.onpath.app.ui.ShownTrack
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanEditSlice
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.app.ui.plan.PlanRunSlice
import com.onpath.app.ui.routes.SaveSlice
import com.onpath.app.ui.routes.ToursStore
import com.onpath.strings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What [TourSession] asks from the rest of the app (main thread). The names differ from the view model's functions on purpose
 * (`ViewModelInitOrderTest` follows every name an `init` can reach).
 */
interface TourSessionHost {
    /** The red error box's text (null = none): a failed start says "recording did not start" only when no box shows. */
    val errorCard: String?
    /** The plan's route (the run's result; null = none yet). */
    val route: PlannedRoute?
    /** The track shown on the map (My routes); the start closes it ("followed" or "just record"). */
    var shown: ShownTrack?
    /** The session's one map orientation: walking direction up or north up. */
    val headingUp: Boolean
    /** The location tracker has a fix (not the tour's last recorded point: the tracker's own value). */
    val hasGpsFix: Boolean
    /** `tapHoldHint = false; offlineInfo = false`: the two lines of the plan sheet that stay until the user acts. */
    fun clearHints()
    /** The map's own menus close when Navigate is entered: POI card, hold menus, point menu, first-point menu, highlight (not `followMe`). */
    fun closeMenusForNavigate()
    /** The tour's corridor of map areas and routing parts is asked for again, quietly ([line] is the plan's route, [last] its last point). */
    fun secureTourCorridor(line: List<GeoPoint>, last: GeoPoint?, activity: Activity)
}

/**
 * The tour's session (rebuild phase 2 piece 19): the mode (Plan / Navigate) and what entering one does, Start ("Los geht's":
 * guards, what is followed, the service's START, the wait for its first state), Continue, the seven answers to the service's
 * questions, the start-up work (the left-over line, the mode flip, the check for an interrupted tour) and the two collectors of
 * the service's state (`startFailed`, `state`). Moved out of the view model as it was; the view model forwards its old names.
 *
 * The service is told only through [recording] (the commands of [tour] and the answers) and through [follow] (the follow files
 * and their command, rule 1). No `init`, no work in the constructor; [start] runs everything that ran in the view model's big
 * `init` between `refreshTours()` and the tour's end collector, in the same order, and is called where that code stood: the first
 * pass of the `state` collector runs inline on `Main.immediate` during construction (paper risk 1; at a process restart during a
 * tour it meets the same world as before) and the mode flip comes after `recalc()` (risk 2: the initial mode is Plan).
 *
 * Latent bugs kept as they were: L4 (a failed
 * start can show two boxes), and (fixed 2026-11-19) L2: a failing `findActiveRecording` at start is reported and read as "no active tour", `startModeKnown` is always set; L1: each pass of the `startFailed` and `state` collectors is guarded – a throw is reported and the collector goes on (a failed activity switch is repeated by the next state, at most [MAX_SYNC_RETRIES] times in a row).
 */
class TourSession(
    private val plan: PlanDocument,
    private val planEdit: PlanEditSlice,
    private val planRun: PlanRunSlice,
    private val planRoute: PlanRouteSlice,
    private val save: SaveSlice,
    private val map: MapViewSlice,
    private val tour: TourController,
    private val follow: TourFollow,
    private val recording: RecordingClient,
    private val rows: TourRows,
    private val tours: ToursStore,
    private val followHistory: FollowHistory,
    private val host: TourSessionHost,
    private val say: Say,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val main: CoroutineDispatcher,
    /** How long Start waits for the service's first state before saying that recording did not start. */
    private val startTimeoutMs: Long = 6_000L,
    /** How long the start-up check waits for a service that restarts by itself (sticky) before it asks about the interrupted tour. */
    private val stickyWaitMs: Long = 1_500L,
) {
    private val activity: Activity get() = planRoute.activity
    private val sac: Int get() = planRoute.sac
    private val waypoints get() = plan.points
    private val route: PlannedRoute? get() = host.route
    private val recState get() = recording.state
    private val followName: String? get() = follow.followName
    private val followLine: List<GeoPoint>? get() = follow.followLine
    private val followIsTrack: Boolean get() = follow.followIsTrack
    private var shown: ShownTrack?
        get() = host.shown
        set(v) { host.shown = v }
    private val errorCard: String? get() = host.errorCard
    private fun sayError(text: String, place: NoticePlace) = say.say(text, NoticeKind.ERROR, place)
    private fun routeEvent(e: RouteRun.Event) { planRun.routeEvent(e) }
    private fun followPlanned(checkNow: Boolean = false) = follow.followPlanned(checkNow)
    private fun followShown() = follow.followShown()
    private fun clearFollow() = follow.clearFollow()
    private fun planFollowed(planLine: List<GeoPoint>?): Boolean = follow.planFollowed(planLine)
    private fun planReplacesTrack(): Boolean = follow.planReplacesTrack()

    /** Plan (home) or Navigate; see AppMode and docs/ux-two-modes. */
    var mode by mutableStateOf(AppMode.PLAN)
    /**
     * Zoom the map takes when Navigate starts or locate is tapped in Navigate ([AppMode.navStartZoom],
     * [AppMode.locateZoom]); null once taken (then the user's zoom stays).
     */
    var navStartZoom by mutableStateOf<Double?>(null)
    /** A plan with legs the router could not find (drawn dashed): "Navigate anyway?" is open. */
    var straightAsk by mutableStateOf(false)

    private var startJob: Job? = null

    /**
     * The start-up work of the view model's big `init`, in its old order: the followed line left over with an empty plan, the
     * mode flip (after the view model's `recalc()` – the plan was routed as "in Plan"), the check for an interrupted tour, the
     * collector of the service's stop by itself, the collector of the service's state.
     */
    fun start() {
        // an empty plan with a followed line left and no tour in the database (Pixel 2026-11-01: a blue line without points
        // that nothing could remove): the line goes – never while a tour is recording (the service may come back)
        if (waypoints.isEmpty() && followLine != null && !followIsTrack) scope.launchSafe("app", "start-up work") {
            val active = withContext(io) {
                runCatching { rows.findActiveRecording() != null }
                    .onFailure { Problems.warn("db", "Active tour not checked at start; the followed line stays", it) }.getOrDefault(true)
            }
            if (!active && waypoints.isEmpty() && recState.value == null && followLine != null && !followIsTrack) {
                Problems.trace("plan", "Empty plan, no tour: the followed line left from before is removed")
                clearFollow()
            }
        }
        // Set directly (enter() touches properties declared below); the check after the DB read
        // calls enter() and so also tells a running service which mode we are in.
        if (recording.state.value != null) mode = AppMode.NAVIGATE
        // no new tour until this check has decided (TourController.canStartNew); cleared whatever happens – a stuck
        // flag would refuse every tour for good
        tour.unfinishedPending = true
        scope.launchSafe("app", "start-up work", io) {
            try {
                // L2 (fixed 2026-11-19): a read that fails is "no active tour found" (nothing is invented, nothing written) –
                // the start mode is entered and known all the same; a cancellation is not a failure
                val active = try {
                    rows.findActiveRecording()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Problems.error("app", "Active tour could not be read at start; started as if none was found", e)
                    null
                }
                val start = AppMode.onStart(recording.state.value != null, active != null)
                withContext(main) {
                    // the same order as always (enter, then known); a throwing enter is reported and the mode still becomes known
                    try {
                        enter(start)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Problems.error("app", "Entering the start mode failed; the start mode is known all the same", e)
                    }
                    map.startModeKnown = true
                }
                if (active != null && recording.state.value == null) {
                    delay(stickyWaitMs) // the service may be restarting by itself (sticky)
                    if (recording.state.value == null) {
                        // drawn behind the question, so the user sees which tour it is (field bug 2026-10-31: empty map)
                        val line = runCatching { tours.loadSegments(active.id).map { seg -> seg.map { GeoPoint(it.lat, it.lon) } } }
                            .onFailure { Problems.warn("db", "Track of the unfinished tour could not be read – not drawn", it) }
                            .getOrDefault(emptyList())
                        withContext(main) { tour.showUnfinished(active, line) }
                    }
                }
            } finally {
                withContext(NonCancellable + main) { tour.unfinishedPending = false }
            }
        }
        // The service stopped by itself (permission gone, foreground refused, database broken): say why, in the app too.
        scope.launchSafe("app", "start-up work") {
            recording.startFailed.collect { why ->
                // L1 (fixed 2026-11-19): one pass is guarded – a throw is reported and the collector lives for the next failed start
                try {
                    if (why != null) {
                        recording.startFailedSeen()
                        // saying the reason fails: reported, and the mode step below still runs (never Navigate with no recording)
                        try {
                            sayError(why, NoticePlace.BACKGROUND)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Problems.error("app", "Saying why the recording could not start failed; the mode step still runs", e)
                        }
                        if (mode == AppMode.NAVIGATE) enter(AppMode.PLAN)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Problems.error("app", "Failed-start pass of the startFailed collector failed; the collector goes on", e)
                }
            }
        }
        // Re-routing (service): follow the line the service navigates now (new route taken / original again).
        scope.launchSafe("app", "start-up work") {
            var saveFailedShown = false
            var recordingSeen: Long? = null
            var syncRetries = 0 // resets of the activity mark in a row (a pass that ends without a throw sets it back to 0)
            recording.state.collect { st ->
                // L1 (fixed 2026-11-19): one pass is guarded – a throw is reported, the next state repeats the work
                try {
                    if (st?.tourId != recordingSeen) {
                        followHistory.clear() // Undo brings back a followed route only within its own tour
                        if (st != null) Problems.trace("tour", "Tour ${st.tourId} recording (plan ${waypoints.size} points, " +
                            (if (followLine != null) "following ${followName ?: "a route"}" else "nothing followed") + ")")
                        else { Problems.trace("tour", "Tour $recordingSeen recording ended"); follow.guidanceEndedByEmptyPlan = false }
                        recordingSeen = st?.tourId
                    }
                    if (st?.saveFailed == true && !saveFailedShown) {
                        sayError(Txt.s(Res.string.msg_track_save_failing), NoticePlace.BACKGROUND)
                        saveFailedShown = true // after the call: a say that throws is tried again with the next state
                    } else if (st?.saveFailed == false) saveFailedShown = false
                    tour.onArrived(st?.arrived == true)
                    val n = st?.nav ?: run { follow.navActivitySeen = null; syncRetries = 0; return@collect }
                    if (n.version != follow.navVersion) { follow.navVersion = n.version; if (followLine != n.line) follow.followLine = n.line }
                    follow.syncNavActivity(n.session)
                    syncRetries = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Problems.error("app", "State pass of the recording-state collector failed; the collector goes on", e)
                    // syncNavActivity marks the activity as seen BEFORE it switches: a throw in the switch would never be tried
                    // again (same activity in every later state). Forget the mark so the next state repeats it – at most
                    // MAX_SYNC_RETRIES times in a row, so a throw that comes every time does not replan with every service tick.
                    if (syncRetries < MAX_SYNC_RETRIES) { syncRetries++; follow.navActivitySeen = null }
                }
            }
        }
    }

    // ---------------- modes: Plan <-> Navigate ----------------

    /** Switches mode; the recording goes on either way, only off-route checks pause in Plan. */
    fun enter(m: AppMode) {
        val was = mode
        mode = m
        host.clearHints()
        if (save.ask != null) save.cancel() // Save's dialog does not stay open behind another mode
        // the start zoom of Navigate, once per start (owner); the map takes it when it follows
        if (m == AppMode.NAVIGATE && was != AppMode.NAVIGATE) navStartZoom = AppMode.navStartZoom(activity)
        else if (m != AppMode.NAVIGATE) navStartZoom = null
        if (m == AppMode.NAVIGATE) { host.closeMenusForNavigate(); map.followMe = true }
        // Plan keeps the compass choice: walking direction up stays turned and following (until you edit or move the
        // map); north up stops following
        else map.followMe = host.headingUp && map.followMe && host.hasGpsFix
        // Plan during a tour: the sheet is one row (tour status + Continue), the map free for holds (owner 2026-10-31)
        if (m == AppMode.PLAN && was == AppMode.NAVIGATE) planEdit.planSize = PlanSize.backInPlan(planEdit.planSize, recState.value != null)
        // Navigate → Plan (also the end of a tour): the plan in front, not the position (owner 2026-11-06, PlanCamera)
        if (m == AppMode.PLAN && was == AppMode.NAVIGATE && waypoints.isNotEmpty())
            map.showPlanWhole(if (recState.value == null) PlanCamera.Moment.TOUR_END else PlanCamera.Moment.FROM_NAVIGATE)
        if (recState.value != null) recording.navigating(m == AppMode.NAVIGATE)
    }

    /**
     * [recordOnly]: ☰ → "Nur aufzeichnen" (owner 2026-11-06, flow map H1): a plain recording whatever the plan holds –
     * nothing is followed, the plan stays as it is.
     */
    fun startNavigation(force: Boolean = false, recordOnly: Boolean = false) {
        if (recState.value != null) { continueNavigation(force); return }
        // an interrupted tour still checked / asked about: its question decides first (TourController.canStartNew) –
        // nothing started, the plan untouched; the question shows or is already there
        if (!tour.canStartNew) { Problems.trace("tour", "Tour start held: an interrupted tour is still checked / asked about"); return }
        val s = shown
        if (!recordOnly && s == null && !force && planHasStraightLegs()) { straightAsk = true; return }
        // PlanStart: the sheet's button says "Start recording" exactly when nothing is followed here
        when (if (recordOnly) PlanStart.RECORD_ONLY else PlanStart.of(trackPoints = s?.points?.size, hasRoute = route != null, pointCount = waypoints.size)) {
            PlanStart.FOLLOW_TRACK -> { followShown(); shown = null }
            PlanStart.NAVIGATE_PLAN -> { followPlanned(); secureTourCorridor(); save.atTourStart() }
            // the button waits for the line; reached anyway: record (a tour is never refused), nothing followed.
            // A shown track without a line closes, like ☰ → Start recording.
            PlanStart.WAIT_FOR_ROUTE, PlanStart.RECORD_ONLY -> { clearFollow(); shown = null }
        }
        startRecording()
        // Navigate only once the service really records (R-01): a refused foreground service, a missing
        // permission or a broken database must not leave the user in Navigate with nothing being recorded.
        startJob?.cancel()
        startJob = scope.launchSafe("app", "startNavigation") {
            val started = withTimeoutOrNull(startTimeoutMs) { recording.state.filterNotNull().first() } != null
            if (started) enter(AppMode.NAVIGATE)
            else if (errorCard == null) sayError(Txt.s(Res.string.msg_recording_not_started), NoticePlace.MAP)
        }
    }

    /**
     * A tour starts on the plan's route (F-OFF-02 "started route", owner: "new route – even without signal"): map areas
     * and routing parts of its corridor (hike 10 km, bike 20 km either side – OfflineCorridor) still missing – loading
     * cancelled, server failed, waited – are asked for again, quietly (background chip; Wi-Fi-only rule as for the plan).
     * Geometry off the main thread.
     */
    private fun secureTourCorridor() {
        val line = route?.points?.takeIf { it.size >= 2 } ?: return
        val pts = waypoints.toList()
        host.secureTourCorridor(line, pts.lastOrNull(), activity) // a round trip closes at its ①
    }

    private fun planHasStraightLegs(): Boolean =
        (!followIsTrack || planReplacesTrack()) && waypoints.size >= 2 && route?.legs?.any { it.straight } == true

    /** The user wants to navigate the plan although parts of it are straight lines. */
    fun straightConfirmed() { straightAsk = false; startNavigation(force = true) }

    /**
     * Back to Navigate while a tour runs (no new recording). The plan's waypoints are **not** changed (owner):
     * when the plan was edited, its route becomes the new navigation session; otherwise the running session (and a
     * new route already taken) stays. Either way the service checks the next fix at once and, when the user is away
     * from the line, asks the same "Take new route / Keep original" question as when leaving the route.
     */
    fun continueNavigation(force: Boolean = false) {
        if ((!followIsTrack || planReplacesTrack()) && waypoints.size >= 2) {
            val planLine = if (planRun.routing) null else route?.points
            // an edited plan with straight legs would become the navigated line: ask first (same as Start)
            if (!force && planLine != null && !planFollowed(planLine) && planHasStraightLegs()) { straightAsk = true; return }
            when {
                planLine != null && !planFollowed(planLine) -> followPlanned(checkNow = true)
                planRun.routing -> { routeEvent(RouteRun.Event.FollowNext) } // follows the plan when the result is there
                else -> checkNow()
            }
        } else checkNow()
        enter(AppMode.NAVIGATE)
    }

    private fun checkNow() {
        if (recState.value != null && followLine != null) recording.checkNow()
    }

    /** The slide switch in Navigate. Recording continues. */
    fun switchToPlan() = enter(AppMode.PLAN)

    /** The service gets its START: the tour follows what [startNavigation] decided. */
    fun startRecording() {
        Problems.trace("tour", "Tour start (${activity.name}, plan ${waypoints.size} points, " +
            (if (followLine != null) "following ${followName ?: "a route"}" else "nothing followed") + ")")
        tour.start(activity.name, sac)
        // no followMe here: in Plan it briefly turned the map with the compass before Navigate took over (owner
        // 2026-11-07: the map never turns by itself) – entering Navigate starts the following
    }

    /** Off-route question in Navigate: take the new route the service found. */
    fun takeNewRoute(questionId: Int) = recording.takeRoute(questionId)

    /** "Off route – re-plan?" in Navigate – Yes: take the new route via the stop in question. */
    fun takeViaRoute(questionId: Int) = recording.takeVia(questionId)

    /** Off-route question in Navigate: keep following the original route. */
    fun keepOriginalRoute(questionId: Int) = recording.keepRoute(questionId)

    /** "Route starts 31 km away" (never on the route yet) – Guide me: a route from here via the start and all points. */
    fun leadToStart(questionId: Int) = recording.leadThere(questionId)

    /** "Route starts 31 km away" – Just record: quiet until the route is reached. */
    fun justRecord(questionId: Int) = recording.justRecord(questionId)

    /** "Du stehst mitten auf der Route" – Hier einsteigen: start here, the points before count as passed (owner 2026-11-03). */
    fun joinHere(questionId: Int) = recording.joinHere(questionId)

    /** OK on "No way to the route found – recording continues". */
    fun leadFailedOk(questionId: Int) = recording.leadFailedOk(questionId)
}

/** How often in a row a failed activity switch of the state collector is repeated by the next state (then quiet until a pass succeeds). */
internal const val MAX_SYNC_RETRIES = 3
