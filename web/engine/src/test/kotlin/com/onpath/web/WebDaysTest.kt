package com.onpath.web

import com.onpath.app.routing.Activity
import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.DayLine
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.PlanDays
import com.onpath.app.routing.SharedPlan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner's multi-day tour calls (WebDays.kt, multi-day tours stages 2 + 3): the page's tour text round trip, the
 * plan code carries it, the rules that need no routed line, a pasted hotel link.
 */
class WebDaysTest {
    private val pts = "46.600000,11.200000;46.650000,11.200000;46.700000,11.200000;46.750000,11.200000"
    private val tour = "d\t4\nl\t360\t1200\nn\t1\tO\nn\t2\tH\tGasthof Krone\tVia Roma 3\\t1\thttps://www.booking.com/hotel/it/krone.html\nt\tTwo\\nlines"

    @Test fun theTourTextRoundTripsThroughThePlanCode() {
        val code = WebDays.planCode(pts, "BIKE_TOURING", 2, "", "2\tGasthof Krone", "München – Venedig", tour)
        val back = PlanCode.decode(code)!!
        assertEquals(4, back.days)
        assertEquals(360, back.limits.maxTimeMin); assertEquals(1200, back.limits.maxAscentM)
        assertEquals(false, back.nights[1]!!.isHotel)
        assertEquals("Gasthof Krone", back.nights[2]!!.hotel!!.name)
        assertTrue(back.nights[2]!!.hotel!!.address!!.startsWith("Via Roma 3"))
        assertTrue(back.nights[2]!!.hotel!!.link!!.contains("booking.com"))
        assertEquals("Two\nlines", back.description)
        val json = Engine.planParse("p=$code")
        assertTrue(json, json.contains("\"tour\":{\"days\":4,\"limits\":{\"timeMin\":360,\"ascentM\":1200}"))
        assertTrue(json, json.contains("\"2\":{\"hotel\":{\"name\":\"Gasthof Krone\""))
        // a normal plan: no tour
        assertTrue(Engine.planParse("p=" + WebDays.planCode(pts, "HIKE", 2, "", "", "", "")).endsWith("\"tour\":null}"))
    }

    @Test fun brokenTourLinesAreLeftOut() {
        val t = WebDays.parseTour("d\tx\nn\tq\tO\nzz\nl\t-5\t300")
        assertNull(t.days); assertTrue(t.nights.isEmpty()); assertNull(t.limits.maxTimeMin); assertEquals(300, t.limits.maxAscentM)
    }

    @Test fun rulesWithoutALine() {
        val start = WebDays.rule("start", pts, "BIKE_TOURING", 2, "", "", "", 3, 0.0, 0.0, "")
        assertTrue(start, start.contains("\"tour\":{\"days\":3"))
        val t3 = "d\t3\nn\t1\tO\nn\t2\tO"
        // − with no grey proposal left: not possible (R5)
        assertEquals("null", WebDays.rule("setDays", pts, "BIKE_TOURING", 2, "", "", t3, 2, 0.0, 0.0, ""))
        assertTrue(WebDays.rule("setDays", pts, "BIKE_TOURING", 2, "", "", t3, 5, 0.0, 0.0, "").contains("\"days\":5"))
        val removed = WebDays.rule("removeNight", pts, "BIKE_TOURING", 2, "", "", t3, 1, 0.0, 0.0, "")
        assertTrue(removed, removed.contains("\"nights\":{\"2\":{\"hotel\":null}}"))
        // R6: a point put in before the marks – they follow their points
        val after = "46.600000,11.200000;46.620000,11.210000;46.650000,11.200000;46.700000,11.200000;46.750000,11.200000"
        val moved = WebDays.rule("afterEdit", pts, "BIKE_TOURING", 2, "", "", t3, 0, 0.0, 0.0, after)
        assertTrue(moved, moved.contains("\"nights\":{\"2\":{\"hotel\":null},\"3\":{\"hotel\":null}}"))
        // nothing routed: the rules that need the line say "null"
        assertEquals("null", WebDays.rule("addNight", "46.1,11.1;46.2,11.1", "HIKE", 2, "", "", t3, 0, 46.15, 11.1, ""))
        // a plan point's „Übernachtung hier“: never the start, the finish or a point with a mark; else needs the line
        for (n in listOf(0, 3, 1, 2)) assertEquals("null", WebDays.rule("addNightAt", pts, "BIKE_TOURING", 2, "", "", t3, n, 0.0, 0.0, ""))
        assertEquals("{\"routed\":false}", WebDays.view("46.1,11.1;46.2,11.1", "HIKE", 2, "", "", t3))
    }

