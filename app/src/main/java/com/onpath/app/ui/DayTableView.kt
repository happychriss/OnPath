package com.onpath.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.onpath.app.routing.DayTable
import com.onpath.strings.*

/**
 * How the day table lays out its numbers on a phone (owner 2026-11-27, night answers 27./28.11.): five columns km · Σ km · ↑ m ·
 * time · sign do not fit beside „Tag N“ at 360 dp and the default font, so by default the running total („gesamt“) sits grey
 * under the day's km (two lines in the km column, [oneRow] false). Developer tools → „Day table: all columns in one row“
 * switches to the planner's one-row layout (kept in Prefs.dayTableOneRow, read once on first use; never in screenshots).
 */
object DayTableLayout {
    private var saved by mutableStateOf<Boolean?>(null)

    /** The layout now: the developer switch as kept, false in previews / screenshots or when it cannot be read. */
    @Composable
    fun oneRow(): Boolean {
        if (androidx.compose.ui.platform.LocalInspectionMode.current) return false
        val ctx = androidx.compose.ui.platform.LocalContext.current
        return saved ?: try { com.onpath.app.data.Prefs(ctx).dayTableOneRow } catch (e: Exception) {
            com.onpath.app.debug.Problems.warn("ui", "Day table layout switch not read – Σ km under km", e); false
        }.also { saved = it }
    }

    /** Developer tools: switch and keep it. */
    fun set(ctx: android.content.Context, on: Boolean) {
        saved = on
        try { com.onpath.app.data.Prefs(ctx).dayTableOneRow = on }
        catch (e: Exception) { com.onpath.app.debug.Problems.warn("ui", "Day table layout switch not kept", e) }
    }
}

/**
 * The multi-day day table (owner 2026-11-27, `docs/design/img/profilauswahl/mehrtage-tabelle.png`; the same core
 * [DayTable] / [com.onpath.app.routing.DayRowText] as the planner's WebDays table): per day „Tag N“ ([title]) with the grey
 * „von → nach“ under it, then aligned right-bound columns km · Σ km · ↑ m · time with their units, the night's moon / bed or
 * the goal flag, and [trailing] (My routes' ›, [trailingWidth] wide – the total row keeps that room). A day above a limit per
 * day: the row in warn yellow, only the exceeded values bold. The total row („Gesamt“) at the bottom. [onTap] a day, [onHold]
 * (plan sheet: the days up to this one). From [LARGE_FONT] on the numbers go under the day's name (no room beside it) – a
 * value is never cut. [below]: a line under the whole row, the row's full width (My routes: „✓ gefahren 2.12.“).
 */
@Composable
fun DayTableView(
    table: DayTableUi,
    modifier: Modifier = Modifier,
    oneRow: Boolean = DayTableLayout.oneRow(),
    picked: (Int) -> Boolean = { false },
    onTap: ((Int) -> Unit)? = null,
    onHold: ((Int) -> Unit)? = null,
    trailingWidth: Dp = 0.dp,
    title: @Composable (DayTableLine) -> Unit = { DayTableTitle(it) },
    trailing: (@Composable (DayTableLine) -> Unit)? = null,
    below: (@Composable (DayTableLine) -> Unit)? = null,
    /** Each row's „von → nach“ as laid out (the screenshot tests check it is never cut). */
    placesLaidOut: (TextLayoutResult) -> Unit = {},
    /**
     * The running total „Σ … km“ under each day's km. False: My routes' day sheet on the phone (owner 2026-11-28 09:52: „auf dem
     * Handy brauche ich nicht die Summe der gesamten Kilometer … das ist was für die Planung“) – its room goes to „von → nach“.
     */
    showSum: Boolean = true,
    /**
     * Each day a card of its own (proposal 2026-11-28, owner 09:58 „mit einzelnen Blasen, so wie auf dem My Tours Menü“): the
     * same card as My routes' tour cards (OnPathShapes.Card, CardPaper, the low warm shadow, 10 dp apart); the columns stay
     * aligned across all cards (one set of widths, the same inner padding).
     */
    cards: Boolean = false,
) {
    val large = LocalDensity.current.fontScale >= LARGE_FONT
    @Suppress("NAME_SHADOWING")
    val table = if (showSum) table else remember(table) { table.copy(lines = table.lines.map { it.copy(cells = it.cells.copy(sum = null)) }) }
    val w = columnWidths(table)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (cards) 10.dp else 2.dp)) {
        for (l in table.lines) DayTableRow(l, w, oneRow, large, picked(l.n), onTap, onHold, title, trailing, below, placesLaidOut, cards)
        if (!cards) HorizontalDivider(Modifier.padding(top = 4.dp), color = OnPathColors.Line)
        TotalRow(table, w, oneRow, large, trailingWidth, card = cards)
    }
}

