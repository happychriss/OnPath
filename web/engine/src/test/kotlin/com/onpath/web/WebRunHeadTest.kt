package com.onpath.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner's run head is the app's (PlanRecalcState.begin + RouteRun in WebPlanChain): a very long plan is asked about first,
 * a multi-day tour never; „Plan anyway“ and an open „Weiter warten?“ survive a fresh worker (historyExport → historyImport).
 */
class WebRunHeadTest {
    private val long = "46.67,11.16;52.05,11.16"
    private val day = "46.67,11.16;46.69,11.15"
    private fun what(j: String) = Regex("\"what\":\"(\\w+)\"").find(j)!!.groupValues[1]
    private fun rounds(j: String) = Regex("\"rounds\":(\\d+)").find(j)!!.groupValues[1].toInt()

    @Test fun aVeryLongPlanIsAskedFirstAndAYesIsKeptAcrossAFreshWorker() {
        Engine.historyImport("")
        val ask = Engine.runHead(long, "HIKE", 2, true)
        assertEquals("ASK", what(ask))
        assertTrue(ask, ask.contains("\"title\":\"This route is very long\""))
        assertEquals("a multi-day tour: never asked", "ROUTE", what(Engine.runHead(long, "HIKE", 2, false)))
        Engine.runHead(long, "HIKE", 2, true)
        Engine.runLongAnswer(true, long)
        assertEquals("ROUTE", what(Engine.runHead(long, "HIKE", 2, true)))
        Engine.historyImport(Engine.historyExport())
        assertEquals("„Plan anyway“ after a fresh worker", "ROUTE", what(Engine.runHead(long, "HIKE", 2, true)))
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
        assertEquals("ASK", what(Engine.runHead(long, "HIKE", 2, true)))
        Engine.historyImport("#")
        Engine.runRoutedBefore(long) // the page's own memory (longroute.ts allowed)
        assertEquals("ROUTE", what(Engine.runHead(long, "HIKE", 2, true)))
    }

    @Test fun cancelOfTheQuestionLeavesItLongAndPlanAnywayRoutesUnasked() {
        Engine.historyImport("")
        assertEquals("ASK", what(Engine.runHead(long, "HIKE", 2, true)))
        Engine.runLongAnswer(false, long)
        assertTrue(Engine.planCancel("other", long, "", 0, "LONG").contains("KEEP_POINTS"))
        Engine.historyImport(Engine.historyExport()) // why it is not routed travels too
        Engine.runPlanAnyway(long)
        assertEquals("ROUTE", what(Engine.runHead(long, "HIKE", 2, true)))
    }
}
