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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
                // Pause / Fortsetzen keeps the bar open (owner 2026-11-06: the pause changes nothing about the lock)
                FilledTonalButton(onClick = { onPause(!paused) },
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
 * null = nothing to lock (the lock is off in the settings).
 */
@Composable
fun ChangePlanButton(onClick: () -> Unit, modifier: Modifier = Modifier, onLock: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // "Sperren" as a full button beside "Plan" (owner 2026-11-06: equal partners, not a small lock icon), where the
        // slider was – the way back to the locked state is as easy to hit as the way into Plan; equal widths, so
        // "Sperren" stays whole with large font (1.4 : 1 cut it to "Sperre")
        // White fill (owner 2026-11-06: it sits right on the map – without a fill the map showed through) and the green
        // edge of "Tour beenden": navigation = green
        if (onLock != null) OutlinedButton(onClick = onLock, contentPadding = PaddingValues(horizontal = 12.dp),
            border = BorderStroke(1.5.dp, OnPathColors.ActionGo),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = OnPathColors.CardWhite, contentColor = OnPathColors.ActionGo),
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

/**
 * Navigate with the lock OFF (owner 2026-11-06, variant 2 of https://claude.ai/artifact/VhoxV9qrd4nNfz9FzgAULi): the
 * status card carries no buttons; at the bottom green "⏸ Pause" | blue "✎ Planen" (56 dp, equally wide). Pause works at
 * once; paused the row is green "Weiter" · green outlined "Beenden" (asks first, like everywhere) · a round blue 56 dp
 * pen = Planen – ending is offered only out of a pause, as in sports apps. A large font drops the icons and, paused,
 * puts Planen in a second full-width line – never a cut word ([LockOffBarRules]).
 */
@Composable
fun LockOffBar(paused: Boolean, onPause: (Boolean) -> Unit, onEnd: () -> Unit, onPlan: () -> Unit, modifier: Modifier = Modifier) {
    val pauseWord = str(Res.string.nav_action_pause)
    val resumeWord = str(Res.string.common_resume)
    val endWord = str(Res.string.nav_end_short)
    val planWord = str(Res.string.nav_change_plan)
    val tm = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleMedium
    val density = LocalDensity.current
    val green = ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionGo, contentColor = OnPathColors.CardWhite)
    val blue = ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionEdit, contentColor = OnPathColors.CardWhite)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        fun w(t: String) = with(density) { tm.measure(t, style).size.width.toDp().value }
        val width = maxWidth.value
        if (!paused) {
            val icons = LockOffBarRules.runningWithIcons(w(pauseWord), w(planWord), width)
            val row = LockOffBarRules.runningInOneRow(w(pauseWord), w(planWord), width)
            val pause = @Composable { m: Modifier ->
                Button(onClick = { onPause(true) }, colors = green, modifier = m.heightIn(min = 56.dp)) {
                    if (icons) { Icon(Icons.Filled.Pause, null, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)) }
                    Text(pauseWord, style = style, maxLines = 1, softWrap = false)
                }
            }
            val plan = @Composable { m: Modifier ->
                Button(onClick = onPlan, colors = blue, modifier = m.heightIn(min = 56.dp)) {
                    if (icons) { Icon(Icons.Filled.Edit, null, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)) }
                    Text(planWord, style = style, maxLines = 1, softWrap = false)
                }
            }
            if (row) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { pause(Modifier.weight(1f)); plan(Modifier.weight(1f)) }
            else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { pause(Modifier.fillMaxWidth()); plan(Modifier.fillMaxWidth()) }
        } else {
            val oneRow = LockOffBarRules.pausedInOneRow(w(resumeWord), w(endWord), width)
            val resume = @Composable { m: Modifier ->
                Button(onClick = { onPause(false) }, colors = green, contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = m.heightIn(min = 56.dp)) { Text(resumeWord, style = style, maxLines = 1, softWrap = false) }
            }
            val end = @Composable { m: Modifier ->
                OutlinedButton(onClick = onEnd, border = BorderStroke(1.5.dp, OnPathColors.ActionGo),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = OnPathColors.CardWhite, contentColor = OnPathColors.ActionGo),
                    contentPadding = PaddingValues(horizontal = 16.dp), modifier = m.heightIn(min = 56.dp)) {
                    Text(endWord, style = style, maxLines = 1, softWrap = false)
                }
            }
            if (oneRow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                resume(Modifier.weight(1f)); end(Modifier.weight(1f))
                FilledIconButton(onClick = onPlan, modifier = Modifier.size(56.dp).semantics { contentDescription = planWord },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = OnPathColors.ActionEdit, contentColor = OnPathColors.CardWhite)) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(24.dp))
                }
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { resume(Modifier.weight(1f)); end(Modifier.weight(1f)) }
                Button(onClick = onPlan, colors = blue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                    Text(planWord, style = style, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Layout rules of [LockOffBar] (pure, dp): never cut a word. */
object LockOffBarRules {
    private const val GAP = 8f
    private const val PAD = 24f // Button's side padding
    private const val ICON = 30f // icon + its spacer
    /** Both running buttons with their icons fit side by side (each half wide). */
    fun runningWithIcons(pauseW: Float, planW: Float, width: Float): Boolean =
        maxOf(pauseW, planW) + ICON + 2 * PAD <= (width - GAP) / 2
    /** Without icons the two still fit side by side, else they stack. */
    fun runningInOneRow(pauseW: Float, planW: Float, width: Float): Boolean =
        maxOf(pauseW, planW) + 2 * PAD <= (width - GAP) / 2
    /** Paused: "Weiter" and "Beenden" (16 dp padding) each in half of what is left beside the round 56 dp Planen. */
    fun pausedInOneRow(resumeW: Float, endW: Float, width: Float): Boolean =
        maxOf(resumeW, endW) + 2 * 16f <= (width - 56f - 2 * GAP) / 2
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
