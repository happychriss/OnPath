package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The plan code shared by app and web planner (QR code, link): round trips, limits, damaged input. */
class PlanCodeTest {
    private val meran = GeoPoint(46.67170, 11.15000)
    private val algund = GeoPoint(46.68012, 11.12640)
    private val vellau = GeoPoint(46.68790, 11.11110)

    private fun near(a: GeoPoint, b: GeoPoint) = abs(a.lat - b.lat) < 6e-6 && abs(a.lon - b.lon) < 6e-6

    @Test fun roundTripKeepsEverything() {
        val plan = SharedPlan(
            points = listOf(meran, algund, vellau), activity = Activity.HIKE, sac = 4,
            pinned = setOf(2), names = mapOf(2 to "Gasthaus Vellau", 0 to "Meran"), title = "Waalweg ü ß ✓",
        )
        val back = PlanCode.decode(PlanCode.encode(plan))!!
        assertEquals(3, back.points.size)
        plan.points.zip(back.points).forEach { (a, b) -> assertTrue("$a vs $b", near(a, b)) }
        assertEquals(Activity.HIKE, back.activity)
        assertEquals(4, back.sac)
        assertEquals(setOf(2), back.pinned)
        assertEquals(mapOf(2 to "Gasthaus Vellau", 0 to "Meran"), back.names)
        assertEquals("Waalweg ü ß ✓", back.title)
    }

    @Test fun everyActivitySurvives() {
        for (a in Activity.entries) {
            val back = PlanCode.decode(PlanCode.encode(SharedPlan(listOf(meran, algund), a)))!!
            assertEquals(a, back.activity)
        }
    }

    @Test fun activityCodesAreFixed() {
        // stored codes must never change: BIKE_TOURING = 0 … HIKE = 5 (byte 2 of the code)
        val code = PlanCode.encode(SharedPlan(listOf(meran), Activity.HIKE))
        assertTrue(code.startsWith("AQUC")) // bytes 1, 5, 2
    }

    @Test fun roundTripPlanStaysARoundTrip() {
        val loop = listOf(meran, algund, vellau, meran)
        val back = PlanCode.decode(PlanCode.encode(SharedPlan(loop, Activity.MTB)))!!
        assertEquals(back.points.first(), back.points.last())
        assertTrue(PlanEdits.isRoundTrip(back.points))
    }

    @Test fun codeIsShortEnoughForAQrCode() {
        val pts = (0 until 20).map { GeoPoint(46.6 + it * 0.004, 11.1 + it * 0.003) }
        val code = PlanCode.encode(SharedPlan(pts, Activity.BIKE_TREKKING))
        assertTrue("${code.length} chars", code.length < 140)
    }

    @Test fun worksAcrossTheWorld() {
        val pts = listOf(GeoPoint(-33.86, 151.21), GeoPoint(64.14, -21.94), GeoPoint(0.0, 0.0), GeoPoint(-90.0, 180.0))
        val back = PlanCode.decode(PlanCode.encode(SharedPlan(pts, Activity.EBIKE)))!!
        pts.zip(back.points).forEach { (a, b) -> assertTrue("$a vs $b", near(a, b)) }
    }

    @Test fun parseFindsTheCodeInLinksAndText() {
        val plan = SharedPlan(listOf(meran, vellau), Activity.BIKE_TOURING)
        val code = PlanCode.encode(plan)
        for (text in listOf(
            "https://planer.example.org/#p=$code",
            PlanCode.link("https://planer.example.org/?x=1#old", plan),
            "https://planer.example.org/?a=1&p=$code",
            "p=$code",
            "  $code\n",
        )) assertNotNull(text, PlanCode.parse(text))
    }

    @Test fun linkKeepsThePlanAfterTheHash() {
        val link = PlanCode.link("https://planer.example.org/index.html#something", SharedPlan(listOf(meran), Activity.HIKE))
        assertTrue(link, link.startsWith("https://planer.example.org/index.html#p="))
    }

    @Test fun damagedInputIsNullNeverACrash() {
        val code = PlanCode.encode(SharedPlan(listOf(meran, algund, vellau), Activity.HIKE, names = mapOf(1 to "Algund")))
        for (cut in 1 until code.length) PlanCode.decode(code.substring(0, cut)) // must not throw
        for (text in listOf("", "   ", "hello world", "https://example.org/", "p=", "p=!!!!", "#p=A", "AAAA", "_____"))
            assertNull(text, PlanCode.parse(text))
        assertNull(PlanCode.parse("x".repeat(20_000)))
    }

    @Test fun newerVersionIsRefused() {
        val code = PlanCode.encode(SharedPlan(listOf(meran), Activity.HIKE))
        assertNull(PlanCode.decode("Ag" + code.drop(2))) // version byte 2
    }

    @Test fun unknownFieldsAreSkipped() {
        // a newer app may add fields: tag 9, length 3 after a valid plan
        val plain = SharedPlan(listOf(meran, algund), Activity.MTB, title = "T")
        val bytes = java.util.Base64.getUrlDecoder().decode(PlanCode.encode(plain)) + byteArrayOf(9, 3, 1, 2, 3)
        val code = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertEquals("T", PlanCode.decode(code)!!.title)
    }

    @Test fun matchesTheJdkBase64() {
        val plan = SharedPlan(listOf(meran, algund, vellau), Activity.HIKE, names = mapOf(1 to "Algund ö"), title = "x")
        val code = PlanCode.encode(plan)
        val bytes = java.util.Base64.getUrlDecoder().decode(code)
        assertEquals(code, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
    }

    @Test fun limitsAreKept() {
        val many = (0 until 600).map { GeoPoint(46.0 + it * 1e-3, 11.0) }
        val longName = "N".repeat(300)
        val back = PlanCode.decode(PlanCode.encode(SharedPlan(many, Activity.HIKE, names = mapOf(0 to longName, 550 to "gone"))))!!
        assertEquals(PlanCode.MAX_POINTS, back.points.size)
        assertEquals(PlanCode.MAX_NAME, back.names[0]!!.length)
        assertNull(back.names[550])
    }

    @Test fun controlCharactersAreDropped() {
        val back = PlanCode.decode(PlanCode.encode(SharedPlan(listOf(meran), Activity.HIKE, title = "a\nb\u0000c", names = mapOf(0 to "  "))))!!
        assertEquals("abc", back.title)
        assertTrue(back.names.isEmpty())
    }
}
