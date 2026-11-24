package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.OpenAsk
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanLinks
import com.onpath.app.routing.SharedPlan
import com.onpath.app.ui.Camera
import com.onpath.app.ui.TrackThen
import com.onpath.app.ui.plan.PlanDocument

/**
 * Something else takes the plan's place (a track or a plan followed during a tour, "Als Plan übernehmen"): no question any
 * more (owner 2026-11-06) – the replaced plan goes under Zuletzt ([SaveState.keepAsDraft]). [what] is for the log.
 */
fun unlessUnsaved(what: String, go: () -> Unit) { Problems.trace("plan", "$what: the plan on the map goes under Zuletzt if not saved"); go() }

/** What [RoutesOpenState] asks of the rest of the app (the main thread). */
interface RoutesOpenPorts {
    /** A tour is being recorded. */
    val touring: Boolean
    val mode: AppMode
    /** Switches to [m] (Plan / Navigate). */
    fun enterMode(m: AppMode)
    /** [t] opened on the map (a planned route becomes the plan, a done tour / track is shown). */
    fun openTour(t: Tour, duringTour: Boolean = false, trackThen: TrackThen = TrackThen.SHOW)
    /** The tour's "Neuer Plan" line (with an Undo when [undo]). */
    fun noteNewPlan(undo: Boolean)
    /** The "Neuer Plan" line's Undo flag; null = no line. */
    val newPlanUndo: Boolean?
    fun dropNewPlanNote()
    /** One Undo step of the plan. */
    fun undoStep()
    /** The followed line is a shown track. */
    val followIsTrack: Boolean
    /** Back to Navigate while a tour runs: the tour follows the plan again. */
    fun continueNavigation()
    /** The shown track is gone. */
    fun clearShown()
    /** [a] is the plan's activity now (remembered). */
    fun setActivity(a: Activity)
    /** [level] is the plan's T level now (remembered). */
    fun setSac(level: Int)
    /** The plan's points are changed by [block] – one Undo step, stored, routed. */
    fun change(block: MutableList<GeoPoint>.() -> Unit)
    fun rememberPlaceName(p: GeoPoint, name: String)
    /** The plan was saved / loaded as [name] (route [id], [points]); null / -1 = not saved. */
    fun saved(name: String?, id: Long, points: List<GeoPoint>?)
    /** A plan link opened as the plan: its title, its points. */
    fun linkOpened(title: String?, points: List<GeoPoint>)
    /** The start camera keeps away from the position after a shared plan / place was shown. */
    fun holdCameraForSharedPlace()
    /** The map follows the position. */
    var followMe: Boolean
    /** Navigate free look. */
    var freeLookAt: GeoPoint?
    fun routeEvent(e: RouteRun.Event)
}

/**
 * What a tap in My routes and a plan link do to the plan (cut out of `RoutesGoSlice`): [requestOpen], [undoNewPlan],
 * [openSharedPlan]. Android-free and coroutine-free: the browser planner takes this file as it is.
 */
class RoutesOpenState(
    private val plan: PlanDocument,
    private val routes: RoutesState,
    private val save: SaveState,
    private val camera: Camera,
    private val ports: RoutesOpenPorts,
) {
    /** Navigate mode or a tour recording: no loading into the plan, no Clear. */
    fun navigating(recording: Boolean): Boolean = ports.mode == AppMode.NAVIGATE || recording

    /**
     * Open / load from My routes ([RoutesState.openAsk]). During a tour no question (owner 2026-11-06 22:05): a
     * planned route is followed at once ([OpenAsk.FOLLOW_PLAN_DURING_TOUR]), a done tour / track too ([OpenAsk.FOLLOW_DURING_TOUR]); the
     * recording goes on, Navigate says so and "Rückgängig" brings the plan from before ([undoNewPlan]). Without a tour the current
     * plan goes under "Zuletzt" (owner 2026-11-06).
     */
    fun requestOpen(t: Tour, why: String = "My routes tapped") {
        Problems.trace("routes", "Open tour ${t.id} \"${t.name}\" (${t.kind}, ${t.distanceM.toInt()} m): $why")
        val ask = routes.openAsk(t, navigating(ports.touring), plan.points, plan.savedPoints)
        val hadPlan = plan.points.isNotEmpty()
        when (ask) {
            OpenAsk.FOLLOW_PLAN_DURING_TOUR -> {
                Problems.trace("tour", "Plan \"${t.name}\" tapped during the tour: followed at once (no question), the recording goes on")
                ports.noteNewPlan(undo = hadPlan)
                unlessUnsaved("Load \"${t.name}\" during the tour") { ports.openTour(t, duringTour = true) }
            }
            OpenAsk.FOLLOW_DURING_TOUR -> {
                Problems.trace("tour", "Track \"${t.name}\" tapped during the tour: followed at once (no question), the recording goes on")
                ports.noteNewPlan(undo = hadPlan)
                unlessUnsaved("Follow \"${t.name}\" during the tour") { ports.openTour(t, trackThen = TrackThen.FOLLOW) }
            }
            OpenAsk.REPLACE_PLAN, OpenAsk.NONE -> ports.openTour(t)
        }
    }

    /**
     * "Rückgängig" on the "Neuer Plan" line: the plan from before comes back in one Undo step and the tour follows it again – a
     * followed track gives way to it as at "Weiter geht's". Only offered when there was a plan before.
     */
    fun undoNewPlan() {
        val undo = ports.newPlanUndo ?: return
        ports.dropNewPlanNote()
        if (!undo) return
        Problems.trace("plan", "Rückgängig on \"Neuer Plan\": the plan from before comes back, the tour follows it")
        ports.undoStep()
        if (ports.followIsTrack && ports.touring) ports.continueNavigation()
    }

    /** Puts a plan from a link into the plan: points, activity, T level, point names, pinned points – a new, unsaved plan. */
    fun openSharedPlan(plan: SharedPlan, via: String = "after asking") {
        val load = PlanLinks.load(plan)
        if (ports.mode != AppMode.PLAN) ports.enterMode(AppMode.PLAN)
        ports.clearShown()
        ports.setActivity(plan.activity)
        ports.setSac(plan.sac.coerceIn(1, 5))
        // pins before the points: the first route already passes them exactly (recalc reads them at once)
        if (load.pinned.isNotEmpty()) this.plan.pin(load.pinned)
        save.keepAsDraft("replaced by a plan link")
        ports.change { clear(); addAll(load.points) }
        load.names.forEach { (p, n) -> ports.rememberPlaceName(p, n) }
        ports.saved(null, -1, null) // not in My routes yet: Save asks for a name
        ports.linkOpened(plan.title, load.points)
        Problems.info("plan", "Plan link opened ($via): ${load.points.size} points, ${plan.activity.name}" +
            (if (plan.activity == Activity.HIKE) " T${plan.sac}" else "") + ", ${load.pinned.size} pinned, ${load.names.size} names")
        // the map shows the plan (field A-019: it stayed where it was): its points now, the whole route once computed;
        // the start camera must not centre on the position afterwards, following stops first (CLAUDE.md "Map camera")
        ports.holdCameraForSharedPlace()
        ports.followMe = false
        ports.freeLookAt = null
        ports.routeEvent(RouteRun.Event.FitNext) // the whole line shows once computed (field A-019)
        camera.fit(load.points)
    }
}
