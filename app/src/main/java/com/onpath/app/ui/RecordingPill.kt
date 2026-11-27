package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
 * records on behind the plan – a quiet paper pill at the top centre, the red record sign. Only while it records: a stopped
 * tour offers just ▶ Weiter · ■ Tour beenden, so Plan is reached while recording (owner 2026-11-27: "wir machen es
 * unnötig kompliziert") – no grey "angehalten" state. An ANZEIGE: no button; "Weiter geht's" is the way back.
 */
object RecordingPillRule {
    /** Only while nothing else speaks at the top: a box, the quiet map pill, the search, the first-map card, the wait window. */
    fun shows(recording: Boolean, box: Boolean, mapPill: Boolean, search: Boolean, firstCard: Boolean, waitWindow: Boolean): Boolean =
        recording && !box && !mapPill && !search && !firstCard && !waitWindow
}

/**
 * [onEnd] (owner 2026-11-27, variant A): „◉ Aufnahme läuft | Beenden“ – a fine line and the blue word, no filled button;
 * the whole pill (48 dp touch) opens the usual "Tour beenden?" sheet (save · cancel · delete to the bin), so a stray tap
 * loses nothing and nothing jumps to Navigate. null = the plain sign.
 */
@Composable
fun RecordingPill(modifier: Modifier = Modifier, onEnd: (() -> Unit)? = null) {
    val text = str(Res.string.plan_rec_running)
    val end = str(Res.string.plan_rec_end)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val touch = if (onEnd == null) Modifier.semantics(mergeDescendants = true) { contentDescription = text }
            else Modifier.minimumInteractiveComponentSize().clickable(role = Role.Button, onClickLabel = end, onClick = onEnd)
                .semantics(mergeDescendants = true) { contentDescription = "$text. $end" }
        Box(touch, contentAlignment = Alignment.Center) {
            Surface(shape = OnPathShapes.Pill, color = OnPathColors.Paper, modifier = Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Pill)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.RadioButtonChecked, null, tint = OnPathColors.Error, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall, color = OnPathColors.Walnut, maxLines = 1)
                    if (onEnd != null) {
                        Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(16.dp).background(OnPathColors.Line))
                        Text(end, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                            color = OnPathColors.ActionEdit, maxLines = 1)
                    }
                }
            }
        }
    }
}
