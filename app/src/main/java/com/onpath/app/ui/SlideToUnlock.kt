package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Threshold logic of the slide switch. Offsets are thumb travel in px. */
object SlideThreshold {
    /** The thumb must travel at least this share of the track before release counts. */
    const val COMPLETE = 0.85f
    /** A tap moves the thumb this share of the track and back: "this slides". */
    const val NUDGE = 0.25f

    fun clamp(offsetPx: Float, maxPx: Float): Float = offsetPx.coerceIn(0f, maxPx.coerceAtLeast(0f))
    fun fraction(offsetPx: Float, maxPx: Float): Float = if (maxPx <= 0f) 0f else clamp(offsetPx, maxPx) / maxPx
    fun isComplete(offsetPx: Float, maxPx: Float): Boolean = maxPx > 0f && fraction(offsetPx, maxPx) >= COMPLETE
}

private val TRACK_HEIGHT = 64.dp
private val THUMB = 56.dp
private val PAD = 4.dp
/** Room kept free on each side of the text, so the small line never touches the thumb or runs into the round end. */
private val TEXT_GAP = 20.dp

/**
 * "» Entsperren" (owner 2026-11-05, Navigate C): the slider only unlocks the screen for changes – then Pause / Tour beenden
 * appear in the status card and "Route ändern" takes the slider's place (NavigateOverlay). The thumb has to be dragged across ≥ 85 % of
 * the track; released earlier it springs back, so pocket or glove touches change nothing.
 * TalkBack users get a normal action ("Switch to plan mode"). A tap (UX review B2) nudges the thumb a quarter of the way
 * and lets it spring back – it shows that the thumb slides, nothing opens.
 * [recording] false (Navigate without a recording): only "Route ändern" behind it – Pause / End make no sense there.
 * [startFraction] places the thumb (screenshot tests only).
 */
@Composable
fun SlideToPlan(onSlid: () -> Unit, modifier: Modifier = Modifier, recording: Boolean = true, startFraction: Float = 0f) {
    val label = str(Res.string.nav_slide_unlock)
    val switchLabel = label
    // TalkBack: "Entsperren. Schieben, um Pause, Tour beenden und Route ändern freizugeben."
    val desc = str(if (recording) Res.string.nav_slide_unlock_desc else Res.string.nav_slide_unlock_desc_plan)
    val slid by rememberUpdatedState(onSlid)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val mainStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    val mainText = "»  $label"
    var tapped by remember { mutableIntStateOf(0) }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(TRACK_HEIGHT).clip(CircleShape).background(MaterialTheme.colorScheme.primary) // the turn card's green (owner 2026-11-01)
            .pointerInput(Unit) { detectTapGestures { tapped++ } }
            .clearAndSetSemantics {
                contentDescription = desc
                onClick(label = switchLabel) { slid(); true }
                customActions = listOf(CustomAccessibilityAction(switchLabel) { slid(); true })
            },
    ) {
        val maxPx = with(density) { (maxWidth - THUMB - PAD * 2).toPx() }
        var offset by remember(maxPx) { mutableFloatStateOf(SlideThreshold.clamp(startFraction * maxPx, maxPx)) }
        var nudge by remember { mutableStateOf<Job?>(null) }
        val f = SlideThreshold.fraction(offset, maxPx)
        LaunchedEffect(tapped) {
            if (tapped == 0 || offset != 0f) return@LaunchedEffect
            nudge = scope.launch {
                animate(0f, SlideThreshold.NUDGE * maxPx, animationSpec = tween(220)) { v, _ -> offset = v }
                animate(offset, 0f, animationSpec = spring()) { v, _ -> offset = v }
            }
        }

        // Filled part behind the thumb.
        Box(Modifier.fillMaxHeight().width(with(density) { offset.toDp() } + THUMB + PAD * 2)
            .clip(CircleShape).background(Color.White.copy(alpha = 0.22f)))
        Column(
            Modifier.align(Alignment.Center).padding(start = THUMB).graphicsLayer { alpha = (1f - 2f * f).coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(mainText, color = Color.White, style = mainStyle, maxLines = 1, softWrap = false)
        }
        Surface(
            shape = CircleShape, color = Color.White, shadowElevation = 3.dp,
            modifier = Modifier.padding(PAD).size(THUMB).offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    state = rememberDraggableState { d -> offset = SlideThreshold.clamp(offset + d, maxPx) },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { nudge?.cancel(); nudge = null },
                    onDragStopped = {
                        if (SlideThreshold.isComplete(offset, maxPx)) { offset = 0f; slid() }
                        else scope.launch { animate(offset, 0f, animationSpec = spring()) { v, _ -> offset = v } }
                    },
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
        }
    }
}
