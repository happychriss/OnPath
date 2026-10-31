package com.onpath.app.ui

import com.onpath.strings.*
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
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.getValue
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState

/**
 * What loads in the background right now (owner: downloads must be visible and stoppable): map regions along
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
    /** What loads, by name ("around Bad Waldsee", "along your route to …"), most important first. */
    val groups: List<LoadGroup> = emptyList(),
) {
    /** The chip's name ("Bad Waldsee") and how many other names load ("+1"). */
    val chip: Pair<String, Int> get() = LoadNames.chip(groups)

    /**
     * A percent to show: something moved. 0 % = nothing has a percent yet (areas still being prepared on the server) –
     * the chip's ring spins and no "0 %" is shown.
     */
    val hasPercent: Boolean get() = percent > 0

    /** "Bad Waldsee +1 · 34 %" – what the chip says (accessibility, tests); without a percent yet only the names. */
    val chipText: String get() = chip.let { (n, more) -> n + (if (more > 0) " +$more" else "") + (if (hasPercent) " · $percent %" else "") }

    /** "Loading newer map" when only newer versions load (the strip is the one indicator for them). */
    val title: String get() = Txt.s(if (regions > 0 && newer >= regions && routingParts == 0) Res.string.maps_bg_title_newer
        else Res.string.maps_bg_title)

    /** Details line about the newer versions, null when none loads. */
    val newerLine: String? get() = if (newer <= 0) null else
        newerEtaMin?.let { Txt.s(Res.string.maps_bg_newer_eta, newer, it) } ?: Txt.s(Res.string.maps_bg_newer, newer)

    /** "3 areas · 24 MB · 40 %" (the strip); "24 MB · 40 %" when only the route planning part of the map loads (never named). */
    val line: String get() {
        val what = if (regions > 0) Txt.p(Res.plurals.maps_areas_count, regions, regions) else null
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
     * server's preparing time counts too, as in the loading window (else the strip stands at 0 % while the server
         * prepares); without it only downloaded bytes count.
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

/**
 * Small light blue chip "◔ Bad Waldsee · 34 %" in the tag look (owner 2026-10-31: `TagContainer` / `TagInk`, like the
 * route tags) with a small progress ring in front – determinate with the percent, spinning while nothing has a percent
 * yet ([BackgroundDownloadUi.hasPercent]: an area still being prepared). Plan: under the ☰ button; Navigate: under the
 * status card. The name is cut with "…" at about half the screen width, "+N" (more names) and the % always show. The
 * pill looks small; the tap target is ≥ 48 dp. Tap → the details sheet ([BackgroundDownloadSheet]).
 */
@Composable
fun BackgroundDownloadChip(ui: BackgroundDownloadUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (name, more) = ui.chip
    val text = str(Res.string.maps_bg_chip_cd, ui.chipText)
    val maxW = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.5f).dp
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = str(Res.string.maps_bg_chip_click), onClick = onClick)
        .semantics { contentDescription = text }, contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = OnPathColors.TagContainer, contentColor = OnPathColors.TagInk,
            shadowElevation = 2.dp) {
            Row(Modifier.widthIn(max = maxW).padding(start = 7.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // the ring as tall as the text (grows with the phone's font size)
                val ring = Modifier.size(with(androidx.compose.ui.platform.LocalDensity.current) { MaterialTheme.typography.labelMedium.fontSize.toDp() })
                val track = OnPathColors.TagInk.copy(alpha = 0.18f)
                if (ui.hasPercent) CircularProgressIndicator(progress = { ui.percent / 100f }, modifier = ring, color = OnPathColors.TagInk,
                    trackColor = track, strokeWidth = 2.dp, gapSize = 0.dp)
                else CircularProgressIndicator(ring, color = OnPathColors.TagInk, trackColor = track, strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(name, color = OnPathColors.TagInk, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text((if (more > 0) " +$more" else "") + (if (ui.hasPercent) " · ${ui.percent} %" else ""), color = OnPathColors.TagInk,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * Tap on the chip: what loads, and "Cancel all" (same as Settings → Offline maps; installed maps stay) – a bottom sheet
 * (owner 2026-10-31: the details of a state are a sheet, dialogs only for decisions). Drag down or Close closes it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BackgroundDownloadSheet(ui: BackgroundDownloadUi, onCancelAll: () -> Unit, onClose: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose, containerColor = OnPathColors.CardWhite) {
        BackgroundDownloadDetailsContent(ui, onCancelAll, onClose)
    }
}

/** The sheet's content: title, one line per name, the total, "Your maps stay", Cancel all (red) · Close. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BackgroundDownloadDetailsContent(ui: BackgroundDownloadUi, onCancelAll: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(str(Res.string.maps_bg_dialog_title), style = MaterialTheme.typography.titleLarge, color = OnPathColors.Ink,
            modifier = Modifier.padding(end = 8.dp, bottom = 4.dp))
        if (ui.groups.isNotEmpty()) {
            // one line per name: "Along your route to Gasthof Taser Alm (2 areas) · 24 MB · loading"
            ui.groups.forEach { Text(it.line, style = MaterialTheme.typography.bodyLarge, color = OnPathColors.Ink) }
            if (ui.newer > 0) ui.newerEtaMin?.let {
                Text(str(Res.string.maps_bg_newer_ready, it), style = MaterialTheme.typography.bodyMedium, color = OnPathColors.InkMuted)
            }
        } else {
            val fresh = ui.regions - ui.newer
            if (fresh > 0) Text(plural(Res.plurals.maps_bg_fresh, fresh, fresh), style = MaterialTheme.typography.bodyLarge)
            ui.newerLine?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
        Text(listOfNotNull(if (ui.bytes > 0) str(Res.string.maps_bg_total, mb(ui.bytes)) else null,
            str(Res.string.maps_bg_done, ui.percent)).joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = OnPathColors.Ink)
        Text(str(Res.string.maps_bg_maps_stay), style = MaterialTheme.typography.bodyMedium, color = OnPathColors.InkMuted)
        // the two buttons wrap (right-aligned) when they do not fit side by side – never a word broken in a button
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancelAll, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(str(Res.string.common_cancel_all), color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold, softWrap = false)
            }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_close), softWrap = false) }
        }
    }
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

/** The details sheet for [ui] while the user opened it (chip tap). */
@androidx.compose.runtime.Composable
internal fun BackgroundDownloadDetails(vm: AppViewModel, ui: BackgroundDownloadUi?) {
    if (!vm.downloadDetailsOpen) return
    if (ui == null) { androidx.compose.runtime.LaunchedEffect(Unit) { vm.downloadDetailsOpen = false }; return }
    BackgroundDownloadSheet(ui, onCancelAll = vm::cancelAllDownloads, onClose = { vm.downloadDetailsOpen = false })
}

/**
 * What of the map card still needs the user (no second map card in the plan sheet – the box at the top says it
 * and asks there): the mobile-data question, areas waiting for Wi-Fi, a failure, offline, no position
 * yet on a fresh install, no downloads in this build. Plain progress is the [BackgroundDownloadChip]'s; "ready" needs
 * nothing. Pure, unit-tested.
 */
fun mapNotice(card: CellCardUi?): CellCardUi? = when (card) {
    is CellCardUi.Working -> card.takeIf { it.question != null || it.wifiNote != null }
    is CellCardUi.Failed, is CellCardUi.Offline, is CellCardUi.NeedPosition, CellCardUi.NotAvailable -> card
    is CellCardUi.Done, null -> null
}

/**
 * Which box a map notice is (docs/design/ui-style.md), null = no box but the quiet grey pill (a state: offline, waiting
 * for GPS, waiting for Wi-Fi after "Wi-Fi only"). FRAGE: mobile data?, "Load over mobile data" while Settings says Wi-Fi
 * only; HINWEIS: "Allow location" (the one way out), "No map downloads in this build" (OK); HINWEIS_WARN: a failure
 * (Try again). Pure, unit-tested.
 */
fun mapNoticeKind(card: CellCardUi): MessageKind? = when (card) {
    is CellCardUi.Failed -> MessageKind.HINWEIS_WARN
    is CellCardUi.Working -> if (card.question != null || card.canLoadNow) MessageKind.FRAGE else null
    is CellCardUi.NeedPosition -> if (card.permission) null else MessageKind.HINWEIS
    CellCardUi.NotAvailable -> MessageKind.HINWEIS
    is CellCardUi.Offline, is CellCardUi.Done -> null
}

/** The map notice ([mapNotice]) as a box, or null when it is the quiet pill ([MapNoticePill]). */
@Composable
fun mapNoticeMessage(card: CellCardUi, cb: CellCardCallbacks): Message? {
    val kind = mapNoticeKind(card) ?: return null
    val working = card as? CellCardUi.Working
    val title = working?.question?.text ?: working?.wifiNote ?: card.title
    val buttons = when {
        card is CellCardUi.Failed ->
            listOf(if (card.retryable) MessageButton(str(Res.string.common_try_again), cb.onRetry) else MessageButton(str(Res.string.common_ok), cb.onClose))
        working?.question != null -> listOf(MessageButton(str(Res.string.maps_btn_wifi_only), cb.onWifiOnly),
            MessageButton(str(Res.string.maps_btn_load_now), cb.onMobileYes))
        // "Maps only on Wi-Fi": areas wait – once over mobile data, the setting stays (owner); Not now = the areas keep waiting
        working != null -> listOf(MessageButton(str(Res.string.common_not_now), cb.onClose), MessageButton(MapLoadUi.LOAD_ON_MOBILE, cb.onMobileYes))
        card == CellCardUi.NotAvailable -> listOf(MessageButton(str(Res.string.common_ok), cb.onClose))
        else -> listOf(MessageButton(str(Res.string.maps_btn_allow_location), cb.onAllowLocation))
    }
    return Message(kind, title, buttons = buttons)
}

/** A map notice that is a state, not a box: the quiet grey pill (offline, waiting for GPS / Wi-Fi). */
@Composable
fun MapNoticePill(card: CellCardUi, modifier: Modifier = Modifier) {
    val working = card as? CellCardUi.Working
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        QuietPill(working?.wifiNote ?: card.title,
            if (card is CellCardUi.NeedPosition) Icons.Outlined.LocationSearching else Icons.Outlined.CloudOff)
    }
}

/** The app opened where there is no detailed map: the box asks instead of loading (e.g. in a car – owner). */
@Composable
fun hereAskMessage(bytes: Long, onLoad: () -> Unit, onNotNow: () -> Unit, mustBuild: Boolean = true): Message =
    // short (owner): title ≤ 5 words, one detail line at most
    Message(MessageKind.FRAGE, str(Res.string.maps_here_ask, mb(bytes)),
        detail = if (mustBuild) str(Res.string.maps_here_ask_detail) else null,
        buttons = listOf(MessageButton(str(Res.string.common_not_now), onNotNow), MessageButton(str(Res.string.maps_btn_load), onLoad)))
