package com.onpath.app.ui.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanDetour
import com.onpath.app.routing.PlanReach
import com.onpath.app.routing.PlanResult
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RoutingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The background step of the detour hint ([PlanDetour]): after a route is shown, on [worker] (routing nobody waits for), it
 * looks whether the T level sends a plan point on a long detour. It never touches the route. [stop] (a newer run) cancels the
 * router at once and drops the result; a failed check leaves no hint. Main thread, except the check itself.
 */
class PlanDetourCheck(
    private val scope: CoroutineScope,
    /** Read when a check starts, never at construction. */
    private val worker: () -> CoroutineDispatcher,
    private val main: CoroutineDispatcher,
) {
    /** What was found for exactly this route ([legs], same list) and these [points]. */
    class Found(val legs: List<PlannedLeg>, val points: List<GeoPoint>, val detours: List<PlanDetour>)

    var found by mutableStateOf<Found?>(null)
        private set

    private var cancel: RouteCancel? = null
    private var job: Job? = null

    private var paused = false

    /** An older check ends and its result goes. */
    fun stop() {
        cancel?.cancel(); cancel = null
        job?.cancel(); job = null
        paused = false
        found = null
    }

    /** A hold needs the router now: a running check ends at once and is due again ([takePaused]); a finished one stays. */
    fun pause() {
        if (job?.isActive != true) return
        cancel?.cancel(); cancel = null
        job?.cancel(); job = null
        paused = true
    }

    /** True once after [pause] stopped a check that no newer run has replaced. */
    fun takePaused(): Boolean = paused.also { paused = false }

    /** Looks at the route result [res], which is on the map; a running check ends first. */
    fun start(res: PlanResult, reach: PlanReach? = null, engine: () -> RoutingEngine) {
        stop()
        if (!PlanDetour.applies(res.activity, res.sac)) return
        val signal = RouteCancel().also { cancel = it }
        job = scope.launchSafe("routing", "Detour check", main) {
            // a cancelled scope (the screen goes) must also end the blocking router request
            val watch = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { signal.cancel() } }
            val detours = withContext(worker()) {
                try {
                    PlanDetour.find(engine(), res, signal, reach = reach)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Problems.warn("routing", "Detour check failed; the plan shows no detour hint", e)
                    emptyList()
                }
            }
            if (cancel === signal && !signal.isCancelled && detours.isNotEmpty()) {
                found = Found(res.route.legs, res.points, detours)
                Problems.trace("routing", "Detour at point(s) ${detours.joinToString { "${it.index + 1}" }}, T${res.sac}")
            }
            watch.cancel()
        }
    }

    /** The detours to say for the [route] now on the map with these plan [points]; empty once either has changed. */
    fun detoursFor(route: PlannedRoute?, points: List<GeoPoint>): List<PlanDetour> {
        val f = found ?: return emptyList()
        return if (route != null && route.legs === f.legs && f.points == points) f.detours else emptyList()
    }
}
