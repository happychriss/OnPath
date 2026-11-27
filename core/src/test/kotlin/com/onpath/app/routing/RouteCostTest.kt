package com.onpath.app.routing

import com.onpath.app.domain.plan.RouteRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's search-time estimate and the app's question before a long leg (owner 2026-11-27). */
class RouteCostTest {
    private val hilden = GeoPoint(51.169, 6.93)
    private val muenchen = GeoPoint(48.137, 11.575)
    private val duesseldorf = GeoPoint(51.2277, 6.7735)
    private val bonn = GeoPoint(50.7374, 7.0982)

    private fun rule(factor: Double = RouteCost.DEFAULT_FACTOR, routed: (GeoPoint, GeoPoint, Boolean) -> Boolean = { _, _, _ -> false }) =
        PhoneLongRule({ factor }, routed)

    // ---------------- field cases first ----------------
    /** The web planner's browser holds far longer legs (owner 2026-11-27): its memory makes Hilden → München a question, not „too long“. */
    @Test fun aBrowsersMemoryAsksInsteadOfBeyond() {
        val browser = rule(3.0).also { it.memoryCapMb = { 600 } } // RouteCost.capKm(600) ≈ 703 km
        val ask = browser.ask(listOf(hilden, muenchen), Activity.HIKE)
        assertNotNull(ask); ask!!
        assertFalse("waiting offered", ask.beyond)
        assertTrue("its estimate: ${ask.estimateS} s", ask.estimateS > RouteCost.ASK_ABOVE_S)
        assertTrue("the phone keeps its 300 km", rule().ask(listOf(hilden, muenchen), Activity.HIKE)!!.beyond)
    }


    /** Field 2026-11-27: Hilden → München on foot was a 24-hour question with „Trotzdem planen“; 475 km is beyond the phone. */
    @Test fun hildenToMuenchenOnFootIsBeyondThePhoneWithoutWaiting() {
        val ask = rule().ask(listOf(hilden, muenchen), Activity.HIKE)
        assertNotNull(ask); ask!!
        assertTrue("no waiting offered", ask.beyond)
        assertEquals(0, ask.leg)
        assertTrue(ask.km in 460.0..490.0)
        // owner 2026-11-27: too far – a closer destination, the browser only as a sentence; OK takes the far point back
        assertEquals("Too far for the phone", RouteCost.askTitle(ask))
        assertEquals("Hilden → München, ${Math.round(ask.km)} km in one go. Choose a closer destination – plan longer tours in the browser on a computer.",
            RouteCost.askText(ask, "Hilden", "München"))
        // the run asks with it, and no "yes" changes it
        val run = RouteRun(longRule = rule(), askSlow = false)
        val step = run.on(RouteRun.Event.Recalc(listOf(hilden, muenchen), Activity.HIKE, planMode = true, newPlan = true))
        assertEquals(Double.POSITIVE_INFINITY, step.state.longAsk)
        val again = step.state.on(RouteRun.Event.PlanAnyway(listOf(hilden, muenchen))).state
            .on(RouteRun.Event.Recalc(listOf(hilden, muenchen), Activity.HIKE, planMode = true, newPlan = false)).state
        assertTrue(again.longAskLeg!!.beyond)
    }

    @Test fun duesseldorfToBonnOnFootIsNotAskedWithTheDefaultFactor() {
        assertNull(rule(8.0).ask(listOf(duesseldorf, bonn), Activity.HIKE))
        val step = RouteRun(longRule = rule(8.0), askSlow = false)
            .on(RouteRun.Event.Recalc(listOf(duesseldorf, bonn), Activity.HIKE, planMode = true, newPlan = true))
        assertTrue(step.state.routing)
    }

    // ---------------- the model ----------------

