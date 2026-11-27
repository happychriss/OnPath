package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.strings.*

/**
 * The empty plan during a tour (owner 2026-11-12 on his Pixel: he deleted all points during a tour, missed that it still
 * recorded and looked for "Hier starten"; then: „Eigentlich ist es gar keine Frage – wenn die Tour noch läuft, kann ich
 * nur ein neues Ziel eingeben … ‚Tour läuft noch', ‚neues Ziel festlegen'"): the sheet's title "● Tour läuft noch"
 * instead of "Neuer Plan", the row "Neues Ziel festlegen ›" (the search) with "Ziel suchen oder Karte gedrückt halten →
 * Hierhin" – every route during a tour starts where you are – and "Weiter geht's" at the bottom as before. An ANZEIGE in
 * the sheet: no box, no running time (nothing ticks), nothing over the map.
 */
object TourStillRunning {
    /** Only while a tour runs and the plan is empty; with points the sheet shows the plan, without a tour the start guide asks. */
    fun shows(pointCount: Int, touring: Boolean): Boolean = pointCount == 0 && touring
}

/** "● Tour läuft noch" – the recording's orange dot, grey while the tour is stopped. */
@Composable
internal fun TourStillRunningTitle(paused: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (paused) OnPathColors.Grey else OnPathColors.Rec))
        Spacer(Modifier.width(10.dp))
        Text(str(Res.string.plan_tour_still_running), style = MaterialTheme.typography.titleLarge)
    }
}

/** "Neues Ziel festlegen ›" – opens the search; the small line says the other way (hold the map → Hierhin). */
@Composable
internal fun TourNewTargetRow(onSearch: () -> Unit) {
    val blue = OnPathColors.PlanAccent
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onSearch)
        .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(blue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Search, null, tint = blue, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(str(Res.string.plan_tour_new_target), style = MaterialTheme.typography.titleMedium, color = blue,
                    fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = blue, modifier = Modifier.size(22.dp))
            }
            Text(str(Res.string.plan_tour_new_target_line), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
