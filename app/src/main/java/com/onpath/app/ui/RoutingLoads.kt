package com.onpath.app.ui

import com.onpath.strings.*
import com.onpath.app.data.RoutingDataLoader

/**
 * One loading modal for map + routing data (field test 2026-10-21, fresh phone): "Loading the map for your area ·
 * 4 regions · about 60 MB" with one % over both by bytes (routing data weighted by its size, included in the MB), or
 * "Loading the map for your area · about 10 MB" when only routing data is missing – routing data is never named
 * (owner 2026-10-22: it is all "the map for your area"). Pure, unit-tested.
 */
object RoutingLoads {

    /**
     * [map] = the map regions' modal (null = no region to wait for), [routing] = routing data still loading (null =
     * there, or not waited for). [held] = the last % shown (never backwards). Null = nothing to wait for.
     */
    fun combine(map: MapLoadUi?, routing: RoutingDataLoader.Status?, held: Int = 0): MapLoadUi? {
        if (routing == null) return map?.let { it.copy(percent = maxOf(it.percent, held).coerceAtMost(99)) }
        val rKind = when (routing.phase) {
            RoutingDataLoader.Phase.LOADING -> MapLoadUi.Kind.LOADING
            RoutingDataLoader.Phase.OFFLINE -> MapLoadUi.Kind.OFFLINE
            RoutingDataLoader.Phase.WIFI -> MapLoadUi.Kind.WIFI
            RoutingDataLoader.Phase.FAILED -> MapLoadUi.Kind.FAILED
        }
        if (map == null) {
            return MapLoadUi(rKind, maxOf(held, routing.percent).coerceAtMost(99),
                routing.secondsLeft.takeIf { rKind == MapLoadUi.Kind.LOADING },
                reason = if (rKind == MapLoadUi.Kind.FAILED) FAILED_TEXT else null,
                retryable = rKind == MapLoadUi.Kind.FAILED,
                regions = 0, bytes = routing.totalBytes, routing = true)
        }
        val mapBytes = (map.bytes ?: 0L).coerceAtLeast(1L)
        val total = mapBytes + routing.totalBytes
        val pct = ((map.percent * mapBytes + routing.percent * routing.totalBytes) / total).toInt()
        val kind = when {
            map.kind == MapLoadUi.Kind.FAILED -> MapLoadUi.Kind.FAILED
            map.kind == MapLoadUi.Kind.OFFLINE || rKind == MapLoadUi.Kind.OFFLINE -> MapLoadUi.Kind.OFFLINE
            else -> map.kind
        }
        val left = listOfNotNull(map.secondsLeft, routing.secondsLeft).maxOrNull()
        return map.copy(kind = kind, percent = maxOf(held, pct).coerceAtMost(99),
            secondsLeft = left.takeIf { kind == MapLoadUi.Kind.LOADING || kind == MapLoadUi.Kind.PREPARING },
            bytes = total, routing = true)
    }

    /**
     * Routing data arrived: the plan is computed again at once, without a question (planning, not re-routing a tour) –
     * in Plan, not on tour, when there is a route (≥ 2 points).
     */
    fun recalcOnArrival(mode: AppMode, onTour: Boolean, points: Int): Boolean = mode == AppMode.PLAN && !onTour && points >= 2

    val FAILED_TEXT: String get() = Txt.s(Res.string.plan_routing_load_failed)
}
