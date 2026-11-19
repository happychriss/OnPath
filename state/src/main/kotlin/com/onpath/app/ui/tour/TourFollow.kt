package com.onpath.app.ui.tour

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.domain.tour.TourPlanCopy
import com.onpath.app.navigation.FollowStore
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.app.recording.RecordingClient
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What the follow state remembers across app starts (the app: [com.onpath.app.data.Prefs]). */
interface FollowPrefs {
    /** The followed line is a shown track (not the plan). */
    var followIsTrack: Boolean
}

/**
 * What [TourFollow] reads of / asks from the rest of the app (main thread, except [tourChanged] which the plan copy's one
 * serial thread calls, as the old code did). Every call stands where the old body had the member it replaces. The names differ
 * from the view model's functions on purpose: the view model's `init` starts the slice, and `ViewModelInitOrderTest` follows
 * every name it can reach (a host member named like `stopName` would drag the search slice into the start-up walk).
 */
interface TourFollowHost {
    /** The plan's route (the run's result; null = none yet). */
    val route: PlannedRoute?
    /** The track shown on the map (My routes), null = none. */
    val shown: com.onpath.app.ui.ShownTrack?
    /** The place name of a plan point (null = none known). */
    fun placeName(p: GeoPoint): String?
    /** "Route 26 Oct, 10:12" – [prefix] in the app's language. */
    fun newRouteName(prefix: String): String
    /** The plan's stops as saved: with their place names. */
    fun stopsAsSaved(pts: List<GeoPoint>): List<com.onpath.app.gpx.GpxWaypoint>
    /** Every follow request still running is stale from now on (piece 18b moves the requests; until then the view model's). */
    fun invalidateSplits()
    /** Feeds one event to the route run. */
    fun routeEvent(e: RouteRun.Event)
    /** The uuid of saved route [id] (blocking, may throw; read on the plan copy's thread); null = none / unknown. */
    fun savedTourUuid(id: Long): String?
    /** The backup (and the sync) is told that tour [id] changed. */
    fun tourChanged(id: Long)
}

/**
 * What the tour follows (rebuild phase 2 piece 18a): the follow state – the line and its name, the original, the baseline, the
 * service's line version, "taken from the plan", "is a track", "guidance ended by an empty plan" – its writes (the follow files
 * and the command to the service, in the order rule 1 demands), the copy of the followed plan kept with the recorded tour (one
 * serial thread), and the service's other activity taken over. Moved out of the view model as it was; the view model forwards
 * its old names to it, and the code of the plan edit (piece 18b) reads and writes the state through the view model's forwarders.
 *
 * The service is told only through [recording] (`followChanged`), the files are written through [files] – synchronously on the
 * main thread right before the command (the service has no version check on the follow file: rule 1). The tour's plan copy is
 * written through [rows] on [planCopyDispatcher], the ONE serial dispatcher the view model made (two would break the
 * order of the writes). The property initialisers restore the state from [files] and [prefs] as the view model's did (rule 33).
 *
 * [start] launches the tour-id collector (rule 21): the view model calls it in a small `init` after this slice and the edits slice (`TourEdits`, which owns the split requests this slice's host invalidates) are
 * built, above the big `init` as before (some declarations lower than the old collector – nothing between reads the follow
 * state or launches). Latent bug kept as it was: L7 ([followAgain] sends its command without the `recState != null` guard of
 * its siblings). L3: the seen activity is initialised at construction now, before the big `init`'s first state pass – the old
 * initialiser stood below that init and set it back to null, so a view model rebuilt during a tour took the service's
 * activity once more with the next state (within a second); now it keeps what the first pass saw, as in the steady state.
 */
