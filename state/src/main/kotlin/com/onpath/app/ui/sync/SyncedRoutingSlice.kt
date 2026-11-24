package com.onpath.app.ui.sync

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.plansync.SyncRouting
import com.onpath.app.plansync.SyncedRouting
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.Planner
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.app.ui.routes.PlannedRow
import com.onpath.app.ui.routes.SaveStore
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.sync_routing_other
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/** What [SyncedRoutingSlice] asks of the app: the sync's files, the lists, the backup (everything else is a store or a slice). */
interface SyncedRoutingHost {
    /** `PlanSync.opened`: reads the sync files, background only; null = not a synced plan. */
    fun opened(uuid: String, points: List<GeoPoint>): SyncRouting.Opened?
    /** Newer routing data for these tiles now (quiet, throttled, never during a tour – the view model's rules). */
    fun refreshRoutingTiles(tiles: List<String>, why: String)
    /** My routes read again. */
    fun refreshTours()
    /** The open plan is (not) a saved route: "Saved as …" (the plan's link, the followed copy). */
    fun setSaved(name: String?, id: Long, points: List<GeoPoint>?)
    /** The route changed in the database: the backup of it. */
    fun tourChanged(id: Long)
}

/**
 * Routes that come from the browser, on the phone (cut out of `AppViewModel`, rebuild phase 2 piece 15; the rule about what
 * may be stored is [SyncedRouting.storable], when a pass runs is [SyncDriver]): the routing of the plans that came
 * ([routeSyncedPlans], on the background routing dispatcher; the ones without routing data wait for the data and are routed
 * by [routeWaiting]), what opening a synced plan says ([syncRoutingCheck]: the grey line, tiles to check) and the open plan
 * following its saved route when the browser renamed or deleted it ([followSyncedSavedPlan]).
 */
class SyncedRoutingSlice(
    private val plan: PlanDocument,
    private val planRoute: PlanRouteSlice,
    private val store: SaveStore,
    private val host: SyncedRoutingHost,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    /**
     * A saved route opened as the plan: when it is a synced plan, the grey line if the browser routed it with another
     * fingerprint, and its tiles checked for newer routing data (field 8 newer than the phone's, or without field 8
     * older than 7 days – plansync/SyncRouting.opened).
     */
    fun syncRoutingCheck(t: Tour, pts: List<GeoPoint>) {
        planRoute.syncRoutingNote = null
        if (t.uuid.isEmpty()) return
        scope.launchSafe("app", "syncRoutingCheck") {
            val o = withContext(io) {
                try { host.opened(t.uuid, pts) } catch (e: Exception) {
                    Problems.warn("sync", "Routing of the opened synced plan not compared", e); null
                }
            } ?: return@launchSafe
            if (plan.linkId != t.id || plan.points.toList() != pts) return@launchSafe // another plan or edited meanwhile
            if (o.otherRouting) {
                Problems.info("sync", "Opened plan was routed in the browser with another routing fingerprint – grey line shown")
                planRoute.syncRoutingNote = Txt.s(Res.string.sync_routing_other)
            }
            if (o.refresh.isNotEmpty()) host.refreshRoutingTiles(o.refresh, "opened a synced plan")
        }
    }

    /** The open plan's saved route was renamed or deleted from the browser: "Saved as …" follows (like Rename / Delete). */
    fun followSyncedSavedPlan() {
        val id = plan.linkId.takeIf { it >= 0 } ?: return
        scope.launchSafe("app", "followSyncedSavedPlan") {
            // a database error is not "deleted": the plan stays linked (review Arch 9)
            val read = withContext(io) { runCatching { store.getTour(id) } }
            val t = read.getOrElse { Problems.warn("db", "Saved route not read after a browser change; the plan stays linked", it); return@launchSafe }
            if (plan.linkId != id) return@launchSafe
            if (t == null || t.deletedMs != null) host.setSaved(null, -1, null) else if (t.name != plan.linkName) plan.relinking { host.setSaved(t.name, id, plan.savedPoints) } // a rename is no save
        }
    }

    /** Routes from the browser that waited for routing data (review routing M5): routed again when data arrives. */
    private val syncRoutingLater = LinkedHashSet<Long>()

    /** Routing data arrived (after the batch): the routes that waited for it are routed now. */
    fun routeWaiting() {
        if (syncRoutingLater.isNotEmpty()) { val later = syncRoutingLater.toList(); syncRoutingLater.clear(); routeSyncedPlans(later) }
    }

    /**
     * Routes from the browser are saved with their points as the line; here they get the real route (the router, the
     * pace) like a route saved on the phone. No routing data there yet: the points stay – opening the route routes it.
     */
    fun routeSyncedPlans(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launchSafe("app", "routeSyncedPlans") {
            for (id in ids.distinct()) {
                val t = withContext(io) {
                    runCatching { store.getTour(id) }.onFailure { Problems.warn("db", "A route from the browser not read; routed when opened", it) }.getOrNull()
                } ?: continue
                if (t.kind != TourKind.PLANNED || t.waypoints.size < 2 || t.deletedMs != null) continue
                val act = planRoute.activityOrNull(t.activity) ?: Activity.HIKE
                val pts = t.waypoints.map { GeoPoint(it.lat, it.lon) }
                // an unknown activity is routed as a hike: detour points that lie on another profile do not count and are not stored
                val anchors = com.onpath.app.routing.AnchorSet.of(t.anchors, planRoute.activityOrNull(t.activity), t.sac).forProfile(act, t.sac)
                val f = planRoute.planFactors(act)
                val r = withContext(planRoute.threads.background) {
                    try {
                        val eng = planRoute.routingEngine()
                        if (eng is com.onpath.app.routing.StraightLineEngine) null
                        else planRoute.withModelTime(Planner(eng, com.onpath.app.routing.RouteCancel(), legs = planRoute.legCache).plan(com.onpath.app.routing.PlanAnchors.expand(pts, anchors, t.pinned), act, t.sac), act, f)
                    } catch (e: Exception) {
                        Problems.warn("sync", "A route from the browser could not be routed; it keeps its points until opened", e); null
                    }
                } ?: continue
                // no routing data there yet (straight legs): keep the points, route it when the data arrives (review M5)
                if (!SyncedRouting.storable(r)) {
                    Problems.trace("sync", "Route \"${t.name}\" from the browser: no routing data there yet – points kept, routed later")
                    syncRoutingLater += id
                    continue
                }
                val ok = withContext(io) {
                    try {
                        // changed again meanwhile (a newer sync, or the user's own save of another activity / T level / pins
                        // with the same stops): leave it as it is – the write would put the old values back
                        val now = store.getTour(id)
                        now != null && now.waypoints == t.waypoints && now.activity == t.activity && now.sac == t.sac && now.pinned == t.pinned && now.anchors == t.anchors &&
                            store.replacePlanned(id, PlannedRow(act.name, t.sac, r.distanceM, r.ascentM, r.descentM,
                            r.durationS, listOf(r.points.map { TrackPoint(it.lat, it.lon, it.ele) }), t.waypoints, t.pinned,
                            planRoute.difficultyOf(r, act), anchors = anchors))
                            .also { if (it) { planRoute.plannedTimes.remove(id); planRoute.storePlanTimes(id, r, act) } }
                    } catch (e: Exception) { Problems.error("db", "A routed route from the browser could not be saved", e); false }
                }
                if (ok) host.tourChanged(id)
            }
            host.refreshTours()
        }
    }
}
