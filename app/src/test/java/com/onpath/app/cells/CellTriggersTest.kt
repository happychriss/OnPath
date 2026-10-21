package com.onpath.app.cells

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trigger rules T1/T3/T4/T5/T6 (map-cells.md §9) against a fake [CellSource]. */
class CellTriggersTest {

    private class FakeCells(
        val installed: MutableSet<CellId> = mutableSetOf(),
        /** Region package bbox [w, s, e, n] or null. */
        val region: DoubleArray? = null,
        override var downloadsAvailable: Boolean = true,
    ) : CellSource {
        val queued = LinkedHashMap<CellId, Pair<CellReason, Boolean>>()
        val requests = ArrayList<Triple<List<CellId>, CellReason, Boolean>>()
        val used = HashSet<CellId>()
        var due: List<CellId> = emptyList()

        override fun cellsAround(p: GeoPoint, radiusM: Double) = CellId.near(p, radiusM)
        override fun cellsAlong(line: List<GeoPoint>, corridorM: Double) = CellId.alongLine(line, corridorM)
        override fun missing(cells: List<CellId>) = cells.filter { it !in installed && it !in queued }
        override fun hasDetailedMap(p: GeoPoint): Boolean =
            CellId.at(p) in installed || region?.let { b -> p.lon >= b[0] && p.lat >= b[1] && p.lon <= b[2] && p.lat <= b[3] } == true
        override fun request(cells: List<CellId>, reason: CellReason, ownPoint: Boolean): List<CellId> {
            requests += Triple(cells, reason, ownPoint)
            val added = cells.filter { (it !in installed || reason == CellReason.REFRESH) && it !in queued }
            added.forEach { queued[it] = reason to ownPoint }
            return added
        }
        override fun refreshDue(ageDays: Int) = due
        override fun installedAmong(cells: List<CellId>) = cells.filter { it in installed }
        override fun markUsed(cells: Collection<CellId>) { used += cells.filter { it in installed } }
    }

    private val meran = CellId(543, 361)
    private val east = CellId(544, 361)
    /** Close to Meran's east border (≈ 1 km): own cell + the eastern neighbour within 5 km. */
    private val nearEdge = GeoPoint(46.67, 11.24)
    private val middle = meran.center

    @Test fun t1PointWithoutMapAsksOwnCellAsPointAndNeighboursAsNear() {
        val f = FakeCells()
        val job = CellTriggers(f).pointSet(nearEdge)!!
        assertEquals(listOf(meran, east), job.cells)
        assertEquals(meran, job.own)
        assertEquals(CellWhy.PLANNED, job.why)
        assertEquals(Triple(listOf(meran), CellReason.POINT, true), f.requests[0])
        assertEquals(Triple(listOf(east), CellReason.NEAR, false), f.requests[1])
    }

    @Test fun t1PointWithDetailedMapAsksNothing() {
        val f = FakeCells(installed = mutableSetOf(meran))
        assertNull(CellTriggers(f).pointSet(middle))
        // region package covers the point: nothing either
        val g = FakeCells(region = doubleArrayOf(10.70, 46.30, 11.60, 46.85))
        assertNull(CellTriggers(g).pointSet(nearEdge))
        assertTrue(f.requests.isEmpty() && g.requests.isEmpty())
    }

    @Test fun t1NeighbourInsideRegionPackageIsNotFetched() {
        // region covers only the east cell; the point is in Meran (outside the region box)
        val f = FakeCells(region = doubleArrayOf(11.25, 46.30, 11.60, 46.85))
        val job = CellTriggers(f).pointSet(nearEdge)!!
        assertEquals(listOf(meran), job.cells)
    }

    @Test fun t1SecondPointInSameAreaAsksNothingNew() {
        val f = FakeCells()
        val t = CellTriggers(f)
        t.pointSet(middle)
        assertNull(t.pointSet(GeoPoint(middle.lat + 0.01, middle.lon)))
        assertEquals(1, f.requests.size)
    }

    @Test fun withoutAppKeyNothingIsRequestedButTheCardCanSaySo() {
        val f = FakeCells(downloadsAvailable = false)
        val job = CellTriggers(f).pointSet(middle)!!
        assertEquals(listOf(meran), job.cells)
        assertTrue(f.requests.isEmpty())
        assertTrue(CellTriggers(f).onTour(middle).isEmpty())
    }

    @Test fun t3NeedsAUsableFix() {
        val f = FakeCells()
        val t = CellTriggers(f)
        assertNull(t.appOpened(middle, 2000f))
        val job = t.appOpened(middle, 40f)!!
        assertEquals(CellWhy.HERE, job.why)
        assertEquals(listOf(meran), job.cells)
        assertEquals(CellReason.POINT, f.requests.single().second)
        // no accuracy known (some network fixes): still enough to pick the cell
        assertEquals(CellWhy.HERE, CellTriggers(FakeCells()).appOpened(middle, null)!!.why)
    }

