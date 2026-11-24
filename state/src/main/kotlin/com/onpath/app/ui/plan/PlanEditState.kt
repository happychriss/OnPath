package com.onpath.app.ui.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.debug.Problems
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlanGuidance
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.TakenPin
import com.onpath.app.domain.plan.RouteBack
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.domain.plan.RoutingSpot
import com.onpath.app.domain.plan.RunCause
import com.onpath.app.navigation.TourSplit
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.WholeRoute

/** Names route points from the offline place index (RouteNamer.label); blocking – called off the main thread. */
fun interface PointNames {
    fun label(p: GeoPoint): String?
}

/**
 * What [PlanEditSlice] asks from the rest of the app (main thread): the map's menus and camera, the tour (what an edit
 * means for a tour in progress – pieces 16–19 still hold that code in the view model), the route run's coroutine and the
 * neighbours of the plan lane. Every call is made at the point of the edit's sequence where the old code did it.
 */
interface PlanEditPorts {
    // ---- map menus and camera (pieces 5, 11, 12)
    /** A running hold-to-measure ends ([why] into the log). */
    fun cancelHoldMeasure(why: String)
    /** Editing on a map that keeps turning is confusing: in Plan with the heading up, following me stops (owner). */
    fun stopFollowingWhenMapTurns()
    fun closePointMenu(why: String)
    fun clearFirstPointMenu()
    fun clearHoldAddMenu()
    fun clearHighlight()
    /** The found place's pin goes ([why] into the log). */
    fun removePin(why: String)
    /** "Delete" in the point menu was tapped: closes the menu, returns the point's index in the plan now, or null. */
    fun menuAction(what: String): Int?
    /** The spinner's point of the route run ([RoutingSpot]). */
    fun setRoutingSpot(spot: GeoPoint?)
    /** The maps of the points the edit [before] → now added load (or wait for "Plan anyway"). */
    fun loadMapsForNewPoints(before: List<GeoPoint>)
    /** The plan is saved as it is for Zuletzt before it leaves the map (Clear). */
    fun keepAsDraft(why: String)
    /** The route line of the plan now, null = none. */
    fun routeLine(): List<GeoPoint>?
    /** The map's route calculation: the plan is planned again. */
    fun recalc()
    /** The activity and T level from before a cancelled change come back. */
    fun restoreActivity(c: PlanStatus.Cause.ActivityChange)
    /** The route-warnings notice follows the plan (PlanRouteSlice.planChanged). */
    fun planChanged()

    // ---- the tour (pieces 16-19)
    /** Current position (the map's GPS), else the tour's last recorded point; null without either. */
    fun myPosition(): GeoPoint?
    fun touring(): Boolean
    fun following(): Boolean
    fun followIsTrack(): Boolean
    /** The plan's route is the line followed now ([followLine] equals [line]). */
    fun followsLine(line: List<GeoPoint>): Boolean
    fun followFromPlan(): Boolean
    /** [TourSplit.outcome] of the edit [before] → [after] during a tour, kept for the tour's follow of the edited plan. */
    fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>): TourSplit.Outcome?
    /** The spoken directions say "no destination ahead". */
    fun sayNoDestination()
    /** One point left after an edit during a tour: "Hierhin" (see the view model). */
    fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean)
    /** An empty plan during a tour ends the route guidance. */
    fun endGuidanceIfPlanEmpty()
    /** The followed line goes. */
    fun clearFollow()
    /** The route guidance ended by an empty plan (Undo brings it back). */
    fun guidanceEndedByEmptyPlan()

    // ---- search
    /** Name of a route point added from a place, or null. */
    fun stopName(p: GeoPoint): String?
    /** The found place on the map (its pin). */
    var searchMark: com.onpath.app.search.Place?
}

/**
 * Editing the plan's points (rebuild phase 2 piece 10): the one [change] every edit goes through (history step in
 * [PlanDocument], what a tour in progress is told, the route run), the Points list, reverse / round trip, Undo / Redo,
 * Clear, the answer of a Cancel that takes an edit back, and the names of unnamed points. The pure rules live in :core
 * (PlanPoints, PlanEdits, RouteBack, PlanGuidance, ClearRule). Constructed with the view model (no work).
 * Coroutine-free (for the planner); the one piece of background work, the names of unnamed points, is [PlanEditSlice].
 */
