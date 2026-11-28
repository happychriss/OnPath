package com.onpath.app.ui.suggest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.routing.Activity
import com.onpath.app.suggest.SuggestCard
import com.onpath.app.suggest.SuggestPill
import com.onpath.app.ui.ActionButtons
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.OnPathShadow
import com.onpath.app.ui.warmShadow
import com.onpath.strings.*
import kotlin.math.roundToInt

/*
 * The round-trip suggestions' screens (docs/design/tour-suggestions.md §7 stage 4; the planner's web/app/src/suggest.ts
 * is the model): the wish sheet of sketch 5 B as a Material 3 dialog – activity as plain labels (no icons, owner
 * 2026-11-17), time and ascent as two-handle range sliders, the Ziel chips, Abbrechen · OK – and the panel in the plan
 * sheet: the wait, then the cards of sketch 4 A with the pills of sketch 7 (≤ 2 green + ≤ 2 yellow, no bars), side by side
 * in one row to swipe (sketch 8, owner 2026-11-18).
 */

/** Time text of the wish: „2–3 h“, halves with the language's mark („2,5–4 h“). */
fun hoursText(h: ClosedFloatingPointRange<Float>): String {
    fun one(v: Float) = if (v % 1f == 0f) v.toInt().toString() else Format.number(v.toDouble(), 1)
    return Txt.s(Res.string.suggest_ui_hours, one(h.start), one(h.endInclusive))
}

/** Ascent text of the wish: „bis 400 m“, „300–600 m“, „ab 300 m“, „egal“ (an end at the scale's end is open). */
fun ascentText(up: ClosedFloatingPointRange<Float>): String {
    val lo = up.start.roundToInt(); val hi = up.endInclusive.roundToInt()
    val open = hi >= SuggestWish.UP_MAX.roundToInt()
    return when {
        lo <= 0 && open -> Txt.s(Res.string.suggest_ui_ascent_any)
        lo <= 0 -> Txt.s(Res.string.suggest_ui_ascent_upto, hi.toString())
        open -> Txt.s(Res.string.suggest_ui_ascent_from, lo.toString())
        else -> Txt.s(Res.string.suggest_ui_ascent_range, lo.toString(), hi.toString())
    }
}

/** The wish in one line for the panel's head: „Wandern · T2 · 2–3 h · ↑ bis 400 m“. */
fun wishSummary(w: SuggestWish): String =
    listOfNotNull(w.activity.label, if (w.activity == Activity.HIKE) "T${w.sac}" else null, hoursText(w.hours), "↑ " + ascentText(w.ascent))
        .joinToString(" · ") { it.replace(' ', '\u00A0') } // breaks only between the parts

private fun zielText(z: String): String = Txt.s(when (z) {
    "gipfel" -> Res.string.suggest_ziel_gipfel
    "wasser" -> Res.string.suggest_ziel_wasser
    "aussicht" -> Res.string.suggest_ziel_aussicht
    "einkehr" -> Res.string.suggest_ziel_einkehr
    else -> Res.string.suggest_ziel_kultur
})

/** A range slider's value snapped to [step] and kept inside its scale. */
private fun snap(r: ClosedFloatingPointRange<Float>, step: Float, min: Float, max: Float): ClosedFloatingPointRange<Float> {
    fun s(v: Float) = ((v / step).roundToInt() * step).coerceIn(min, max)
    val a = s(r.start); val b = s(r.endInclusive)
    return a..maxOf(a, b)
}

