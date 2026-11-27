package com.onpath.app.ui

import com.onpath.strings.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.navigation.NavText
import com.onpath.app.routing.Activity
import kotlin.math.abs

/** "✓ fits" / "⚠ 1.4 km not suited for gravelbikes, steps 40 m · +1.2 km" / "Checking…" (pure, for tests). */
internal fun activityRowStatus(s: ActivityRowState): String = when (s) {
    ActivityRowState.Checking -> Txt.s(Res.string.nav_check_checking)
    ActivityRowState.Plain, ActivityRowState.Idle -> ""
    is ActivityRowState.Failed -> s.message
    is ActivityRowState.Ready -> buildString {
        val f = s.fit
        if (f.fits) append(Txt.s(Res.string.nav_check_fits)) else append("⚠ ").append(f.problems.joinToString(", "))
        val e = Math.round(abs(f.extraM) / 10) * 10.0
        if (e >= 100) append(" · ").append(if (f.extraM > 0) "+" else "−").append(Format.distance(e))
    }
}

/**
 * "You are navigating as: Hike T3" and every other activity. A tap on a row (or a Hike T chip) routes the rest of the
 * followed route for that one only, offline in the background (spinner on that row – owner 2026-11-07: nothing is
 * computed before a choice); found → the usual "Take new route / Keep original" question with the new route as the
 * dashed proposal, none → "No route found for it" on the row and the sheet stays. Rows ≥ 56 dp, button 56 dp.
 */
@Composable
fun ActivitySwitchSheet(ui: ActivityCheckUi, onChoose: (Activity) -> Unit, onKeep: () -> Unit, modifier: Modifier = Modifier,
                        onHikeLevel: (Int) -> Unit = {}) {
    val free = ui.rows.any { it.state == ActivityRowState.Plain }
    // the activity picker's look (owner 2026-11-27, variant C – „ein komplett anderes Menü“ during a tour): Rad | Wandern,
    // the cards, T1–T5; each card here also says what the rest of the route would be for it (fits / not suited / time)
    var hike by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(ui.current == Activity.HIKE) }
    Surface(shape = RoundedCornerShape(topStart = OnPathShapes.SHEET_DP.dp, topEnd = OnPathShapes.SHEET_DP.dp), color = OnPathColors.Paper, modifier = modifier.fillMaxWidth().warmShadow(OnPathShadow.HIGH, RoundedCornerShape(topStart = OnPathShapes.SHEET_DP.dp, topEnd = OnPathShapes.SHEET_DP.dp))) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
            Text(str(if (free) Res.string.nav_check_recording_as else Res.string.nav_check_navigating_as), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ui.current.icon, null, tint = OnPathColors.Green, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(10.dp))
                Text(NavText.activityName(ui.current, ui.sac), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            SwitchToHeader(if (free) null else str(Res.string.nav_check_rest_of_route))
            ui.note?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            GroupSwitch(hike) { hike = it }
            Spacer(Modifier.size(12.dp))
            // both groups laid out, the hidden one invisible: no jump (as in the picker)
            androidx.compose.foundation.layout.Box {
                val bikes: @Composable () -> Unit = { Group(visible = !hike) { BIKES.forEach { a -> SwitchCard(ui, a, !hike, onChoose) } } }
                val levels: @Composable () -> Unit = { Group(visible = hike) { SacScale.levels.forEach { l -> LevelSwitchCard(ui, l, hike, onHikeLevel) } } }
                if (hike) { bikes(); levels() } else { levels(); bikes() }
            }
            Spacer(Modifier.size(12.dp))
            Button(colors = ActionButtons.go(), onClick = onKeep, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(str(Res.string.nav_check_keep, NavText.activityName(ui.current, ui.sac)), style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** A bike activity's card: the current one green (not tappable), the others with what the rest of the route would be. */
@Composable
private fun SwitchCard(ui: ActivityCheckUi, a: Activity, shown: Boolean, onChoose: (Activity) -> Unit) {
    val row = ui.rows.firstOrNull { it.activity == a }
    val current = a == ui.current
    ChoiceCard(current, enabled = shown && row?.choosable == true, onClick = { onChoose(a) },
        lead = { Icon(a.icon, null, tint = OnPathColors.Green) }, name = a.label,
        line = row?.let { activityRowStatus(it.state) }?.takeIf { it.isNotEmpty() } ?: a.line,
        description = str(Res.string.nav_check_switch_to_cd, a.label), lineColor = row?.let { rowColor(it.state) },
        trailing = row?.let { r -> { RowTrailing(r.state) } })
}

/** A T level's card: the Hike row's state shows on the level it stands for ([ActivityCheckRules.chipLevel]). */
@Composable
private fun LevelSwitchCard(ui: ActivityCheckUi, l: SacScale.Level, shown: Boolean, onHikeLevel: (Int) -> Unit) {
    val current = ui.current == Activity.HIKE && ui.sac == l.level
    val row = ui.rows.firstOrNull { it.activity == Activity.HIKE }?.takeIf { ActivityCheckRules.chipLevel(ui) == l.level && !current }
    ChoiceCard(current, enabled = shown && !current, onClick = { onHikeLevel(l.level) }, lead = { LevelBadge(l.level, current) },
        name = l.name, line = row?.let { activityRowStatus(it.state) }?.takeIf { it.isNotEmpty() } ?: l.short,
        description = str(Res.string.routes_sac_button_desc, l.level, l.name), lineColor = row?.let { rowColor(it.state) },
        trailing = row?.let { r -> { RowTrailing(r.state) } })
}

@Composable
private fun rowColor(s: ActivityRowState) = when {
    s is ActivityRowState.Ready && s.fit.fits -> OnPathColors.Good
    // "not suited" is a warning about the way (amber); red only for "no route found"
    s is ActivityRowState.Ready -> OnPathColors.Warn
    s is ActivityRowState.Failed -> OnPathColors.Error
    else -> null
}

@Composable
private fun RowTrailing(s: ActivityRowState) = when (s) {
    ActivityRowState.Checking -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
    is ActivityRowState.Ready -> Text(instrument(Format.duration(s.fit.timeS), MaterialTheme.typography.titleSmall),
        style = MaterialTheme.typography.titleSmall)
    else -> Unit
}

/**
 * "Wechseln zu" with the grey "für den Rest der Route" beside it – or under it when both do not fit on one line
 * ([ActivityCheckRules.noteStacked]; large German font broke the title as "Wechs / eln zu"), like a value row in Settings.
 */
@Composable
private fun SwitchToHeader(note: String?) {
    val title = str(Res.string.nav_check_switch_to)
    val titleStyle = MaterialTheme.typography.titleSmall
    val noteStyle = MaterialTheme.typography.bodySmall
    val measure = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        val stacked = note != null && ActivityCheckRules.noteStacked(
            with(density) { maxWidth.toPx() }, measure.measure(title, titleStyle).size.width.toFloat(),
            measure.measure(note, noteStyle).size.width.toFloat(), with(density) { 12.dp.toPx() })
        if (stacked) Column {
            Text(title, style = titleStyle)
            note?.let { Text(it, style = noteStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(title, style = titleStyle, modifier = Modifier.weight(1f))
            if (note != null) Text(note, style = noteStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