    /** Owner 2026-11-27: „20–60 Sekunden“ read as asking below 30 s – the app asks only when the low end is above 30 s. */
    @Test fun theAppAsksOnlyWhenTheRangesLowEndIsAboveTheLimit() {
        val ms = 35_000L // low end 24.5 s
        assertTrue(RouteCost.decide(listOf(ms), listOf(50.0)) is RouteCost.Decision.Ask) // the planner: asked
        assertEquals(RouteCost.Decision.Now, RouteCost.decide(listOf(ms), listOf(50.0), lowEnd = true)) // the app: routed
        val slow = 50_000L // low end 35 s
        val ask = RouteCost.decide(listOf(slow), listOf(50.0), lowEnd = true) as RouteCost.Decision.Ask
        assertTrue("the shown range starts above 30 s: ${ask.loMs}", ask.loMs > RouteCost.ASK_ABOVE_MS)
    }

    @Test fun desktopTimeGrowsWithDistance() {
        var last = -1.0
        for (km in 0..600 step 5) { val t = RouteCost.desktopMs(km.toDouble()); assertTrue(t > last || km == 0); last = t }
    }

    @Test fun desktopTimeIsContinuousAtTheKnee() {
        val below = RouteCost.desktopMs(RouteCost.KNEE_KM - 1e-6)
        val above = RouteCost.desktopMs(RouteCost.KNEE_KM + 1e-6)
        assertEquals(below, above, 0.01)
        assertEquals(3.5 * Math.pow(160.0, 1.5), RouteCost.desktopMs(160.0), 1e-6)
    }

    @Test fun modelMatchesTheMeasurements() {
        assertEquals(1500.0, RouteCost.desktopMs(60.0), 300.0)
        assertEquals(3100.0, RouteCost.desktopMs(100.0), 600.0)
        assertEquals(7400.0, RouteCost.desktopMs(160.0), 800.0)
        assertEquals(56_000.0, RouteCost.desktopMs(487.0), 6000.0)
    }

    @Test fun estimateIsDesktopTimesFactor() {
        assertEquals((RouteCost.desktopMs(100.0) * 8).toLong(), RouteCost.estimateMs(100.0, Activity.HIKE, 8.0))
        assertEquals(RouteCost.estimateMs(100.0, Activity.HIKE, 30.0), RouteCost.estimateMs(100.0, Activity.HIKE, 99.0))
    }

    // ---------------- learning ----------------

    @Test fun factorLearnsByEwma() {
        val km = 100.0
        val observed = 4.0
        val ms = (RouteCost.desktopMs(km) * observed).toLong()
        assertEquals(8.0 * 0.7 + observed * 0.3, RouteCost.factorAfter(8.0, km, ms), 0.01)
    }

    @Test fun shortLegsAndOddTimesTeachNothing() {
        assertEquals(8.0, RouteCost.factorAfter(8.0, 9.9, 100_000), 0.0)
        assertEquals(8.0, RouteCost.factorAfter(8.0, 50.0, 0), 0.0)
        assertEquals(8.0, RouteCost.factorAfter(8.0, 50.0, -5), 0.0)
    }

    @Test fun factorStaysInItsBounds() {
        var f = 8.0
        repeat(50) { f = RouteCost.factorAfter(f, 100.0, 10_000_000) }
        assertEquals(RouteCost.MAX_FACTOR, f, 0.0)
        repeat(50) { f = RouteCost.factorAfter(f, 100.0, 1) }
        assertEquals(RouteCost.MIN_FACTOR, f, 0.0)
        assertEquals(RouteCost.DEFAULT_FACTOR, RouteCost.bounded(Double.NaN), 0.0)
    }

    @Test fun searchCostLearnsOnlyFromLegsAndKeepsTodaysLimitAsFloor() {
        var stored = 8.0
        val cost = SearchCost({ stored }, { stored = it })
        cost.searched(listOf(duesseldorf, bonn, hilden), 999_999)
        assertEquals(8.0, stored, 0.0)
        cost.searched(listOf(duesseldorf, bonn), (RouteCost.desktopMs(RouteTimeLimit.straightKm(listOf(duesseldorf, bonn))) * 2).toLong())
        assertEquals(8.0 * 0.7 + 2 * 0.3, stored, 0.05)
        assertEquals(45_000L, cost.limitMs(listOf(hilden, GeoPoint(51.17, 6.94)), 45_000L))
        val far = listOf(hilden, GeoPoint(49.5, 9.0)) // ~240 km
        assertTrue(cost.limitMs(far, 45_000L) >= (2 * RouteCost.phoneS(RouteTimeLimit.straightKm(far), stored) * 1000).toLong())
    }

