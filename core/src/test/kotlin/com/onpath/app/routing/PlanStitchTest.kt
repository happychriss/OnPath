package com.onpath.app.routing

import com.onpath.app.data.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The writer side of the anchors (docs/design/detour-stitch.md §2): which edit makes which anchors, from which line. */
class PlanStitchTest {
    private val hike = AnchorProfile.of(Activity.HIKE, 2)
    private val bike = AnchorProfile.of(Activity.BIKE_TOURING, 0)

    /** A straight line along lat 47, a point every km, [km] long, 1e-6° values, starting at [lon0]. */
    private fun line(km: Int, lon0: Double = 11.0): List<GeoPoint> {
        val step = 1000.0 / Geo.mPerDegLon(47.0)
        return List(km + 1) { GeoPoint(47.0, Math.round((lon0 + it * step) * 1e6) / 1e6) }
    }

    /** A point [km] along [line], [offDeg] north of it. */
    private fun near(line: List<GeoPoint>, km: Int, offDeg: Double) = GeoPoint(line[km].lat + offDeg, line[km].lon)

    private fun lines(points: List<GeoPoint>, legs: List<List<GeoPoint>?>, profile: AnchorProfile = hike) = VisibleLines(points, profile, legs)

    private val leg = line(100)
    private val two = listOf(leg.first(), leg.last())
    private val oneLeg = lines(two, listOf(leg))

    @Test fun insertNearTheMiddleOfALongLegIsStitched() {
        val n = near(leg, 50, 0.009) // ~1 km off
        val set = PlanStitch.after(two, listOf(two[0], n, two[1]), AnchorSet.NONE, oneLeg)
        val a = set.anchors[1]
        assertNotNull(a)
        assertEquals(1, a!!.before.size); assertEquals(1, a.after.size)
        assertEquals(hike, set.profile)
        assertEquals(1, set.anchors.size)
    }

    @Test fun insertOnAShortLegGetsNoAnchors() {
        val s = line(40)
        val p = listOf(s.first(), s.last())
        val set = PlanStitch.after(p, listOf(p[0], near(s, 20, 0.005), p[1]), AnchorSet.NONE, lines(p, listOf(s)))
        assertTrue(set.anchors.isEmpty())
    }

    @Test fun linesOfAnotherProfileWhileTheSetHasAnchorsAreNotUsed() {
        val own = Anchors(after = listOf(leg[30]))
        val set = AnchorSet(mapOf(0 to own), hike)
        val n = near(leg, 60, 0.009)
        val got = PlanStitch.after(two, listOf(two[0], n, two[1]), set, lines(two, listOf(leg), bike))
        assertEquals(PlanAnchors.edited(two, listOf(two[0], n, two[1]), set.anchors), got.anchors)
        assertEquals(hike, got.profile)
    }

    @Test fun anEmptySetTakesTheLinesProfile() {
        val n = near(leg, 50, 0.009)
        assertEquals(bike, PlanStitch.after(two, listOf(two[0], n, two[1]), AnchorSet.NONE, lines(two, listOf(leg), bike)).profile)
    }

    @Test fun staleLinesAreNotUsed() {
        val n = near(leg, 50, 0.009)
        val other = listOf(two[0], GeoPoint(47.5, 11.5))
        val set = PlanStitch.after(two, listOf(two[0], n, two[1]), AnchorSet.NONE, lines(other, listOf(leg)))
        assertTrue(set.anchors.isEmpty())
        assertNull(set.profile)
    }

    @Test fun aStraightLegGivesNoStitch() {
        val n = near(leg, 50, 0.009)
        assertTrue(PlanStitch.after(two, listOf(two[0], n, two[1]), AnchorSet.NONE, lines(two, listOf(null))).anchors.isEmpty())
    }

    @Test fun aMoveWithin2kmIsStitchedAgainAndAFarOneIsNot() {
        val a = leg[0]; val m = near(leg, 40, 0.0); val c = leg[100]
        val old = listOf(a, m, c)
        val l = lines(old, listOf(leg.subList(0, 41), leg.subList(40, 101)))
        val moved = near(leg, 60, 0.009)
        val set = PlanStitch.after(old, listOf(a, moved, c), AnchorSet.NONE, l)
        assertNotNull(set.anchors[1])
        assertEquals(1, set.anchors[1]!!.before.size)
        val far = near(leg, 60, 0.05) // ~5.5 km
        assertTrue(PlanStitch.after(old, listOf(a, far, c), AnchorSet.NONE, l).anchors.isEmpty())
    }

    @Test fun appendPrependAndDeleteAreJustEdited() {
        val a = leg[0]; val m = near(leg, 40, 0.0); val c = leg[100]
        val old = listOf(a, m, c)
        val l = lines(old, listOf(leg.subList(0, 41), leg.subList(40, 101)))
        val set = AnchorSet(mapOf(1 to Anchors(listOf(leg[30]), listOf(leg[50]))), hike)
        val x = near(leg, 70, 0.009)
        for (edited in listOf(old + x, listOf(x) + old, listOf(a, c))) {
            assertEquals(PlanAnchors.edited(old, edited, set.anchors), PlanStitch.after(old, edited, set, l).anchors)
        }
    }

    @Test fun aTransferEqualsFollowInsert() {
        // P at km 60 holds before = km 45; a point put in at km 55 (1 km off) lies between that anchor and P
        val a = leg[0]; val p = leg[60]; val c = leg[100]
        val old = listOf(a, p, c)
        val l = lines(old, listOf(leg.subList(0, 61), leg.subList(60, 101)))
        val set = AnchorSet(mapOf(1 to Anchors(listOf(leg[45]), listOf(leg[75]))), hike)
        val n = near(leg, 55, 0.009)
        val after = listOf(a, n, p, c)
        val got = PlanStitch.after(old, after, set, l)
        assertEquals(PlanAnchors.followInsert(old, after, set.anchors, 1, leg.subList(0, 61)).let { it }, got.anchors)
        assertEquals(hike, got.profile)
    }

    @Test fun linesOfGroupsTheExpandedLegs() {
        val g = leg
        val a1 = g[30]; val b1 = g[70]
        val plan = PlanAnchors.expand(listOf(g[0], g[50], g[100]), mapOf(1 to Anchors(listOf(a1), listOf(b1))), emptySet())
        val route = PlannedRoute(
            legs = listOf(g.subList(0, 31), g.subList(30, 51), g.subList(50, 71), g.subList(70, 101)).map { PlannedLeg(it, false) },
            distanceM = 0.0, ascentM = 0.0, descentM = 0.0, durationS = 0.0, error = null,
        )
        val v = PlanStitch.linesOf(route, plan, hike)!!
        assertEquals(listOf(g[0], g[50], g[100]), v.points)
        assertEquals(g.subList(0, 51), v.lines[0])
        assertEquals(g.subList(50, 101), v.lines[1])
        // a straight expanded leg makes its visible leg null, the other stays
        val st = route.copy(legs = route.legs.mapIndexed { i, l -> if (i == 3) l.copy(straight = true) else l })
        val w = PlanStitch.linesOf(st, plan, hike)!!
        assertNotNull(w.lines[0]); assertNull(w.lines[1])
        // a route that does not fit the plan
        assertNull(PlanStitch.linesOf(route.copy(legs = route.legs.drop(1)), plan, hike))
    }
}
