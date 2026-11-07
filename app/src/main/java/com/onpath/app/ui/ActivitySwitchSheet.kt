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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.navigation.ActivityFit
import com.onpath.app.navigation.NavText
import com.onpath.app.routing.Activity
import kotlin.math.abs

/** One row of the activity check sheet. */
sealed interface ActivityRowState {
    /**
     * Not routed yet (owner 2026-11-07: "erst dann, wenn ich wirklich eine Routenart ausgewählt habe"): a tap routes
     * the rest of the route for this activity only, then switches when a route is found.
     */
    data object Idle : ActivityRowState
    /** The rest of the route is being routed for this activity (spinner). */
    data object Checking : ActivityRowState
    data class Ready(val fit: ActivityFit) : ActivityRowState
    /** No route for this activity (no data, nothing found, timeout): shown, not choosable. */
    data class Failed(val message: String) : ActivityRowState
    /** No route is followed (free recording): choosing switches at once. */
    data object Plain : ActivityRowState
}

data class ActivityRow(val activity: Activity, val sac: Int, val state: ActivityRowState) {
    val choosable: Boolean get() = state is ActivityRowState.Ready || state == ActivityRowState.Plain || state == ActivityRowState.Idle
}

/** The check-and-switch sheet (Navigate → tap the activity icon). Stays until a row or "Keep …" is chosen. */
data class ActivityCheckUi(
    val current: Activity,
    val sac: Int,
    val rows: List<ActivityRow>,
    /** E.g. "Waiting for GPS…" or "No route followed …"; null = none. */
    val note: String? = null,
)

/** The check sheet's pure rules (unit-tested, ActivityCheckRulesTest). */
object ActivityCheckRules {
    /**
     * Hike's T chip [level] chosen while a route is followed (K1): the Hike row now stands for that level and is routed
     * for it (Checking – only this row, owner 2026-11-07); hiking at that very level already → no Hike row (nothing to switch to). Hiking at another level →
     * a Hike row for the new level, first in the list.
     */
    fun withHikeLevel(ui: ActivityCheckUi, level: Int): ActivityCheckUi {
        val others = ui.rows.filter { it.activity != Activity.HIKE }
        if (ui.current == Activity.HIKE && level == ui.sac) return ui.copy(rows = others)
        val row = ActivityRow(Activity.HIKE, level, ActivityRowState.Checking)
        val old = ui.rows.indexOfFirst { it.activity == Activity.HIKE }
        val rows = if (old >= 0) ui.rows.toMutableList().also { it[old] = row } else listOf(row) + others
        return ui.copy(rows = rows)
    }

    /**
     * Row [a] was chosen at [level] (owner 2026-11-07: only what is chosen is computed): that row alone is routed
     * (Checking); a row still routing from an earlier tap is cancelled and plain again; found / failed rows stay.
     */
    fun checking(ui: ActivityCheckUi, a: Activity, level: Int): ActivityCheckUi = ui.copy(rows = ui.rows.map {
        when {
            it.activity == a -> it.copy(sac = level, state = ActivityRowState.Checking)
            it.state == ActivityRowState.Checking -> it.copy(state = ActivityRowState.Idle)
            else -> it
        }
    })

    /** The T level the chips show as chosen: the Hike row's level, else the current level while hiking, else none. */
    fun chipLevel(ui: ActivityCheckUi): Int =
        ui.rows.firstOrNull { it.activity == Activity.HIKE }?.sac ?: if (ui.current == Activity.HIKE) ui.sac else 0
}

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
    Surface(shape = RoundedCornerShape(topStart = OnPathShapes.SHEET_DP.dp, topEnd = OnPathShapes.SHEET_DP.dp), color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth().warmShadow(OnPathShadow.HIGH, RoundedCornerShape(topStart = OnPathShapes.SHEET_DP.dp, topEnd = OnPathShapes.SHEET_DP.dp))) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
            Text(str(if (free) Res.string.nav_check_recording_as else Res.string.nav_check_navigating_as), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ui.current.icon, null, tint = OnPathColors.Green, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(10.dp))
                Text(NavText.activityName(ui.current, ui.sac), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            // hiking: the T level can change on its own (K1) – the chips under the current activity
            if (ui.current == Activity.HIKE) androidx.compose.foundation.layout.Box(Modifier.padding(top = 8.dp)) { SacChips(ActivityCheckRules.chipLevel(ui), onHikeLevel) }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text(str(Res.string.nav_check_switch_to), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (!free) Text(str(Res.string.nav_check_rest_of_route), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ui.note?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            ui.rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider()
                ActivityRowItem(row) { onChoose(row.activity) }
                // Hike's T1–T5 under its row, as in the plan's activity dialog (owner 2026-11-06, K1)
                if (row.activity == Activity.HIKE && ui.current != Activity.HIKE) SacChips(ActivityCheckRules.chipLevel(ui), onHikeLevel)
            }
            Spacer(Modifier.size(12.dp))
            Button(colors = ActionButtons.go(), onClick = onKeep, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(str(Res.string.nav_check_keep, NavText.activityName(ui.current, ui.sac)), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ActivityRowItem(row: ActivityRow, onClick: () -> Unit) {
    val s = row.state
    val status = activityRowStatus(s)
    val name = NavText.activityName(row.activity, row.sac)
    val switchLabel = str(Res.string.nav_check_switch_to_cd, name)
    val dim = !row.choosable && s !is ActivityRowState.Checking
    val content = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
        .clickable(enabled = row.choosable, onClickLabel = switchLabel, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = listOf(name, status).filter { it.isNotEmpty() }.joinToString(". ") }
        .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(row.activity.icon, null, tint = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else OnPathColors.Green,
            modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = content)
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium, color = when {
                s is ActivityRowState.Ready && s.fit.fits -> OnPathColors.Good
                // "not suited" is a warning about the way (amber); red only for "no route found"
                s is ActivityRowState.Ready -> OnPathColors.Warn
                s is ActivityRowState.Failed -> OnPathColors.Error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            })
        }
        when (s) {
            ActivityRowState.Checking -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            is ActivityRowState.Ready -> Text(instrument(Format.duration(s.fit.timeS), MaterialTheme.typography.titleSmall),
                style = MaterialTheme.typography.titleSmall)
            else -> Unit
        }
    }
}
