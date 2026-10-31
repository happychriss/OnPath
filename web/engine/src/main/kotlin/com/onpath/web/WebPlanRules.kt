package com.onpath.web

import com.onpath.app.routing.Activity
import com.onpath.app.routing.LongRoute
import com.onpath.app.ui.PlanStatus
import com.onpath.strings.*

/**
 * The app's plan rules the page asks before it routes (docs/design/web-planner.md §15): the long-route question
 * ([LongRoute], AppViewModel.recalc / answerLongRoute) and what Cancel does ([PlanStatus.onCancel]).
 */
object WebPlanRules {
    /**
     * Owner: a plan far beyond a day (a point in another country by mistake) is asked about first – nothing is routed
     * or loaded until the answer. `{"ask":false}` or the question with the app's texts (TourBar.LongRouteDialog).
     */
    fun longRoute(points: String, activity: String): String {
        val pts = Engine.parsePoints(points)
        val act = Activity.valueOf(activity)
        if (!LongRoute.needsAsk(pts, act)) return "{\"ask\":false}"
        val s = LongRoute.estimateS(pts, act)
        return "{\"ask\":true,\"seconds\":" + Engine.num(s, 0) +
            ",\"title\":" + Engine.str(Txt.s(Res.string.nav_long_route_title)) +
            ",\"text\":" + Engine.str(longRouteText(s, act)) +
            ",\"plan\":" + Engine.str(Txt.s(Res.string.nav_plan_anyway)) +
            ",\"cancel\":" + Engine.str(Txt.s(Res.string.common_cancel)) +
            ",\"notRouted\":" + Engine.str(PlanStatus.notRoutedText(com.onpath.app.ui.NotRouted.LONG)) +
            ",\"again\":" + Engine.str(PlanStatus.notRoutedAction(com.onpath.app.ui.NotRouted.LONG)) + "}"
    }

    /** Mirror of the app's ui/TourBar.kt `longRouteText` (a Compose file the engine cannot compile; web/parity.json). */
    internal fun longRouteText(seconds: Double, activity: Activity): String {
        val how = if (activity == Activity.HIKE) Res.string.nav_long_route_foot else Res.string.nav_long_route_bike
        return Txt.s(how, LongRoute.describe(seconds)).replaceFirstChar { it.uppercase() }
    }

    /**
     * Plan points the route does not reach (the app's [com.onpath.app.ui.PlanGaps]: a summit above the T level, a
     * point off any path – the router stops at the nearest allowed way): the sheet's red line, combined with a failed
     * leg like PlanPanel does (one line, "+N more"), and the grey dashed pieces. For the last plan of exactly these
     * points; [names] = lines "index<TAB>name".
     */
    fun gaps(points: String, activity: String, names: String): String {
        val wps = Engine.parsePoints(points)
        val p = Engine.lastPlanFor(wps) ?: return "{\"text\":null,\"gaps\":[]}"
        // a stop the route still skips although it lies on a way (Bug 01) keeps its place and shows its gap, as in the app
        val gaps = com.onpath.app.ui.PlanGaps.of(wps, p.snapped, p.skipped)
        val nm = names.split('\n').mapNotNull { l -> l.substringBefore('\t').toIntOrNull()?.let { it to l.substringAfter('\t') } }.toMap()
        val gapText = com.onpath.app.ui.PlanGaps.text(gaps, Activity.valueOf(activity)) { nm[it] }
        val text = when {
            gapText == null -> null
            p.error != null && !com.onpath.app.ui.PlanGaps.hasMore(gapText) -> com.onpath.app.ui.PlanGaps.withMore(gapText, 1)
            else -> gapText
        }
        return "{\"text\":" + (text?.let { Engine.str(it) } ?: "null") + ",\"gaps\":" + gaps.joinToString(",", "[", "]") { g ->
            val (at, zoom) = com.onpath.app.ui.PlanGaps.showTarget(g)
            "{\"index\":" + g.index + ",\"point\":[" + Engine.num(g.point.lat, 6) + "," + Engine.num(g.point.lon, 6) + "],\"end\":[" +
                Engine.num(g.routeEnd.lat, 6) + "," + Engine.num(g.routeEnd.lon, 6) + "],\"show\":[" + Engine.num(at.lat, 6) + "," +
                Engine.num(at.lon, 6) + "," + Engine.num(zoom, 1) + "]}"
        } + "}"
    }

    /**
     * Cancel of the long-route question for a run started by [cause] ("edit", "undo", "redo", "activity", other):
     * "UNDO" / "REDO" / "RESTORE_ACTIVITY" / "KEEP_POINTS" ([PlanStatus.onCancel]).
     */
    fun onCancel(cause: String, canUndo: Boolean, canRedo: Boolean): String {
        val c = when (cause) {
            "edit" -> PlanStatus.Cause.PointEdit
            "undo" -> PlanStatus.Cause.Undo
            "redo" -> PlanStatus.Cause.Redo
            "activity" -> PlanStatus.Cause.ActivityChange(Activity.HIKE, 2) // only the kind matters here
            else -> PlanStatus.Cause.Other
        }
        return PlanStatus.onCancel(c, canUndo, canRedo).name
    }
}
