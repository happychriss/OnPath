package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState

/**
 * The one modal while the user waits for the map of their area: a point set / searched / tapped where there is no
 * detailed map (T1), a route computed while the visible map has none ([MapLoadWait]). Plain words, the time left, one
 * button; each phase shows its own progress (owner 2026-11-18: a % that races and then stands at 98 looks hung):
 * preparing on the server and joining the map in have no measurable progress – the title says what happens and the bar
 * runs, no number; only the download has a % – the real bytes. Closes by itself only when the map is there; Cancel stops
 * it ([MapLoadCancel]).
 * Background loads (the map for here when the app opens – also the first start –, route corridor, on tour, newer
 * versions) never show it.
 */
data class MapLoadUi(
    val kind: Kind,
    /**
     * 0..100: the bytes downloaded of everything this window waits for – real bytes, never a guess, never backwards.
     * Shown only while bytes can flow ([hasPercent]); the other phases show no number.
     */
    val percent: Int,
    /** Estimated seconds left; null = unknown (no line). */
    val secondsLeft: Int?,
    /** Failure: plain reason. */
    val reason: String? = null,
    val retryable: Boolean = false,
    /** How many regions (map cells – never called that on screen) the modal waits for, and their size. */
    val regions: Int = 1,
    val bytes: Long? = null,
    /**
     * Routing data loads too (its size is in [bytes]); [regions] 0 = only routing data ([RoutingLoads]). Never named on
     * screen (it is all "the map for your area").
     */
    val routing: Boolean = false,
    /** Of [regions]: already on the phone ("2 of 4 map areas"). */
    val regionsDone: Int = 0,
) {
    /** FINISHING: everything is downloaded and is joined into the map / written into the routing file. */
    enum class Kind { PREPARING, LOADING, FINISHING, OFFLINE, WIFI, FAILED }

    val title: String get() = when (kind) {
        Kind.PREPARING -> Txt.s(Res.string.maps_load_preparing)
        Kind.FINISHING -> Txt.s(Res.string.maps_load_finishing)
        Kind.FAILED -> Txt.s(Res.string.maps_load_failed)
        else -> Txt.s(Res.string.maps_load_loading)
    }

    /**
     * A bar with a number: while it downloads, and while a download waits (offline / Wi-Fi: the bar stands, grey, the
     * line says why). Preparing and finishing have no measurable progress: the bar runs, no number.
     */
    val hasPercent: Boolean get() = kind == Kind.LOADING || kind == Kind.OFFLINE || kind == Kind.WIFI

    /** The line under the bar: time left, or why it waits. */
    val line: String? get() = when (kind) {
        Kind.OFFLINE -> OFFLINE_TEXT
        Kind.WIFI -> WIFI_TEXT
        Kind.FAILED -> reason
        Kind.FINISHING -> null
        else -> secondsLeft?.let { timeLeft(it) }
    }

    val paused: Boolean get() = kind == Kind.OFFLINE || kind == Kind.WIFI

    /** "3 map areas · about 27 MB" (routing data included, not named); only routing data missing: "about 10 MB". */
    val totals: String get() = if (regions == 0) bytes?.let { Txt.s(Res.string.maps_about_mb, mb(it)) } ?: ""
        // several areas, some there: "2 of 4 map areas · about 27 MB" – the count moves while a bar cannot
        else if (regions > 1 && regionsDone in 1 until regions && bytes != null)
            Txt.s(Res.string.maps_load_totals_done, regionsDone, regions, mb(bytes))
        else if (bytes == null) Txt.p(Res.plurals.maps_load_totals, regions, regions)
        else Txt.p(Res.plurals.maps_load_totals_mb, regions, regions, mb(bytes))

    companion object {
        val OFFLINE_TEXT: String get() = Txt.s(Res.string.maps_offline_text)
        val WIFI_TEXT: String get() = Txt.s(Res.string.maps_wifi_text)
        /** One-time load over mobile data while "Maps only on Wi-Fi" waits (modal and question box; setting unchanged). */
        val LOAD_ON_MOBILE: String get() = Txt.s(Res.string.maps_load_on_mobile)

        fun timeLeft(s: Int): String = when {
            s < 60 -> Txt.s(Res.string.maps_time_lt_minute)
            s < 120 -> Txt.s(Res.string.maps_time_one_minute)
            else -> Txt.s(Res.string.maps_time_minutes, (s + 30) / 60)
        }
    }
}

/**
 * One map region on its way, from the server / download states, updated about every second – each phase its own
 * progress (owner 2026-11-18): preparing (queued + building) has the server's time left and no %; the download has the
 * real bytes (0–100 %); joining it into the map ([CellState.Installing]) has neither. Pure (time passed in), unit-tested.
 */
