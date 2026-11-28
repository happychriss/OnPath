package com.onpath.app.plansync

import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.ExpandedPlan
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.LegSplit
import com.onpath.app.routing.PlanAnchors
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Planner
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.SharedPlan
import com.onpath.app.sync.SyncItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * „Die Route, die ich im Planer sehe, wenn ich auf Speichern drücke, ist die, die ich auf dem Handy bekomme“ (owner
 * 2026-11-28; docs/team/items/2026-11-28-saved-equals-followed.md points 3 and 5) – the router side with a fake engine,
 * no rd5 (the real router is SameRouteTest's):
 * - a stitched plan (a point put in 1–2 km beside a long leg: anchors, the leg cut from the cache, then the idle settling)
 *   gives the same line as a full routing of the same plan on a device that has nothing in its cache;
 * - the same plan through the sync – PlanCode → SyncItem → RouteItems.planContent → expand → Planner with an empty cache
 *   – gives the line the editing device showed at Save.
 * The engine bends every line by its own two ends, so a piece of a leg is NOT the route between the piece's ends (as
 * with BRouter): only the settling makes warm and cold meet.
 */
class SavedEqualsFollowedTest {
    private val act = Activity.BIKE_TOURING
    private val sac = 2

    /** Each request's line: its ends, bent sideways by a sine over its own length – a cut piece is not a fresh route. */
    private class Bending : RoutingEngine {
        var calls = 0
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls++
            val out = ArrayList<GeoPoint>()
            for (k in 0 until waypoints.size - 1) {
                val a = waypoints[k]; val b = waypoints[k + 1]
                val n = maxOf(8, (Geo.distance(a, b) / 250).toInt())
                val leg = (0..n).map { i ->
                    val t = i.toDouble() / n
                    GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t + 0.01 * Math.sin(t * Math.PI))
                }
                out += if (k == 0) leg else leg.drop(1)
            }
            val d = Geo.length(out)
            return RouteResult(out, d, 10.0, 10.0, d / 5.0, matched = waypoints)
        }
    }

    // München → Kufstein-ish (~95 km, over the 60 km a stitch needs), and a hotel ~1.5 km beside the line near the middle (6 decimals, as the plan code keeps them)
    private val from = GeoPoint(48.137154, 11.576124)
    private val to = GeoPoint(47.600000, 12.600000)
    private val hotel = GeoPoint(47.880000, 12.098000)

    private fun hash(r: PlannedRoute) = r.points.map { Math.round(it.lat * 1e6) to Math.round(it.lon * 1e6) }

    /** The editing device: the plain plan routed, the hotel put in with anchors (followInsert), the leg cut, the plan run. */
    private fun editing(settle: Boolean): Triple<SharedPlan, PlannedRoute, ExpandedPlan> {
        val e = Bending(); val cache = LegCache(); val planner = Planner(e, legs = cache)
        val base = planner.plan(listOf(from, to), act, sac)
        val pts = listOf(from, hotel, to)
        val anchors = PlanAnchors.followInsert(listOf(from, to), pts, emptyMap(), 1, base.legs[0].points)
        assertTrue("the hotel 1–2 km off the line gets anchors (the stitch case): $anchors", anchors.isNotEmpty())
        val x = PlanAnchors.expand(pts, anchors, emptySet())
        LegSplit.seed(cache, x, act, sac)
        var shown = planner.plan(x, act, sac)
        if (settle) { planner.settle(x, act, sac); shown = planner.plan(x, act, sac) }
        return Triple(SharedPlan(pts, act, sac, emptySet(), emptyMap(), anchors = anchors), shown, x)
    }

    /** Point 3: the stitched line (settled) = the same plan routed in full on a device with an empty cache. */
    @Test fun aStitchedRouteIsTheFullRoutingOfTheSamePlan() {
        val (plan, stitched, _) = editing(settle = true)
        // the same plan as a device keeps it (every point at 1e-6°, PlanAnchors.exact), every leg routed afresh
        val exact = plan.anchors.mapValues { (_, a) -> com.onpath.app.routing.Anchors(a.before.map(PlanAnchors::exact), a.after.map(PlanAnchors::exact)) }
        val full = Planner(Bending(), legs = LegCache()).plan(PlanAnchors.expand(plan.points.map(PlanAnchors::exact), exact, emptySet()), act, sac)
        assertEquals(hash(full), hash(stitched))
    }

    /** Point 5: Save → plan code → sync item → planContent → a cold phone: the same line as shown at Save (after settling). */
    @Test fun theLineAfterTheSyncIsTheLineShownAtSave() {
        val (plan, shown, _) = editing(settle = true)
        val item = SyncItem("id-1", 1L, "dev", kind = SyncItem.PLAN, title = "Hotel", plan = PlanCode.encode(plan))
        val content = RouteItems.planContent(item)
        assertNotNull("the plan item reads back", content); content!!
        val x = PlanAnchors.expand(content.points, content.anchors, content.pinned)
        val phone = Planner(Bending(), legs = LegCache()).plan(x, content.activity, content.sac)
        assertEquals(hash(shown), hash(phone))
    }
}
