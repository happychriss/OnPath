package com.onpath.app.ui

// The small interfaces shared by the slices (docs/design/umbau-phase-2.md §3.3): what a slice asks of the rest of the app.
// A slice never names a neighbour that is not a slice yet – it asks through a port the view model implements.

/**
 * Says [text] where it belongs (owner: no dark message bar): a line in My routes / the open Settings page where the user
 * acted ([place]); an error without such a place in the red error box; an INFO without a place only in the problem log.
 * Routing: [NoticeRouting]. Implemented by [NoticeBoard].
 */
fun interface Say {
    fun say(text: String, kind: NoticeKind, place: NoticePlace)
}

/**
 * The map's camera, as the slices ask for it (defined here, used from piece 3 on): the view model turns each call into the
 * matching UiEvent (Fit / CenterOn / PanTo) of :app.
 */
interface Camera {
    /** Show all [points] (north up or at the current bearing by the session's one orientation). */
    fun fit(points: List<com.onpath.app.routing.GeoPoint>)
    /** Fly to one place at [zoom]. */
    fun centerOn(point: com.onpath.app.routing.GeoPoint, zoom: Double)
    /** Move the map to [point] at once, same zoom. */
    fun panTo(point: com.onpath.app.routing.GeoPoint)
}

/** Where the phone is now (null = no fix); the slices ask it, the view model answers from the location tracker. */
fun interface Position {
    fun here(): com.onpath.app.routing.GeoPoint?
}

/**
 * A piece of work that can be stopped (the running route calculation of [com.onpath.app.ui.plan.PlanRunSlice]): the slice only
 * ever asks it to stop. The app wraps its coroutine `Job` in one (`JobHandle`), the planner its own task.
 */
fun interface Cancellable {
    fun cancel()
}

/** The two clocks the slices read: wall time (stored stamps) and elapsed real time (waits, never goes back). */
interface Clock {
    fun nowMs(): Long
    fun elapsedMs(): Long
}

/**
 * Blocking work off the caller's thread, its result back on it (the slices' "write on the IO thread, go on here"). The app:
 * [CoroutineBackground] (`launchSafe` + `withContext(io)`); the planner: [work] at once on its in-memory store, then [then].
 * A `run` called from inside a [then] goes on in the same flow (no new dispatch).
 */
interface Background {
    /**
     * Runs [work] (blocking; its failure is reported under [what] and [then] is not called), then [then] with its result on the
     * caller's thread; [always] after either, also when [then] threw.
     */
    fun <T> run(what: String, work: () -> T, always: () -> Unit = {}, then: (T) -> Unit)
}

/**
 * The place name proposed for a plan, at most [maxMs] late; null = none in time. [got] runs on the caller's thread, once;
 * a failure of the proposal is reported under [what] ([got] is not called); [always] after either.
 */
fun interface NameProposal {
    fun ask(what: String, points: List<com.onpath.app.routing.GeoPoint>, route: com.onpath.app.routing.PlannedRoute?, maxMs: Long,
            always: () -> Unit, got: (String?) -> Unit)
}

/** [NameProposal.ask] with nothing to run after. */
fun NameProposal.ask(what: String, points: List<com.onpath.app.routing.GeoPoint>, route: com.onpath.app.routing.PlannedRoute?, maxMs: Long,
                     got: (String?) -> Unit) = ask(what, points, route, maxMs, {}, got)
