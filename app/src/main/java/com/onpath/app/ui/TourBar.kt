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

/**
 * The tour card in Plan mode while a tour records: the three answers to "what now with my
 * tour?" together in one row – Continue navigation (filled, most used), Pause / Resume (tonal), End tour (outlined
 * red, asks first). Continue also resumes a paused tour (back to navigating = walking again).
 */
@Composable
fun TourRunningBar(
    distanceM: Double, elapsedS: Long, paused: Boolean, onEnd: () -> Unit, modifier: Modifier = Modifier,
    onPause: (Boolean) -> Unit = {}, onContinue: () -> Unit = {}, activity: com.onpath.app.routing.Activity? = null,
) {
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).warmShadow(OnPathShadow.LOW, MaterialTheme.shapes.large), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            TourControls(distanceM, elapsedS, paused, onPause, onContinue, onEnd, activity)
        }
    }
}

/** Recording state dot: orange while recording, grey while paused. */
@Composable
fun RecDot(paused: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(if (paused) OnPathColors.Grey else OnPathColors.Rec, CircleShape))
}

/** What the end sheet shows of the tour: distance, moving time, ascent (from the running recording). */
data class EndStats(val distanceM: Double, val elapsedS: Long, val ascentM: Double)

/** "Weiterwandern" on foot, "Weiterfahren" on any bike (owner 2026-11-05: the way back names what you do). */
internal fun keepGoingLabel(@Suppress("UNUSED_PARAMETER") activity: com.onpath.app.routing.Activity?): String =
    Txt.s(Res.string.nav_continue) // owner 2026-11-06: one word for going on after a pause / at the end – "Weiter"

/**
 * "Tour beenden?" (owner 2026-11-05: V2 – "am besten, mal sehen, ob der Klick nervt"): a sheet from the bottom in
 * sections – header (activity sign, title, activity, ✕); TOUR (green dot): distance · time · ascent and the big green
 * "Tour speichern" where the thumb is – nothing about the plan (owner 2026-11-06: the tour and the plan are two things;
 * a plan never saved was saved at "Los geht's", changes made on the way show "Nicht gespeichert ›" in Plan); then outlined "Weiterwandern" / "Weiterfahren" and the quiet red "Tour löschen"
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
    keepLabel: String = keepGoingLabel(activity),
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

/** The end sheet's inside: title, numbers, plan option, Save, Weiterfahren | Löschen, bin note. */
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


/** "Continue · Gravel bike" (owner rule: the way back to Navigate names the activity). */
internal fun continueLabel(activity: com.onpath.app.routing.Activity?): String =
    if (activity == null) Txt.s(Res.string.nav_continue) else Txt.s(Res.string.nav_continue_as, activity.navName)

/**
 * Status line + the three tour buttons: Continue (filled, full width, names the activity; also resumes), then Pause /
 * Resume (tonal) and End (outlined red, asks first) side by side. Used in the stand-alone tour card (over a shown track
 * or a place card); the plan sheet splits it into [TourStatusRow] and [TourPauseEnd].
 */
