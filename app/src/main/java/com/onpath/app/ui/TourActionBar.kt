package com.onpath.app.ui

import com.onpath.strings.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What the Navigate slider unlocks (owner 2026-11-01: "mit dem Schieber die anderen Buttons freischalten"): in place of
 * the slider, three plain verbs – Pause (Resume while paused) · Change route · End – and ✕. The slider stays the guard
 * against pocket and glove touches; behind it every button says what it does (was: "Stop" = switch to Plan, a red ■ =
 * end the recording, "Continue" = back to Navigate). Change route opens the plan with its "Change route … Done" header;
 * End asks first ("End recording?"). The bar closes with ✕, with any of its buttons, or by itself after
 * [TourActions.OPEN_MS] (riding on, phone in the pocket).
 */
@Composable
fun TourActionBar(paused: Boolean, onPause: (Boolean) -> Unit, onChangeRoute: () -> Unit, onEnd: () -> Unit,
                  onClose: () -> Unit, modifier: Modifier = Modifier, closable: Boolean = true) {
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primary, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                str(if (paused) Res.string.common_resume else Res.string.nav_action_pause), Color.White,
                Modifier.weight(1f)) { onClose(); onPause(!paused) }
            ActionButton(Icons.Filled.Edit, str(Res.string.plan_slide_change_route), Color.White, Modifier.weight(1f)) {
                onClose(); onChangeRoute()
            }
            ActionButton(Icons.Filled.Stop, str(Res.string.nav_end_short), Color.White, Modifier.weight(1f), iconTint = OnPathColors.OnDarkError) {
                onClose(); onEnd()
            }
            if (closable) IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = str(Res.string.nav_actions_close), tint = OnPathColors.OnDarkMuted)
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, tint: Color, modifier: Modifier, iconTint: Color = tint,
                         onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = 0.12f),
        modifier = modifier.padding(horizontal = 3.dp).heightIn(min = 64.dp)) {
        Column(Modifier.padding(vertical = 6.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, color = tint, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** Rules of the unlocked bar (pure). */
object TourActions {
    /** The unlocked bar closes by itself after this long without a tap (phone back in the pocket). */
    const val OPEN_MS = 30_000L
}
