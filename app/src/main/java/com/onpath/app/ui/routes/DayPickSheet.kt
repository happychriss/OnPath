package com.onpath.app.ui.routes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.routing.Activity
import com.onpath.app.routing.DayRowText
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.DayEndSign
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.OnPathShapes
import com.onpath.app.ui.OverviewSnapshot
import com.onpath.app.ui.TopBarRow
import com.onpath.strings.*

/**
 * My routes' day sheet of a saved multi-day route (owner 2026-11-27, "ja"; rows as the planner's day overview, owner
 * 2026-11-27 later): drawn in place over the list like the overview. The route's name with its ⋮ ([menu]: rename, copy,
 * export, share, delete – as on the card), the facts line, the whole line (display only), „Welchen Tag möchtest du fahren?“
 * and one row per day: pill „Tag 3“, „Koblenz → Mainz“ when the plan names both ends, aligned columns km · ↑ m · time, the
 * night sign (open night / Übernachtung / Hotel; none at the finish) and ›. One tap on a row loads that day ([onRide]), ›
 * opens its own overview ([onDetail]). The next open day has the filled pill, a ridden day says „✓ Tag 2 · gefahren am 2. Dez.“.
 * During a tour ([canRide] false) rows load nothing and a line says why.
 */
@Composable
fun DayPickSheet(ui: DayPickUi, canRide: Boolean, snapshot: OverviewSnapshot?, onRide: (Int) -> Unit, onDetail: (Int) -> Unit,
                 onClose: () -> Unit, menu: @Composable () -> Unit) {
    val t = ui.tour
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.systemBarsPadding()) {
            TopBarRow(
                back = { IconButton(onClick = onClose, Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, str(Res.string.common_back)) } },
                title = { ellipsize -> Text(t.name, style = MaterialTheme.typography.titleLarge, maxLines = if (ellipsize) 1 else 2, overflow = TextOverflow.Ellipsis) },
                action = null, menu = menu,
            )
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(com.onpath.app.ui.instrument(factsLine(ui), MaterialTheme.typography.bodyMedium, muted), style = MaterialTheme.typography.bodyMedium, color = muted)
                val segs = remember(ui.line) { listOf(ui.line) }
                com.onpath.app.ui.OverviewMapPicture(t, segs, snapshot, emptyList(), null,
                    Modifier.padding(top = 10.dp).fillMaxWidth().height(180.dp).clip(OnPathShapes.Small))
                Text(str(Res.string.daypick_question), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                if (!canRide) Text(str(Res.string.daypick_touring), style = MaterialTheme.typography.bodySmall, color = OnPathColors.Warn,
                    modifier = Modifier.padding(bottom = 6.dp))
                val cols = columnWidths(ui.rows)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in ui.rows) DayPickRowView(r, r.n == ui.next, cols, canRide, { onRide(r.n) }, { onDetail(r.n) })
                }
            }
        }
    }
}

/** „Fahrrad · 8 Tage · 616 km · ↑ 4.210 m“. */
@Composable
private fun factsLine(ui: DayPickUi): String {
    val t = ui.tour
    val a = t.activity?.let { n -> Activity.entries.firstOrNull { it.name == n } }
    val days = t.days?.days ?: ui.rows.size
    return listOfNotNull(a?.let { com.onpath.app.data.LastActivity.chipText(it, t.sac) }, plural(Res.plurals.multiday_days_count, days, days),
        Format.distance(t.distanceM), "↑ " + Format.meters(t.ascentM)).joinToString(" · ")
}

/** A day's time as the owner's sketch writes it: „4 h 30“, under an hour „45 min“. */
internal fun dayTime(s: Double): String {
    val total = (s / 60).toLong()
    val h = total / 60; val m = total % 60
    return if (h > 0) "$h h ${m.toString().padStart(2, '0')}" else "$m min"
}

private fun cells(r: DayPickRow) = listOf(Format.distance(r.distanceM), "↑ " + Format.meters(r.ascentM), dayTime(r.timeS))

