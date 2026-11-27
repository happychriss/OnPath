package com.onpath.app.domain

import com.onpath.strings.*

/** Why a plan with two or more points has no route on purpose (the sheet says so and offers to calculate it). */
enum class NotRouted {
    /** "This route is very long" was answered Cancel and there was no change to take back (e.g. a plan restored at start). */
    LONG,
    /** "Calculating the route… Cancel" was tapped and there was no change to take back. */
    STOPPED,
}

/**
 * Plan sheet status without a route, and what Cancel does. Invariant: "Calculating the route…" only while a routing
 * request runs – never left standing after a Cancel (Start disabled, no way out).
 */
object PlanStatus {
    val CALCULATING: String get() = Txt.s(Res.string.plan_calculating)

    /**
     * The wait card's line (owner 2026-11-05): "Abschnitt 2 von 4 wird berechnet…" while legs are routed one by
     * one ([progress] = legs done / legs to route, only the legs the router works on), else [CALCULATING]. Every leg
     * routed ([finishing]): "Route wird geprüft…" – times, warnings, what reaches a stop; not "Abschnitt 4 von 4".
     */
    fun calculating(progress: Pair<Int, Int>?): String =
        if (finishing(progress)) Txt.s(Res.string.plan_finishing)
        else if (hasLegs(progress)) Txt.s(Res.string.plan_calculating_leg, minOf(progress!!.first + 1, progress.second), progress.second)
        else CALCULATING

    /** All legs are routed and the result is worked out (nothing to measure: the bar runs). */
    fun finishing(progress: Pair<Int, Int>?): Boolean = progress != null && progress.second > 0 && progress.first >= progress.second

    /** A determinate bar: two or more legs to route ([calculating]); one leg (or none known) = the running bar. */
    fun hasLegs(progress: Pair<Int, Int>?): Boolean = progress != null && progress.second >= 2

    /** The bar's share: legs done / legs to route. */
    fun share(progress: Pair<Int, Int>): Float = (progress.first.toFloat() / progress.second).coerceIn(0f, 1f)

    /** What Cancel does: Undo / Redo one plan step, put the activity and T level back, or keep the points unrouted. */
    enum class OnCancel { UNDO, REDO, RESTORE_ACTIVITY, KEEP_POINTS }

    /**
     * What started a routing run: Cancel takes back only *that* change – never the
     * last point edit after an activity change.
     */
    sealed interface Cause {
        /** A point edit (add, delete, move, reverse, clear …): one Undo step. */
        data object PointEdit : Cause
        /** Undo: Cancel brings the step back (Redo). */
        data object Undo : Cause
        /** Redo: Cancel takes it back (Undo). */
        data object Redo : Cause
        /** The activity / T level changed from [activity] / [sac]: Cancel puts them back. */
        data class ActivityChange(val activity: com.onpath.app.routing.Activity, val sac: Int) : Cause
        /** Anything else (app start, newer routing data, a plan loaded, route back …): Cancel just stops. */
        data object Other : Cause
    }

    /** Cancel (long-route question or a running calculation) for a run started by [cause]. */
    fun onCancel(cause: Cause, canUndo: Boolean, canRedo: Boolean): OnCancel = when (cause) {
        Cause.PointEdit -> if (canUndo) OnCancel.UNDO else OnCancel.KEEP_POINTS
        Cause.Redo -> if (canUndo) OnCancel.UNDO else OnCancel.KEEP_POINTS
        Cause.Undo -> if (canRedo) OnCancel.REDO else OnCancel.KEEP_POINTS
        is Cause.ActivityChange -> OnCancel.RESTORE_ACTIVITY
        Cause.Other -> OnCancel.KEEP_POINTS
    }

    /**
     * Second line of the sheet when there is no route yet, for [pointCount] points; null = none: an empty plan and one
     * point say nothing (owner 2026-11-27 – the hold / search hints went, the sheet's question is enough).
     */
    fun noRouteLine(pointCount: Int, routing: Boolean, notRouted: NotRouted?): String? = when {
        pointCount < 2 -> null
        routing -> CALCULATING
        else -> notRoutedText(notRouted)
    }

    /** Notice for a plan that is not routed (no request running). */
    fun notRoutedText(notRouted: NotRouted?): String = when (notRouted) {
        NotRouted.LONG -> Txt.s(Res.string.plan_not_routed_long)
        NotRouted.STOPPED -> Txt.s(Res.string.plan_not_routed_stopped)
        null -> Txt.s(Res.string.plan_not_routed)
    }

    /** Button of that notice. */
    fun notRoutedAction(notRouted: NotRouted): String = when (notRouted) {
        NotRouted.LONG -> Txt.s(Res.string.plan_anyway)
        NotRouted.STOPPED -> Txt.s(Res.string.plan_calculate)
    }
}

/**
 * The legs' part of one plan run's wait (owner 2026-11-05: it must move within one long leg too, never back): legs done
 * plus the running leg's search share (`BRouterEngine.searchProgress`, null = not known) over the legs to route, 0..1.
 * Keeps the highest value it gave – a new search inside the same leg (a retry) or a leg from the cache never moves it
 * back; every leg routed = 1. That it never stands either (owner 2026-11-18) is the wait window's ONE bar ([OneBar]):
 * it creeps on by time while this stands, inside the leg's share, and „prüfen“ follows as its own step
 * ([RouteShares]). One per run; not thread-safe (one poller).
 */
class WaitBar {
    private var last = 0f

    /** The legs' share 0..1, or null = nothing to measure (no legs to route). */
    fun next(done: Int, total: Int, leg: Double?): Float? {
        if (total <= 0) return null
        val v = ((done.coerceIn(0, total) + (if (done >= total) 0.0 else (leg ?: 0.0).coerceIn(0.0, 1.0))) / total).toFloat()
        last = maxOf(last, v.coerceIn(0f, 1f))
        return last
    }
}
