package com.onpath.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.routing.GeoPoint
import com.onpath.strings.*

// The multi-day panel's parts like the planner's (owner's sketch 2026-11-27, planner d7d01bd7b; backlog „Parity: multi-day
// panel like the planner“): head, „Planung“ + chips, the profile's title and the profile of the whole trip or one day.
// Built on their own (night plan stage 7); the plan sheet (PlanPanel) calls them after stage 6.

/**
 * The head of a multi-day plan: the tour's title with „Speichern“ beside it ([onSave]; null = nothing to save now, the button
 * greyed), the save state under it („Nicht gespeichert“ / „Geändert · nicht gespeichert“ / „Gespeichert“) and the row
 * „Gesamte Reise“ km · time · ↑ · ↓ (none while the route is not worked out). [onTitle] = a tap on the title (null = text).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiDayHead(head: MultiDayHeadUi, onSave: (() -> Unit)?, onTitle: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        val title = @Composable { m: Modifier ->
            Text(head.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = m.semantics { heading() }
                    .then(if (onTitle != null) Modifier.heightIn(min = 40.dp).clickable(role = Role.Button, onClick = onTitle) else Modifier))
        }
        val save = @Composable {
            OutlinedButton(onClick = { onSave?.invoke() }, enabled = onSave != null, colors = ActionButtons.editOutlined(),
                modifier = Modifier.heightIn(min = 40.dp)) {
                Icon(Icons.Outlined.Save, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(str(Res.string.common_save), maxLines = 1, softWrap = false)
            }
        }
        // a large font: „Speichern“ under the title (beside it the title would break inside its words)
        if (LocalDensity.current.fontScale >= 1.3f) { title(Modifier.fillMaxWidth()); save() }
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            title(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            save()
        }
        Text(head.save.text(), style = MaterialTheme.typography.bodySmall,
            color = if (head.save == DaySaveState.SAVED) OnPathColors.Good else muted)
        head.whole?.let { w ->
            // the label and the numbers: side by side, or the numbers under the label at a large font
            FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center) {
                Text(str(Res.string.days_whole_trip), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(instrument(w, MaterialTheme.typography.bodyMedium, muted), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * „Planung“ and the tour's chips (planner: before the chips): „8 Tage ✎“, „4,5 h / Tag“, „500 m ↑ / Tag“ – a chip only for a
 * limit that is set. [onChip] null = display only (the app sets days and limits in the planner).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanningChips(p: PlanningUi, onChip: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(str(Res.string.days_planning), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 2.dp))
        Chip(p.days, edit = onChip != null, onChip)
        p.hours?.let { Chip(it, edit = false, onChip) }
        p.ascent?.let { Chip(it, edit = false, onChip) }
    }
}

@Composable
private fun Chip(text: String, edit: Boolean, onClick: (() -> Unit)?) {
    val m = Modifier.heightIn(min = 32.dp)
    val content = @Composable {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = OnPathColors.OnEditTonal, maxLines = 1, softWrap = false)
            if (edit) { Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.Edit, null, tint = OnPathColors.OnEditTonal, modifier = Modifier.size(14.dp)) }
        }
    }
    if (onClick != null) Surface(onClick = onClick, shape = OnPathShapes.Pill, color = OnPathColors.EditTonal, modifier = m, content = content)
    else Surface(shape = OnPathShapes.Pill, color = OnPathColors.EditTonal, modifier = m, content = content)
}

/**
 * „Höhenprofil · gesamte Reise“ with the day borders, or for an open [day] „Höhenprofil · Tag N“ with only that day's part
 * (planner: dayProfile, 0 … its km) – no other day's borders then.
 */
@Composable
fun TripProfile(days: DaysUi, day: Int?, onHighlight: (GeoPoint?) -> Unit, modifier: Modifier = Modifier) {
    val cuts = remember(days.borders) { days.borders.map { it.first } }
    val pts = remember(days.profile, cuts, day) { MultiDayPanel.dayProfile(days.profile, cuts, day) }
    Column(modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(MultiDayPanel.profileTitle(day), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            ElevationProfile(pts, height = 96.dp, onHighlight = onHighlight, onRelease = { onHighlight(null) })
            if (day == null) DayBorders(days)
        }
    }
}

/**
 * The plan sheet's multi-day body like the planner's panel (owner's sketch 2026-11-27): „Planung“ + chips, the day table
 * ([DayTableView]: tap = this day, hold = the days up to this one) and „Höhenprofil · gesamte Reise“ with the day borders –
 * „Höhenprofil · Tag N“ with only that day while one day is picked. The drop-in for [DaysOverview] (same arguments).
 */
@Composable
fun MultiDayOverview(days: DaysUi, onTap: (Int) -> Unit, onHold: (Int) -> Unit, onHighlight: (GeoPoint?) -> Unit) {
    val table = days.table ?: return DaysOverview(days, onTap, onHold, onHighlight)
    Column(Modifier.fillMaxWidth()) {
        PlanningChips(MultiDayPanel.planning(days.count, days.limits))
        DayTableView(table, Modifier.padding(top = 6.dp), picked = { n -> days.selected?.let { n in it } == true }, onTap = onTap, onHold = onHold)
        TripProfile(days, days.selected?.takeIf { it.first == it.last }?.first, onHighlight)
    }
}
