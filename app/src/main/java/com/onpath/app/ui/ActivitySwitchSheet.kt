package com.onpath.app.ui

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
    /** The rest of the route is being routed for this activity (spinner). */
    data object Checking : ActivityRowState
    data class Ready(val fit: ActivityFit) : ActivityRowState
    /** No route for this activity (no data, nothing found, timeout): shown, not choosable. */
    data class Failed(val message: String) : ActivityRowState
    /** No route is followed (free recording): choosing switches at once. */
    data object Plain : ActivityRowState
}

data class ActivityRow(val activity: Activity, val sac: Int, val state: ActivityRowState) {
    val choosable: Boolean get() = state is ActivityRowState.Ready || state == ActivityRowState.Plain
}

/** The check-and-switch sheet (Navigate → tap the activity icon). Stays until a row or "Keep …" is chosen. */
data class ActivityCheckUi(
    val current: Activity,
    val sac: Int,
    val rows: List<ActivityRow>,
    /** E.g. "Waiting for GPS…" or "No route followed …"; null = none. */
    val note: String? = null,
)

/** "✓ fits" / "⚠ 1.4 km not suited for trekking bikes, steps 40 m · +1.2 km" / "Checking…" (pure, for tests). */
internal fun activityRowStatus(s: ActivityRowState): String = when (s) {
    ActivityRowState.Checking -> "Checking the rest of the route…"
    ActivityRowState.Plain -> ""
    is ActivityRowState.Failed -> s.message
    is ActivityRowState.Ready -> buildString {
        val f = s.fit
        if (f.fits) append("✓ fits") else append("⚠ ").append(f.problems.joinToString(", "))
        val e = Math.round(abs(f.extraM) / 10) * 10.0
        if (e >= 100) append(" · ").append(if (f.extraM > 0) "+" else "−").append(Format.distance(e))
    }
}

/**
 * "You are navigating as: Hike T3" and, for every other activity, how the rest of the followed route fits it
 * (fits / problems + its time), computed offline in the background (spinner per row). Choosing a row asks the usual
 * "Take new route / Keep original" question with the new route as the dashed proposal. Rows ≥ 56 dp, button 56 dp.
 */
@Composable
fun ActivitySwitchSheet(ui: ActivityCheckUi, onChoose: (Activity) -> Unit, onKeep: () -> Unit, modifier: Modifier = Modifier) {
    val free = ui.rows.any { it.state == ActivityRowState.Plain }
    Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
            Text(if (free) "You are recording as" else "You are navigating as", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ui.current.icon, null, tint = OnPathColors.Green, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(10.dp))
                Text(NavText.activityName(ui.current, ui.sac), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text("Switch to", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (!free) Text("for the rest of the route", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ui.note?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            ui.rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider()
                ActivityRowItem(row) { onChoose(row.activity) }
            }
            Spacer(Modifier.size(12.dp))
            Button(onClick = onKeep, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Keep ${NavText.activityName(ui.current, ui.sac)}", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ActivityRowItem(row: ActivityRow, onClick: () -> Unit) {
    val s = row.state
    val status = activityRowStatus(s)
    val name = NavText.activityName(row.activity, row.sac)
    val dim = !row.choosable && s !is ActivityRowState.Checking
    val content = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
        .clickable(enabled = row.choosable, onClickLabel = "Switch to $name", onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = listOf(name, status).filter { it.isNotEmpty() }.joinToString(". ") }
        .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(row.activity.icon, null, tint = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else OnPathColors.Green,
            modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = content)
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium, color = when {
                s is ActivityRowState.Ready && s.fit.fits -> OnPathColors.Good
                // "not suited" is a warning about the way (amber); red only for "no route found" (review 2026-10-24 B17)
                s is ActivityRowState.Ready -> OnPathColors.Warn
                s is ActivityRowState.Failed -> OnPathColors.Error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            })
        }
        when (s) {
            ActivityRowState.Checking -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            is ActivityRowState.Ready -> Text(Format.duration(s.fit.timeS), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            else -> Unit
        }
    }
}
