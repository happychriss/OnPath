package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.onpath.app.data.Format
import com.onpath.app.routing.PlanPointRow
import com.onpath.app.routing.PlanPoints
import com.onpath.app.routing.PointRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Height of one point row: the drag maths (PlanPoints.dropIndex) counts in rows of this height. */
private val ROW_HEIGHT = 56.dp
/** Width of the ≡ handle at the start of a row: a drag starting here moves that row. */
private val HANDLE_WIDTH = 48.dp

/**
 * Points section of the full plan sheet (owner 2026-10-21): header with ⇅ Reverse and ↺ Round trip, then one row per
 * route point – ≡ (drag it to reorder; holding first works too), marker, name, distance from the previous point, ✕
 * (delete at once, Undo brings it back). Tapping a row shows the point on the map. No vibration in the list (owner).
 * After a ✕ the deleted row stays as a quiet placeholder for a moment and the list ignores taps ([PointsDeleteGuard]):
 * the rows never shift under the finger (field test 2026-10-22: a second tap deleted the next point).
 */
@Composable
fun PointsSection(
    rows: List<PlanPointRow>,
    canReverse: Boolean,
    canRoundTrip: Boolean,
    onReverse: () -> Unit,
    onRoundTrip: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onDelete: (row: Int) -> Unit,
    onShow: (row: Int) -> Unit,
    modifier: Modifier = Modifier,
    /** The scrolling container around the list (auto-scroll while a row is dragged near its edges). */
    scroll: ScrollState? = null,
    /** That container's visible part (for the auto-scroll edges). */
    viewport: () -> LayoutCoordinates? = { null },
) {
    RecomposeProbe.hit("points")
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(str(Res.string.plan_points), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onReverse, enabled = canReverse) {
                Icon(Icons.Outlined.SwapVert, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(str(Res.string.plan_reverse))
            }
            TextButton(onClick = onRoundTrip, enabled = canRoundTrip) {
                Icon(Icons.Outlined.Loop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(str(Res.string.plan_round_trip))
            }
        }
        if (rows.isEmpty()) {
            Text(str(Res.string.plan_no_points), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            return@Column
        }
        PointRows(rows, onMove, onDelete, onShow, scroll, viewport)
    }
}

@Composable
private fun PointRows(
    rows: List<PlanPointRow>,
    onMove: (from: Int, to: Int) -> Unit,
    onDelete: (row: Int) -> Unit,
    onShow: (row: Int) -> Unit,
    scroll: ScrollState?,
    viewport: () -> LayoutCoordinates?,
) {
    val density = LocalDensity.current
    val rowPx = with(density) { ROW_HEIGHT.toPx() }
    val handlePx = with(density) { HANDLE_WIDTH.toPx() }
    val edgePx = with(density) { 56.dp.toPx() }
    val stepPx = with(density) { 10.dp.toPx() }
    val scope = rememberCoroutineScope()
    val move by rememberUpdatedState(onMove)
    val scrollNow by rememberUpdatedState(scroll)
    val viewportNow by rememberUpdatedState(viewport)
    // the drop shows at once; the plan's own rows replace it as soon as they arrive
    var pending by remember(rows) { mutableStateOf<Pair<Int, Int>?>(null) }
    // safe ✕: the deleted row keeps its place for a moment, taps are ignored meanwhile (PointsDeleteGuard)
    val guard = remember { PointsDeleteGuard() }
    var guardNow by remember { mutableLongStateOf(0L) }
    val delete by rememberUpdatedState(onDelete)
    val show by rememberUpdatedState(onShow)
    val rowsNow by rememberUpdatedState(rows)
    val calmDelete: (Int) -> Unit = { i ->
        val t = System.currentTimeMillis()
        val r = rowsNow.getOrNull(i)
        if (r != null && guard.onDelete(i, r, rowsNow.size, t)) {
            guardNow = t
            delete(i)
            scope.launch { delay(PointsDeleteGuard.QUIET_MS + 50); guardNow = System.currentTimeMillis() }
        }
    }
    val calmShow: (Int) -> Unit = { i -> if (guard.allowsTap(System.currentTimeMillis())) show(i) }
    val entries = guard.entries(pending?.let { (f, t) -> PlanPoints.moved(rows, f, t) } ?: rows, guardNow)
    val count = entries.size
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // the dropped row glides from under the finger into its slot
    val settle = remember { Animatable(0f) }
    var settleRow by remember { mutableIntStateOf(-1) }
    var listCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val target = dragFrom?.let { PlanPoints.dropIndex(it, dragOffset, rowPx, count) }
    Column(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { listCoords = it }
            // One detector for the whole list – it does not move while a row does (owner field test 0.8.9: "moving
            // the points doesn't work"). A drag that starts on ≡ moves that row at once (holding first is fine too);
            // it consumes the movement before the list's scroll and the sheet see it, so neither moves meanwhile.
            .pointerInput(count, rowPx, handlePx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x > handlePx) return@awaitEachGesture
                    if (!guard.allowsTap(System.currentTimeMillis())) return@awaitEachGesture // just deleted: calm
                    val row = PlanPoints.rowAt(down.position.y, rowPx, count) ?: return@awaitEachGesture
                    val picked = awaitVerticalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                        ?: return@awaitEachGesture
                    val startY = down.position.y
                    var fingerY = picked.position.y
                    dragFrom = row; dragOffset = fingerY - startY
                    // near the top / bottom edge of the visible list: scroll it, the row stays under the finger
                    val auto = scope.launch {
                        while (isActive) {
                            withFrameNanos { }
                            val sc = scrollNow ?: break
                            val vp = viewportNow() ?: continue
                            val lc = listCoords ?: continue
                            if (!vp.isAttached || !lc.isAttached) continue
                            val inView = vp.localPositionOf(lc, Offset(0f, fingerY)).y
                            val step = PlanPoints.autoScroll(inView, vp.size.height.toFloat(), edgePx, stepPx)
                            if (step != 0f) {
                                val used = sc.scrollBy(step)
                                fingerY += used; dragOffset = fingerY - startY
                            }
                        }
                    }
                    val ended = try {
                        drag(picked.id) { c -> c.consume(); fingerY = c.position.y; dragOffset = fingerY - startY }
                    } finally { auto.cancel() }
                    val to = if (ended) PlanPoints.dropIndex(row, dragOffset, rowPx, count) else row
                    val residual = dragOffset - (to - row) * rowPx
                    if (to != row) pending = row to to
                    settleRow = to
                    dragFrom = null; dragOffset = 0f
                    scope.launch {
                        settle.snapTo(residual)
                        settle.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
                        settleRow = -1
                    }
                    if (to != row) move(row, to)
                }
            },
    ) {
        entries.forEachIndexed { i, e ->
            val r = e.row
            val dragged = dragFrom == i
            val lifted = dragged || settleRow == i
            val from = dragFrom
            val shiftRows = if (from != null && target != null && !dragged) PlanPoints.shift(i, from, target) else 0
            // the other rows make room smoothly; after the drop the list is in its new order at once (no glide back)
            val shiftPx by animateFloatAsState(shiftRows * rowPx, label = "row shift",
                animationSpec = if (from != null) spring(stiffness = Spring.StiffnessMediumLow) else snap())
            Surface(
                color = if (lifted) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                shadowElevation = if (lifted) 6.dp else 0.dp,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        translationY = when {
                            dragged -> dragOffset
                            settleRow == i -> settle.value
                            else -> shiftPx
                        }
                    },
            ) {
                val ri = e.rowIndex
                if (ri == null) DeletedRow(r) else PointRow(ri, r, rowsNow.size, calmDelete, calmShow)
            }
        }
    }
}

