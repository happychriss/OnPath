package com.onpath.app.ui

import com.onpath.strings.*
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
import androidx.compose.ui.graphics.Color
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
 * the layout pass – moving the sheet never recomposes its content (else it stutters on a 120 Hz phone).
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
    /** The opaque strip under the bar (navigation bar + a little air): there in every step. */
    internal var footH by mutableFloatStateOf(0f)
    /**
     * Steps below this show no bottom bar: the bar slides in from under the sheet's bottom edge while the sheet grows
     * from step barFrom - 1 to barFrom (the plan sheet's rolled-up row has its own small Start button).
     */
    internal var barFrom by mutableIntStateOf(0)
    internal var bodyCoords: LayoutCoordinates? = null
    internal val markers = arrayOfNulls<Pair<LayoutCoordinates, () -> Float>>(stepCount)

    /** Sheet height of step [i] at rest: the body down to its stop + the bottom bar (from step [barFrom]) + the foot. */
    fun natural(i: Int): Float {
        val stop = if (i >= stops.lastIndex) bodyH else stops[i].takeIf { it >= 0f } ?: bodyH
        return minOf(stop, bodyH) + footH + if (i >= barFrom) barH else 0f
    }

    /**
     * How much of the bottom bar shows now, 0..1: 0 at step barFrom - 1 and below, 1 from step barFrom on, in between
     * with the sheet's height (finger or spring). Read it only in layout / draw (graphicsLayer), never in composition.
     */
    fun barShown(): Float {
        if (barFrom <= 0) return 1f
        val lo = natural(barFrom - 1)
        val hi = natural(barFrom)
        if (hi <= lo + 0.5f) return if (shown() >= hi) 1f else 0f
        return ((shown() - lo) / (hi - lo)).coerceIn(0f, 1f)
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
 * A bottom sheet with steps (small → profile → points), Apple-like: the body is composed and laid out
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
    /** Steps below this have no [bottomBar] (see StepSheetState.barFrom); 0 = the bar in every step. */
    barFrom: Int = 0,
    /** The sheet's colour (Plan: [OnPathColors.PlanTint]); null = the theme's surface. */
    surfaceColor: Color? = null,
    /** Handle + a top edge in this colour (Plan: [OnPathColors.PlanAccent]); null = the grey handle, no edge. */
    accent: Color? = null,
    /**
     * Content of the sheet's foot (above the navigation bar), shown in EVERY step – also rolled up, the sheet sits on it
     * (owner 2026-11-06, C: during a tour the green way back to Navigate, where "Los geht's" is without one); null = none.
     */
    foot: (@Composable ColumnScope.() -> Unit)? = null,
    bottomBar: @Composable ColumnScope.() -> Unit,
    body: @Composable StepSheetScope.() -> Unit,
) {
    val density = LocalDensity.current
    val flingPxS = with(density) { 700.dp.toPx() }
    val scope = rememberCoroutineScope()
    val onStepNow by rememberUpdatedState(onStep)
    androidx.compose.runtime.SideEffect { if (state.barFrom != barFrom) state.barFrom = barFrom }
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
    // and a style reload waits (same rule as a map drag; the settle frames jank too otherwise)
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
        val heights = state.heights()
        val target = SheetDrag.target(shown, heights, velocityPxS, flingPxS)
        Problems.trace("sheet", SheetDrag.describe(shown, heights, velocityPxS, target))
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
    val surface = surfaceColor ?: MaterialTheme.colorScheme.surface
    Layout(
        contents = listOf(
            {
                Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = surface,
                    shadowElevation = 8.dp) {
                    // Plan's blue top edge (owner 2026-11-03): a stripe along the top, clipped by the rounded shape
                    if (accent != null) Box(Modifier.fillMaxWidth().height(SHEET_EDGE)) {
                        Box(Modifier.fillMaxWidth().height(SHEET_EDGE).background(accent))
                    }
                }
            },
            {
                Column(Modifier.fillMaxWidth().onPlaced { state.bodyCoords = it; state.updateStops() }
                    .padding(horizontal = 16.dp)) {
                    // Material drag handle (32 × 4 dp) in a full-width 24 dp tall strip (owner 2026-10-31: less air; the
                    // whole sheet can be dragged, and the rolled-up row is one big tap target)
                    Box(Modifier.fillMaxWidth().height(HANDLE_HEIGHT)
                        .clickable(onClickLabel = str(Res.string.plan_resize_panel), onClick = onHandleTap)
                        .semantics { contentDescription = Txt.s(Res.string.plan_resize_panel) },
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.width(32.dp).height(4.dp).clip(CircleShape)
                            .background(accent ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                    }
                    sheetScope.body()
                }
            },
            {
                // opaque, and it takes the touches over the body behind it (rows hidden below the step)
                Column(Modifier.fillMaxWidth().background(surface)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                    .padding(horizontal = 16.dp), content = bottomBar)
            },
            {
                // the foot: [foot] + navigation bar + a little air, opaque in every step (the bar slides out behind it)
                Column(Modifier.fillMaxWidth().background(surface)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                    .navigationBarsPadding().padding(bottom = FOOT_AIR)) {
                    if (foot != null) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), content = foot)
                }
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
    ) { (bg, body, bar, foot), c ->
        val w = c.maxWidth
        // body and bar keep their constraints → measured once, not per frame
        val bodyP = body.first().measure(Constraints(minWidth = w, maxWidth = w, maxHeight = c.maxHeight))
        val barP = bar.first().measure(Constraints(minWidth = w, maxWidth = w, maxHeight = c.maxHeight))
        val footP = foot.first().measure(Constraints(minWidth = w, maxWidth = w, maxHeight = c.maxHeight))
        if (state.bodyH != bodyP.height.toFloat()) state.bodyH = bodyP.height.toFloat()
        if (state.barH != barP.height.toFloat()) state.barH = barP.height.toFloat()
        if (state.footH != footP.height.toFloat()) state.footH = footP.height.toFloat()
        val h = state.shown().roundToInt().coerceIn(footP.height, c.maxHeight)
        val bgP = bg.first().measure(Constraints.fixed(w, h))
        // below step barFrom the bar slides down behind the foot (what is left of it lies below the screen's edge)
        val shownBar = (barP.height * state.barShown()).roundToInt()
        layout(w, h) {
            bgP.place(0, 0)
            bodyP.placeWithLayer(0, 0)
            if (shownBar > 0) barP.placeWithLayer(0, h - footP.height - shownBar)
            footP.placeWithLayer(0, h - footP.height)
        }
    }
}

/** Plan's blue edge along the sheet's top (owner 2026-11-03). */
private val SHEET_EDGE = 3.dp

/** Height of the grab-handle strip at the sheet's top. */
internal val HANDLE_HEIGHT = 24.dp
/** Air under the bottom bar (above the navigation bar). */
private val FOOT_AIR = 10.dp

/** Frame times while the sheet moves → events trace. Not in previews / tests. */
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
