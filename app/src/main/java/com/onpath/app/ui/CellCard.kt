package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellJob
import com.onpath.app.cells.CellState
import com.onpath.app.cells.CellWhy
import com.onpath.app.cells.label
import kotlin.math.atan2

/**
 * What happens with the detailed map of the area the user plans in (docs/design/map-cells.md §9). Shown in the blue strip
 * at the top ([mapNotice] / [MapNoticeStrip] in BackgroundDownloads.kt), not in the plan sheet (owner): Load now / Only
 * on Wi-Fi for mobile data, Try again / Not now when it failed, × to hide.
 */
sealed interface CellCardUi {
    val title: String

    data object NotAvailable : CellCardUi {
        override val title get() = Txt.s(Res.string.maps_card_not_available)
    }

    data class Working(
        override val title: String,
        val why: String?,
        val rows: List<CellRow>,
        /** "2 more areas · waiting" when there are more cells than rows. */
        val more: String?,
        /** 0..1 when downloading, null otherwise. */
        val progress: Float?,
        val question: MobileQuestion?,
        /** "3 map areas wait for Wi-Fi · 25 MB" when the question was answered or Settings says Wi-Fi only. */
        val wifiNote: String?,
        /**
         * With [wifiNote]: "Load now" over mobile data is offered (Settings says Wi-Fi only). The user who just answered
         * "Wi-Fi only" gets a quiet grey pill without a choice.
         */
        val canLoadNow: Boolean = true,
    ) : CellCardUi

    data class Offline(override val title: String, val why: String?) : CellCardUi

    data class Failed(override val title: String, val why: String?, val retryable: Boolean) : CellCardUi

    /**
     * Fresh install, no detailed map anywhere yet, no position to fetch it for (no permission / no fix): what brings the
     * map. Stays until a position or a point starts the download (T3 / T1) or the user hides it.
     */
    data class NeedPosition(val permission: Boolean) : CellCardUi {
        override val title get() = if (permission) WAITING else ALLOW
        companion object {
            /** A question (blue, "Allow location"). */
            val ALLOW: String get() = Txt.s(Res.string.maps_need_position_allow)
            /** A status (grey pill). */
            val WAITING: String get() = Txt.s(Res.string.maps_need_position_waiting)
        }
    }

    data class Done(val areas: Int, val bytes: Long) : CellCardUi {
        override val title get() = Txt.p(Res.plurals.maps_card_done, areas, areas, mb(bytes))
    }
}

data class CellRow(val name: String, val state: String, val own: Boolean)

/** "Use mobile data? · 3 map areas, 25 MB" · Wi-Fi only · Load now. */
data class MobileQuestion(val areas: Int, val bytes: Long?) {
    val text: String get() = if (bytes == null) Txt.p(Res.plurals.maps_mobile_question, areas, areas)
        else Txt.p(Res.plurals.maps_mobile_question_mb, areas, areas, mb(bytes))
}

/** Whole MB, at least 1 for anything non-empty. */
internal fun mb(bytes: Long): Long = if (bytes <= 0) 0 else maxOf(1, (bytes + 524_288) / 1_048_576)

/** Pure: card content from the trigger's job and the cell states (unit-tested). */
object CellCards {
    const val MAX_ROWS = 3
    val OFFLINE_TITLE: String get() = Txt.s(Res.string.maps_card_offline)

    /**
     * The first-start card ([CellCardUi.NeedPosition]) when there is no other card ([job] null): downloads [available],
     * no detailed map anywhere ([noDetailedMap]: no installed cell, no region package), T3 still waiting for a
     * position ([waitingForPosition]) and not hidden by the user. Pure, unit-tested.
     */
    fun firstStart(
        job: CellJob?, available: Boolean, noDetailedMap: Boolean, waitingForPosition: Boolean, hidden: Boolean,
        permission: Boolean,
    ): CellCardUi.NeedPosition? =
        if (job == null && available && noDetailedMap && waitingForPosition && !hidden) CellCardUi.NeedPosition(permission) else null

