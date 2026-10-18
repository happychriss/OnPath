package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format
import com.onpath.app.time.Factors
import com.onpath.app.time.PaceModel
import com.onpath.app.time.TimeBreakdown
import com.onpath.app.time.TimeEstimate

/** What went into the time shown in the plan sheet. */
data class TimeDetailsUi(
    val result: TimeBreakdown.Result,
    val paceLabel: String,
    val paceFactor: Double,
    /** Learned across tours for this activity (null = nothing learned yet) and how many tours it is based on. */
    val learned: Factors?,
    val learnedTours: Int,
)

/**
 * "Time details" (route sheet ⋯): a debug view of the time estimate – which slope bands the route has, how long each
 * takes at Normal pace, which factor (pace setting × learned) is applied, and the result. Read-only.
 */
@Composable
fun TimeDetailsScreen(ui: TimeDetailsUi, onBack: () -> Unit) {
    val r = ui.result
    val bike = TimeEstimate.isBike(r.activity)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Time details", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TdSection("Result") {
                    TdKV("Moving time", Format.duration(r.finalS), bold = true)
                    TdKV("With breaks (+${(TimeEstimate.BREAK_ALLOWANCE * 100).toInt()} %)",
                        Format.duration(r.finalS * (1 + TimeEstimate.BREAK_ALLOWANCE)))
                    TdKV("At Normal pace, nothing learned", Format.duration(r.baseS))
                    if (r.wayRows.isNotEmpty()) TdKV("From the slope alone (no way info)", Format.duration(r.slopeOnlyS))
                }
                TdSection("Factors") {
                    TdKV("Activity", r.activity.label)
                    TdKV("Pace setting", "${ui.paceLabel} × ${f2(ui.paceFactor)}")
                    if (ui.learned != null) {
                        TdKV("Learned (${ui.learnedTours} tours) ↓ / → / ↑",
                            "× ${f2(ui.learned.down)} / ${f2(ui.learned.flat)} / ${f2(ui.learned.up)}")
                    } else TdKV("Learned from tours", "nothing yet")
                    TdKV("Applied ↓ / → / ↑", "× ${f2(r.factors.down)} / ${f2(r.factors.flat)} / ${f2(r.factors.up)}", bold = true)
                }
                TdSection("By slope (${r.segments} pieces of ${r.segmentM.toInt()} m)") {
                    Row(Modifier.fillMaxWidth()) {
                        TdCell("Slope", 1.5f, header = true); TdCell("Dist.", 1f, header = true); TdCell("Height", 1.3f, header = true)
                        TdCell("Base", 1f, header = true); TdCell("×", 0.7f, header = true); TdCell("Time", 1f, header = true)
                        TdCell("km/h", 0.9f, header = true)
                    }
                    HorizontalDivider()
                    r.rows.filter { it.distanceM > 0 }.forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            TdCell(row.label, 1.5f)
                            TdCell(Format.distance(row.distanceM), 1f)
                            TdCell(buildString {
                                if (row.upM >= 1) append("↑${row.upM.toInt()}")
                                if (row.downM >= 1) { if (isNotEmpty()) append(" "); append("↓${row.downM.toInt()}") }
                            }, 1.3f)
                            TdCell(TimeEstimate.hm(row.baseS), 1f)
                            TdCell(f2(row.factor), 0.7f)
                            TdCell(TimeEstimate.hm(row.finalS), 1f, bold = true)
                            TdCell(String.format("%.1f", row.kmh), 0.9f)
                        }
                    }
                }
                if (r.wayRows.isNotEmpty()) TdSection("By way type / difficulty (Normal pace)") {
                    Row(Modifier.fillMaxWidth()) {
                        TdCell("Way", 2f, header = true); TdCell("Dist.", 1f, header = true)
                        TdCell("Slope only", 1.1f, header = true); TdCell("×", 0.7f, header = true)
                        TdCell("Time", 1f, header = true); TdCell("km/h", 0.9f, header = true)
                    }
                    HorizontalDivider()
                    r.wayRows.forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            TdCell(row.label, 2f)
                            TdCell(Format.distance(row.distanceM), 1f)
                            TdCell(TimeEstimate.hm(row.slopeS), 1.1f)
                            TdCell(f2(row.factor), 0.7f)
                            TdCell(TimeEstimate.hm(row.wayS), 1f, bold = true)
                            TdCell(String.format("%.1f", if (row.wayS > 0) row.distanceM / row.wayS * 3.6 else 0.0), 0.9f)
                        }
                    }
                }
                TdSection("How it is calculated") {
                    TdNote(if (bike) "Bikes: power model per piece – rider power, mass, air drag, rolling resistance and " +
                        "downhill speed cap of the surface, pushing on very steep climbs."
                    else "Hiking: minutes per km from the slope of each 50 m piece (4 km/h flat, about 320–340 m/h up, " +
                        "500–550 m/h down, slower again on very steep descents).")
                    TdNote(if (bike) "Way per piece: real surface (track grade too), slower on bad smoothness and MTB grades " +
                        "(S1 ≤ 15, S2 ≤ 10, S3 ≤ 6 km/h on an MTB), pushing on S4+ / T4+ and steps (carried, half walking speed). " +
                        "Unknown way: the activity's usual surface (${PaceModel.bike(r.activity).surface})."
                    else "Way per piece: trail difficulty T1–T2 × 1.0, T3 × 1.15, T4 × 1.35, T5 × 1.6, T6 × 1.67; " +
                        "rock / scree and steps at least × 1.15; faint trails × 1.1–1.25.")
                    TdNote("Heights come from the terrain model along the route (never GPS altitude), lightly smoothed.")
                    TdNote("While navigating, the factors are also learned from your pace on this trip (down / flat / up).")
                }
            }
        }
    }
}

private fun f2(v: Double) = String.format("%.2f", v)

@Composable
private fun TdSection(title: String, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun TdKV(k: String, v: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.SemiBold else null)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TdCell(t: String, w: Float, header: Boolean = false, bold: Boolean = false) {
    Text(t, fontSize = if (header) 11.sp else 12.sp, lineHeight = 14.sp, maxLines = 2,
        fontWeight = if (header || bold) FontWeight.SemiBold else null,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(w).padding(end = 4.dp))
}

@Composable
private fun TdNote(t: String) = Text("• $t", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
