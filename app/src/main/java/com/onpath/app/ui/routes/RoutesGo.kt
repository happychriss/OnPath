package com.onpath.app.ui.routes

import com.onpath.app.routing.GeoPoint

/**
 * "Los geht's" from My routes (owner 2026-11-07): the route is loaded as the plan (an unsaved plan on the map goes under
 * Zuletzt as at every load – no question), its line is calculated as after "Planen", and once it is there the tour starts
 * by the plan sheet's own start path (phone check, "Gerade Linien?" ask, Hinführen when the start is far – Navigate's
 * question). Waits while the line is calculated; starts nothing when the plan was changed meanwhile, when no line came
 * (no map, routing failed, the long-route question – the plan sheet says why and its own Los geht's stays) or when a tour
 * runs already. Pure.
 */
object RoutesGo {
    enum class Step { WAIT, START, DROP }

    /** At most this long the start waits for the line; after it the plan sheet's own Los geht's is the way. */
    const val MAX_WAIT_MS = 60_000L

    /** [loaded] = the route's points as loaded; [now] = the plan's points now; [hasRoute] = the plan has a line. */
    fun step(loaded: List<GeoPoint>, now: List<GeoPoint>, routing: Boolean, hasRoute: Boolean, recording: Boolean): Step = when {
        recording -> Step.DROP
        now != loaded -> Step.DROP
        routing -> Step.WAIT
        hasRoute -> Step.START
        else -> Step.DROP
    }
}
