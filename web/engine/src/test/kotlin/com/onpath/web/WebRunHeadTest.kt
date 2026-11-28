package com.onpath.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The planner's run head is the app's (PlanRecalcState.begin + RouteRun in WebPlanChain) with the browser's long-leg rule
 * (RouteCost, owner 2026-11-27/28, like the app): a leg whose search even at its low end takes longer than 30 s is asked
 * about first („Abbrechen“ / „Berechnen“), a leg beyond its memory only offers „OK“, a multi-day tour is never asked; a yes
 * survives a fresh worker (historyExport → historyImport).
 */
class WebRunHeadTest {
    /** 598 km straight: beyond a 4 GB browser (340 km), asked on an 8 GB one (700 km). */
    private val long = "46.67,11.16;52.05,11.16"
    /** 250 km straight: about 47 s at the browser's start factor – asked, never beyond. */
    private val mid = "46.67,11.16;48.92,11.16"
    private val day = "46.67,11.16;46.69,11.15"
    private fun what(j: String) = Regex("\"what\":\"(\\w+)\"").find(j)!!.groupValues[1]
    private fun rounds(j: String) = Regex("\"rounds\":(\\d+)").find(j)!!.groupValues[1].toInt()

    @Before fun startValues() { Engine.costSet(Engine.BROWSER_FACTOR, Engine.BROWSER_MEMORY_MB) }

    @Test fun aShortPlanRoutesAtOnceALongLegIsAskedWithItsEstimate() {
        Engine.historyImport("")
        assertEquals("ROUTE", what(Engine.runHead(day, "HIKE", 2, true)))
        val ask = Engine.runHead(mid, "HIKE", 2, true, "", "0\tHilden\n1\tFrankfurt")
        assertEquals("ASK", what(ask))
        assertTrue(ask, ask.contains("\"title\":\"Long route: about"))
        assertTrue(ask, ask.contains("Hilden → Frankfurt, 250 km"))
        // like the app (owner 2026-11-28): „Abbrechen“ · „Berechnen“, no stop sentence, no „Zwischenziel setzen“
        assertTrue(ask, ask.contains("\"plan\":\"Calculate\"") && ask.contains("\"cancel\":\"Cancel\"") && ask.contains("\"again\":\"Calculate\""))
        assertFalse(ask, ask.contains("Weiter warten") || ask.contains("Keep waiting") || ask.contains("stop") || ask.contains("Wait\""))
    }

    /**
     * A long tour saved with support points (owner 2026-11-27, „Lange Browser-Touren auf dem Handy“: the browser shows a
     * reloaded long tour at once): the run head weighs the legs between them, so the reload is routed without a question.
     */
    @Test fun aReloadedTourWithSupportPointsIsRoutedAtOnce() {
        Engine.historyImport("")
        val a = com.onpath.app.routing.GeoPoint(46.67, 11.16); val b = com.onpath.app.routing.GeoPoint(48.92, 11.16)
        assertEquals("without support points: asked", "ASK", what(Engine.runHead(mid, "HIKE", 2, true)))
        Engine.historyImport("")
        val supports = (1..4).map { com.onpath.app.routing.GeoPoint(46.67 + it * 0.45, 11.16) }
        val set = com.onpath.app.routing.AnchorSet(mapOf(0 to com.onpath.app.routing.Anchors(after = supports.take(2)),
            1 to com.onpath.app.routing.Anchors(before = supports.drop(2))), com.onpath.app.routing.AnchorProfile.of(com.onpath.app.routing.Activity.HIKE, 2))
        Engine.planArrive(mid, com.onpath.app.data.AnchorsJson.writeSet(listOf(a, b), set)!!)
        val head = Engine.runHead(mid, "HIKE", 2, true)
        assertEquals("with support points every ~50 km: routed at once ($head)", "ROUTE", what(head))
        assertFalse("no long estimate in the wait line for short pieces ($head)", head.contains("estimateS\":4") || head.contains("estimateS\":5"))
    }

    /** Beyond the browser's memory (owner 2026-11-28): only „OK“, which takes the point back – no „Zwischenziel“, no wait. */
    @Test fun aLegBeyondTheBrowsersMemoryOnlyOffersOk() {
        Engine.historyImport("")
        val beyond = Engine.runHead(long, "HIKE", 2, true)
        assertEquals("ASK", what(beyond))
        assertTrue(beyond, beyond.contains("\"beyond\":true") && !beyond.contains("\"plan\"") && beyond.contains("\"cancel\":\"OK\""))
        assertFalse(beyond, beyond.contains("stop"))
        Engine.costSet(Engine.BROWSER_FACTOR, 600) // an 8 GB browser: 700 km
        val asked = Engine.runHead(long, "HIKE", 2, true)
        assertTrue(asked, asked.contains("\"beyond\":false") && asked.contains("\"plan\":\"Calculate\""))
    }

