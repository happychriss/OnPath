package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.plan.LivePlanRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Owner's Pixel 8, 2026-11-28 11:40 (a tour recording, My routes open): the Zuletzt tile of the plan on the map flickered
 * – its line, then the kind's sign. My routes builds the live row's Tour again on every recomposition with createdMs =
 * „now“ (Screens.kt), and that time was part of the line cache's key: a new key each second while recording → no line →
 * the sign until it was read again. The live row's key must stay the same while its plan stays the same.
 */
class RouteLineKeyTest {
    private fun live(nowMs: Long, distanceM: Double = 331.0) =
        Tour(LivePlanRows.LIVE_ID, TourKind.PLANNED, "In Hilden (19)", nowMs, null, 0, distanceM, 0.0, 0.0, 300.0, "done", emptyList())

    @Test fun theLiveRowsKeyStaysTheSameOverRecompositions() {
        assertEquals(RouteLineKey.of(live(1_000L)), RouteLineKey.of(live(2_000L)))
        assertEquals(RouteLineKey.of(live(1_000L)), RouteLineKey.of(live(61_000L)))
    }

    @Test fun anotherLivePlanIsAnotherKey() {
        assertNotEquals(RouteLineKey.of(live(1_000L)), RouteLineKey.of(live(2_000L, distanceM = 420.0)))
    }
}
