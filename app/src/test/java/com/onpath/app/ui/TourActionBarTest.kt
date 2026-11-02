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
        val pause = bar.indexOf("TrackIconKind.PAUSE, str(if (paused)").also { assertTrue("paused → ▶", bar.contains("if (paused) TrackIconKind.RESUME else TrackIconKind.PAUSE")) }
        val change = bar.indexOf("TrackIconKind.CHANGE, str(Res.string.plan_slide_change_route), OnPathColors.ActionEdit")
        val end = bar.indexOf("TrackIconKind.END, str(Res.string.nav_end_short), OnPathColors.ActionStop")
        assertTrue("$pause $change $end", pause in 0 until change && change < end)
        assertTrue(bar.substring(pause, change).contains("OnPathColors.ActionGo"))
        assertTrue("no hand colours in the bar", !Regex("Color\\(0x").containsMatchIn(bar))
    }

    @Test fun whiteWordsReadOnEveryTile() {
        for ((name, c) in listOf("go" to OnPathColors.ActionGo, "edit" to OnPathColors.ActionEdit, "stop" to OnPathColors.ActionStop,
            "bar" to OnPathColors.Green))
            assertTrue("$name ${contrastWithWhite(c)}", contrastWithWhite(c) >= 4.5)
    }

    @Test fun compactAboutOneAndAHalfSliders() {
        val barDp = TourActions.TILE_HEIGHT.value + 2 * TourActions.BAR_PAD.value
        assertTrue("$barDp dp vs the 64 dp slider", barDp in 88f..104f)
        assertEquals(30_000L, TourActions.OPEN_MS)
    }
}
