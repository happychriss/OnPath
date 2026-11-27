package com.onpath.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.onpath.app.debug.Problems
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LongAsk
import com.onpath.app.routing.PhoneLongRule
import com.onpath.app.routing.PlanPointRow
import com.onpath.app.ui.plan.PlanEditSlice
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.app.ui.plan.PlanRunSlice

/** The open long-leg question's leg ([LongAsk]) with the names of its ends (null = unnamed). */
data class LongLegNames(val ask: LongAsk, val from: String?, val to: String?)

/**
 * The leg of the long-leg question now open – set by AppViewModel when it is asked (main thread), read by
 * [LongRouteDialog] (MapScreen passes only the seconds). Null = none.
 */
object LongLegShown {
    var leg by androidx.compose.runtime.mutableStateOf<LongLegNames?>(null)
}

/**
 * The app's long-leg question (owner 2026-11-27, [com.onpath.app.routing.RouteCost]): its rule, the names of the leg's
 * ends and what its answer does. Called by AppViewModel on the main thread.
 */
internal object LongLegs {
    /** The app's [RouteRun.longRule]: this phone's learned factor; a leg in the leg cache (memory, or its file unless quick) is routed. */
    fun rule(context: Context, planRoute: () -> PlanRouteSlice): PhoneLongRule {
        val memory = com.onpath.app.data.progressMemoryOf(context)
        return PhoneLongRule({ memory.phoneFactor }, { a, b, quick ->
            val r = planRoute()
            if (quick) r.legCache.inMemory(a, b, r.activity, r.sac) != null else r.legCache.has(a, b, r.activity, r.sac)
        }, lowEnd = true)
    }

    /** The question for [ask] is open: its leg's names from the Points list [rows] of a plan of [points] points, for the dialog. */
    fun asked(ask: LongAsk, rows: List<PlanPointRow>, points: Int) {
        val names = rows.associate { it.planIndex to it.name.takeIf { n -> n.isNotBlank() } }
        // a round trip's closing point is no row: its name is the start's
        fun name(i: Int) = names[i] ?: if (i == points - 1) names[0] else null
        LongLegShown.leg = if (ask.leg < 0) null else LongLegNames(ask, name(ask.leg), name(ask.leg + 1))
    }

    /**
     * The answer: „Berechnen“ ([yes]) → routed, not asked again; „Abbrechen“ → the points stay without a route (the
     * not-routed line's „Berechnen“ asks again; owner 2026-11-27: no „Zwischenziel setzen“ – nobody knows where it belongs) – during a tour ([touring]) the change that made it so long is
     * taken back instead, so the tour keeps its line. True = the camera goes to the plan ([LongRouteCancelCamera]).
     */
    fun answer(yes: Boolean, planRun: PlanRunSlice, planEdit: PlanEditSlice, points: List<GeoPoint>, touring: Boolean,
               removePin: (String) -> Unit = {}): Boolean {
        // „Zu weit für das Handy“ → OK (owner 2026-11-27): the far point is not set – the change is taken back, its pin goes
        // too („ich komme ja eh nicht hin“), the map goes back to the plan / the position
        val beyond = planRun.routeRun.longAskLeg?.beyond == true
        LongLegShown.leg = null
        val effects = planRun.routeEvent(RouteRun.Event.LongAnswer(yes, points))
        if (yes) { planRun.perform(effects); return false }
        // points kept unrouted: their maps load with the next edit that can be routed, or "Plan anyway"
        if (planRun.longRouteDeferred != null) Problems.trace("cells", "Long plan not routed: nothing was loaded for it yet")
        if (touring || beyond) planRun.perform(effects) // [PlanEditState.cancelRun]
        if (beyond) removePin("too far for the phone")
        else planEdit.keepUnrouted(NotRouted.LONG)
        return true
    }
}
