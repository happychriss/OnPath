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
import androidx.compose.material.icons.filled.PlayArrow
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
 * over the buttons while it shows, so it never covers the pause band or a button (the bottom column grows upwards).
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
                  hold: Boolean = false, pausedSinceMs: Long? = null, free: Boolean = false) {
    // Paused (owner 2026-11-07, variant C of https://claude.ai/artifact/24XVQ4goVZixT53fnQDn7H): a calm paper band over
    // the buttons says what stands still and since when – one column, so the band, the lock line and the buttons stack
    // and never overlap (the "Neuer Plan" card sits above the measured column)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (paused) PauseBand(pausedSinceMs, free)
        if (hold) HoldBar(Modifier) { hint -> TourBottomBarRow(paused, onPause, onEnd, onPlan, Modifier, hint) }
        else TourBottomBarRow(paused, onPause, onEnd, onPlan, Modifier, null)
    }
}

/** Screenshot tests: a fixed "now" for the pause band (null = the real clock, ticking every second). */
val LocalPauseClock = androidx.compose.runtime.staticCompositionLocalOf<Long?> { null }

/**
 * The pause band (owner 2026-11-07, variant C): "⏸ Pause · 12:04" with the pause time running, and what stands still –
 * "Aufnahme und Navigation angehalten", on a free tour "Aufnahme angehalten" ([PauseBandText]). Paper, walnut words,
 * no button of its own (Weiter is right under it).
 */
@Composable
private fun PauseBand(sinceMs: Long?, free: Boolean) {
    val fixed = LocalPauseClock.current
    var now by remember { mutableLongStateOf(fixed ?: System.currentTimeMillis()) }
    if (fixed == null && sinceMs != null) LaunchedEffect(sinceMs) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000L - (now - sinceMs).mod(1_000L)) }
    }
    val title = sinceMs?.let { str(Res.string.nav_pause_band, PauseBandText.elapsed(now - it)) } ?: str(Res.string.nav_action_pause)
    Surface(shape = OnPathShapes.Card, color = OnPathColors.Paper,
        border = BorderStroke(1.dp, OnPathColors.Walnut.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().warmShadow(OnPathShadow.LOW, OnPathShapes.Card)
            .semantics(mergeDescendants = true) {}) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Pause, null, tint = OnPathColors.Walnut, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // the running time in equal-width digits: nothing jiggles while it counts
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold,
                    color = OnPathColors.Walnut)
                Text(str(if (PauseBandText.freeLine(free)) Res.string.nav_pause_band_line_free else Res.string.nav_pause_band_line),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The pause band's words (pure, unit-tested). */
object PauseBandText {
    /** How long the pause lasts: "0:07", "12:04", after an hour "1:02:30" (never negative – a clock set back shows 0:00). */
    fun elapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
    /** A free tour (no route followed) pauses only the recording; with a route the guidance stands still too. */
    fun freeLine(free: Boolean): Boolean = free
}

/** [TourBottomBar]'s buttons; [holdHint] non-null = locked (held buttons, the hint on an early release). */
@Composable
private fun TourBottomBarRow(paused: Boolean, onPause: (Boolean) -> Unit, onEnd: () -> Unit, onPlan: () -> Unit, modifier: Modifier,
                             holdHint: ((Float) -> Unit)?) {
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
            // "▶ Weiter" (owner 2026-11-07): the play sign says what the green button does; dropped only when it would not fit
            val playFits = TourBottomBarRules.pausedInOneRow(w(resumeWord) + TourBottomBarRules.ICON, w(endWord), width)
            val oneRow = playFits || TourBottomBarRules.pausedInOneRow(w(resumeWord), w(endWord), width)
            val resume = @Composable { m: Modifier -> go(resumeWord, if (playFits || !oneRow) Icons.Filled.PlayArrow else null, m) { onPause(false) } }
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
    const val ICON = 30f // icon + its spacer
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
