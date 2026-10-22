package com.onpath.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.onpath.app.debug.FrameJankMeter
import com.onpath.app.debug.Problems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * State of a [StepSheet]. Everything that changes while the sheet moves (finger height, spring value) is read only in
 * the layout pass – moving the sheet never recomposes its content (owner field report 0.9.3: the plan sheet stuttered
 * on a 120 Hz Pixel 8).
 */
@Stable
class StepSheetState internal constructor(stepCount: Int, initialStep: Int) {
    /** The step the sheet is at or springing to (0 = smallest). */
    var step by mutableIntStateOf(initialStep)
        internal set
    internal val anim = Animatable(0f)
    internal var animReady = false
    internal var dragging by mutableStateOf(false)
    internal var dragHeight by mutableFloatStateOf(0f)
    internal var raw = 0f
    internal var velocity = 0f
    internal var settleTick by mutableIntStateOf(0)
    /** Where in the body (px from its top) each step ends; the last step always shows the whole body. */
    internal val stops = mutableStateListOf<Float>().apply { repeat(stepCount) { add(-1f) } }
    internal var bodyH by mutableFloatStateOf(0f)
    internal var barH by mutableFloatStateOf(0f)
    internal var bodyCoords: LayoutCoordinates? = null
    internal val markers = arrayOfNulls<Pair<LayoutCoordinates, () -> Float>>(stepCount)

    /** Sheet height of step [i] at rest: the body down to its stop + the bottom bar. */
    fun natural(i: Int): Float {
        val stop = if (i >= stops.lastIndex) bodyH else stops[i].takeIf { it >= 0f } ?: bodyH
        return minOf(stop, bodyH) + barH
    }

    fun heights(): List<Float> = List(stops.size) { natural(it) }

    /** Sheet height now (finger, spring or rest). */
    internal fun shown(): Float = when {
        dragging -> dragHeight
        animReady -> anim.value
        else -> natural(step)
    }

    /** The finger moves it or the spring runs. */
    val moving: Boolean get() = dragging || anim.isRunning

    internal fun updateStops() {
        val b = bodyCoords ?: return
        if (!b.isAttached) return
        markers.forEachIndexed { i, m ->
            val (c, extra) = m ?: return@forEachIndexed
            if (!c.isAttached) return@forEachIndexed
            val y = b.localPositionOf(c, Offset.Zero).y + extra()
            if (abs(stops[i] - y) > 0.5f) stops[i] = y
        }
    }
}

@Composable
fun rememberStepSheetState(stepCount: Int, initialStep: Int): StepSheetState =
    remember { StepSheetState(stepCount, initialStep) }

/** What the body of a [StepSheet] can use: mark where a step ends. */
interface StepSheetScope {
    /** The sheet at step [step] shows the body down to here; [extra] = scroll offset when the mark is in a scrolled list. */
    fun Modifier.stepEnd(step: Int, extra: () -> Float = { 0f }): Modifier
}

/**
 * A bottom sheet with steps (owner 2026-10-22: small → profile → points), Apple-like: the body is composed and laid out
 * ONCE at its full height; a step is only how much of it shows. While the finger drags, the sheet follows it 1:1 (rubber
 * band beyond the ends); on release it goes to the nearest step, or the next one in a fling's direction
 * (SheetDrag.target), with a spring from where the finger left it. Moving it only re-places layers: the body (own
 * layer, never re-recorded), the [bottomBar] (own layer, rides on the sheet's bottom edge, covers the body below the
 * step) and the rounded background. A scrolling list inside first scrolls; at its top, pulling down moves the sheet.
 * Frame times of every move go to the events trace ("Sheet drag: …", FrameJankMeter).
 */
