package com.onpath.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlanStatus
import com.onpath.strings.*

/**
 * The plan has no route on purpose: says why, and calculates it on request (stays until then – no timeout). [busy]: the
 * run its button started goes – the button spins, no waiting sentence (owner 2026-11-27).
 */
@Composable
internal fun NotRoutedBar(why: NotRouted, busy: Boolean = false, onPlanAnyway: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(PlanStatus.notRoutedText(why), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(colors = ActionButtons.editText(), onClick = onPlanAnyway, enabled = !busy) {
            if (busy && com.onpath.app.ui.progress.WaitMotion.othersMove()) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OnPathColors.PlanAccent)
                Spacer(Modifier.width(8.dp))
            }
            // a long leg: „Berechnen“ asks again with its estimate (Warten / Zwischenziel) – no „Trotzdem planen“ (owner 2026-11-27)
            Text(if (why == NotRouted.LONG) str(Res.string.plan_calculate) else PlanStatus.notRoutedAction(why))
        }
    }
}
