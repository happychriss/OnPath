package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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

/** Height of one point row: the drag maths (PlanPoints.dropIndex) counts in rows of this height. */
private val ROW_HEIGHT = 56.dp

/**
 * Points section of the full plan sheet (owner 2026-10-21): header with ⇅ Reverse and ↺ Round trip, then one row per
 * route point – ≡ (hold and drag to reorder), marker, name, distance from the previous point, ✕ (delete at once,
 * Undo brings it back). Tapping a row shows the point on the map.
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
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Points", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onReverse, enabled = canReverse) {
                Icon(Icons.Outlined.SwapVert, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Reverse")
            }
            TextButton(onClick = onRoundTrip, enabled = canRoundTrip) {
                Icon(Icons.Outlined.Loop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Round trip")
            }
        }
        if (rows.isEmpty()) {
            Text("No points yet – hold the map to add one.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            return@Column
        }
        val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
        var dragFrom by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        val move by rememberUpdatedState(onMove)
        val count = rows.size
        val target = dragFrom?.let { PlanPoints.dropIndex(it, dragOffset, rowPx, count) }
        rows.forEachIndexed { i, r ->
            val dragged = dragFrom == i
            val shiftRows = if (dragFrom != null && target != null && !dragged) PlanPoints.shift(i, dragFrom!!, target) else 0
            Surface(
                color = if (dragged) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                shadowElevation = if (dragged) 6.dp else 0.dp,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.zIndex(if (dragged) 1f else 0f)
                    .graphicsLayer { translationY = if (dragged) dragOffset else shiftRows * rowPx },
            ) {
                Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                    // ≡: hold, then drag (a plain swipe scrolls the sheet)
                    Box(
                        Modifier.size(44.dp, ROW_HEIGHT)
                            .semantics { contentDescription = "Move point ${i + 1} (hold and drag)" }
                            .pointerInput(i, count) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { /* no vibration in the Points list (owner 2026-10-21, PlanHaptics) */ dragFrom = i; dragOffset = 0f },
                                    onDrag = { change, d -> change.consume(); dragOffset += d.y },
                                    onDragEnd = {
                                        val from = dragFrom
                                        val to = from?.let { PlanPoints.dropIndex(it, dragOffset, rowPx, count) }
                                        dragFrom = null; dragOffset = 0f
                                        if (from != null && to != null && to != from) move(from, to)
                                    },
                                    onDragCancel = { dragFrom = null; dragOffset = 0f },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable(role = Role.Button, onClickLabel = "Show on the map") { onShow(i) }
                            .semantics { contentDescription = "Show point ${i + 1} on the map: ${r.name}" },
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
                        .semantics { contentDescription = "Delete point ${i + 1}" }) {
                        Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
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

private fun roleText(r: PlanPointRow): String? = when {
    r.loop -> "Start & goal"
    r.role == PointRole.START -> "Start"
    r.role == PointRole.GOAL -> "Goal"
    else -> null
}

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
