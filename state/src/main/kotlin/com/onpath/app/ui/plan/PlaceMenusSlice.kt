package com.onpath.app.ui.plan

import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** What [PlaceMenusSlice] asks of the view model: [PlaceMenusPorts] and the offline place index (a suspending read). */
interface PlaceMenusHost : PlaceMenusPorts {
    /** The offline place index (may throw: no index). */
    suspend fun offlineIndex(): PlaceLookup
}

/**
 * The app's place menus: the state and decisions of [PlaceMenusState] (coroutine-free – for the planner once its closure
 * is listed in `state/web-ready.txt`) plus the one piece of background work, the lookup of the pinned points' POI kinds
 * ([refreshPinnedKinds]). The cut every slice with a coroutine gets (docs/design/web-ready-status.md): `XState` = plain
 * state + decisions + what a finished piece of work changes; `XSlice : XState` = the coroutines that drive it.
 */
class PlaceMenusSlice(
    plan: PlanDocument, planRun: PlanRunSlice, placeAdd: PlaceAddSlice,
    private val host: PlaceMenusHost,
    /** The router a held place's rows are measured on (the same as the hold's). */
    router: HoldRouter,
    /** Where the pinned POI kinds are looked up (off the main thread). */
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) : PlaceMenusState(plan, planRun, placeAdd, host, router) {
    private var poiKindsJob: Job? = null

    /** Looks up the POI under each pinned point without a known kind (once per point, off the main thread). */
    fun refreshPinnedKinds() {
        val todo = pinnedKindsTodo()
        if (todo.isEmpty() || poiKindsJob?.isActive == true) return
        poiKindsJob = scope.launchSafe("app", "refreshPinnedKinds") {
            val index = try { host.offlineIndex() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Problems.info("plan", "No place index for pinned POI symbols; plain points shown", e); return@launchSafe }
            val found = withContext(io) { findPinnedKinds(todo, index) }
            pinnedKindsFound(todo, found)
        }
    }
}
