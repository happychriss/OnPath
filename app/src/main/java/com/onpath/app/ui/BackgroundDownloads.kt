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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
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

    /** "⬇ Bad Waldsee +1 · 34 %" without the arrow (accessibility, tests). */
    val chipText: String get() = chip.let { (n, more) -> n + (if (more > 0) " +$more" else "") + " · $percent %" }

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
 * Small, slightly transparent blue chip "⬇ Bad Waldsee · 34 %" (not a full-width strip: too crowded). Plan: under the ☰
 * button; Navigate: under the status card. The name is cut with "…" at about half the screen width, "+N" (more names)
 * and the % always show. Tap → details.
 */
@Composable
fun BackgroundDownloadChip(ui: BackgroundDownloadUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (name, more) = ui.chip
    val text = str(Res.string.maps_bg_chip_cd, ui.chipText)
    val maxW = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.5f).dp
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = str(Res.string.maps_bg_chip_click), onClick = onClick)
        .semantics { contentDescription = text }, contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = maxW).background(OnPathColors.InfoBlue.copy(alpha = 0.88f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Download, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(name, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text((if (more > 0) " +$more" else "") + " · ${ui.percent} %", color = Color.White, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
}

/** Tap on the strip / chip: what loads, and "Cancel all" (same as Settings → Offline maps; installed maps stay). */
@Composable
fun BackgroundDownloadDialog(ui: BackgroundDownloadUi, onCancelAll: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(Res.string.maps_bg_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ui.groups.isNotEmpty()) {
                    // one line per name: "Along your route to Gasthof Taser Alm (2 areas) · 24 MB · loading"
                    ui.groups.forEach { Text(it.line) }
                    if (ui.newer > 0) ui.newerEtaMin?.let { Text(str(Res.string.maps_bg_newer_ready, it), style = MaterialTheme.typography.bodyMedium) }
                } else {
                    val fresh = ui.regions - ui.newer
                    if (fresh > 0) Text(plural(Res.plurals.maps_bg_fresh, fresh, fresh))
                    ui.newerLine?.let { Text(it) }
                }
                Text(listOfNotNull(if (ui.bytes > 0) str(Res.string.maps_bg_total, mb(ui.bytes)) else null,
                    str(Res.string.maps_bg_done, ui.percent)).joinToString(" · "),
                    fontWeight = FontWeight.Medium)
                Text(str(Res.string.maps_bg_maps_stay), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_close)) } },
        dismissButton = {
            TextButton(onClick = onCancelAll, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(str(Res.string.common_cancel_all), color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold)
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
 * Where the strips and the error card (stays until OK) sit. Owner 2026-10-30: the blue strips not glued under the
 * search bar – a calm gap below it; the small loading chip stays right under ☰. The error card never covers the blue
 * strip's buttons (Try again, Allow location, Load now): below the strip / chip while it shows. In Navigate it goes below
 * the turn card, status card and chips. The plan sheet is drawn over the strips (PlanMode), so they never cover its
 * primary button. Pure, tested.
 */
object MessagePlacement {
    /** Top of the loading chip's touch area below the status bar (search pill + ☰ row above it). */
    const val CHIP_TOP_DP = 68
    /** Top of the blue / amber strips below the status bar: ≈ 24 dp below the search pill + ☰ row. */
    const val STRIP_TOP_DP = 92
    /** The error card without a strip: where a strip would be. */
    const val MESSAGE_TOP_DP = STRIP_TOP_DP
    const val GAP_DP = 8

    /** Plan: [stripBottomDp] = the bottom of the strip / chip below the status bar (0 = not measured yet). */
    fun topDp(stripShown: Boolean, stripBottomDp: Int): Int =
        if (stripShown && stripBottomDp > 0) maxOf(MESSAGE_TOP_DP, stripBottomDp + GAP_DP) else MESSAGE_TOP_DP

    /** Navigate: [topColumnDp] = height of the turn card + status card + chips column below the status bar. */
    fun navigateTopDp(topColumnDp: Int): Int = topColumnDp.coerceAtLeast(0) + GAP_DP
}

/** The details dialog for [ui] while the user opened it (strip / chip tap). */
@androidx.compose.runtime.Composable
internal fun BackgroundDownloadDetails(vm: AppViewModel, ui: BackgroundDownloadUi?) {
    if (!vm.downloadDetailsOpen) return
    if (ui == null) { androidx.compose.runtime.LaunchedEffect(Unit) { vm.downloadDetailsOpen = false }; return }
    BackgroundDownloadDialog(ui, onCancelAll = vm::cancelAllDownloads, onClose = { vm.downloadDetailsOpen = false })
}

/**
 * What of the map card still needs the user (no second map card in the plan sheet – the blue strip at the top says it
 * and asks there): the mobile-data question, areas waiting for Wi-Fi, a failure, offline, no position
 * yet on a fresh install, no downloads in this build. Plain progress is the [BackgroundDownloadChip]'s; "ready" needs
 * nothing. Pure, unit-tested.
 */
fun mapNotice(card: CellCardUi?): CellCardUi? = when (card) {
    is CellCardUi.Working -> card.takeIf { it.question != null || it.wifiNote != null }
    is CellCardUi.Failed, is CellCardUi.Offline, is CellCardUi.NeedPosition, CellCardUi.NotAvailable -> card
    is CellCardUi.Done, null -> null
}

/** How the map notice looks: one colour = one meaning. Pure, unit-tested. */
enum class NoticeLook { QUESTION, WARNING, QUIET }

fun noticeLook(card: CellCardUi): NoticeLook = when (card) {
    is CellCardUi.Failed -> NoticeLook.WARNING
    is CellCardUi.Working -> if (card.question != null || card.canLoadNow) NoticeLook.QUESTION else NoticeLook.QUIET
    is CellCardUi.NeedPosition -> if (card.permission) NoticeLook.QUIET else NoticeLook.QUESTION
    is CellCardUi.Offline, CellCardUi.NotAvailable, is CellCardUi.Done -> NoticeLook.QUIET
}

/**
 * The map notice at the top (see [mapNotice]); same place as the progress chip. Blue strip only for a question with
 * buttons (mobile data, Allow location, "Load now" while Settings says Wi-Fi only); a failure = amber strip with Try
 * again; a pure status (offline, waiting for GPS, waiting for Wi-Fi after "Wi-Fi only") = the quiet grey pill, no ×.
 * No "why" lines.
 */
@Composable
fun MapNoticeStrip(card: CellCardUi, cb: CellCardCallbacks, modifier: Modifier = Modifier) {
    val working = card as? CellCardUi.Working
    val title = working?.question?.text ?: working?.wifiNote ?: card.title
    when (noticeLook(card)) {
        NoticeLook.QUIET -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QuietPill(title, if (card is CellCardUi.NeedPosition) Icons.Outlined.LocationSearching else Icons.Outlined.CloudOff,
                action = if (card == CellCardUi.NotAvailable) str(Res.string.common_ok) to cb.onClose else null)
        }
        NoticeLook.WARNING -> {
            val f = card as CellCardUi.Failed
            Notice(title, warning = true, onClose = null,
                listOfNotNull(str(Res.string.common_not_now) to cb.onClose, if (f.retryable) str(Res.string.common_try_again) to cb.onRetry else null), modifier)
        }
        NoticeLook.QUESTION -> {
            val buttons: List<Pair<String, () -> Unit>> = when {
                working?.question != null -> listOf(str(Res.string.maps_btn_wifi_only) to cb.onWifiOnly, str(Res.string.maps_btn_load_now) to cb.onMobileYes)
                // "Maps only on Wi-Fi": areas wait – once over mobile data, the setting stays (owner)
                working?.wifiNote != null -> listOf(MapLoadUi.LOAD_ON_MOBILE to cb.onMobileYes)
                else -> listOf(str(Res.string.maps_btn_allow_location) to cb.onAllowLocation)
            }
            // the Wi-Fi note can be hidden (the areas keep waiting); a question is answered with its buttons
            Notice(title, warning = false, onClose = if (working != null && working.question == null) cb.onClose else null, buttons, modifier)
        }
    }
}

/** The app opened where there is no detailed map: the strip asks instead of loading (e.g. in a car – owner). */
@Composable
fun HereAskStrip(regions: Int, bytes: Long, onLoad: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier,
                 mustBuild: Boolean = true) =
    // short (owner): title ≤ 5 words, one detail line at most
    Notice(str(Res.string.maps_here_ask, mb(bytes)),
        warning = false, onClose = null, listOf(str(Res.string.common_not_now) to onNotNow, str(Res.string.maps_btn_load) to onLoad), modifier,
        detail = if (mustBuild) str(Res.string.maps_here_ask_detail) else null)

/**
 * A note the user must see once at the top of the map, with OK (owner allows this one blue strip without a question,
 * e.g. "Paired. …"): same look as the map strips, stays until OK, the map below stays usable.
 */
@Composable
fun OkNoteStrip(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onOk: () -> Unit, modifier: Modifier = Modifier) =
    Notice(title, warning = false, onClose = null, listOf(str(Res.string.common_ok) to onOk), modifier, icon = icon)

/** A blue ACTION strip at the top of the map: a question / one step with its buttons (last = main), e.g. the pairing step. */
@Composable
fun ActionStrip(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, buttons: List<Pair<String, () -> Unit>>,
                modifier: Modifier = Modifier) =
    Notice(title, warning = false, onClose = null, buttons, modifier, icon = icon)

/**
 * One strip at the top: a title, an optional detail line, buttons right-aligned (last = main). Blue (ACTION) for a
 * question; amber with ⚠ (WARNING) for a failure that offers "Try again".
 */
@Composable
private fun Notice(title: String, warning: Boolean, onClose: (() -> Unit)?,
                   buttons: List<Pair<String, () -> Unit>>, modifier: Modifier, detail: String? = null,
                   icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Download) {
    val bg = if (warning) OnPathColors.WarnContainer else OnPathColors.InfoBlue.copy(alpha = 0.94f)
    val fg = if (warning) OnPathColors.OnWarnContainer else Color.White
    Surface(shape = RoundedCornerShape(12.dp), color = bg, contentColor = fg, shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = title }) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (warning) Icons.Outlined.Warning else icon, null,
                    tint = if (warning) OnPathColors.Warn else fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                // 48 dp like every other target (gloves)
                if (onClose != null) androidx.compose.material3.IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, str(Res.string.maps_hide_cd), modifier = Modifier.size(20.dp))
                } else Spacer(Modifier.width(8.dp))
            }
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.9f),
                modifier = Modifier.padding(start = 26.dp, end = 8.dp)) }
            if (buttons.isNotEmpty()) {
                NoticeButtons(buttons, if (warning) NoticeTone.AMBER else NoticeTone.BLUE,
                    Modifier.padding(top = 6.dp, end = 4.dp, bottom = 4.dp))
            } else Spacer(Modifier.height(4.dp))
        }
    }
}
