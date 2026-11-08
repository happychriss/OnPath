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

    /**
     * The undo pill's slot above the column (owner 2026-11-08 evening, on the Pixel: the pill sat "mitten im Bild"): it
     * stands right above the compass, right-aligned with the column. The slot is kept free whether the pill shows or
     * not, so nothing moves when it comes or goes.
     */
    const val PILL_SLOT_DP = 48

    /** Left of the pill: the activity button (12 dp margin + 48 dp + air) – the pill never reaches into it. */
    const val ACTIVITY_ZONE_DP = 72

    /** The fixed top: [SCREEN_FRACTION] of [screenPx], never above [minTopPx] (the band's bottom + air). */
    fun fixedTop(screenPx: Int, minTopPx: Int): Int = maxOf((screenPx * SCREEN_FRACTION).toInt(), minTopPx)

    /** The column's fixed top with the pill's slot above it: never closer to the band ([limitPx]) than slot + gap. */
    fun fixedTopWithPill(screenPx: Int, limitPx: Int, slotPx: Int, gapPx: Int): Int =
        fixedTop(screenPx, limitPx + slotPx + gapPx)

    /**
     * The column's top (px): [fixedTopPx] – or higher only when the sheet's top edge [sheetTopPx] would come closer than
     * [gapPx] to the column's bottom; null = no room between [limitPx] (the band) and the sheet: the column is not placed.
     */
    fun place(fixedTopPx: Int, heightPx: Int, sheetTopPx: Int, limitPx: Int, gapPx: Int): Int? {
        val y = minOf(fixedTopPx, sheetTopPx - gapPx - heightPx)
        return if (y < limitPx) null else y
    }

    /**
     * The undo pill (owner 2026-11-08 evening): right above the column ([columnTopPx]), right-aligned with it. The column
     * stands at its fixed top on the S10 and the Pixel at every sheet step but the points step – so the pill does too;
     * only on a small phone, where a high sheet pushes the column up, it goes up with it (it is never covered: the
     * column steps back first). Returns the pill's left and top (px).
     */
    fun pill(columnTopPx: Int, columnRightPx: Int, pillWidthPx: Int, pillHeightPx: Int, gapPx: Int): Pair<Int, Int> =
        (columnRightPx - pillWidthPx) to (columnTopPx - gapPx - pillHeightPx)

    /** The widest the pill may be: from the screen's right margin to the activity button. */
    fun pillMaxWidth(screenWidthPx: Int, endPx: Int, activityZonePx: Int): Int = (screenWidthPx - endPx - activityZonePx).coerceAtLeast(0)
}

/**
 * Plan's map controls on their own layer ([ControlsPlacement]). Drawn under the message boxes and the sheet (PlanMode
 * order: a box and the sheet always lie over it). [sheetHeightPx] is read while placing only – a moving sheet moves
 * the column without recomposing anything. [limit] = the top band's bottom (status bar included).
 */
@Composable
fun PlanControlsLayer(sheetHeightPx: () -> Int, limit: Dp, modifier: Modifier = Modifier, controls: @Composable () -> Unit) {
    // the first child is the column, a second one the undo pill ([UndoPill], only while there is something to undo):
    // the pill's slot above the column is kept in every case, so the column never moves when the pill comes or goes
    Layout(content = controls, modifier = modifier.fillMaxSize()) { measurables, c ->
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val gap = ControlsPlacement.GAP_DP.dp.roundToPx()
        val end = ControlsPlacement.END_DP.dp.roundToPx()
        val column = measurables.firstOrNull()?.measure(loose)
        val pillMax = ControlsPlacement.pillMaxWidth(c.maxWidth, end, ControlsPlacement.ACTIVITY_ZONE_DP.dp.roundToPx())
        val pills = measurables.drop(1).map { it.measure(loose.copy(maxWidth = pillMax)) }
        layout(c.maxWidth, c.maxHeight) {
            if (column == null) return@layout
            val limitPx = limit.roundToPx() + gap
            val slot = ControlsPlacement.PILL_SLOT_DP.dp.roundToPx()
            val fixed = ControlsPlacement.fixedTopWithPill(c.maxHeight, limitPx, slot, gap)
            val sheetTop = c.maxHeight - sheetHeightPx()
            // a sheet that reaches the column pushes it up – never into the pill's slot under the band
            val top = ControlsPlacement.place(fixed, column.height, sheetTop, limitPx + slot + gap, gap) ?: return@layout
            val right = c.maxWidth - end
            column.place(right - column.width, top)
            pills.forEach { p ->
                val (x, y) = ControlsPlacement.pill(top, right, p.width, p.height, gap)
                p.place(x, maxOf(y, limitPx))
            }
        }
    }
}