/** „Tag 3“ in bold – the plan sheet's day name (My routes' sheet passes its pill). */
@Composable
fun DayTableTitle(l: DayTableLine) {
    Text(l.day, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
        overflow = TextOverflow.Ellipsis)
}

/** The widths of km · Σ · ↑ · time: the widest value of each over the days and the total (aligned at any font size). */
private class Widths(val km: Dp, val sum: Dp, val up: Dp, val time: Dp)

private val numStyle: TextStyle @Composable get() = MaterialTheme.typography.bodySmall

/** A cell's text: tabular digits, units one step smaller; [strong] = km / an exceeded value (bold), else normal. */
private fun cell(s: String, style: TextStyle, strong: Boolean): AnnotatedString =
    Instrument.styled(s, style, numberWeight = if (strong) FontWeight.Bold else FontWeight.Normal)

@Composable
private fun columnWidths(t: DayTableUi): Widths {
    val tm = rememberTextMeasurer()
    val style = numStyle
    val density = LocalDensity.current
    return remember(t, density, style) {
        val all = t.lines.map { it.cells } + t.total
        fun widest(f: (DayTable.Cells) -> String?) = with(density) {
            (all.mapNotNull(f).maxOfOrNull { tm.measure(cell(it, style, true), style).size.width } ?: 0).toDp() + 1.dp
        }
        Widths(widest { it.km }, widest { it.sum }, widest { it.up }, widest { it.time })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayTableRow(l: DayTableLine, w: Widths, oneRow: Boolean, large: Boolean, picked: Boolean,
                        onTap: ((Int) -> Unit)?, onHold: ((Int) -> Unit)?,
                        title: @Composable (DayTableLine) -> Unit, trailing: (@Composable (DayTableLine) -> Unit)?,
                        below: (@Composable (DayTableLine) -> Unit)?, placesLaidOut: (TextLayoutResult) -> Unit, card: Boolean = false) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bg = when {
        l.over -> OnPathColors.WarnContainer
        picked -> OnPathColors.Route.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val desc = listOfNotNull(l.day, l.places).joinToString(" · ")
    // a card: My routes' tour card (shape, paper, low shadow); the picked / over-limit tint over its paper
    val frame = if (card) Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Card).clip(OnPathShapes.Card).background(OnPathColors.CardPaper)
        else Modifier.clip(OnPathShapes.Small)
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(frame).background(bg)
        .then(if (onTap != null) Modifier.combinedClickable(role = Role.Button, onClick = { onTap(l.n) },
            onLongClick = onHold?.let { h -> { h(l.n) } }) else Modifier)
        .semantics { selected = picked; contentDescription = desc }
        .padding(start = if (card) 10.dp else 8.dp, top = if (card) 10.dp else 6.dp, bottom = if (card) 10.dp else 6.dp,
            end = if (trailing != null) 0.dp else if (card) 10.dp else 8.dp),
        verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                title(l)
                l.places?.let {
                    // wraps as far as it needs, never cut („Augsburg → Münch…“ before, 2026-11-28); the developer's one-row
                    // layout leaves the name column almost no width – two lines there, as before
                    Text(it, style = MaterialTheme.typography.bodySmall, color = muted, onTextLayout = placesLaidOut,
                        maxLines = if (oneRow && !large) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp))
                }
                if (large) Numbers(l.cells, w, oneRow = true, large = true, muted, Modifier.padding(top = 2.dp))
            }
            if (!large) Numbers(l.cells, w, oneRow, large = false, muted, Modifier.padding(start = 6.dp))
            Box(Modifier.padding(start = 8.dp).size(22.dp), contentAlignment = Alignment.Center) {
                if (l.end != null) DayEndSign(l.end, 20.dp) else GoalFlagSign(20.dp)
            }
            trailing?.invoke(l)
        }
        below?.invoke(l)
    }
}