    /** Why an area loads – for the events trace / Diagnostics only; the strip shows no "why" line. */
    fun why(w: CellWhy): String = when (w) {
        CellWhy.PLANNED -> "Getting the detailed map because you planned here."
        CellWhy.HERE -> "Getting the detailed map because you are here."
        CellWhy.UPDATE -> "Updating the map for the new map style (small streets)."
        CellWhy.SEARCHED -> "Getting the detailed map because you searched here."
        CellWhy.TAPPED -> "Getting the detailed map you asked for."
    }

    /**
     * [sizes]: bytes of installed cells; [mobileDeclined]: the user answered "Only on Wi-Fi"; [wifiOnly]: Settings.
     * Returns null when there is nothing to show.
     */
    fun build(
        job: CellJob?, states: Map<CellId, CellState>, sizes: Map<CellId, Long>,
        available: Boolean, wifiOnly: Boolean, mobileDeclined: Boolean,
    ): CellCardUi? {
        if (job == null || job.cells.isEmpty()) return null
        if (!available) return CellCardUi.NotAvailable
        fun st(c: CellId): CellState = states[c]?.takeIf { it != CellState.None }
            ?: if (c in sizes) CellState.Ready("") else CellState.None
        val all = job.cells
        val s = all.associateWith { st(it) }
        val ready = all.filter { s[it] is CellState.Ready }
        val why = why(job.why)
        if (ready.size == all.size) return CellCardUi.Done(all.size, ready.sumOf { sizes[it] ?: 0L })
        val open = all.filter { s[it] !is CellState.Ready && s[it] !is CellState.Failed }
        val failed = all.filter { s[it] is CellState.Failed }
        if (open.isEmpty() && failed.isNotEmpty()) {
            val f = s[failed.first()] as CellState.Failed
            val title = if (failed.size == 1) f.message else Txt.s(Res.string.maps_card_failed_many, failed.size, f.message)
            return CellCardUi.Failed(title, why, failed.any { (s[it] as CellState.Failed).retryable })
        }
        // only waiting for the network (no download running): one plain line
        val busy = open.filter { s[it] !is CellState.Offline }
        if (busy.isEmpty() && open.isNotEmpty()) {
            val msg = (s[open.first()] as CellState.Offline).message
            // the "no connection" text of the map download (cells/MapCells, CellApi) – compared as a whole, in any language
            return CellCardUi.Offline(if (msg == Txt.s(Res.string.maps_err_no_connection)) OFFLINE_TITLE else msg, why)
        }
        val downloading = open.mapNotNull { s[it] as? CellState.Downloading }
        val installing = open.any { s[it] == CellState.Installing }
        val waiting = open.filter { s[it] is CellState.WaitingForWifi }
        val waitBytes = waiting.mapNotNull { (s[it] as CellState.WaitingForWifi).bytes }.takeIf { it.size == waiting.size }?.sum()
        val title = when {
            downloading.isNotEmpty() || installing -> {
                val done = ready.sumOf { sizes[it] ?: 0L } + downloading.sumOf { it.doneBytes }
                Txt.s(Res.string.maps_card_downloading, minOf(ready.size + 1, all.size), all.size, mb(done))
            }
            waiting.size == open.size -> Txt.s(Res.string.maps_card_ready_to_download)
            else -> {
                val eta = open.mapNotNull { when (val x = s[it]) { is CellState.Queued -> x.etaS; is CellState.Building -> x.etaS; else -> null } }.maxOrNull()
                if (eta == null) Txt.s(Res.string.maps_card_preparing)
                else Txt.s(Res.string.maps_card_preparing_min, maxOf(1, (eta + 59) / 60))
            }
        }
        val progress = downloading.firstOrNull { it.totalBytes > 0 }?.let { it.doneBytes.toFloat() / it.totalBytes }
        val ordered = all.sortedBy { if (it == job.own) 0 else 1 }
        val rows = ordered.take(MAX_ROWS).mapIndexed { i, c -> CellRow(name(c, job, i), rowState(s[c]!!), c == job.own) }
        val rest = ordered.drop(MAX_ROWS)
        val more = if (rest.isEmpty()) null else {
            val restReady = rest.count { s[it] is CellState.Ready }
            when {
                restReady == rest.size -> Txt.p(Res.plurals.maps_card_more_ready, rest.size, rest.size)
                restReady > 0 -> Txt.p(Res.plurals.maps_card_more_some_ready, rest.size, rest.size, restReady)
                else -> Txt.p(Res.plurals.maps_card_more_waiting, rest.size, rest.size)
            }
        }
        val waitNote = if (waiting.isEmpty()) null else if (waitBytes == null) Txt.p(Res.plurals.maps_card_wait_wifi, waiting.size, waiting.size)
            else Txt.p(Res.plurals.maps_card_wait_wifi_mb, waiting.size, waiting.size, mb(waitBytes))
        val question = if (waiting.isNotEmpty() && !wifiOnly && !mobileDeclined) MobileQuestion(waiting.size, waitBytes) else null
        return CellCardUi.Working(title, why, rows, more, progress, question, if (question == null) waitNote else null,
            canLoadNow = wifiOnly)
    }

