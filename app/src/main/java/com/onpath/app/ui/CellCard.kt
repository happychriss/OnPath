package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellJob
import com.onpath.app.cells.CellState
import com.onpath.app.cells.CellWhy
import com.onpath.app.cells.label
import kotlin.math.atan2

/**
 * The map card in the plan sheet (docs/design/map-cells.md §9): what happens with the detailed map of the area the
 * user plans in. Persistent – it never goes by itself while something is open: OK when ready, Yes / Only on Wi-Fi for
 * mobile data, Retry / Not now when it failed, × to hide it while it loads (downloads go on).
 */
sealed interface CellCardUi {
    val title: String

    data object NotAvailable : CellCardUi {
        override val title = "Map downloads not available in this build"
        const val BODY = "Planning works on the overview map; routes without routing data are straight lines."
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
        /** "3 areas wait for Wi-Fi (≈ 25 MB)" when the question was answered or Settings says Wi-Fi only. */
        val wifiNote: String?,
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
            const val ALLOW = "Allow location to get the detailed map for your area – or set a point on the map"
            const val WAITING = "Waiting for your position to get the detailed map for your area – or set a point on the map"
            const val BODY = "Hold the map where you want to go. Until then the overview map is shown."
        }
    }

    data class Done(val areas: Int, val bytes: Long) : CellCardUi {
        override val title get() = "Map ready offline ✓ ($areas ${if (areas == 1) "area" else "areas"}, ${mb(bytes)} MB)"
    }
}

data class CellRow(val name: String, val state: String, val own: Boolean)

data class MobileQuestion(val areas: Int, val bytes: Long?) {
    val text: String get() = "Download $areas more map ${if (areas == 1) "area" else "areas"}" +
        (bytes?.let { " (≈ ${mb(it)} MB)" } ?: "") + " over mobile data?"
}

/** Whole MB, at least 1 for anything non-empty. */
internal fun mb(bytes: Long): Long = if (bytes <= 0) 0 else maxOf(1, (bytes + 524_288) / 1_048_576)

/** Pure: card content from the trigger's job and the cell states (unit-tested). */
object CellCards {
    const val MAX_ROWS = 3
    const val OFFLINE_TITLE = "No detailed map here yet – it downloads when you're online"

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

