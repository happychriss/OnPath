package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.onpath.app.domain.HoldHint

/**
 * The „Halten, um hier zu planen“ pill (Chriss 2026-11-28, picture halten-lernen-abc (a)): shown at the tapped spot when
 * [HoldHint.pillOnTap] says so, gone by itself after [HoldHint.SHOW_MS] or at the next finger-down. `MapHost` owns one.
 */
class HoldPillState {
    /** Where the finger tapped, in the map's pixels; null = no pill. */
    var at by mutableStateOf<Offset?>(null)
        private set
    /** Bumped at every show, so a second tap restarts the time. */
    var shown by mutableStateOf(0)
        private set

    fun show(o: Offset) { at = o; shown++ }
    fun hide() { at = null }
}

/** The pill just above the tapped spot, centred on it and kept inside the map with an 8 dp edge. */
@Composable
fun HoldPill(state: HoldPillState) {
    val at = state.at ?: return
    LaunchedEffect(state.shown) { kotlinx.coroutines.delay(HoldHint.SHOW_MS); state.hide() }
    val gap = with(LocalDensity.current) { 18.dp.roundToPx() }
    val edge = with(LocalDensity.current) { 8.dp.roundToPx() }
    Layout({ QuietPill(str(Res.string.plan_hold_pill), Icons.Outlined.TouchApp) }, Modifier) { m, c ->
        val p = m.first().measure(Constraints(maxWidth = (c.maxWidth - 2 * edge).coerceAtLeast(0)))
        layout(c.maxWidth, c.maxHeight) {
            val x = (at.x.toInt() - p.width / 2).coerceIn(edge, (c.maxWidth - edge - p.width).coerceAtLeast(edge))
            val y = (at.y.toInt() - gap - p.height).coerceAtLeast(edge)
            p.place(x, y)
        }
    }
}
