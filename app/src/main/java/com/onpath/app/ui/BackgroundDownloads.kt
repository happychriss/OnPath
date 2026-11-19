package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.getValue
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import com.onpath.app.cells.MapCells
import com.onpath.app.data.RoutingDataLoader
import com.onpath.app.domain.ActionPercent
import com.onpath.app.domain.CellProgress
import com.onpath.app.domain.PrepareProgress
import com.onpath.app.domain.StepShares
import com.onpath.app.ui.progress.BesideDetails
import com.onpath.app.ui.progress.BesideRow
import com.onpath.app.ui.progress.BesideSign

/**
 * What loads in the background right now (owner: downloads must be visible and stoppable) – form B, "ich kann
 * weitermachen" (owner 2026-11-19): map regions along the plan's route or a multi-day tour, the map ahead on a tour,
 * newer versions, routing data – anything the user is not waiting for in the wait window. [percent] = ONE number over
 * the whole action, every load of it together ([ActionPercent], each by its steps' learned shares), never back – the
 * sign, its details and the notification say the same.
 */
data class BackgroundDownloadUi(
    /** Map regions loading (never called "cells" on screen). */
    val regions: Int,
    /** Routing data parts loading. */
    val routingParts: Int,
    /** Bytes of everything loading, as far as known. */
    val bytes: Long,
    /** 0..100 over the whole action. */
    val percent: Int,
    /** Of [regions]: newer versions of areas the user already has (they replace the current map once loaded). */
    val newer: Int = 0,
    /** The server still builds some newer version: about this many minutes (null = unknown / none). */
    val newerEtaMin: Int? = null,
    /** What loads, by name ("around Bad Waldsee", "along your route to …"), most important first. */
    val groups: List<LoadGroup> = emptyList(),
    /** One line per load of the action with where it stands – the finished ones too ("fertig"). */
    val rows: List<BesideRow> = emptyList(),
) {
    /** The details' notes: when a newer map is ready, "The maps you have stay". */
    val notes: List<String> get() = listOfNotNull(
        newerEtaMin?.takeIf { newer > 0 }?.let { Txt.s(Res.string.maps_bg_newer_ready, it) },
        Txt.s(Res.string.maps_bg_maps_stay))
}

/**
 * Progress of one background loading action, pure: its loads ([ActionPercent.Item]) seen since the action began count
 * as done once they leave; the action ends when nothing loads. The names seen stay as lines ("fertig") until then.
 * Unit-tested.
 */
class BackgroundProgress(private val action: ActionPercent = BackgroundAction.shared) {
    private val seen = LinkedHashMap<String, BesideRow>()

    fun update(active: List<ActionPercent.Item>, regions: Int, routingParts: Int, newer: Int = 0, newerEtaMin: Int? = null,
               groups: List<LoadGroup> = emptyList()): BackgroundDownloadUi? {
        val pct = action.update(active)
        if (pct == null) { seen.clear(); return null }
        val now = groups.associateBy { it.name }
        for (k in seen.keys.toList()) if (k !in now) seen[k] = BesideRow(k, Txt.s(Res.string.maps_phase_done))
        for ((k, g) in now) seen[k] = BesideRow(k, g.phase.text)
        return BackgroundDownloadUi(regions, routingParts, active.sumOf { it.weight }, pct, newer, newerEtaMin, groups, seen.values.toList())
    }