    /**
     * What counts as "another message" for the no-flash gate ([NoFlash]): the kind of card, for a working card its step
     * (preparing / downloading / ready to download) and whether a question or Wi-Fi note is in it; progress, MB counts
     * and rows are updates of the same step.
     */
    fun stepKey(ui: CellCardUi): Any = when (ui) {
        is CellCardUi.Working -> listOf("working", ui.title.substringBefore(' '), ui.question != null, ui.wifiNote != null)
        is CellCardUi.NeedPosition -> listOf("need", ui.permission)
        is CellCardUi.Offline -> "offline"
        is CellCardUi.Failed -> listOf("failed", ui.title)
        is CellCardUi.Done -> "done"
        CellCardUi.NotAvailable -> "na"
    }

    /** Cards that wait for the user show at once (a question, a failure, "Map ready", "not available"). */
    fun waitsForUser(ui: CellCardUi): Boolean = ui is CellCardUi.Failed || ui is CellCardUi.Done ||
        ui == CellCardUi.NotAvailable || (ui is CellCardUi.Working && ui.question != null)

    private fun rowState(s: CellState): String = when (s) {
        is CellState.Ready -> Txt.s(Res.string.maps_row_ready)
        CellState.None -> Txt.s(Res.string.maps_state_waiting)
        else -> s.label()
    }

    /** "Your point" / "Your position" for the own cell, "Area to the north-east" for a neighbour, "Map area 2" else. */
    internal fun name(c: CellId, job: CellJob, index: Int): String {
        val own = job.own
        if (c == own) return Txt.s(if (job.why == CellWhy.HERE) Res.string.maps_row_your_position else Res.string.maps_row_your_point)
        if (own == null) return Txt.s(Res.string.maps_row_area_n, index + 1)
        val dx = (c.x - own.x).toDouble(); val dy = (own.y - c.y).toDouble()
        val dirs = listOf(Res.string.maps_row_area_east, Res.string.maps_row_area_north_east, Res.string.maps_row_area_north,
            Res.string.maps_row_area_north_west, Res.string.maps_row_area_west, Res.string.maps_row_area_south_west,
            Res.string.maps_row_area_south, Res.string.maps_row_area_south_east)
        val a = Math.toDegrees(atan2(dy, dx)).let { if (it < 0) it + 360 else it }
        return Txt.s(dirs[((a + 22.5) / 45).toInt() % 8])
    }
}

class CellCardCallbacks(
    val onMobileYes: () -> Unit = {},
    /** Overlay "Cancel": stop loading these areas (installed ones stay; the next point set there asks again). */
    val onCancel: () -> Unit = {},
    val onWifiOnly: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** OK on "Map ready", Not now on a failure, OK on "not available", × while loading (downloads go on). */
    val onClose: () -> Unit = {},
    /** "Allow location" on the first-start card (system permission dialog). */
    val onAllowLocation: () -> Unit = {},
)