class TourFollow(
    private val plan: PlanDocument,
    private val planRoute: PlanRouteSlice,
    private val recording: RecordingClient,
    private val files: FollowStore,
    private val prefs: FollowPrefs,
    private val rows: TourRows,
    private val host: TourFollowHost,
    private val scope: CoroutineScope,
    private val planCopyDispatcher: CoroutineDispatcher,
) {
    private val activity: Activity get() = planRoute.activity
    private val sac: Int get() = planRoute.sac
    private val waypoints get() = plan.points
    private val savedName: String? get() = plan.savedName
    private val savedId: Long get() = plan.savedId
    private val route: PlannedRoute? get() = host.route
    private val recState get() = recording.state

    var followName by mutableStateOf(files.loadLine()?.first)
    /** Line currently followed (drawn on the map while browsing); after a re-route the new line (from the service). */
    var followLine by mutableStateOf(files.loadLine()?.second)
    /** The followed line as it was started (plan/track) – re-routing never changes it. */
    var followOriginal: List<GeoPoint>? = files.loadSession()?.original ?: followLine
    /** Last navigation line version seen from the service. */
    var navVersion = -1
    /** The followed line was taken from this plan ("Start navigation"), so Clear removes it too. */
    var followFromPlan = false
    /** The route guidance was ended in the tour (all points deleted / "Just record"): the status says "Recording only". */
    var guidanceEndedByEmptyPlan by mutableStateOf(false)
    /** The followed line is a shown track: "Continue navigation" keeps it instead of re-planning. */
    var followIsTrack = prefs.followIsTrack
    /** The plan's points when a track was taken to follow ([com.onpath.app.domain.PlanGuidance.planReplacesTrack]); null = no track / unknown. */
    var planAtTrackFollow: List<GeoPoint>? = null
    /** Session activity last seen from the service (switch taken → the app follows). */
    var navActivitySeen: Activity? = null
    /** Plan line computed for an activity switched while navigating: counts as followed (not an edit). */
    var planBaseline: List<GeoPoint>? = null

    /**
     * The copy of the followed plan kept with the recorded tour (owner 2026-11-07, [TourPlanCopy]): written on one serial
     * background thread, in the order the plan changed; a failure is loud in the problem log and tried again.
     */
    private val tourPlanCopy = TourPlanCopy(
        write = { id, p ->
            runCatching { rows.setTourPlan(id, p.copy(updatedMs = System.currentTimeMillis())) }
                .onFailure { Problems.error("db", "The followed plan could not be stored with tour $id – tried again with the next change", it) }
                .isSuccess
        },
        finalChanged = { id -> host.tourChanged(id) },
    )
    private fun planCopy(block: TourPlanCopy.() -> Unit) {
        scope.launchSafe("app", "tourPlanCopy", planCopyDispatcher) { tourPlanCopy.block() }
    }

    /** The tour-id collector (moved from the view model's small `init`; see the class comment). */
    fun start() {
        // the recording tour's id: a new tour takes the plan followed at its start, its end writes the last version
        scope.launchSafe("app", "tourPlanCopy") {
            recState.map { it?.tourId }.distinctUntilChanged()
                .collect { id -> planCopy { tour(id) } }
        }
    }

    /** The plan on the map IS what the tour follows: its route line is the original or the baseline. */
    fun planFollowed(planLine: List<GeoPoint>?): Boolean =
        planLine != null && (planLine == followOriginal || planLine == planBaseline)

    /** A track was taken to follow and the plan is not the one it was taken at any more ([com.onpath.app.domain.PlanGuidance.planReplacesTrack]). */
    fun planReplacesTrack(): Boolean =
        com.onpath.app.domain.PlanGuidance.planReplacesTrack(followIsTrack, waypoints.toList(), planAtTrackFollow)

    /** The plan guides: a plan line is followed and the plan has points. */
    val planGuides: Boolean get() = followLine != null && !followIsTrack && waypoints.isNotEmpty()

    /** The plan is saved / loaded (name, id, points): during the tour the tour's copy knows its route and name now. */
    fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) {
        plan.setSaved(name, id, points)
        // saved at the tour start / on the way: the tour's copy knows its route and name now
        if (recState.value != null && followFromPlan && followLine != null && !followIsTrack) recopyFollowedPlan()
    }

    /** The service navigates another activity now (switch taken, also from the notification): the app follows. */
    fun syncNavActivity(s: NavSession?) {
        val a = s?.activity ?: return
        if (a == navActivitySeen) return
        navActivitySeen = a
        if (a != activity) {
            followOriginal = s.original
            host.routeEvent(RouteRun.Event.AdoptBaseline)
            planRoute.selectActivity(a, userChange = false)
        }
    }

    /** Follows [s] again as it was (Undo during a tour, [com.onpath.app.domain.plan.FollowHistory]); [planLine] = the plan's route line now. */
    fun followAgain(s: NavSession, planLine: List<GeoPoint>, versionNow: Int) {
        // a new version: the service and the junction cues re-read the line
        val session = s.copy(version = maxOf(s.version, versionNow) + 1, token = newSessionToken())
        guidanceEndedByEmptyPlan = false
        files.appFollows(session, session.name, session.active)
        followName = session.name
        followLine = session.active
        followOriginal = session.original
        planBaseline = planLine
        followIsTrack = false; prefs.followIsTrack = false
        followFromPlan = true
        planAtTrackFollow = null
        copyFollowedPlan(session.activity, session.sac)
        Problems.trace("tour", "The tour follows \"${session.name}\" again as before the edit (${session.stops.size} points" +
            (if (session.rerouted) ", " + (if (session.lead && !session.reached) "way there" else "new route") + " kept" else "") + ")")
        recording.followChanged()
    }

    /**
     * The tour follows the plan's route. Its name (lock screen "Back to …", the tour log) is the saved route's while the
     * plan belongs to one ([savedName]), else "Route <date>".
     */
    fun followPlanned(checkNow: Boolean = false) {
        val r = route ?: return
        val w = waypoints.toList()
        setFollow(savedName ?: host.newRouteName(Txt.s(Res.string.msg_name_route)), r.points, isTrack = false, checkNow = checkNow,
            stops = w, names = w.map { host.placeName(it) }, pinned = plan.pinnedIndices())
        followFromPlan = true
        copyFollowedPlan(activity, sac)
        if (recState.value != null) Problems.trace("tour", "The tour follows \"$followName\" now (${activity.name}, ${w.size} points)")
    }

    /** The followed plan copied again as the activity the tour follows (one taken on the route is the session's, not the plan's). */
    fun recopyFollowedPlan() {
        val followed = recState.value?.nav?.session?.takeIf { it.kind == NavKind.PLAN }
        copyFollowedPlan(followed?.activity ?: activity, followed?.sac ?: sac)
    }

    /**
     * The tour follows the plan (its newest version): a copy goes with the recorded tour ([TourPlanCopy], owner
     * 2026-11-07) – before the start it waits for the tour. [act] / [level] = what the tour follows it as.
     */
    fun copyFollowedPlan(act: Activity, level: Int) {
        val p = tourPlanNow(act, level) ?: return
        val id = savedId
        planCopy {
            // the saved route it is (read here: a route saved at the tour start is not in My routes' list yet)
            val source = if (id < 0) null else runCatching { host.savedTourUuid(id) }
                .onFailure { Problems.warn("db", "The followed plan's saved route not read – the copy is kept without it", it) }.getOrNull()
            followed(p.copy(sourceUuid = source))
        }
    }

    /** The plan on the map as the tour's copy: points with names, pins, the line, its numbers; null below two points. */
    private fun tourPlanNow(act: Activity, level: Int): com.onpath.app.data.TourPlan? {
        val pts = waypoints.toList()
        if (pts.size < 2) return null
        val r = route
        return com.onpath.app.data.TourPlan(name = savedName, activity = act.name, sac = level, waypoints = host.stopsAsSaved(pts),
            pinned = plan.pinnedIndices(), line = (r?.points ?: pts).map { com.onpath.app.gpx.TrackPoint(it.lat, it.lon, it.ele) },
            distanceM = r?.distanceM ?: 0.0, ascentM = r?.ascentM ?: 0.0, descentM = r?.descentM ?: 0.0,
            durationS = r?.durationS ?: 0.0)
    }

    fun followShown() {
        val s = host.shown ?: return
        setFollow(s.name, s.points, isTrack = true)
        followFromPlan = false
        planCopy { notFollowed() } // a track: the plan's copy stays as it was
        planAtTrackFollow = waypoints.toList()
    }

    /**
     * Starts a navigation session for [points] (nav-session.json for the service: original line, stops for re-routing)
     * and the plain follow line. [checkNow]: the service checks the next fix at once ("Continue navigation").
     */
    fun setFollow(name: String, points: List<GeoPoint>, isTrack: Boolean, checkNow: Boolean = false,
                  stops: List<GeoPoint> = emptyList(), names: List<String?> = emptyList(), pinned: Set<Int> = emptySet(),
                  firstNumber: Int = 1) {
        guidanceEndedByEmptyPlan = false
        val session = runCatching {
            // a new token (a re-route of the old line never overwrites it) and a new version (cues / voice re-read the line)
            NavSession.start(if (isTrack) NavKind.TRACK else NavKind.PLAN, name, activity, sac, points, stops, names, pinned)
                .copy(token = newSessionToken(), version = (recState.value?.nav?.version ?: navVersion).coerceAtLeast(0) + 1,
                    firstNumber = firstNumber.coerceAtLeast(1))
        }.onFailure { Problems.error("navigation", "Navigation session could not be started; following without it", it) }.getOrNull()
        files.appFollows(session, name, points)
        followName = name
        followLine = points
        followOriginal = points
        planBaseline = null
        followIsTrack = isTrack; prefs.followIsTrack = isTrack
        planAtTrackFollow = null
        if (recState.value != null) recording.followChanged(checkNow)
    }

    /** A token no earlier session of this install had (time-based, strictly increasing). */
    private fun newSessionToken(): Long = maxOf(System.currentTimeMillis(), lastSessionToken + 1).also { lastSessionToken = it }
    private var lastSessionToken = 0L

    fun clearFollow() {
        host.invalidateSplits()
        followFromPlan = false
        planCopy { notFollowed() } // nothing followed ("Nur aufzeichnen", guidance ended): what is stored stays
        guidanceEndedByEmptyPlan = false
        followIsTrack = false; prefs.followIsTrack = false
        planAtTrackFollow = null
        files.appEnds()
        followName = null
        followLine = null
        followOriginal = null
        planBaseline = null
        if (recState.value != null) recording.followChanged()
    }
}
