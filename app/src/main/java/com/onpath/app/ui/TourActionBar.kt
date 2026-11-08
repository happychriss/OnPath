package com.onpath.app.ui

import com.onpath.strings.*

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.liveRegion
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
import com.onpath.app.domain.tour.UnlockWindow

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
 * The locked bar's frame (owner 2026-11-07, 09:30): nothing over the buttons at rest – the light buttons say "locked" by
 * themselves. A release before the end shows "Gedrückt halten" for [HoldRule.HINT_MS] right above the tapped button
 * (walnut pill, white words, 16 sp; [HoldRule.hintLeft]) – [HoldButton] adds a short tick. The hint takes its own line
 * over the buttons while it shows, so it never covers a button (the bottom column grows upwards).
 */
@Composable
private fun HoldBar(modifier: Modifier, bar: @Composable (onEarly: (Float) -> Unit) -> Unit) {
    var hintAt by remember { mutableLongStateOf(0L) }
    var tappedX by remember { mutableFloatStateOf(0f) } // the tapped button's centre, window px
    var barLeft by remember { mutableFloatStateOf(0f) }
    var barWidth by remember { mutableFloatStateOf(0f) }
    var hintWidth by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(hintAt) { if (hintAt != 0L) { kotlinx.coroutines.delay(HoldRule.HINT_MS); hintAt = 0L } }
    Column(modifier.fillMaxWidth().onGloballyPositioned { barLeft = it.boundsInWindow().left; barWidth = it.size.width.toFloat() }) {
        if (hintAt != 0L || LocalHoldHintShown.current) {
            val left = HoldRule.hintLeft(tappedX - barLeft, hintWidth, barWidth)
            Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Surface(shape = OnPathShapes.Pill, color = OnPathColors.Walnut,
                    modifier = Modifier.onSizeChanged { hintWidth = it.width.toFloat() }
                        .offset { androidx.compose.ui.unit.IntOffset(left.toInt(), 0) }
                        .warmShadow(OnPathShadow.HIGH, OnPathShapes.Pill)
                        .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.TouchApp, null, Modifier.size(20.dp), tint = OnPathColors.CardWhite)
                        Spacer(Modifier.width(8.dp))
                        Text(str(Res.string.nav_hold_hint), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            color = OnPathColors.CardWhite)
                    }
                }
            }
        }
        bar { x -> tappedX = x; hintAt = System.nanoTime() }
    }
}

/** Screenshot tests: draw the "Gedrückt halten" hint as right after a short tap (over the bar's first button). */
val LocalHoldHintShown = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
private fun HoldLabel(icon: ImageVector?, text: String?, color: Color, compact: Boolean = false) {
    Row(Modifier.fillMaxSize().padding(horizontal = if (compact) 10.dp else 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, Modifier.size(if (text == null) 24.dp else if (compact) 20.dp else 22.dp), tint = color)
        if (icon != null && text != null) Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
        if (text != null) Text(text, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1, softWrap = false)
    }
}

/**
 * The tour's ONE bottom bar in Navigate, with and without the lock (owner 2026-11-06, variant 2 of
 * https://claude.ai/artifact/VhoxV9qrd4nNfz9FzgAULi): the status card carries no buttons; at the bottom green
 * "⏸ Anhalten" | blue "✎ Planen" (56 dp, equally wide; owner 2026-11-07: "Anhalten" says both – pause and end).
 * Stopped (owner 2026-11-07, variant 3 of the "Anhalten" sketch): no band of its own – the status card says
 * "⏸ Angehalten seit 14:32" ([StoppedText]) – and three same-built buttons with icons: green "▶ Weiter" (the widest),
 * dark green "■ Tour beenden" (asks first: the end sheet; never red – red is only for delete), blue "✎ Planen"; one row
 * where it fits, else Weiter full width over the other two, a large font stacks all three ([TourBottomBarRules]) – never
 * a cut word. One rule everywhere (owner 2026-11-08): **pale = hold, strong = tap**. Locked ([hold], owner 2026-11-06
 * 22:20, variant A of https://claude.ai/artifact/3noAFaW5cjWVYBDHReLUrN): every button pale, each fires only when held
 * ~1 s ([HoldButton]; the band fills in its strong colour); inside the unlock window ([windowOpen]) and with the lock off
 * every button strong on a plain tap.
 */
