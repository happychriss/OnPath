package com.onpath.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where the map footer (scale, attribution, map controls) goes above the plan sheet: on the sheet's top edge, but never
 * above [limitPx] – the bottom of the search pill + ☰ row (else, with the points open, the compass covers ☰). Pushed
 * further, it stays there and fades out under the sheet over [fadePx].
 */
object FooterPlacement {
    data class Spot(val y: Int, val alpha: Float)

    fun place(sheetTopPx: Int, footerHeightPx: Int, limitPx: Int, fadePx: Int): Spot {
        val y = sheetTopPx - footerHeightPx
        if (y >= limitPx) return Spot(y, 1f)
        val over = (limitPx - y).toFloat()
        return Spot(limitPx, (1f - over / fadePx.coerceAtLeast(1)).coerceIn(0f, 1f))
    }
}

/**
 * Plan mode bottom: [sheet] at the bottom, [footer] riding on its top edge (FooterPlacement: never above [topLimit]).
 * Both are measured once; when the sheet moves only their places (and the footer's fade) change – no recomposition per
 * frame. The sheet is drawn over the footer; a faded-out footer is not placed (no taps).
 */
@Composable
fun SheetWithFooter(
    topLimit: Dp,
    footer: @Composable () -> Unit,
    sheet: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    fade: Dp = 40.dp,
) {
    Layout(contents = listOf(footer, sheet), modifier = modifier.fillMaxSize()) { (f, s), c ->
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val sheetP = s.map { it.measure(loose) }
        val footerP = f.map { it.measure(loose) }
        val sheetH = sheetP.maxOfOrNull { it.height } ?: 0
        val footerH = footerP.sumOf { it.height }
        layout(c.maxWidth, c.maxHeight) {
            val sheetTop = c.maxHeight - sheetH
            val spot = FooterPlacement.place(sheetTop, footerH, topLimit.roundToPx(), fade.roundToPx())
            if (spot.alpha > 0.01f) {
                var y = spot.y
                footerP.forEach { p ->
                    p.placeWithLayer(0, y) { alpha = spot.alpha }
                    y += p.height
                }
            }
            sheetP.forEach { it.place(0, c.maxHeight - it.height) }
        }
    }
}

/** Bottom of the Plan top bar (search pill + ☰: 8 + 56 + 8 dp) below the status bar, plus a small gap. */
internal val TOP_BAR_BOTTOM = 76.dp
