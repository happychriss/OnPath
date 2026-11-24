package com.onpath.app.ui.tour

import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.PlanGuidance
import com.onpath.app.domain.SplitRequests
import com.onpath.app.domain.plan.FollowHistory
import com.onpath.app.domain.plan.RouteBack
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavLine
import com.onpath.app.navigation.TourSplit
import com.onpath.app.recording.RecordingClient
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Planner
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.PlanGps
import com.onpath.app.ui.Position
import com.onpath.app.ui.Say
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanEditSlice
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * What [TourEdits] reads of / asks from the rest of the app (main thread). Every call stands where the old body had the member
 * it replaces. The names differ from the view model's functions on purpose: the view model's `init`s reach the slice, and
 * `ViewModelInitOrderTest` follows every name it can reach (a host member named `recalc` would drag the whole route run into
 * the start-up walk of the small `init` that starts the follow slice).
 */
interface TourEditsHost {
    /** The plan's route (the run's result; null = none yet). */
    val route: PlannedRoute?
    /** Plans the points again (the run). */
    fun replan()
    /** The maps and routing data for the points [after] that were not in [before] ([MapDataFlow.pointsSet], long plans wait for their answer). */
    fun mapsForNewPoints(before: List<GeoPoint>, after: List<GeoPoint>)
    /** "No position for this edit": the note on the map, the line says why. */
    fun noPositionNote(note: PlanGps.Note, text: org.jetbrains.compose.resources.StringResource)
    /** The place name of a plan point (null = none known). */
    fun placeName(p: GeoPoint): String?
    /** "Route 26 Oct, 10:12" – [prefix] in the app's language. */
    fun newRouteName(prefix: String): String
    /** Feeds one event to the route run. */
    fun routeRunEvent(e: RouteRun.Event)
    /** The shown track is closed (a track followed during the tour becomes the followed line, the shown copy goes). */
    fun closeShownTrack()
    /** Back to Navigate with what is followed (piece 19 moves it). */
    fun resumeNavigation()
}

/**
 * What a plan edit means for the tour (rebuild phase 2 piece 18b): the passed points, the split of an edit into passed points and
 * points ahead, the tour following the edited plan (also Undo / Redo to a plan followed before), the position → ahead route, the
 * question "Route back to the start?" and its answer, one point left "right here", the guidance ended by an empty plan, a track
 * followed during the tour. Moved out of the view model as it was; the follow state and its writes are [follow] (piece 18a), the
 * edits themselves [planEdit] / [plan], the service is told only through [follow] (rule 1: its writes stay synchronous on main
 * right before their command). The view model keeps same-name forwarders for what the screens call and re-points the hosts of
 * the plan edit / the place menus / My routes to this slice.
 *
 * No `init`, no work in the constructor, no collector. [followCancel] is `@Volatile` and written by the view model's `recalc` too:
 * one field, the same visibility as before. [splitRequests] (stale-making of older position → ahead routes, rules 16 / 19) is
 * read by [TourFollow]'s host (`clearFollow`) through the view model's adapter.
 */
