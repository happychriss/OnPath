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

/** One step under the bar: "✓ vorbereitet", "● lädt …", "○ einfügen" (or one leg of a route). */
data class SketchStep(val label: String, val state: SketchStepState)

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
                ui.steps.forEach { StepLine(it) }
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
        Text(step.label, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (step.state == SketchStepState.NOW) FontWeight.SemiBold else FontWeight.Normal,
            color = when (step.state) {
                SketchStepState.NOW -> OnPathColors.Ink
                SketchStepState.DONE -> OnPathColors.InkMuted
                SketchStepState.LATER -> OnPathColors.InkMuted.copy(alpha = 0.75f)
            })
    }
}

/**
 * Form B ("ich kann weitermachen"): one small sign beside the map – a ring (with its % while bytes flow, else running)
 * and one of the three texts; several loads = the same sign with a count on the ring. Tap → the details.
 */
data class BesideSignSketch(
    /** "lädt 34 %", "wird vorbereitet", "fast fertig". */
    val text: String,
    /** null = no measurable progress: the ring runs. */
    val percent: Int?,
    /** How many loads run; 1 = no count. */
    val count: Int = 1,
)

@Composable
fun BesideSignSketchView(ui: BesideSignSketch, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(shape = OnPathShapes.Pill, color = OnPathColors.TagContainer, contentColor = OnPathColors.TagInk,
            modifier = Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Pill)) {
            Row(Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                // the ring a little taller than the text (grows with the phone's font size); several loads: their count inside
                val ring = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.fontSize.toDp() } + 8.dp
                val track = OnPathColors.TagInk.copy(alpha = 0.18f)
                Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                    if (ui.percent != null) CircularProgressIndicator(progress = { ui.percent / 100f }, modifier = Modifier.size(ring),
                        color = OnPathColors.TagInk, trackColor = track, strokeWidth = 2.dp, gapSize = 0.dp)
                    else CircularProgressIndicator(Modifier.size(ring), color = OnPathColors.TagInk, trackColor = track, strokeWidth = 2.dp)
                    if (ui.count > 1) Text("${ui.count}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        color = OnPathColors.TagInk, softWrap = false)
                }
                Spacer(Modifier.width(7.dp))
                Text(ui.text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** One load in the details: its name, one of the three texts, a bar only while bytes flow. */
data class BesideRowSketch(val name: String, val state: String, val percent: Int?)

/** Form B's details (a bottom sheet's content): title, one row per load with its own bar, Cancel all · Close. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BesideDetailsSketchView(title: String, rows: List<BesideRowSketch>, onCancelAll: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = OnPathColors.Ink, modifier = Modifier.padding(end = 8.dp))
        rows.forEach { r ->
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.name, style = MaterialTheme.typography.bodyLarge, color = OnPathColors.Ink, modifier = Modifier.padding(end = 12.dp))
                    Text(r.state, style = MaterialTheme.typography.bodyMedium, color = OnPathColors.InkMuted, softWrap = false)
                }
                // a bar only while bytes flow; "wird vorbereitet" / "fast fertig" say it in words (an empty bar would look stuck)
                if (r.percent != null) LinearProgressIndicator(progress = { r.percent / 100f }, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clip(CircleShape))
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
