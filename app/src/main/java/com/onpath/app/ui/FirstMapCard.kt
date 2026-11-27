package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellJob
import com.onpath.app.cells.CellState
import com.onpath.app.cells.CellWhy

/**
 * The first map of the user's area (owner 2026-11-08, sketch A2; since 2026-11-19 „ich muss warten“ = form A): after the
 * phone setup the map for here may still be loading – then the wait window shows ([MapLoadModal], its content was this
 * card's) – or it cannot load without network: then this small ANZEIGE card on the map beside the activity button
 * (docs/design/ui-style.md) – no button, goes by itself when the map is there. While it shows, the grey offline pill,
 * the "Overview only" pill and the sign beside say nothing of their own (they would say the same).
 */
object FirstMapCard {
    sealed interface Ui {
        /** The map for here loads: the wait window shows it ([waits]). */
        data object Loading : Ui
        data object Offline : Ui
    }

    /**
     * Shows while the map for here ([CellWhy.HERE], the app opened at a position – the first start too) is not there,
     * the view has no detailed map ([viewWithoutDetail]), the phone setup is closed, in Plan, with downloads in this
     * build. Nothing when its areas wait for Wi-Fi / the mobile-data question or all failed (those boxes ask). Pure,
     * unit-tested.
     */
    fun of(
        job: CellJob?, states: Map<CellId, CellState>, sizes: Map<CellId, Long>,
        viewWithoutDetail: Boolean, setupOpen: Boolean, plan: Boolean, available: Boolean,
    ): Ui? {
        if (!plan || setupOpen || !available || !viewWithoutDetail) return null
        if (job == null || job.why != CellWhy.HERE || job.cells.isEmpty()) return null
        fun st(c: CellId): CellState = states[c]?.takeIf { it != CellState.None } ?: if (c in sizes) CellState.Ready("") else CellState.None
        fun ready(s: CellState) = s is CellState.Ready || s == CellState.Downloaded
        val s = job.cells.associateWith(::st)
        val open = job.cells.filter { !ready(s[it]!!) }
        if (open.isEmpty()) return null // the map is there: the card goes by itself
        val going = open.filter { s[it] !is CellState.Failed }
        if (going.isEmpty()) return null // all failed: the HINWEIS box with Try again
        // "loads when you are online" only when the phone is offline – a busy server says so in the pill (serverSide)
        if (going.all { (s[it] as? CellState.Offline)?.serverSide == false }) return Ui.Offline
        if (going.all { s[it] is CellState.WaitingForWifi || s[it] is CellState.Offline }) return null // the Wi-Fi question / note
        return Ui.Loading
    }

    /** The wait window waits for [job] – the first map of the area loads ([of] = [Ui.Loading]; the setup is the window's caller's). */
    fun waits(job: CellJob, states: Map<CellId, CellState>, sizes: Map<CellId, Long>, viewWithoutDetail: Boolean, available: Boolean): Boolean =
        of(job, states, sizes, viewWithoutDetail, setupOpen = false, plan = true, available = available) == Ui.Loading
}

/** At this font scale and above the card may be wider (no map controls beside it then). */
const val FIRST_MAP_CARD_LARGE_FONT = 1.5f

/** The card while the first map cannot load (offline): cloud crossed, "No map yet", "Loads once you're online". */
@Composable
fun FirstMapCardView(ui: FirstMapCard.Ui, modifier: Modifier = Modifier) {
    if (ui != FirstMapCard.Ui.Offline) return
    val large = LocalDensity.current.fontScale >= FIRST_MAP_CARD_LARGE_FONT
    val title = str(Res.string.maps_first_offline)
    val detail = str(Res.string.maps_first_offline_line)
    // over the map: paper (owner 2026-11-06); narrow at normal font, wider at a large font
    Surface(shape = OnPathShapes.Card, color = OnPathColors.Paper.copy(alpha = 0.97f),
        modifier = modifier.widthIn(max = if (large) 320.dp else 220.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $detail" }
            .warmShadow(OnPathShadow.LOW, OnPathShapes.Card)) {
        Row(Modifier.padding(start = 12.dp, end = 14.dp, top = if (large) 6.dp else 10.dp, bottom = if (large) 6.dp else 10.dp)) {
            Icon(Icons.Outlined.CloudOff, null, tint = OnPathColors.InkMuted, modifier = Modifier.padding(top = 1.dp).size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(if (large) 2.dp else 6.dp)) {
                Text(title, style = if (large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = OnPathColors.Ink)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = OnPathColors.InkMuted)
            }
        }
    }
}
