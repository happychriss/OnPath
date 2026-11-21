package com.onpath.app.ui.dayrows

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.routing.DayEndKind
import com.onpath.app.ui.DayEndSign
import com.onpath.app.ui.DayRow
import com.onpath.app.ui.DayRowUi
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.instrument

/**
 * SKETCH (owner 2026-11-21, multi-day day rows): "Tag 1", the day's km and ↑ m, and "von Ort – nach Ort" in grey under it
 * only when both places are known. Not wired: drawn only by DayRowsSketch*Test through the sheet's LocalDayRowSketch hook.
 * Variant A: values right-aligned before the end sign. Variant B: values directly after "Tag n".
 */
internal enum class Variant { TODAY, A, B }

/** One day of the sketch data; a null place = no place near that end. */
internal class SketchDay(val n: Int, val from: String?, val to: String?, val distanceM: Double, val ascentM: Double,
                         val end: DayEndKind?, val over: Boolean)

internal val sketchDays = listOf(
    SketchDay(1, "München", "Bad Tölz", 92_000.0, 640.0, DayEndKind.PROPOSAL, false),
    SketchDay(2, "Bad Tölz", "Gasthof Krone", 96_000.0, 1_420.0, DayEndKind.HOTEL, true),
    SketchDay(3, "Gasthof Krone", "Brixen", 79_000.0, 980.0, DayEndKind.OVERNIGHT, false),
    SketchDay(4, "Brixen", null, 95_000.0, 420.0, DayEndKind.PROPOSAL, false),
    SketchDay(5, null, "Bassano", 101_000.0, 1_120.0, DayEndKind.PROPOSAL, false),
    SketchDay(6, "Bassano", "Venedig", 101_000.0, 220.0, null, false),
)

/** Row heights in dp as drawn, by day number (for the measuring test). */
internal val rowHeights = mutableMapOf<Int, Float>()

/** The row of [variant] for the sheet's rows, drawn from [days]. */
internal fun rowFor(variant: Variant, days: List<SketchDay> = sketchDays): @Composable (DayRowUi, Boolean, () -> Unit, () -> Unit) -> Unit =
    { r, picked, tap, hold ->
        val d = days.first { it.n == r.n }
        val density = LocalDensity.current.density
        val measure = Modifier.onSizeChanged { rowHeights[r.n] = it.height / density }
        if (variant == Variant.TODAY) Box(measure) { DayRow(r, picked, tap, hold) }
        else DayRowSketch(d, variant == Variant.A, picked, tap, hold, measure)
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayRowSketch(d: SketchDay, valuesRight: Boolean, picked: Boolean, onTap: () -> Unit, onHold: () -> Unit, extra: Modifier) {
    val values = "${Format.distance(d.distanceM)} · ↑ ${Format.meters(d.ascentM)}"
    Row(Modifier.fillMaxWidth().then(extra).heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
        .background(if (picked) OnPathColors.Route.copy(alpha = 0.10f) else Color.Transparent)
        .combinedClickable(role = Role.Button, onClick = onTap, onLongClick = onHold)
        .semantics { selected = picked }
        .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tag ${d.n}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                if (valuesRight) Spacer(Modifier.weight(1f))
                Text(instrument(values, MaterialTheme.typography.bodySmall), style = MaterialTheme.typography.bodySmall,
                    color = if (d.over) OnPathColors.Warn else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(start = if (valuesRight) 8.dp else 10.dp))
            }
            // the grey line: only when both places are known
            if (d.from != null && d.to != null)
                Text("${d.from} – ${d.to}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        DayEndSign(d.end, 20.dp)
    }
}
