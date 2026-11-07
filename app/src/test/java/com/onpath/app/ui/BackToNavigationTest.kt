package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Core feature (owner 2026-11-06, C – replaces the round "←" of 2026-11-05, R1): during a tour the way from the plan back
 * to Navigate is a green full-width button "▲ Weiter geht's" (plain: no red dot, no numbers – owner 2026-11-06) at the bottom, where "▶ Los geht's" is
 * without a tour – in every step of the plan sheet (its foot), under a shown tour and under a place card; Android's Back
 * does the same. The tour goes on, the plan's points stay, a paused recording goes on.
 */
class BackToNavigationTest {
    /** The band is one row; at a large font the mode label gets its own line (no overlap). */
    @Test fun bandHeights() {
        assertEquals(76f, PlanTopBand.bottom(fontScale = 1f).value)
        assertEquals(116f, PlanTopBand.bottom(fontScale = 2f).value)
        assertEquals(28f, PlanTopBand.LABEL_START.value)
    }
}
