package com.onpath.app.routing

import com.onpath.app.data.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Detour anchors (docs/design/detour-stitch.md §1, §2): expanded list, stitch rule, how anchors follow edits. */
class PlanAnchorsTest {
    private fun pt(i: Int) = GeoPoint(47.0 + i * 0.01, 11.0 + i * 0.01)
    private fun pts(n: Int) = List(n) { pt(it) }
    private fun a(i: Int) = GeoPoint(48.0 + i * 0.001, 12.0)

    /** A straight line along lat 47, a point every km (metres by Geo), [km] long, 1e-6° values. */
    private fun line(km: Int): List<GeoPoint> {
        val step = 1000.0 / Geo.mPerDegLon(47.0)
        return List(km + 1) { GeoPoint(47.0, Math.round((11.0 + it * step) * 1e6) / 1e6) }
    }

    private fun alongKm(line: List<GeoPoint>, g: GeoPoint) = Geo.cumulative(line)[line.indexOf(g)] / 1000.0

    // expand

    @Test fun noAnchorsIsTheIdentity() {
        val w = pts(4)
        val e = PlanAnchors.expand(w, emptyMap(), setOf(1, 3))
        assertEquals(w, e.points)
        assertEquals(setOf(1, 3), e.pins)
        assertEquals(listOf(0, 1, 2, 3), e.visibleIndex)
        assertTrue(!e.hasAnchors)
        for (i in w.indices) { assertEquals(i, e.expandedIndex(i)); assertEquals(i, e.visibleOf(i)) }
    }

    @Test fun anchorsSitInFrontOfAndBehindTheirPoint() {
        val w = pts(4)
        val e = PlanAnchors.expand(w, mapOf(1 to Anchors(listOf(a(1), a(2)), listOf(a(3))), 2 to Anchors(after = listOf(a(4)))), setOf(1, 2))
        assertEquals(listOf(w[0], a(1), a(2), w[1], a(3), w[2], a(4), w[3]), e.points)
        assertEquals(listOf(0, -1, -1, 1, -1, 2, -1, 3), e.visibleIndex)
        assertEquals(setOf(3, 5), e.pins)
        assertEquals(listOf(0, 3, 5, 7), (0..3).map { e.expandedIndex(it) })
        assertEquals(-1, e.visibleOf(1)); assertEquals(-1, e.visibleOf(99)); assertEquals(-1, e.expandedIndex(4))
        assertEquals(4, e.visibleCount)
        assertTrue(e.hasAnchors)
    }

    @Test fun outOfRangeAndEndSidesAreNotExpanded() {
        val w = pts(3)
        val e = PlanAnchors.expand(
            w, mapOf(0 to Anchors(listOf(a(1)), listOf(a(2))), 2 to Anchors(listOf(a(3)), listOf(a(4))), 7 to Anchors(listOf(a(5))), -1 to Anchors(listOf(a(6)))),
            setOf(1, 9),
        )
        // first point: only `after`; last point: only `before`; nothing from indices 7 and -1
        assertEquals(listOf(w[0], a(2), w[1], a(3), w[2]), e.points)
        assertEquals(setOf(2), e.pins)
    }

    // forInsert

    @Test fun stitchTakesExactLinePointsAboutTenKmAway() {
        val l = line(100)
        val p = GeoPoint(47.009, l[50].lon)
        val s = PlanAnchors.forInsert(l, l.first(), l.last(), p)!!
        assertEquals(1, s.before.size); assertEquals(1, s.after.size)
        assertTrue(s.before[0] in l); assertTrue(s.after[0] in l)
        assertEquals(40.0, alongKm(l, s.before[0]), 0.6)
        assertEquals(60.0, alongKm(l, s.after[0]), 0.6)
        assertEquals(s, PlanAnchors.forInsert(l, l.first(), l.last(), p)) // deterministic
    }

    @Test fun anchorIsNeverInterpolated() {
        // a sparse line (points 7 km apart): the anchor is still one of its points
        val l = line(105).filterIndexed { i, _ -> i % 7 == 0 }
        val p = GeoPoint(47.0, l[7].lon)
        val s = PlanAnchors.forInsert(l, l.first(), l.last(), p)!!
        assertTrue(s.before.all { it in l } && s.after.all { it in l })
        assertTrue(s.before.isNotEmpty() && s.after.isNotEmpty())
    }

    @Test fun anchorsArePlainCoordinates() {
        val way = WayAttrs("path", "gravel", 0, -1)
        val l = line(100).mapIndexed { i, g -> g.copy(ele = 500.0 + i, way = way) }
        val s = PlanAnchors.forInsert(l, l.first(), l.last(), GeoPoint(47.009, l[50].lon))!!
        val all = s.before + s.after
        assertTrue(all.isNotEmpty() && all.all { it.ele == null && it.way == null })
        val back = PlanCode.decode(PlanCode.encode(SharedPlan(listOf(l.first(), GeoPoint(47.009, l[50].lon), l.last()), Activity.HIKE, anchors = mapOf(1 to s))))!!
        assertEquals(s, back.anchors[1])
    }

