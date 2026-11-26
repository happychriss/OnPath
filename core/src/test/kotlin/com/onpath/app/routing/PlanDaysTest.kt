package com.onpath.app.routing

import com.onpath.app.time.Factors
import com.onpath.app.time.RouteTimes
import com.onpath.app.time.TimeEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Multi-day tours (docs/design/multi-day-tours.md, owner 2026-11-17): the grey proposals, the day rows, the limits and
 * the rules R1–R7 – the scenarios D–N of §4 and the edge cases.
 */
class PlanDaysTest {
    /** A line of [n] steps of 1 km northwards, [stepS] seconds each, [up] metres up on step k (k → k + 1). */
    private fun line(n: Int, stepS: Double = 180.0, up: (Int) -> Double = { 0.0 }): DayLine {
        val pts = (0..n).map { GeoPoint(46.0 + it * 0.009, 11.0) }
        val ups = DoubleArray(n + 1).also { for (k in 1..n) it[k] = it[k - 1] + up(k - 1) }
        return DayLine(pts, DoubleArray(n + 1) { it * 1000.0 }, ups, DoubleArray(n + 1), DoubleArray(n + 1) { it * stepS })
    }

    /** München – Venedig as a straight 600 km: 5 plan points at km 0, 150, 300, 450, 600; 6 days, 3 min per km. */
    private val ride = line(600)
    private val at = intArrayOf(0, 150, 300, 450, 600)
    private val tour = SharedPlan(at.map { ride.points[it] }, Activity.BIKE_TOURING, days = 6)

    private fun days(p: SharedPlan, l: DayLine = ride, pointAt: IntArray = WaypointInsertion.routeIndices(p.points, l.points)) =
        PlanDays.of(l, pointAt, p.days ?: 1, p.limits, p.nights)

    private fun km(t: TourDays) = t.days.map { it.distanceM / 1000 }

    private fun assertKm(expected: List<Double>, t: TourDays, slack: Double = 1.0) {
        assertEquals(expected.size, t.days.size)
        expected.zip(km(t)).forEach { (e, a) -> assertTrue("$expected vs ${km(t)}", abs(e - a) <= slack) }
    }

    // ---- the days --------------------------------------------------------------------------------------------------

    @Test fun scenarioA_sixDaysWithoutMarksAreEqual() {
        val t = days(tour)
        assertEquals(5, t.proposals.size)
        assertEquals(listOf(100, 200, 300, 400, 500), t.proposals.map { it.lineIndex })
        assertTrue(t.proposals.all { it.kind == DayEndKind.PROPOSAL && it.planIndex == null })
        assertEquals(ride.points[100], t.proposals[0].point)
        assertEquals(100_000.0, t.proposals[0].alongM, 0.0)
        assertKm(List(6) { 100.0 }, t, 0.0)
        assertEquals((1..6).toList(), t.days.map { it.number })
        assertNull(t.days.last().end)
        assertTrue(t.days.all { it.timeS == 18_000.0 && it.over.isEmpty() })
        assertNull("no limits: nothing to suggest", t.suggestedDays)
    }

    @Test fun scenarioD_anOvernightOnNight2EvensOutBothSides() {
        val e = PlanDays.addNight(tour, ride.points, at, 150)
        assertFalse(e.dayAdded)
        assertEquals(tour.points, e.plan.points) // a plan point is there: it gets the mark, no new point
        assertEquals(mapOf(1 to NightMark.OVERNIGHT), e.plan.nights)
        assertEquals(6, e.plan.days)
        val t = days(e.plan)
        assertEquals(4, t.proposals.size)
        assertKm(listOf(75.0, 75.0, 112.5, 112.5, 112.5, 112.5), t, 0.5)
        assertEquals(DayEndKind.OVERNIGHT, t.days[1].end!!.kind)
        assertEquals(1, t.days[1].end!!.planIndex)
    }