    fun why(w: CellWhy): String = when (w) {
        CellWhy.PLANNED -> "Getting the detailed map because you planned here."
        CellWhy.HERE -> "Getting the detailed map because you are here."
        CellWhy.UPDATE -> "Updating the map for the new map style (small streets)."
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
            val title = if (failed.size == 1) f.message else "${failed.size} map areas could not be loaded: ${f.message}"
            return CellCardUi.Failed(title, why, failed.any { (s[it] as CellState.Failed).retryable })
        }
        // only waiting for the network (no download running): one plain line
        val busy = open.filter { s[it] !is CellState.Offline }
        if (busy.isEmpty() && open.isNotEmpty()) {
            val msg = (s[open.first()] as CellState.Offline).message
            return CellCardUi.Offline(if (msg.startsWith("No connection")) OFFLINE_TITLE else msg, why)
        }
        val downloading = open.mapNotNull { s[it] as? CellState.Downloading }
        val installing = open.any { s[it] == CellState.Installing }
        val waiting = open.filter { s[it] is CellState.WaitingForWifi }
        val waitBytes = waiting.mapNotNull { (s[it] as CellState.WaitingForWifi).bytes }.takeIf { it.size == waiting.size }?.sum()
        val title = when {
            downloading.isNotEmpty() || installing -> {
                val done = ready.sumOf { sizes[it] ?: 0L } + downloading.sumOf { it.doneBytes }
                "Downloading ${minOf(ready.size + 1, all.size)} of ${all.size} · ${mb(done)} MB"
            }
            waiting.size == open.size -> "Detailed map ready to download"
            else -> {
                val eta = open.mapNotNull { when (val x = s[it]) { is CellState.Queued -> x.etaS; is CellState.Building -> x.etaS; else -> null } }.maxOrNull()
                "Preparing map for this area" + (eta?.let { " – about ${maxOf(1, (it + 59) / 60)} min" } ?: "")
            }
        }
        val progress = downloading.firstOrNull { it.totalBytes > 0 }?.let { it.doneBytes.toFloat() / it.totalBytes }
        val ordered = all.sortedBy { if (it == job.own) 0 else 1 }
        val rows = ordered.take(MAX_ROWS).mapIndexed { i, c -> CellRow(name(c, job, i), rowState(s[c]!!), c == job.own) }
        val rest = ordered.drop(MAX_ROWS)
        val more = if (rest.isEmpty()) null else {
            val restReady = rest.count { s[it] is CellState.Ready }
            "${rest.size} more ${if (rest.size == 1) "area" else "areas"}" + if (restReady == rest.size) " · ready" else if (restReady > 0) " · $restReady ready" else " · waiting"
        }
        val waitNote = if (waiting.isEmpty()) null else "${waiting.size} ${if (waiting.size == 1) "area waits" else "areas wait"} for Wi-Fi" +
            (waitBytes?.let { " (≈ ${mb(it)} MB)" } ?: "") + if (wifiOnly) " – Settings: maps only on Wi-Fi" else ""
        val question = if (waiting.isNotEmpty() && !wifiOnly && !mobileDeclined) MobileQuestion(waiting.size, waitBytes) else null
        return CellCardUi.Working(title, why, rows, more, progress, question, if (question == null) waitNote else null)
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
        is CellState.Ready -> "ready ✓"
        CellState.None -> "waiting"
        else -> s.label()
    }

    /** "Your point" / "Your position" for the own cell, "Area to the north-east" for a neighbour, "Map area 2" else. */
    internal fun name(c: CellId, job: CellJob, index: Int): String {
        val own = job.own
        if (c == own) return if (job.why == CellWhy.HERE) "Your position" else "Your point"
        if (own == null) return "Map area ${index + 1}"
        val dx = (c.x - own.x).toDouble(); val dy = (own.y - c.y).toDouble()
        val dirs = listOf("east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east")
        val a = Math.toDegrees(atan2(dy, dx)).let { if (it < 0) it + 360 else it }
        return "Area to the " + dirs[((a + 22.5) / 45).toInt() % 8]
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

/** The card; [compact] (plan sheet MINI): one line. */
@Composable
fun CellCard(ui: CellCardUi, cb: CellCardCallbacks, compact: Boolean = false, modifier: Modifier = Modifier) {
    val (icon, tint) = when (ui) {
        is CellCardUi.Done -> Icons.Outlined.CheckCircle to OnPathColors.Good
        is CellCardUi.Failed -> Icons.Outlined.Warning to Color(0xFFB26A00)
        is CellCardUi.Offline -> Icons.Outlined.CloudOff to MaterialTheme.colorScheme.onSurfaceVariant
        CellCardUi.NotAvailable -> Icons.Outlined.Map to MaterialTheme.colorScheme.onSurfaceVariant
        is CellCardUi.Working -> Icons.Outlined.CloudDownload to OnPathColors.Green
        is CellCardUi.NeedPosition -> Icons.Outlined.LocationOn to OnPathColors.Green
    }
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(ui.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                if (ui is CellCardUi.Working || ui is CellCardUi.Offline || ui is CellCardUi.NeedPosition) {
                    IconButton(onClick = cb.onClose, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Close, "Hide (the map keeps loading)", tint = variant, modifier = Modifier.size(18.dp))
                    }
                } else Spacer(Modifier.width(6.dp))
            }
            if (compact) {
                // the one thing that waits for an answer stays visible even in the small sheet
                if (ui is CellCardUi.Working && ui.question != null) Question(ui.question, cb)
                return@Column
            }
            val why = when (ui) { is CellCardUi.Working -> ui.why; is CellCardUi.Offline -> ui.why; is CellCardUi.Failed -> ui.why; else -> null }
            Column(Modifier.padding(start = 28.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                why?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = variant) }
                when (ui) {
                    CellCardUi.NotAvailable -> Text(CellCardUi.NotAvailable.BODY, style = MaterialTheme.typography.bodySmall, color = variant)
                    is CellCardUi.NeedPosition -> Text(CellCardUi.NeedPosition.BODY, style = MaterialTheme.typography.bodySmall, color = variant)
                    is CellCardUi.Working -> {
                        ui.progress?.let {
                            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(CircleShape))
                        }
                        ui.rows.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (r.own) "●" else "○", color = if (r.own) OnPathColors.Green else variant,
                                    style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(6.dp))
                                Text(r.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Text(r.state, style = MaterialTheme.typography.bodySmall, color = variant, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                            }
                        }
                        ui.more?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = variant) }
                        ui.wifiNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = variant) }
                    }
                    else -> Unit
                }
            }
            when (ui) {
                is CellCardUi.Working -> ui.question?.let { Question(it, cb) }
                is CellCardUi.Failed -> Row(Modifier.fillMaxWidth().padding(start = 20.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = cb.onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Not now") }
                    if (ui.retryable) TextButton(onClick = cb.onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
                }
                is CellCardUi.NeedPosition -> if (!ui.permission) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = cb.onAllowLocation, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow location") }
                }
                is CellCardUi.Done, CellCardUi.NotAvailable -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = cb.onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Question(q: MobileQuestion, cb: CellCardCallbacks) {
    Column(Modifier.padding(start = 28.dp, end = 8.dp, top = 4.dp)) {
        Text(q.text, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = cb.onWifiOnly, modifier = Modifier.heightIn(min = 48.dp)) { Text("Only on Wi-Fi") }
            Button(onClick = cb.onMobileYes, modifier = Modifier.heightIn(min = 48.dp)) { Text("Yes") }
        }
    }
}

/** Small persistent hint on the map where only the overview exists (z ≥ 10, Plan). */
@Composable
fun OverviewHint(modifier: Modifier = Modifier) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Map, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(OVERVIEW_HINT, style = MaterialTheme.typography.bodySmall)
        }
    }
}

