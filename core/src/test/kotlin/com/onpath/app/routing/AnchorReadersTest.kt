package com.onpath.app.routing

import com.onpath.app.backup.BackupCodec
import com.onpath.app.backup.BackupMeta
import com.onpath.app.data.AnchorsJson
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.data.TourPlan
import com.onpath.app.domain.plan.DayFollow
import com.onpath.app.domain.plan.PlanSession
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.plansync.RouteItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader stage of the detour stitch (docs/design/detour-stitch.md §6.3): a plan's anchors survive every place a plan
 * is kept or carried, follow every edit, and no edit makes a new one.
 */
class AnchorReadersTest {
    private fun g(lat: Double, lon: Double = 11.0) = GeoPoint(lat, lon)
    private val a = g(46.1); private val p = g(46.3); private val c = g(46.5); private val d = g(46.7)
    private val x = g(46.2); private val y = g(46.4); private val z = g(46.6)
    private val pts = listOf(a, p, c, d)
    /** P has an anchor on each side, C one behind it. */
    private val anchors = mapOf(1 to Anchors(listOf(x), listOf(y)), 2 to Anchors(after = listOf(z)))
    private val set = AnchorSet.of(anchors, Activity.HIKE, 2)

    // ---- storage

    @Test fun jsonKeepsAnchorsExactly() {
        val exact = mapOf(1 to Anchors(listOf(g(46.123456, 11.654321)), listOf(g(46.200001, 11.700002))))
        assertEquals(exact, AnchorsJson.read(AnchorsJson.write(pts, exact), pts))
    }

    @Test fun noAnchorsWriteNothing() {
        assertEquals(null, AnchorsJson.write(pts, emptyMap()))
        assertEquals(emptyMap<Int, Anchors>(), AnchorsJson.read(null, pts))
    }

    @Test fun anchorsWrittenForAnotherPointAreDropped() {
        val json = AnchorsJson.write(pts, anchors)
        val moved = listOf(a, g(46.31), c, d) // point 1 is another point now: a kill between two writes
        assertEquals(mapOf(2 to anchors[2]), AnchorsJson.read(json, moved))
    }

    @Test fun damagedJsonIsNoAnchorsNeverACrash() {
        assertEquals(emptyMap<Int, Anchors>(), AnchorsJson.read("{not json", pts))
        assertEquals(emptyMap<Int, Anchors>(), AnchorsJson.read("""[{"i":"x"}]""", pts))
    }

    @Test fun overTheCodesLimitsIsDropped() {
        val many = mapOf(1 to Anchors((0..PlanCode.MAX_ANCHORS_SIDE).map { g(46.2 + it * 1e-3) }))
        assertEquals(null, AnchorsJson.write(pts, many))
        val far = mapOf(1 to Anchors(listOf(g(10.0))))
        assertEquals(null, AnchorsJson.write(pts, far))
    }

    private fun tour(kind: TourKind = TourKind.PLANNED) = Tour(7, kind, "T", 1L, "HIKE", 2, 1.0, 0.0, 0.0, 1.0, "done",
        pts.map { GpxWaypoint(it.lat, it.lon, null) }, uuid = "u1", anchors = anchors)

    @Test fun aSyncedRouteCarriesItsAnchors() {
        val code = RouteItems.planOf(tour())!!
        assertEquals(anchors, PlanCode.decode(code)!!.anchors)
        val item = com.onpath.app.sync.SyncItem(id = "i", updatedAt = 1L, device = "d", kind = com.onpath.app.sync.SyncItem.PLAN, title = "T", plan = code)
        assertEquals(anchors, RouteItems.planContent(item)!!.anchors)
    }

    /** A route of an activity this app does not know is read as a hike: anchors of another profile are not in its code (review finding 3). */
    @Test fun aRouteOfAnUnknownActivityCarriesNoAnchors() {
        val code = RouteItems.planOf(tour().copy(activity = "SKI_TOURING"))!!
        assertEquals(emptyMap<Int, Anchors>(), PlanCode.decode(code)!!.anchors)
        assertEquals("a known one keeps them", anchors, PlanCode.decode(RouteItems.planOf(tour().copy(activity = "HIKE"))!!)!!.anchors)
    }

    @Test fun twoPlansThatDifferOnlyInAnchorsAreNotTheSame() {
        val with = RouteItems.planOf(tour())
        val without = RouteItems.planOf(tour().copy(anchors = emptyMap()))
        assertTrue(!RouteItems.samePlan(with, without))
    }

    @Test fun theBackupRecordKeepsAPlannedRoutesAnchors() {
        val back = BackupCodec.fromJson(BackupCodec.toJson(BackupMeta.of(tour(), emptyList(), 5L)))!!
        assertEquals(anchors, back.anchors)
    }