    @Test fun scenarioE_aHotelMovesThePointThere() {
        val blue = PlanDays.addNight(tour, ride.points, at, 150).plan
        val hotelAt = GeoPoint(47.3502, 11.0105)
        val e = PlanDays.setHotel(blue, 1, Hotel("Gasthof Post", link = "https://b.example/p"), hotelAt)
        assertFalse(e.dayAdded)
        assertEquals(hotelAt, e.plan.points[1])
        assertEquals(NightMark(Hotel("Gasthof Post", link = "https://b.example/p")), e.plan.nights[1])
        assertEquals("Gasthof Post", e.plan.names[1]) // an older app shows it as a named point (scenario Q)
        assertEquals(6, e.plan.days)
        assertEquals(DayEndKind.HOTEL, days(e.plan).days[1].end!!.kind)
    }

    @Test fun scenarioF_aHotelBeforeAnyOvernight() {
        val e = PlanDays.setHotel(tour, 2, Hotel("Hotel Brenner"), GeoPoint(47.70, 11.01))
        assertFalse(e.dayAdded)
        assertEquals(6, e.plan.days)
        assertEquals(setOf(2), e.plan.nights.keys)
        assertEquals(4, PlanDays.proposalCount(e.plan))
        // the start and the finish never get a hotel
        assertEquals(tour, PlanDays.setHotel(tour, 0, Hotel("X"), GeoPoint(46.0, 11.0)).plan)
        assertEquals(tour, PlanDays.setHotel(tour, 4, Hotel("X"), GeoPoint(46.0, 11.0)).plan)
    }

    @Test fun scenarioG_hotelRemovedTheNightStaysWhereTheRouteLeavesForIt() {
        // the route turns off at km 100 for 5 km to the hotel and comes back over the same nodes, then goes on north
        val north = (0..100).map { GeoPoint(46.0 + it * 0.009, 11.0) }
        val out = (1..5).map { GeoPoint(46.9, 11.0 + it * 0.013) }
        val back = out.reversed().drop(1) + north.last()
        val on = (1..100).map { GeoPoint(46.9 + it * 0.009, 11.0) }
        val pts = north + out + back + on
        val hotel = out.last()
        val plan = SharedPlan(listOf(pts.first(), hotel, pts.last()), Activity.BIKE_TOURING, names = mapOf(1 to "Post"), days = 2,
            nights = mapOf(1 to NightMark(Hotel("Post"))))
        val pointAt = WaypointInsertion.routeIndices(plan.points, pts)
        assertEquals(105, pointAt[1])
        val p = PlanDays.removeHotel(plan, 1, pts, pointAt)
        assertEquals(NightMark.OVERNIGHT, p.nights[1])
        assertEquals(north.last(), p.points[1])
        assertEquals(emptyMap<Int, String>(), p.names)
        assertEquals(2, p.days)
        // a hotel the route passes without a detour: the point stays
        val onRoute = tour.copy(nights = mapOf(2 to NightMark(Hotel("Am Weg"))))
        assertEquals(tour.points, PlanDays.removeHotel(onRoute, 2, ride.points, at).points)
        // not a hotel: unchanged
        assertEquals(tour, PlanDays.removeHotel(tour, 2, ride.points, at))
    }

    @Test fun scenarioH_overnightRemovedAGreyComesBack() {
        val blue = PlanDays.addNight(tour, ride.points, at, 300).plan
        assertEquals(4, PlanDays.proposalCount(blue))
        val p = PlanDays.removeNight(blue, 2)
        assertEquals(tour.points, p.points)
        assertEquals(6, p.days)
        assertEquals(5, PlanDays.proposalCount(p))
        assertEquals(days(tour), days(p))
        // a hotel's night goes with its point (it was there for the hotel only)
        val green = PlanDays.setHotel(blue, 2, Hotel("H"), GeoPoint(47.35, 11.02)).plan
        val q = PlanDays.removeNight(green, 2)
        assertEquals(tour.points - tour.points[2], q.points)
        assertEquals(emptyMap<Int, NightMark>(), q.nights)
        assertEquals(6, q.days)
    }

