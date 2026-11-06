package com.onpath.app.ui

import com.onpath.strings.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/**
 * Navigate without a recording: ONE big blue "Planen" (56 dp, full width) – opens the plan. Locked ([hold]): held ~1 s
 * it fills and fires ([HoldButton], owner 2026-11-06 variant A of the hold lock); the lock off: a plain tap.
 */
@Composable
fun ChangePlanButton(onClick: () -> Unit, modifier: Modifier = Modifier, hold: Boolean = false) {
    val word = str(Res.string.nav_change_plan)
    if (!hold) {
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionEdit, contentColor = OnPathColors.CardWhite),
            contentPadding = PaddingValues(horizontal = 24.dp), modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Filled.Edit, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(word, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        return
    }
    HoldBar(modifier) { hint ->
        HoldButton(word, OnPathColors.ActionEdit, TourActions.EditTonal, TourActions.OnEditTonal, onClick, hint,
            Modifier.fillMaxWidth().height(56.dp)) { c -> HoldLabel(Icons.Filled.Edit, word, c) }
    }
}

/**
 * The locked bar's frame: the small line over the buttons – at rest "🔒 Gesperrt · Knopf gedrückt halten" only until the
 * user has fired one held button (owner 2026-11-07, [HoldRule.pillShows]; the light buttons say "locked" by themselves),
 * after a release before the end "Gedrückt halten" in walnut for [HoldRule.HINT_MS] (a short tap is never silent).
 */
@Composable
private fun HoldBar(modifier: Modifier, bar: @Composable (onEarly: () -> Unit) -> Unit) {
    var strongAt by remember { mutableLongStateOf(0L) }
    val strong = strongAt != 0L
    LaunchedEffect(strongAt) { if (strongAt != 0L) { kotlinx.coroutines.delay(HoldRule.HINT_MS); strongAt = 0L } }
    Column(modifier.fillMaxWidth()) {
        if (HoldRule.pillShows(LocalHoldLearned.current, strong)) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
            Surface(shape = OnPathShapes.Pill, color = if (strong) OnPathColors.Walnut else OnPathColors.Paper, modifier = Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Pill)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (strong) Icons.Filled.TouchApp else Icons.Filled.Lock, null, Modifier.size(18.dp),
                        tint = if (strong) OnPathColors.CardWhite else OnPathColors.Walnut)
                    Spacer(Modifier.width(6.dp))
                    Text(str(if (strong) Res.string.nav_hold_hint else Res.string.nav_hold_hint_rest),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (strong) OnPathColors.CardWhite else OnPathColors.Walnut)
                }
            }
        }
        bar { strongAt = System.nanoTime() }
    }
}

@Composable
private fun HoldLabel(icon: ImageVector?, text: String?, color: Color) {
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, Modifier.size(if (text == null) 24.dp else 22.dp), tint = color)
        if (icon != null && text != null) Spacer(Modifier.width(8.dp))
        if (text != null) Text(text, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1, softWrap = false)
    }
}

/**
 * The tour's ONE bottom bar in Navigate, with and without the lock (owner 2026-11-06, variant 2 of
 * https://claude.ai/artifact/VhoxV9qrd4nNfz9FzgAULi; "Stopp und Pause an der Stelle ist logischer"): the status card
 * carries no buttons; at the bottom green "⏸ Pause" | blue "✎ Planen" (56 dp, equally wide). Paused the row is green
 * "Weiter" · green outlined "Beenden" (asks first, like everywhere) · a round blue 56 dp pen = Planen – ending is offered
 * only out of a pause, as in sports apps. A large font drops the icons and, paused, puts Planen in a second full-width
 * line – never a cut word ([TourBottomBarRules]). Locked ([hold], owner 2026-11-06 22:20, variant A of
 * https://claude.ai/artifact/3noAFaW5cjWVYBDHReLUrN – replaces the slider): the same buttons light, each fires only
 * when held ~1 s ([HoldButton]); the lock off: a plain tap.
 */
@Composable
fun TourBottomBar(paused: Boolean, onPause: (Boolean) -> Unit, onEnd: () -> Unit, onPlan: () -> Unit, modifier: Modifier = Modifier,
                  hold: Boolean = false) {
    if (hold) HoldBar(modifier) { hint -> TourBottomBarRow(paused, onPause, onEnd, onPlan, Modifier, hint) }
    else TourBottomBarRow(paused, onPause, onEnd, onPlan, modifier, null)
}

