package com.onpath.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.rides.RideChoice
import com.onpath.app.rides.RideView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos

private val RideGrey = Color(0xFF8A8F98)
private val LiftPurple = Color(0xFF8E24AA)

/**
 * "End tour" when a bus / car / lift was found (docs/design/vehicle-detection.md, owner 2026-10-22): instead of the
 * short question, a timeline (on foot / ride / lift with times, km, ascent, Ø speed), a small map (walked blue, ridden
 * grey dashed, lift purple; what a choice drops is faint) and one of four choices – Cancel · Delete · Save as always.
 * [onDelete] null = "Edit rides" on a saved tour (no Delete there; Cancel keeps it as it is).
 */
@Composable
fun RideSummaryDialog(
    summary: RideView.Summary, onSave: (RideChoice) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit,
    title: String = "End this tour?", saveLabel: String = "Save", initial: RideChoice? = null,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var choice by remember(summary) { mutableStateOf(initial?.takeIf { c -> summary.options.any { it.choice == c } } ?: summary.preselected) }
    var askDelete by remember { mutableStateOf(false) }
    if (askDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete this track?") },
            text = { Text("Recording stops and the track is not kept. You can still get it back from the recycle bin " +
                "for ${com.onpath.app.backup.RecycleBin.DAYS} days (My routes → ⋮ → Recycle bin).") },
            confirmButton = { TextButton(onClick = { askDelete = false; onDelete() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
        return
    }
    val rides = summary.rides.size
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DirectionsBus, null) },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (rides == 1) "Part of this tour looks like a ride (bus, car or lift). What should count?"
                    else "$rides parts of this tour look like rides (bus, car or lift). What should count?")
                RideMap(summary.lines[choice].orEmpty(), Modifier.fillMaxWidth().height(120.dp))
                summary.rows.forEach { TimelineRow(it, zone) }
                Spacer(Modifier.height(2.dp))
                Column(Modifier.selectableGroup()) {
                    summary.options.forEach { o ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = choice == o.choice, onClick = { choice = o.choice }, role = Role.RadioButton),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = choice == o.choice, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(o.title, fontWeight = FontWeight.Medium)
                                Text(o.detail, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text("All recorded points are kept – you can change this later in My routes (⋮ → Edit rides).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(choice) }) { Text(saveLabel) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (onDelete != null) TextButton(onClick = { askDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            }
        },
    )
}

@Composable
private fun TimelineRow(r: RideView.Row, zone: ZoneId) {
    val fmt = remember(zone) { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    fun t(ms: Long?) = ms?.let { fmt.format(Instant.ofEpochMilli(it)) } ?: "–"
    val color = when (r.kind) {
        RideView.RowKind.VEHICLE -> RideGrey
        RideView.RowKind.LIFT -> LiftPurple
        else -> OnPathColors.Route
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Column {
            Text("${r.kind.label} · ${t(r.startMs)}–${t(r.endMs)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val parts = buildList {
                add(Format.distance(r.distanceM))
                if (r.ascentM >= 1) add("↑ ${r.ascentM.toInt()} m")
                if (r.descentM >= 1) add("↓ ${r.descentM.toInt()} m")
                r.speedKmh?.let { add("Ø ${it.toInt()} km/h") }
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A sketch of the track (no map tiles): kept blue, rides grey dashed, lifts purple dashed, dropped faint. */
@Composable
private fun RideMap(lines: List<RideView.Line>, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.background(bg, RoundedCornerShape(8.dp)).padding(10.dp)) {
        val all = lines.flatMap { it.points }
        if (all.size < 2) return@Canvas
        val lat0 = all.minOf { it.lat }; val lat1 = all.maxOf { it.lat }
        val k = cos(Math.toRadians((lat0 + lat1) / 2))
        val x0 = all.minOf { it.lon } * k; val x1 = all.maxOf { it.lon } * k
        val span = maxOf(x1 - x0, lat1 - lat0, 1e-6)
        val scale = minOf(size.width, size.height) / span
        val ox = (size.width - (x1 - x0) * scale) / 2; val oy = (size.height - (lat1 - lat0) * scale) / 2
        fun pt(lat: Double, lon: Double) = Offset((ox + (lon * k - x0) * scale).toFloat(), (oy + (lat1 - lat) * scale).toFloat())
        val order = listOf(RideView.Part.DROPPED, RideView.Part.KEPT, RideView.Part.VEHICLE, RideView.Part.LIFT)
        for (part in order) for (l in lines.filter { it.part == part }) {
            if (l.points.size < 2) continue
            val path = Path().apply {
                l.points.forEachIndexed { i, p -> val o = pt(p.lat, p.lon); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
            val (c, w, eff) = when (part) {
                RideView.Part.KEPT -> Triple(OnPathColors.Route, 5f, null)
                RideView.Part.VEHICLE -> Triple(RideGrey, 5f, dash)
                RideView.Part.LIFT -> Triple(LiftPurple, 5f, dash)
                RideView.Part.DROPPED -> Triple(RideGrey.copy(alpha = 0.35f), 4f, null)
            }
            drawPath(path, c, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = eff))
        }
    }
}
