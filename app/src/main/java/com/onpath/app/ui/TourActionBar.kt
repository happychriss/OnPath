package com.onpath.app.ui

import com.onpath.strings.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/**
 * After the slide (owner 2026-11-05, Navigate C; the blue button reads "Plan"): the tour's two actions directly under the route info in the status card
 * – "Pause" (green tonal, "Fortsetzen" while paused) and "Tour beenden" (green outlined; asks first, "Aufnahme beenden?").
 * Words, no red, ≥ 48 dp. Each closes the unlocked state ([onClose]); paused the actions stay (Fortsetzen at once).
 */
@Composable
fun TourCardActions(paused: Boolean, onPause: (Boolean) -> Unit, onEnd: () -> Unit, onClose: () -> Unit) {
    val pauseWord = str(if (paused) Res.string.common_resume else Res.string.nav_action_pause)
    val endWord = str(Res.string.nav_end_tour)
    val tm = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // side by side while both words fit whole (24 dp padding each side of a button), else one under the other
            val need = with(density) {
                (tm.measure(pauseWord, style).size.width + tm.measure(endWord, style).size.width).toDp()
            } + 24.dp * 4 + 8.dp
            val pause = @Composable { m: Modifier ->
                FilledTonalButton(onClick = { onClose(); onPause(!paused) },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = TourActions.GoTonal, contentColor = TourActions.OnGoTonal),
                    modifier = m.heightIn(min = 48.dp)) { Text(pauseWord, maxLines = 1, softWrap = false) }
            }
            val end = @Composable { m: Modifier ->
                OutlinedButton(onClick = { onClose(); onEnd() },
                    border = BorderStroke(1.5.dp, OnPathColors.ActionGo),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnPathColors.ActionGo),
                    modifier = m.heightIn(min = 48.dp)) { Text(endWord, maxLines = 1, softWrap = false) }
            }
            if (TourActions.sideBySide(need.value, maxWidth.value)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pause(Modifier.weight(1f)); end(Modifier.weight(1f))
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pause(Modifier.fillMaxWidth()); end(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Where the slider was, after the slide: ONE big blue "Plan" (owner 2026-11-05: large so nobody misses it – full
 * width, 56 dp like the green main buttons; the word is just "Plan") – opens the plan; the tour goes on. [onLock]: an
 * open lock left of it (owner 2026-11-05: lock / unlock as a sign) – shows the screen is unlocked, a tap locks it again;
 * null = nothing to lock (the lock is off in the settings, or paused – then it stays open).
 */
@Composable
fun ChangePlanButton(onClick: () -> Unit, modifier: Modifier = Modifier, onLock: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // "Sperren" as a full button beside "Plan" (owner 2026-11-06: equal partners, not a small lock icon), where the
        // slider was – the way back to the locked state is as easy to hit as the way into Plan; equal widths, so
        // "Sperren" stays whole with large font (1.4 : 1 cut it to "Sperre")
        if (onLock != null) OutlinedButton(onClick = onLock, contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
            Icon(Icons.Filled.Lock, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(str(Res.string.nav_lock), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionEdit, contentColor = OnPathColors.CardWhite),
            contentPadding = PaddingValues(horizontal = 24.dp), modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
            Icon(Icons.Filled.Edit, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(str(Res.string.nav_change_plan), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

/** Rules of the unlocked state (pure). */
object TourActions {
    /** The unlocked state locks again by itself after this long without a tap (phone back in the pocket). */
    const val OPEN_MS = 30_000L
    /** Pause's light green and its dark green words (≥ 7:1). */
    val GoTonal = Color(0xFFD5EADF)
    val OnGoTonal = Color(0xFF0D3A27)

    /** Pause and Tour beenden side by side while both words fit whole ([needDp] incl. padding), else stacked. */
    fun sideBySide(needDp: Float, maxWidthDp: Float): Boolean = needDp <= maxWidthDp
}
