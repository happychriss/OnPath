package com.onpath.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The arrival as planned for the live viewer (owner 2026-11-26: „Etwas später als geplant“). */
class PlannedArrivalTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theFirstEstimateOfALineIsThePlan() {
        val p = PlannedArrival.next(null, 7, 1, 1_000L)
        assertEquals(1_000L, p!!.arrivalMs)
        // later estimates (slower walking) do not move the plan
        assertEquals(1_000L, PlannedArrival.next(p, 7, 1, 1_500L)!!.arrivalMs)
    }

    @Test fun aNewLineOrTourPlansAnew() {
        val p = PlannedArrival.next(null, 7, 1, 1_000L)
        assertEquals(2_000L, PlannedArrival.next(p, 7, 2, 2_000L)!!.arrivalMs)
        assertEquals(3_000L, PlannedArrival.next(p, 8, 1, 3_000L)!!.arrivalMs)
    }

    @Test fun noRouteNoPlan() {
        assertNull(PlannedArrival.next(null, 7, null, 1_000L))
        assertNull(PlannedArrival.next(null, 7, 1, null))
    }

    /** The followed line briefly missing (a re-route in the making): the tour keeps its last plan, no new one is taken. */
    @Test fun aBrieflyMissingLineKeepsTheTour_sPlan() {
        val p = PlannedArrival.next(null, 7, 1, 1_000L)!!
        assertEquals(p, PlannedArrival.next(p, 7, null, null))
        assertEquals(p, PlannedArrival.next(p, 7, null, 9_000L))
        // the same line back: still the first plan, not the late estimate of the moment
        assertEquals(1_000L, PlannedArrival.next(PlannedArrival.next(p, 7, null, null), 7, 1, 1_800L)!!.arrivalMs)
        // another tour without a line has no plan of its own
        assertNull(PlannedArrival.next(p, 8, null, 1_000L))
    }

    @Test fun theLineInTheFileReadsBack() {
        val p = PlannedArrival(7, -123, 1_800_000_000_000L)
        assertEquals(p, PlannedArrival.decode(p.encode()))
        assertEquals(p, PlannedArrival.decode(p.encode() + "\n"))
        for (bad in listOf(null, "", "onpath-live 1 abc", "planned1 7 x 1", "planned1 7 1", "planned1 7 1 2 3")) assertNull(bad, PlannedArrival.decode(bad))
    }

    /** An app restart during a tour (a new process, the same file): the tour keeps its plan, the late estimate is no new plan. */
    @Test fun thePlanSurvivesARestart() {
        val dir = tmp.newFolder("live")
        val first = PlannedArrivals(LiveFiles(dir, LiveFiles.PLANNED))
        assertEquals(1_000L, first.next(7, 1, 1_000L)!!.arrivalMs)
        assertEquals(1_000L, first.next(7, 1, 1_400L)!!.arrivalMs)

        val again = PlannedArrivals(LiveFiles(dir, LiveFiles.PLANNED))
        // right after the restart the navigator has no line yet, then the same line with a later estimate
        assertEquals(1_000L, again.next(7, null, null)!!.arrivalMs)
        assertEquals(1_000L, again.next(7, 1, 1_900L)!!.arrivalMs)
        // a re-plan or another tour plans anew – also after the next restart
        assertEquals(2_500L, again.next(7, 2, 2_500L)!!.arrivalMs)
        assertEquals(2_500L, PlannedArrivals(LiveFiles(dir, LiveFiles.PLANNED)).next(7, 2, 3_000L)!!.arrivalMs)
        assertEquals(5_000L, PlannedArrivals(LiveFiles(dir, LiveFiles.PLANNED)).next(8, 1, 5_000L)!!.arrivalMs)
        // the share's own file is not touched
        assertNull(LiveFiles(dir).read())
    }

    /** No file (a test, or one that cannot be read): memory only, the rule the same. */
    @Test fun withoutAStoreTheRuleIsTheSame() {
        val m = PlannedArrivals(null)
        assertEquals(1_000L, m.next(7, 1, 1_000L)!!.arrivalMs)
        assertEquals(1_000L, m.next(7, null, null)!!.arrivalMs)
        assertEquals(1_000L, m.next(7, 1, 2_000L)!!.arrivalMs)
    }

    @Test fun theHeadCarriesIt() {
        val h = LiveHead(LiveState.RECORDING, arrivalMs = 5_000L, plannedArrivalMs = 4_000L)
        assertEquals(4_000L, LiveItem.readHead(LiveItem.head(h))!!.plannedArrivalMs)
        assertNull(LiveItem.readHead(LiveItem.head(h.copy(plannedArrivalMs = null)))!!.plannedArrivalMs)
    }
}
