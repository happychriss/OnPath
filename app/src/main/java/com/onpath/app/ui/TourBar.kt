package com.onpath.app.ui

import com.onpath.strings.*

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Navigation
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
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            TourControls(distanceM, elapsedS, paused, onPause, onContinue, onEnd, activity)
        }
    }
}

/**
 * The recording sign on the activity icon (owner 2026-11-01: "ein kleines REC-Schild" instead of the small dot): a red
 * pill "● REC" – grey while paused.
 */
@Composable
fun RecBadge(paused: Boolean, modifier: Modifier = Modifier) {
    val bg = if (paused) OnPathColors.Grey else OnPathColors.Error
    Row(modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(OnPathColors.CardWhite, CircleShape))
        Spacer(Modifier.width(2.dp))
        Text(str(Res.string.nav_rec_badge), color = OnPathColors.CardWhite, style = MaterialTheme.typography.labelSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
    }
}

/** Recording state dot: orange while recording, grey while paused. */
@Composable
fun RecDot(paused: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(if (paused) OnPathColors.Grey else OnPathColors.Rec, CircleShape))
}

/**
 * "End tour" (owner): always Delete (apart, left) · Cancel · Save ([DeleteApartButtons]). Save stops and keeps the tour; Delete (red) asks once
 * more, then the tour goes to the recycle bin (30 days) – for a short walk nobody wants to keep.
 * [cancelLabel]: "Cancel" from the End button, "Keep going" on arrival.
 */
@Composable
fun EndTourDialog(
    onSave: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
    title: String = str(Res.string.nav_end_tour_title), cancelLabel: String = str(Res.string.common_cancel),
) {
    var askDelete by remember { mutableStateOf(false) }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(str(Res.string.nav_delete_track_title)) },
            text = { DialogText(RECYCLE_BIN_NOTE) },
            confirmButton = { TextButton(onClick = { askDelete = false; onDelete() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(str(Res.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(str(Res.string.common_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Flag, null) },
        title = { Text(title) },
        // no body: the buttons say it
        confirmButton = { DeleteApartButtons({ askDelete = true }, cancelLabel, onDismiss, str(Res.string.common_save), onSave) },
    )
}

/**
 * The buttons of the dialogs that end a tour (End tour, arrival, an interrupted recording, the ride check) – the
 * standard (owner 2026-11-01: "ich möchte immer auf Löschen drücken"): Delete red on the left, apart, never where the
 * thumb lands; on the right the way back and Save, the main action, filled. Delete still asks once more.
 */
@Composable
internal fun DeleteApartButtons(onDelete: (() -> Unit)?, cancelLabel: String, onCancel: () -> Unit, saveLabel: String, onSave: () -> Unit) {
    androidx.compose.ui.layout.Layout(content = {
        TextButton(onClick = onCancel) { Text(cancelLabel) }
        Button(onClick = onSave) { Text(saveLabel, maxLines = 1) }
        if (onDelete != null) TextButton(onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(str(Res.string.common_delete)) }
    }, modifier = Modifier.fillMaxWidth()) { ms, c ->
        // one row when all fit (Delete left, apart); else Cancel + Save right-aligned (each on its own row when even
        // that does not fit, a large font) and Delete under them on the left
        val loose = c.copy(minWidth = 0)
        val p = ms.map { it.measure(loose) }
        val w = c.maxWidth
        val gap = 8.dp.roundToPx()
        val cancel = p[0]; val save = p[1]; val del = p.getOrNull(2)
        val oneRow = p.sumOf { it.width } + gap * (p.size - 1) <= w
        val pairFits = cancel.width + gap + save.width <= w
        val rows = when {
            oneRow -> 1
            pairFits -> 1 + (if (del != null) 1 else 0)
            else -> 2 + (if (del != null) 1 else 0)
        }
        val rowH = p.maxOf { it.height }
        layout(w, rowH * rows + gap * (rows - 1)) {
            if (oneRow || pairFits) {
                save.placeRelative(w - save.width, (rowH - save.height) / 2)
                cancel.placeRelative(w - save.width - gap - cancel.width, (rowH - cancel.height) / 2)
                del?.placeRelative(0, if (oneRow) (rowH - del.height) / 2 else rowH + gap)
            } else {
                save.placeRelative(w - save.width, 0)
                cancel.placeRelative(w - cancel.width, rowH + gap)
                del?.placeRelative(0, 2 * (rowH + gap))
            }
        }
    }
}

/** Body of every "Delete …?" dialog that moves to the recycle bin. */
internal val RECYCLE_BIN_NOTE: String get() = Txt.s(Res.string.nav_recycle_bin_note, com.onpath.app.backup.RecycleBin.DAYS)

/** Shown once in Navigate when the destination is reached: the same three choices. */
@Composable
fun ArrivalDialog(onSave: () -> Unit, onDelete: () -> Unit, onKeepGoing: () -> Unit) =
    EndTourDialog(onSave, onDelete, onKeepGoing, title = str(Res.string.nav_arrived_title), cancelLabel = str(Res.string.nav_keep_going))


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
    Button(onClick = { if (paused) onPause(false); onContinue() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
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
 * [onEnd] (the row alone, field 2026-11-01 / UX B2): a round ■ (48 dp, red edge) left of Continue opens the same
 * "End this tour?" question as End tour – no drag needed to stop.
 */
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onEnd != null) {
                OutlinedIconButton(onClick = onEnd, modifier = Modifier.size(48.dp).semantics { contentDescription = endLabel },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = { if (paused) onPause(false); onContinue() },
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
        FilledTonalButton(onClick = { onPause(!paused) }, modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(str(if (paused) Res.string.common_resume else Res.string.nav_pause), maxLines = 1)
        }
        OutlinedButton(onClick = onEnd, modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
            Icon(Icons.Filled.Stop, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp))
            Text(str(Res.string.nav_end_tour), color = MaterialTheme.colorScheme.error, maxLines = 1)
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
        confirmButton = { TextButton(onClick = onYes) { Text(str(Res.string.nav_route_back)) } },
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
        confirmButton = { TextButton(onClick = onPlan) { Text(str(Res.string.nav_plan_anyway)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(str(Res.string.common_cancel)) } },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}
