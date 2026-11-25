package com.onpath.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How Navigate's map controls fit the room between the status card and the bottom bar (owner 2026-11-24: the column gives
 * way, never the card): [FULL] both bubbles at 56 dp with a 12 dp gap; [COMPACT] 48 dp slots (still Material's minimum
 * touch target) and a 6 dp gap; [SIDE] the tour bubble (share, ⚙) side by side to the left of the map bubble's top – the
 * column is then only the map bubble high.
 */
enum class ControlsTier(val slot: Dp, val gap: Dp) {
    FULL(56.dp, 12.dp), COMPACT(48.dp, 6.dp), SIDE(48.dp, 12.dp)
}

/** The fit rule, pure so a test can check it ([ControlsRoomTest]). */
object ControlsFit {
    /** Breathing room kept above the bottom bar's top and below the cards (dp). */
    const val MARGIN_DP = 20

    /** One bubble of [n] slots (a 1 dp divider between), high in a column and wide in a row. */
    fun bubble(n: Int, slot: Dp): Dp = if (n <= 0) 0.dp else slot * n + 1.dp * (n - 1)

    /** The column's height in [tier]: [tour] slots in the tour bubble, [map] in the map bubble. */
    fun height(tier: ControlsTier, tour: Int, map: Int): Dp = when (tier) {
        ControlsTier.SIDE -> bubble(map, tier.slot)
        else -> bubble(tour, tier.slot) + (if (tour > 0) tier.gap else 0.dp) + bubble(map, tier.slot)
    }

    /** The first tier whose column fits [room]; [ControlsTier.SIDE] when none does (the smallest it can be); FULL without a measure. */
    fun tier(room: Dp?, tour: Int, map: Int): ControlsTier =
        if (room == null) ControlsTier.FULL
        else ControlsTier.values().firstOrNull { height(it, tour, map) <= room } ?: ControlsTier.SIDE

    /** What the waiting pill keeps free at the right (it sits beside the column's top; [SIDE] is wider there). */
    fun pillClear(tier: ControlsTier, tour: Int): Dp =
        if (tier == ControlsTier.SIDE) tier.slot + tier.gap + bubble(tour, tier.slot) + 8.dp else 68.dp
}

/** What the top and the bottom of Navigate tell each other about the controls: the chosen tier and the pill's height. */
class ControlsLane {
    var tier by mutableStateOf(ControlsTier.FULL)
    /** The waiting pill's height in dp (0 = none): its room is not the column's – the pill keeps clear sideways. */
    var pillDp by mutableIntStateOf(0)
}

val LocalControlsLane = compositionLocalOf { ControlsLane() }
val LocalControlsTier = compositionLocalOf { ControlsTier.FULL }

/** Around Navigate's overlay: top and bottom share one [ControlsLane]. */
@Composable
fun ControlsLaneHost(content: @Composable () -> Unit) {
    val lane = androidx.compose.runtime.remember { ControlsLane() }
    CompositionLocalProvider(LocalControlsLane provides lane, content = content)
}

/**
 * Around the footer: measures the room under the top cards ([topDp], the cards' column below the status bar) and over the
 * bottom bar ([bottomDp]) and tells the controls which [ControlsTier] fits ([LocalControlsTier]; the pill too, [ControlsLane]).
 */
@Composable
fun ControlsRoom(topDp: Int, bottomDp: Int, content: @Composable () -> Unit) {
    val lane = LocalControlsLane.current
    val tour = if (LocalTourShare.current != null) 2 else 1
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val d = LocalDensity.current
        val statusTop = with(d) { WindowInsets.statusBars.getTop(d).toDp() }
        val pill = if (lane.pillDp > 0) lane.pillDp + 8 else 0
        val room = maxHeight - statusTop - (topDp - pill).coerceAtLeast(0).dp - bottomDp.dp - ControlsFit.MARGIN_DP.dp
        val tier = ControlsFit.tier(room, tour, 4)
        if (lane.tier != tier) lane.tier = tier // written while composing: the pill (composed earlier) is recomposed in the same frame
        CompositionLocalProvider(LocalControlsTier provides tier, content = content)
    }
}