    @Test fun t4AsksTheMissingCorridorAndMarksUsed() {
        val f = FakeCells(installed = mutableSetOf(meran))
        val line = listOf(middle, GeoPoint(46.67, 11.40)) // Meran → into the eastern cell
        val job = CellTriggers(f).routeComputed(line)!!
        assertTrue(east in job.cells && meran !in job.cells)
        assertNull(job.own)
        assertTrue(f.requests.all { it.second == CellReason.CORRIDOR && !it.third })
        assertEquals(setOf(meran), f.used)
        // computed again: nothing new
        assertNull(CellTriggers(f).routeComputed(line))
    }

    @Test fun t4FullyCoveredRouteAsksNothing() {
        val f = FakeCells(installed = CellId.alongLine(listOf(middle, GeoPoint(46.67, 11.20)), 10_000.0).toMutableSet())
        assertNull(CellTriggers(f).routeComputed(listOf(middle, GeoPoint(46.67, 11.20))))
        assertTrue(f.requests.isEmpty())
    }

    @Test fun t5NearTheEdgeAsksTheNextCellWithOwnPointRuleAndNotAgainAtOnce() {
        var now = 1_000_000L
        val f = FakeCells(installed = mutableSetOf(meran))
        val t = CellTriggers(f) { now }
        // middle of an installed cell: nothing
        assertTrue(t.onTour(middle).isEmpty())
        // 1 km from the east edge: the eastern cell, own-point rule (loads on mobile data)
        assertEquals(listOf(east), t.onTour(nearEdge))
        assertEquals(Triple(listOf(east), CellReason.NEAR, true), f.requests.last())
        // server failed → dropped from the queue; not asked again on the next fixes …
        f.queued.clear()
        assertTrue(t.onTour(nearEdge).isEmpty())
        // … but after half an hour
        now += CellTriggers.AHEAD_RETRY_MS
        assertEquals(listOf(east), t.onTour(nearEdge))
    }

    @Test fun t6RefreshOnceADay() {
        var now = 10L * CellTriggers.DAY_MS
        val f = FakeCells(installed = mutableSetOf(meran)).apply { due = listOf(meran) }
        val t = CellTriggers(f) { now }
        assertEquals(listOf(meran), t.refreshIfDue())
        assertEquals(CellReason.REFRESH, f.requests.last().second)
        f.queued.clear()
        assertTrue(t.refreshIfDue().isEmpty())
        now += CellTriggers.DAY_MS
        assertEquals(listOf(meran), t.refreshIfDue())
    }

    @Test fun mergeJoinsAnOpenCardAndReplacesAFinishedOne() {
        val a = CellJob(listOf(meran), meran, CellWhy.HERE)
        val b = CellJob(listOf(east, meran), null, CellWhy.PLANNED)
        assertEquals(CellJob(listOf(meran, east), meran, CellWhy.HERE), CellTriggers.merge(a, b, openDone = false))
        assertEquals(b, CellTriggers.merge(a, b, openDone = true))
        assertEquals(b, CellTriggers.merge(null, b, openDone = false))
    }

    @Test fun newPointsAreThoseNotInTheOldList() {
        val p1 = GeoPoint(46.6, 11.1); val p2 = GeoPoint(46.7, 11.2); val p3 = GeoPoint(46.8, 11.3)
        assertEquals(listOf(p3), CellTriggers.newPoints(listOf(p1, p2), listOf(p1, p3, p2)))
        // reverse / round trip add no new values
        assertTrue(CellTriggers.newPoints(listOf(p1, p2), listOf(p2, p1, p2)).isEmpty())
    }

    /** Owner 2026-10-21: after routing, the installed areas along the route are checked for a newer version (quiet). */
    @Test fun afterRoutingInstalledCorridorCellsAreCheckedOncePerHour() {
        var t = 1_000_000L
        val f = FakeCells(installed = mutableSetOf(meran))
        val tr = CellTriggers(f) { t }
        val line = listOf(GeoPoint(46.67, 11.15), GeoPoint(46.68, 11.17))
        assertEquals(listOf(meran), tr.freshenAlong(line))
        assertEquals(Triple(listOf(meran), CellReason.REFRESH, false), f.requests.last())
        f.queued.clear()
        assertEquals(emptyList<CellId>(), tr.freshenAlong(line)) // within the hour: no second check
        t += CellTriggers.FRESH_CHECK_MS
        assertEquals(listOf(meran), tr.freshenAlong(line))
        // nothing installed along the route / no key: nothing
        assertEquals(emptyList<CellId>(), CellTriggers(FakeCells()).freshenAlong(line))
        assertEquals(emptyList<CellId>(), CellTriggers(FakeCells(installed = mutableSetOf(meran), downloadsAvailable = false)).freshenAlong(line))
    }
}
