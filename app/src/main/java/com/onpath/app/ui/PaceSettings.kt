package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.AppSettings
import com.onpath.app.routing.Activity
import com.onpath.app.time.Factors
import com.onpath.app.time.Pace
import kotlin.math.abs
import kotlin.math.roundToInt

/** What OnPath learned about one activity from earlier tours (time/PaceStore), for Settings. */
data class LearnedPace(val activity: Activity, val tours: Int, val factors: Factors)

/**
 * Settings → "Your pace" (docs/plan-time-estimate.md §4.1): one choice for hiking and one for cycling, a factor on
 * the times only (the route stays the same). Below it, what was learned from your tours, each with Reset.
 */
@Composable
internal fun PaceSection(settings: AppSettings, onChange: (AppSettings) -> Unit, learned: List<LearnedPace>,
                         onReset: (Activity) -> Unit) {
    var resetAsk by remember { mutableStateOf<LearnedPace?>(null) }
    Section("Your pace") {
        Text("Changes the times only, not the route. Times are walking / riding time without breaks.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        PaceRow("Hiking", settings.hikePace) { onChange(settings.copy(hikePace = it)) }
        PaceRow("Cycling", settings.bikePace) { onChange(settings.copy(bikePace = it)) }
        if (learned.isEmpty()) {
            Text("OnPath also learns your pace when you follow a route for at least 30 minutes.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        learned.forEach { l ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Learned · ${l.activity.label}", fontSize = 15.sp)
                    Text(learnedText(l.factors, l.tours), fontSize = 12.sp, lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { resetAsk = l }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset") }
            }
        }
    }
    resetAsk?.let { l ->
        AlertDialog(
            onDismissRequest = { resetAsk = null },
            title = { Text("Reset learned pace?") },
            text = { Text("Times for ${l.activity.label} go back to your pace setting. What OnPath learned from ${tours(l.tours)} is forgotten.") },
            confirmButton = { TextButton(onClick = { resetAsk = null; onReset(l.activity) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { resetAsk = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PaceRow(title: String, selected: Pace, onPick: (Pace) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Pace.entries.forEach { p ->
            FilterChip(selected = selected == p, onClick = { onPick(p) }, label = { Text(p.label) },
                modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

private fun tours(n: Int) = if (n == 1) "1 tour" else "$n tours"

/** "Climbs 12 % faster · flat as set · downhill 5 % slower (4 tours)". Factor < 1 = faster than the setting. */
internal fun learnedText(f: Factors, tours: Int): String {
    fun one(name: String, x: Double): String {
        val pct = ((x - 1) * 100).roundToInt()
        return when {
            abs(pct) < 3 -> "$name as set"
            pct < 0 -> "$name ${-pct} % faster"
            else -> "$name $pct % slower"
        }
    }
    return listOf(one("climbs", f.up), one("flat", f.flat), one("downhill", f.down)).joinToString(" · ")
        .replaceFirstChar { it.uppercase() } + " (${tours(tours)})"
}

/** After a tour: "Moving 2:52 · breaks 0:41" and "On the route: planned 3:10 – you needed 2:52". */
@Composable
internal fun TourTimesText(t: com.onpath.app.time.TourTimes) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(t.movingLine, style = MaterialTheme.typography.bodyMedium)
        t.planLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
