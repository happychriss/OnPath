package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.data.AppSettings
import com.onpath.app.routing.Activity
import com.onpath.app.time.Pace
import com.onpath.app.time.TimeEstimate
import com.onpath.strings.*

/**
 * Settings → "Your pace" (docs/plan-time-estimate.md §4.1, owner 2026-10-22: five levels): one level for hiking and one
 * for cycling, a factor on the times only (the route stays the same). Under each level what it means: hiking from the
 * DIN 33466 rule (Average = 4 km/h · 300 m up per hour), cycling from the bike model of [activity] (the current one;
 * a trekking bike while hiking). Every plan and every tour starts from it; during a tour the arrival adapts to today's
 * pace (learning per tour only – nothing is carried to the next tour).
 */
@Composable
internal fun PaceSection(settings: AppSettings, onChange: (AppSettings) -> Unit, activity: Activity = Activity.HIKE) {
    val bike = if (TimeEstimate.isBike(activity)) activity else Activity.BIKE_TREKKING
    Section(SettingsPage.PACE.title) {
        Text(str(Res.string.settings_pace_intro),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        PaceGroup(str(Res.string.settings_pace_hiking), Activity.HIKE, settings.hikePace) { onChange(settings.copy(hikePace = it)) }
        PaceGroup(str(Res.string.settings_pace_cycling, bike.navName), bike, settings.bikePace) { onChange(settings.copy(bikePace = it)) }
        Text(str(Res.string.settings_pace_learn),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
}

/** One activity: the five levels as radio rows, each with what it means ("about 4 km/h · 300 m up per hour"). */
@Composable
private fun PaceGroup(title: String, activity: Activity, selected: Pace, onPick: (Pace) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp))
    Column(Modifier.selectableGroup()) {
        Pace.entries.forEach { p ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = selected == p, onClick = { onPick(p) }, role = Role.RadioButton)
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == p, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(76.dp))
                Text(p.meaning(activity), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