    @Test fun anAnchorIsNeverALegEnd() {
        // a ferry: only the two ends 70 km apart and one point near the middle; P is on the long first piece
        val a = GeoPoint(47.0, 11.0); val m = GeoPoint(47.0, 11.0 + 35_000.0 / Geo.mPerDegLon(47.0)); val z = GeoPoint(47.0, 11.0 + 70_000.0 / Geo.mPerDegLon(47.0))
        val l = listOf(a, m, z)
        val s = PlanAnchors.forInsert(l, a, z, GeoPoint(47.0, 11.0 + 30_000.0 / Geo.mPerDegLon(47.0)))
        assertTrue(s == null || (a !in s.before + s.after && z !in s.before + s.after))
        assertEquals(listOf(m), s!!.before + s.after) // the one inner point is the only choice
        // two points only: nothing to pick, no stitch
        assertNull(PlanAnchors.forInsert(listOf(a, z), a, z, GeoPoint(47.0, 11.0 + 30_000.0 / Geo.mPerDegLon(47.0))))
    }

    @Test fun legLengthThreshold() {
        val p59 = line(59); val p61 = line(61)
        assertNull(PlanAnchors.forInsert(p59, p59.first(), p59.last(), p59[30]))
        assertNotNull(PlanAnchors.forInsert(p61, p61.first(), p61.last(), p61[30]))
        assertNull(PlanAnchors.forInsert(listOf(line(100)[0]), pt(0), pt(1), pt(0)))
    }

    @Test fun offsetThreshold() {
        val l = line(100)
        // 0.018° of latitude = 2.0 km; 0.017 = 1.89 km, 0.019 = 2.11 km
        assertNotNull(PlanAnchors.forInsert(l, l.first(), l.last(), GeoPoint(47.017, l[50].lon)))
        assertNull(PlanAnchors.forInsert(l, l.first(), l.last(), GeoPoint(47.019, l[50].lon)))
    }

    @Test fun shortSideStaysEmpty() {
        val l = line(100)
        val near = PlanAnchors.forInsert(l, l.first(), l.last(), l[11])!! // 11 km from the start: before is short
        assertTrue(near.before.isEmpty()); assertEquals(1, near.after.size)
        val ok = PlanAnchors.forInsert(l, l.first(), l.last(), l[13])!! // 13 km: both
        assertEquals(1, ok.before.size)
        val end = PlanAnchors.forInsert(l, l.first(), l.last(), l[89])!! // 11 km from the end
        assertEquals(1, end.before.size); assertTrue(end.after.isEmpty())
    }

    // follow

    private val three = Anchors(listOf(a(1)), listOf(a(2)))

    @Test fun anchorsFollowTheirPointByValue() {
        val w = pts(5)
        val out = PlanAnchors.follow(w, w, mapOf(2 to three))
        assertEquals(mapOf(2 to three), out)
        // a point in front is deleted: index shifts, anchors stay on both sides
        assertEquals(mapOf(1 to three), PlanAnchors.follow(w, w.drop(1), mapOf(2 to three)))
        // a new point appended far away
        assertEquals(mapOf(2 to three), PlanAnchors.follow(w, w + pt(9), mapOf(2 to three)))
    }

    @Test fun deletedOrMovedPointLosesItsAnchors() {
        val w = pts(5)
        assertTrue(PlanAnchors.follow(w, w.filterIndexed { i, _ -> i != 2 }, mapOf(2 to three)).isEmpty())
        val moved = w.toMutableList().also { it[2] = GeoPoint(47.5, 11.5) }
        assertTrue(PlanAnchors.follow(w, moved, mapOf(2 to three)).isEmpty())
    }

    @Test fun aChangedNeighbourDropsThatSideOnly() {
        val w = pts(5)
        val at = mapOf(2 to three)
        // next point deleted: `after` goes
        assertEquals(mapOf(2 to Anchors(before = three.before)), PlanAnchors.follow(w, w.filterIndexed { i, _ -> i != 3 }, at))
        // previous point deleted: `before` goes, P is now index 1
        assertEquals(mapOf(1 to Anchors(after = three.after)), PlanAnchors.follow(w, w.filterIndexed { i, _ -> i != 1 }, at))
        // next point moved
        val movedNext = w.toMutableList().also { it[3] = GeoPoint(47.5, 11.5) }
        assertEquals(mapOf(2 to Anchors(before = three.before)), PlanAnchors.follow(w, movedNext, at))
        // a new point inserted after P: `after` goes; before P: `before` goes
        val ins = GeoPoint(47.5, 11.5)
        assertEquals(mapOf(2 to Anchors(before = three.before)), PlanAnchors.follow(w, w.take(3) + ins + w.drop(3), at))
        assertEquals(mapOf(3 to Anchors(after = three.after)), PlanAnchors.follow(w, w.take(2) + ins + w.drop(2), at))
    }

    @Test fun bothNeighboursChangedMeansNoEntry() {
        val w = pts(5)
        val out = PlanAnchors.follow(w, listOf(w[0], w[2], w[4]), mapOf(1 to Anchors(listOf(a(1)), listOf(a(2)))))
        assertTrue(out.isEmpty())
        assertEquals(emptyMap<Int, Anchors>(), PlanAnchors.follow(w, w, emptyMap()))
    }

    // reversed

    @Test fun reversedMirrorsIndicesAndSwapsSides() {
        val r = PlanAnchors.reversed(mapOf(1 to Anchors(listOf(a(1), a(2)), listOf(a(3))), 9 to Anchors(listOf(a(4)))), 5)
        assertEquals(mapOf(3 to Anchors(listOf(a(3)), listOf(a(2), a(1)))), r)
        val back = PlanAnchors.reversed(r, 5)
        assertEquals(mapOf(1 to Anchors(listOf(a(1), a(2)), listOf(a(3)))), back)
    }
}
