package com.onpath.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner's Pixel 2026-11-28 15:09: a blue triangle stood in the middle of the open search („München · 474 km“) – the
 * „where I face“ arrow (FacingArrow, #1A73E8) at the position dot's screen point, drawn AFTER the Plan / Navigate layers,
 * so over the full-screen search, the place card, the round-trip wish sheet and the wait window inside them. It belongs to
 * the map: drawn right after it and before ModeSwitch, every layer covers it; on the bare map it shows as before.
 */
class FacingArrowOrderTest {
    @Test fun theArrowIsDrawnRightAfterTheMapBeforeEveryLayer() {
        val src = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        val body = src.substringAfter("fun MapScreen(")
        val map = body.indexOf("MapHost(")
        val arrow = body.indexOf("FacingArrow(ctl.facing)")
        val layers = body.indexOf("ModeSwitch(vm.tourSession.mode")
        assertTrue("map $map, arrow $arrow, layers $layers", map in 0 until arrow && arrow < layers)
        // the search is one of those layers (PlanMode → SearchHost), so it lies over the arrow
        assertTrue(src.substringAfter("private fun PlanMode(").contains("com.onpath.app.ui.search.SearchHost(vm)"))
    }
}
