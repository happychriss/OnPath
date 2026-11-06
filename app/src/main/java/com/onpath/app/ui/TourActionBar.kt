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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
 * Navigate without a recording, unlocked: ONE big blue "Planen" where the slider was (56 dp, full width) – opens the plan.
 * No "Sperren" button (owner 2026-11-06): it locks again after 30 s, with a tap on the map and with Back.
 */
@Composable
fun ChangePlanButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionEdit, contentColor = OnPathColors.CardWhite),
        contentPadding = PaddingValues(horizontal = 24.dp), modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(Icons.Filled.Edit, null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(str(Res.string.nav_change_plan), style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

/**
 * The tour's ONE bottom bar in Navigate, unlocked or with the lock off (owner 2026-11-06, variant 2 of
 * https://claude.ai/artifact/VhoxV9qrd4nNfz9FzgAULi; "Stopp und Pause an der Stelle ist logischer" – the same with the
 * lock): the status card carries no buttons; at the bottom green "⏸ Pause" | blue "✎ Planen" (56 dp, equally wide).
 * Pause works at once; paused the row is green "Weiter" · green outlined "Beenden" (asks first, like everywhere) · a
 * round blue 56 dp pen = Planen – ending is offered only out of a pause, as in sports apps. A large font drops the
 * icons and, paused, puts Planen in a second full-width line – never a cut word ([TourBottomBarRules]).
 */
@Composable
fun TourBottomBar(paused: Boolean, onPause: (Boolean) -> Unit, onEnd: () -> Unit, onPlan: () -> Unit, modifier: Modifier = Modifier) {
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
            val icons = TourBottomBarRules.runningWithIcons(w(pauseWord), w(planWord), width)
            val row = TourBottomBarRules.runningInOneRow(w(pauseWord), w(planWord), width)
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
            val oneRow = TourBottomBarRules.pausedInOneRow(w(resumeWord), w(endWord), width)
            val resume = @Composable { m: Modifier ->
                Button(onClick = { onPause(false) }, colors = green, contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = m.heightIn(min = 56.dp)) { Text(resumeWord, style = style, maxLines = 1, softWrap = false) }
            }
            val end = @Composable { m: Modifier ->
                OutlinedButton(onClick = onEnd, border = BorderStroke(1.5.dp, OnPathColors.ActionGo),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = OnPathColors.Paper, contentColor = OnPathColors.ActionGo),
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

/** Layout rules of [TourBottomBar] (pure, dp): never cut a word. */
object TourBottomBarRules {
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

}
