package com.onpath.app.cells

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.OfflineCorridor
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
        override fun installedAmong(cells: List<CellId>) = cells.filter { it in installed }
        override fun markUsed(cells: Collection<CellId>) { used += cells.filter { it in installed } }
        val kept = ArrayList<Set<CellId>>()
        override fun keepCorridor(keep: Collection<CellId>): List<CellId> {
            kept += keep.toSet()
            val drop = queued.filter { (c, r) -> r.first == CellReason.CORRIDOR && c !in keep }.keys.toList()
            drop.forEach { queued.remove(it) }
            return drop
        }
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

    /** A tap asks first (owner): the preview requests nothing; where the detailed map is, nothing to ask. */
    @Test fun tapPreviewAsksTheServerNothing() {
        val f = FakeCells()
        val want = CellTriggers(f).preview(nearEdge)
        assertEquals(listOf(meran, east), want.take(2))
        assertTrue(f.requests.isEmpty())
        assertTrue(CellTriggers(FakeCells(installed = mutableSetOf(meran))).preview(middle).isEmpty())
        // after "Load": the same regions are asked for, the job is about the tapped point
        val job = CellTriggers(f).pointSet(nearEdge, CellWhy.TAPPED)!!
        assertEquals(want.toSet(), job.cells.toSet())
        assertEquals(CellWhy.TAPPED, job.why)
        assertTrue(CellTriggers(f).preview(nearEdge).isEmpty()) // all asked for already
    }

    /** Modal → Cancel drops the cells: searching there again starts loading again normally. */
    @Test fun searchAgainAfterCancelLoadsAgain() {
        val f = FakeCells()
        val t = CellTriggers(f)
        val first = t.pointSet(nearEdge, CellWhy.SEARCHED)!!
        assertEquals(CellWhy.SEARCHED, first.why)
        first.cells.forEach { f.queued.remove(it) } // MapCells.cancel
        val again = t.pointSet(nearEdge, CellWhy.SEARCHED)!!
        assertEquals(first.cells, again.cells)
        assertEquals(meran, again.own)
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

    /**
     * "New route – even without signal" (field 0.13.3, emulator shoot A-019): a route ~1.5 km from an area border
     * brings the neighbouring area too – the margin of [OfflineCorridor.HIKE_M] either side (a hike), start and destination
     * included – so a hut or a detour just over the border routes offline later.
     */
    @Test fun t4RouteNearAnAreaBorderAsksTheNeighbourWithinTheMargin() {
        val f = FakeCells(installed = mutableSetOf(meran))
        // ≈ 1.5 km west of Meran's east border (lon 11.25), the whole route inside Meran
        val line = listOf(GeoPoint(46.70, 11.20), GeoPoint(46.69, 11.23))
        assertEquals(meran, CellId.at(line[0])); assertEquals(meran, CellId.at(line[1]))
        val job = CellTriggers(f).routeComputed(line, activity = Activity.HIKE)!!
        assertTrue("the eastern neighbour is within the margin", east in f.queued)
        assertEquals(CellReason.CORRIDOR to false, f.queued[east])
        // the route itself has the detailed map: the neighbour loads quietly (download chip), no "No detailed map" card
        assertEquals(emptyList<CellId>(), job.cells)
        assertEquals(listOf(east), job.margin)
    }

    /**
     * The field case: Vellau → Leiteralm lies > 10 km from every border of Meran (one area); appending Gasthof Hochmuth
     * (~7.5 km from the east border) brings the eastern area into the margin. Offline the card said
     * "No detailed map – loads when you're online" although the route and Hochmuth had the map: the margin is quiet.
     */
    @Test fun fieldVellauLeiteralmThenHochmuth() {
        val f = FakeCells(installed = mutableSetOf(meran))
        val t = CellTriggers(f)
        val vellau = GeoPoint(46.6956, 11.10935); val leiteralm = GeoPoint(46.70605, 11.11125)
        val hochmuth = GeoPoint(46.7035, 11.1515)
        assertNull("one area covers the planned hike and its margin", t.routeComputed(listOf(vellau, leiteralm), activity = Activity.HIKE))
        val job = t.routeComputed(listOf(vellau, leiteralm, hochmuth), activity = Activity.HIKE)!!
        assertEquals(listOf(east), job.margin)
        assertTrue("Hochmuth has the detailed map: no card", job.cells.isEmpty())
    }

    /** A cell the route itself crosses stays on the card (the user waits for it); margin cells beside it are quiet. */
    @Test fun t4CellsUnderTheRouteAreOnTheCardTheMarginIsQuiet() {
        val f = FakeCells(installed = mutableSetOf(meran))
        val line = listOf(middle, GeoPoint(46.60, 11.40)) // into the eastern cell, ~5 km from its south border
        val job = CellTriggers(f).routeComputed(line)!!
        assertEquals(listOf(east), job.cells)
        assertTrue(job.margin.isNotEmpty() && east !in job.margin && job.margin.all { it in f.queued })
    }

    /**
     * A tour starts on the planned route (F-OFF-02 "started route"): what of its corridor is still missing – the user
     * cancelled the loading, the server failed, the corridor was capped – is asked for again, quietly.
     */
    @Test fun tourStartAsksTheMissingCorridorAgain() {
        val f = FakeCells(installed = mutableSetOf(meran))
        val t = CellTriggers(f)
        val line = listOf(GeoPoint(46.70, 11.20), GeoPoint(46.69, 11.23))
        t.routeComputed(line, activity = Activity.HIKE)
        f.queued.clear() // cancelled / failed: dropped from the queue
        assertEquals(listOf(east), t.tourStarted(line, activity = Activity.HIKE))
        assertEquals(Triple(listOf(east), CellReason.CORRIDOR, false), f.requests.last())
        // everything there or asked for: nothing new; no key / no line: nothing
        assertEquals(emptyList<CellId>(), t.tourStarted(line, activity = Activity.HIKE))
        assertEquals(emptyList<CellId>(), CellTriggers(FakeCells(downloadsAvailable = false)).tourStarted(line))
        assertEquals(emptyList<CellId>(), t.tourStarted(listOf(middle)))
    }

    @Test fun t4FullyCoveredRouteAsksNothing() {
        for (a in listOf(Activity.HIKE, Activity.MTB, null)) {
            val f = FakeCells(installed = CellId.alongLine(listOf(middle, GeoPoint(46.67, 11.20)), OfflineCorridor.marginM(a)).toMutableSet())
            assertNull("$a", CellTriggers(f).routeComputed(listOf(middle, GeoPoint(46.67, 11.20)), activity = a))
            assertTrue("$a", f.requests.isEmpty())
        }
        // a hike's corridor installed is not enough for a bike plan: the wider margin loads
        val f = FakeCells(installed = CellId.alongLine(listOf(middle, GeoPoint(46.67, 11.20)), OfflineCorridor.HIKE_M).toMutableSet())
        assertTrue(CellTriggers(f).routeComputed(listOf(middle, GeoPoint(46.67, 11.20)), activity = Activity.MTB)!!.margin.isNotEmpty())
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

    /** After routing, the installed areas along the route are checked for a newer version (quiet). */
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

    /** The corridor belongs to the current plan – a new route drops the old corridor first. */
    @Test fun t4NewRouteDropsTheOldCorridor() {
        val f = FakeCells()
        val t = CellTriggers(f)
        val east2 = listOf(middle, GeoPoint(46.67, 11.70)) // Meran → two cells east
        t.routeComputed(east2)!!
        val far = CellId.alongLine(east2, OfflineCorridor.MAX_M) - CellId.alongLine(listOf(middle, GeoPoint(46.67, 11.20)), OfflineCorridor.MAX_M).toSet()
        assertTrue(far.isNotEmpty() && far.all { it in f.queued })
        // undo: the route is short again → the far cells go
        t.routeComputed(listOf(middle, GeoPoint(46.67, 11.20)))
        assertTrue(far.none { it in f.queued })
    }

    /** No route (cleared, long-route question, Cancel, not routed): no corridor, nothing requested. */
    @Test fun planWithoutRouteKeepsNoCorridor() {
        val f = FakeCells()
        val t = CellTriggers(f)
        t.routeComputed(listOf(middle, GeoPoint(46.67, 11.70)))
        val before = f.requests.size
        t.planRoute(null)
        assertTrue(f.queued.isEmpty())
        assertEquals(emptySet<CellId>(), f.kept.last())
        assertEquals(before, f.requests.size)
    }

    /** A huge corridor never asks for hundreds of regions: the first stretch only. */
    @Test fun t4CorridorIsCappedToItsFirstStretch() {
        val f = FakeCells()
        // Meran → Hamburg, ~900 km straight: far more corridor cells than the cap
        val line = (0..90).map { GeoPoint(46.67 + it * 0.0894, 11.16 - it * 0.0127) }
        assertTrue(CellId.alongLine(line, OfflineCorridor.MAX_M).size > CellTriggers.CORRIDOR_MAX_CELLS)
        val job = CellTriggers(f).routeComputed(line)!!
        assertEquals(CellTriggers.CORRIDOR_MAX_CELLS, (job.cells + job.margin).size)
        assertTrue("the stretch at the start loads first", CellId.at(line.first()) in job.cells)
    }

    /**
     * Owner 2026-11-07 (F-OFF-02): the corridor is 10 km on foot and 20 km for every bike activity. The Vellau → Leiteralm
     * line has areas 10–20 km away: a bike plan asks for them (quietly, as margin), a hike plan does not, a plan whose
     * activity is not known gets the bike's corridor (robust first).
     */
    @Test fun t4CorridorWidthFollowsTheActivity() {
        val line = listOf(GeoPoint(46.6956, 11.10935), GeoPoint(46.70605, 11.11125))
        val band = CellId.alongLine(line, 20_000.0) - CellId.alongLine(line, 10_000.0).toSet()
        assertTrue("areas between 10 and 20 km", band.isNotEmpty())
        val hike = FakeCells(installed = mutableSetOf(meran))
        assertNull(CellTriggers(hike).routeComputed(line, activity = Activity.HIKE))
        assertTrue(band.none { it in hike.queued })
        for (a in Activity.entries.filter { it != Activity.HIKE } + listOf(null)) {
            val bike = FakeCells(installed = mutableSetOf(meran))
            val job = CellTriggers(bike).routeComputed(line, activity = a)!!
            assertTrue("$a", band.all { it in bike.queued })
            // the route itself has the detailed map: the wider margin is quiet, never "No detailed map"
            assertTrue("$a", job.cells.isEmpty())
            assertEquals("$a", job.margin.toSet(), bike.queued.keys)
        }
        // the same corridor through corridorOf (the precomputed one the ViewModel passes)
        assertEquals(CellId.alongLine(line, 20_000.0), CellTriggers(FakeCells()).corridorOf(line, Activity.MTB))
        assertEquals(CellId.alongLine(line, 10_000.0), CellTriggers(FakeCells()).corridorOf(line, Activity.HIKE))
    }

    /** The activity changes on a plan (bike → hike): the next computed route's corridor drops the bike's wider margin. */
    @Test fun t4ActivityChangeNarrowsTheCorridorOnTheNextRoute() {
        val line = listOf(GeoPoint(46.6956, 11.10935), GeoPoint(46.70605, 11.11125))
        val band = CellId.alongLine(line, 20_000.0) - CellId.alongLine(line, 10_000.0).toSet()
        val f = FakeCells(installed = mutableSetOf(meran))
        val t = CellTriggers(f)
        t.routeComputed(line, activity = Activity.EBIKE)
        assertTrue(band.all { it in f.queued })
        t.routeComputed(line, activity = Activity.HIKE)
        assertTrue(band.none { it in f.queued })
        // the plan settles without a new route but with its line: the hike corridor is kept, the bike's band stays gone
        t.routeComputed(line, activity = Activity.MTB)
        t.planRoute(line, Activity.HIKE)
        assertTrue(band.none { it in f.queued })
    }

    /** A tour started on a bike route secures the 20 km corridor; on foot 10 km. */
    @Test fun tourStartedUsesTheActivityCorridor() {
        val line = listOf(GeoPoint(46.6956, 11.10935), GeoPoint(46.70605, 11.11125))
        val band = CellId.alongLine(line, 20_000.0) - CellId.alongLine(line, 10_000.0).toSet()
        val hike = FakeCells(installed = mutableSetOf(meran))
        assertTrue(CellTriggers(hike).tourStarted(line, activity = Activity.HIKE).none { it in band })
        val bike = FakeCells(installed = mutableSetOf(meran))
        assertTrue(CellTriggers(bike).tourStarted(line, activity = Activity.BIKE_TREKKING).containsAll(band))
    }
}
