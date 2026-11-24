package com.onpath.app.ui.routes

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.ui.CoroutineBackground
import com.onpath.app.ui.CoroutineNameProposal
import com.onpath.app.ui.plan.PlanDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/** [SavePorts] and the one suspending member: the place name proposed for a plan (the app: the search index / plan link title). */
interface SaveHost : SavePorts {
    /** The place name proposed for a plan ("Algund → Meran", a plan link's title); null = none. */
    suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?): String?
}

/**
 * Save (C18) as the app runs it: [SaveState] with the coroutines – the work on [scope] (the view model's), the blocking calls
 * on [io], the place name from [SaveHost.proposeName] waited for at most [nameWaitMs]. Name and constructor are the old ones.
 */
class SaveSlice(
    plan: PlanDocument,
    routes: RoutesState,
    recent: RecentPlans,
    store: SaveStore,
    host: SaveHost,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    nameWaitMs: Long = RecentPlans.NAME_WAIT_MS,
) : SaveState(plan, routes, recent, store, host, CoroutineBackground(scope, io),
    CoroutineNameProposal(scope) { pts, r -> host.proposeName(pts, r) }, nameWaitMs)