/** The numbers of a row: km (bold) · Σ (grey; under km unless [oneRow]) · ↑ m · time, the exceeded ones bold in warn ink. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Numbers(c: DayTable.Cells, w: Widths, oneRow: Boolean, large: Boolean, muted: Color, modifier: Modifier) {
    val style = numStyle
    val ink = MaterialTheme.colorScheme.onSurface
    @Composable fun Cell(s: String, width: Dp, color: Color, strong: Boolean) =
        Text(cell(s, style, strong), style = style, color = color, maxLines = 1, softWrap = false, textAlign = TextAlign.End,
            modifier = if (large) Modifier else Modifier.width(width))
    val up = @Composable { Cell(c.up, w.up, if (c.overAscent) OnPathColors.Warn else ink, c.overAscent) }
    val time = @Composable { Cell(c.time, w.time, if (c.overTime) OnPathColors.Warn else ink, c.overTime) }
    if (large) {
        // the largest font: the values wrap to a next line rather than being cut
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Cell(c.km, w.km, ink, true); c.sum?.let { Cell(it, w.sum, muted, false) }; up(); time()
        }
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (oneRow) {
            Cell(c.km, w.km, ink, true)
            Box(Modifier.width(w.sum)) { c.sum?.let { Cell(it, w.sum, muted, false) } }
        } else Column(Modifier.widthIn(min = maxOf(w.km, w.sum)), horizontalAlignment = Alignment.End) {
            Cell(c.km, maxOf(w.km, w.sum), ink, true)
            c.sum?.let { Cell(it, maxOf(w.km, w.sum), muted, false) }
        }
        up(); time()
    }
}

/** „Gesamt“ · km · ↑ m · time under the days, in the same columns (the sign's and the trailing room kept free). */
@Composable
private fun TotalRow(t: DayTableUi, w: Widths, oneRow: Boolean, large: Boolean, trailingWidth: Dp, card: Boolean = false) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val side = if (card) 10.dp else 8.dp // in the cards' columns
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = side, end = if (trailingWidth > 0.dp) 0.dp else side),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.totalLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            if (large) Numbers(t.total, w, oneRow = true, large = true, muted, Modifier.padding(top = 2.dp))
        }
        if (!large) Numbers(t.total, w, oneRow, large = false, muted, Modifier.padding(start = 6.dp))
        Spacer(Modifier.width(8.dp + 22.dp + trailingWidth))
    }
}

/** The goal: a red disc with a white flag – the planner's goal sign in the table (the map's goal point colour). */
@Composable
fun GoalFlagSign(size: Dp = 20.dp) {
    val label = DayEndLook.label(null)
    Canvas(Modifier.size(size).semantics { contentDescription = label }) {
        val u = this.size.minDimension / 22f
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        drawCircle(OnPathColors.PointGoal, 10f * u, o(11f, 11f))
        drawLine(Color.White, o(8f, 6f), o(8f, 16.5f), 1.6f * u, StrokeCap.Round)
        drawPath(Path().apply { moveTo(8.6f * u, 6f * u); lineTo(15.5f * u, 8.2f * u); lineTo(8.6f * u, 10.6f * u); close() }, Color.White)
    }
}

/** From this font scale the numbers go under the day's name (no room beside it). */
private const val LARGE_FONT = 1.3f
