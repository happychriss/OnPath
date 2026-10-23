package com.onpath.app.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import kotlin.math.max
import kotlin.math.min

/**
 * The one modal while the user waits for the map of their area (owner 2026-10-21, field test 0.8.7): first start (T3)
 * and a point set where there is no detailed map (T1). Plain words, one bar with %, the time left, one button. Closes
 * by itself only when the map is there; Cancel stops it ([MapLoadCancel], owner 2026-10-21: "Cancel = stop").
 * Background loads (route corridor, on tour, newer versions) never show it.
 */
data class MapLoadUi(
    val kind: Kind,
    /** 0..100, never backwards, 100 only when the map is there. */
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
     * screen (owner 2026-10-22: it is all "the map for your area").
     */
    val routing: Boolean = false,
) {
    enum class Kind { PREPARING, LOADING, OFFLINE, WIFI, FAILED }

    val title: String get() = when (kind) {
        Kind.PREPARING -> "Preparing the map for your area"
        Kind.FAILED -> "The map for your area could not be loaded"
        else -> "Loading the map for your area"
    }

    /** The line under the bar: time left, or why it waits. */
    val line: String? get() = when (kind) {
        Kind.OFFLINE -> OFFLINE_TEXT
        Kind.WIFI -> WIFI_TEXT
        Kind.FAILED -> reason
        else -> secondsLeft?.let { timeLeft(it) }
    }

    val paused: Boolean get() = kind == Kind.OFFLINE || kind == Kind.WIFI

    /** "3 map areas · about 27 MB" (routing data included, not named); only routing data missing: "about 10 MB". */
    val totals: String get() = if (regions == 0) bytes?.let { "about ${mb(it)} MB" } ?: ""
        else "$regions ${if (regions == 1) "map area" else "map areas"}" + (bytes?.let { " · about ${mb(it)} MB" } ?: "")

    companion object {
        const val OFFLINE_TEXT = "No internet connection – the map will load as soon as you're online."
        const val WIFI_TEXT = "The map will load when you're on Wi-Fi (Settings: “Download maps only on Wi-Fi”)."

        fun timeLeft(s: Int): String = when {
            s < 60 -> "Less than a minute left"
            s < 120 -> "About 1 minute left"
            else -> "About ${(s + 30) / 60} minutes left"
        }
    }
}

/**
 * One overall % for the map of the user's area from the server / download states, updated about every second
 * (owner 2026-10-21): preparing (queued + building) = 0–[BUILD_END] %, using the server's progress and ETA and moving on
 * by elapsed time between polls; download = [BUILD_END]–99 % by bytes; added to the map = 100 %. Never backwards, never
 * 100 before the map is there. Pure (time passed in), unit-tested.
 */
class MapLoadProgress(private val defaultBuildS: Int = DEFAULT_BUILD_S) {
    private var shown = 0.0
    private var last: CellState? = null
    /** Interpolation anchor of the preparing phase. */
    private var anchorAt = 0L
    private var anchorP = 0.0
    private var anchorRemainS = defaultBuildS.toDouble()
    /** Download rate measurement. */
    private var dlAt: Long? = null
    private var dlBytes = 0L

    fun update(s: CellState, now: Long): MapLoadUi {
        val changed = s != last
        last = s
        return when (s) {
            CellState.None, CellState.Requested, is CellState.Queued, is CellState.Building -> {
                if (changed) {
                    val server = if (s is CellState.Building) BUILD_END * s.progress.coerceIn(0.0, 1.0) else 0.0
                    val eta = when (s) { is CellState.Queued -> s.etaS; is CellState.Building -> s.etaS; else -> null }
                    anchorAt = now
                    anchorP = max(shown, server)
                    anchorRemainS = (eta ?: defaultBuildS).coerceAtLeast(1).toDouble()
                }
                val elapsed = (now - anchorAt) / 1000.0
                val f = min(elapsed / anchorRemainS, MAX_INTERPOLATION)
                shown = min(max(shown, anchorP + (BUILD_END - anchorP) * f), BUILD_END - 1)
                val left = (anchorRemainS - elapsed).coerceAtLeast(0.0).toInt()
                MapLoadUi(MapLoadUi.Kind.PREPARING, shown.toInt(), left)
            }
            is CellState.Downloading -> {
                val p = if (s.totalBytes > 0) BUILD_END + (99 - BUILD_END) * s.doneBytes.toDouble() / s.totalBytes else BUILD_END
                shown = min(max(shown, p), 99.0)
                val start = dlAt
                if (start == null || s.doneBytes < dlBytes) { dlAt = now; dlBytes = s.doneBytes }
                val dt = (now - (dlAt ?: now)) / 1000.0
                val rate = if (dt >= 2 && s.doneBytes > dlBytes) (s.doneBytes - dlBytes) / dt else null
                val left = if (rate != null && s.totalBytes > 0) ((s.totalBytes - s.doneBytes) / rate).toInt() else null
                MapLoadUi(MapLoadUi.Kind.LOADING, shown.toInt(), left)
            }
            CellState.Installing -> { shown = max(shown, 99.0); MapLoadUi(MapLoadUi.Kind.LOADING, 99, 0) }
            // downloaded during a tour: on the phone, joined after it – as far as the loading window goes, it is done
            CellState.Downloaded -> { shown = 100.0; MapLoadUi(MapLoadUi.Kind.LOADING, 100, 0) }
            is CellState.Ready -> { shown = 100.0; MapLoadUi(MapLoadUi.Kind.LOADING, 100, 0) }
            is CellState.Offline -> MapLoadUi(MapLoadUi.Kind.OFFLINE, shown.toInt(), null)
            is CellState.WaitingForWifi -> MapLoadUi(MapLoadUi.Kind.WIFI, shown.toInt(), null)
            is CellState.Failed -> MapLoadUi(MapLoadUi.Kind.FAILED, shown.toInt(), null,
                reason = if (s.retryable) "Something went wrong while preparing it. Please try again." else s.message,
                retryable = s.retryable)
        }
    }

