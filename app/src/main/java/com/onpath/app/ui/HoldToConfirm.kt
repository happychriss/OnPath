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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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

/**
 * The lock as "hold to confirm" (owner 2026-11-06 22:20, variant A of https://claude.ai/artifact/3noAFaW5cjWVYBDHReLUrN,
 * replaces the slider): while Navigate is locked the bottom bar's buttons are light; a finger held on one fills it with
 * its full colour from the left in [HOLD_MS] – only when full it fires (+ a short buzz). Let go earlier: the band runs
 * back and nothing happens; the line "Gedrückt halten" shows for [HINT_MS] so a short tap is never silent. Pure rules
 * here (`HoldRuleTest`); the drawing and the finger in [HoldButton].
 */
object HoldRule {
    /** How long the finger holds until the button fires (owner: "1 oder 2 Sekunden"; 1 s – a stop at the lights). */
    const val HOLD_MS = 1_000
    /** How long "Gedrückt halten" shows after a release before the end. */
    const val HINT_MS = 2_000L
    /** The band runs back from full in this long (shorter from less). */
    const val BACK_MS = 300

    /** How full the band is after [heldMs] of holding, starting at [from] (0..1). */
    fun progress(heldMs: Long, from: Float = 0f): Float =
        (from.coerceIn(0f, 1f) + heldMs.coerceAtLeast(0) / HOLD_MS.toFloat()).coerceIn(0f, 1f)

    /** The rest of the hold from a band already at [from] (a second touch while it still runs back). */
    fun remainingMs(from: Float): Int = ((1f - from.coerceIn(0f, 1f)) * HOLD_MS).toInt()

    /** The button fires when the band is full – the finger may still be down. */
    fun fires(progress: Float): Boolean = progress >= 1f

    enum class Release { FIRED, BACK_WITH_HINT }

    /** What a lifted finger means: after the band was full it already fired; earlier = back, and the hint. */
    fun release(progress: Float): Release = if (fires(progress)) Release.FIRED else Release.BACK_WITH_HINT

    /** How long the band runs back from [progress]. */
    fun backMs(progress: Float): Int = (BACK_MS * progress.coerceIn(0f, 1f)).toInt()
}

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
    onEarlyRelease: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    outline: Color? = null,
    content: @Composable (Color) -> Unit,
) {
    val band = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val fire by rememberUpdatedState(onFire)
    val early by rememberUpdatedState(onEarlyRelease)
    Box(
        modifier.clip(shape).background(light)
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
                        band.animateTo(1f, tween(HoldRule.remainingMs(band.value), easing = LinearEasing))
                        if (HoldRule.fires(band.value)) {
                            fired = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            fire()
                        }
                    }
                    waitForUpOrCancellation()
                    if (!fired && HoldRule.release(band.value) == HoldRule.Release.BACK_WITH_HINT) {
                        run.cancel()
                        early()
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

