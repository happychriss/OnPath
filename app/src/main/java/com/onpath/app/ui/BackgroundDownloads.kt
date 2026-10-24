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
import androidx.compose.foundation.layout.widthIn
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
    /** What loads, by name (owner 2026-10-23: "around Bad Waldsee", "along your route to …"), most important first. */
    val groups: List<LoadGroup> = emptyList(),
) {
    /** The chip's name ("Bad Waldsee") and how many other names load ("+1"). */
    val chip: Pair<String, Int> get() = LoadNames.chip(groups)

    /** "⬇ Bad Waldsee +1 · 34 %" without the arrow (accessibility, tests). */
    val chipText: String get() = chip.let { (n, more) -> n + (if (more > 0) " +$more" else "") + " · $percent %" }

    /** "Loading newer map" when only newer versions load (owner 2026-10-22: the strip is the one indicator for them). */
    val title: String get() = if (regions > 0 && newer >= regions && routingParts == 0) "Loading newer map" else "Loading map"

    /** Details line about the newer versions, null when none loads. */
    val newerLine: String? get() = if (newer <= 0) null else
        "Newer map for ${if (newer == 1) "1 of your areas" else "$newer of your areas"} – replaces the old one when loaded" +
            (newerEtaMin?.let { " (being prepared, about $it min)" } ?: "")

    /** "3 areas · 24 MB · 40 %" (the strip); "24 MB · 40 %" when only the route planning part of the map loads (never named). */
    val line: String get() {
        val what = when {
            regions > 0 -> "$regions ${if (regions == 1) "area" else "areas"}"
            else -> null
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

    fun update(active: List<Item>, regions: Int, routingParts: Int, newer: Int = 0, newerEtaMin: Int? = null,
               groups: List<LoadGroup> = emptyList()): BackgroundDownloadUi? {
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
        return BackgroundDownloadUi(regions, routingParts, active.sumOf { it.total }, held, newer, newerEtaMin, groups)
    }

    companion object {
        /** A region or part of unknown size (server not asked yet). */
        const val ESTIMATE_BYTES = 10L * 1_048_576

        /** A map region in one of these states is loading (asked, built, downloaded or joined right now). */
        fun loading(s: CellState): Boolean = s == CellState.Requested || s is CellState.Queued || s is CellState.Building ||
            s is CellState.Downloading || s == CellState.Installing

        /**
         * The background items of the cell [states] (those in [waitedFor] are the modal's, not the strip's). An
         * [installed] area only asked for a newer version ([CellState.Requested]: the server has not answered) is no
         * loading yet. With [progress] (one [MapLoadProgress] per cell, kept by the caller; [now] uptime ms) the
         * server's preparing time counts too, as in the loading window (field test 0.10.1: the strip stood at 0 % for
         * the ~10 minutes the server prepared two areas); without it only downloaded bytes count.
         */
        fun cellItems(
            states: Map<CellId, CellState>, waitedFor: Set<CellId>, installed: Set<CellId> = emptySet(),
            progress: MutableMap<CellId, MapLoadProgress>? = null, now: Long = 0L, size: (CellId) -> Long?,
        ): List<Item> {
            val items = states.filter { (c, s) -> c !in waitedFor && loading(s) && !(c in installed && s == CellState.Requested) }
            progress?.keys?.retainAll(items.keys)
            return items.map { (c, s) ->
                val d = s as? CellState.Downloading
                val total = d?.totalBytes?.takeIf { it > 0 } ?: size(c) ?: 0L
                val done = if (progress == null) d?.doneBytes ?: 0L else {
                    val pct = progress.getOrPut(c) { MapLoadProgress() }.update(s, now).percent
                    (if (total > 0) total else ESTIMATE_BYTES) * pct / 100
                }
                Item("cell:${c.id}", done, total)
            }
        }
    }
}

/** Information colour of the chip and the map notice: blue – not the amber of warnings, not the green brand. */
private val InfoBlue = Color(0xFF1565C0)

/**
 * Small, slightly transparent blue chip "⬇ Bad Waldsee · 34 %" (owner 2026-10-23: the full-width strip looked crowded).
 * Plan: under the ☰ button; Navigate: under the status card. The name is cut with "…" at about half the screen width (owner: ~45 %),
 * "+N" (more names) and the % always show. Tap → details.
 */
@Composable
fun BackgroundDownloadChip(ui: BackgroundDownloadUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (name, more) = ui.chip
    val text = "Loading map: ${ui.chipText}"
    val maxW = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.5f).dp
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Show background downloads", onClick = onClick)
        .semantics { contentDescription = text }, contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = maxW).background(InfoBlue.copy(alpha = 0.88f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Download, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text((if (more > 0) " +$more" else "") + " · ${ui.percent} %", color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
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
                if (ui.groups.isNotEmpty()) {
                    // one line per name (owner 2026-10-23): "Along your route to Gasthof Taser Alm (2 areas) · 24 MB · loading"
                    ui.groups.forEach { Text(it.line) }
                    if (ui.newer > 0) ui.newerEtaMin?.let { Text("The newer map is being prepared, about $it min. It replaces the old one when loaded.", fontSize = 13.sp) }
                } else {
                    val fresh = ui.regions - ui.newer
                    if (fresh > 0) Text("Map: $fresh ${if (fresh == 1) "area" else "areas"} along your route or around you")
                    ui.newerLine?.let { Text(it) }
                }
                Text(listOfNotNull(if (ui.bytes > 0) "In all about ${mb(ui.bytes)} MB" else null, "${ui.percent} % done").joinToString(" · "),
                    fontWeight = FontWeight.Medium)
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
 * cover the blue strip's buttons (Try again, Allow location, Load now) – below the strip while it shows. The loading
 * chip under ☰ (owner 2026-10-23) uses the same place: the message goes below it too, never over it. Pure, tested.
 */
object MessagePlacement {
    /** Top of the blue strip / the loading chip's touch area below the status bar (search pill + ☰ row above it). */
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
 * yet on a fresh install, no downloads in this build. Plain progress is the [BackgroundDownloadChip]'s; "ready" needs
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
    val buttons: List<Pair<String, () -> Unit>> = when {
        working?.question != null -> listOf("Only on Wi-Fi" to cb.onWifiOnly, "Load now" to cb.onMobileYes)
        // "Maps only on Wi-Fi": areas wait – once over mobile data, the setting stays (owner Q6 2026-10-23)
        working?.wifiNote != null -> listOf(MapLoadUi.LOAD_ON_MOBILE to cb.onMobileYes)
        card is CellCardUi.Failed -> listOfNotNull("Not now" to cb.onClose, if (card.retryable) "Try again" to cb.onRetry else null)
        card is CellCardUi.NeedPosition && !card.permission -> listOf("Allow location" to cb.onAllowLocation)
        card == CellCardUi.NotAvailable -> listOf("OK" to cb.onClose)
        else -> emptyList()
    }
    BlueNotice(title, detail, warning = card is CellCardUi.Failed, onClose = if (closable) cb.onClose else null, buttons, modifier)
}

/**
 * Owner 2026-10-23 (e.g. in a car): the app opened where there is no detailed map – the strip asks instead of loading.
 */
@Composable
fun HereAskStrip(regions: Int, bytes: Long, onLoad: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) =
    BlueNotice("Detailed map for here? (≈ ${mb(bytes)} MB)",
        "Until then the overview map shows. Setting a point here also loads it.",
        warning = false, onClose = null, listOf("Not now" to onNotNow, "Load" to onLoad), modifier)

/** One blue strip at the top (owner 2026-10-23): a title, an optional detail line, buttons right-aligned (last = main). */
@Composable
private fun BlueNotice(title: String, detail: String?, warning: Boolean, onClose: (() -> Unit)?,
                       buttons: List<Pair<String, () -> Unit>>, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), color = InfoBlue.copy(alpha = 0.94f), contentColor = Color.White, shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = title }) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (warning) Icons.Outlined.Warning else Icons.Outlined.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (onClose != null) androidx.compose.material3.IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, "Hide (the map keeps loading)", modifier = Modifier.size(18.dp))
                } else Spacer(Modifier.width(8.dp))
            }
            detail?.let { Text(it, fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(start = 22.dp, end = 8.dp)) }
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
