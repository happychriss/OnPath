package com.onpath.web

import com.onpath.app.domain.RouteShares
import com.onpath.app.domain.StepShares
import com.onpath.app.domain.WaitStepState
import com.onpath.strings.Txt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The planner's wait window (WebWait, owner 2026-11-19 „A, aber nie mehr als 3 Häkchen“): a new place's map, its routing
 * data and the route as steps of ONE bar – never back, never standing, at most 3 rows, 100 only at the end; the learned
 * shares stored and read back; the sign beside (WebSign) one percent over the whole action.
 */
class WebWaitTest {
    @Before fun en() { Txt.setLanguage("en") }

    private fun fresh() = WebWait(StepShares.DEFAULT, RouteShares.DEFAULT_CHECK, WebWait.ROUTE_PART)

    @Test fun mapThenRouteIsOneBarThatNeverGoesBackNorStands() {
        val w = fresh()
        val seen = ArrayList<Int>()
        val titles = LinkedHashSet<String>()
        var t = 0L
        // 10 s routing data download (10 MB), 3 s written, then 3 legs of 5 s each and a 3 s check – every half second
        fun tick(tk: WebWait.Tick) { val u = w.tick(tk); seen += u.percent; titles += u.title; assertTrue(u.steps.size <= 3); t += 500 }
        // a plan's wait: the route is part of it from the start (its legs not known yet)
        while (t < 10_000) tick(WebWait.Tick(t, dlDone = t * 1000, dlTotal = 10_000_000, routing = true, join = 0.0, route = WebWait.Route(0, 0, false, null)))
        val routeAt = t
        while (t < routeAt + 3_000) tick(WebWait.Tick(t, dlDone = 10_000_000, dlTotal = 10_000_000, routing = true, join = 0.5,
            route = WebWait.Route(0, 3, false, null)))
        val legsAt = t
        while (t < legsAt + 15_000) tick(WebWait.Tick(t, dlDone = 10_000_000, dlTotal = 10_000_000, routing = true,
            route = WebWait.Route(((t - legsAt) / 5000).toInt(), 3, false, null)))
        val checkAt = t
        while (t < checkAt + 3_000) tick(WebWait.Tick(t, dlDone = 10_000_000, dlTotal = 10_000_000, routing = true,
            route = WebWait.Route(3, 3, true, null)))
        assertTrue("never back: $seen", seen.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("never 100 before the end: $seen", seen.all { it < 100 })
        // never standing: no number longer than 10 s (the app's PercentStallTest), a tick each half second
        assertTrue("never standing: $seen", seen.windowed(21).all { it.first() < it.last() })
        assertEquals(listOf("Map for your area", "Calculating the route"), titles.toList())
        assertEquals(100, w.full(t)!!.percent)
    }

    @Test fun aLegThatTakesLongCreepsOnAndSaysSo() {
        val w = fresh()
        val seen = ArrayList<Int>()
        var late = false
        var t = 0L
        while (t < 600_000) { // ten minutes in one leg (the router says nothing within a leg)
            val u = w.tick(WebWait.Tick(t, route = WebWait.Route(0, 1, false, null)))
            seen += u.percent; late = late || u.late; t += 500
        }
        assertTrue("never back: ${seen.distinct()}", seen.zipWithNext().all { (a, b) -> b >= a })
        // an overrun borrows a whole percent every fifth of the wait so far (the app's OneBar): still moving after minutes
        assertTrue("still moving after minutes: ${seen.takeLast(600).distinct()}", seen.takeLast(600).distinct().size > 1)
        assertTrue(seen.last() < 100)
        assertTrue("says it takes longer: ${seen.distinct()}", late)
    }

    /** The most seconds a line can mean ("about 5 seconds left" = 5, "about 3 minutes left" = 209 – minutes are rounded); null = no number. */
    private fun claimedS(line: String?): Int? {
        if (line == null) return null
        Regex("about (\\d+) seconds left").find(line)?.let { return it.groupValues[1].toInt() }
        if (line == "about 1 minute left") return 89
        Regex("about (\\d+) minutes left").find(line)?.let { return it.groupValues[1].toInt() * 60 + 29 }
        return null
    }

    /**
     * Runs a wait every half second: [map] ms of map download (0 = none), then [legMs] for each of [legs] legs (the router
     * says nothing within a leg), then [checkMs] of check; returns (time, line, seconds really left) at each tick.
     */
    private fun run(w: WebWait, map: Long, legs: Int, legMs: Long, checkMs: Long): List<Triple<Long, String?, Long>> {
        val out = ArrayList<Triple<Long, String?, Long>>()
        val end = map + legs * legMs + checkMs
        var t = 0L
        while (t < end) {
            val inMap = t < map
            val r = t - map
            val done = if (inMap) 0 else minOf(legs, (r / legMs).toInt())
            val dl = if (map == 0L) 0L else 10_000_000L
            val tk = WebWait.Tick(t, dlDone = if (inMap) dl * t / map else dl, dlTotal = dl, routing = map > 0,
                route = WebWait.Route(done, if (inMap) 0 else legs, done >= legs, null))
            out += Triple(t, w.tick(tk).line, (end - t) / 1000)
            t += 500
        }
        return out
    }

    private fun assertHonest(what: String, seen: List<Triple<Long, String?, Long>>) {
        // a number is never less than the wait really needs (rounding: the text is in fives / whole minutes)
        val lies = seen.filter { (_, line, left) -> claimedS(line)?.let { it + 5 < left } == true }
        assertTrue("$what: the time left claims less than is left: " +
            lies.take(6).joinToString { (t, l, left) -> "${t / 1000} s: \"$l\" (really $left s)" }, lies.isEmpty())
    }

    @Test fun aSingleLongLegAfterAQuickMapClaimsNoShortTime() {
        // the owner's München → Hilden (2026-11-19, „bereit in 5 s“ – then it hangs): the routing data in 6 s (the map
        // part's steps, 70 % of the bar), then ONE leg of 600 km the router works on for two minutes without a word
        val seen = run(fresh(), map = 6_000, legs = 1, legMs = 120_000, checkMs = 4_000)
        assertHonest("map 6 s, one leg 120 s", seen)
    }

    @Test fun aSingleLongLegAloneClaimsNoShortTime() {
        // the same leg with the routing data already here: the bar creeps on time – that is no measure of the leg
        assertHonest("one leg 120 s", run(fresh(), map = 0, legs = 1, legMs = 120_000, checkMs = 4_000))
        // a route part learned from short routes (MIN: the route only 10 % of the bar) changes nothing
        assertHonest("one leg 120 s, learned part", run(WebWait(StepShares.DEFAULT, RouteShares.DEFAULT_CHECK, WebWait.MIN_PART),
            map = 6_000, legs = 1, legMs = 120_000, checkMs = 4_000))
    }

    @Test fun theRoutePartSaysHowLongItRunsLikeTheApp() {
        // the app's RouteWaitUi line (owner 2026-11-27 „realistischer … transparenter“): how long it runs, no time left
        WebWait.estimate(-1.0)
        val seen = run(fresh(), map = 0, legs = 3, legMs = 20_000, checkMs = 3_000)
        assertHonest("three legs of 20 s", seen)
        assertEquals("running for 0:42", seen.first { it.first == 42_000L }.second)
        assertTrue("no estimate without one: ${seen.map { it.second }.distinct()}", seen.none { it.second?.contains("about") == true })
    }

    @Test fun aLongRunsEstimateShowsInTheLine() {
        // the run head's estimate (the app's RouteEstimate from LongRule.runS): above 5 s it reads „· about 1–2 min“
        WebWait.estimate(90.0)
        try {
            val u = fresh().let { w -> w.tick(WebWait.Tick(0, route = WebWait.Route(0, 1, false, null))); w.tick(WebWait.Tick(42_000, route = WebWait.Route(0, 1, false, null))) }
            assertTrue("estimate in the line: ${u.line}", u.line!!.startsWith("running for 0:42 · ") && u.line!!.contains("about"))
            // a short run (4 s estimate): no estimate shown
            WebWait.estimate(4.0)
            val v = fresh().let { w -> w.tick(WebWait.Tick(0, route = WebWait.Route(0, 1, false, null))); w.tick(WebWait.Tick(3_000, route = WebWait.Route(0, 1, false, null))) }
            assertEquals("running for 0:03", v.line)
        } finally { WebWait.estimate(-1.0) }
    }

    @Test fun aLegLongerThanTheOnesBeforeDropsTheNumber() {
        // a short leg (5 s), then a long one (90 s): the pace of the short one would say "5 s" – past it the window says
        // it takes longer instead of a number it cannot keep
        val w = fresh()
        val seen = ArrayList<Triple<Long, String?, Long>>()
        var t = 0L
        while (t < 99_000) {
            val done = when { t < 5_000 -> 0; t < 95_000 -> 1; else -> 2 }
            seen += Triple(t, w.tick(WebWait.Tick(t, route = WebWait.Route(done, 2, done >= 2, null))).line, (99_000 - t) / 1000)
            t += 500
        }
        val late = seen.filter { it.first in 15_000..90_000 }
        assertTrue("no number in the long leg past the short one's time: ${late.map { it.second }.distinct()}",
            late.all { claimedS(it.second) == null })
    }

    @Test fun anAreaWithoutTheServersEstimateShowsNoTime() {
        // the flash before the worker's first report (2026-11-19): an area asked for, the server's answer not in yet –
        // the prepare step's 3 minutes are only the assumed default (PrepareProgress.DEFAULT_S), no measurement
        val w = fresh()
        var t = 0L
        val before = ArrayList<String?>()
        while (t < 10_000) {
            before += w.tick(WebWait.Tick(t, areas = listOf(WebWait.Area("10-1-1", if (t < 5_000) "requested" else "queued", 0.0, null)),
                dlTotal = 1_000_000, map = true)).line
            t += 500
        }
        assertTrue("no time before the server's estimate: ${before.distinct()}", before.all { claimedS(it) == null })
        // the server says 60 s: now the step is measured – its time shows
        val after = ArrayList<String?>()
        while (t < 14_000) {
            after += w.tick(WebWait.Tick(t, areas = listOf(WebWait.Area("10-1-1", "building", 0.1, 60)), dlTotal = 1_000_000, map = true)).line
            t += 500
        }
        assertTrue("the server's time once it came: ${after.distinct()}", after.any { (claimedS(it) ?: 0) >= 50 })
    }

    @Test fun twoAreasOneWithoutAnEstimateShowNoTime() {
        // one area's estimate is not the other's: the step's time left is unknown while one has none
        val w = fresh()
        var t = 0L
        val lines = ArrayList<String?>()
        while (t < 6_000) {
            lines += w.tick(WebWait.Tick(t, areas = listOf(WebWait.Area("a", "building", 0.5, 20), WebWait.Area("b", "requested", 0.0, null)),
                dlTotal = 1_000_000, map = true)).line
            t += 500
        }
        assertTrue("no time while an area has no estimate: ${lines.distinct()}", lines.all { claimedS(it) == null })
    }

    @Test fun mapTilesAloneShowNoTime() {
        // the page's first map (CI 2026-11-20: „about 5 seconds left“ before the map server's answer): tiles are counted
        // as they load at an assumed size, their number grows as the map asks for more – no measure of what is left
        val w = fresh()
        var t = 0L
        val lines = ArrayList<String?>()
        while (t < 20_000) { lines += w.tick(WebWait.Tick(t, dlDone = t * 500, dlTotal = 10_000_000, map = true)).line; t += 500 }
        assertTrue("no number for map tiles: ${lines.distinct()}", lines.all { claimedS(it) == null })
    }

    @Test fun routingDataAloneShowsItsTime() {
        // the routing data by its real bytes at the measured rate (no route after it in this wait): the number stays
        val w = fresh()
        var t = 0L
        val lines = ArrayList<String?>()
        while (t < 20_000) { lines += w.tick(WebWait.Tick(t, dlDone = t * 500, dlTotal = 10_000_000, routing = true)).line; t += 500 }
        assertTrue("a number for the routing data: ${lines.distinct()}", lines.any { claimedS(it) != null })
    }

    @Test fun atMostThreeRowsMovingAlongTheLegs() {
        val w = fresh()
        val u = w.tick(WebWait.Tick(0, route = WebWait.Route(2, 5, false, listOf("A → B", "B → C", "C → D", "D → E", "E → F"))))
        assertEquals(listOf("B → C", "C → D", "D → E"), u.steps.map { it.label })
        assertEquals(listOf(WaitStepState.DONE, WaitStepState.NOW, WaitStepState.LATER), u.steps.map { it.state })
        val c = w.tick(WebWait.Tick(500, route = WebWait.Route(5, 5, true, null)))
        assertEquals("checking", c.steps.last().label)
        assertEquals(3, c.steps.size)
    }

    @Test fun theMapStepsUseTheAppsWords() {
        val w = fresh()
        val u = w.tick(WebWait.Tick(0, areas = listOf(WebWait.Area("10-1-1", "building", 0.2, 120)), dlTotal = 1_000_000, map = true))
        assertEquals(listOf("being prepared", "load"), u.steps.map { it.label })
        val v = w.tick(WebWait.Tick(1000, areas = listOf(WebWait.Area("10-1-1", "ready", 1.0, null)), dlDone = 0, dlTotal = 1_000_000,
            map = true, routing = true))
        assertEquals(listOf("prepared", "loading map + routing data"), v.steps.map { it.label })
        val off = w.tick(WebWait.Tick(1500, dlTotal = 1_000_000, map = true, paused = true))
        assertTrue(off.paused); assertTrue(off.line!!.startsWith("No internet"))
    }

    @Test fun learnedSharesAreStoredAndReadBack() {
        val w = fresh()
        var t = 0L
        while (t < 6_000) { w.tick(WebWait.Tick(t, areas = listOf(WebWait.Area("a", "building", t / 6000.0, 6)), dlTotal = 1000, map = true)); t += 500 }
        while (t < 8_000) { w.tick(WebWait.Tick(t, areas = listOf(WebWait.Area("a", "ready", 1.0, null)), dlDone = (t - 6000) / 2, dlTotal = 1000, map = true)); t += 500 }
        while (t < 14_000) { w.tick(WebWait.Tick(t, areas = listOf(WebWait.Area("a", "ready", 1.0, null)), dlDone = 1000, dlTotal = 1000, map = true,
            route = WebWait.Route(if (t < 12_000) 0 else 1, 1, t >= 12_000, null))); t += 500 }
        val l = w.end(t, finished = true)
        assertTrue("prepare took longer than its start share: ${l.shares}", l.shares.prepare > StepShares.DEFAULT.prepare)
        val back = StepShares.decode(WebWait.encodeShares(l.shares))
        assertEquals(l.shares.prepare, back.prepare, 1e-4); assertEquals(l.shares.download, back.download, 1e-4)
        assertEquals(round4(l.check), RouteShares.decode(WebWait.encodeCheck(l.check)), 1e-9)
        assertEquals(round4(l.routePart), WebWait.decodePart(WebWait.encodePart(l.routePart)), 1e-9)
        // cancelled: nothing learned
        val c = fresh().apply { tick(WebWait.Tick(0, dlTotal = 10, map = true)) }.end(10_000, finished = false)
        assertEquals(StepShares.DEFAULT, c.shares)
    }

    private fun round4(v: Double) = Math.round(v * 10_000) / 10_000.0

    @Test fun storedFormsAreRobust() {
        assertEquals("0.3000", WebWait.fixed4(0.3)); assertEquals("0.0500", WebWait.fixed4(0.05)); assertEquals("1.0000", WebWait.fixed4(1.0))
        assertEquals(StepShares.DEFAULT, StepShares.decode(WebWait.encodeShares(StepShares.DEFAULT)))
        assertEquals(WebWait.ROUTE_PART, WebWait.decodePart("garbage"), 0.0)
        assertEquals(WebWait.MAX_PART, WebWait.decodePart("p1:0.99"), 0.0)
        // the JSON exports with damaged stored values: start values, no throw
        WebWait.start("x", "y", "z")
        val j = WebWait.tick(0.0, "bad line\n10-1-1\tbuilding\t0.5\t-1", 0.0, 100.0, true, false, -1.0, 0, -1, false, "", 0, false)
        assertTrue(j, j.startsWith("{\"title\":\"Map for your area\",\"route\":false,\"percent\":"))
        val e = WebWait.end(500.0, true)
        assertTrue(e, e.contains("\"shares\":\"v1:") && e.contains("\"check\":\"r1:") && e.contains("\"part\":\"p1:"))
        assertEquals("{\"full\":null}", WebWait.end(600.0, true))
    }

    @Test fun legNamesAsTheApp() {
        assertEquals(listOf("Algund → Meran", "Meran → Algund"), WebWait.legLabels(mapOf(0 to "Algund", 1 to "Meran"), 3, 2))
        assertNull(WebWait.legLabels(mapOf(0 to "Algund", 1 to "Meran", 2 to "Lana"), 3, 1)) // a leg from the cache: counted
        assertNull(WebWait.legLabels(mapOf(0 to "Algund", 2 to "Lana"), 3, 2)) // a point without a name: counted
        assertEquals(listOf("Algund → Algund"), WebWait.legLabels(mapOf(0 to "Algund"), 2, 1)) // a round trip's closing point
    }

    @Test fun theSignIsOnePercentForTheWholeAction() {
        var last = -1
        val seen = ArrayList<Int>()
        for (s in 0..60) {
            val p = WebSign.tick(s * 1000.0, "a\tbuilding\t${s / 60.0 * 0.9}\t60\nb\tqueued\t0\t${120 - s}")
            assertTrue("never back: $seen $p", p >= last); last = p; seen += p
        }
        assertTrue(seen.last() in 1..99)
        assertFalse(WebSign.tick(61_000.0, "a\tready\t1\t-1\nb\tready\t1\t-1") >= 0)
    }
}
