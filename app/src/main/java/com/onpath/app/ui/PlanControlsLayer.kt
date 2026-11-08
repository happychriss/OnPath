package com.onpath.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where Plan's map controls stand (owner 2026-11-06, onpath.private#43): no longer on the plan sheet's top edge (they
 * moved with every sheet step and jumped when Undo appeared) but at ONE fixed place on the right, anchored at the top a
 * little above the middle – the column only grows downwards ([MapControls] Plan order: compass · locate first, the
 * conditional buttons below), so no button ever changes its height. Only a sheet pulled up so far that it would reach
 * the column pushes it up; when even that leaves no room below the top band, the column steps back (not placed).
 * Pure – unit-tested (`PlanControlsPlacementTest`).
 */
object ControlsPlacement {
    /**
     * The column's top as a share of the screen height. The owner's preview had ~30 %; 26 % keeps all six buttons (with
     * Undo AND Redo) clear of the half-open sheet on the Pixel (sheet top ≈ 61 %), so a half sheet never pushes it.
     */
    const val SCREEN_FRACTION = 0.26f

    /** Air between the column and the sheet / the top band. */
    const val GAP_DP = 12

    /** Right margin, as on the old footer (MapFooter: 16 dp). */
    const val END_DP = 16

    /** The fixed top: [SCREEN_FRACTION] of [screenPx], never above [minTopPx] (the band's bottom + air). */
    fun fixedTop(screenPx: Int, minTopPx: Int): Int = maxOf((screenPx * SCREEN_FRACTION).toInt(), minTopPx)

    /**
     * The column's top (px): [fixedTopPx] – or higher only when the sheet's top edge [sheetTopPx] would come closer than
     * [gapPx] to the column's bottom; null = no room between [limitPx] (the band) and the sheet: the column is not placed.
     */
    fun place(fixedTopPx: Int, heightPx: Int, sheetTopPx: Int, limitPx: Int, gapPx: Int): Int? {
        val y = minOf(fixedTopPx, sheetTopPx - gapPx - heightPx)
        return if (y < limitPx) null else y
    }

    /**
     * The undo pill (owner 2026-11-08, [UndoPill]): it never moves the column – the column is placed by its own height.
     * Under the column, right-aligned, when it fits above the sheet ([sheetTopPx] − [gapPx]); else beside the column,
     * level with its last button, when it fits on the screen ([startPx] = the left margin); else not placed (null).
     * Returns the pill's left and top (px).
     */
    fun pill(columnTopPx: Int, columnHeightPx: Int, columnLeftPx: Int, pillWidthPx: Int, pillHeightPx: Int,
             sheetTopPx: Int, screenWidthPx: Int, gapPx: Int, endPx: Int, startPx: Int): Pair<Int, Int>? {
        val below = columnTopPx + columnHeightPx + gapPx
        if (below + pillHeightPx <= sheetTopPx - gapPx) return (screenWidthPx - endPx - pillWidthPx) to below
        val beside = columnLeftPx - gapPx - pillWidthPx
        if (beside >= startPx) return beside to (columnTopPx + columnHeightPx - pillHeightPx)
        return null
    }
}

/**
 * Plan's map controls on their own layer ([ControlsPlacement]). Drawn under the message boxes and the sheet (PlanMode
 * order: a box and the sheet always lie over it). [sheetHeightPx] is read while placing only – a moving sheet moves
 * the column without recomposing anything. [limit] = the top band's bottom (status bar included).
 */
@Composable
fun PlanControlsLayer(sheetHeightPx: () -> Int, limit: Dp, modifier: Modifier = Modifier, controls: @Composable () -> Unit) {
    // the first child is the column, a second one the undo pill ([UndoPill], only while there is something to undo)
    Layout(content = controls, modifier = modifier.fillMaxSize()) { measurables, c ->
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val column = placeables.firstOrNull()
        layout(c.maxWidth, c.maxHeight) {
            if (column == null) return@layout
            val gap = ControlsPlacement.GAP_DP.dp.roundToPx()
            val end = ControlsPlacement.END_DP.dp.roundToPx()
            val limitPx = limit.roundToPx() + gap
            val sheetTop = c.maxHeight - sheetHeightPx()
            val top = ControlsPlacement.place(ControlsPlacement.fixedTop(c.maxHeight, limitPx), column.height,
                sheetTop, limitPx, gap) ?: return@layout
            val left = c.maxWidth - column.width - end
            column.place(left, top)
            placeables.drop(1).forEach { p ->
                ControlsPlacement.pill(top, column.height, left, p.width, p.height, sheetTop, c.maxWidth, gap, end, end)
                    ?.let { (x, y) -> p.place(x, y) }
            }
        }
    }
}