    @Test fun scenarioI_aNightMovedElsewhere() {
        val blue = PlanDays.addNight(tour, ride.points, at, 150).plan
        // remove it and add it at km 200 (a new point on the line)
        val moved = PlanDays.addNight(PlanDays.removeNight(blue, 1), ride.points, at, 200)
        assertFalse(moved.dayAdded)
        assertEquals(6, moved.plan.days)
        assertEquals(ride.points[200], moved.plan.points[2])
        assertEquals(mapOf(2 to NightMark.OVERNIGHT), moved.plan.nights)
        // a blue point dragged: the night goes with it
        val dragged = PlanDays.afterEdit(blue, PlanEdits.replace(blue.points, 1, ride.points[180]))
        assertEquals(mapOf(1 to NightMark.OVERNIGHT), dragged.nights)
        assertEquals(180, days(dragged).days[1].end!!.lineIndex)
    }

    @Test fun scenarioJ_oneDayMoreOrFewer() {
        val blue = PlanDays.addNight(tour, ride.points, at, 150).plan
        val seven = PlanDays.setDays(blue, 7)!!
        assertEquals(5, days(seven).proposals.size)
        assertEquals(blue.nights, seven.nights)
        assertEquals(2, days(PlanDays.setDays(blue, 4)!!).proposals.size) // 4 days: 1 night + 2 greys
        assertNull("− with no grey left: not possible", PlanDays.setDays(blue, 1))
        assertNull(PlanDays.setDays(blue, 0))
        assertNull(PlanDays.setDays(blue, PlanCode.MAX_DAYS + 1))
        assertTrue(PlanDays.canRemoveDay(blue))
        assertFalse(PlanDays.canRemoveDay(PlanDays.setDays(blue, 2)!!))
    }

    @Test fun scenarioK_everyNightDecidedAndOneMoreStop() {
        var p = tour.copy(days = 3)
        p = PlanDays.addNight(p, ride.points, at, 150).plan
        p = PlanDays.addNight(p, ride.points, at, 300).plan
        assertEquals(0, PlanDays.proposalCount(p))
        assertEquals(3, p.days)
        val e = PlanDays.addNight(p, ride.points, at, 450)
        assertTrue("N + 1, said in one line", e.dayAdded)
        assertEquals(4, e.plan.days)
        assertEquals(setOf(1, 2, 3), e.plan.nights.keys)
        assertTrue(days(e.plan).proposals.isEmpty())
    }

    @Test fun scenarioL_anotherPassMovesOnlyTheGreys() {
        val blue = PlanDays.addNight(tour, ride.points, at, 150).plan
        // day 4 gets a 60 km detour: the line is longer behind km 300, the plan points are the same
        val pts = ride.points.take(301) + (1..60).map { GeoPoint(46.0 + 300 * 0.009, 11.0 + it * 0.0001) } + ride.points.drop(301)
        val longer = DayLine(pts, DoubleArray(pts.size) { it * 1000.0 }, DoubleArray(pts.size), DoubleArray(pts.size),
            DoubleArray(pts.size) { it * 180.0 })
        val before = days(blue)
        val after = days(blue, longer)
        assertEquals(DayEndKind.OVERNIGHT, after.days[1].end!!.kind)
        assertEquals(150, after.days[1].end!!.lineIndex) // the user's night stays
        assertEquals(before.proposals.size, after.proposals.size)
        assertTrue(before.proposals.map { it.lineIndex } != after.proposals.map { it.lineIndex })
        assertKm(listOf(75.0, 75.0, 127.5, 127.5, 127.5, 127.5), after, 0.5)
    }