@Composable
fun TourBottomBar(paused: Boolean, onPause: (Boolean) -> Unit, onEnd: () -> Unit, onPlan: () -> Unit, modifier: Modifier = Modifier,
                  hold: Boolean = false, windowOpen: Boolean = false) {
    // per button ([UnlockWindow.needsHold], owner 2026-11-08): inside the 10 s window after a fired hold every button is a
    // tap, "Weiter" too; outside it every button wants a hold while the lock is on ([hold])
    val held = { b: UnlockWindow.BarButton -> UnlockWindow.needsHold(b, hold, windowOpen) }
    val shown = if (paused) listOf(UnlockWindow.BarButton.RESUME, UnlockWindow.BarButton.END, UnlockWindow.BarButton.PLAN)
        else listOf(UnlockWindow.BarButton.PAUSE, UnlockWindow.BarButton.PLAN)
    if (shown.any(held)) HoldBar(modifier) { hint -> TourBottomBarRow(paused, onPause, onEnd, onPlan, Modifier, hint, held) }
    else TourBottomBarRow(paused, onPause, onEnd, onPlan, modifier, null, held)
}

/** The stopped head's words (pure, unit-tested): since when the tour stands still, and what rests. */
object StoppedText {
    /** The clock time the stop began – "Angehalten seit 14:32" (24 h, the phone's time zone; [zone] for tests). */
    fun since(sinceMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).apply { timeZone = zone }.format(java.util.Date(sinceMs))
    /** A free tour (no route followed) rests only the recording; with a route the guidance rests too. */
    fun freeLine(free: Boolean): Boolean = free
}

