package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
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
 * What the slider says (owner 2026-10-31): "Tour menu" with a small line "Change route · Pause · End" under it – the
 * small line only when it fits (large font: the main line only, never cut). Without a recording only "Change route".
 */
object SlideLabel {
    /** The small line fits on one line beside the thumb, and both lines fit into the track's height (px). */
    fun subFits(subWidth: Int, availWidth: Int, mainHeight: Int, subHeight: Int, availHeight: Int): Boolean =
        subWidth <= availWidth && mainHeight + subHeight <= availHeight
}

/**
 * "→ Tour menu": the only way out of Navigate. The thumb has to be dragged across ≥ 85 % of
 * the track; released earlier it springs back, so pocket or glove touches change nothing.
 * TalkBack users get a normal action ("Switch to plan mode").
 * [recording] false (Navigate without a recording): only "Change route" – Pause / End make no sense there.
 * [startFraction] places the thumb (screenshot tests only).
 */
@Composable
fun SlideToPlan(onSlid: () -> Unit, modifier: Modifier = Modifier, recording: Boolean = true, startFraction: Float = 0f) {
    val label = str(if (recording) Res.string.plan_slide_to_plan else Res.string.plan_slide_change_route)
    val sub = if (recording) str(Res.string.plan_slide_to_plan_sub) else null
    val switchLabel = str(Res.string.plan_switch_to_plan)
    val desc = str(Res.string.plan_slide_desc, if (sub == null) label else "$label: ${sub.replace(" · ", ", ")}")
    val slid by rememberUpdatedState(onSlid)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val mainStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    val subStyle = MaterialTheme.typography.bodyMedium
    val mainText = "→  $label"
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
        // the small line only when it fits (measured at the phone's font size): never cut, never an ellipsis
        val showSub = sub != null && with(density) {
            val m = measurer.measure(mainText, mainStyle, maxLines = 1, softWrap = false)
            val s = measurer.measure(sub, subStyle, maxLines = 1, softWrap = false)
            SlideLabel.subFits(s.size.width, (maxWidth - THUMB - TEXT_GAP * 2).roundToPx(), m.size.height, s.size.height,
                (TRACK_HEIGHT - PAD * 2).roundToPx())
        }

        // Filled part behind the thumb.
        Box(Modifier.fillMaxHeight().width(with(density) { offset.toDp() } + THUMB + PAD * 2)
            .clip(CircleShape).background(OnPathColors.Route.copy(alpha = 0.55f)))
        Column(
            Modifier.align(Alignment.Center).padding(start = THUMB).graphicsLayer { alpha = (1f - 2f * f).coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(mainText, color = Color.White, style = mainStyle, maxLines = 1, softWrap = false)
            if (showSub && sub != null) Text(sub, color = OnPathColors.OnDarkMuted, style = subStyle, maxLines = 1, softWrap = false)
        }
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