/** The widths of the three columns: the widest value of each over all rows (so they line up at any font size). */
@Composable
private fun columnWidths(rows: List<DayPickRow>): List<Dp> {
    val tm = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    return remember(rows, density, style) {
        (0..2).map { k -> with(density) { (rows.maxOfOrNull { tm.measure(cells(it)[k], style).size.width } ?: 0).toDp() + 2.dp } }
    }
}

@Composable
private fun DayPickRowView(r: DayPickRow, next: Boolean, cols: List<Dp>, canRide: Boolean, onRide: () -> Unit, onDetail: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val large = LocalDensity.current.fontScale >= LARGE_FONT
    val ridden = r.riddenMs != null
    val nextDesc = str(Res.string.daypick_next)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(OnPathShapes.Small)
        .background(if (next) OnPathColors.PlanAccent.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
        .then(if (canRide) Modifier.clickable(role = Role.Button, onClick = onRide) else Modifier)
        .semantics { selected = next; if (next) contentDescription = nextDesc }
        .padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (ridden) Text(str(Res.string.daypick_ridden, r.n.toString(), riddenDate(r.riddenMs!!)), style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold, color = OnPathColors.Green)
            else DayPill(r.n, next)
            places(r)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
            if (large) Columns(r, cols, muted, Modifier.padding(top = 2.dp))
        }
        if (!large) Columns(r, cols, muted, Modifier.padding(start = 8.dp))
        Box(Modifier.padding(start = 6.dp).size(22.dp), contentAlignment = Alignment.Center) { if (r.end != null) DayEndSign(r.end, 20.dp) }
        IconButton(onClick = onDetail, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Filled.ChevronRight, str(Res.string.daypick_details, r.n.toString()), tint = muted)
        }
    }
}

/** „Koblenz → Mainz“ when the plan names both ends of the day; null otherwise (the row says „Tag 3“ only). */
private fun places(r: DayPickRow): String? {
    val a = r.from?.trim().orEmpty(); val b = r.to?.trim().orEmpty()
    return if (a.isNotEmpty() && b.isNotEmpty()) Txt.s(Res.string.daypick_from_to, a, b) else null
}

/** „2. Dez.“ / „2 Dec“ – the app language's short day and month. */
private fun riddenDate(ms: Long): String =
    java.text.SimpleDateFormat(Txt.s(Res.string.daypick_date), Format.locale()).format(java.util.Date(ms))

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Columns(r: DayPickRow, cols: List<Dp>, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    // a value is never cut: at the largest font a column that does not fit goes to the next line
    androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cells(r).forEachIndexed { k, s ->
            Text(com.onpath.app.ui.instrument(s, MaterialTheme.typography.bodySmall, color), style = MaterialTheme.typography.bodySmall, color = color,
                maxLines = 1, softWrap = false, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = cols[k]))
        }
    }
}

/** „Tag 3“: the next open day filled plan blue, the others a plan-blue outline. */
@Composable
private fun DayPill(n: Int, filled: Boolean) {
    Surface(shape = OnPathShapes.Pill, color = if (filled) OnPathColors.PlanAccent else OnPathColors.CardWhite,
        border = if (filled) null else BorderStroke(1.dp, OnPathColors.PlanAccent)) {
        Text(DayRowText.day(n), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
            color = if (filled) OnPathColors.CardWhite else OnPathColors.PlanAccent, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

/** From this font scale the columns go under the day's name (no room beside it). */
private const val LARGE_FONT = 1.3f

/** The stand-in route of day [r] for its own overview (the route's overview of one day): name, numbers, its ends as points. */
fun dayTour(ui: DayPickUi, r: DayPickRow, line: List<GeoPoint>): com.onpath.app.data.Tour {
    val name = places(r)?.let { DayRowText.day(r.n) + " · " + it } ?: DayRowText.day(r.n)
    val ends = listOfNotNull(line.firstOrNull(), line.lastOrNull()).map { com.onpath.app.gpx.GpxWaypoint(it.lat, it.lon, null) }
    return ui.tour.copy(id = -1L, uuid = "", name = name, distanceM = r.distanceM, ascentM = r.ascentM, descentM = 0.0, durationS = r.timeS,
        waypoints = ends, pinned = emptySet(), days = null, anchors = emptyMap(), favourite = false)
}