    @Test fun scenarioM_aGreyDraggedBecomesBlueThere() {
        val grey = days(tour).proposals[1] // at km 200
        val e = PlanDays.addNight(tour, ride.points, at, grey.lineIndex + 10)
        assertFalse(e.dayAdded)
        assertEquals(6, e.plan.days)
        assertEquals(ride.points[210], e.plan.points[2]) // a new point on the line, between km 150 and 300
        assertEquals(mapOf(2 to NightMark.OVERNIGHT), e.plan.nights)
        val t = days(e.plan)
        assertEquals(4, t.proposals.size)
        assertTrue(t.days.any { it.end?.kind == DayEndKind.OVERNIGHT && it.end!!.lineIndex == 210 })
        // the start, the finish, a point that is marked already: unchanged
        assertEquals(tour, PlanDays.addNight(tour, ride.points, at, 0).plan)
        assertEquals(tour, PlanDays.addNight(tour, ride.points, at, 600).plan)
        assertEquals(e.plan, PlanDays.addNight(e.plan, ride.points, WaypointInsertion.routeIndices(e.plan.points, ride.points), 210).plan)
    }

    @Test fun scenarioN_deletingAMarkedPoint() {
        val blue = PlanDays.addNight(tour, ride.points, at, 300).plan
        val p = PlanDays.afterEdit(blue, PlanPoints.deleteAt(blue.points, 2))
        assertEquals(emptyMap<Int, NightMark>(), p.nights)
        assertEquals(6, p.days)
        assertEquals(5, days(p).proposals.size)
    }

    // ---- limits ------------------------------------------------------------------------------------------------------

    @Test fun limitsSuggestTheNumberOfDays() {
        val five = DayLimits(maxTimeMin = 300)
        assertEquals(6, PlanDays.suggestDays(ride, at, five)) // 30 h at 5 h a day
        assertEquals(7, PlanDays.suggestDays(ride, at, five, mapOf(1 to NightMark.OVERNIGHT))) // 7.5 h + 22.5 h
        assertNull(PlanDays.suggestDays(ride, at, DayLimits.NONE))
        assertEquals(6, days(tour.copy(limits = five)).suggestedDays)
    }

    @Test fun greysKeepTheDaysWithinTheLimitsWherePossible() {
        // 2000 m up in the first 100 km, flat after: equal days by time would put 2000 m on day 1
        val l = line(400) { k -> if (k < 100) 20.0 else 0.0 }
        val p = SharedPlan(listOf(l.points.first(), l.points.last()), Activity.BIKE_TOURING, days = 4,
            limits = DayLimits(maxAscentM = 1000))
        val t = days(p, l)
        assertEquals(3, t.proposals.size)
        assertTrue(t.days.joinToString { "${it.ascentM}" }, t.days.all { it.over.isEmpty() && it.ascentM <= 1000 })
        assertEquals(2, t.suggestedDays)
        // and otherwise as equal by time as the limit lets them be: day 1 climbs 1000 m, the rest share 350 km
        assertKm(listOf(50.0, 116.7, 116.7, 116.7), t)
        // without the limit: equal by time
        assertKm(List(4) { 100.0 }, days(p.copy(limits = DayLimits.NONE), l), 0.0)
    }

    @Test fun aLimitImpossibleToMeetIsFlaggedNothingMoves() {
        val l = line(400) { k -> if (k < 100) 20.0 else 0.0 }
        val p = SharedPlan(listOf(l.points.first(), l.points.last()), Activity.BIKE_TOURING, days = 2,
            limits = DayLimits(maxTimeMin = 400, maxAscentM = 100))
        val t = days(p, l)
        assertKm(listOf(200.0, 200.0), t, 0.0) // equal by time
        assertEquals(setOf(DayLimitKind.ASCENT, DayLimitKind.TIME), t.days[0].over) // 2000 m, 10 h
        assertEquals(setOf(DayLimitKind.TIME), t.days[1].over) // 200 km × 3 min = 10 h > 6 h 40
        assertEquals(20 + 2, t.suggestedDays) // 2000 m at 100 m a day + the flat 300 km at 400 min a day
    }

