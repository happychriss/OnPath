package com.onpath.web

import com.onpath.app.routing.PlanCode
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
}