    // ---------------- the decision ----------------

    @Test fun decideNowAskAndSplit() {
        assertEquals(RouteCost.Decision.Now, RouteCost.decide(listOf(30_000L, 10L), listOf(50.0, 1.0)))
        assertEquals(RouteCost.Decision.Ask(21_700, 46_500, 1), RouteCost.decide(listOf(10L, 31_000L), listOf(1.0, 100.0)))
        assertEquals(RouteCost.Decision.SplitOnly(0), RouteCost.decide(listOf(1L, 999_999L), listOf(301.0, 200.0)))
        assertEquals(RouteCost.Decision.Now, RouteCost.decide(emptyList(), emptyList()))
    }

    @Test fun memoryCapacityScalesTheKmCap() {
        assertEquals(300.0, RouteCost.capKm(256), 1e-9)
        assertEquals(1200.0, RouteCost.capKm(1024), 1e-9)
        assertEquals(RouteCost.Decision.Now, RouteCost.decide(listOf(1L), listOf(487.0), memoryCapMb = 1024))
    }

    @Test fun askAt30SecondsAndBeyond300Km() {
        // a leg just under / over 30 s on the phone
        val f = 8.0
        fun kmFor(s: Double): Double { var km = 1.0; while (RouteCost.phoneS(km, f) < s) km += 0.1; return km }
        val k30 = kmFor(30.0)
        val a = GeoPoint(50.0, 7.0)
        fun pointAt(km: Double) = GeoPoint(50.0 + km / 111.195, 7.0)
        assertNull(rule(f).ask(listOf(a, pointAt(k30 - 1)), Activity.HIKE))
        val ask = rule(f).ask(listOf(a, pointAt(k30 + 1)), Activity.HIKE)
        assertNotNull(ask); assertFalse(ask!!.beyond)
        assertTrue(rule(f).ask(listOf(a, pointAt(301.0)), Activity.BIKE_TOURING)!!.beyond)
        assertFalse(rule(f).ask(listOf(a, pointAt(299.0)), Activity.BIKE_TOURING)!!.beyond)
    }

    @Test fun aRoutedLegIsNotAskedAbout() {
        val pts = listOf(hilden, muenchen, GeoPoint(48.2, 11.6))
        assertNull(rule { a, b, _ -> a == hilden && b == muenchen }.ask(pts, Activity.HIKE))
    }

    @Test fun theLongestLegIsNamed() {
        val pts = listOf(duesseldorf, bonn, GeoPoint(49.5, 9.0))
        val ask = rule().ask(pts, Activity.HIKE)!!
        assertEquals(1, ask.leg)
        assertTrue(RouteCost.askTitle(ask).startsWith("Long route: about "))
        assertEquals("Point 2 → 3, ${Math.round(ask.km)} km in one go. With a stop on the way it is faster.", RouteCost.askText(ask, "Bonn", null))
        assertEquals("Bonn → Würzburg, ${Math.round(ask.km)} km in one go. With a stop on the way it is faster.", RouteCost.askText(ask, "Bonn", "Würzburg"))
    }

    @Test fun runEstimateSumsTheLegsStillToRoute() {
        val pts = listOf(duesseldorf, bonn, hilden)
        val all = rule().runS(pts, Activity.HIKE)!!
        val part = rule { a, _, _ -> a == duesseldorf }.runS(pts, Activity.HIKE)!!
        assertTrue(all > part && part > 0)
    }

    // ---------------- words ----------------

    @Test fun rangeWords() {
        assertEquals("about 40–90 seconds", RouteCost.words(60.0))
        assertEquals("about 3–6 minutes", RouteCost.words(240.0))
        assertEquals("about 2–4 hours", RouteCost.words(9000.0))
        assertEquals("about 5–10 s", RouteCost.words(6.0, short = true))
        assertEquals("about 1–2 min", RouteCost.words(70.0, short = true))
    }
}
