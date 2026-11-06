package com.onpath.app.ui

import androidx.compose.foundation.clickable
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
 * value in Walnuss, a chevron – and a tap opens the choice as a bottom sheet with radio rows; the tap on one applies it
 * and closes the sheet. A bottom sheet, not a dialog: docs/design/ui-style.md keeps dialogs for decisions that can lose
 * something, and picking a setting loses nothing (the old value is one tap away). Switches stay switches. NOT for the
 * hiking T1–T5 choice (owner: those stay buttons, wherever they appear).
 */
object SettingRows {
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

    /** The value shown on a choice row: the chosen option's label, or the first one when the stored value is unknown. */
    fun <T> valueOf(options: List<Pair<T, String>>, selected: T): String =
        options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
}

/** One option of a [ChoiceRow]: its value, its word, and optionally one line under it in the sheet. */
data class Choice<T>(val value: T, val label: String, val line: String? = null)

/** A group heading above a settings card ("ABSEITS DER ROUTE"): small, Walnuss, like Android's settings. */
@Composable
internal fun SettingsGroupHeader(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.Bold, color = OnPathColors.Walnut.copy(alpha = 0.75f),
    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 0.dp))

/** A paper card for one group of settings rows. */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) =
    Surface(shape = OnPathShapes.Card, color = OnPathColors.Paper, modifier = Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Card)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { content() }
    }

/**
 * A value row: [title] (+ [sub]), the chosen option's word in Walnuss, a chevron; a tap opens the choice sheet.
 * [indent]: depends on the switch above it; [enabled] false = greyed (38 %) and not tappable.
 */
@Composable
internal fun <T> ChoiceRow(title: String, sub: String?, options: List<Choice<T>>, selected: T, onPick: (T) -> Unit,
                           enabled: Boolean = true, indent: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val value = SettingRows.valueOf(options.map { it.value to it.label }, selected)
    val a = if (enabled) 1f else 0.38f
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button) { open = true }
        .padding(start = if (indent) 32.dp else 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = a))
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a))
        }
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            color = OnPathColors.Walnut.copy(alpha = a), maxLines = 2)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a))
    }
    if (open) ChoiceSheet(title, options, selected, onPick = { open = false; if (it != selected) onPick(it) }, onDismiss = { open = false })
}

/**
 * The choice of a [ChoiceRow] as a bottom sheet: title, one radio row per option (with its line), the tap applies it
 * and closes. Drag down / Back / a tap outside close without a change. [inline]: drawn in place (screenshot tests –
 * Paparazzi draws no sheet window).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceSheet(title: String, options: List<Choice<T>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit,
                             inline: Boolean = false) {
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).selectableGroup()) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
            options.forEach { o ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = o.value == selected, onClick = { onPick(o.value) }, role = Role.RadioButton)
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = o.value == selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp),
                        colors = RadioButtonDefaults.colors(selectedColor = OnPathColors.Walnut))
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(o.label, style = MaterialTheme.typography.bodyLarge)
                        o.line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    if (inline) {
        val sheet = RoundedCornerShape(topStart = OnPathShapes.SHEET_DP.dp, topEnd = OnPathShapes.SHEET_DP.dp)
        Surface(Modifier.fillMaxWidth().warmShadow(OnPathShadow.LOW, sheet), shape = sheet, color = OnPathColors.Paper) {
            Column {
                androidx.compose.material3.BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                content()
            }
        }
        return
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OnPathColors.Paper) { content() }
}

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
