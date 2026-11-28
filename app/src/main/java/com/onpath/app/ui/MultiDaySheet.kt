package com.onpath.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.DayRowText
import com.onpath.app.routing.GeoPoint
import com.onpath.strings.*

// DayHeadUi: :state, ui/MultiDayUi.kt (phase 2 piece 20). The day-end signs are drawn in My routes' day table and on the map.

/** The day-end signs (round 3, variant A): grey dashed moon = night open, blue moon = Übernachtung, green bed = Hotel. */
object DayEndLook {
    val Blue = Color(0xFF2E6A9E)
    val Green = Color(0xFF245F46)
    val GreyFill = Color(0xFFEEF0EC)
    val GreyEdge = Color(0xFFA9AFA6)
    val GreyInk = Color(0xFF9AA096)

    fun fill(k: DayEndKind?): Color = when (k) { DayEndKind.PROPOSAL -> GreyFill; DayEndKind.OVERNIGHT -> Blue; DayEndKind.HOTEL -> Green; null -> OnPathColors.Route }
    fun line(k: DayEndKind): Color = when (k) { DayEndKind.PROPOSAL -> GreyEdge; DayEndKind.OVERNIGHT -> Blue; DayEndKind.HOTEL -> Green }

    fun label(k: DayEndKind?): String = Txt.s(when (k) {
        DayEndKind.PROPOSAL -> Res.string.multiday_end_open
        DayEndKind.OVERNIGHT -> Res.string.multiday_end_overnight
        DayEndKind.HOTEL -> Res.string.multiday_end_hotel
        null -> Res.string.multiday_end_finish
    })
}

/** A day-end sign [size] wide (a 22 unit box as in the sketch); [kind] null = the finish (route-blue square). */
@Composable
fun DayEndSign(kind: DayEndKind?, size: Dp = 20.dp) {
    Canvas(Modifier.size(size).semantics { contentDescription = DayEndLook.label(kind) }) { drawDayEnd(kind) }
}

/** Draws the sign of [kind] into the whole draw area (22 × 22 units). */
fun DrawScope.drawDayEnd(kind: DayEndKind?) {
    val u = size.minDimension / 22f
    fun o(x: Float, y: Float) = Offset(x * u, y * u)
    if (kind == null) { drawRect(OnPathColors.Route, o(6f, 6f), androidx.compose.ui.geometry.Size(10f * u, 10f * u)); return }
    val grey = kind == DayEndKind.PROPOSAL
    val fill = DayEndLook.fill(kind)
    val ink = if (grey) DayEndLook.GreyInk else Color.White
    drawCircle(fill, 10f * u, o(11f, 11f))
    drawCircle(if (grey) DayEndLook.GreyEdge else Color.White, 10f * u, o(11f, 11f),
        style = Stroke(1.5f * u, pathEffect = if (grey) PathEffect.dashPathEffect(floatArrayOf(2.5f * u, 2f * u)) else null))
    if (kind == DayEndKind.HOTEL) {
        val s = Stroke(1.6f * u, cap = StrokeCap.Round)
        drawLine(ink, o(5.5f, 14.5f), o(5.5f, 8.5f), s.width, StrokeCap.Round)
        drawLine(ink, o(5.5f, 12f), o(16.5f, 12f), s.width, StrokeCap.Round)
        drawLine(ink, o(16.5f, 12f), o(16.5f, 14.5f), s.width, StrokeCap.Round)
        drawLine(ink, o(10.5f, 12f), o(10.5f, 8.8f), s.width, StrokeCap.Round)
        drawLine(ink, o(10.5f, 8.8f), o(15.5f, 8.8f), s.width, StrokeCap.Round)
        drawLine(ink, o(15.5f, 8.8f), o(16.5f, 10.2f), s.width, StrokeCap.Round)
        drawCircle(ink, 1.2f * u, o(8f, 10f))
    } else {
        // the moon: a disc and, cut out of it, a smaller one in the sign's colour
        drawCircle(ink, 5.6f * u, o(10.4f, 11.2f))
        drawCircle(fill, 4.6f * u, o(13.0f, 9.2f))
    }
}

/**
 * A followed day (proposal 2026-11-28, as the planner's „Nachher · ein Tag“): on top the big tonal button „‹ Alle Tage“ (back to
 * the trip's day sheet in My routes, MD-2; only when no tour runs), under it „Tag 4 von 6“ with ‹ › – the trip's day before /
 * after, loaded as a tap in the day sheet does ([onStep] -1 / +1; off at the first / last day) – and, when its start or goal
 * moved, the line „Start und Ziel geändert – wird als eigene Tour gespeichert“ before saving (MD-4). Large font: wraps, never cut.
 */
@Composable
fun DayHeadLine(head: DayHeadUi, onAllDays: () -> Unit, onStep: (Int) -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (head.canLeave) androidx.compose.material3.Surface(onClick = onAllDays, shape = OnPathShapes.Small, color = OnPathColors.EditTonal,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                Text("‹ " + str(Res.string.multiday_all_days), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = OnPathColors.PlanAccent)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(head.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = { onStep(-1) }, enabled = head.canPrev) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    str(Res.string.multiday_prev_day), tint = if (head.canPrev) OnPathColors.PlanAccent else MaterialTheme.colorScheme.outline)
            }
            androidx.compose.material3.IconButton(onClick = { onStep(1) }, enabled = head.canNext) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    str(Res.string.daypick_next), tint = if (head.canNext) OnPathColors.PlanAccent else MaterialTheme.colorScheme.outline)
            }
        }
        if (head.leavesTrip) InfoLine(str(Res.string.multiday_own_tour))
    }
}
