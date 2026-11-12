package com.onpath.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Full pages (My routes, Settings, search, route details, phone setup …) fade in and out quickly instead of popping
 * (owner 2026-11-12: "ganz schnell, aber smooth", like Komoot) – a fade with a very small scale, Material's shared
 * axis Z in miniature. Dialogs and bottom sheets keep their own animations.
 */
object PageMotion {
    const val ENTER_MS = 180
    const val EXIT_MS = 150
    /** The page grows from this size while it fades in and shrinks to it while it fades out. */
    const val SCALE = 0.98f

    /** Transition lengths for the system animator duration scale; 0 (animations off / reduced motion) = instant. */
    fun durations(animatorScale: Float): Pair<Int, Int> =
        if (animatorScale <= 0f || animatorScale.isNaN()) 0 to 0 else ENTER_MS to EXIT_MS
}

/**
 * A full page shown while [visible], faded in and out ([PageMotion]). Shown from the start (first composition,
 * screenshots) it is there at once. While it fades out it takes no taps (a second tap must not hit the leaving page)
 * and gives up the keyboard focus. Back handling stays with the caller, next to the `if (visible)` it had before.
 */
@Composable
fun PageFade(visible: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val (enterMs, exitMs) = remember { PageMotion.durations(ModeMotion.animatorScale(context)) }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(enterMs, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(enterMs, easing = LinearOutSlowInEasing), initialScale = PageMotion.SCALE),
        exit = fadeOut(tween(exitMs, easing = FastOutLinearInEasing)) +
            scaleOut(tween(exitMs, easing = FastOutLinearInEasing), targetScale = PageMotion.SCALE),
    ) {
        val leaving = transition.targetState == EnterExitState.PostExit
        val focus = LocalFocusManager.current
        LaunchedEffect(leaving) { if (leaving) focus.clearFocus(force = true) }
        Box(Modifier.fillMaxSize()) {
            content()
            if (leaving) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            })
        }
    }
}

/**
 * [PageFade] for a page that shows a value ([value] non-null = shown): while it fades out the page keeps the last
 * value, so it does not go blank or jump while it leaves.
 */
@Composable
fun <T : Any> PageFadeOf(value: T?, content: @Composable (T) -> Unit) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    PageFade(visible = value != null) { last?.let { content(it) } }
}
