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
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.filled.Close
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
import kotlinx.coroutines.flow.collectLatest
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
    /** Of [regions]: newer versions of areas the user already has (they replace the current map once loaded). */
    val newer: Int = 0,
    /** The server still builds some newer version: about this many minutes (null = unknown / none). */
    val newerEtaMin: Int? = null,
) {
    /** "Loading newer map" when only newer versions load (owner 2026-10-22: the strip is the one indicator for them). */
    val title: String get() = if (regions > 0 && newer >= regions && routingParts == 0) "Loading newer map" else "Loading map"

    /** Details line about the newer versions, null when none loads. */
    val newerLine: String? get() = if (newer <= 0) null else
        "Newer version of ${if (newer == 1) "1 area" else "$newer areas"} you have – it replaces the current map when loaded" +
            (newerEtaMin?.let { " (being prepared, about $it min)" } ?: "")

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

    fun update(active: List<Item>, regions: Int, routingParts: Int, newer: Int = 0, newerEtaMin: Int? = null): BackgroundDownloadUi? {
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
        return BackgroundDownloadUi(regions, routingParts, active.sumOf { it.total }, held, newer, newerEtaMin)
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
    val text = "${ui.title} · ${ui.line}"
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
                val fresh = ui.regions - ui.newer
                if (fresh > 0) Text("Map: $fresh ${if (fresh == 1) "area" else "areas"} along your route or around you")
                ui.newerLine?.let { Text(it) }
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
    // the states are collected inside the producer: a download tick does not recompose the caller, only a changed value
    val target by androidx.compose.runtime.produceState<BackgroundDownloadUi?>(null) {
        vm.cells.states.collectLatest { states ->
            while (true) { value = vm.backgroundDownloadUi(states); kotlinx.coroutines.delay(1000) }
        }
    }
    val gate = androidx.compose.runtime.remember { backgroundGate() }
    return rememberNoFlash(target, gate)
}

/**
 * Where the general message bar ("… OK") sits (UX review 2026-10-23, problem 11): it stays until OK, so it must never
 * cover the blue strip's buttons (Try again, Allow location, Load now) – below the strip while it shows. Pure, tested.
 */
object MessagePlacement {
    /** Top of the blue strip below the status bar (search pill + ☰ row above it). */
    const val STRIP_TOP_DP = 68
    /** The message bar without a strip. */
    const val MESSAGE_TOP_DP = 70

    fun topDp(stripShown: Boolean, stripHeightDp: Int): Int =
        if (stripShown && stripHeightDp > 0) maxOf(MESSAGE_TOP_DP, STRIP_TOP_DP + stripHeightDp + 8) else MESSAGE_TOP_DP
}

/** The details dialog for [ui] while the user opened it (strip / chip tap). */
@androidx.compose.runtime.Composable
internal fun BackgroundDownloadDetails(vm: AppViewModel, ui: BackgroundDownloadUi?) {
    if (!vm.downloadDetailsOpen) return
    if (ui == null) { androidx.compose.runtime.LaunchedEffect(Unit) { vm.downloadDetailsOpen = false }; return }
    BackgroundDownloadDialog(ui, onCancelAll = vm::cancelAllDownloads, onClose = { vm.downloadDetailsOpen = false })
}

/**
 * What of the map card still needs the user (owner 2026-10-23: no second map card in the plan sheet – the blue strip at
 * the top says it and asks there): the mobile-data question, areas waiting for Wi-Fi, a failure, offline, no position
 * yet on a fresh install, no downloads in this build. Plain progress is the [BackgroundDownloadStrip]'s; "ready" needs
 * nothing. Pure, unit-tested.
 */
fun mapNotice(card: CellCardUi?): CellCardUi? = when (card) {
    is CellCardUi.Working -> card.takeIf { it.question != null || it.wifiNote != null }
    is CellCardUi.Failed, is CellCardUi.Offline, is CellCardUi.NeedPosition, CellCardUi.NotAvailable -> card
    is CellCardUi.Done, null -> null
}

/** The blue strip when the map needs an answer or says why it does not come (see [mapNotice]); same place as the progress. */
@Composable
fun MapNoticeStrip(card: CellCardUi, cb: CellCardCallbacks, modifier: Modifier = Modifier) {
    val working = card as? CellCardUi.Working
    val title = working?.question?.text ?: working?.wifiNote ?: card.title
    val detail = when (card) {
        is CellCardUi.Failed -> card.why
        is CellCardUi.Offline -> card.why
        is CellCardUi.NeedPosition -> CellCardUi.NeedPosition.BODY
        CellCardUi.NotAvailable -> CellCardUi.NotAvailable.BODY
        else -> null
    }
    val closable = card is CellCardUi.Offline || card is CellCardUi.NeedPosition || (working != null && working.question == null)
    Surface(shape = RoundedCornerShape(12.dp), color = InfoBlue.copy(alpha = 0.94f), contentColor = Color.White, shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = title }) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (card is CellCardUi.Failed) Icons.Outlined.Warning else Icons.Outlined.Download,
                    null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (closable) androidx.compose.material3.IconButton(onClick = cb.onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, "Hide (the map keeps loading)", modifier = Modifier.size(18.dp))
                } else Spacer(Modifier.width(8.dp))
            }
            detail?.let { Text(it, fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(start = 22.dp, end = 8.dp)) }
            val buttons: List<Pair<String, () -> Unit>> = when {
                working?.question != null -> listOf("Only on Wi-Fi" to cb.onWifiOnly, "Load now" to cb.onMobileYes)
                // "Maps only on Wi-Fi": areas wait – once over mobile data, the setting stays (owner Q6 2026-10-23)
                working?.wifiNote != null -> listOf(MapLoadUi.LOAD_ON_MOBILE to cb.onMobileYes)
                card is CellCardUi.Failed -> listOfNotNull("Not now" to cb.onClose, if (card.retryable) "Try again" to cb.onRetry else null)
                card is CellCardUi.NeedPosition && !card.permission -> listOf("Allow location" to cb.onAllowLocation)
                card == CellCardUi.NotAvailable -> listOf("OK" to cb.onClose)
                else -> emptyList()
            }
            if (buttons.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                buttons.forEachIndexed { i, (label, act) ->
                    val main = i == buttons.lastIndex
                    TextButton(onClick = act, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(label, color = Color.White, fontWeight = if (main) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            } else Spacer(Modifier.height(4.dp))
        }
    }
}