    @Test fun aRecordFromBeforeAnchorsReadsNone() {
        val o = BackupCodec.toJson(BackupMeta.of(tour().copy(anchors = emptyMap()), emptyList(), 5L))
        assertTrue(!o.has("anchors"))
        assertEquals(emptyMap<Int, Anchors>(), BackupCodec.fromJson(o)!!.anchors)
    }

    @Test fun theFollowedPlansCopyKeepsItsAnchors() {
        val copy = TourPlan("T", "HIKE", 2, pts.map { GpxWaypoint(it.lat, it.lon, null) }, anchors = anchors)
        assertEquals(anchors, TourPlan.fromJson(copy.toJson())!!.anchors)
    }

    @Test fun aSharedLinkCarriesThePlansAnchors() {
        val shared = PlanLinks.share(pts, Activity.HIKE, 2, emptySet(), { null }, null, anchors)
        val load = PlanLinks.load(PlanCode.decode(PlanCode.encode(shared))!!)
        assertEquals(anchors, load.anchors)
    }

    // ---- edits

    private fun session() = PlanSession(pts.toMutableList(), anchorsAtStart = set)

    @Test fun deletingAPointTakesItsAnchorsAndTheFacingSideOfItsNeighbour() {
        val s = session()
        s.edit { removeAt(1) } // P goes: C's neighbour before it changed, it has no `before` anyway and keeps `after`
        assertEquals(mapOf(1 to Anchors(after = listOf(z))), s.anchors)
    }

    @Test fun undoAndRedoBringTheAnchorsBack() {
        val s = session()
        s.edit { removeAt(1) }
        s.replace(s.undo()!!)
        assertEquals(pts, s.points.toList()); assertEquals(anchors, s.anchors)
        s.replace(s.redo()!!)
        assertEquals(mapOf(1 to Anchors(after = listOf(z))), s.anchors)
    }

    @Test fun reverseTurnsTheAnchorsRound() {
        val s = session()
        s.edit { reverse() }
        assertEquals(mapOf(2 to Anchors(listOf(y), listOf(x)), 1 to Anchors(before = listOf(z))), s.anchors)
    }

    @Test fun noEditMakesANewAnchor() {
        val s = session()
        val all = (anchors.values.flatMap { it.before + it.after }).toSet()
        s.edit { add(2, g(46.35)) } // a point put in on the leg P → C
        s.edit { set(0, g(46.05)) }
        s.edit { removeAt(s.points.lastIndex) }
        assertTrue(s.anchors.values.flatMap { it.before + it.after }.all { it in all })
    }

    @Test fun aPointPutInBesideAnAnchorDropsOnlyThatSide() {
        val s = session()
        s.edit { add(2, g(46.35)) }
        assertEquals(Anchors(before = listOf(x)), s.anchors[1])
    }

    @Test fun anchorsArriveOnlyWithExactlyTheirPoints() {
        val s = PlanSession(mutableListOf(a, d))
        s.arriving(pts, set)
        s.edit { clear(); addAll(pts) }
        assertEquals(anchors, s.anchors)
        val other = PlanSession(mutableListOf(a, d))
        other.arriving(pts, set)
        other.edit { clear(); addAll(listOf(a, c, d)) } // another plan came first: they are not taken
        assertEquals(emptyMap<Int, Anchors>(), other.anchors)
    }

    @Test fun anchorsCountOnlyOnTheirProfileAndAreBackAfterACancel() {
        val s = session()
        assertEquals(anchors, s.anchorsFor(Activity.HIKE, 2))
        assertEquals("another T level", emptyMap<Int, Anchors>(), s.anchorsFor(Activity.HIKE, 3))
        assertEquals("another activity", emptyMap<Int, Anchors>(), s.anchorsFor(Activity.BIKE_TOURING, 2))
        assertEquals("nothing was thrown away: the same profile again", anchors, s.anchorsFor(Activity.HIKE, 2))
    }

    @Test fun aBikesTLevelIsNoOtherProfile() {
        val bike = AnchorSet.of(anchors, Activity.BIKE_TOURING, 2)
        assertEquals(anchors, bike.forProfile(Activity.BIKE_TOURING, 4))
    }

    @Test fun anUndoStepKeepsTheProfileOfItsAnchors() {
        val s = session()
        s.edit { clear() }
        s.arriving(listOf(a, d), AnchorSet.of(mapOf(0 to Anchors(after = listOf(x))), Activity.BIKE_TOURING, 2))
        s.edit { addAll(listOf(a, d)) }
        s.replace(s.undo()!!) // the empty plan
        s.replace(s.undo()!!) // the hike
        assertEquals(anchors, s.anchorsFor(Activity.HIKE, 2))
        assertEquals(emptyMap<Int, Anchors>(), s.anchorsFor(Activity.BIKE_TOURING, 2))
    }

