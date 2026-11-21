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

// DayRowUi, DaysUi, DayHeadUi: :state, ui/MultiDayUi.kt (phase 2 piece 20)

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

/** The overview's title line: "München – Venedig · 6 Tage" (the summary step). */
@Composable
fun DaysTitle(days: DaysUi) {
    val n = plural(Res.plurals.multiday_days_count, days.count, days.count)
    Text(listOfNotNull(days.name, n).joinToString(" · "), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

/**
 * The overview below the summary (sketch round 2, step 11): the height profile with the day borders (grey dashed, blue,
 * green; the picked days shaded) and one row per day – tap = this day, hold = the days from the picked one to this one
 * (several in a row, scenario O).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DaysOverview(days: DaysUi, onTap: (Int) -> Unit, onHold: (Int) -> Unit, onHighlight: (GeoPoint?) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            ElevationProfile(days.profile, height = 96.dp, onHighlight = onHighlight, onRelease = { onHighlight(null) })
            DayBorders(days)
        }
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (r in days.rows) DayRow(r, days.selected?.let { r.n in it } == true, { onTap(r.n) }, { onHold(r.n) })
        }
    }
}

/** The day borders over the profile – the same plot area as [ElevationProfile] (40 dp axis left, 6 dp top, 16 dp bottom). */
@Composable
private fun DayBorders(days: DaysUi) {
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        val left = 40.dp.toPx(); val top = 6.dp.toPx(); val bottom = size.height - 16.dp.toPx()
        if (size.width - left < 24.dp.toPx() || bottom <= top) return@Canvas
        fun x(f: Float) = left + f.coerceIn(0f, 1f) * (size.width - left)
        val cuts = listOf(0f) + days.borders.map { it.first } + 1f
        days.selected?.let { s ->
            val a = cuts.getOrNull(s.first - 1) ?: return@let
            val b = cuts.getOrNull(s.last) ?: return@let
            drawRect(OnPathColors.Route.copy(alpha = 0.10f), Offset(x(a), top), androidx.compose.ui.geometry.Size(x(b) - x(a), bottom - top))
        }
        for ((f, k) in days.borders) {
            val grey = k == DayEndKind.PROPOSAL
            drawLine(DayEndLook.line(k), Offset(x(f), top), Offset(x(f), bottom), (if (grey) 1f else 1.8f) * density,
                pathEffect = if (grey) PathEffect.dashPathEffect(floatArrayOf(3f * density, 2f * density)) else null)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayRow(r: DayRowUi, picked: Boolean, onTap: () -> Unit, onHold: () -> Unit) {
    val values = "${Format.distance(r.distanceM)} · ↑ ${Format.meters(r.ascentM)}"
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
        .background(if (picked) OnPathColors.Route.copy(alpha = 0.10f) else Color.Transparent)
        .combinedClickable(role = Role.Button, onClick = onTap, onLongClick = onHold)
        .semantics { selected = picked }
        .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DayRowText.day(r.n), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                Spacer(Modifier.weight(1f))
                // R8: a day above a limit per day – its values orange
                Text(instrument(values, MaterialTheme.typography.bodySmall), style = MaterialTheme.typography.bodySmall,
                    color = if (r.over) OnPathColors.Warn else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(start = 8.dp))
            }
            // the grey line: only when the place is known at both ends of the day
            r.places?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        DayEndSign(r.end, 20.dp)
    }
}

/** A followed day: "Tag 4 von 6" · "‹ Alle Tage", and the day's missing maps with "Laden". */
@Composable
fun DayHeadLine(head: DayHeadUi, onAllDays: () -> Unit, onLoadMaps: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(head.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (head.canLeave) androidx.compose.material3.TextButton(colors = ActionButtons.editText(), onClick = onAllDays) {
                Text("‹ " + str(Res.string.multiday_all_days))
            }
        }
        head.mapsBytes?.let { b ->
            InfoLine(str(Res.string.multiday_day_maps_missing, tourMb(b)) + " · " + str(Res.string.multiday_load), onTap = onLoadMaps)
        }
    }
}

/** "340 MB" – the sizes of the map question (rounded to 10 MB from 100 MB on). */
fun tourMb(bytes: Long): String {
    val m = (bytes / 1_000_000.0)
    val r = if (m >= 100) (Math.round(m / 10) * 10) else Math.round(m).coerceAtLeast(1)
    return if (r >= 1000) "${Format.number(r / 1000.0, 1)} GB" else "$r MB"
}

/** The foot's one button in the overview: "Tag 4 fahren" / "Tage 3–4 fahren", "Tag wählen" while none is picked. */
@Composable
fun RideDaysButton(selected: IntRange?, onRide: () -> Unit) {
    val label = when {
        selected == null -> str(Res.string.multiday_pick)
        selected.first == selected.last -> str(Res.string.multiday_ride_day, selected.first.toString())
        else -> str(Res.string.multiday_ride_days, selected.first.toString(), selected.last.toString())
    }
    Button(colors = ActionButtons.planGo(), onClick = onRide, enabled = selected != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = PlanFoot.HEIGHT)) {
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
    }
}

/**
 * §7: the question at the first opening of a multi-day tour – Später · Nur Tag n · **Ganze Tour** (FRAGE, three buttons;
 * on mobile data it says that the maps wait for Wi-Fi).
 */
@Composable
internal fun tourMapsMessage(ask: com.onpath.app.ui.plan.MultiDaySlice.TourMapsAsk, onAnswer: (com.onpath.app.ui.plan.MultiDaySlice.TourMapsAnswer) -> Unit): Message =
    Message(MessageKind.FRAGE, str(Res.string.multiday_maps_title),
        str(Res.string.multiday_maps_text) + if (!ask.onWifi) " " + str(Res.string.multiday_maps_wifi) else "",
        buttons = listOfNotNull(
            MessageButton(str(Res.string.multiday_maps_later)) { onAnswer(com.onpath.app.ui.plan.MultiDaySlice.TourMapsAnswer.LATER) },
            if (ask.dayCells.isNotEmpty()) MessageButton(str(Res.string.multiday_maps_day, ask.day.toString(), tourMb(ask.dayBytes))) {
                onAnswer(com.onpath.app.ui.plan.MultiDaySlice.TourMapsAnswer.DAY)
            } else null,
            MessageButton(str(Res.string.multiday_maps_whole, tourMb(ask.wholeBytes))) { onAnswer(com.onpath.app.ui.plan.MultiDaySlice.TourMapsAnswer.WHOLE) },
        ))
