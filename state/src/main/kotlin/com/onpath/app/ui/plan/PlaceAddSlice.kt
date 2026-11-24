package com.onpath.app.ui.plan

import com.onpath.app.domain.HoldPaths
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/** What [PlaceAddSlice] asks of the view model: everything is in [PlaceAddPorts] (no suspending call). */
interface PlaceAddHost : PlaceAddPorts

/**
 * Adding points to the plan: the state and decisions of [PlaceAddState] (coroutine-free) on a [HoldPaths] built here from the
 * hold's thread and the view model's scope. Constructed with the view model (no work).
 */
class PlaceAddSlice(
    plan: PlanDocument,
    planEdit: PlanEditSlice,
    planRun: PlanRunSlice,
    host: PlaceAddHost,
    router: HoldRouter,
    /** The hold's own routing thread ([RoutingThreads.hold]). */
    holdWorker: CoroutineDispatcher,
    /** Where a measure's result is applied: the view model's scope (the main thread). */
    scope: CoroutineScope,
) : PlaceAddState(plan, planEdit, planRun, host, router, GivenWayMeasure(HoldPaths(scope, holdWorker), planRun))

/** A hold has 1.5 s for its measure: the router's other background work stops for it and may go on when it is over. */
private class GivenWayMeasure(private val paths: HoldPaths, private val planRun: PlanRunSlice) : com.onpath.app.domain.HoldMeasure by paths {
    override fun start(points: List<com.onpath.app.routing.GeoPoint>, s: com.onpath.app.routing.GeoPoint,
                       straight: com.onpath.app.domain.PlaceAddMenu.Detours, router: com.onpath.app.domain.HoldMeasure.Router,
                       done: (com.onpath.app.domain.HoldMeasure.Outcome) -> Unit) {
        planRun.besideRoute?.holdMeasures(true)
        paths.start(points, s, straight, router) { done(it); planRun.besideRoute?.holdMeasures(false) }
    }
    override fun cancel(): Boolean = paths.cancel().also { if (it) planRun.besideRoute?.holdMeasures(false) }
}
