package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline

import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.onpath.app.debug.Problems
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import com.onpath.app.data.Format
import com.onpath.app.domain.tour.EndSheetGuard

/** Recording state dot: orange while recording, grey while paused. */
@Composable
fun RecDot(paused: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(if (paused) OnPathColors.Grey else OnPathColors.Rec, CircleShape))
}

/** What the end sheet shows of the tour: distance, moving time, ascent (from the running recording). */
data class EndStats(val distanceM: Double, val elapsedS: Long, val ascentM: Double)

/** The way back from the end sheet: "Weiter" (owner 2026-11-06, one word after a pause / at the end). */
internal fun keepGoingLabel(): String = Txt.s(Res.string.nav_continue) // owner 2026-11-06: one word – "Weiter"

/**
 * "Tour beenden?": a sheet from the bottom – header (activity sign, title, activity, ✕); distance · time · ascent and the
 * big green "Tour speichern" where the thumb is – nothing about the plan (owner 2026-11-06: the tour and the plan are two
 * things; the plan was saved at "Los geht's", drafts live under Zuletzt); then "Weiter" and the quiet red "Tour löschen"
 * with the bin note – no second question (owner 2026-11-01: "ist ja sowieso alles im Papierkorb"). Large font: it scrolls.
 * [keepLabel]: the way back (arrival: the same activity word).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EndTourDialog(
    onSave: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
    title: String = str(Res.string.nav_end_tour_title),
    activity: com.onpath.app.routing.Activity? = null,
    stats: EndStats? = null,
    keepLabel: String = keepGoingLabel(),
    /** Screenshot tests: the sheet drawn at the bottom of the screen itself (Paparazzi draws no sheet window). */
    inline: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        EndTourContent(onSave, onDelete, onDismiss, title, activity, stats, keepLabel)
    }
    if (inline) {
        Box(Modifier.fillMaxSize()) {
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    androidx.compose.material3.BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                    content()
                }
            }
        }
        return
    }
    // A real bottom sheet (owner 2026-11-06 on the Pixel: Weiterfahren | Löschen were cut off at the bottom – the
    // full-screen Dialog window started under the status bar but was laid out a full screen high, so its lowest
    // ~50 dp were below the glass). ModalBottomSheet knows the status and gesture bars; opened fully, never half.
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) { content() }
}