/** The wish sheet (sketch 5 B): a Material 3 dialog, Abbrechen · OK in one row at the bottom right. */
@Composable
fun SuggestWishDialog(wish: SuggestWish, onChange: (SuggestWish) -> Unit, onCancel: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(str(Res.string.suggest_ui_from_here)) },
        text = { WishFields(wish, onChange) },
        confirmButton = {
            TextButton(colors = ActionButtons.editText(), onClick = onOk, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_ok)) }
        },
        dismissButton = {
            TextButton(colors = ActionButtons.editText(), onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WishFields(wish: SuggestWish, onChange: (SuggestWish) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // activity: plain labels, the plan's one pre-set; the T level beside it for a hike
        Column {
            FieldHead(str(Res.string.suggest_ui_activity), null)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(wish.activity.label, Activity.entries.map { it.label }) { i -> onChange(wish.copy(activity = Activity.entries[i])) }
                if (wish.activity == Activity.HIKE)
                    Choice("T${wish.sac}", (1..5).map { "T$it" }) { i -> onChange(wish.copy(sac = i + 1)) }
            }
        }
        Column {
            FieldHead(str(Res.string.suggest_ui_time), hoursText(wish.hours))
            Range(wish.hours, SuggestWish.H_MIN, SuggestWish.H_MAX, SuggestWish.H_STEP) { onChange(wish.copy(hours = it)) }
        }
        Column {
            FieldHead(str(Res.string.suggest_ui_ascent), ascentText(wish.ascent))
            Range(wish.ascent, 0f, SuggestWish.UP_MAX, SuggestWish.UP_STEP) { onChange(wish.copy(ascent = it)) }
        }
        Column {
            FieldHead(str(Res.string.suggest_ui_ziel), null)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (z in SuggestWish.ZIEL) {
                    val on = z in wish.ziel
                    FilterChip(selected = on, onClick = { onChange(wish.copy(ziel = if (on) wish.ziel - z else wish.ziel + z)) },
                        label = { Text(zielText(z)) })
                }
            }
        }
    }
}

