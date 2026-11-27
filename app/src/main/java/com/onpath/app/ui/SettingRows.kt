package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.data.JunctionGuide
import com.onpath.app.setup.Check
import com.onpath.app.setup.CheckId
import com.onpath.app.setup.Need
import com.onpath.strings.*

/**
 * Settings in "value rows" (owner 2026-11-06 23:05, variant C of the Settings → Navigation round: "Ich mag Wertezeilen
 * am meisten, das sollten wir auch bei allen anderen Einstellungen so machen"): a choice is one row – title, the current
 * value in bold ink, a chevron – and a tap opens a small menu anchored at the row (owner 2026-11-27, one control standard:
 * Material 3 DropdownMenu, no sheet, no sub page, no segmented buttons); the tap on one applies it and closes the menu.
 * Not a dialog: docs/design/ui-style.md keeps dialogs for decisions that can lose something, and picking a setting loses
 * nothing (the old value is one tap away). Switches stay switches. NOT for the hiking T1–T5 choice (owner: those stay
 * buttons, wherever they appear).
 */
object SettingRows {
    /**
     * A value row side by side or stacked (value under the title): side by side only when the value fits in ~45 % of the
     * row ([contentW] without paddings and chevron) and the title's longest word still fits beside it – else a word
     * would break in the middle ("Kartenausrich-tung", large font). Widths in the same unit (px or dp).
     */
    fun valueStacked(contentW: Float, valueW: Float, titleLongestWordW: Float, gap: Float): Boolean =
        valueW > contentW * 0.45f || titleLongestWordW > contentW - valueW - gap

    /** Where "Handy einrichten" sits on the Navigation page. */
    enum class PhoneRow {
        /** Something a tour needs is missing: at the top, red ⚠. */
        TOP_PROBLEM,
        /** Only something recommended is missing: at the top, amber ⚠. */
        TOP_WARNING,
        /** All set up (or no checks known): the last row, plain. */
        LAST,
    }

    /**
     * "Handy einrichten" on the Navigation page (owner 2026-11-06 23:05: "muss nach oben oder markiert werden, wenn
     * irgendwas von den Sachen nicht eingerichtet ist"): at the top while a required or recommended check is not ok –
     * optional ones never move it (the ☰ menu and the Settings list mark only the required ones, unchanged).
     */
    fun phoneRow(checks: List<Check>?): PhoneRow = when {
        checks.orEmpty().any { !it.ok && it.need == Need.REQUIRED } -> PhoneRow.TOP_PROBLEM
        checks.orEmpty().any { !it.ok && it.need == Need.RECOMMENDED } -> PhoneRow.TOP_WARNING
        else -> PhoneRow.LAST
    }

    /** What is missing, required first, each once, in the checks' order ("Fehlt: Standort, Akku"). */
    fun missing(checks: List<Check>?): List<CheckId> {
        val open = checks.orEmpty().filter { !it.ok && it.need != Need.OPTIONAL }
        return (open.filter { it.need == Need.REQUIRED } + open.filter { it.need == Need.RECOMMENDED }).map { it.id }.distinct()
    }

    /** The one "Testen" under the junction cue tests the chosen kind; Off = no test button. */
    fun testFor(guide: JunctionGuide): CheckId? = when (guide) {
        JunctionGuide.VIBRATION -> CheckId.VIBRATION
        JunctionGuide.VOICE -> CheckId.VOICE
        JunctionGuide.OFF -> null
    }

    /** Where each tile of a [SettingsCard] sits: top and bottom (px) of rows [heights] with [gap] between them. */
    fun tileSpans(heights: List<Int>, gap: Int): List<Pair<Int, Int>> {
        var y = 0
        return heights.mapIndexed { i, h -> if (i > 0) y += gap; (y to y + h).also { y += h } }
    }