@Composable
private fun PointRow(i: Int, r: PlanPointRow, count: Int, onDelete: (row: Int) -> Unit, onShow: (row: Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        // ≡: drag it (the list's detector does the work)
        Box(
            Modifier.size(HANDLE_WIDTH, ROW_HEIGHT)
                .semantics { contentDescription = Txt.s(Res.string.plan_point_move_desc, i + 1) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .clickable(role = Role.Button, onClickLabel = str(Res.string.plan_show_on_map)) { onShow(i) }
                .semantics { contentDescription = Txt.s(Res.string.plan_point_show_desc, i + 1, r.name) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PointMarker(r, count)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                roleText(r)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1)
                }
            }
            legText(r)?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1)
            }
        }
        IconButton(onClick = { onDelete(i) }, modifier = Modifier.size(48.dp)
            .semantics { contentDescription = Txt.s(Res.string.plan_point_delete_desc, i + 1) }) {
            Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The row just deleted, for a moment in its old place (no buttons: a second tap there does nothing). */
@Composable
private fun DeletedRow(r: PlanPointRow) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).semantics { contentDescription = Txt.s(Res.string.plan_point_deleted_desc, r.name) },
        verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(HANDLE_WIDTH))
        Text(str(Res.string.plan_point_deleted), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** Green start (↺ on a round trip), numbered blue stops, red goal – the same colours and numbers as on the map. */
@Composable
private fun PointMarker(r: PlanPointRow, count: Int) {
    val color = when (r.role) {
        PointRole.START -> OnPathColors.Good
        PointRole.GOAL -> OnPathColors.Bad
        PointRole.STOP -> OnPathColors.Route
    }
    Box(Modifier.size(28.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(if (r.loop) "↺" else "${r.planIndex + 1}", color = Color.White, fontSize = if (r.loop) 15.sp else 13.sp,
            fontWeight = FontWeight.Bold)
    }
}

/** The role under the name; not repeated when an unnamed point is already called by it ("Start", "Destination"). */
private fun roleText(r: PlanPointRow): String? = when {
    r.loop -> Txt.s(Res.string.plan_point_start_finish)
    r.role == PointRole.START -> Txt.s(Res.string.plan_point_start)
    r.role == PointRole.GOAL -> Txt.s(Res.string.plan_point_destination)
    else -> null
}?.takeIf { it != r.name }

/** "+2.3 km" from the previous point, "~" when straight; a round trip's start shows the way back ("↺ 1.2 km"). */
private fun legText(r: PlanPointRow): String? {
    val m = r.legM ?: return null
    val d = Format.distance(m)
    return when {
        r.loop -> "↺ " + (if (r.approx) "~" else "") + d
        r.approx -> "~$d"
        else -> "+$d"
    }
}
