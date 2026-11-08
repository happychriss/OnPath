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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * The first-map card (owner 2026-11-08, sketch A2): after the phone setup the map for here may still be loading – or
 * cannot load without network. An ANZEIGE card on the map beside the activity button (docs/design/ui-style.md): no
 * button, goes by itself when the map is there. While it shows, the grey offline pill, the "Overview only" pill and the
 * download chip say nothing of their own (they would say the same).
 */
object FirstMapCard {
    sealed interface Ui {
        /** [percent] null = nothing downloaded yet (the server prepares it): the bar moves without a value. */
        data class Loading(val percent: Int?, val bytes: Long) : Ui
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
        if (going.all { s[it] is CellState.Offline }) return Ui.Offline
        if (going.all { s[it] is CellState.WaitingForWifi || s[it] is CellState.Offline }) return null // the Wi-Fi question / note
        val bytes = MapLoads.estimate(job.cells, states, sizes)
        val done = job.cells.sumOf { c ->
            when (val x = s[c]!!) {
                is CellState.Downloading -> x.doneBytes
                else -> if (ready(x) || x == CellState.Installing) MapLoads.knownBytes(states[c], sizes[c]) ?: MapLoads.ESTIMATED_REGION_BYTES else 0L
            }
        }
        val percent = if (done <= 0 || bytes <= 0) null else (done * 100 / bytes).toInt().coerceIn(0, 99)
        return Ui.Loading(percent, bytes)
    }
}

/** At this font scale and above the bar and "34 %" share one line and the MB go (the card stays clear of the plan sheet). */
const val FIRST_MAP_CARD_LARGE_FONT = 1.5f

/**
 * The card: cloud icon (20 dp), the title, under it the thin bar and "34 % · ca. 18 MB" (offline: "Lädt, sobald du online
 * bist"). At a large font ([FIRST_MAP_CARD_LARGE_FONT]) the title is set smaller, bar and "34 %" share one line, no MB.
 */
@Composable
fun FirstMapCardView(ui: FirstMapCard.Ui, modifier: Modifier = Modifier) {
    val large = LocalDensity.current.fontScale >= FIRST_MAP_CARD_LARGE_FONT
    val loading = ui as? FirstMapCard.Ui.Loading
    val title = str(if (loading != null) Res.string.maps_first_loading else Res.string.maps_first_offline)
    val detail = when {
        loading == null -> str(Res.string.maps_first_offline_line)
        large -> "${loading.percent ?: 0} %"
        else -> str(Res.string.maps_first_progress, loading.percent ?: 0, mb(loading.bytes))
    }
    // over the map: paper (owner 2026-11-06); narrow at normal font, wider at a large font (no map controls there then)
    Surface(shape = OnPathShapes.Card, color = OnPathColors.Paper.copy(alpha = 0.97f),
        modifier = modifier.widthIn(max = if (large) 320.dp else 220.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $detail" }
            .warmShadow(OnPathShadow.LOW, OnPathShapes.Card)) {
        Row(Modifier.padding(start = 12.dp, end = 14.dp, top = if (large) 6.dp else 10.dp, bottom = if (large) 6.dp else 10.dp)) {
            Icon(if (loading != null) Icons.Outlined.CloudDownload else Icons.Outlined.CloudOff, null,
                tint = if (loading != null) OnPathColors.Green else OnPathColors.InkMuted,
                modifier = Modifier.padding(top = 1.dp).size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(if (large) 2.dp else 6.dp)) {
                Text(title, style = if (large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = OnPathColors.Ink)
                when {
                    loading == null -> Text(detail, style = MaterialTheme.typography.bodySmall, color = OnPathColors.InkMuted)
                    large -> Row(verticalAlignment = Alignment.CenterVertically) {
                        FirstMapBar(loading.percent, Modifier.weight(1f))
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = OnPathColors.InkMuted,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                    else -> {
                        FirstMapBar(loading.percent, Modifier.width(150.dp))
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = OnPathColors.InkMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun FirstMapBar(percent: Int?, modifier: Modifier) {
    if (percent == null) LinearProgressIndicator(modifier = modifier.clip(CircleShape), color = MaterialTheme.colorScheme.primary)
    else LinearProgressIndicator(progress = { percent / 100f }, color = MaterialTheme.colorScheme.primary, modifier = modifier.clip(CircleShape))
}