    /** The tiles as one path: the first tile's top corners and the last one's bottom corners [outer], the rest [inner]. */
    fun tilesPath(spans: List<Pair<Int, Int>>, width: Float, outer: Float, inner: Float): androidx.compose.ui.graphics.Path {
        val path = androidx.compose.ui.graphics.Path()
        spans.forEachIndexed { i, (top, bottom) ->
            val t = androidx.compose.ui.geometry.CornerRadius(if (i == 0) outer else inner)
            val b = androidx.compose.ui.geometry.CornerRadius(if (i == spans.lastIndex) outer else inner)
            path.addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, top.toFloat(), width, bottom.toFloat(), t, t, b, b))
        }
        return path
    }

    /** The value shown on a choice row: the chosen option's label, or the first one when the stored value is unknown. */
    fun <T> valueOf(options: List<Pair<T, String>>, selected: T): String =
        options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
}

/** One option of a [ChoiceRow]: its value, its word, and optionally one line under it in the sheet. */
data class Choice<T>(val value: T, val label: String, val line: String? = null)

/**
 * A group heading above a settings card ("Abseits der Route"): as Android's own settings – sentence case, the accent green
 * (owner 2026-11-27, variant B: the grey-brown capitals were the faintest text on the page). [top]: extra room above it.
 */
@Composable
internal fun SettingsGroupHeader(text: String, top: androidx.compose.ui.unit.Dp = 8.dp) = Text(text,
    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = OnPathColors.Green,
    modifier = Modifier.padding(start = 16.dp, top = top, bottom = 0.dp))

/** The ground of the settings pages: the deeper sage, so the light rows stand out (variant B, 2026-11-27). */
internal val SettingsGround get() = OnPathColors.PaperDeep

/**
 * One group of settings rows, as Android 16's settings draw them (owner 2026-11-27, variant B „Android-16-Gruppen“):
 * every row its own light tile, [TILE_GAP_DP] of ground between them, the group's outer corners round
 * ([OnPathShapes.CARD_DP]), the inner ones nearly square – no shadow, no dividers. A child thinner than 3 dp (a
 * HorizontalDivider of the older cards) is not drawn: the gap is the divider now. Ripples stay inside the tiles.
 */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    // the tiles' vertical spans, written in layout, read only when drawing (no recomposition)
    val spans = remember { mutableStateOf(emptyList<Pair<Int, Int>>()) }
    val paper = OnPathColors.CardPaper
    androidx.compose.ui.layout.Layout(content, Modifier.fillMaxWidth().drawWithContent {
        val path = SettingRows.tilesPath(spans.value, size.width, OnPathShapes.CARD_DP.dp.toPx(), TILE_INNER_DP.dp.toPx())
        drawPath(path, paper)
        clipPath(path) { this@drawWithContent.drawContent() }
    }) { ms, c ->
        val gap = TILE_GAP_DP.dp.roundToPx()
        val thin = 3.dp.roundToPx()
        val loose = c.copy(minWidth = 0, minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val ps = ms.map { it.measure(loose) }.filter { it.height >= thin }
        val tiles = SettingRows.tileSpans(ps.map { it.height }, gap)
        if (tiles != spans.value) spans.value = tiles
        layout(c.maxWidth, tiles.lastOrNull()?.second ?: 0) {
            ps.forEachIndexed { i, p -> p.placeRelative(0, tiles[i].first) }
        }
    }
}

private const val TILE_GAP_DP = 2
private const val TILE_INNER_DP = 4

/**
 * A value row: [title] (+ [sub]), the chosen option's word in Walnuss, a chevron; a tap opens the choice menu
 * ([ChoiceMenuItems] in a DropdownMenu anchored at the row).
 * [indent]: depends on the switch above it; [enabled] false = greyed (38 %) and not tappable.
 */
