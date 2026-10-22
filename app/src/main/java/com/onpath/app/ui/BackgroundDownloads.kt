package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState

/**
 * What loads in the background right now (owner 2026-10-22: downloads must be visible and stoppable): map regions along
 * the plan's route, the map ahead on a tour, newer versions, routing data – anything the user is not waiting for in the
 * big loading modal. [percent] over everything loading since the strip appeared (never backwards).
 */
data class BackgroundDownloadUi(
    /** Map regions loading (never called "cells" on screen). */
    val regions: Int,
    /** Routing data parts loading. */
    val routingParts: Int,
    /** Bytes of everything loading, as far as known. */
    val bytes: Long,
    val percent: Int,
) {
    /** "3 areas · 24 MB · 40 %" (the strip), "routing data" when only that loads. */
    val line: String get() {
        val what = when {
            regions > 0 -> "$regions ${if (regions == 1) "area" else "areas"}"
            else -> "routing data"
        }
        return listOfNotNull(what, if (bytes > 0) "${mb(bytes)} MB" else null, "$percent %").joinToString(" · ")
    }
}

/**
 * Progress of one background loading session, pure: items (map regions, routing parts) seen since the session began
 * count as done once they leave; the session ends when nothing loads. Unit-tested.
 */
class BackgroundProgress {
    private val items = LinkedHashMap<String, LongArray>() // key → [done, total]
    private var held = 0

    /** One item loading now: [done] of [total] bytes (total 0 = unknown, counted with an estimate). */
    data class Item(val key: String, val done: Long, val total: Long)

    fun update(active: List<Item>, regions: Int, routingParts: Int): BackgroundDownloadUi? {
        if (active.isEmpty()) { items.clear(); held = 0; return null }
        val now = active.associateBy { it.key }
        for ((k, v) in items) if (k !in now) v[0] = v[1] // finished (or dropped): full
        for (i in active) {
            val t = if (i.total > 0) i.total else ESTIMATE_BYTES
            val v = items.getOrPut(i.key) { LongArray(2) }
            v[0] = maxOf(v[0].coerceAtMost(t), i.done.coerceAtMost(t)); v[1] = t
        }
        val total = items.values.sumOf { it[1] }
        val done = items.values.sumOf { it[0] }
        val pct = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 99)
        held = maxOf(held, pct)
        return BackgroundDownloadUi(regions, routingParts, active.sumOf { it.total }, held)
    }

    companion object {
        /** A region or part of unknown size (server not asked yet). */
        const val ESTIMATE_BYTES = 10L * 1_048_576

        /** A map region in one of these states is loading (asked, built, downloaded or joined right now). */
        fun loading(s: CellState): Boolean = s == CellState.Requested || s is CellState.Queued || s is CellState.Building ||
            s is CellState.Downloading || s == CellState.Installing

        /** The background items of the cell [states] (those in [waitedFor] are the modal's, not the strip's). */
        fun cellItems(states: Map<CellId, CellState>, waitedFor: Set<CellId>, size: (CellId) -> Long?): List<Item> =
            states.filter { (c, s) -> c !in waitedFor && loading(s) }.map { (c, s) ->
                val d = s as? CellState.Downloading
                val total = d?.totalBytes?.takeIf { it > 0 } ?: size(c) ?: 0L
                Item("cell:${c.id}", d?.doneBytes ?: 0L, total)
            }
    }
}

/** Information colour of the strip and chip: blue – not the amber of warnings, not the green brand. */
private val InfoBlue = Color(0xFF1565C0)

/** Plan: thin, slightly transparent blue strip below the search bar: "⬇ Loading map · 3 areas · 24 MB · 40 %". Tap → details. */
@Composable
fun BackgroundDownloadStrip(ui: BackgroundDownloadUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = "Loading map · ${ui.line}"
    Surface(shape = RoundedCornerShape(12.dp), color = InfoBlue.copy(alpha = 0.88f), contentColor = Color.White, shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth().heightIn(min = 36.dp)
            .clickable(role = Role.Button, onClickLabel = "Show background downloads", onClick = onClick)
            .semantics { contentDescription = text }) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { ui.percent / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
        }
    }
}

/** Navigate: only a small "⬇ 40 %" chip (next to the recording status). Tap → details. */
@Composable
fun BackgroundDownloadChip(ui: BackgroundDownloadUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = "Loading map ${ui.percent} %"
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Show background downloads", onClick = onClick)
        .semantics { contentDescription = text }, contentAlignment = Alignment.Center) {
        Row(Modifier.background(InfoBlue.copy(alpha = 0.88f), RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Download, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("${ui.percent} %", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Tap on the strip / chip: what loads, and "Cancel all" (same as Settings → Offline maps; installed maps stay). */
@Composable
fun BackgroundDownloadDialog(ui: BackgroundDownloadUi, onCancelAll: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Loading in the background") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ui.regions > 0) Text("Map: ${ui.regions} ${if (ui.regions == 1) "area" else "areas"} along your route or around you")
                if (ui.routingParts > 0) Text("Routing data: ${ui.routingParts} ${if (ui.routingParts == 1) "part" else "parts"}")
                Text(listOfNotNull(if (ui.bytes > 0) "About ${mb(ui.bytes)} MB" else null, "${ui.percent} % done").joinToString(" · "))
                Text("Cancel stops it. The maps you have stay; an area loads again when you plan there.", fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = onCancelAll, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel all", color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

/** The strip appears only after a background download has run this long (owner: 2 s is too hectic). */
const val BACKGROUND_SHOW_DELAY_MS = 10_000L

/** The gate for the strip / chip: shows after [BACKGROUND_SHOW_DELAY_MS], stays until all is done; progress shows at once. */
fun backgroundGate() = NoFlash<BackgroundDownloadUi>(showDelayMs = BACKGROUND_SHOW_DELAY_MS, stepKey = { Unit })

/** What the strip / chip shows now (gated: after [BACKGROUND_SHOW_DELAY_MS], until all is done); ticks every second. */
@androidx.compose.runtime.Composable
internal fun rememberBackgroundDownload(vm: AppViewModel): BackgroundDownloadUi? {
    val states by vm.cells.states.collectAsState()
    val target by androidx.compose.runtime.produceState<BackgroundDownloadUi?>(null, states) {
        while (true) { value = vm.backgroundDownloadUi(states); kotlinx.coroutines.delay(1000) }
    }
    val gate = androidx.compose.runtime.remember { backgroundGate() }
    return rememberNoFlash(target, gate)
}

/** The details dialog for [ui] while the user opened it (strip / chip tap). */
@androidx.compose.runtime.Composable
internal fun BackgroundDownloadDetails(vm: AppViewModel, ui: BackgroundDownloadUi?) {
    if (!vm.downloadDetailsOpen) return
    if (ui == null) { androidx.compose.runtime.LaunchedEffect(Unit) { vm.downloadDetailsOpen = false }; return }
    BackgroundDownloadDialog(ui, onCancelAll = vm::cancelAllDownloads, onClose = { vm.downloadDetailsOpen = false })
}
