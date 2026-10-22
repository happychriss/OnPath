package com.onpath.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import com.onpath.app.debug.FrameJankMeter
import com.onpath.app.debug.Problems

/**
 * Plan ⇄ Navigate transition (owner decision for 0.10.0): the leaving controls slide out at the edges and fade, the new
 * ones slide in, in [DURATION_MS]. Only the control layers move – the map (MapHost) is a sibling of [ModeSwitch] and is
 * neither recomposed nor re-laid out, and no camera move is added (CLAUDE.md "Map camera"). Pure parts unit tested
 * (ModeSwitchTest).
 */
object ModeMotion {
    const val DURATION_MS = 250

    /** Transition length for the system animator duration scale; 0 (animations off / reduced motion) = instant. */
    fun durationMs(animatorScale: Float): Int = if (animatorScale <= 0f || animatorScale.isNaN()) 0 else DURATION_MS

    /** "Mode switch → Navigate" – the events-trace label of one transition (FrameJankMeter). */
    fun traceLabel(to: AppMode): String = "Mode switch → " + if (to == AppMode.PLAN) "Plan" else "Navigate"

    /** Settings → Developer options → Animator duration scale (0 with "Remove animations"). Never throws. */
    fun animatorScale(context: Context): Float = try {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (e: Exception) {
        Problems.info("ui", "Animator duration scale unknown; normal mode switch animation", e); 1f
    }
}

/** Which edge a control group of a mode layer sits on: it leaves / comes over that edge. */
enum class ModeEdge { TOP, BOTTOM }

private val MAX_SLIDE = 160.dp

private class ModeMotionScope(val scope: AnimatedVisibilityScope, val durationMs: Int)

private val LocalModeMotion = compositionLocalOf<ModeMotionScope?> { null }

/**
 * Slide of a control group with the mode switch (top bar up, sheet / card down). Outside [ModeSwitch] (screenshots,
 * previews) it is a plain [Modifier] – nothing changes.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun Modifier.modeEdge(edge: ModeEdge): Modifier {
    val m = LocalModeMotion.current ?: return this
    val d = m.durationMs
    val sign = if (edge == ModeEdge.TOP) -1 else 1
    // a full-screen layer (the plan sheet's layout) moves at most this far – with the fade it reads as "slides away"
    val cap = with(LocalDensity.current) { MAX_SLIDE.roundToPx() }
    return with(m.scope) {
        this@modeEdge.animateEnterExit(
            enter = slideInVertically(tween(d, easing = LinearOutSlowInEasing)) { sign * minOf(it, cap) },
            exit = slideOutVertically(tween(d, easing = FastOutLinearInEasing)) { sign * minOf(it, cap) },
        )
    }
}

/**
 * The two control layers above the map. The leaving layer fades out (its edges slide away, [modeEdge]) and takes no
 * touches while it goes; the coming one fades / slides in. [durationMs] 0 = instant (reduced motion). Each transition
 * writes one frame-time line to the events trace ("Mode switch → Navigate: …").
 */
@Composable
fun ModeSwitch(
    mode: AppMode,
    plan: @Composable () -> Unit,
    navigate: @Composable () -> Unit,
    durationMs: Int = rememberModeDuration(),
) {
    val transition = updateTransition(mode, label = "mode")
    ModeFrameMeter(transition)
    for (layer in AppMode.entries) {
        transition.AnimatedVisibility(
            visible = { it == layer },
            enter = fadeIn(tween(durationMs, easing = LinearOutSlowInEasing)),
            exit = fadeOut(tween(durationMs, easing = FastOutLinearInEasing)),
        ) {
            CompositionLocalProvider(LocalModeMotion provides ModeMotionScope(this, durationMs)) {
                Box(Modifier.fillMaxSize()) {
                    if (layer == AppMode.PLAN) plan() else navigate()
                    // leaving: no taps on what is going away (a tap on a fading button must not act)
                    if (transition.targetState != layer) TouchShield()
                }
            }
        }
    }
}

/** Transition length for this phone's animator duration scale (read once per screen). */
@Composable
private fun rememberModeDuration(): Int {
    val context = LocalContext.current
    return remember(context) { ModeMotion.durationMs(ModeMotion.animatorScale(context)) }
}

/** Swallows every touch on its area (the leaving layer while it animates out). */
@Composable
private fun TouchShield() {
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent().changes.forEach { it.consume() }
        }
    })
}

/** Frame times of each mode switch → events trace (like the sheet's "Sheet move"). Not in previews / tests. */
@Composable
private fun ModeFrameMeter(transition: Transition<AppMode>) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    LaunchedEffect(window) {
        val meter = FrameJankMeter(window)
        try {
            snapshotFlow { transition.currentState to transition.targetState }.collect { (c, t) ->
                if (c != t) meter.start()
                else meter.stop()?.let { Problems.trace("mode", it.line(ModeMotion.traceLabel(t))) }
            }
        } finally {
            meter.stop()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