class MapLoadProgress(private val defaultBuildS: Int = DEFAULT_BUILD_S) {
    /** Bytes downloaded in % – stays where it was while offline / waiting. */
    private var shown = 0
    private var last: CellState? = null
    /** When the preparing state last changed, and the server's time left then. */
    private var anchorAt = 0L
    private var anchorRemainS = defaultBuildS.toDouble()
    private var preparing = false
    /** Download rate measurement. */
    private var dlAt: Long? = null
    private var dlBytes = 0L

    fun update(s: CellState, now: Long): MapLoadUi {
        val changed = s != last
        last = s
        return when (s) {
            CellState.None, CellState.Requested, is CellState.Queued, is CellState.Building -> {
                val eta = when (s) { is CellState.Queued -> s.etaS; is CellState.Building -> s.etaS; else -> null }
                // a new estimate from the server re-anchors; without one the first estimate runs down
                if (!preparing || (changed && eta != null)) { anchorAt = now; anchorRemainS = (eta ?: defaultBuildS).coerceAtLeast(1).toDouble() }
                preparing = true
                val left = (anchorRemainS - (now - anchorAt) / 1000.0).coerceAtLeast(0.0).toInt()
                MapLoadUi(MapLoadUi.Kind.PREPARING, shown, left)
            }
            is CellState.Downloading -> {
                preparing = false
                if (s.totalBytes > 0) shown = maxOf(shown, (s.doneBytes * 100 / s.totalBytes).toInt().coerceIn(0, 100))
                val start = dlAt
                if (start == null || s.doneBytes < dlBytes) { dlAt = now; dlBytes = s.doneBytes }
                val dt = (now - (dlAt ?: now)) / 1000.0
                val rate = if (dt >= 2 && s.doneBytes > dlBytes) (s.doneBytes - dlBytes) / dt else null
                val left = if (rate != null && s.totalBytes > 0) ((s.totalBytes - s.doneBytes) / rate).toInt() else null
                MapLoadUi(MapLoadUi.Kind.LOADING, shown, left)
            }
            // all bytes are here; the map is joined in – no progress to measure, so no number
            CellState.Installing -> { preparing = false; shown = 100; MapLoadUi(MapLoadUi.Kind.FINISHING, 100, null) }
            // downloaded during a tour: on the phone, joined after it – as far as the loading window goes, it is done
            CellState.Downloaded -> { shown = 100; MapLoadUi(MapLoadUi.Kind.LOADING, 100, 0) }
            is CellState.Ready -> { shown = 100; MapLoadUi(MapLoadUi.Kind.LOADING, 100, 0) }
            is CellState.Offline -> MapLoadUi(MapLoadUi.Kind.OFFLINE, shown, null)
            is CellState.WaitingForWifi -> MapLoadUi(MapLoadUi.Kind.WIFI, shown, null)
            is CellState.Failed -> MapLoadUi(MapLoadUi.Kind.FAILED, shown, null,
                reason = if (s.retryable) Txt.s(Res.string.maps_load_failed_retry) else s.message,
                retryable = s.retryable)
        }
    }

    companion object {
        /** Assumed preparing time when the server gives no ETA. */
        const val DEFAULT_BUILD_S = 180
    }
}

/**
 * The modal for several regions at once ("N regions · about X MB"): one bar over all of them. Pure, unit-tested.
 */
object MapLoads {
    /** "2 regions · ≈ 18 MB" (the tap question). */
    fun askText(regions: Int, bytes: Long): String = Txt.p(Res.plurals.maps_ask_text, regions, regions, mb(bytes))

    /** Size of a region when the server has not said it yet. */
    const val ESTIMATED_REGION_BYTES = 9L * 1_048_576

    /** Known size of a region (installed, downloading, waiting for Wi-Fi), else null. */
    fun knownBytes(s: CellState?, installed: Long?): Long? = installed
        ?: (s as? CellState.Downloading)?.totalBytes?.takeIf { it > 0 }
        ?: (s as? CellState.WaitingForWifi)?.bytes

    /** Total size: known sizes, [ESTIMATED_REGION_BYTES] for the rest. */
    fun estimate(cells: Collection<CellId>, states: Map<CellId, CellState>, sizes: Map<CellId, Long>): Long =
        cells.sumOf { knownBytes(states[it], sizes[it]) ?: ESTIMATED_REGION_BYTES }