class TourEdits(
    private val plan: PlanDocument,
    private val planEdit: PlanEditSlice,
    private val planRoute: PlanRouteSlice,
    private val follow: TourFollow,
    private val recording: RecordingClient,
    private val position: Position,
    private val followHistory: FollowHistory,
    private val host: TourEditsHost,
    private val say: Say,
    private val scope: CoroutineScope,
    private val routingDispatcher: CoroutineDispatcher,
) {
    private val activity: Activity get() = planRoute.activity
    private val sac: Int get() = planRoute.sac
    private val waypoints get() = plan.points
    private val pinned: Set<GeoPoint> get() = plan.pinned
    private val savedName: String? get() = plan.savedName
    private val route: PlannedRoute? get() = host.route
    private val recState get() = recording.state
    private val followLine: List<GeoPoint>? get() = follow.followLine
    private val followIsTrack: Boolean get() = follow.followIsTrack
    private fun myPosition(): GeoPoint? = position.here()
    private fun sayError(text: String, place: NoticePlace) = say.say(text, NoticeKind.ERROR, place)

    /** The tour's follow line being routed ([followAhead]): a newer edit stops it, so the plan never waits behind it. */
    @Volatile var followCancel: com.onpath.app.routing.RouteCancel? = null

    /**
     * What the last plan edit means for the running tour ([TourSplit], docs/design/tour-edit-position.md): the position
     * splits the plan into passed points and points ahead. Taken by [followEditedPlan] once the plan's route is ready;
     * null = no tour following the plan, or nothing passed (the plan is followed as a whole, as before).
     */
    private var pendingSplit: TourSplit.Outcome? = null
    /** The plan before that edit (FollowHistory key: Undo brings its followed route back exactly). */
    private var splitBefore: List<GeoPoint>? = null
    /** Newer position → ahead routes supersede older ones; any later edit / clear makes a running one stale. */
    val splitRequests = SplitRequests()

    /**
     * The plan points passed in the running tour ([TourSplit.passed]) – empty without a tour that follows the plan.
     * Only the app's tour: the plan itself is edited exactly as in the web planner.
     */
    fun tourPassed(plan: List<GeoPoint> = waypoints.toList()): Set<Int> {
        val t = splitTour(plan) ?: return emptySet()
        return TourSplit.passed(plan, t, known = passedPoints)
            .also { r -> r.forEach { i -> plan.getOrNull(i)?.let { passedPoints += it } } }
    }

    /** [tourPassed] as the map draws it (grey): a stop the route only passed by is behind the rider, but was not reached. */
    fun tourPassedOnMap(plan: List<GeoPoint> = waypoints.toList()): Set<Int> {
        val passed = tourPassed(plan)
        val t = splitTour(plan) ?: return emptySet()
        return TourSplit.reachedOf(plan, passed, t)
    }

    /** What [TourSplit] knows of the running tour; null = no tour that follows the plan. */
    private fun splitTour(plan: List<GeoPoint>): TourSplit.Tour? {
        val rec = recState.value ?: return null
        if (rec.tourId != passedTourId) { passedTourId = rec.tourId; passedPoints.clear(); passedByPoints.clear() }
        if (followLine == null || followIsTrack || plan.isEmpty()) return null
        val s = rec.nav?.session?.takeIf { it.kind == NavKind.PLAN }
        s?.passedBy?.forEach { i -> s.stops.getOrNull(i)?.let { passedByPoints += it.point } }
        return TourSplit.Tour(
            track = rec.track, position = myPosition(), reached = s?.reached == true, bike = activity != Activity.HIKE,
            // progress compares with the stops' places on the original line only (a taken re-route has its own)
            stops = s?.takeUnless { it.rerouted }?.stops.orEmpty(), progressM = rec.nav?.progressM ?: 0.0,
            visited = s?.visited?.mapNotNull { s.stops.getOrNull(it)?.point }?.toSet().orEmpty(),
            passedBy = passedByPoints.toSet(),
        )
    }

    /**
     * The plan points found passed in this tour (fix-tour-edit-line.md cause 1): after an edit the tour follows
     * position → ahead and its session no longer knows them – they stay passed (matched by coordinate; a deleted point
     * simply no longer matches). Cleared with a new tour.
     */
    private val passedPoints = HashSet<GeoPoint>()
    /** Of those, the stops the route only passed by ([com.onpath.app.navigation.NavSession.passedBy]): never grey on the map. */
    private val passedByPoints = HashSet<GeoPoint>()
    private var passedTourId: Long? = null

    /** The next plan change loads a saved route during a tour ([openTour] duringTour): a whole new plan ([splitOf]). */
    var loadedOnTheWay = false

    /** [TourSplit.outcome] of the edit [before] → [after] during a tour, kept for [followEditedPlan]. */
    fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>): TourSplit.Outcome? {
        splitRequests.invalidate()
        // a route loaded on the way (a tap in My routes during a tour, owner 2026-11-03 / 2026-11-06) is a new plan, not an edit: followed as
        // a whole from its point ① (a way there / the mid-route question like at a tour start), nothing of it passed
        val fresh = loadedOnTheWay; loadedOnTheWay = false
        val passed = if (fresh) emptySet() else tourPassed(before)
        val o = if (passed.isEmpty()) null else TourSplit.outcome(before, after, passed)
        pendingSplit = o; splitBefore = before
        if (o != null) Problems.trace("plan", "Edit during the tour: ${passed.size} of ${before.size} points passed → " +
            (if (o is TourSplit.Outcome.Follow) "follow position → ${o.ahead.size} points ahead" else o.toString()))
        return o
    }

    /**
     * Yes: the plan becomes position → start and the tour follows it (followEditedPlan) – no extra Undo step, so one
     * Undo brings the plan from before the delete. No (or Yes without a position): plain recording, the route guidance
     * ends as for an empty plan; Undo brings the plan and the guidance back.
     */
    fun answerRouteBack(yes: Boolean) {
        if (!planEdit.routeBackAsk) return
        planEdit.routeBackAsk = false
        splitRequests.invalidate()
        if (recState.value == null) return
        val pos = myPosition()
        val plan = waypoints.toList()
        // one point left: position → start (RouteBack); more: the plan's start is appended as its goal (a round trip),
        // the passed points stay, the tour follows position → start (TourSplit)
        val back = if (!yes) null else if (plan.size == 1) RouteBack.plan(plan, pos)
            else plan.takeIf { pos != null }?.let { it + it.first() }
        if (back != null && plan.size > 1) splitOf(plan, back) else pendingSplit = null
        if (back != null) {
            Problems.trace("plan", "Route back to the start: yes – plan = position → start")
            val before = waypoints.toList()
            this.plan.replace(back)
            host.mapsForNewPoints(before, waypoints)
            this.plan.prunePinned()
            host.routeRunEvent(RouteRun.Event.PlanEdited)
            host.replan()
            return
        }
        if (yes) host.noPositionNote(PlanGps.Note.ROUTE_BACK_NO_POSITION, Res.string.msg_route_back_no_position)
        Problems.trace("plan", "Route back to the start: " + (if (yes) "yes, but no position" else "no") +
            " – route guidance ended, recording continues (Undo brings it back)")
        if (followLine != null && !followIsTrack) { follow.clearFollow(); follow.guidanceEndedByEmptyPlan = true }
    }

    /**
     * Owner rule ([PlanGuidance]): what Plan shows = what Navigate follows. An empty plan during a tour ends the
     * route guidance – no line, no off-route warning, no junction vibration, no arrival; the recording goes on. Undo
     * (points back) follows the plan again ([followEditedPlan]).
     */
    fun endGuidanceIfPlanEmpty() {
        if (!PlanGuidance.endsGuidance(waypoints.size, recState.value != null, followLine != null, followIsTrack)) return
        Problems.trace("plan", "All points deleted during the tour: route guidance ended, recording continues (Undo brings it back)")
        follow.clearFollow()
        follow.guidanceEndedByEmptyPlan = true
    }

    /**
     * One point left after an edit during a tour ([PlanGuidance.oneLeftGoesFromHere], owner 2026-11-06,
     * onpath.private#39): "Hierhin" – the plan becomes position → that point in the same Undo step (one Undo brings the
     * plan from before), the sheet shows that route and the tour follows it as a whole new plan ([followEditedPlan]).
     * Without a position the guidance ends as for an empty plan and a line says why; the recording goes on.
     */
    fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean = false) {
        val left = waypoints.singleOrNull()
        val pos = myPosition()
        val auto = left != null && left == plan.oneLeftStart
        val outcome = plan.oneLeft(before.size, recState.value != null, followLine != null, followIsTrack, planEdit.routeBackAsk, pos)
        if (outcome == PlanGuidance.OneLeft.NONE) return
        if (outcome == PlanGuidance.OneLeft.EMPTY) {
            if (!mayEmpty) {
                // Undo / Redo brings a plan back – it never empties it; but one point right here has no route of its
                // own, so the tour must not keep following the old line (audit 2026-11-07, PlanFlowPropertyTest rule 4):
                // the guidance ends as for an empty plan, the recording goes on, Redo / a new point bring it back
                Problems.trace("plan", "Undo / Redo left one point " +
                    (if (auto) "(the start the one-point rule put in)" else "right where I stand") +
                    " during the tour: route guidance ended, recording continues")
                pendingSplit = null
                follow.clearFollow()
                follow.guidanceEndedByEmptyPlan = true
                return
            }
            Problems.trace("plan", "One point left during the tour, " +
                (if (auto) "only the start this rule put in" else "right where I stand") + ": the plan empties (field bug 2026-11-06)")
            planEdit.routeBackAsk = false
            plan.empty()
            return // endGuidanceIfPlanEmpty: the guidance ends, the recording goes on
        }
        val goal = left!!
        if (pos == null) {
            Problems.trace("plan", "One point left during the tour, no position: route guidance ended, recording continues")
            pendingSplit = null
            follow.clearFollow()
            follow.guidanceEndedByEmptyPlan = true
            host.noPositionNote(PlanGps.Note.ONE_POINT_NO_POSITION, Res.string.msg_one_point_no_position)
            return
        }
        pendingSplit = null // a whole new plan from here, nothing of it passed
        plan.fromHere(pos, goal)
        host.mapsForNewPoints(before, waypoints)
        Problems.trace("plan", "One point left during the tour: plan = position → that point (like Hierhin)")
    }

    /**
     * Bug 02: the position while a tour is led to this plan ("Guide me") and has never reached it – a new point that fits
     * on the way there becomes the new start ([WaypointInsertion] rule 4); else null.
     */
    fun wayThereFrom(): GeoPoint? {
        val s = recState.value?.nav?.session ?: return null
        if (!s.lead || s.reached || s.kind != com.onpath.app.navigation.NavKind.PLAN || followIsTrack) return null
        return myPosition()
    }

    /**
     * The plan was edited while a tour follows it (round trip, Undo, points added / removed, reverse): the tour follows
     * the new plan at once – followed line, target and arrival – without a question (owner rule). A
     * re-computed line that is the same route (Undo back to it) keeps the running session. A followed GPX track or a
     * free recording are not touched; a plan with fewer than two points changes nothing.
     */
    fun followEditedPlan() {
        val r = route ?: return
        if (PlanGuidance.resumes(follow.guidanceEndedByEmptyPlan, recState.value != null, followLine != null, r.points.size)) {
            Problems.trace("plan", "Plan points back during the tour: following the plan again")
            follow.followPlanned(checkNow = true)
            return
        }
        if (recState.value == null || followLine == null || followIsTrack) return
        // the plan the tour follows changed (also an edit of passed points only): its copy follows
        follow.recopyFollowedPlan()
        val split = pendingSplit; pendingSplit = null
        when (split) {
            // only passed points edited: the guidance ahead stays, the plan line counts as followed
            TourSplit.Outcome.Unchanged -> { follow.planBaseline = r.points; return }
            // "No destination ahead" is open: the followed line stays until it is answered
            TourSplit.Outcome.NothingAhead -> return
            is TourSplit.Outcome.Follow -> {
                // Undo / Redo back to a plan followed before in this tour: that route exactly (FollowHistory)
                val now = recState.value?.nav?.session?.takeIf { it.kind == com.onpath.app.navigation.NavKind.PLAN }
                val back = followHistory.find(waypoints.toList())
                val before = splitBefore
                if (now != null && before != null) followHistory.remember(before, now)
                if (back != null) { follow.followAgain(back, r.points, now?.version ?: 0); return }
                followAhead(split.ahead, r.points)
                return
            }
            else -> Unit
        }
        if (r.points.size < 2) return // nothing to follow (e.g. all points on one spot): keep the running line
        val orig = follow.followOriginal
        if (follow.planFollowed(r.points)) return
        if (orig != null && NavLine.sameRoute(r.points, orig)) { follow.planBaseline = r.points; return }
        // Bug 02: back at plan points followed before in this tour (Undo / Redo) → that route exactly, a way there
        // ("Guide me") or a taken re-route included, no routing; the session it replaces is kept for Redo
        val now = recState.value?.nav?.session?.takeIf { it.kind == com.onpath.app.navigation.NavKind.PLAN }
        val back = followHistory.find(waypoints.toList())
        now?.let { followHistory.remember(it.stops.map { st -> st.point }, it) }
        if (back != null) { follow.followAgain(back, r.points, now?.version ?: 0); return }
        follow.followPlanned()
    }

    /**
     * The tour follows position → [ahead] ([TourSplit.Outcome.Follow]): routed like any plan (the same [PlanResult] the
     * web planner uses), only for these points. [planLine] = the whole plan's route, which then counts as followed (Plan
     * draws the followed line). No route found: the guidance followed so far stays, a red box says so.
     */
    private fun followAhead(ahead: List<GeoPoint>, planLine: List<GeoPoint>) {
        val pos = myPosition()
        val pts = listOfNotNull(pos) + ahead
        if (pts.size < 2) { follow.followPlanned(); return } // no position yet and one point ahead: the plan as a whole
        val gen = splitRequests.next(waypoints)
        val act = activity; val level = sac; val f = planRoute.planFactors(act)
        val off = if (pos != null) 1 else 0
        val pins = ahead.indices.filter { ahead[it] in pinned }.map { it + off }.toSet()
        val name = savedName ?: host.newRouteName(Txt.s(Res.string.msg_name_route))
        val firstAhead = waypoints.indexOf(ahead.first()) + 1
        // a newer edit stops this run ([recalc]); the legs ahead come from the plan's run just before (LegCache) – only
        // position → first point ahead is new
        followCancel?.cancel()
        val cancel = com.onpath.app.routing.RouteCancel().also { followCancel = it }
        scope.launchSafe("app", "followAhead") {
            val res = withContext(routingDispatcher) {
                exceptions {
                    com.onpath.app.routing.PlanResult.plan(Planner(planRoute.routingEngine(), cancel, legs = planRoute.legCache), pts, act, level,
                        pins, emptySet(), f)
                }
            }
            if (followCancel === cancel) followCancel = null
            if (cancel.isCancelled) {
                Problems.trace("tour", "Follow line to the ${ahead.size} points ahead stopped – a newer edit came")
                return@launchSafe
            }
            if (!splitRequests.isCurrent(gen, waypoints.toList()) || recState.value == null) return@launchSafe
            val line = res.getOrNull()?.route?.takeIf { it.error == null && it.points.size >= 2 }?.points
            if (line == null) {
                Problems.warn("tour", "No route from the position to the ${ahead.size} points ahead; the guidance so far stays",
                    res.exceptionOrNull())
                sayError(Txt.s(Res.string.nav_no_route_ahead, firstAhead), NoticePlace.MAP)
                return@launchSafe
            }
            follow.setFollow(name, line, isTrack = false, checkNow = true, stops = ahead, names = ahead.map { host.placeName(it) },
                pinned = ahead.indices.filter { ahead[it] in pinned }.toSet(), firstNumber = firstAhead)
            follow.planBaseline = planLine
            follow.followFromPlan = true
            follow.copyFollowedPlan(act, level)
            Problems.trace("tour", "The tour follows position → ${ahead.size} points ahead of \"$name\" (${line.size} line points)")
        }
    }

    /** A shown track followed during a tour: the plan leaves the map first, then the track is followed, closed as shown, Navigate. */
    fun followShownNow() {
        if (recState.value == null) return
        if (waypoints.isNotEmpty()) {
            Problems.trace("plan", "Track followed during the tour: the plan (${waypoints.size} points) leaves the map (Undo brings it back)")
            planEdit.clearPlan()
        }
        follow.followShown(); host.closeShownTrack(); host.resumeNavigation()
    }

    /**
     * Like runCatching, but only for exceptions (review Arch 9): a cancellation goes on to the coroutine, an Error
     * (OutOfMemoryError) is not taken for a failed route.
     */
    private inline fun <T> exceptions(block: () -> T): Result<T> = try { Result.success(block()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}