@Composable
internal fun <T> ChoiceRow(title: String, sub: String?, options: List<Choice<T>>, selected: T, onPick: (T) -> Unit,
                           enabled: Boolean = true, indent: Boolean = false, menuLines: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val value = SettingRows.valueOf(options.map { it.value to it.label }, selected)
    val a = if (enabled) 1f else 0.38f
    val titleStyle = MaterialTheme.typography.bodyLarge
    val valueStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    val measure = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val start = if (indent) 32.dp else 16.dp
    // the room for title + value: the row minus its paddings and the chevron
    val contentW = with(density) { (maxWidth - start - 12.dp - 24.dp).toPx() }
    val valueW = measure.measure(value, valueStyle).size.width.toFloat()
    val longestWord = title.split(' ').maxOfOrNull { measure.measure(it, titleStyle).size.width.toFloat() } ?: 0f
    val stacked = SettingRows.valueStacked(contentW, valueW, longestWord, with(density) { 12.dp.toPx() })
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button) { open = true }
        .padding(start = start, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = titleStyle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = a))
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a))
            // too long beside the title (large font, long words): the value under it, never a word broken in two
            if (stacked) Text(value, style = valueStyle, color = OnPathColors.Ink.copy(alpha = a))
        }
        if (!stacked) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = valueStyle, color = OnPathColors.Ink.copy(alpha = a), maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a))
    }
    // the menu opens under the value, at the row's end (the eye is there after the tap)
    Box(Modifier.align(Alignment.BottomEnd)) {
        // white with a clear shadow (variant B): the menu lifts off the paper rows instead of melting into them
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false },
            shape = ChoiceMenuShape, containerColor = Color.White, shadowElevation = 6.dp) {
            ChoiceMenuItems(options, selected, menuLines) { open = false; if (it != selected) onPick(it) }
        }
    }
    }
}

/**
 * The options of a [ChoiceRow]'s menu: one item per option – a ✓ before the chosen one, the word, its line under it when
 * it has one (pace: "etwa 4 km/h · 300 Höhenmeter pro Stunde"); a tap picks it. Inside a DropdownMenu, or – screenshot
 * tests, Paparazzi draws no popup window – in a [ChoiceMenuCard].
 */
@Composable
internal fun <T> ChoiceMenuItems(options: List<Choice<T>>, selected: T, lines: Boolean = true, onPick: (T) -> Unit) {
    options.forEach { o ->
        val chosen = o.value == selected
        androidx.compose.material3.DropdownMenuItem(
            text = {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(o.label, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                    o.line?.takeIf { lines }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
            leadingIcon = {
                if (chosen) Icon(Icons.Filled.Check, null, tint = OnPathColors.Green, modifier = Modifier.size(20.dp))
                else Spacer(Modifier.size(20.dp))
            },
            onClick = { onPick(o.value) },
            // the chosen one on a light green ground, like Android's own menus (variant B)
            modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 320.dp).padding(horizontal = 6.dp)
                .then(if (chosen) Modifier.background(ChoiceChosen, RoundedCornerShape(12.dp)) else Modifier)
                .semantics { this.selected = chosen },
        )
    }
}

/** A choice menu drawn in place, like the DropdownMenu's own surface (screenshot tests). */
@Composable
internal fun <T> ChoiceMenuCard(options: List<Choice<T>>, selected: T, lines: Boolean = true) =
    Surface(shape = ChoiceMenuShape, color = Color.White, shadowElevation = 6.dp,
        modifier = Modifier.widthIn(min = 112.dp, max = 320.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) { ChoiceMenuItems(options, selected, lines) {} }
    }

private val ChoiceMenuShape = RoundedCornerShape(16.dp)
/** The chosen option's ground in a choice menu: a light green (plan green at ~12 % on white). */
private val ChoiceChosen = Color(0xFFE4EEE7)

/**
 * "Handy einrichten" as a row: plain with a chevron when all is set up; at the top of the Navigation page with ⚠ (red:
 * a tour needs it, amber: recommended) and "Fehlt: …" while something is missing ([SettingRows.phoneRow]).
 */
@Composable
internal fun PhoneSetupRow(place: SettingRows.PhoneRow, missing: List<String>, onClick: () -> Unit) {
    val colour = when (place) {
        SettingRows.PhoneRow.TOP_PROBLEM -> OnPathColors.Bad
        SettingRows.PhoneRow.TOP_WARNING -> OnPathColors.Warn
        SettingRows.PhoneRow.LAST -> null
    }
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (colour != null) {
            Icon(if (place == SettingRows.PhoneRow.TOP_PROBLEM) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber, null,
                tint = colour, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(str(Res.string.setup_nav_check), style = MaterialTheme.typography.bodyLarge)
            if (colour != null && missing.isNotEmpty()) Text(str(Res.string.settings_phone_missing, missing.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = colour)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
