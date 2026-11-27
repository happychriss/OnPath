package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.onpath.strings.*

/**
 * "◉ Aufnahme läuft" over the map while the user plans during a tour (owner 2026-11-27, variant A with sign 3): the tour
 * records on behind the plan – a quiet paper pill at the top centre, the red record sign; stopped ("⏸ Anhalten") it is
 * grey and says "Aufnahme angehalten" – nothing is recorded then. An ANZEIGE: no button, it goes when the tour ends or
 * Navigate comes back ("Weiter geht's" is the way back).
 */
object RecordingPillRule {
    /** Only while nothing else speaks at the top: a box, the quiet map pill, the search, the first-map card, the wait window. */
    fun shows(recording: Boolean, box: Boolean, mapPill: Boolean, search: Boolean, firstCard: Boolean, waitWindow: Boolean): Boolean =
        recording && !box && !mapPill && !search && !firstCard && !waitWindow
}

@Composable
fun RecordingPill(paused: Boolean, modifier: Modifier = Modifier) {
    val text = str(if (paused) Res.string.plan_rec_paused else Res.string.plan_rec_running)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(shape = OnPathShapes.Pill, color = OnPathColors.Paper,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = text }.warmShadow(OnPathShadow.LOW, OnPathShapes.Pill)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.RadioButtonChecked, null, tint = if (paused) OnPathColors.Grey else OnPathColors.Error,
                    modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = OnPathColors.Walnut, maxLines = 1)
            }
        }
    }
}
