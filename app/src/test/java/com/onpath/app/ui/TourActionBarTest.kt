package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The slider's bar (owner 2026-11-02, onpath.private#17 = C compact): three tiles in fixed colours – green Pause /
 * Resume, blue Change route, red End – white words on each (≥ 4.5:1), the colour rule as tokens, no hand colours.
 */
class TourActionBarTest {
    private val bar = File("src/main/java/com/onpath/app/ui/TourActionBar.kt").readText()

    private fun contrastWithWhite(c: Color): Double = 1.05 / (c.luminance() + 0.05)

    @Test fun greenPauseBlueChangeRedEnd() {
        // plain signs (owner 2026-11-02): ⏸ / ▶, the pencil, ■
        val pause = bar.indexOf("Icons.Filled.Pause, str(if (paused)").also { assertTrue("paused → ▶", bar.contains("if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause")) }
        val change = bar.indexOf("Icons.Filled.Edit, str(Res.string.plan_slide_change_route), OnPathColors.ActionEdit")
        val end = bar.indexOf("Icons.Filled.Stop, str(Res.string.nav_end_short), OnPathColors.ActionStop")
        assertTrue("$pause $change $end", pause in 0 until change && change < end)
        assertTrue(bar.substring(pause, change).contains("OnPathColors.ActionGo"))
        assertTrue("no hand colours in the bar", !Regex("Color\\(0x").containsMatchIn(bar))
    }

    @Test fun whiteWordsReadOnEveryTile() {
        for ((name, c) in listOf("go" to OnPathColors.ActionGo, "edit" to OnPathColors.ActionEdit, "stop" to OnPathColors.ActionStop,
            "bar" to OnPathColors.Green))
            assertTrue("$name ${contrastWithWhite(c)}", contrastWithWhite(c) >= 4.5)
    }

    /** Owner 2026-11-03: the tiles were "echt riesig" – the bar now about the slider's own 64 dp, still ≥ 48 dp to touch. */
    @Test fun compactAboutTheSlidersHeight() {
        val barDp = TourActions.TILE_HEIGHT.value + 2 * TourActions.BAR_PAD.value
        assertTrue("$barDp dp vs the 64 dp slider", barDp in 52f..66f)
        assertTrue(TourActions.TILE_HEIGHT.value >= 48f)
        assertEquals(30_000L, TourActions.OPEN_MS)
    }

    /** Owner 2026-11-02: no ✕ – a tap on the map or Android's back closes the open bar (not while paused). */
    @Test fun aTapOnTheMapOrBackClosesTheBar() {
        // Navigate's bar has no ✕ close button (the plan's red "Neu planen" tile uses the ✕ sign – that is not a close)
        assertTrue("no ✕ in the bar", !bar.substringAfter("fun TourActionBar(").substringBefore("\n}\n").contains("Icons.Filled.Close"))
        val nav = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
        assertTrue(nav.contains("val closableBar = open && !ui.paused && ui.recordedM != null"))
        assertTrue(nav.contains("BackHandler(enabled = closableBar) { open = false }"))
        assertTrue(nav.contains("detectTapGestures { open = false }"))
    }

    /** Owner 2026-11-03: the three pills of the plan during a tour are always equally high. */
    @Test fun pillsAreEquallyHigh() {
        val pills = bar.substringAfter("fun ActionPills(").substringBefore("\n}\n")
        assertTrue(pills, pills.contains(".height(IntrinsicSize.Min)") && pills.contains("Modifier.weight(1f).fillMaxHeight()"))
    }
}