    companion object {
        /** A region or part of unknown size (server not asked yet). */
        const val ESTIMATE_BYTES = ActionPercent.UNKNOWN_WEIGHT

        /** A map region in one of these states is loading (asked, built, downloaded or joined right now). */
        fun loading(s: CellState): Boolean = s == CellState.Requested || s is CellState.Queued || s is CellState.Building ||
            s is CellState.Downloading || s == CellState.Installing

        /**
         * The background loads of the cell [states] (those in [waitedFor] are the wait window's, not the sign's): each
         * where it stands over its own steps ([CellProgress], the learned [shares]; an area still prepared by the
         * server's time left at [now] – [prepare]; [joining] = the pieces in of the areas joined now). An [installed]
         * area only asked for a newer version ([CellState.Requested]: the server has not answered) is no loading yet.
         */
        fun cellItems(
            states: Map<CellId, CellState>, waitedFor: Set<CellId>, installed: Set<CellId> = emptySet(),
            shares: StepShares = StepShares.DEFAULT, joining: MapCells.Joining? = null,
            prepare: PrepareProgress? = null, now: Long = 0L, size: (CellId) -> Long?,
        ): List<ActionPercent.Item> {
            val on = states.filter { (c, s) -> c !in waitedFor && loading(s) && !(c in installed && s == CellState.Requested) }
            prepare?.keep(on.keys.map { "cell:${it.id}" }.toSet())
            return on.map { (c, s) ->
                val key = "cell:${c.id}"
                val total = (s as? CellState.Downloading)?.totalBytes?.takeIf { it > 0 } ?: size(c) ?: 0L
                ActionPercent.Item(key, total, CellProgress.fraction(s, shares, joining?.takeIf { c in it.cells }?.fraction ?: 0.0,
                    prepare?.fraction(key, s, now)))
            }
        }

        /** Routing data loading: its bytes, then the rewrite of its file – the download and join steps' shares. */
        fun routingItem(r: RoutingDataLoader.Status, shares: StepShares = StepShares.DEFAULT): ActionPercent.Item {
            val d = shares.download / (shares.download + shares.join)
            val bytes = if (r.totalBytes > 0) (r.doneBytes.toDouble() / r.totalBytes).coerceIn(0.0, 1.0) else 0.0
            return ActionPercent.Item("routing", r.totalBytes, if (r.writing) d + (1 - d) * r.written else d * bytes)
        }
    }
}

/** The one action of the app: the sign beside the map and the notification feed the same, so they say the same number. */
object BackgroundAction {
    val shared = ActionPercent()
    /** The areas the server prepares: their progress by its time left – the same for the sign and the notification. */
    val prepare = PrepareProgress()
}

/**
 * The sign beside the map (form B): a ring and the action's percent, nothing else (owner 2026-11-19). Plan: under the ☰
 * button; Navigate: under the status card; Settings → Offline maps. Tap → the details ([BackgroundDownloadSheet]).
 */
@Composable
fun BackgroundDownloadChip(ui: BackgroundDownloadUi, onClick: () -> Unit, modifier: Modifier = Modifier) =
    BesideSign(ui.percent, onClick, modifier)

/**
 * Tap on the sign: the ONE bar of the action, what loads (one line each, the finished ones "fertig"), and "Cancel all"
 * (same as Settings → Offline maps; installed maps stay) – a bottom sheet (owner 2026-10-31: the details of a state are a
 * sheet, dialogs only for decisions). Drag down or Close closes it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BackgroundDownloadSheet(ui: BackgroundDownloadUi, onCancelAll: () -> Unit, onClose: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose, containerColor = OnPathColors.Paper) {
        BackgroundDownloadDetailsContent(ui, onCancelAll, onClose)
    }
}

/** The sheet's content (also drawn by the screenshot tests). */
@Composable
fun BackgroundDownloadDetailsContent(ui: BackgroundDownloadUi, onCancelAll: () -> Unit, onClose: () -> Unit) =
    BesideDetails(str(Res.string.maps_bg_dialog_title), ui.percent, ui.rows, ui.notes, onCancelAll, onClose)

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
            while (true) { value = vm.mapData.backgroundDownloadUi(states); kotlinx.coroutines.delay(1000) }
        }
    }
    val gate = androidx.compose.runtime.remember { backgroundGate() }
    return rememberNoFlash(target, gate)
}

/** The details sheet for [ui] while the user opened it (chip tap). */
@androidx.compose.runtime.Composable
internal fun BackgroundDownloadDetails(vm: AppViewModel, ui: BackgroundDownloadUi?) {
    if (!vm.mapData.downloadDetailsOpen) return
    if (ui == null) { androidx.compose.runtime.LaunchedEffect(Unit) { vm.mapData.downloadDetailsOpen = false }; return }
    BackgroundDownloadSheet(ui, onCancelAll = vm.mapData::cancelAllDownloads, onClose = { vm.mapData.downloadDetailsOpen = false })
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
