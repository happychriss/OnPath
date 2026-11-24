package com.onpath.app.ui

import com.onpath.app.debug.launchSafe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's [Background]: a coroutine on the view model's [scope] (failures reported under `app` / [Background.run]'s `what`),
 * the blocking [work] on [io]. A `run` called from inside a `then` is the next step of the same flow – it used to be the next
 * lines of one coroutine, so it starts undispatched (no extra hop through the scope's dispatcher); one called from anywhere
 * else – also from a [NameProposal]'s `got`, which runs in the proposal's own coroutine – is a new, dispatched coroutine.
 */
class CoroutineBackground(private val scope: CoroutineScope, private val io: CoroutineDispatcher) : Background {
    /** > 0 while a `then` runs (always on the scope's thread). */
    private var inThen = 0

    override fun <T> run(what: String, work: () -> T, always: () -> Unit, then: (T) -> Unit) {
        val start = if (inThen > 0) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT
        scope.launchSafe("app", what, start = start) {
            try {
                val result = withContext(io) { work() }
                inThen++
                try { then(result) } finally { inThen-- }
            } finally { always() }
        }
    }
}

/**
 * The app's [NameProposal]: a coroutine on [scope] that waits at most `maxMs` for [propose] (null = none in time), then calls
 * `got` on the scope's thread; `always` after either. A failure is reported under `app` / the `what` asked.
 */
class CoroutineNameProposal(
    private val scope: CoroutineScope,
    private val propose: suspend (List<GeoPoint>, PlannedRoute?) -> String?,
) : NameProposal {
    override fun ask(what: String, points: List<GeoPoint>, route: PlannedRoute?, maxMs: Long, always: () -> Unit, got: (String?) -> Unit) {
        scope.launchSafe("app", what) {
            try { got(withTimeoutOrNull(maxMs) { propose(points, route) }) } finally { always() }
        }
    }
}