/** Small persistent hint on the map where only the overview exists (z ≥ 10, Plan). */
@Composable
fun OverviewHint(modifier: Modifier = Modifier) = QuietPill(str(Res.string.maps_overview_hint), Icons.Outlined.Map, modifier = modifier)

/**
 * The quiet grey pill under the search bar: a status with nothing to decide ("Overview only – …",
 * "No detailed map – loads when online", "Waiting for GPS …"). No ×: it goes when the state goes. [action]: one small
 * text button for the rare status that still needs an OK.
 */
@Composable
fun QuietPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier,
              action: Pair<String, () -> Unit>? = null) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        modifier = modifier.androidxSemantics(text)) {
        Row(Modifier.padding(start = 12.dp, end = if (action == null) 12.dp else 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false))
            action?.let { (label, act) -> Spacer(Modifier.width(8.dp)); NoticeButton(label, act, NoticeTone.LIGHT) }
        }
    }
}

private fun Modifier.androidxSemantics(text: String) =
    this.then(Modifier.semantics(mergeDescendants = true) { contentDescription = text })

/**
 * When the "Overview only" hint shows (Plan, z ≥ [MIN_ZOOM]): when a large part of the visible map has no detailed
 * map – not just the centre (the centre can lie in a downloaded area while half the screen is overview only). The view
 * is sampled on a [GRID] × [GRID] grid. Pure, unit-tested.
 */
object OverviewHintRule {
    const val MIN_ZOOM = 10.0
    const val GRID = 5
    /** Share of the sampled points without detailed map from which the hint shows. */
    const val MIN_SHARE = 0.3

    /** Sample points (lat, lon) over the box, cell centres of a [GRID] × [GRID] grid. */
    fun samples(south: Double, west: Double, north: Double, east: Double): List<Pair<Double, Double>> =
        (0 until GRID).flatMap { i -> (0 until GRID).map { j ->
            (south + (north - south) * (i + 0.5) / GRID) to (west + (east - west) * (j + 0.5) / GRID)
        } }

    /** [detailed]: per sample point whether the detailed map covers it. */
    fun show(zoom: Double, detailed: List<Boolean>): Boolean =
        zoom >= MIN_ZOOM && detailed.isNotEmpty() && detailed.count { !it } >= MIN_SHARE * detailed.size
}

/**
 * A newer version of a map area the user already has (checked after each route) – never modal; shown only through the
 * background-download strip and its details (one loading indicator), [Preparing] gives the "about N min" there.
 */
sealed interface MapUpdateUi {
    data class Loading(val progress: Float?) : MapUpdateUi
    data class Preparing(val minutes: Int?) : MapUpdateUi
}

object MapUpdates {
    /** What the installed cells' states say; null = nothing to show. Loading wins over Preparing. */
    fun of(states: Map<CellId, CellState>, installed: Set<CellId>): MapUpdateUi? {
        val mine = states.filterKeys { it in installed }.values
        val loading = mine.filter { it is CellState.Downloading || it == CellState.Installing }
        if (loading.isNotEmpty()) {
            val dl = loading.filterIsInstance<CellState.Downloading>().filter { it.totalBytes > 0 }
            val total = dl.sumOf { it.totalBytes }
            return MapUpdateUi.Loading(if (total > 0) dl.sumOf { it.doneBytes }.toFloat() / total else null)
        }
        val eta = mine.mapNotNull { s -> when (s) { is CellState.Queued -> s.etaS ?: -1; is CellState.Building -> s.etaS ?: -1; else -> null } }
        if (eta.isEmpty()) return null
        val known = eta.filter { it >= 0 }
        return MapUpdateUi.Preparing(known.maxOrNull()?.let { (it + 59) / 60 })
    }

    fun text(u: MapUpdateUi): String = when (u) {
        is MapUpdateUi.Loading -> Txt.s(Res.string.maps_update_loading)
        is MapUpdateUi.Preparing -> u.minutes?.let { Txt.s(Res.string.maps_update_preparing_min, it) }
            ?: Txt.s(Res.string.maps_update_preparing)
    }
}