@Composable
fun StepSheet(
    step: Int,
    onStep: (Int) -> Unit,
    onHandleTap: () -> Unit,
    state: StepSheetState,
    modifier: Modifier = Modifier,
    onDragging: (Boolean) -> Unit = {},
    bottomBar: @Composable ColumnScope.() -> Unit,
    body: @Composable StepSheetScope.() -> Unit,
) {
    val density = LocalDensity.current
    val flingPxS = with(density) { 700.dp.toPx() }
    val scope = rememberCoroutineScope()
    val onStepNow by rememberUpdatedState(onStep)
    val stepNow by rememberUpdatedState(step)
    // the caller's step (handle tap, VM) wins; the spring follows below
    LaunchedEffect(step) { if (state.step != step && !state.dragging) state.step = step }
    // the height of the current step changed (step switched, content grew, release): spring there from what shows now
    LaunchedEffect(state) {
        snapshotFlow { state.natural(state.step) to state.settleTick }.collectLatest { (n, _) ->
            if (state.dragging || state.bodyH <= 0f) return@collectLatest
            if (!state.animReady) { state.anim.snapTo(n); state.animReady = true; return@collectLatest }
            val v = state.velocity
            state.velocity = 0f
            state.anim.animateTo(n, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow), initialVelocity = v)
        }
    }
    SheetFrameMeter(state)
    // while the sheet is dragged or still springs after the finger let go, finished map regions wait to join the map
    // and a style reload waits (same rule as a map drag, owner 2026-10-22; phone trace 0.10.0: the settle frames janked too)
    val onDraggingNow by rememberUpdatedState(onDragging)
    LaunchedEffect(state) { snapshotFlow { state.moving }.distinctUntilChanged().drop(1).collect { onDraggingNow(it) } }
    DisposableEffect(state) { onDispose { if (state.moving) onDraggingNow(false) } }

    fun start() {
        if (state.dragging) return
        state.raw = state.shown()
        state.dragHeight = state.raw
        state.dragging = true
        scope.launch { state.anim.stop() }
    }
    fun drag(dy: Float) {  // dy > 0 = finger down = smaller
        if (!state.dragging) start()
        state.raw -= dy
        val hs = state.heights()
        state.dragHeight = SheetDrag.follow(state.raw, hs.first(), hs.last())
    }
    suspend fun release(velocityPxS: Float) {
        if (!state.dragging) return
        val shown = state.dragHeight
        val target = SheetDrag.target(shown, state.heights(), velocityPxS, flingPxS)
        state.anim.snapTo(shown)
        state.animReady = true
        state.velocity = -velocityPxS
        state.step = target
        state.dragging = false
        state.settleTick++
        if (target != stepNow) onStepNow(target)
    }
    val nested = remember(state) {
        object : NestedScrollConnection {
            // the sheet was pulled down by the list's over-scroll: pushing up gives it back before the list scrolls
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!state.dragging || source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                val room = state.natural(state.step) - state.dragHeight
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
    val sheetScope = remember(state) {
        object : StepSheetScope {
            override fun Modifier.stepEnd(step: Int, extra: () -> Float): Modifier = onPlaced { c ->
                state.markers[step] = c to extra
                state.updateStops()
            }
        }
    }
    val surface = MaterialTheme.colorScheme.surface
    Layout(
        contents = listOf(
            {
                Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = surface,
                    shadowElevation = 8.dp) {}
            },
            {
                Column(Modifier.fillMaxWidth().onPlaced { state.bodyCoords = it; state.updateStops() }
                    .padding(horizontal = 16.dp)) {
                    // Material drag handle (32 × 4 dp) in a full-width 48 dp tall touch area
                    Box(Modifier.fillMaxWidth().height(48.dp)
                        .clickable(onClickLabel = "Resize panel", onClick = onHandleTap)
                        .semantics { contentDescription = "Resize panel" },
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.width(32.dp).height(4.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                    }
                    sheetScope.body()
                }
            },
            {
                // opaque, and it takes the touches over the body behind it (rows hidden below the step)
                Column(Modifier.fillMaxWidth().background(surface)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                    .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = bottomBar)
            },
        ),
        modifier = modifier.fillMaxWidth()
            .nestedScroll(nested)
            .draggable(
                state = rememberDraggableState { drag(it) },
                orientation = Orientation.Vertical,
                onDragStarted = { start() },
                onDragStopped = { v -> release(v) },
            ),
    ) { (bg, body, bar), c ->
        val w = c.maxWidth
        // body and bar keep their constraints → measured once, not per frame
        val bodyP = body.first().measure(Constraints(minWidth = w, maxWidth = w, maxHeight = c.maxHeight))
        val barP = bar.first().measure(Constraints(minWidth = w, maxWidth = w, maxHeight = c.maxHeight))
        if (state.bodyH != bodyP.height.toFloat()) state.bodyH = bodyP.height.toFloat()
        if (state.barH != barP.height.toFloat()) state.barH = barP.height.toFloat()
        val h = state.shown().roundToInt().coerceIn(barP.height, c.maxHeight)
        val bgP = bg.first().measure(Constraints.fixed(w, h))
        layout(w, h) {
            bgP.place(0, 0)
            bodyP.placeWithLayer(0, 0)
            barP.placeWithLayer(0, h - barP.height)
        }
    }
}

/** Frame times while the sheet moves → events trace (owner's next swipe gives numbers). Not in previews / tests. */
@Composable
private fun SheetFrameMeter(state: StepSheetState) {
    val view = LocalView.current
    LaunchedEffect(state) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val meter = FrameJankMeter(window)
        var dragged = false
        try {
            snapshotFlow { state.moving to state.dragging }.collectLatest { (moving, dragging) ->
                if (dragging) dragged = true
                if (moving) { meter.start(); return@collectLatest }
                delay(150)  // drag end → spring start is not the end of the move
                meter.stop()?.let { Problems.trace("sheet", it.line(if (dragged) "Sheet drag" else "Sheet move")) }
                dragged = false
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
