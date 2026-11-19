package com.onpath.app.ui.suggest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.suggest.SuggestCard
import com.onpath.app.ui.ActionButtons
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.OnPathShadow
import com.onpath.app.ui.warmShadow
import com.onpath.strings.*

/*
 * SKETCH (owner 2026-11-19, round trips: „der Beschreibungstext … ist ein bisschen abgeschnitten … wenn man draufklickt,
 * dass man den Text als vollen Text angezeigt bekommt. Dazu würde ich gerne ein UI-Bild sehen vorher.“): pictures for the
 * owner before anything is built. Not wired anywhere – only SuggestFullTextSketch*Test draws these. V1 = the card grows
 * upward in place, V2 = a bottom sheet with the full text. The one new word („weniger“) comes in as a plain string
 * until the owner says yes.
 */

/** The full text's most height inside a grown card (V1); a longer text scrolls inside the card. */
private val GROWN_TEXT_MAX = 180.dp

/** km · h · ↑ and the part on the signed loop (muted), as on the card. */
@Composable
private fun StatsLine(c: SuggestCard, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    Text(buildAnnotatedString {
        append(cardStats(c))
        c.loopKm?.let { withStyle(SpanStyle(color = OnPathColors.InkMuted)) { append(" · " + str(Res.string.suggest_ui_on_loop, Format.number(it, 1))) } }
    }, style = style, modifier = modifier)
}

/**
 * V1: the panel of today (head, the swipe row, the dots) with card [grown] grown upward in place – the full text, a
 * small „▴ weniger“ under it folds it back. The other cards stay in the row (the next one looks in at the right).
 */
@Composable
fun SuggestPanelGrownSketch(ui: SuggestPanelUi, grown: Int, less: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().warmShadow(OnPathShadow.HIGH, SheetShape), shape = SheetShape, color = OnPathColors.Paper) {
        Column(Modifier.navigationBarsPadding().padding(top = 4.dp, bottom = 8.dp)) {
            PanelHeadSketch(ui.cards.size)
            val n = ui.cards.size
            val pager = rememberPagerState(initialPage = ui.chosen.coerceIn(0, n - 1)) { n }
            HorizontalPager(pager, Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = ROW_PAD, end = PEEK + CARD_GAP),
                pageSpacing = CARD_GAP, verticalAlignment = Alignment.Top, beyondViewportPageCount = n) { i ->
                if (i == grown) SuggestCardGrownSketch(ui.cards[i], chosen = i == ui.chosen, less = less)
                else SuggestCardView(ui.cards[i], chosen = i == ui.chosen)
            }
            Dots(n, ui.chosen)
        }
    }
}

/** The panel's head as today: „3 Rundtouren · Wunsch ändern · ✕“. */
@Composable
private fun PanelHeadSketch(count: Int) {
    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(plural(Res.plurals.suggest_ui_count, count, count.toString()), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(colors = ActionButtons.editText(), onClick = {}, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(str(Res.string.suggest_ui_change), maxLines = 1)
        }
        IconButton(onClick = {}, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = str(Res.string.common_close)) }
    }
}

@Composable
private fun Dots(n: Int, chosen: Int) {
    if (n > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
        repeat(n) { i ->
            Box(Modifier.padding(horizontal = 3.dp).size(6.dp).clip(CircleShape)
                .background(if (i == chosen) OnPathColors.ActionEdit else OnPathColors.Line))
        }
    }
}

/** V1's grown card: today's card, the title and the text in full (the text scrolls inside past [GROWN_TEXT_MAX], a fade says there is more). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestCardGrownSketch(c: SuggestCard, chosen: Boolean, less: String) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = OnPathColors.CardPaper,
        border = if (chosen) BorderStroke(2.dp, OnPathColors.ActionEdit) else BorderStroke(1.dp, OnPathColors.Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Badge(c.badge) }
                Spacer(Modifier.width(8.dp))
                Take(chosen, false) {}
            }
            Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
            StatsLine(c, MaterialTheme.typography.bodyMedium, Modifier.padding(end = 4.dp))
            val scroll = rememberScrollState()
            Text(c.line, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, end = 4.dp).heightIn(max = GROWN_TEXT_MAX).fadeWhenMore(scroll).verticalScroll(scroll))
            // „▴ weniger“ under the text's end, where today's „…“ is; the pills keep the whole width below it
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(colors = ActionButtons.editText(), onClick = {}, modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Filled.ExpandLess, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(less, maxLines = 1)
                }
            }
            if (c.pills.isNotEmpty()) FlowRow(Modifier.padding(end = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) { c.pills.forEach { Pill(it) } }
        }
    }
}

/** A soft fade at the text's lower edge while more of it is below (it scrolls inside the card). */
private fun Modifier.fadeWhenMore(scroll: androidx.compose.foundation.ScrollState) = drawWithContent {
    drawContent()
    if (scroll.value < scroll.maxValue) {
        val h = 28.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(OnPathColors.CardPaper.copy(alpha = 0f), OnPathColors.CardPaper), startY = size.height - h,
            endY = size.height), topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
    }
}

/**
 * V2: a bottom sheet over the row (Material 3: 28 dp top corners, handle, drag down / tap outside closes): the badge,
 * the title, km · h · ↑, the full text (scrolls inside when long), Schließen · Übernehmen at the bottom right.
 */
@Composable
fun SuggestFullTextSheetSketch(c: SuggestCard, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().warmShadow(OnPathShadow.HIGH, SHEET_TOP), shape = SHEET_TOP, color = OnPathColors.Paper) {
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 16.dp, bottom = 12.dp)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(32.dp).height(4.dp).background(OnPathColors.InkMuted.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Badge(c.badge)
                Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                StatsLine(c, MaterialTheme.typography.bodyLarge)
                Text(c.line, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(colors = ActionButtons.editText(), onClick = {}, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(str(Res.string.common_close), maxLines = 1)
                }
                Button(colors = ActionButtons.edit(), onClick = {}, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(str(Res.string.suggest_ui_take), maxLines = 1, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private val SHEET_TOP = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
