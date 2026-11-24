package com.onpath.app.domain

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.RouteCancel

/**
 * The measure of a hold beside a plan on the real paths, as the plan's state asks for it (owner 2026-11-17 „Always real
 * paths“, F-PLAN-15): started for a spot, answered once with an [Outcome] – on the paths, or the straight-line numbers
 * after a timeout or any failure, so a hold always gets its decision (core feature C1). One measure at a time: [start]
 * replaces the one before, [cancel] stops it; a measure replaced or cancelled never calls its `done`. The app's is
 * [HoldPaths] (coroutines, its own routing thread); the browser planner's measures in its routing worker and answers
 * later (no coroutine here: this file is for both).
 */
interface HoldMeasure {
    /** The routed length a → b in metres (blocking, on the measure's own thread); throws when there is none. Stops soon after [cancel]. */
    fun interface Router {
        fun length(a: GeoPoint, b: GeoPoint, cancel: RouteCancel): Double
    }

    /** What a measure found: [detours] on the paths, or the straight ones after [failure]; [ms] = how long it took. */
    class Outcome(val detours: PlaceAddMenu.Detours, val onPaths: Boolean, val ms: Long, val failure: String? = null) {
        /** For the hold's trace: "paths: insert +X m, append Y m (Z ms)" or "paths failed: … – straight lines". */
        fun describe(): String =
            if (onPaths) "paths: insert +${detours.insertM.toInt()} m" +
                (detours.earlyInsertM?.let { " (earlier legs +${it.toInt()} m)" } ?: "") +
                ", append ${detours.appendM.toInt()} m ($ms ms)"
            else "paths failed: $failure ($ms ms) – straight lines"
    }

    /** A measure is running (its `done` not called yet). */
    val running: Boolean

    /**
     * Measures [s] beside the plan [points] (2+) on the paths; [straight] = its straight-line numbers
     * ([PlaceAddMenu.detours], the fallback and the arrival angle). [router] gives the lengths where the measure runs in
     * this process (an implementation that measures elsewhere need not call it). [done] gets the outcome – never for a
     * measure cancelled or replaced.
     */
    fun start(points: List<GeoPoint>, s: GeoPoint, straight: PlaceAddMenu.Detours, router: Router, done: (Outcome) -> Unit)

    /** Stops a running measure (its `done` is never called); true when one was running. */
    fun cancel(): Boolean
}
