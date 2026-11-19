package com.onpath.app.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.OnPathShadow
import com.onpath.app.ui.OnPathShapes

import com.onpath.app.ui.warmShadow
import com.onpath.strings.*

/*
 * SKETCH (owner 2026-11-19, "one progress design, two forms"): pictures for the owner before anything is built.
 * Not wired anywhere – only the screenshot test ProgressSketch*Test draws these. Texts come in as plain strings until
 * the owner says yes; then they move to strings/ and the real states feed the data classes.
 */

/** Where a step of the wait window stands. */
enum class SketchStepState { DONE, NOW, LATER }

/**
 * One step under the bar: "✓ vorbereitet", "● lädt …", "○ einfügen" (or one leg of a route). [short]: the label at a
 * large font when [label] would not fit on one line ("lädt" for "lädt Karte + Routendaten").
 */
data class SketchStep(val label: String, val state: SketchStepState, val short: String? = null)

/** What the window waits for: the map (cloud) or a route (route sign). */
enum class SketchWaitKind { MAP, ROUTE }

/**
 * Form A ("ich muss warten"): one window over the map, always the same frame – title, ONE bar 0–100 over all steps with
 * its number (never back, never standing), the steps as a small list with ticks (the one in progress = what happens
 * now), the time left once it can be estimated, Cancel.
 */
data class WaitWindowSketch(
    val kind: SketchWaitKind,
    val title: String,
    /** 0..100 over all steps. */
    val percent: Int,
    val steps: List<SketchStep>,
    /** "noch etwa 20 Sekunden"; null = not estimated yet (no line). */
    val timeLeft: String?,
)

/**
 * Never more than 3 ticks (owner 2026-11-19, „nie mehr als 3 Häkchen"): with more steps (a route's legs + the check) a
 * window of 3 moves along – the last done, the one running, the next. Pure.
 */
object SketchSteps {
    const val MAX = 3

    fun shown(steps: List<SketchStep>): List<SketchStep> {
        if (steps.size <= MAX) return steps
        val now = steps.indexOfFirst { it.state != SketchStepState.DONE }.let { if (it < 0) steps.lastIndex else it }
        val from = (now - 1).coerceIn(0, steps.size - MAX)
        return steps.subList(from, from + MAX)
    }
}

/** At this font scale and above the time left gets its own line. */
const val WAIT_WINDOW_LARGE_FONT = 1.5f

@Composable
fun WaitWindowSketchView(ui: WaitWindowSketch, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = OnPathShapes.Card, color = OnPathColors.Paper,
        modifier = modifier.fillMaxWidth().warmShadow(OnPathShadow.HIGH, OnPathShapes.Card)) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 6.dp)) {
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ui.kind == SketchWaitKind.MAP) Icons.Outlined.CloudDownload else Icons.Outlined.Route, null,
                    tint = OnPathColors.Green, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(ui.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = OnPathColors.Ink)
            }
            Row(Modifier.padding(top = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { ui.percent / 100f }, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).clip(CircleShape))
                Text("${ui.percent} %", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = OnPathColors.Ink, softWrap = false, modifier = Modifier.padding(start = 12.dp))
            }
            Column(Modifier.padding(top = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SketchSteps.shown(ui.steps).forEach { StepLine(it) }
            }
            // time left beside Cancel; at a large font on its own line, Cancel under it (never squeezed)
            val cancel: @Composable () -> Unit = {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(str(Res.string.common_cancel), softWrap = false)
                }
            }
            if (LocalDensity.current.fontScale >= WAIT_WINDOW_LARGE_FONT) {
                ui.timeLeft?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = OnPathColors.InkMuted, modifier = Modifier.padding(top = 10.dp, end = 8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { cancel() }
            } else Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ui.timeLeft ?: "", style = MaterialTheme.typography.bodyMedium, color = OnPathColors.InkMuted,
                    modifier = Modifier.weight(1f))
                cancel()
            }
        }
    }
}

