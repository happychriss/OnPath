package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Threshold logic of the slide switch. Offsets are thumb travel in px. */
object SlideThreshold {
    /** The thumb must travel at least this share of the track before release counts. */
    const val COMPLETE = 0.85f

    fun clamp(offsetPx: Float, maxPx: Float): Float = offsetPx.coerceIn(0f, maxPx.coerceAtLeast(0f))
    fun fraction(offsetPx: Float, maxPx: Float): Float = if (maxPx <= 0f) 0f else clamp(offsetPx, maxPx) / maxPx
    fun isComplete(offsetPx: Float, maxPx: Float): Boolean = maxPx > 0f && fraction(offsetPx, maxPx) >= COMPLETE
}

private val TRACK_HEIGHT = 64.dp
private val THUMB = 56.dp
private val PAD = 4.dp

/**
 * "→ Slide to plan": the only way out of Navigate. The thumb has to be dragged across ≥ 85 % of
 * the track; released earlier it springs back, so pocket or glove touches change nothing.
 * TalkBack users get a normal action ("Switch to plan mode").
 * [startFraction] places the thumb (screenshot tests only).
 */
@Composable
fun SlideToPlan(onSlid: () -> Unit, modifier: Modifier = Modifier, label: String = str(Res.string.plan_slide_to_plan), startFraction: Float = 0f) {
    val switchLabel = str(Res.string.plan_switch_to_plan)
    val desc = str(Res.string.plan_slide_desc, label)
    val slid by rememberUpdatedState(onSlid)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier.fillMaxWidth().height(TRACK_HEIGHT).clip(CircleShape).background(Color(0xE6202A33))
            .clearAndSetSemantics {
                contentDescription = desc
                onClick(label = switchLabel) { slid(); true }
                customActions = listOf(CustomAccessibilityAction(switchLabel) { slid(); true })
            },
    ) {
        val maxPx = with(density) { (maxWidth - THUMB - PAD * 2).toPx() }
        var offset by remember(maxPx) { mutableFloatStateOf(SlideThreshold.clamp(startFraction * maxPx, maxPx)) }
        val f = SlideThreshold.fraction(offset, maxPx)

        // Filled part behind the thumb.
        Box(Modifier.fillMaxHeight().width(with(density) { offset.toDp() } + THUMB + PAD * 2)
            .clip(CircleShape).background(OnPathColors.Route.copy(alpha = 0.55f)))
        Text(
            "→  $label", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center).padding(start = THUMB).graphicsLayer { alpha = (1f - 2f * f).coerceIn(0f, 1f) },
        )
        Surface(
            shape = CircleShape, color = Color.White, shadowElevation = 3.dp,
            modifier = Modifier.padding(PAD).size(THUMB).offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    state = rememberDraggableState { d -> offset = SlideThreshold.clamp(offset + d, maxPx) },
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        if (SlideThreshold.isComplete(offset, maxPx)) { offset = 0f; slid() }
                        else scope.launch { animate(offset, 0f, animationSpec = spring()) { v, _ -> offset = v } }
                    },
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = OnPathColors.Route, modifier = Modifier.size(30.dp))
            }
        }
    }
}