    @Test fun aPastedHotelLink() {
        val g = WebDays.hotelText("Gasthof Krone\nhttps://www.google.com/maps/place/Gasthof+Krone/@47.0281,11.4962,17z")
        assertTrue(g, g.contains("\"lat\":47.0281") && g.contains("\"lon\":11.4962") && g.contains("\"name\":\"Gasthof Krone\""))
        val b = WebDays.hotelText("https://www.booking.com/hotel/it/gasthof-krone-matrei.de.html?aid=1")
        assertTrue(b, b.contains("\"lat\":null") && b.contains("\"search\":\"Gasthof Krone Matrei\"") && b.contains("booking.com"))
        val a = WebDays.hotelText("Via Roma 3, 39012 Meran")
        assertTrue(a, a.contains("\"search\":\"Via Roma 3, 39012 Meran\""))
        assertEquals("null", WebDays.hotelText("   "))
    }

    /**
     * The owner's Rhine tour (2026-11-20: a grey moon between Heidelberg and the next blue one, „eigentlich sollte der
     * jetzt ja weg sein“): 4 days and two nights set leave one grey proposal – the shared rule (R1: N stays, a grey one
     * goes) spreads it into the longest stretch, here between the two blue moons. Only "−" (R5) takes it away, so the
     * planner says on every day end which day of how many ends there.
     */
    @Test fun aGreyBetweenTwoSetNightsIsTheDayCountsLeftoverNight() {
        // a straight 190 km northwards, 3 min per km: start (km 0), Heidelberg (30), Mainz (120), Koblenz (190)
        val pts = (0..190).map { GeoPoint(49.2 + it * 0.009, 8.6) }
        val line = DayLine(pts, DoubleArray(191) { it * 1000.0 }, DoubleArray(191), DoubleArray(191), DoubleArray(191) { it * 180.0 })
        val at = intArrayOf(0, 30, 120, 190)
        val plan = PlanDays.start(SharedPlan(at.map { pts[it] }, Activity.BIKE_TOURING), 4)
        fun greys(p: SharedPlan) = PlanDays.of(line, at, p.days!!, p.limits, p.nights).proposals
        assertEquals(3, greys(plan).size)
        val heidelberg = PlanDays.addNight(plan, pts, at, 30).plan
        val mainz = PlanDays.addNight(heidelberg, pts, at, 120)
        assertFalse(mainz.dayAdded)
        assertEquals(4, mainz.plan.days)
        val left = greys(mainz.plan)
        assertEquals("N − 1 − 2 nights = one grey left", 1, left.size)
        assertTrue("in the longest stretch, Heidelberg – Mainz: ${left[0].lineIndex}", left[0].lineIndex in 31..119)
        val ends = PlanDays.of(line, at, 4, mainz.plan.limits, mainz.plan.nights).days.mapNotNull { it.end?.kind }
        assertEquals(listOf(DayEndKind.OVERNIGHT, DayEndKind.PROPOSAL, DayEndKind.OVERNIGHT), ends)
        // "−" takes exactly that grey one away; then no "−" is left
        assertTrue(PlanDays.canRemoveDay(mainz.plan))
        val three = PlanDays.setDays(mainz.plan, 3)!!
        assertTrue(greys(three).isEmpty())
        assertFalse(PlanDays.canRemoveDay(three))
        // the same tour started with 3 days: the second night takes the last grey one, none stays
        val started3 = PlanDays.start(SharedPlan(at.map { pts[it] }, Activity.BIKE_TOURING), 3)
        val both = PlanDays.addNight(PlanDays.addNight(started3, pts, at, 30).plan, pts, at, 120).plan
        assertEquals(3, both.days)
        assertTrue(greys(both).isEmpty())
    }
}
