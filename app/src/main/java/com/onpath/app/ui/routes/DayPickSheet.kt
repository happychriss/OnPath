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
import androidx.compose.ui.text.TextLayoutResult
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
import com.onpath.app.ui.DayTableView
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.OnPathShapes
import com.onpath.app.ui.OverviewSnapshot
import com.onpath.app.ui.TopBarRow
import com.onpath.strings.*

/**
 * My routes' day sheet of a saved multi-day route (owner 2026-11-27, "ja"; rows as the planner's day overview, owner
 * 2026-11-27 later): drawn in place over the list like the overview. The route's name with its ⋮ ([menu]: rename, copy,
 * export, share, delete – as on the card), the facts line, the whole line (display only), „Welchen Tag möchtest du fahren?“
 * and the planner's day table ([DayTableView], owner 2026-11-27): pill „Tag 3“, „Koblenz → Mainz“ when the plan names both
 * ends, aligned columns km · Σ km · ↑ m · time, the night sign (open night / Übernachtung / Hotel; the goal flag at the
 * finish), ›, and the total row. One tap on a row loads that day ([onRide]), ›
 * opens its own overview ([onDetail]). The next open day has the filled pill; under the day's row a day ridden to its goal says
 * „✓ gefahren 2.12.“, one whose recording stopped before the goal „angefangen 2.12.“ (MD-5 / MD-6 / MD-9, owner 2026-11-28) –
 * on a line of its own across the row that wraps between words, never cut with „…“ ([stateLaidOut]: the screenshot tests check it).
 * During a tour ([canRide] false) rows load nothing and a line says why.
 */
@Composable
fun DayPickSheet(ui: DayPickUi, canRide: Boolean, snapshot: OverviewSnapshot?, onRide: (Int) -> Unit, onDetail: (Int) -> Unit,
                 onClose: () -> Unit, stateLaidOut: (TextLayoutResult) -> Unit = {}, menu: @Composable () -> Unit) {
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
                // the planner's day table (owner 2026-11-27): the shared DayTable columns, the total row; one tap loads the day, › its overview
                val table = remember(ui.rows) { DayPick.table(ui.rows) }
                val byN = remember(ui.rows) { ui.rows.associateBy { it.n } }
                val nextDesc = str(Res.string.daypick_next)
                DayTableView(table, picked = { it == ui.next }, onTap = if (canRide) onRide else null, trailingWidth = 44.dp,
                    title = { l -> byN[l.n]?.let { r -> DayName(r, r.n == ui.next, nextDesc) } },
                    below = { l -> byN[l.n]?.let { r -> DayState(r, stateLaidOut) } },
                    trailing = { l -> IconButton(onClick = { onDetail(l.n) }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.ChevronRight, str(Res.string.daypick_details, l.n.toString()), tint = muted)
                    } })
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

/** „Koblenz → Mainz“ when the plan names both ends of the day (the shared DayRowText); null otherwise (the row says „Tag 3“ only). */
private fun places(r: DayPickRow): String? = DayRowText.places(r.from, r.to)

/** „2.12.“ / „2 Dec“ – the app language's short day and month. */
private fun riddenDate(ms: Long): String =
    java.text.SimpleDateFormat(Txt.s(Res.string.daypick_date), Format.locale()).format(java.util.Date(ms))

/** The day's name in the table: its pill, the next open day's filled. */
@Composable
private fun DayName(r: DayPickRow, next: Boolean, nextDesc: String) {
    Box(if (next) Modifier.semantics { contentDescription = nextDesc } else Modifier) { DayPill(r.n, next) }
}

/**
 * Under the day's row, the row's full width: „✓ gefahren 2.12.“ for a day done, „angefangen 2.12.“ for one begun (the name
 * column beside the numbers is too narrow for it – it was cut there); wraps between words, never cut.
 */
@Composable
private fun DayState(r: DayPickRow, stateLaidOut: (TextLayoutResult) -> Unit) {
    val state = r.riddenMs?.let { str(Res.string.daypick_ridden, riddenDate(it)) to OnPathColors.Green }
        ?: r.startedMs?.let { str(Res.string.daypick_started, riddenDate(it)) to OnPathColors.PlanAccent } ?: return
    Text(state.first, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = state.second,
        onTextLayout = stateLaidOut, modifier = Modifier.padding(top = 2.dp))
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

/** The stand-in route of day [r] for its own overview (the route's overview of one day): name, numbers, its ends as points. */
fun dayTour(ui: DayPickUi, r: DayPickRow, line: List<GeoPoint>): com.onpath.app.data.Tour {
    val name = places(r)?.let { DayRowText.day(r.n) + " · " + it } ?: DayRowText.day(r.n)
    val ends = listOfNotNull(line.firstOrNull(), line.lastOrNull()).map { com.onpath.app.gpx.GpxWaypoint(it.lat, it.lon, null) }
    return ui.tour.copy(id = -1L, uuid = "", name = name, distanceM = r.distanceM, ascentM = r.ascentM, descentM = 0.0, durationS = r.timeS,
        waypoints = ends, pinned = emptySet(), days = null, anchors = emptyMap(), favourite = false)
}
