package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.AppSettings
import com.onpath.app.time.Pace

/**
 * Settings → "Your pace" (docs/plan-time-estimate.md §4.1): one choice for hiking and one for cycling, a factor on
 * the times only (the route stays the same). Every plan and every tour starts from it; during a tour the arrival adapts
 * to today's pace (learning per tour only, owner 2026-10-22 – nothing is carried to the next tour).
 */
@Composable
internal fun PaceSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    Section("Your pace") {
        Text("Changes the times only, not the route. Times are walking / riding time without breaks.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        PaceRow("Hiking", settings.hikePace) { onChange(settings.copy(hikePace = it)) }
        PaceRow("Cycling", settings.bikePace) { onChange(settings.copy(bikePace = it)) }
        Text("During a tour, the arrival time adapts to your pace of the day after about 15 minutes.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
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

/** After a tour: "Moving 2:52 · breaks 0:41" and "On the route: planned 3:10 – you needed 2:52". */
@Composable
internal fun TourTimesText(t: com.onpath.app.time.TourTimes) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(t.movingLine, style = MaterialTheme.typography.bodyMedium)
        t.planLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
