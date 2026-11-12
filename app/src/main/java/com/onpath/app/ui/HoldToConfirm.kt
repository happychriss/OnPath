package com.onpath.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow

/**
 * The lock as "hold to confirm" (owner 2026-11-06 22:20, variant A of https://claude.ai/artifact/3noAFaW5cjWVYBDHReLUrN,
 * replaces the slider): while Navigate is locked the bottom bar's buttons are light; a finger held on one fills it with
 * its full colour from the left in the chosen time ([LocalHoldMs], default [HoldRule.HOLD_MS]) – only when full it fires (+ a short buzz). Let go earlier: the band runs
 * back and nothing happens; "Gedrückt halten" shows for [HINT_MS] right above the tapped button, with a short tick, so a
 * short tap is never silent (owner 2026-11-07: nothing at rest, only after a short tap). Pure rules
 * here (`HoldRuleTest`); the drawing and the finger in [HoldButton].
 */
object HoldRule {
    /**
     * How long the finger holds until the button fires by default (owner: "1 oder 2 Sekunden"; 1 s – a stop at the
     * lights); the user's time comes from Settings → Navigation ([com.onpath.app.data.HoldSetting], [LocalHoldMs]).
     */
    const val HOLD_MS = com.onpath.app.data.HoldSetting.DEFAULT_MS
    /** How long "Gedrückt halten" shows after a release before the end. */
    const val HINT_MS = 2_000L
    /** The band runs back from full in this long (shorter from less). */
    const val BACK_MS = 300

    /** How full the band is after [heldMs] of holding, starting at [from] (0..1), for a hold of [holdMs]. */
    fun progress(heldMs: Long, from: Float = 0f, holdMs: Int = HOLD_MS): Float =
        if (holdMs <= 0) 1f
        else (from.coerceIn(0f, 1f) + heldMs.coerceAtLeast(0) / holdMs.toFloat()).coerceIn(0f, 1f)

    /** The rest of a hold of [holdMs] from a band already at [from] (a second touch while it still runs back). */
    fun remainingMs(from: Float, holdMs: Int = HOLD_MS): Int =
        ((1f - from.coerceIn(0f, 1f)) * holdMs.coerceAtLeast(0)).toInt()

    /** The button fires when the band is full – the finger may still be down. */
    fun fires(progress: Float): Boolean = progress >= 1f

    enum class Release { FIRED, BACK_WITH_HINT }

    /** What a lifted finger means: after the band was full it already fired; earlier = back, and the hint. */
    fun release(progress: Float): Release = if (fires(progress)) Release.FIRED else Release.BACK_WITH_HINT

    /** How long the band runs back from [progress]. */
    fun backMs(progress: Float): Int = (BACK_MS * progress.coerceIn(0f, 1f)).toInt()

    /**
     * Where the hint "Gedrückt halten" sits over the locked bar (owner 2026-11-07, 09:30: never at rest – only after a
     * short tap, right above the tapped button): its left edge in the bar, centred over the button's centre
     * [buttonCenter] (both in the bar's own px) and kept inside the bar ([barWidth]) – a button at the edge pushes it in.
     */
    fun hintLeft(buttonCenter: Float, hintWidth: Float, barWidth: Float): Float =
        (buttonCenter - hintWidth / 2f).coerceIn(0f, (barWidth - hintWidth).coerceAtLeast(0f))

    /**
     * Fills the band from [from] to full in a hold of [holdMs], by the frame clock – each frame's value goes to [show];
     * returns when full (= the button fires). Never a tween: Compose stretches a tween by the phone's animation scale –
     * with animations off (Accessibility → "Remove animations", every CI emulator) the band was full on its first frame
     * and a short tap fired the hold (GitHub 2026-11-07: "Held button fired" 50 ms after a 21 ms tap on a 2 s hold;
     * `HoldFillTest`).
     */
    suspend fun fill(from: Float, holdMs: Int, show: suspend (Float) -> Unit) {
        val startMs = withFrameMillis { it }
        while (true) {
            val p = withFrameMillis { progress(it - startMs, from, holdMs) }
            show(p)
            if (fires(p)) return
        }
    }
}

/** The hold time the bar's [HoldButton]s use (Settings → Navigation, provided by Navigate; screenshot tests: the default). */
val LocalHoldMs = androidx.compose.runtime.compositionLocalOf { HoldRule.HOLD_MS }

/** Told when a held button fired, just before its action runs; nothing by default. */
val LocalOnHoldFired = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/**
 * One button of the locked bar: [light] ground with [onLight] words at rest; while held the band in [solid] grows from
 * the left and the words under it turn white. [outline] draws a border (the outlined "Beenden"). TalkBack: one action
 * "[label]" that fires at once – a screen reader user acts on purpose (double tap), holding is not needed there.
 */
@Composable
fun HoldButton(
    label: String,
    solid: Color,
    light: Color,
    onLight: Color,
    onFire: () -> Unit,
    /** A release before the end; gets the button's centre x in the window (px) so the hint can sit right above it. */
    onEarlyRelease: (centerX: Float) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = OnPathShapes.Pill,
    outline: Color? = null,
    content: @Composable (Color) -> Unit,
) {
    val band = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val fire by rememberUpdatedState(onFire)
    val early by rememberUpdatedState(onEarlyRelease)
    val holdMs by rememberUpdatedState(LocalHoldMs.current)
    val learnt by rememberUpdatedState(LocalOnHoldFired.current)
    var centerX by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.onGloballyPositioned { centerX = it.boundsInWindow().center.x }.floatShadow(shape)
            .clip(shape).background(light)
            .then(if (outline != null) Modifier.border(BorderStroke(1.5.dp, outline), shape) else Modifier)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                onClick(label) { fire(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var fired = false
                    val run = scope.launch {
                        HoldRule.fill(band.value, holdMs) { band.snapTo(it) }
                        fired = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        learnt() // first: the unlock window opens – then the action, which may shut it again ("Weiter")
                        fire()
                    }
                    waitForUpOrCancellation()
                    if (!fired && HoldRule.release(band.value) == HoldRule.Release.BACK_WITH_HINT) {
                        run.cancel()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) // a short tick: "not yet"
                        early(centerX)
                        scope.launch { band.animateTo(0f, tween(HoldRule.backMs(band.value), easing = LinearEasing)) }
                    } else scope.launch { band.snapTo(0f) }
                }
            },
    ) {
        Box(Modifier.fillMaxSize()) { content(onLight) }
        Box(Modifier.fillMaxSize().drawWithContent {
            val p = band.value
            if (p > 0f) clipRect(right = size.width * p) { this@drawWithContent.drawContent() }
        }) {
            Box(Modifier.fillMaxSize().background(solid)) { content(OnPathColors.CardWhite) }
        }
    }
}