/** A field's name (small, muted) and its value at the right („2–3 h“). */
@Composable
private fun FieldHead(name: String, value: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** A plain label with ▾ that opens its choices (no icons on the activity, owner 2026-11-17). */
@Composable
private fun Choice(label: String, choices: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = true, onClick = { open = true }, label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEachIndexed { i, c -> DropdownMenuItem(text = { Text(c) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

@Composable
private fun Range(value: ClosedFloatingPointRange<Float>, min: Float, max: Float, step: Float, onChange: (ClosedFloatingPointRange<Float>) -> Unit) {
    RangeSlider(value = value, onValueChange = { onChange(snap(it, step, min, max)) }, valueRange = min..max,
        steps = ((max - min) / step).roundToInt() - 1,
        colors = SliderDefaults.colors(thumbColor = OnPathColors.ActionEdit, activeTrackColor = OnPathColors.ActionEdit,
            inactiveTrackColor = OnPathColors.EditTonal,
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
}

/** What the panel shows ([SuggestSlice]'s state, plain for screenshots). */
data class SuggestPanelUi(
    val phase: SuggestPhase,
    val summary: String,
    val cards: List<SuggestCard> = emptyList(),
    val notes: List<String> = emptyList(),
    val chosen: Int = -1,
    val taking: Boolean = false,
)

class SuggestPanelCallbacks(
    val onChoose: (Int) -> Unit = {},
    val onTake: (Int) -> Unit = {},
    val onChange: () -> Unit = {},
    val onClose: () -> Unit = {},
    /** A card's whole text opened (its index) or closed (-1): the panel's height changed (the host fits the loop again). */
    val onOpen: (Int) -> Unit = {},
)

/**
 * Which card of the row shows its whole text (owner 2026-11-19, V1 „die Karte wächst“): at most one. A tap on a card's
 * text opens it (the text takes a tap only when something on the card is cut), „▴ weniger“ closes it, and the row
 * settling on another card closes it (a swipe, a tap on the card beside).
 */
class OpenCard(start: Int = -1) {
    var open by mutableIntStateOf(start)
        private set

    fun tapText(i: Int) { open = i }
    fun less() { open = -1 }
    fun settled(page: Int) { if (page != open) open = -1 }
}

/** Where a tap on a round-trip card lands: „Übernehmen“, the text block (title, numbers, text line), the rest. */
enum class CardPart { TAKE, TEXT, REST }

/** What that tap does: take the loop, open the card's whole text, or choose the card (its loop on the map). */
enum class CardTap { TAKE, OPEN, CHOOSE }

/**
 * The card's taps (owner 2026-11-28 15:36, variant A): on the card in front the whole text block opens the card when
 * something on it is cut – „⌄ mehr“ at the text line's end says so; nothing cut, or the peeking card: the text is part
 * of the card and chooses it. „Übernehmen“ answers only its own button.
 */
object CardTaps {
    fun showsMore(cut: Boolean, open: Boolean): Boolean = cut && !open

    fun of(part: CardPart, front: Boolean, cut: Boolean): CardTap = when (part) {
        CardPart.TAKE -> CardTap.TAKE
        CardPart.TEXT -> if (front && cut) CardTap.OPEN else CardTap.CHOOSE
        CardPart.REST -> CardTap.CHOOSE
    }
}

/** For the screenshot tests: what a folded card showed (by its title) – „⌄ mehr“, whether it was cut, the line's room. */
class CardProbe {
    val more = mutableMapOf<String, Boolean>()
    val moreCut = mutableMapOf<String, Boolean>()
    val lineWidth = mutableMapOf<String, Int>()
}

internal val LocalCardProbe = staticCompositionLocalOf<CardProbe?> { null }

/** How far the next card looks in at the right edge of the row (sketch 8). */
private val PEEK = 16.dp
private val ROW_PAD = 14.dp
private val CARD_GAP = 8.dp

/**
 * The suggestions in the plan sheet's place (like the place card). The cards (sketch 8, owner 2026-11-18 „Ja, so
 * bauen“): a low strip along the bottom – a small head „3 Rundtouren · Wunsch ändern · ✕“, the cards side by side in
 * one row that snaps card by card (the next one looks in at the right), dots for where one is. The card in front is the
 * chosen one: swiping to a card shows its loop on the map, alone, and the camera fits it above the strip. The wait and
 * the failure stay a short panel with the wish in one line.
 */
@Composable
fun SuggestPanel(ui: SuggestPanelUi, cb: SuggestPanelCallbacks, modifier: Modifier = Modifier, startOpen: Int = -1) {
    // a grown card never takes the loop's room: the panel stays under GROWN_MAX of the screen, the card's text scrolls inside
    val most = (LocalConfiguration.current.screenHeightDp * GROWN_MAX).dp
    Surface(
        modifier.fillMaxWidth().heightIn(max = most).warmShadow(OnPathShadow.HIGH, SheetShape),
        shape = SheetShape, color = OnPathColors.Paper,
    ) {
        Column(Modifier.navigationBarsPadding().padding(top = 4.dp, bottom = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val head = when (ui.phase) {
                    SuggestPhase.CARDS -> plural(Res.plurals.suggest_ui_count, ui.cards.size, ui.cards.size.toString())
                    else -> str(Res.string.suggest_ui_from_here)
                }
                Text(head, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ui.phase == SuggestPhase.CARDS || ui.phase == SuggestPhase.FAILED)
                    TextButton(colors = ActionButtons.editText(), onClick = cb.onChange, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(str(Res.string.suggest_ui_change), maxLines = 1)
                    }
                IconButton(onClick = cb.onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = str(Res.string.common_close))
                }
            }
            when (ui.phase) {
                SuggestPhase.WAIT, SuggestPhase.LOADING -> Column(Modifier.padding(horizontal = 16.dp)) {
                    Summary(ui.summary)
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (com.onpath.app.ui.progress.WaitMotion.othersMove()) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp, color = OnPathColors.ActionEdit)
                        Spacer(Modifier.width(12.dp))
                        Text(str(if (ui.phase == SuggestPhase.LOADING) Res.string.suggest_ui_loading_routing else Res.string.suggest_ui_wait),
                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                SuggestPhase.FAILED -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Summary(ui.summary)
                    Note(str(Res.string.suggest_ui_failed))
                }
                SuggestPhase.CARDS -> {
                    if (ui.notes.isNotEmpty()) Column(Modifier.padding(horizontal = ROW_PAD).padding(bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) { ui.notes.forEach { Note(it) } }
                    if (ui.cards.isNotEmpty()) CardRow(ui, cb, startOpen)
                }
            }
        }
    }
}

/** The sheet's shape: rounded at the top, flush with the screen's bottom edge (sketch 8). */
private val SheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

/** The panel with a grown card stays under this part of the screen: at least 40 % above it keep the loop in sight. */
internal const val GROWN_MAX = 0.6f

@Composable
private fun Summary(text: String) {
    if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The row of cards: one card in front, the next looking in by [PEEK]; it snaps card by card. Where it settles is the
 * chosen card ([SuggestPanelCallbacks.onChoose]); a card chosen elsewhere (the first one, a tap on the peeking card)
 * scrolls to the front.
 */
@Composable
private fun ColumnScope.CardRow(ui: SuggestPanelUi, cb: SuggestPanelCallbacks, startOpen: Int) {
    val n = ui.cards.size
    val pager = rememberPagerState(initialPage = ui.chosen.coerceIn(0, n - 1)) { n }
    val chosenNow by rememberUpdatedState(ui.chosen)
    val choose by rememberUpdatedState(cb.onChoose)
    val opened by rememberUpdatedState(cb.onOpen)
    val card = remember(ui.cards) { OpenCard(startOpen) }
    // the row → the slice: the card it settled on is the chosen one (its loop on the map); another card closes the open text
    LaunchedEffect(pager, n, card) {
        snapshotFlow { pager.settledPage }.collect { p ->
            card.settled(p)
            if (p in 0 until n && p != chosenNow) choose(p)
        }
    }
    // the slice → the row: a card chosen by a tap (or the first card after a run) comes to the front
    LaunchedEffect(ui.chosen) {
        if (ui.chosen in 0 until n && ui.chosen != pager.currentPage) pager.animateScrollToPage(ui.chosen)
    }
    LaunchedEffect(card) { snapshotFlow { card.open }.drop(1).collect { opened(it) } }
    HorizontalPager(
        // under the panel's cap the row takes what is left (a grown card scrolls its text inside)
        pager, Modifier.fillMaxWidth().weight(1f, fill = false),
        contentPadding = PaddingValues(start = ROW_PAD, end = PEEK + CARD_GAP),
        pageSpacing = CARD_GAP, verticalAlignment = Alignment.Top,
        beyondViewportPageCount = n, // every card composed: the strip keeps one height while swiping
    ) { i ->
        SuggestCardView(ui.cards[i], chosen = i == ui.chosen, taking = ui.taking && i == ui.chosen,
            onClick = { cb.onChoose(i) }, onTake = { cb.onTake(i) }, open = card.open == i,
            onOpen = { card.tapText(i) }, onLess = card::less)
    }
    if (n > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
        repeat(n) { i ->
            Box(Modifier.padding(horizontal = 3.dp).size(6.dp).clip(CircleShape)
                .background(if (i == ui.chosen) OnPathColors.ActionEdit else OnPathColors.Line))
        }
    }
}

@Composable
private fun Note(text: String) {
    Surface(color = OnPathColors.WarnContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = OnPathColors.OnWarnContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

/** „8,4 km · 2:10 h · ↑ 180 m“ */
fun cardStats(c: SuggestCard): String =
    "${Format.number(c.km, 1)} km · ${Format.durationShort(c.hours * 3600)} · ↑ ${c.ascent} m"

/**
 * One compact card (sketch 8): badge and „Übernehmen“ on top (filled on the chosen card, outlined on the others), the
 * title, km · h · ↑ (and the part on the signed loop), the text in one line, the pills. Where something is cut, „⌄ mehr“
 * ends the text line and a tap on the text block grows the card in place ([open], owner 2026-11-19 V1, 2026-11-28
 * variant A): title, line and text in full, „⌃ weniger“ under them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestCardView(
    c: SuggestCard, chosen: Boolean, taking: Boolean = false, onClick: () -> Unit = {}, onTake: () -> Unit = {},
    open: Boolean = false, onOpen: () -> Unit = {}, onLess: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = OnPathColors.CardPaper,
        border = if (chosen) BorderStroke(2.dp, OnPathColors.ActionEdit) else BorderStroke(1.dp, OnPathColors.Line),
        modifier = Modifier.fillMaxWidth().semantics { selected = chosen }.clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = if (open) 8.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Badge(c.badge) }
                Spacer(Modifier.width(8.dp))
                Take(chosen, taking, onTake)
            }
            // at a large font the grown card's pills scroll with the text: the room under the panel's cap goes to the text
            val pillsInside = open && LocalDensity.current.fontScale >= LARGE_FONT
            val pills: @Composable () -> Unit = {
                if (c.pills.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) { c.pills.forEach { Pill(it) } }
            }
            if (open) GrownText(c, onLess, if (pillsInside) pills else null) else ShortText(c, front = chosen, onOpen)
            if (!pillsInside) pills()
        }
    }
}

/** From this font scale on, a grown card's pills scroll with its text (under „▴ weniger“ they would take the text's room). */
private const val LARGE_FONT = 1.15f

/** km · h · ↑ and the part on the signed loop (muted). */
@Composable
private fun statsText(c: SuggestCard) = buildAnnotatedString {
    append(cardStats(c))
    c.loopKm?.let { withStyle(SpanStyle(color = OnPathColors.InkMuted)) { append(" · " + str(Res.string.suggest_ui_on_loop, Format.number(it, 1))) } }
}

/**
 * The card as it sits in the row: one line each. Where any of them is cut, „⌄ mehr“ ends the text line (the counterpart
 * of „⌃ weniger“) and, on the card in front, the whole text block – at least 48 dp – takes the tap that grows the card
 * (owner 2026-11-28 15:36, variant A); else a tap there is the card's own (it chooses the card).
 */
@Composable
private fun ShortText(c: SuggestCard, front: Boolean, onOpen: () -> Unit) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    // measured here, in the same pass (not from onTextLayout a frame later): „mehr“ and the tap never flicker in
    val measurer = rememberTextMeasurer()
    val name = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    val stats = statsText(c)
    val statsStyle = MaterialTheme.typography.bodyMedium
    val lineStyle = MaterialTheme.typography.bodySmall
    val room = constraints.maxWidth - with(LocalDensity.current) { 4.dp.roundToPx() }
    fun cut(t: androidx.compose.ui.text.AnnotatedString, st: androidx.compose.ui.text.TextStyle) = t.isNotEmpty() &&
        measurer.measure(t, st, overflow = TextOverflow.Ellipsis, maxLines = 1,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = room.coerceAtLeast(0))).hasVisualOverflow
    val isCut = cut(androidx.compose.ui.text.AnnotatedString(c.name), name) || cut(stats, statsStyle) ||
        cut(androidx.compose.ui.text.AnnotatedString(c.line), lineStyle)
    val showMore = CardTaps.showsMore(isCut, open = false)
    val probe = LocalCardProbe.current
    SideEffect { probe?.more?.set(c.name, showMore) }
    val more = str(Res.string.suggest_ui_more)
    val tap = if (CardTaps.of(CardPart.TEXT, front, isCut) == CardTap.OPEN)
        Modifier.clickable(role = Role.Button, onClickLabel = more, onClick = onOpen) else Modifier
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(tap), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(c.name, style = name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 4.dp))
        Text(stats, style = statsStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 4.dp))
        if (c.line.isNotBlank() || showMore) Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.line, style = lineStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                onTextLayout = { probe?.lineWidth?.set(c.name, it.size.width) })
            if (showMore) MoreMark(c.name, probe)
        }
    }
}

/** „⌄ mehr“: the look of „⌃ weniger“ (blue, the icon before the word), not a button of its own – the text block is. */
@Composable
private fun MoreMark(name: String, probe: CardProbe?) {
    Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = OnPathColors.ActionEdit, modifier = Modifier.size(20.dp))
        Text(str(Res.string.suggest_ui_more_mark), style = MaterialTheme.typography.labelLarge, color = OnPathColors.ActionEdit,
            maxLines = 1, softWrap = false, onTextLayout = { r -> probe?.moreCut?.set(name, r.hasVisualOverflow) })
    }
}

/**
 * The grown card's middle: title, line and the whole text in full. Where the panel's room ([GROWN_MAX]) is not enough
 * (a large font) they scroll inside the card, a fade at the edge where more is; „▴ weniger“ under them – like
 * „Übernehmen“ above – never scrolls away.
 */
@Composable
private fun ColumnScope.GrownText(c: SuggestCard, onLess: () -> Unit, pills: (@Composable () -> Unit)?) {
    val scroll = rememberScrollState()
    Column(Modifier.weight(1f, fill = false).fadeAtEdges(scroll).verticalScroll(scroll).padding(end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(statsText(c), style = MaterialTheme.typography.bodyMedium)
        if (c.line.isNotBlank()) Text(c.line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        pills?.invoke()
    }
    // „▴ weniger“ at the text's end, where the row's „…“ was; the pills keep the whole width below it (a large font: above it)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(colors = ActionButtons.editText(), onClick = onLess, modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Filled.ExpandLess, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(str(Res.string.suggest_ui_less), maxLines = 1)
        }
    }
}

/** How deep the fade at a scrolling text's edge is: clearly more than a line. */
private val FADE = 40.dp

/** A fade to the card's paper at the lower edge while more text is below, at the upper edge while some is above. */
private fun Modifier.fadeAtEdges(scroll: ScrollState) = drawWithContent {
    drawContent()
    val h = FADE.toPx().coerceAtMost(size.height / 2)
    val paper = OnPathColors.CardPaper
    if (scroll.value < scroll.maxValue)
        drawRect(Brush.verticalGradient(listOf(paper.copy(alpha = 0f), paper), startY = size.height - h, endY = size.height),
            topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
    if (scroll.value > 0)
        drawRect(Brush.verticalGradient(listOf(paper, paper.copy(alpha = 0f)), startY = 0f, endY = h), size = Size(size.width, h))
}

/** „Übernehmen“ in the card's top row: filled on the chosen card, outlined on the others (sketch 8). */
@Composable
private fun Take(chosen: Boolean, taking: Boolean, onTake: () -> Unit) {
    val pad = PaddingValues(horizontal = 14.dp)
    if (chosen) Button(colors = ActionButtons.edit(), onClick = onTake, enabled = !taking, contentPadding = pad) {
        // taking: only the button spins, its word stays (owner 2026-11-27: no waiting sentence where the user tapped)
        if (taking && com.onpath.app.ui.progress.WaitMotion.othersMove()) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(8.dp))
        }
        Text(str(Res.string.suggest_ui_take), maxLines = 1)
    } else OutlinedButton(colors = ActionButtons.editOutlined(), onClick = onTake, contentPadding = pad,
        border = BorderStroke(1.dp, OnPathColors.ActionEdit.copy(alpha = 0.55f))) {
        Text(str(Res.string.suggest_ui_take), maxLines = 1)
    }
}

