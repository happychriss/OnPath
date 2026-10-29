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