const val OVERVIEW_HINT = "Overview only – set a point and the detailed map follows"

/**
 * When the "Overview only" hint shows (Plan, z ≥ [MIN_ZOOM]): when a large part of the visible map has no detailed
 * map – not just the centre (field test 0.8.7: centre inside a downloaded area, half the screen overview only, no
 * hint). The view is sampled on a [GRID] × [GRID] grid. Pure, unit-tested.
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
 * While map areas are built or downloaded: a clear modal overlay (owner 2026-10-21) – what happens, why, progress, the
 * mobile-data question ("Wait for Wi-Fi" / "Use mobile data") and one way out: Cancel. It is shown for
 * [CellCardUi.Working] (until the mobile-data question was answered "Wait for Wi-Fi"), [CellCardUi.Offline] and
 * [CellCardUi.Failed]; "Map ready" and the rest stay in the plan sheet's card.
 */
fun CellCardUi.wantsOverlay(): Boolean = this is CellCardUi.Working && (question != null || wifiNote == null) ||
    this is CellCardUi.Offline || this is CellCardUi.Failed

@Composable
fun MapLoadOverlay(ui: CellCardUi, cb: CellCardCallbacks) {
    androidx.compose.ui.window.Dialog(onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        MapLoadPanel(ui, cb)
    }
}

/** The overlay's content (also used by the screenshot tests, which cannot show a Dialog window). */
@Composable
fun MapLoadPanel(ui: CellCardUi, cb: CellCardCallbacks) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val (icon, tint) = when (ui) {
                is CellCardUi.Failed -> Icons.Outlined.Warning to Color(0xFFB26A00)
                is CellCardUi.Offline -> Icons.Outlined.CloudOff to variant
                else -> Icons.Outlined.CloudDownload to OnPathColors.Green
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp))
            Text(ui.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val why = when (ui) { is CellCardUi.Working -> ui.why; is CellCardUi.Offline -> ui.why; is CellCardUi.Failed -> ui.why; else -> null }
            why?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = variant) }
            if (ui is CellCardUi.Working) {
                if (ui.progress != null) LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
                else if (ui.question == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
                ui.rows.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (r.own) "●" else "○", color = if (r.own) OnPathColors.Green else variant, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(r.state, style = MaterialTheme.typography.bodyMedium, color = variant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                }
                ui.more?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = variant) }
                ui.question?.let { q ->
                    Text(q.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = cb.onWifiOnly, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Wait for Wi-Fi") }
                        Button(onClick = cb.onMobileYes, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Use mobile data") }
                    }
                }
                Text("Planning goes on with the overview map and straight lines until the map is here.",
                    style = MaterialTheme.typography.bodySmall, color = variant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (ui is CellCardUi.Failed && ui.retryable) TextButton(onClick = cb.onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
                TextButton(onClick = cb.onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            }
        }
    }
}

/**
 * A newer version of a map area the user already has (checked after each route, owner 2026-10-21) – never modal:
 * [Loading] veils the map for the few seconds the download + switch take; [Preparing] (the server builds it first,
 * minutes) is a small hint while planning goes on with the current map.
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
        is MapUpdateUi.Loading -> "Loading the newer map…"
        is MapUpdateUi.Preparing -> "Newer map for this area being prepared" + (u.minutes?.let { " · about $it min" } ?: "") +
            ""
    }
}

/** Over the map (Plan): a soft veil + pill while the newer map loads, or a small hint while it is prepared. */
@Composable
fun MapUpdateLayer(u: MapUpdateUi?, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(u is MapUpdateUi.Loading, modifier = Modifier.then(modifier),
        enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), shadowElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(MapUpdates.text(MapUpdateUi.Loading(null)), style = MaterialTheme.typography.bodyMedium)
                    }
                    val p = (u as? MapUpdateUi.Loading)?.progress
                    if (p != null) {
                        Spacer(Modifier.size(8.dp))
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.width(160.dp))
                    }
                }
            }
        }
    }
}

/** The small "being prepared" hint (top of the map, like the overview hint). */
@Composable
fun MapPreparingHint(u: MapUpdateUi.Preparing, modifier: Modifier = Modifier) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudDownload, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(MapUpdates.text(u), style = MaterialTheme.typography.bodySmall)
        }
    }
}