    @Test fun aDayAboveALimitAfterAFarHotelIsOrange() {
        // R8: a night the user set makes day 1 too long – flagged, the night stays
        val p = tour.copy(days = 2, limits = DayLimits(maxTimeMin = 900), nights = mapOf(3 to NightMark.OVERNIGHT))
        val t = days(p)
        assertEquals(setOf(DayLimitKind.TIME), t.days[0].over) // 450 km = 22.5 h
        assertEquals(emptySet<DayLimitKind>(), t.days[1].over)
        assertEquals(450, t.days[0].end!!.lineIndex)
    }

    // ---- edge cases --------------------------------------------------------------------------------------------------

    @Test fun oneDayIsTheWholeRoute() {
        val t = days(tour.copy(days = 1))
        assertTrue(t.proposals.isEmpty())
        assertEquals(1, t.days.size)
        assertEquals(600_000.0, t.days[0].distanceM, 0.0)
        assertEquals(108_000.0, t.days[0].timeS, 0.0)
        assertNull(t.days[0].end)
        // a normal plan (no N) reads as one day
        assertEquals(1, days(tour.copy(days = null)).days.size)
    }

    @Test fun everyNightDecided() {
        val p = tour.copy(days = 3, nights = mapOf(1 to NightMark.OVERNIGHT, 3 to NightMark(Hotel("H"))))
        val t = days(p)
        assertTrue(t.proposals.isEmpty())
        assertKm(listOf(150.0, 300.0, 150.0), t, 0.0)
        assertEquals(listOf(DayEndKind.OVERNIGHT, DayEndKind.HOTEL), t.days.mapNotNull { it.end?.kind })
        assertFalse(PlanDays.canRemoveDay(p))
        assertNull(PlanDays.setDays(p, 2))
        // N stored smaller than the marks (a damaged or hand-made plan): one day per mark + 1
        assertEquals(3, days(p.copy(days = 1)).days.size)
    }