/** „Ausgeschildert · Traumpfad“ (blue) or „Vorschlag“ (green), small. */
@Composable
private fun Badge(badge: String?) {
    val (bg, fg) = if (badge != null) SuggestColours.SignedBg to SuggestColours.SignedInk else SuggestColours.OwnBg to SuggestColours.OwnInk
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(if (badge != null) str(Res.string.suggest_ui_badge_signed, badge) else str(Res.string.suggest_ui_badge_own),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = fg,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A sketch-7 pill: light green (strength) or light yellow (weakness / hint), three intensities. */
@Composable
private fun Pill(p: SuggestPill) {
    val bg = SuggestColours.pill(p.strength, p.level)
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Text(p.text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = if (p.strength) SuggestColours.GreenInk else SuggestColours.YellowInk,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), maxLines = 1)
    }
}

/** The colours of the planner's cards (suggest.css), the same on the phone. */
object SuggestColours {
    val SignedBg = Color(0xFFE3E9F7)
    val SignedInk = Color(0xFF1E3FAE)
    val OwnBg = Color(0xFFDDE9DB)
    val OwnInk = Color(0xFF1F4F2A)
    val GreenInk = Color(0xFF1C3F1F)
    val YellowInk = Color(0xFF4A3A00)
    private val GREEN = listOf(Color(0xFFDCEDD4), Color(0xFFCDE6C3), Color(0xFFA9D49A))
    private val YELLOW = listOf(Color(0xFFF7E8B8), Color(0xFFF6E4A6), Color(0xFFEED073))
    fun pill(strength: Boolean, level: Int): Color = (if (strength) GREEN else YELLOW)[(level - 1).coerceIn(0, 2)]
}
