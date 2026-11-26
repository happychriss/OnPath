package com.onpath.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The arrival as planned for the live viewer (owner 2026-11-26: „Etwas später als geplant“). */
class PlannedArrivalTest {
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

    @Test fun theHeadCarriesIt() {
        val h = LiveHead(LiveState.RECORDING, arrivalMs = 5_000L, plannedArrivalMs = 4_000L)
        assertEquals(4_000L, LiveItem.readHead(LiveItem.head(h))!!.plannedArrivalMs)
        assertNull(LiveItem.readHead(LiveItem.head(h.copy(plannedArrivalMs = null)))!!.plannedArrivalMs)
    }
}