    @Test fun aRouteOfTwoPoints() {
        val l = line(1)
        val p = SharedPlan(l.points, Activity.HIKE, days = 3)
        val t = days(p, l)
        assertEquals(3, t.days.size)
        assertEquals(1000.0, t.days.sumOf { it.distanceM }, 0.0)
        assertEquals(PlanDays.addNight(p, l.points, intArrayOf(0, 1), 1).plan, p) // only start and finish: nothing to mark
        // and an empty line: no days, no crash
        assertTrue(PlanDays.of(DayLine(emptyList(), DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0)),
            IntArray(0), 3, DayLimits.NONE, emptyMap()).days.isEmpty())
    }

    @Test fun aRoundTrip() {
        // 300 km out, 300 km back on another way: start = finish
        val out = (0..300).map { GeoPoint(46.0 + it * 0.009, 11.0) }
        val back = (299 downTo 0).map { GeoPoint(46.0 + it * 0.009, 11.05) } + out.first()
        val pts = out + back.drop(0)
        val l = DayLine(pts, DoubleArray(pts.size) { it * 1000.0 }, DoubleArray(pts.size), DoubleArray(pts.size),
            DoubleArray(pts.size) { it * 180.0 })
        val plan = SharedPlan(listOf(pts.first(), pts[150], pts[300], pts[450], pts.first()), Activity.BIKE_TOURING, days = 4)
        assertTrue(PlanEdits.isRoundTrip(plan.points))
        val e = PlanDays.addNight(plan, pts, WaypointInsertion.routeIndices(plan.points, pts), 300)
        assertEquals(mapOf(2 to NightMark.OVERNIGHT), e.plan.nights)
        val t = days(e.plan, l)
        assertEquals(4, t.days.size)
        assertEquals(l.distM.last(), t.days.sumOf { it.distanceM }, 1e-6)
        assertEquals(300, t.days[1].end!!.lineIndex)
        // the closing point never gets a night
        assertEquals(e.plan, PlanDays.afterEdit(e.plan.copy(nights = e.plan.nights + (4 to NightMark.OVERNIGHT)), e.plan.points))
    }

    @Test fun brokenInputNeverThrowsAndTheDaysStillAddUp() {
        val rnd = kotlin.random.Random(5)
        repeat(500) {
            val n = rnd.nextInt(0, 30)
            val l = line(n) { rnd.nextDouble(0.0, 50.0) }
            val pointAt = IntArray(rnd.nextInt(0, 6)) { rnd.nextInt(-3, n + 4) }
            val nights = (0 until rnd.nextInt(0, 5)).associate { rnd.nextInt(-1, 7) to NightMark.OVERNIGHT }
            val limits = if (rnd.nextBoolean()) DayLimits.NONE else DayLimits(rnd.nextInt(1, 100), rnd.nextInt(1, 200))
            val t = PlanDays.of(l, pointAt, rnd.nextInt(-2, 12), limits, nights)
            assertEquals(l.distM.last(), t.days.sumOf { it.distanceM }, 1e-6)
            assertTrue(t.days.all { it.distanceM >= 0 })
        }
    }

    @Test fun startSetsN() {
        assertEquals(6, PlanDays.start(tour.copy(days = null), 6).days)
        assertEquals(PlanCode.MAX_DAYS, PlanDays.start(tour, 1000).days)
        assertEquals(3, PlanDays.start(tour.copy(nights = mapOf(1 to NightMark.OVERNIGHT, 2 to NightMark.OVERNIGHT)), 1).days)
    }

    @Test fun theDaysAddUpToThePlan() {
        val t = days(tour.copy(days = 7, nights = mapOf(1 to NightMark.OVERNIGHT, 3 to NightMark.OVERNIGHT)))
        assertEquals(600_000.0, t.days.sumOf { it.distanceM }, 1e-6)
        assertEquals(108_000.0, t.days.sumOf { it.timeS }, 1e-6)
        assertEquals(t.days.map { it.toM }, t.days.map { it.fromM }.drop(1) + 600_000.0)
    }

    @Test fun theLineFromARealRoute() {
        // DayLine.of: the plan sheet's ascent (Planner.climb) and OnPath's time (TimeEstimate.planSeconds)
        val pts = (0..800).map { GeoPoint(46.0 + it * 0.0005, 11.0, ele = 600 + 300 * sin(it / 60.0)) }
        val l = DayLine.of(pts, Activity.HIKE, Factors.ONE)
        val (up, down) = Planner.climb(pts)
        assertEquals(up, l.upM.last(), 1e-9)
        assertEquals(down, l.downM.last(), 1e-9)
        assertEquals(RouteTimes.of(pts, Activity.HIKE).total(), l.timeS.last(), 1e-6)
        assertEquals(TimeEstimate.planSeconds(pts, Activity.HIKE, Factors.ONE), TimeEstimate.round(l.timeS.last()), 0.0)
        assertTrue((1 until l.timeS.size).all { l.timeS[it] >= l.timeS[it - 1] && l.upM[it] >= l.upM[it - 1] })
        val f = Factors(1.2, 1.2, 1.2)
        assertEquals(RouteTimes.of(pts, Activity.HIKE).total(f), DayLine.of(pts, Activity.HIKE, f).timeS.last(), 1e-6)
    }

    // detour anchors follow their points through every edit (docs/design/detour-stitch.md §2)

    private val q1 = GeoPoint(46.5, 11.0)
    private val q2 = GeoPoint(46.6, 11.0)
    private val q3 = GeoPoint(46.7, 11.0)
    private val q4 = GeoPoint(46.8, 11.0)

    @Test fun anchorsFollowAnEditOfTheRoute() {
        val plan = tour.copy(anchors = mapOf(2 to Anchors(listOf(q1), listOf(q2))))
        // a point in front is deleted: the anchors move to index 1 and keep both sides
        assertEquals(mapOf(1 to Anchors(listOf(q1), listOf(q2))), PlanDays.afterEdit(plan, plan.points.drop(1)).anchors)
        // the next point is deleted: the `after` side goes
        assertEquals(mapOf(2 to Anchors(listOf(q1))), PlanDays.afterEdit(plan, plan.points.filterIndexed { i, _ -> i != 3 }).anchors)
        // its own point is deleted: gone
        assertTrue(PlanDays.afterEdit(plan, plan.points.filterIndexed { i, _ -> i != 2 }).anchors.isEmpty())
    }

    @Test fun reversingARouteReversesItsAnchors() {
        val w = listOf(GeoPoint(46.1, 11.0), GeoPoint(46.2, 11.0), GeoPoint(46.3, 11.0), GeoPoint(46.4, 11.0), GeoPoint(46.9, 11.0))
        val plan = SharedPlan(w, Activity.HIKE, anchors = mapOf(3 to Anchors(listOf(q1, q2), listOf(q3))))
        assertEquals(mapOf(1 to Anchors(listOf(q3), listOf(q2, q1))), PlanDays.afterEdit(plan, w.reversed()).anchors)
        // a plan equal to its own reverse is no edit
        val sym = listOf(w[0], w[1], w[0])
        val symPlan = SharedPlan(sym, Activity.HIKE, anchors = mapOf(1 to Anchors(listOf(q1), listOf(q2))))
        assertEquals(symPlan.anchors, PlanDays.afterEdit(symPlan, sym).anchors)
    }

    @Test fun aHotelThatMovesAPointTakesItsAnchorsAndTheNeighboursFacingSide() {
        val plan = PlanDays.addNight(tour, ride.points, at, 150).plan.copy(
            anchors = mapOf(1 to Anchors(listOf(q1), listOf(q2)), 2 to Anchors(listOf(q3), listOf(q4))))
        val e = PlanDays.setHotel(plan, 1, Hotel("Post"), GeoPoint(47.3502, 11.0105))
        // point 1 moved: its anchors are gone; point 2 lost its `before` (its neighbour moved), kept `after`
        assertEquals(mapOf(2 to Anchors(after = listOf(q4))), e.plan.anchors)
    }

    @Test fun removingAHotelThatMovesItsPointDropsTheAnchorsToo() {
        val north = (0..100).map { GeoPoint(46.0 + it * 0.009, 11.0) }
        val out = (1..5).map { GeoPoint(46.9, 11.0 + it * 0.013) }
        val back = out.reversed().drop(1) + north.last()
        val on = (1..100).map { GeoPoint(46.9 + it * 0.009, 11.0) }
        val pts = north + out + back + on
        val plan = SharedPlan(listOf(pts.first(), out.last(), pts.last()), Activity.BIKE_TOURING, names = mapOf(1 to "Post"), days = 2,
            nights = mapOf(1 to NightMark(Hotel("Post"))), anchors = mapOf(1 to Anchors(listOf(q1), listOf(q2))))
        val p = PlanDays.removeHotel(plan, 1, pts, WaypointInsertion.routeIndices(plan.points, pts))
        assertEquals(north.last(), p.points[1])
        assertTrue(p.anchors.isEmpty())
    }

    /** Owner 2026-11-26 night: „Als Übernachtung“ for a held place – a lodging is a hotel at once, anything else blue. */
    @Test fun aPlaceAsOvernightEndsThatDay() {
        val hut = PlanDays.nightAtPlace(tour, 2, lodging = true, name = "Gasthof Krone")
        assertEquals(Hotel("Gasthof Krone"), hut.plan.nights[2]?.hotel)
        val cafe = PlanDays.nightAtPlace(tour, 2, lodging = false, name = "Café")
        assertEquals(NightMark.OVERNIGHT, cafe.plan.nights[2])
        // start, finish and a point with a mark stay as they are
        assertEquals(tour, PlanDays.nightAtPlace(tour, 0, lodging = true, name = "X").plan)
        assertEquals(tour, PlanDays.nightAtPlace(tour, tour.points.lastIndex, lodging = true, name = "X").plan)
        assertEquals(cafe.plan, PlanDays.nightAtPlace(cafe.plan, 2, lodging = true, name = "X").plan)
    }
}
