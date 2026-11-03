package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint

/**
 * "Route ändern" during a tour (owner 2026-11-02): the plan when it opened is kept, so "Änderungen verwerfen" can go back
 * to it. Pure, unit-tested.
 */
object TourEdit {
    /**
     * The snapshot after a mode switch [was] → [now]: taken when a tour switches Navigate → Plan, dropped when it leaves
     * Plan or without a tour, else kept ([current]).
     */
    fun startOf(was: AppMode, now: AppMode, touring: Boolean, plan: List<GeoPoint>, current: List<GeoPoint>?): List<GeoPoint>? = when {
        !touring || now != AppMode.PLAN -> null
        was == AppMode.NAVIGATE -> plan
        else -> current
    }

    /** Something to discard: a snapshot exists and the plan differs from it. */
    fun changed(start: List<GeoPoint>?, plan: List<GeoPoint>): Boolean = start != null && start != plan
}
