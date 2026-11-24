package com.onpath.app.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The map column gives way to a short screen (owner 2026-11-24): full, then 48 dp slots, then the tour bubble beside the map bubble. */
class ControlsRoomTest {
    @Test fun fullColumnHeights() {
        // share + gear (2 slots) and the map bubble (4): 56 dp slots, 1 dp dividers, 12 dp gap
        assertEquals(113.dp + 12.dp + 227.dp, ControlsFit.height(ControlsTier.FULL, 2, 4))
        assertEquals(97.dp + 6.dp + 195.dp, ControlsFit.height(ControlsTier.COMPACT, 2, 4))
        assertEquals(195.dp, ControlsFit.height(ControlsTier.SIDE, 2, 4))
    }

    @Test fun theFirstTierThatFitsWins() {
        assertEquals(ControlsTier.FULL, ControlsFit.tier(352.dp, 2, 4))
        assertEquals(ControlsTier.COMPACT, ControlsFit.tier(351.dp, 2, 4))
        assertEquals(ControlsTier.COMPACT, ControlsFit.tier(298.dp, 2, 4))
        assertEquals(ControlsTier.SIDE, ControlsFit.tier(297.dp, 2, 4))
        assertEquals(ControlsTier.SIDE, ControlsFit.tier(195.dp, 2, 4))
    }

    @Test fun neverSmallerThanTheSideTierAndNoMeasureMeansFull() {
        assertEquals(ControlsTier.SIDE, ControlsFit.tier(10.dp, 2, 4))
        assertEquals(ControlsTier.FULL, ControlsFit.tier(null, 2, 4))
    }

    @Test fun slotsStayAtMaterialsMinimumTouchTarget() {
        ControlsTier.values().forEach { assert(it.slot >= 48.dp) }
    }

    @Test fun pillKeepsClearOfTheWiderSideTier() {
        assertEquals(68.dp, ControlsFit.pillClear(ControlsTier.FULL, 2))
        assertEquals(68.dp, ControlsFit.pillClear(ControlsTier.COMPACT, 2))
        assertEquals(48.dp + 12.dp + 97.dp + 8.dp, ControlsFit.pillClear(ControlsTier.SIDE, 2))
    }
}
