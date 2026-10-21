package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint

// ------------------------------------------------------------------ shared pieces

/** Rounded bottom sheet look used for every bottom panel. */
@Composable
fun BottomPanel(
    modifier: Modifier = Modifier,
    /**
     * Resizable panels: called with true for a swipe down (smaller), false for a swipe up (bigger). The sheet follows
     * the finger and settles with a spring (SheetDrag); a scrolling list inside hands its over-scroll to the sheet.
     */
    onSwipe: ((down: Boolean) -> Unit)? = null,
    /** Tap on the grab handle. */
    onHandleTap: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val sheet = if (onSwipe == null) null else rememberSheetDrag(onSwipe)
    Surface(
        modifier = modifier.fillMaxWidth().then(sheet?.modifier ?: Modifier),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.then(sheet?.heightModifier ?: Modifier.animateContentSize()).navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            // Material drag handle (32 × 4 dp) in a full-width 48 dp tall touch area when it does something.
            val handle = Modifier.fillMaxWidth().height(if (onHandleTap != null) 48.dp else 24.dp)
            Box(
                if (onHandleTap != null) handle.clickable(onClickLabel = "Resize panel", onClick = onHandleTap)
                    .semantics { contentDescription = "Resize panel" } else handle,
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(32.dp).height(4.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
            }
            content()
        }
    }
}

/** The pieces of a draggable sheet: [modifier] goes on the sheet (gestures), [heightModifier] on its content. */
private class SheetDragModifiers(val modifier: Modifier, val heightModifier: Modifier)

/**
 * A real draggable sheet (owner field test 0.8.9, polish-audit B4): while the finger is down the sheet height follows
 * it 1:1 (content clipped or the surface stretched); on release [SheetDrag.settle] picks smaller / same / bigger from
 * distance and fling speed, and the height springs from where the finger left it to the new size – no jump. Size
 * changes without a drag (handle tap, content changes) spring the same way. A scrolling list inside first scrolls;
 * its leftover drag (at its top, pulling down) moves the sheet (nested scroll).
 */
@Composable
private fun rememberSheetDrag(onSwipe: (down: Boolean) -> Unit): SheetDragModifiers {
    val density = LocalDensity.current
    val distancePx = with(density) { 56.dp.toPx() }
    val flingPxS = with(density) { 700.dp.toPx() }
    val minPx = with(density) { 72.dp.toPx() }
    val swipe by rememberUpdatedState(onSwipe)
    val scope = rememberCoroutineScope()
    val state = remember { SheetDragState() }
    // the natural height changed (size switched, content grew): spring there from what is shown now
    LaunchedEffect(state) {
        snapshotFlow { state.natural }.collectLatest { n ->
            if (n < 0f) return@collectLatest
            if (!state.shownSet) { state.anim.snapTo(n); state.shownSet = true } else
                state.anim.animateTo(n, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    fun start() {
        if (state.dragging) return
        state.raw = if (state.shownSet) state.anim.value else state.natural
        state.dragging = true
        state.height = state.raw
        scope.launch { state.anim.stop() }
    }
    fun drag(dy: Float) {  // dy > 0 = finger down = smaller
        if (!state.dragging) start()
        state.raw -= dy
        state.height = SheetDrag.follow(state.raw, minPx, state.max)
    }
    suspend fun release(velocityPxS: Float) {
        if (!state.dragging) return
        val shown = state.height
        val decision = SheetDrag.settle(shown, state.natural, velocityPxS, distancePx, flingPxS)
        state.anim.snapTo(shown)
        state.dragging = false
        when (decision) {
            SheetDrag.Settle.SMALLER -> swipe(true)
            SheetDrag.Settle.BIGGER -> swipe(false)
            SheetDrag.Settle.SAME -> {}
        }
        // spring to the (new) natural height; when the size switch changes it, the collector above takes over
        scope.launch {
            state.anim.animateTo(state.natural, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                initialVelocity = -velocityPxS)
        }
    }
    val nested = remember(state) {
        object : NestedScrollConnection {
            // the sheet was pulled down by the list's over-scroll: pushing up gives it back before the list scrolls
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!state.dragging || source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                val room = state.natural - state.height
                if (room <= 0f) return Offset.Zero
                val take = maxOf(available.y, -room)
                drag(take)
                return Offset(0f, take)
            }
            // the list is at its top and the finger keeps pulling down: the sheet follows
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                drag(available.y)
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!state.dragging) return Velocity.Zero
                release(available.y)
                return available
            }
        }
    }
    val gestures = Modifier
        .nestedScroll(nested)
        .draggable(
            state = rememberDraggableState { drag(it) },
            orientation = Orientation.Vertical,
            onDragStarted = { start() },
            onDragStopped = { v -> release(v) },
        )
    val height = Modifier.layout { measurable, constraints ->
        val p = measurable.measure(constraints.copy(minHeight = 0))
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else Float.MAX_VALUE
        state.max = maxH
        if (state.natural != p.height.toFloat()) state.natural = p.height.toFloat()
        val shown = when {
            state.dragging -> state.height
            state.shownSet -> state.anim.value
            else -> p.height.toFloat()
        }
        val h = shown.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(p.width, h) { p.place(0, 0) }
    }
    return SheetDragModifiers(gestures, height)
}

private class SheetDragState {
    val anim = Animatable(0f)
    var shownSet = false
    var natural by mutableFloatStateOf(-1f)
    var dragging by mutableStateOf(false)
    var height by mutableFloatStateOf(0f)
    var raw = 0f
    var max = Float.MAX_VALUE
}

@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = if (big) 28.sp else 18.sp, maxLines = 1)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Distance · ascent · descent · time; in a 2 × 2 grid when there is not room for four columns (dialogs). */
@Composable
fun StatsRow(distance: Double, ascent: Double, descent: Double, durationS: Double) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stats = listOf(Format.distance(distance) to "Distance", "↑ " + Format.meters(ascent) to "Ascent",
            "↓ " + Format.meters(descent) to "Descent", Format.duration(durationS) to "Time")
        val perRow = if (maxWidth < 340.dp) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth()) { row.forEach { (v, l) -> Stat(v, l, Modifier.weight(1f)) } }
            }
        }
    }
}

// ------------------------------------------------------------------ planning: see PlanPanel.kt

@Composable
fun ActionButtonSmall(icon: ImageVector, desc: String, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, desc) }
}

// ------------------------------------------------------------------ shown track

@Composable
fun TrackPanel(
    track: ShownTrack,
    isFollowed: Boolean,
    recording: Boolean,
    onFollow: () -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit,
    onHighlight: (GeoPoint?) -> Unit,
) {
    BottomPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(track.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            ActionButtonSmall(Icons.Filled.Close, "Close track", onClose)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.distance(track.distanceM), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(Format.duration(track.durationS), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 1.dp))
        }
        Text("↑ ${Format.meters(track.ascentM)}  ↓ ${Format.meters(track.descentM)}", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        ElevationProfile(track.points, color = OnPathColors.Gpx, onHighlight = onHighlight)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Export GPX", maxLines = 1)
            }
            Button(onClick = onFollow, enabled = !isFollowed || !recording, modifier = Modifier.weight(2f).height(56.dp)) {
                Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(8.dp))
                Text(if (recording) "Follow this track" else "Start navigation", maxLines = 1)
            }
        }
    }
}

/** Placeholder for previews/screenshot tests where MapLibre cannot render. */
@Composable
fun MapPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFFE8EEDC)), contentAlignment = Alignment.Center) {
        Text("map", color = Color(0x55000000), textAlign = TextAlign.Center)
    }
}