/** [TourBottomBar]'s buttons; [holdHint] non-null = locked (held buttons, the hint on an early release). */
@Composable
private fun TourBottomBarRow(paused: Boolean, onPause: (Boolean) -> Unit, onEnd: () -> Unit, onPlan: () -> Unit, modifier: Modifier,
                             holdHint: (() -> Unit)?) {
    val pauseWord = str(Res.string.nav_action_pause)
    val resumeWord = str(Res.string.common_resume)
    val endWord = str(Res.string.nav_end_short)
    val planWord = str(Res.string.nav_change_plan)
    val tm = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleMedium
    val density = LocalDensity.current
    val green = ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionGo, contentColor = OnPathColors.CardWhite)
    val blue = ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionEdit, contentColor = OnPathColors.CardWhite)
    val go = @Composable { word: String, icon: ImageVector?, m: Modifier, act: () -> Unit ->
        if (holdHint != null) HoldButton(word, OnPathColors.ActionGo, TourActions.GoTonal, TourActions.OnGoTonal, act, holdHint,
            m.height(56.dp)) { c -> HoldLabel(icon, word, c) }
        else Button(onClick = act, colors = green, contentPadding = if (icon == null) PaddingValues(horizontal = 16.dp) else ButtonDefaults.ContentPadding,
            modifier = m.heightIn(min = 56.dp)) {
            if (icon != null) { Icon(icon, null, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)) }
            Text(word, style = style, maxLines = 1, softWrap = false)
        }
    }
    val edit = @Composable { icons: Boolean, m: Modifier ->
        if (holdHint != null) HoldButton(planWord, OnPathColors.ActionEdit, TourActions.EditTonal, TourActions.OnEditTonal, onPlan, holdHint,
            m.height(56.dp)) { c -> HoldLabel(if (icons) Icons.Filled.Edit else null, planWord, c) }
        else Button(onClick = onPlan, colors = blue, modifier = m.heightIn(min = 56.dp)) {
            if (icons) { Icon(Icons.Filled.Edit, null, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)) }
            Text(planWord, style = style, maxLines = 1, softWrap = false)
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        fun w(t: String) = with(density) { tm.measure(t, style).size.width.toDp().value }
        val width = maxWidth.value
        if (!paused) {
            val icons = TourBottomBarRules.runningWithIcons(w(pauseWord), w(planWord), width)
            val row = TourBottomBarRules.runningInOneRow(w(pauseWord), w(planWord), width)
            val pause = @Composable { m: Modifier -> go(pauseWord, if (icons) Icons.Filled.Pause else null, m) { onPause(true) } }
            val plan = @Composable { m: Modifier -> edit(icons, m) }
            if (row) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { pause(Modifier.weight(1f)); plan(Modifier.weight(1f)) }
            else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { pause(Modifier.fillMaxWidth()); plan(Modifier.fillMaxWidth()) }
        } else {
            val oneRow = TourBottomBarRules.pausedInOneRow(w(resumeWord), w(endWord), width)
            val resume = @Composable { m: Modifier -> go(resumeWord, null, m) { onPause(false) } }
            val end = @Composable { m: Modifier ->
                if (holdHint != null) HoldButton(endWord, OnPathColors.ActionGo, OnPathColors.Paper, OnPathColors.ActionGo, onEnd, holdHint,
                    m.height(56.dp), outline = OnPathColors.ActionGo) { c -> HoldLabel(null, endWord, c) }
                else OutlinedButton(onClick = onEnd, border = BorderStroke(1.5.dp, OnPathColors.ActionGo),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = OnPathColors.Paper, contentColor = OnPathColors.ActionGo),
                    contentPadding = PaddingValues(horizontal = 16.dp), modifier = m.heightIn(min = 56.dp)) {
                    Text(endWord, style = style, maxLines = 1, softWrap = false)
                }
            }
            if (oneRow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                resume(Modifier.weight(1f)); end(Modifier.weight(1f))
                if (holdHint != null) HoldButton(planWord, OnPathColors.ActionEdit, TourActions.EditTonal, TourActions.OnEditTonal, onPlan, holdHint,
                    Modifier.size(56.dp), shape = CircleShape) { c -> HoldLabel(Icons.Filled.Edit, null, c) }
                else FilledIconButton(onClick = onPlan, modifier = Modifier.size(56.dp).semantics { contentDescription = planWord },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = OnPathColors.ActionEdit, contentColor = OnPathColors.CardWhite)) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(24.dp))
                }
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { resume(Modifier.weight(1f)); end(Modifier.weight(1f)) }
                edit(true, Modifier.fillMaxWidth())
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
    /** Planen's light blue while locked and its dark blue words (≥ 7:1; owner 2026-11-06, variant A of the hold lock). */
    val EditTonal = OnPathColors.EditTonal
    val OnEditTonal = OnPathColors.OnEditTonal

}