    companion object {
        const val BUILD_END = 80.0
        /** Assumed preparing time when the server gives no ETA. */
        const val DEFAULT_BUILD_S = 180
        /** Between polls the bar moves at most this share of the way to [BUILD_END] (never reaches it by itself). */
        const val MAX_INTERPOLATION = 0.9
    }
}

/**
 * The modal for several regions at once (owner 2026-10-21: "N regions · about X MB"): one bar over all of them. Pure,
 * unit-tested.
 */
object MapLoads {
    /** "2 regions · ≈ 18 MB" (the tap question). */
    fun askText(regions: Int, bytes: Long): String = "$regions ${if (regions == 1) "map area" else "map areas"} · ≈ ${mb(bytes)} MB"

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
     * [sizes] the installed regions' sizes. The % is the mean over the regions (ready = 100), never below [held] (the
     * last value shown) and below 100 until all are there. Null when every region is there.
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
        val mean = (per.sumOf { it.percent } + 100 * (total - open.size)) / total
        val pct = maxOf(held, mean).coerceAtMost(99)
        val bytes = estimate(focus, states, sizes)
        val kinds = per.map { it.kind }
        val left = per.mapNotNull { it.secondsLeft }.maxOrNull()
        return when {
            kinds.all { it == MapLoadUi.Kind.FAILED } ->
                per.first().copy(percent = pct, regions = total, bytes = bytes, retryable = per.any { it.retryable })
            MapLoadUi.Kind.OFFLINE in kinds -> MapLoadUi(MapLoadUi.Kind.OFFLINE, pct, null, regions = total, bytes = bytes)
            MapLoadUi.Kind.LOADING in kinds -> MapLoadUi(MapLoadUi.Kind.LOADING, pct, left, regions = total, bytes = bytes)
            MapLoadUi.Kind.PREPARING in kinds -> MapLoadUi(MapLoadUi.Kind.PREPARING, pct, left, regions = total, bytes = bytes)
            else -> MapLoadUi(MapLoadUi.Kind.WIFI, pct, null, regions = total, bytes = bytes)
        }
    }
}

/**
 * Modal → Cancel (owner 2026-10-21, field test: searched Hilden → "about 30 minutes" → Cancel): **Cancel = stop.** The
 * regions the modal waited for stop loading (installed ones stay; the server may finish its build – the next request is
 * then quick), no card follows for that job, the searched place's ring goes when a search / shared place started it, a
 * point the user set stays in the plan, the map stays where it is. Setting a point or searching there again loads anew.
 * Pure, unit-tested.
 */
object MapLoadCancel {
    data class Outcome(
        /** Regions to drop from the queue (not installed). */
        val stop: List<CellId>,
        /** Remove the orange search ring / searched-place marker. */
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
        return Outcome(all.filter { it !in installed }, bySearch, closeCard)
    }
}

class MapLoadCallbacks(val onCancel: () -> Unit = {}, val onRetry: () -> Unit = {}, val onClose: () -> Unit = {})

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
        MapLoadPanel(shown, MapLoadCallbacks(acted(cb.onCancel), acted(cb.onRetry), acted(cb.onClose)))
    }
}

/** The modal's content (also used by the screenshot tests, which cannot show a Dialog window). */
@Composable
fun MapLoadPanel(ui: MapLoadUi, cb: MapLoadCallbacks) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val (icon, tint) = when (ui.kind) {
                MapLoadUi.Kind.FAILED -> Icons.Outlined.Warning to Color(0xFFB26A00)
                MapLoadUi.Kind.OFFLINE -> Icons.Outlined.CloudOff to variant
                else -> Icons.Outlined.CloudDownload to OnPathColors.Green
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp))
            Text(ui.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(ui.totals, style = MaterialTheme.typography.bodyMedium, color = variant)
            if (ui.kind != MapLoadUi.Kind.FAILED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { ui.percent / 100f },
                        color = if (ui.paused) variant else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).clip(CircleShape))
                    Text("${ui.percent} %", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 12.dp))
                }
            }
            ui.line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = variant) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (ui.kind == MapLoadUi.Kind.FAILED) {
                    TextButton(onClick = cb.onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                    if (ui.retryable) TextButton(onClick = cb.onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                } else {
                    TextButton(onClick = cb.onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                }
            }
        }
    }
}

/** A tap on the world map where there is no detailed map (owner 2026-10-21): never a silent download from a tap. */
@Composable
fun LoadAskDialog(regions: Int, bytes: Long, onLoad: () -> Unit, onNotNow: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text("Load the map for this area?") },
        text = { Text(MapLoads.askText(regions, bytes)) },
        confirmButton = { TextButton(onClick = onLoad, modifier = Modifier.heightIn(min = 48.dp)) { Text("Load") } },
        dismissButton = { TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Not now") } },
    )
}