/** The end sheet's inside: title, numbers, Save, Weiter | Löschen, bin note. */
@Composable
private fun EndTourContent(
    onSave: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit, title: String,
    activity: com.onpath.app.routing.Activity?, stats: EndStats?, keepLabel: String,
) {
                // the thumb that just held "Beenden" (hold lock) must not save (field bug 2026-11-06 22:30): every button
                // here ignores a press that began within EndSheetGuard.GUARD_MS of the sheet appearing
                val shownAt = remember { android.os.SystemClock.uptimeMillis() }
                androidx.compose.runtime.LaunchedEffect(Unit) { Problems.trace("tour", "End sheet shown: \"$title\"") }
                val save = guardedTap("Tour speichern", shownAt, onSave)
                val keep = guardedTap("$keepLabel (keep going)", shownAt, onDismiss)
                val close = guardedTap("✕ (keep going)", shownAt, onDismiss)
                val delete = guardedTap("Tour löschen", shownAt, onDelete)
                Column(Modifier.verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 16.dp)) {
                    // icon and ✕ at the title's first line: a long title wraps under itself, nothing floats between lines
                    Row(verticalAlignment = Alignment.Top) {
                        if (activity != null) {
                            Icon(activity.icon, null, tint = OnPathColors.ActionGo, modifier = Modifier.padding(top = 2.dp).size(28.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.headlineSmall)
                            if (activity != null) Text(activity.navName, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.IconButton(onClick = close.onClick, interactionSource = close.source,
                            modifier = Modifier.offset(y = (-8).dp).size(48.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = keepLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // Variant A (owner 2026-11-06, "fertig und keinen Bock mehr" – much used): everything on one screen
                    // without scrolling – the numbers in one line, the big green
                    // Save, then Weiterfahren | Löschen side by side (stacked with a large font); Löschen = the bin, no
                    // second question (owner 2026-11-01)
                    if (stats != null) { Spacer(Modifier.height(8.dp)); EndStatsLine(stats) }
                    Spacer(Modifier.height(16.dp))
                    EndSaveButton(str(Res.string.nav_save_recording), save)
                    Spacer(Modifier.height(12.dp))
                    EndKeepAndDelete(keepLabel, keep, delete)
                }
}

/** "821 m · 4 min · ↑ 3 m" – the tour's numbers in one line under the title (wraps with a large font, never cut). */
@Composable
private fun EndStatsLine(st: EndStats) = Text(
    // a value never breaks inside ("842 m" whole): no-break spaces within, the line wraps only between the values;
    // instrument numbers (owner 2026-11-06): big digits, small units
    instrument(listOf(Format.distance(st.distanceM), Format.durationShort(st.elapsedS.toDouble()), "↑ ${Format.meters(st.ascentM)}")
        .joinToString("  ·  ") { it.replace(' ', '\u00A0') }, MaterialTheme.typography.titleMedium),
    style = MaterialTheme.typography.titleMedium)

/**
 * Weiterfahren | Löschen as equals under Save (owner 2026-11-06, variant A): both outlined, 56 dp, Löschen red on the
 * word; side by side, stacked with a large font. The bin note under them.
 */
@Composable
private fun EndKeepAndDelete(keepLabel: String, onKeep: GuardedTap, onDelete: GuardedTap) {
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
    val keep: @Composable (Modifier) -> Unit = { m ->
        OutlinedButton(onClick = onKeep.onClick, interactionSource = onKeep.source, modifier = m.heightIn(min = 56.dp),
            border = BorderStroke(1.dp, OnPathColors.ActionGo)) {
            Text(keepLabel, color = OnPathColors.ActionGo, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
    val delete: @Composable (Modifier) -> Unit = { m ->
        OutlinedButton(onClick = onDelete.onClick, interactionSource = onDelete.source, modifier = m.heightIn(min = 56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(6.dp))
            Text(str(Res.string.nav_delete_tour_short), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (stacked) {
            keep(Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); delete(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            keep(Modifier.weight(1f)); delete(Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(RECYCLE_BIN_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** The main action, filled green, full width, 56 dp. */
@Composable
private fun EndSaveButton(label: String, tap: GuardedTap) = Button(onClick = tap.onClick, interactionSource = tap.source,
    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    colors = ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionGo)) {
    Icon(Icons.Filled.Check, null); Spacer(Modifier.width(8.dp))
    Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
}

/** A button's press source and its click, guarded by [EndSheetGuard] (the end sheet). */
internal class GuardedTap(val source: androidx.compose.foundation.interaction.MutableInteractionSource, val onClick: () -> Unit)

/**
 * [onClick] runs only when the finger went down [EndSheetGuard.GUARD_MS] or more after [shownAt]; a click without a
 * press (TalkBack) counts from the click. Every tap – done or ignored – goes into the problem log.
 */
@Composable
internal fun guardedTap(what: String, shownAt: Long, onClick: () -> Unit): GuardedTap {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressedAt = remember { longArrayOf(0L) }
    androidx.compose.runtime.LaunchedEffect(source) {
        source.interactions.collect {
            if (it is androidx.compose.foundation.interaction.PressInteraction.Press) pressedAt[0] = android.os.SystemClock.uptimeMillis()
        }
    }
    val click = androidx.compose.runtime.rememberUpdatedState(onClick)
    return remember(source, shownAt) {
        GuardedTap(source) {
            val now = android.os.SystemClock.uptimeMillis()
            val pressed = if (pressedAt[0] in shownAt..now) pressedAt[0] else now
            if (EndSheetGuard.accepts(shownAt, pressed)) {
                Problems.trace("tour", "End sheet: $what (pressed ${pressed - shownAt} ms after it appeared)")
                click.value()
            } else Problems.trace("tour", "End sheet: $what ignored – pressed ${pressed - shownAt} ms after the sheet appeared")
        }
    }
}

/** The way back, outlined green, full width. */
@Composable
private fun EndKeepButton(label: String, tap: GuardedTap) = OutlinedButton(onClick = tap.onClick, interactionSource = tap.source,
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), border = BorderStroke(1.dp, OnPathColors.ActionGo)) {
    Text(label, color = OnPathColors.ActionGo, style = MaterialTheme.typography.labelLarge, maxLines = 1)
}

/** Delete: always there, quiet, apart from Save – red only on the word; the bin note under it, no second question. */
@Composable
private fun EndDeleteArea(onDelete: GuardedTap) = Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    TextButton(onClick = onDelete.onClick, interactionSource = onDelete.source, modifier = Modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
        Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text(str(Res.string.nav_delete_tour))
    }
    Text(RECYCLE_BIN_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/**
 * The buttons of the other dialogs that end a tour (an interrupted recording, the ride check) – the same order as the
 * end sheet (owner 2026-11-05, V2): filled green Save, outlined way back, then the quiet red "Tour löschen" with the bin
 * note (owner 2026-11-01: "ich möchte immer auf Löschen drücken" – always there, apart from Save, no second question).
 */
@Composable
internal fun DeleteApartButtons(onDelete: (() -> Unit)?, cancelLabel: String, onCancel: () -> Unit, saveLabel: String, onSave: () -> Unit) {
    // the ride check also opens straight from a held "Beenden": the same guard as the end sheet
    val shownAt = remember { android.os.SystemClock.uptimeMillis() }
    val save = guardedTap(saveLabel, shownAt, onSave)
    val cancel = guardedTap("$cancelLabel (keep going)", shownAt, onCancel)
    val delete = onDelete?.let { guardedTap("Tour löschen", shownAt, it) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EndSaveButton(saveLabel, save)
        EndKeepButton(cancelLabel, cancel)
        if (delete != null) EndDeleteArea(delete)
    }
}

/** Body of every "Delete …?" dialog that moves to the recycle bin. */
internal val RECYCLE_BIN_NOTE: String get() = Txt.s(Res.string.nav_recycle_bin_note, com.onpath.app.backup.RecycleBin.DAYS)

/** Shown once in Navigate when the destination is reached: the same three choices. */
@Composable
fun ArrivalDialog(onSave: () -> Unit, onDelete: () -> Unit, onKeepGoing: () -> Unit,
                  activity: com.onpath.app.routing.Activity? = null, stats: EndStats? = null, inline: Boolean = false) =
    EndTourDialog(onSave, onDelete, onKeepGoing, title = str(Res.string.nav_arrived_title), activity = activity, stats = stats,
        inline = inline)


/**
 * [RouteBack]: the goal of a start → goal plan was deleted during a tour. Stays until answered (no outside tap / back
 * dismiss – never self-dismissing). Verb buttons.
 */
@Composable
fun RouteBackDialog(onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.Flag, null) },
        title = { Text(str(Res.string.nav_route_back_title)) },
        text = { DialogText(str(Res.string.nav_route_back_text)) },
        confirmButton = { TextButton(colors = ActionButtons.goText(), onClick = onYes) { Text(str(Res.string.nav_route_back)) } },
        dismissButton = { TextButton(onClick = onNo) { Text(str(Res.string.nav_just_record)) } },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}

/**
 * A plan far longer than a day (e.g. a point in another country by mistake). Asked before anything is routed or
 * loaded. Cancel takes back the change that made it so long (AppViewModel.cancelRun). Stays until a button is tapped:
 * an outside tap or Back must not undo anything.
 */
@Composable
fun LongRouteDialog(seconds: Double, activity: com.onpath.app.routing.Activity, onPlan: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Outlined.Warning, null, tint = OnPathColors.Warn) },
        title = { Text(str(Res.string.nav_long_route_title)) },
        text = { DialogText(com.onpath.app.routing.LongRoute.text(seconds, activity)) },
        confirmButton = { TextButton(colors = ActionButtons.editText(), onClick = onPlan) { Text(str(Res.string.nav_plan_anyway)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(str(Res.string.common_cancel)) } },
        properties = LongRouteDialogRules.properties,
    )
}

/**
 * Problem 9: a tap outside "This route is very long" or Back must not undo – the question stays until a button
 * (Plan anyway / Cancel) answers it.
 */
internal object LongRouteDialogRules {
    val properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
}
