package com.onpath.web

import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.plan.RunCause
import com.onpath.app.navigation.TourSplit
import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.Place
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanEditPorts
import com.onpath.app.ui.plan.PlanEditState
import com.onpath.app.ui.plan.PlanRunHost
import com.onpath.app.ui.plan.PlanRunSlice
import com.onpath.app.ui.plan.PlanStore

/**
 * The app's plan-editing chain as the planner's page would hold it (umbau's `XState` cut, docs/design/web-ready-status.md
 * §6): [PlanDocument] → [PlanRunSlice] → [PlanEditState], over ports with the planner's facts (always Plan, no tour, no
 * GPS track). The ports record what the slice asks the host to do ([did]) – the page would apply those.
 *
 * Today only [probe] uses it: it proves that TeaVM builds and runs PlanEditState (`cause::class.simpleName` in cancelRun)
 * before the page's plan moves onto it – the page's plan and its undo history still live in the routing worker's engine
 * (WebApi.history*). PlaceAddState / PlaceMenusState are not here: PlaceAddState takes core's HoldPaths, and TeaVM cannot
 * build kotlinx.coroutines' launch / withTimeoutOrNull (threads, locks, atomic arrays – laptop 2026-11-20, the build's
 * „AtomicReferenceArray / LockSupport / ReentrantReadWriteLock was not found“); the measure would also have to wait for
 * the routing worker, which a blocking router on the page cannot – they need a `HoldMeasure` port (start / cancel, the
 * outcome handed back later) in place of HoldPaths.
 */
class WebPlanChain(points: List<GeoPoint> = emptyList()) {
    /** What the slice asked the host, in order (one word each, e.g. "recalc", "restoreActivity"). */
    val did = ArrayList<String>()
    var line: List<GeoPoint>? = null

    private val store = object : PlanStore {
        override var points: List<GeoPoint> = points
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }
    val plan = PlanDocument(store)
    val runCauses = RunCause()
    val planRun = PlanRunSlice(runCauses, object : PlanRunHost {
        override fun points() = plan.points.toList()
        override fun clearRoute() { did += "clearRoute" }
        override fun noCorridor() { did += "noCorridor" }
        override fun loadDeferredMaps(before: List<GeoPoint>) { did += "loadDeferredMaps" }
        override fun recalc() { did += "recalc" }
        override fun cancelRun(why: NotRouted) { edit.cancelRun(why) }
    })

    private var mark: Place? = null

    val edit: PlanEditState = PlanEditState(plan, planRun, runCauses, object : PlanEditPorts {
        override fun cancelHoldMeasure(why: String) { did += "cancelHoldMeasure" }
        override fun stopFollowingWhenMapTurns() = Unit // the planner's map never turns with a heading
        override fun closePointMenu(why: String) { did += "closePointMenu" }
        override fun clearFirstPointMenu() { did += "clearFirstPointMenu" }
        override fun clearHoldAddMenu() { did += "clearHoldAddMenu" }
        override fun clearHighlight() { did += "clearHighlight" }
        override fun removePin(why: String) { mark = null; did += "removePin" }
        override fun menuAction(what: String): Int? = null
        override fun setRoutingSpot(spot: GeoPoint?) = Unit
        override fun loadMapsForNewPoints(before: List<GeoPoint>) { did += "loadMaps" }
        override fun keepAsDraft(why: String) { did += "keepAsDraft" }
        override fun routeLine(): List<GeoPoint>? = line
        override fun recalc() { did += "recalc" }
        override fun restoreActivity(c: PlanStatus.Cause.ActivityChange) { did += "restoreActivity" }
        override fun planChanged() { did += "planChanged" }
        // the planner has no tour, no GPS track to follow
        override fun myPosition(): GeoPoint? = null
        override fun touring() = false
        override fun following() = false
        override fun followIsTrack() = false
        override fun followsLine(line: List<GeoPoint>) = false
        override fun followFromPlan() = false
        override fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>): TourSplit.Outcome? = null
        override fun sayNoDestination() = Unit
        override fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean) = Unit
        override fun endGuidanceIfPlanEmpty() = Unit
        override fun clearFollow() = Unit
        override fun guidanceEndedByEmptyPlan() = Unit
        override fun stopName(p: GeoPoint): String? = null
        override var searchMark: Place?
            get() = mark
            set(v) { mark = v }
    })

    companion object {
        /**
         * Builds the chain and runs it once through the spot umbau asked about: a point edit whose run is cancelled – the
         * edit is undone, and cancelRun logs the cause's `simpleName`. `{"afterEdit":3,"points":2,"canRedo":true,"did":[…]}`
         * or `{"error":"…"}`.
         */
        fun probe(): String = try {
            val c = WebPlanChain(listOf(GeoPoint(50.0, 7.0), GeoPoint(50.1, 7.1)))
            c.edit.change { add(GeoPoint(50.05, 7.2)) } // a point edit: its run starts
            val afterEdit = c.plan.points.size
            c.runCauses.start()
            c.edit.cancelRun(NotRouted.STOPPED)
            "{\"afterEdit\":$afterEdit,\"points\":${c.plan.points.size},\"canRedo\":${c.plan.canRedo}," +
                "\"did\":${c.did.joinToString(",", "[", "]") { Engine.str(it) }}}"
        } catch (e: Throwable) {
            "{\"error\":${Engine.str(e::class.simpleName + ": " + e.message)}}"
        }
    }
}