open class PlanEditState(
    private val plan: PlanDocument,
    private val planRun: PlanRunSlice,
    /** What started the routing run: Cancel takes back only that ([PlanRouteSlice.runCauses]). */
    private val runCauses: RunCause,
    private val host: PlanEditPorts,
) {
    /**
     * Plan panel size (full / compact / mini / rolled up), kept while the app runs: a plan opens at the summary step
     * (owner 2026-10-31); rolled up by the user it stays rolled up through route updates until opened again.
     */
    var planSize by mutableStateOf(PlanSize.MINI)

    /**
     * The goal of a start → goal plan was deleted during a tour (owner, [RouteBack]): "Route back to the start?" stays
     * until answered (never self-dismissing). The followed line is unchanged while it is open. Set here and by the
     * tour's answer (view model, pieces 16–19).
     */
    var routeBackAsk by mutableStateOf(false)

    /** Route details (way types, surfaces, difficulty, full profile) – opened from the sheet's profile or badge. */
    var routeDetailsOpen by mutableStateOf(false)

    /** The found place the last edit added ([TakenPin]): a Cancel that undoes that edit shows its pin again. */
    var pinTaken: TakenPin? = null

    /** Nearest place / hut / peak for route points without a name of their own (offline index, RouteNamer.label). */
    var nearbyNames by mutableStateOf<Map<GeoPoint, String>>(emptyMap())
        private set

    private val waypoints: List<GeoPoint> get() = plan.points

    /**
     * One undoable change of the planned points. No vibration here: the plan vibrates only at the moment of a map hold
     * (PlanHaptics, owner), never after an edit.
     */
    fun change(block: MutableList<GeoPoint>.() -> Unit) {
        host.cancelHoldMeasure("plan changed")
        pinTaken = null // a newer edit: only the add itself can bring its pin back (the add sets it after this)
        // editing on a map that keeps turning is confusing: stop following, the map stays at its angle (owner)
        host.stopFollowingWhenMapTurns()
        val before = plan.edit(block)
        if (WholeRoute.firstRoute(before.size, waypoints.size)) planRun.routeEvent(RouteRun.Event.FitNext)
        host.setRoutingSpot(RoutingSpot.of(before, waypoints))
        host.loadMapsForNewPoints(before)
        plan.prunePinned()
        planRun.routeEvent(RouteRun.Event.PlanEdited)
        val split = host.splitOf(before, waypoints.toList())
        val pos = host.myPosition()
        // the one point left is where I stand (or only the start the one-point rule put in): nothing to ask about – the
        // plan empties in oneLeftGoesFromHere (field bug 2026-11-06; standing on point 1 also counts it as passed, so the
        // split says NothingAhead)
        val leftIsHere = plan.leftIsHere(pos)
        routeBackAsk = !leftIsHere && (split == TourSplit.Outcome.NothingAhead ||
            RouteBack.asks(before, waypoints, host.touring(), host.following(), host.followIsTrack(), pos))
        if (routeBackAsk) {
            Problems.trace("plan", "Edit during the tour left no point ahead: asking \"No destination ahead\"")
            host.sayNoDestination()
        }
        host.oneLeftGoesFromHere(before, mayEmpty = true)
        host.planChanged()
        host.endGuidanceIfPlanEmpty()
        runCauses.pointEdit()
        host.recalc()
    }

    /** The plan becomes [list] in one undoable step ([change]). */
    fun set(list: List<GeoPoint>) = change { clear(); addAll(list) }

    /** Plan point [index] deleted (map menu, search card): a round trip stays closed, as in the Points list. */
    fun removePoint(index: Int) {
        val after = com.onpath.app.routing.PlanPoints.deleteAt(waypoints, index)
        if (after.size != waypoints.size) set(after)
    }

    // ---------------- Points section of the full plan sheet (PointsSection, routing/PlanPoints) ----------------

    /** Rows of the Points section: names (pinned POI / search first, then the nearest place), leg distances. */
    fun planPointRows(route: com.onpath.app.routing.PlannedRoute?): List<com.onpath.app.routing.PlanPointRow> {
        val pts = waypoints.toList()
        val line = route?.takeIf { !planRun.routing }?.points
        return com.onpath.app.routing.PlanPoints.rows(pts, line) { i -> pts.getOrNull(i)?.let { host.stopName(it) ?: nearbyNames[it] } }
    }

    /** The route points still without a name (neither a pinned / searched place nor a known nearest place). */
    fun pointNamesTodo(): List<GeoPoint> = waypoints.toList().filter { it !in nearbyNames && host.stopName(it) == null }.distinct()

    /** The names of [todo] from the offline place index: blocking – called off the main thread. */
    fun findPointNames(todo: List<GeoPoint>, names: PointNames): Map<GeoPoint, String> =
        todo.mapNotNull { p -> names.label(p)?.let { p to it } }.toMap()

    /** The names found for the points (main thread). */
    fun pointNamesFound(found: Map<GeoPoint, String>) {
        nearbyNames = nearbyNames + found
    }

    /** Points list: row [from] dropped at row [to] (hold ≡ and drag); to the top = the new start. Undo reverts. */
    fun movePointInList(from: Int, to: Int) {
        val after = com.onpath.app.routing.PlanPoints.move(waypoints.toList(), from, to) // a copy: move() hands the list itself back when nothing moves
        if (after == waypoints.toList()) return
        Problems.trace("plan", "Points list: point ${from + 1} moved to ${to + 1} (plan ${waypoints.size} points)")
        set(after)
    }

    /** Points list → ✕: deleted at once (Undo brings it back); a round trip stays closed at its start. */
    fun deletePointFromList(index: Int) {
        val after = com.onpath.app.routing.PlanPoints.delete(waypoints, index)
        if (after.size == waypoints.size) return
        Problems.trace("plan", "Points list: point ${index + 1} deleted (plan ${waypoints.size} → ${after.size})")
        set(after)
    }

    /** Point menu → "Delete" (Undo brings it back). */
    fun deletePointFromMenu() {
        val i = host.menuAction("Delete") ?: return
        val before = waypoints.size
        val gone = waypoints[i]
        removePoint(i)
        // another marker on the same spot still looks like "the point stayed" (a point snapped next to it)
        val twin = waypoints.withIndex().minByOrNull { com.onpath.app.data.Geo.distance(it.value, gone) }
            ?.takeIf { com.onpath.app.data.Geo.distance(it.value, gone) < 100.0 }
        Problems.trace("plan", "Point ${i + 1} deleted: plan $before → ${waypoints.size}, route recalculation " +
            (if (planRun.routing) "started" else "not needed") +
            (twin?.let { ", point ${it.index + 1} is only ${com.onpath.app.data.Geo.distance(it.value, gone).toInt()} m away" } ?: ""))
    }

    fun makeDestination(index: Int) {
        // the last row; a round trip stays closed at its start (as dragging the row to the end of the Points list)
        if (index in waypoints.indices && index != waypoints.lastIndex) set(com.onpath.app.routing.PlanPoints.move(waypoints, index, waypoints.size - 1))
    }
    /** "⇅ Umkehren" changes the plan (greyed out for an out-and-back over one point, owner 2026-11-06 #40). */
    fun canReverse(): Boolean = PlanEdits.reverseChanges(waypoints)
    fun reverseRoute() {
        if (!PlanEdits.reverseChanges(waypoints)) {
            Problems.trace("plan", "Reverse: nothing to reverse (plan ${waypoints.size} points, the same backwards)"); return
        }
        Problems.trace("plan", "Reverse: plan ${waypoints.size} points")
        set(PlanEdits.reverse(waypoints))
    }

    /** "↻ Rundtour" can be switched: on (adds the way back to the start) or off (the plan is a round trip). */
    fun canRoundTrip(): Boolean = PlanEdits.isRoundTrip(waypoints) || PlanEdits.canRoundTrip(waypoints)

    /**
     * "↻ Rundtour" is a switch (owner 2026-11-06, onpath.private#40): on → the start becomes the final destination; on a
     * round trip a tap takes it back – the closing point goes (A → B → A → A → B). One Undo step either way.
     */
    fun roundTrip() {
        val on = PlanEdits.isRoundTrip(waypoints)
        val w = if (on) PlanEdits.unRoundTrip(waypoints) else PlanEdits.roundTrip(waypoints)
        if (w.size == waypoints.size) { Problems.trace("plan", "Round trip: nothing to change (plan ${waypoints.size} points)"); return }
        Problems.trace("plan", "Round trip ${if (on) "off" else "on"}: plan ${waypoints.size} → ${w.size} points")
        set(w)
    }

    fun undoStep() = step(plan.undo(), "Undo")
    fun redoStep() = step(plan.redo(), "Redo")

    private fun step(prev: List<GeoPoint>?, what: String) {
        if (prev == null) { Problems.trace("plan", "$what: nothing to ${what.lowercase()}"); return }
        Problems.trace("plan", "$what: plan ${waypoints.size} → ${prev.size} points")
        pinTaken = null // the user's own Undo / Redo: a later Cancel does not bring a pin back
        val before = waypoints.toList()
        host.setRoutingSpot(RoutingSpot.of(before, prev))
        val split = host.splitOf(before, prev)
        plan.replace(prev)
        // the plan an open question was about is gone; one point left where I stand asks nothing (standing on it counts it
        // as passed, so the split says NothingAhead – GitHub run 2026-11-07, as for edits in change())
        routeBackAsk = split == TourSplit.Outcome.NothingAhead && !plan.leftIsHere(host.myPosition())
        host.oneLeftGoesFromHere(before, mayEmpty = false)
        host.closePointMenu("undo / redo"); host.clearFirstPointMenu(); host.clearHoldAddMenu(); host.cancelHoldMeasure(what)
        planRun.routeEvent(RouteRun.Event.PlanEdited)
        host.planChanged()
        host.endGuidanceIfPlanEmpty()
        runCauses.step(undo = what == "Undo")
        host.recalc()
    }

    /**
     * Resets the whole plan in one undoable step: points, route line, pinned marks (kept while
     * undo can bring them back), error, highlight, notice and point menu. A routing job still
     * running is cancelled and its result ignored (recalc → a new [RouteRun.gen]).
     * A followed copy of this plan goes too, unless a recording is using it right now.
     */
    fun clearPlan() {
        host.keepAsDraft("cleared")
        Problems.trace("plan", "Clear route: plan ${waypoints.size} → 0 points" + if (host.touring()) " (tour running)" else "")
        val planLine = host.routeLine()
        if (waypoints.isNotEmpty()) change { clear() } else host.recalc()
        host.closePointMenu("plan cleared"); host.clearFirstPointMenu(); host.clearHoldAddMenu(); host.clearHighlight()
        host.removePin("plan cleared")
        // the empty sheet says how to start a plan: not rolled up
        if (planSize == PlanSize.COLLAPSED) planSize = PlanSize.MINI
        // no tour: the followed line goes with the plan – also when it was the way there ("Guide me") or the app restarted
        // meanwhile (followFromPlan lives in memory only; Pixel 2026-11-01: a blue line without points stayed). A shown
        // GPX track keeps its own line.
        if (com.onpath.app.domain.plan.ClearRule.dropsFollowWithoutTour(following = host.following(),
                recording = host.touring(), followFromPlan = host.followFromPlan(),
                followIsPlanLine = planLine != null && host.followsLine(planLine), followIsTrack = host.followIsTrack())) host.clearFollow()
        // during a tour (owner 2026-11-05): "Neu planen" ends every guidance – also a followed track, also when the plan was
        // already empty; Navigate shows a free tour, the recording never stops. Undo brings the points (and following them).
        if (PlanGuidance.clearEndsGuidance(host.touring(), host.following())) {
            Problems.trace("plan", "Plan cleared during the tour: route guidance ended, recording continues")
            host.clearFollow()
            host.guidanceEndedByEmptyPlan()
        }
    }

    /**
     * Cancel of the long-route question or of a running calculation: a point edit is
     * undone (Undo / Redo the other way), an activity / T level change gets the previous activity back, anything else
     * just stops and leaves the points unrouted.
     */
    fun cancelRun(why: NotRouted) {
        val cause = runCauses.takeForCancel()
        val what = PlanStatus.onCancel(cause, plan.canUndo, plan.canRedo)
        Problems.trace("routing", "Cancel ($why) of a run for ${cause::class.simpleName}: ${what.name.lowercase()}")
        val taken = pinTaken; pinTaken = null
        planRun.leaveUnrouted(why)
        when (what) {
            PlanStatus.OnCancel.UNDO -> undoStep()
            PlanStatus.OnCancel.REDO -> redoStep()
            PlanStatus.OnCancel.RESTORE_ACTIVITY -> (cause as? PlanStatus.Cause.ActivityChange)?.let { host.restoreActivity(it) }
            PlanStatus.OnCancel.KEEP_POINTS -> Unit
        }
        // the add of a found place taken back: the place stays as its pin (field 2026-11-07, S10 – never forgotten)
        TakenPin.back(taken, waypoints.toList(), host.searchMark)?.let { p ->
            host.searchMark = p
            Problems.trace("plan", "Cancel took back the add of \"${p.title()}\": its pin is shown again")
        }
    }
}
