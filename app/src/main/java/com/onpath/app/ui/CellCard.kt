package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

    fun why(w: CellWhy): String = when (w) {
        CellWhy.PLANNED -> "Getting the detailed map because you planned here."
        CellWhy.HERE -> "Getting the detailed map because you are here."
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
    val onWifiOnly: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** OK on "Map ready", Not now on a failure, OK on "not available", × while loading (downloads go on). */
    val onClose: () -> Unit = {},
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
                if (ui is CellCardUi.Working || ui is CellCardUi.Offline) {
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