    /**
     * One [MapLoadUi] for the regions [focus]: [progress] holds one [MapLoadProgress] per region (kept by the caller),
     * [sizes] the installed regions' sizes. The % is the bytes downloaded over all regions (a region on the phone
     * counts in full, one still prepared with 0), never below [held] (the last value shown). The kind is the phase:
     * LOADING while a region downloads, else PREPARING while the server works on one, else FINISHING (all downloaded,
     * joined into the map). Null when every region is there.
     */
    fun aggregate(
        focus: Collection<CellId>, states: Map<CellId, CellState>, sizes: Map<CellId, Long>,
        progress: MutableMap<CellId, MapLoadProgress>, held: Int, now: Long,
    ): MapLoadUi? {
        if (focus.isEmpty()) return null
        fun ready(c: CellId) = states[c] is CellState.Ready || (states[c] == null && c in sizes)
        val open = focus.filter { !ready(it) }
        if (open.isEmpty()) return null
        val per = open.map { c -> progress.getOrPut(c) { MapLoadProgress() }.update(states[c] ?: CellState.Requested, now) }
        val total = focus.size
        val bytes = estimate(focus, states, sizes)
        fun size(c: CellId) = knownBytes(states[c], sizes[c]) ?: ESTIMATED_REGION_BYTES
        val got = focus.sumOf { c -> if (ready(c)) size(c) else size(c) * per[open.indexOf(c)].percent / 100 }
        val pct = maxOf(held, if (bytes > 0) (got * 100 / bytes).toInt() else 0).coerceIn(0, 100)
        val there = total - open.size
        val kinds = per.map { it.kind }
        val left = per.mapNotNull { it.secondsLeft }.maxOrNull()
        return when {
            kinds.all { it == MapLoadUi.Kind.FAILED } ->
                per.first().copy(percent = pct, regions = total, bytes = bytes, retryable = per.any { it.retryable }, regionsDone = there)
            MapLoadUi.Kind.OFFLINE in kinds -> MapLoadUi(MapLoadUi.Kind.OFFLINE, pct, null, regions = total, bytes = bytes, regionsDone = there)
            MapLoadUi.Kind.LOADING in kinds -> MapLoadUi(MapLoadUi.Kind.LOADING, pct, left, regions = total, bytes = bytes, regionsDone = there)
            MapLoadUi.Kind.PREPARING in kinds -> MapLoadUi(MapLoadUi.Kind.PREPARING, pct, left, regions = total, bytes = bytes, regionsDone = there)
            MapLoadUi.Kind.FINISHING in kinds -> MapLoadUi(MapLoadUi.Kind.FINISHING, pct, null, regions = total, bytes = bytes, regionsDone = there)
            else -> MapLoadUi(MapLoadUi.Kind.WIFI, pct, null, regions = total, bytes = bytes, regionsDone = there)
        }
    }
}

/**
 * When the user waits for a map job in the modal. Pure, unit-tested.
 *
 * Field bug 2026-11-08 (owner, fresh install): the location granted in the phone setup gave the first fix, the map for
 * here started loading (T3) and – the empty world map counting as "the view has no detailed map" – the modal opened
 * over the setup guide: the user had to wait inside the onboarding. The map for here (opening the app, the first start
 * too) loads in the background only; and no modal ever covers the phone setup.
 */
object MapLoadWait {
    /**
     * [job] opens / joins the modal: the user's own point / search / tap ([CellJob.own]), or a job loaded while the
     * visible map has no detailed map – but never the map for here ([CellWhy.HERE]: the user only opened the app).
     */
    fun waits(job: com.onpath.app.cells.CellJob, viewWithoutDetail: Boolean): Boolean =
        job.own != null || (viewWithoutDetail && job.why != com.onpath.app.cells.CellWhy.HERE)

    /** The modal to show: none while the phone setup (welcome page / guide) is open – the map loads on behind it. */
    fun shown(ui: MapLoadUi?, setupOpen: Boolean): MapLoadUi? = if (setupOpen) null else ui
}

/**
 * Modal → Cancel: **Cancel = stop** (owner). The regions the modal waited for stop loading (installed ones stay; the
 * server may finish its build – the next request is then quick), no card follows for that job, the searched place's
 * ring goes when a search / shared place started it and its map is still missing (map there = the ring stays), a point
 * the user set stays in the plan, the map stays where it is.
 * Setting a point or searching there again loads anew. Pure, unit-tested.
 */
object MapLoadCancel {
    data class Outcome(
        /** Regions to drop from the queue (not installed). */
        val stop: List<CellId>,
        /** Remove the candidate marker (searched / shared place). */
        val clearSearchMark: Boolean,
        /** The plan sheet's card for this job goes too (no "waiting for Wi-Fi" afterwards). */
        val closeCard: Boolean,
    )

