package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.routing.Activity
import com.onpath.strings.*

/**
 * The activity picker (owner 2026-11-27, variant C „Rad | Wandern, dann Auswahl“): a sheet from the bottom – „Aktivität“
 * with ✕, a switch Rad | Wandern on top, under it only that group's choices as cards: the five bikes (icon, name, one
 * line) or the five hiking levels (T badge, name, one short line – readable before the tap, owner 2026-11-26 night).
 * ONE tap on a card chooses and closes (owner 2026-11-05: no confirm); the switch only shows the other group. The chosen
 * card: green edge on light green (colour rule 1). Opens on the group of [current].
 */
@Composable
internal fun ActivityPickerSheet(current: Activity, onSelect: (Activity) -> Unit, onDismiss: () -> Unit, sac: Int, onSac: (Int) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
            // the sheet itself swallows taps (a tap beside a card must not close it)
            Box(Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null) {}) {
                ActivityPickerContent(current, onSelect = { onSelect(it); onDismiss() }, onDismiss = onDismiss, sac = sac,
                    onSac = { level -> onSelect(Activity.HIKE); onSac(level); onDismiss() })
            }
        }
    }
}

/** The bike group of the switch: every activity but Hike, in the enum's order. */
internal val BIKES: List<Activity> get() = Activity.entries.filter { it != Activity.HIKE }

/** The sheet's content (drawn in place for the screenshot tests): head, switch, the group's cards. */
@Composable
internal fun ActivityPickerContent(current: Activity, onSelect: (Activity) -> Unit, onDismiss: () -> Unit, sac: Int, onSac: (Int) -> Unit) {
    var hike by rememberSaveable { mutableStateOf(current == Activity.HIKE) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(OnPathColors.Paper)
        .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).background(OnPathColors.Line, CircleShape))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(str(Res.string.routes_activity_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Box(Modifier.size(32.dp).background(OnPathColors.PaperDeep, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, str(Res.string.common_close), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
        GroupSwitch(hike) { hike = it }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!hike) BIKES.forEach { a ->
                ChoiceCard(a == current, onClick = { onSelect(a) }, lead = { Icon(a.icon, null, tint = OnPathColors.Green) }, name = a.label, line = a.line)
            } else SacScale.levels.forEach { l ->
                val sel = current == Activity.HIKE && sac == l.level
                ChoiceCard(sel, onClick = { onSac(l.level) }, lead = { LevelBadge(l.level, sel) }, name = l.name, line = l.short,
                    description = str(Res.string.routes_sac_button_desc, l.level, l.name))
            }
        }
    }
}

/** Rad | Wandern: two halves, the shown one white and raised on the deeper sage (like iOS's segmented control). */
@Composable
private fun GroupSwitch(hike: Boolean, onHike: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(OnPathColors.PaperDeep, RoundedCornerShape(14.dp)).padding(3.dp)) {
        Half(Activity.BIKE_TOURING.icon, str(Res.string.activity_group_bike), !hike, Modifier.weight(1f)) { onHike(false) }
        Half(Activity.HIKE.icon, Activity.HIKE.label, hike, Modifier.weight(1f)) { onHike(true) }
    }
}

@Composable
private fun Half(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(11.dp))
        .then(if (on) Modifier.background(Color.White, RoundedCornerShape(11.dp)) else Modifier)
        .selectable(on, role = Role.Tab, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        val tint = if (on) OnPathColors.Green else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
    }
}

/** One choice: a card with its sign, the name and one line; the chosen one with a green edge on light green. */
@Composable
private fun ChoiceCard(chosen: Boolean, onClick: () -> Unit, lead: @Composable () -> Unit, name: String, line: String, description: String? = null) {
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
        .background(if (chosen) OnPathColors.GreenLight else OnPathColors.PaperDim, shape)
        .then(if (chosen) Modifier.border(2.dp, OnPathColors.Green, shape) else Modifier)
        .selectable(chosen, role = Role.Button, onClick = onClick)
        .then(if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier)
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { lead() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** „T2“ in a small rounded square – green with white when chosen. */
@Composable
private fun LevelBadge(level: Int, chosen: Boolean) =
    Box(Modifier.size(32.dp).background(if (chosen) OnPathColors.Green else OnPathColors.PaperDeep, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        Text(str(Res.string.routes_sac_badge, level), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = if (chosen) Color.White else MaterialTheme.colorScheme.onSurface)
    }
