package com.onpath.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlanStatus
import com.onpath.strings.*

/**
 * The plan has no route on purpose: says why, and calculates it on request (stays until then – no timeout). [busy]: the
 * run its button started goes – the button spins, no waiting sentence (owner 2026-11-27). The button is the plan's thick
 * filled blue one ([ActionButtons.planGo], owner 2026-11-27 „muss dicker“ – the planner's filled twin), at least 48 dp tall;
 * from [LARGE_FONT] on it goes under the sentence, full width (never squeezed or cut).
 */
@Composable
internal fun NotRoutedBar(why: NotRouted, busy: Boolean = false, onPlanAnyway: () -> Unit) {
    val large = LocalDensity.current.fontScale >= LARGE_FONT
    val text = @Composable { m: Modifier ->
        Text(PlanStatus.notRoutedText(why), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = m)
    }
    val button = @Composable { m: Modifier ->
        androidx.compose.material3.Button(colors = ActionButtons.planGo(), onClick = onPlanAnyway, enabled = !busy,
            modifier = m.heightIn(min = 48.dp)) {
            if (busy && com.onpath.app.ui.progress.WaitMotion.othersMove()) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OnPathColors.PlanAccent) // on the disabled (busy) button
                Spacer(Modifier.width(8.dp))
            }
            // a long leg: „Berechnen“ asks again with its estimate (Warten / Zwischenziel) – no „Trotzdem planen“ (owner 2026-11-27)
            Text(if (why == NotRouted.LONG) str(Res.string.plan_calculate) else PlanStatus.notRoutedAction(why),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
    if (large) Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        text(Modifier)
        button(Modifier.fillMaxWidth().padding(top = 6.dp))
    } else Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        text(Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        button(Modifier)
    }
}

/** From this font scale the button goes under the sentence. */
private const val LARGE_FONT = 1.3f