    @Test fun theStoredSetKeepsItsProfile() {
        val back = com.onpath.app.data.AnchorsJson.readSet(com.onpath.app.data.AnchorsJson.writeSet(pts, set), pts)
        assertEquals(set, back)
        assertEquals(AnchorSet.NONE, com.onpath.app.data.AnchorsJson.readSet("{broken", pts))
        assertEquals(AnchorSet.NONE, com.onpath.app.data.AnchorsJson.readSet("""{"activity":"NO_SUCH","sac":2,"entries":[]}""", pts))
    }

    @Test fun anEmptiedPlanHasNoAnchorsAndUndoBringsThem() {
        val s = session()
        s.edit { clear() }
        assertEquals(emptyMap<Int, Anchors>(), s.anchors)
        s.replace(s.undo()!!)
        assertEquals(anchors, s.anchors)
    }

    // ---- navigation (§8.3)

    @Test fun theFirstPointAheadLosesItsBeforeAnchors() {
        val ahead = listOf(p, c, d)
        val got = PlanAnchors.forAhead(pts, anchors, ahead, off = 1)
        assertEquals(mapOf(1 to Anchors(after = listOf(y)), 2 to Anchors(after = listOf(z))), got)
        val route = PlanAnchors.expand(listOf(g(46.25)) + ahead, got, emptySet()).points
        assertTrue("never back to the anchor behind the rider", x !in route)
    }

    @Test fun laterPointsAheadKeepBothSides() {
        val all = mapOf(2 to Anchors(listOf(y), listOf(z)))
        assertEquals(mapOf(2 to Anchors(listOf(y), listOf(z))), PlanAnchors.forAhead(pts, all, listOf(p, c, d), off = 1))
    }

    @Test fun pointsAheadThatAreNotThePlansEndGetNoAnchors() {
        assertEquals(emptyMap<Int, Anchors>(), PlanAnchors.forAhead(pts, anchors, listOf(g(40.0), c, d), off = 1))
        assertEquals(emptyMap<Int, Anchors>(), PlanAnchors.forAhead(pts, anchors, pts + a, off = 1))
    }

    // ---- a day of a multi-day tour

    private val n = 300
    private val line = (0..n).map { GeoPoint(46.0 + it * 0.009, 11.0) }
    private val dayLine = DayLine(line, DoubleArray(n + 1) { it * 1000.0 }, DoubleArray(n + 1), DoubleArray(n + 1), DoubleArray(n + 1) { it * 180.0 })
    private val at = intArrayOf(0, 50, 100, 150, 220, 300)
    private val tourPts = at.map { line[it] }
    /** Point 1 (day 1), the night at 2 with both sides, 3 and 4 in the days after. */
    private val tourAnchors = mapOf(
        1 to Anchors(listOf(line[25]), listOf(line[75])),
        2 to Anchors(listOf(line[85]), listOf(line[120])),
        3 to Anchors(listOf(line[130]), listOf(line[180])),
    )
    private val whole = SharedPlan(tourPts, Activity.BIKE_TOURING, days = 3,
        nights = mapOf(2 to NightMark.OVERNIGHT, 4 to NightMark.OVERNIGHT), anchors = tourAnchors)
    private val tourDays = PlanDays.of(dayLine, WaypointInsertion.routeIndices(tourPts, line), 3, whole.limits, whole.nights)
    private fun dayPart(k: Int) = DayFollow.part(whole, tourDays, WaypointInsertion.routeIndices(tourPts, line), line, k, k)!!

    @Test fun aDayKeepsItsAnchorsButNotThoseThatFaceOutOfIt() {
        val day2 = dayPart(2) // points 2, 3, 4
        assertEquals(listOf(tourPts[2], tourPts[3], tourPts[4]), day2.points)
        assertEquals(mapOf(0 to Anchors(after = listOf(line[120])), 1 to tourAnchors[3]), day2.anchors)
    }

    @Test fun aDaysEditGoesBackAndTheOutsideSidesStay() {
        val day2 = dayPart(2)
        // point 3 (index 1 of the day) deleted: its anchors go, the day's first point lost its `after` with its neighbour
        val edited = listOf(day2.points[0], day2.points[2])
        val (back, _) = DayFollow.writeBack(whole, day2, edited, emptySet(), { null }, { null }, emptyMap())
        assertEquals(mapOf(1 to tourAnchors[1], 2 to Anchors(before = listOf(line[85]))), back.anchors)
    }

    @Test fun aDayUnchangedLeavesTheWholeToursAnchors() {
        val day2 = dayPart(2)
        val (back, _) = DayFollow.writeBack(whole, day2, day2.points, emptySet(), { null }, { null }, day2.anchors)
        assertEquals(tourAnchors, back.anchors)
    }
}