/** [TourBottomBar]'s buttons; [held] = this button wants a hold ([holdHint]: the hint on an early release). */
@Composable
private fun TourBottomBarRow(paused: Boolean, onPause: (Boolean) -> Unit, onEnd: () -> Unit, onPlan: () -> Unit, modifier: Modifier,
                             holdHint: ((Float) -> Unit)?, held: (UnlockWindow.BarButton) -> Boolean) {
    val hintOr: (Float) -> Unit = holdHint ?: {}
    val pauseWord = str(Res.string.nav_action_pause)
    val resumeWord = str(Res.string.common_resume)
    val endWord = str(Res.string.nav_end_tour)
    val planWord = str(Res.string.nav_change_plan)
    val tm = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleMedium
    val density = LocalDensity.current
    val green = ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionGo, contentColor = OnPathColors.CardWhite)
    val blue = ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionEdit, contentColor = OnPathColors.CardWhite)
    val go = @Composable { word: String, icon: ImageVector?, m: Modifier, hold: Boolean, act: () -> Unit ->
        if (hold) HoldButton(word, OnPathColors.ActionGo, TourActions.GoTonal, TourActions.OnGoTonal, act, hintOr,
            m.height(56.dp)) { c -> HoldLabel(icon, word, c) }
        else Button(onClick = act, colors = green, contentPadding = if (icon == null) PaddingValues(horizontal = 16.dp) else ButtonDefaults.ContentPadding,
            modifier = m.heightIn(min = 56.dp)) {
            if (icon != null) { Icon(icon, null, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)) }
            Text(word, style = style, maxLines = 1, softWrap = false)
        }
    }
    val planHeld = held(UnlockWindow.BarButton.PLAN)
    val edit = @Composable { icons: Boolean, m: Modifier ->
        if (planHeld) HoldButton(planWord, OnPathColors.ActionEdit, TourActions.EditTonal, TourActions.OnEditTonal, onPlan, hintOr,
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
            val pause = @Composable { m: Modifier -> go(pauseWord, if (icons) Icons.Filled.Pause else null, m, held(UnlockWindow.BarButton.PAUSE)) { onPause(true) } }
            val plan = @Composable { m: Modifier -> edit(icons, m) }
            if (row) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { pause(Modifier.weight(1f)); plan(Modifier.weight(1f)) }
            else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { pause(Modifier.fillMaxWidth()); plan(Modifier.fillMaxWidth()) }
        } else {
            // stopped (owner 2026-11-07, "Anhalten" variant 5): ▶ Weiter · ■ Tour beenden · ✎ Planen, icons and words together,
            // a compact label (20 dp icon, 6 dp gap, 10 dp sides) so more fits in one row; today's 56 dp
            // pale = hold, strong = tap (owner 2026-11-08): held – the light ground, the band fills in the strong colour;
            // a tap – the strong colour with white words
            val one = @Composable { word: String, icon: ImageVector, strong: Color, light: Color, onLight: Color,
                                   hold: Boolean, act: () -> Unit, m: Modifier ->
                if (hold) HoldButton(word, strong, light, onLight, act, hintOr, m.height(56.dp)) { c ->
                    HoldLabel(icon, word, c, compact = true) }
                else Button(onClick = act, colors = ButtonDefaults.buttonColors(containerColor = strong, contentColor = OnPathColors.CardWhite),
                    contentPadding = PaddingValues(horizontal = 10.dp), modifier = m.heightIn(min = 56.dp)) {
                    Icon(icon, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                    Text(word, style = style, maxLines = 1, softWrap = false)
                }
            }
            val resume = @Composable { m: Modifier -> one(resumeWord, Icons.Filled.PlayArrow, OnPathColors.ActionGo, TourActions.GoTonal,
                TourActions.OnGoTonal, held(UnlockWindow.BarButton.RESUME), { onPause(false) }, m) }
            // ■ the square "stop" of every recorder – the flag is the route's destination everywhere in OnPath
            val end = @Composable { m: Modifier -> one(endWord, Icons.Filled.Stop, TourActions.EndStrong, TourActions.GoTonal,
                TourActions.OnGoTonal, held(UnlockWindow.BarButton.END), onEnd, m) }
            val plan = @Composable { m: Modifier -> one(planWord, Icons.Filled.Edit, OnPathColors.ActionEdit, TourActions.EditTonal,
                TourActions.OnEditTonal, held(UnlockWindow.BarButton.PLAN), onPlan, m) }
            val side = TourBottomBarRules.stoppedSide(w(endWord), w(planWord))
            when (TourBottomBarRules.stopped(TourBottomBarRules.stoppedNeed(w(resumeWord)), side, width, density.fontScale)) {
                TourBottomBarRules.Stopped.ONE_ROW -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    resume(Modifier.weight(1f)); end(Modifier.width(side.dp)); plan(Modifier.width(side.dp))
                }
                TourBottomBarRules.Stopped.TWO_ROWS -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    resume(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { end(Modifier.weight(1f)); plan(Modifier.weight(1f)) }
                }
                TourBottomBarRules.Stopped.STACK -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    resume(Modifier.fillMaxWidth()); end(Modifier.fillMaxWidth()); plan(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Layout rules of [TourBottomBar] (pure, dp): never cut a word. */
object TourBottomBarRules {
    private const val GAP = 8f
    private const val PAD = 24f // Button's side padding
    const val ICON = 30f // icon + its spacer
    /** Both running buttons with their icons fit side by side (each half wide). */
    fun runningWithIcons(pauseW: Float, planW: Float, width: Float): Boolean =
        maxOf(pauseW, planW) + ICON + 2 * PAD <= (width - GAP) / 2
    /** Without icons the two still fit side by side, else they stack. */
    fun runningInOneRow(pauseW: Float, planW: Float, width: Float): Boolean =
        maxOf(pauseW, planW) + 2 * PAD <= (width - GAP) / 2
    /** A stopped button's width: the word + the compact icon (20 dp + 6 dp gap) + 10 dp each side + 4 dp air. */
    fun stoppedNeed(wordW: Float): Float = wordW + 26f + 2 * 10f + 4f
    /** "Tour beenden" and "Planen" are equally wide (at least 96 dp, so short words still make a balanced group). */
    fun stoppedSide(endW: Float, planW: Float): Float = maxOf(stoppedNeed(maxOf(endW, planW)), 96f)

    enum class Stopped { ONE_ROW, TWO_ROWS, STACK }
    /**
     * Stopped: one row when "▶ Weiter" ([resumeNeed]) gets what is left beside the two [side] buttons and is still the
     * widest; else Weiter full width over the two side by side; a large font (≥ 1.5×, owner) or a too narrow screen
     * stacks all three full width.
     */
    fun stopped(resumeNeed: Float, side: Float, width: Float, fontScale: Float = 1f): Stopped = when {
        width - 2 * side - 2 * GAP >= maxOf(side, resumeNeed) -> Stopped.ONE_ROW
        fontScale < 1.5f && side <= (width - GAP) / 2 && resumeNeed <= width -> Stopped.TWO_ROWS
        else -> Stopped.STACK
    }
}

/** The tour bar's tonal colours (the unlock window's rule: [com.onpath.app.domain.tour.UnlockWindow]). */
object TourActions {
    /** Anhalten's light green and its dark green words (≥ 7:1). */
    val GoTonal = Color(0xFFD5EADF)
    val OnGoTonal = Color(0xFF0D3A27)
    /** "Tour beenden"'s strong tone (a tap; owner 2026-11-08, variant 2): the dark green of its pale button's words. */
    val EndStrong = OnGoTonal
    /** Planen's light blue while locked and its dark blue words (≥ 7:1; owner 2026-11-06, variant A of the hold lock). */
    val EditTonal = OnPathColors.EditTonal
    val OnEditTonal = OnPathColors.OnEditTonal

}