    @Test fun aFasterBrowserIsNotAsked() {
        Engine.historyImport("")
        Engine.costSet(0.5, Engine.BROWSER_MEMORY_MB) // bounded to RouteCost.MIN_FACTOR 2: 250 km ≈ 32 s, its low end ≈ 22 s
        // the app's rule (owner 2026-11-28, RouteCost lowEnd): asked only when even the range's low end is above 30 s
        assertEquals("ROUTE", what(Engine.runHead(mid, "HIKE", 2, true)))
        Engine.historyImport("")
        assertEquals("200 km straight at factor 2 ≈ 21 s: at once", "ROUTE", what(Engine.runHead("46.67,11.16;48.47,11.16", "HIKE", 2, true)))
    }

    /** The run's search estimate goes with the head (the app's RouteEstimate): the wait window's „· etwa 1–2 min“. */
    @Test fun aRoutedHeadCarriesTheRunsEstimate() {
        Engine.historyImport("")
        Engine.costSet(0.5, Engine.BROWSER_MEMORY_MB) // a fast browser (factor 2): 200 km is routed at once
        val est = { j: String -> Regex("\"estimateS\":([\\d.]+)").find(j)?.groupValues?.get(1)?.toDouble() }
        val longish = Engine.runHead("46.67,11.16;48.47,11.16", "HIKE", 2, true) // 200 km: routed at once, about 20 s
        assertEquals("ROUTE", what(longish))
        assertTrue(longish, (est(longish) ?: 0.0) > 5.0)
        Engine.historyImport("")
        val short = Engine.runHead(day, "HIKE", 2, true) // a few km: under the 10 km the estimate counts – none
        assertEquals("ROUTE", what(short))
        assertTrue(short, (est(short) ?: 0.0) < 5.0)
    }

    @Test fun waitIsKeptAcrossAFreshWorkerAMultiDayTourIsNeverAsked() {
        Engine.historyImport("")
        assertEquals("ASK", what(Engine.runHead(mid, "HIKE", 2, true)))
        assertEquals("a multi-day tour: never asked", "ROUTE", what(Engine.runHead(mid, "HIKE", 2, false)))
        Engine.runHead(mid, "HIKE", 2, true)
        Engine.runLongAnswer(true, mid)
        assertEquals("ROUTE", what(Engine.runHead(mid, "HIKE", 2, true)))
        Engine.historyImport(Engine.historyExport())
        assertEquals("„Warten“ after a fresh worker", "ROUTE", what(Engine.runHead(mid, "HIKE", 2, true)))
    }

    @Test fun weiterWartenRoutesWithOnePeriodMoreAlsoAfterAFreshWorker() {
        Engine.historyImport("")
        val gen = Regex("\"gen\":(\\d+)").find(Engine.runHead(day, "HIKE", 2, true))!!.groupValues[1].toInt()
        Engine.runEnd(gen, "slow", 1)
        Engine.historyImport(Engine.historyExport())
        Engine.runSlowAnswer(true)
        assertEquals(2, rounds(Engine.runHead(day, "HIKE", 2, true)))
    }

    @Test fun theEndOfARunOfAnEarlierChainNeverEndsTheNewerRun() {
        Engine.historyImport("")
        val old = Regex("\"gen\":(\\d+)").find(Engine.runHead(day, "HIKE", 2, true))!!.groupValues[1].toInt()
        Engine.historyImport("#") // an opened plan: a fresh chain whose run counts from 0 again
        val now = Regex("\"gen\":(\\d+)").find(Engine.runHead(day, "HIKE", 2, true))!!.groupValues[1].toInt()
        assertTrue("$old vs $now", old != now)
        Engine.runEnd(old, "slow", 1) // the late answer of the run before: ignored
        Engine.runSlowAnswer(true)
        assertEquals("the newer run was not touched: no „Weiter warten“ period added", 1, rounds(Engine.runHead(day, "HIKE", 2, true)))
    }

    @Test fun aYesOfThisPageHoldsOnAFreshChain() {
        Engine.historyImport("#")
        assertEquals("ASK", what(Engine.runHead(mid, "HIKE", 2, true)))
        Engine.historyImport("#")
        Engine.runRoutedBefore(mid) // the page's own memory (longroute.ts allowed)
        assertEquals("ROUTE", what(Engine.runHead(mid, "HIKE", 2, true)))
    }

    /** The app's (owner 2026-11-27): after „Abbrechen“ the not-routed line's „Berechnen“ asks again – never a blind yes. */
    @Test fun addAStopLeavesItUnroutedAndCalculateAsksAgain() {
        Engine.historyImport("")
        assertEquals("ASK", what(Engine.runHead(mid, "HIKE", 2, true)))
        Engine.runLongAnswer(false, mid)
        assertTrue(Engine.planCancel("other", mid, "", 0, "LONG").contains("KEEP_POINTS"))
        Engine.historyImport(Engine.historyExport()) // why it is not routed travels too
        Engine.runPlanAnyway(mid)
        assertEquals("ASK", what(Engine.runHead(mid, "HIKE", 2, true)))
    }

    /** No „Trotzdem planen“ in the planner (owner 2026-11-28): the day rule's question says „Berechnen“ too. */
    @Test fun noPlanAnywayAnywhere() {
        val day = WebPlanRules.longAsk(30 * 3600.0, com.onpath.app.routing.Activity.HIKE)
        assertFalse(day, day.contains("Plan anyway") || day.contains("Trotzdem"))
        assertTrue(day, day.contains("\"plan\":\"Calculate\""))
    }
}