@Composable
fun TourControls(
    distanceM: Double, elapsedS: Long, paused: Boolean,
    onPause: (Boolean) -> Unit, onContinue: () -> Unit, onEnd: () -> Unit,
    activity: com.onpath.app.routing.Activity? = null,
    recordOnly: Boolean = false,
) {
    TourStatus(tourStatusText(distanceM, elapsedS, paused, recordOnly), paused)
    Spacer(Modifier.height(8.dp))
    Button(colors = ActionButtons.go(), onClick = { if (paused) onPause(false); onContinue() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(Icons.Filled.Navigation, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
        Text(continueLabel(activity), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(8.dp))
    TourPauseEnd(paused, onPause, onEnd)
}

/** "Tour running · 6.4 km · 1:23:45" (paused / "Recording only" after the guidance ended). */
@Composable
internal fun tourStatusText(distanceM: Double, elapsedS: Long, paused: Boolean, recordOnly: Boolean): String {
    val res = when { paused -> Res.string.nav_tour_paused; recordOnly -> Res.string.nav_tour_record_only; else -> Res.string.nav_tour_running }
    return str(res, Format.distance(distanceM), Format.clock(elapsedS))
}

/**
 * The orange (paused: grey) dot + the status; wraps rather than cuts at a large font (owner: numbers in full), and only
 * after a "·" – never between a number and its unit ("1,7 / km").
 */
@Composable
private fun TourStatus(status: String, paused: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.semantics { contentDescription = status }, verticalAlignment = Alignment.CenterVertically) {
        RecDot(paused)
        Spacer(Modifier.width(10.dp))
        Text(breakOnlyAtDots(status), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** "Tour running · 1.7 km · 0:14:38" with no-break spaces except after each "·" (the only places a line may break). */
internal fun breakOnlyAtDots(s: String): String = s.replace(' ', '\u00A0').replace("\u00A0·\u00A0", "\u00A0· ")

/**
 * The plan sheet's first step during a tour (owner 2026-10-31): ONE row – the status on the left, a compact green
 * [Continue] (≥ 48 dp, also resumes a paused tour) on the right. Pause / End tour come with the next step
 * ([TourPauseEnd]); TalkBack hears the activity on the button ("Continue · Hike"). At a large font, when the status
 * beside the button would be taller than status + button stacked, the button goes under the status (right-aligned).
 * [onEnd] (the row alone, field 2026-11-01 / UX B2): "Beenden" (red edge, owner 2026-11-01: a word, not a ■) left of
 * "Navigation weiter" opens the same "End this tour?" question as End tour – no drag needed to stop.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TourStatusRow(
    distanceM: Double, elapsedS: Long, paused: Boolean, onPause: (Boolean) -> Unit, onContinue: () -> Unit,
    activity: com.onpath.app.routing.Activity? = null, recordOnly: Boolean = false, onEnd: (() -> Unit)? = null,
) {
    val status = tourStatusText(distanceM, elapsedS, paused, recordOnly)
    val described = continueLabel(activity)
    val endLabel = str(Res.string.nav_end_tour)
    Layout(content = {
        TourStatus(status, paused)
        // the two buttons side by side; at a large font they wrap (never cut to "▲ N")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onEnd != null) {
                // the word, not a ■ (owner 2026-11-01: "kryptisch – was macht dieses Stopp?"): it ends the recording
                OutlinedButton(onClick = onEnd, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = endLabel },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error), contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(str(Res.string.nav_end_tour), maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
            }
            Button(colors = ActionButtons.go(), onClick = { if (paused) onPause(false); onContinue() },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = described },
                contentPadding = PaddingValues(start = 14.dp, end = 18.dp)) {
                Icon(Icons.Filled.Navigation, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(str(Res.string.nav_continue), maxLines = 1)
            }
        }
    }, modifier = Modifier.fillMaxWidth()) { ms, c ->
        val gap = 8.dp.roundToPx()
        val max = c.maxWidth
        val b = ms[1].measure(c.copy(minWidth = 0, minHeight = 0))
        val besideW = (max - b.width - gap).coerceAtLeast(0)
        val beside = TourRowFit.beside(ms[0].minIntrinsicHeight(besideW), ms[0].minIntrinsicHeight(max), b.height)
        val t = ms[0].measure(Constraints(maxWidth = if (beside) besideW else max))
        if (beside) {
            val h = maxOf(t.height, b.height)
            layout(max, h) {
                t.placeRelative(0, (h - t.height) / 2)
                b.placeRelative(max - b.width, (h - b.height) / 2)
            }
        } else layout(max, t.height + gap + b.height) {
            t.placeRelative(0, 0)
            b.placeRelative(max - b.width, t.height + gap)
        }
    }
}

/** Layout rules of the tour row and the Pause / End pair (pure, for tests). */
internal object TourRowFit {
    /** The status goes beside Continue unless that makes it taller than status (full width) + button stacked. */
    fun beside(statusBesideH: Int, statusFullH: Int, buttonH: Int): Boolean = statusBesideH <= maxOf(statusFullH, buttonH)

    /** Pause and End tour side by side when both labels fit their half; else one under the other – never cut. */
    fun sideBySide(pauseW: Int, endW: Int, width: Int, gap: Int): Boolean = maxOf(pauseW, endW) * 2 + gap <= width
}

/**
 * Pause / Resume (tonal) and End tour (outlined red, asks first) side by side, equal width; at a large font (a label
 * would be cut, e.g. „Tour beenden“) one under the other, full width.
 */
@Composable
fun TourPauseEnd(paused: Boolean, onPause: (Boolean) -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    Layout(content = {
        FilledTonalButton(colors = ActionButtons.goTonal(), onClick = { onPause(!paused) }, modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(str(if (paused) Res.string.common_resume else Res.string.nav_pause), maxLines = 1)
        }
        // Tour beenden is navigation: green, no red at the stop (owner 2026-11-05, onpath.private#27)
        OutlinedButton(onClick = onEnd, modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp), border = BorderStroke(1.5.dp, OnPathColors.ActionGo)) {
            Icon(Icons.Filled.Stop, null, Modifier.size(18.dp), tint = OnPathColors.ActionGo); Spacer(Modifier.width(6.dp))
            Text(str(Res.string.nav_end_tour), color = OnPathColors.ActionGo, maxLines = 1)
        }
    }, modifier = modifier.fillMaxWidth()) { ms, c ->
        val gap = 8.dp.roundToPx()
        val max = c.maxWidth
        val side = TourRowFit.sideBySide(ms[0].maxIntrinsicWidth(Constraints.Infinity), ms[1].maxIntrinsicWidth(Constraints.Infinity), max, gap)
        val w = if (side) (max - gap) / 2 else max
        val p = ms[0].measure(Constraints.fixedWidth(w))
        val e = ms[1].measure(Constraints.fixedWidth(w))
        if (side) {
            val h = maxOf(p.height, e.height)
            layout(max, h) { p.placeRelative(0, (h - p.height) / 2); e.placeRelative(max - e.width, (h - e.height) / 2) }
        } else layout(max, p.height + gap + e.height) { p.placeRelative(0, 0); e.placeRelative(0, p.height + gap) }
    }
}


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

/** "About 29 days on foot. Calculating and loading its maps takes long." */
internal fun longRouteText(seconds: Double, activity: com.onpath.app.routing.Activity): String {
    val how = if (activity == com.onpath.app.routing.Activity.HIKE) Res.string.nav_long_route_foot else Res.string.nav_long_route_bike
    return Txt.s(how, com.onpath.app.routing.LongRoute.describe(seconds)).replaceFirstChar { it.uppercase() }
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
        text = { DialogText(longRouteText(seconds, activity)) },
        confirmButton = { TextButton(colors = ActionButtons.editText(), onClick = onPlan) { Text(str(Res.string.nav_plan_anyway)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(str(Res.string.common_cancel)) } },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}