@Composable
private fun StepLine(step: SketchStep) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // the sign as tall as the text (grows with the phone's font size)
        val h = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.fontSize.toDp() } + 2.dp
        Box(Modifier.size(h), contentAlignment = Alignment.Center) {
            when (step.state) {
                SketchStepState.DONE -> Icon(Icons.Filled.Check, null, tint = OnPathColors.Green, modifier = Modifier.size(h))
                SketchStepState.NOW -> Box(Modifier.size(h * 0.6f).background(OnPathColors.Green, CircleShape))
                SketchStepState.LATER -> Box(Modifier.size(h * 0.6f).border(1.5.dp, OnPathColors.InkMuted.copy(alpha = 0.5f), CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        val large = LocalDensity.current.fontScale >= WAIT_WINDOW_LARGE_FONT
        Text(if (large) step.short ?: step.label else step.label, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (step.state == SketchStepState.NOW) FontWeight.SemiBold else FontWeight.Normal,
            color = when (step.state) {
                SketchStepState.NOW -> OnPathColors.Ink
                SketchStepState.DONE -> OnPathColors.InkMuted
                SketchStepState.LATER -> OnPathColors.InkMuted.copy(alpha = 0.75f)
            })
    }
}

/**
 * Form B ("ich kann weitermachen"): one small sign beside the map – a ring and its percent, nothing else (owner
 * 2026-11-19, „einfach nur Prozent"). The percent is ONE number over the whole action – every load together (all areas
 * of a multi-day tour = one 0–100). Tap → the details.
 */
data class BesideSignSketch(
    /** 0..100 over everything that loads; null = nothing measurable yet: the ring runs, no number. */
    val percent: Int?,
)

@Composable
fun BesideSignSketchView(ui: BesideSignSketch, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(shape = OnPathShapes.Pill, color = OnPathColors.TagContainer, contentColor = OnPathColors.TagInk,
            modifier = Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Pill)) {
            Row(Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                // the ring a little taller than the text (grows with the phone's font size)
                val ring = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.fontSize.toDp() } + 6.dp
                val track = OnPathColors.TagInk.copy(alpha = 0.18f)
                if (ui.percent != null) CircularProgressIndicator(progress = { ui.percent / 100f }, modifier = Modifier.size(ring),
                    color = OnPathColors.TagInk, trackColor = track, strokeWidth = 2.dp, gapSize = 0.dp)
                else CircularProgressIndicator(Modifier.size(ring), color = OnPathColors.TagInk, trackColor = track, strokeWidth = 2.dp)
                if (ui.percent != null) {
                    Spacer(Modifier.width(7.dp))
                    Text("${ui.percent} %", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** One load in the details: its name and where it stands (one of the three texts). */
data class BesideRowSketch(val name: String, val state: String)

/**
 * Form B's details (a bottom sheet's content): title, the ONE bar with the sign's percent over the whole action, then
 * what loads – one line each, no number of its own –, Cancel all · Close.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BesideDetailsSketchView(title: String, percent: Int, rows: List<BesideRowSketch>, onCancelAll: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = OnPathColors.Ink, modifier = Modifier.padding(end = 8.dp))
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = { percent / 100f }, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).clip(CircleShape))
            Text("$percent %", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = OnPathColors.Ink, softWrap = false, modifier = Modifier.padding(start = 12.dp))
        }
        Column(Modifier.padding(end = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { r ->
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.name, style = MaterialTheme.typography.bodyLarge, color = OnPathColors.Ink, modifier = Modifier.padding(end = 12.dp))
                    Text(r.state, style = MaterialTheme.typography.bodyMedium, color = OnPathColors.InkMuted, softWrap = false)
                }
            }
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancelAll, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(str(Res.string.common_cancel_all), color = OnPathColors.Bad, fontWeight = FontWeight.SemiBold, softWrap = false)
            }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_close), softWrap = false) }
        }
    }
}