    /**
     * [waitedFor]: the modal's regions; [job] / [jobWaited]: the card's job and whether the modal waited for it;
     * [installed]: regions on the phone; [bySearch]: a search / shared place opened (or joined) the modal.
     */
    fun decide(
        waitedFor: Set<CellId>, job: com.onpath.app.cells.CellJob?, jobWaited: Boolean, installed: Set<CellId>,
        bySearch: Boolean,
    ): Outcome {
        val closeCard = job != null && jobWaited
        val all = LinkedHashSet<CellId>(waitedFor)
        if (closeCard) all += job!!.cells
        // the ring goes only when Cancel really stops the searched place's map (field 2026-11-07, S10: the map for the
        // place was there, the window still showed – Cancel then took the destination too). The map is there / only
        // routing data was waited for: the found place stays until the user acts on it.
        val stopsItsMap = waitedFor.any { it !in installed }
        return Outcome(all.filter { it !in installed }, bySearch && stopsItsMap, closeCard)
    }
}

class MapLoadCallbacks(
    val onCancel: () -> Unit = {}, val onRetry: () -> Unit = {}, val onClose: () -> Unit = {},
    /** "Load now over mobile data" while it waits for Wi-Fi (once; the setting stays). */
    val onLoadOnMobile: () -> Unit = {},
)

/**
 * The modal over the map. [ui] null = nothing to wait for. No flash (owner rule): it appears only when the wait lasts
 * ≥ 1 s, a title stays ≥ 2.5 s (the bar keeps moving), a tap answers at once ([NoFlash]).
 */
@Composable
fun MapLoadModal(ui: MapLoadUi?, cb: MapLoadCallbacks) {
    val gate = remember { NoFlash<MapLoadUi.Kind>(urgent = { it == MapLoadUi.Kind.FAILED }) }
    val lastRef = remember { arrayOfNulls<MapLoadUi>(1) }
    if (ui != null) lastRef[0] = ui
    val kind = rememberNoFlash(ui?.kind, gate) ?: return
    val shown = (ui ?: lastRef[0])?.copy(kind = kind) ?: return
    fun acted(f: () -> Unit): () -> Unit = { gate.userActed(android.os.SystemClock.uptimeMillis()); f() }
    androidx.compose.ui.window.Dialog(onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        MapLoadPanel(shown, MapLoadCallbacks(acted(cb.onCancel), acted(cb.onRetry), acted(cb.onClose), acted(cb.onLoadOnMobile)))
    }
}

/** The modal's content (also used by the screenshot tests, which cannot show a Dialog window). */
@Composable
fun MapLoadPanel(ui: MapLoadUi, cb: MapLoadCallbacks) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = OnPathShapes.Card, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().warmShadow(OnPathShadow.HIGH, OnPathShapes.Card)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val (icon, tint) = when (ui.kind) {
                MapLoadUi.Kind.FAILED -> Icons.Outlined.Warning to OnPathColors.Warn
                MapLoadUi.Kind.OFFLINE -> Icons.Outlined.CloudOff to variant
                else -> Icons.Outlined.CloudDownload to OnPathColors.Green
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp))
            Text(ui.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(ui.totals, style = MaterialTheme.typography.bodyMedium, color = variant)
            if (ui.kind != MapLoadUi.Kind.FAILED) {
                // the download: the real bytes with their number; preparing / finishing: the bar runs, no number
                if (ui.hasPercent) Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { ui.percent / 100f },
                        color = if (ui.paused) variant else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).clip(CircleShape))
                    Text("${ui.percent} %", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 12.dp))
                } else LinearProgressIndicator(color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CircleShape))
            }
            ui.line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = variant) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (ui.kind == MapLoadUi.Kind.FAILED) {
                    TextButton(onClick = cb.onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_close)) }
                    if (ui.retryable) TextButton(onClick = cb.onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_try_again)) }
                } else {
                    TextButton(onClick = cb.onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) }
                    if (ui.kind == MapLoadUi.Kind.WIFI) TextButton(onClick = cb.onLoadOnMobile, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(MapLoadUi.LOAD_ON_MOBILE)
                    }
                }
            }
        }
    }
}

/** A tap on the world map where there is no detailed map: never a silent download from a tap (owner). */
@Composable
fun LoadAskDialog(regions: Int, bytes: Long, onLoad: () -> Unit, onNotNow: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(str(Res.string.maps_ask_title)) },
        text = { DialogText(MapLoads.askText(regions, bytes)) },
        confirmButton = { TextButton(onClick = onLoad, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.maps_btn_load)) } },
        dismissButton = { TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_not_now)) } },
    )
}
