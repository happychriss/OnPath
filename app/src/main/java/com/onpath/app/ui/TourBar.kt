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

/** What the end sheet shows of the tour: distance, moving time, ascent (from the running recording). */
data class EndStats(val distanceM: Double, val elapsedS: Long, val ascentM: Double)

/** "Weiterwandern" on foot, "Weiterfahren" on any bike (owner 2026-11-05: the way back names what you do). */
internal fun keepGoingLabel(activity: com.onpath.app.routing.Activity?): String =
    Txt.s(if (activity == com.onpath.app.routing.Activity.HIKE) Res.string.nav_keep_hiking else Res.string.nav_keep_riding)

/**
 * "Tour beenden?" (owner 2026-11-05: V2 – "am besten, mal sehen, ob der Klick nervt"): a sheet from the bottom in
 * sections – header (activity sign, title, activity, ✕); TOUR (green dot): distance · time · ascent and the big green
 * "Tour speichern" where the thumb is; PLAN (blue dot, only with a changed / new plan): a switch "Geänderten Plan auch
 * speichern" (off at first) + which plan; then outlined "Weiterwandern" / "Weiterfahren" and the quiet red "Tour löschen"
 * with the bin note – no second question (owner 2026-11-01: "ist ja sowieso alles im Papierkorb"). Large font: it scrolls.
 * [keepLabel]: the way back (arrival: the same activity word).
 */
@Composable
fun EndTourDialog(
    onSave: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
    title: String = str(Res.string.nav_end_tour_title),
    activity: com.onpath.app.routing.Activity? = null,
    stats: EndStats? = null,
    keepLabel: String = keepGoingLabel(activity),
    /** "Geänderten Plan auch speichern"; null = no plan to save (no PLAN section). */
    routeLabel: String? = null, routeChecked: Boolean = false, onRouteChecked: (Boolean) -> Unit = {},
    /** Which plan: "„Rund um den Kofel“ – mit deinen Änderungen von unterwegs" / "Wird als neuer Plan gespeichert". */
    routeLine: String? = null,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss)) {
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(top = 48.dp)
                .clickable(interactionSource = null, indication = null) { /* inside: no dismiss */ },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(width = 32.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activity != null) {
                            Icon(activity.icon, null, tint = OnPathColors.ActionGo, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.headlineSmall)
                            if (activity != null) Text(activity.navName, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = keepLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    EndSectionLabel(str(Res.string.nav_end_section_tour), OnPathColors.ActionGo)
                    Spacer(Modifier.height(8.dp))
                    if (stats != null) { EndStatsCard(stats); Spacer(Modifier.height(12.dp)) }
                    EndSaveButton(str(Res.string.nav_save_recording), onSave)
                    if (routeLabel != null) {
                        Spacer(Modifier.height(20.dp))
                        EndSectionLabel(str(Res.string.nav_end_section_plan), OnPathColors.PlanAccent)
                        Spacer(Modifier.height(8.dp))
                        EndPlanOption(routeLabel, routeLine, routeChecked, onRouteChecked)
                    }
                    Spacer(Modifier.height(20.dp))
                    EndKeepButton(keepLabel, onDismiss)
                    Spacer(Modifier.height(4.dp))
                    EndDeleteArea(onDelete)
                }
            }
        }
    }
}

@Composable
private fun EndSectionLabel(text: String, dot: androidx.compose.ui.graphics.Color) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).background(dot, CircleShape))
    Spacer(Modifier.width(8.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
}

/** Distance · time · ascent; at a large font one line each ("Strecke   12,5 km") instead of three squeezed columns. */
@Composable
private fun EndStatsCard(st: EndStats) {
    val items = listOf(str(Res.string.nav_end_stat_distance) to Format.distance(st.distanceM),
        str(Res.string.nav_end_stat_time) to Format.durationShort(st.elapsedS.toDouble()),
        str(Res.string.nav_end_stat_ascent) to "↑ ${Format.meters(st.ascentM)}")
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            items.forEach { (l, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(v, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        } else Row(Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) {
            items.forEach { (l, v) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(v, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** The plan option (blue = plan): the whole row toggles; a switch at the end. */
@Composable
private fun EndPlanOption(label: String, line: String?, checked: Boolean, onChecked: (Boolean) -> Unit) = Surface(
    color = OnPathColors.PlanTint, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, OnPathColors.PlanEdge),
    modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChecked)
        .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(checked, null, colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedTrackColor = OnPathColors.PlanAccent, uncheckedBorderColor = OnPathColors.PlanAccent, uncheckedThumbColor = OnPathColors.PlanAccent))
    }
}

/** The main action, filled green, full width, 56 dp. */
@Composable
private fun EndSaveButton(label: String, onClick: () -> Unit) = Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    colors = ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionGo)) {
    Icon(Icons.Filled.Check, null); Spacer(Modifier.width(8.dp))
    Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
}

/** The way back, outlined green, full width. */
@Composable
private fun EndKeepButton(label: String, onClick: () -> Unit) = OutlinedButton(onClick = onClick,
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), border = BorderStroke(1.dp, OnPathColors.ActionGo)) {
    Text(label, color = OnPathColors.ActionGo, style = MaterialTheme.typography.labelLarge, maxLines = 1)
}

/** Delete: always there, quiet, apart from Save – red only on the word; the bin note under it, no second question. */
@Composable
private fun EndDeleteArea(onDelete: () -> Unit) = Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp),
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EndSaveButton(saveLabel, onSave)
        EndKeepButton(cancelLabel, onCancel)
        if (onDelete != null) EndDeleteArea(onDelete)
    }
}

/** Body of every "Delete …?" dialog that moves to the recycle bin. */
internal val RECYCLE_BIN_NOTE: String get() = Txt.s(Res.string.nav_recycle_bin_note, com.onpath.app.backup.RecycleBin.DAYS)

/** Shown once in Navigate when the destination is reached: the same three choices. */
@Composable
fun ArrivalDialog(onSave: () -> Unit, onDelete: () -> Unit, onKeepGoing: () -> Unit,
                  routeLabel: String? = null, routeChecked: Boolean = false, onRouteChecked: (Boolean) -> Unit = {},
                  activity: com.onpath.app.routing.Activity? = null, stats: EndStats? = null, routeLine: String? = null) =
    EndTourDialog(onSave, onDelete, onKeepGoing, title = str(Res.string.nav_arrived_title), activity = activity, stats = stats,
        routeLabel = routeLabel, routeChecked = routeChecked, onRouteChecked = onRouteChecked, routeLine = routeLine)


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
