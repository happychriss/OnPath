package com.onpath.app.ui.plan

import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.plan.RunCause
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** What [PlanEditSlice] asks of the view model: [PlanEditPorts] and the offline place index (a suspending read). */
interface PlanEditHost : PlanEditPorts {
    /** Opens the offline place index (may throw): names for route points ([PointNames]). */
    suspend fun pointNames(): PointNames
}

/**
 * Editing the plan's points: the state and decisions of [PlanEditState] (coroutine-free) plus the one piece of
 * background work, the names of unnamed route points ([refreshPointNames]). Constructed with the view model (no work).
 */
class PlanEditSlice(
    plan: PlanDocument,
    planRun: PlanRunSlice,
    /** What started the routing run: Cancel takes back only that ([PlanRouteSlice.runCauses]). */
    runCauses: RunCause,
    private val host: PlanEditHost,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : PlanEditState(plan, planRun, runCauses, host) {
    private var namesJob: Job? = null

    /** Names the route points from the offline place index (once per point, off the main thread). */
    fun refreshPointNames() {
        val todo = pointNamesTodo()
        if (todo.isEmpty() || namesJob?.isActive == true) return
        namesJob = scope.launchSafe("app", "refreshPointNames") {
            // only the places near each point are read from the index file (off the main thread)
            val names = try { host.pointNames() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Problems.info("plan", "No place index for point names; \"Point N\" shown", e); return@launchSafe }
            val found = withContext(io) { findPointNames(todo, names) }
            pointNamesFound(found)
        }
    }
}
