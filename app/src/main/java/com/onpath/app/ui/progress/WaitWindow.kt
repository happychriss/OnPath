package com.onpath.app.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.domain.WaitStep
import com.onpath.app.domain.WaitStepState
import com.onpath.app.domain.WaitSteps
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.OnPathShadow
import com.onpath.app.ui.OnPathShapes
import com.onpath.app.ui.warmShadow

/** At this font scale and above the line gets its own row and the step rows take their short labels. */
const val WAIT_WINDOW_LARGE_FONT = 1.5f

/** The window's content (also drawn by the screenshot tests, which cannot show a Dialog window). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaitWindowView(ui: WaitWindowUi, buttons: List<WaitButton>, modifier: Modifier = Modifier) {
    val large = LocalDensity.current.fontScale >= WAIT_WINDOW_LARGE_FONT
    Surface(shape = OnPathShapes.Card, color = OnPathColors.Paper,
        modifier = modifier.fillMaxWidth().warmShadow(OnPathShadow.HIGH, OnPathShapes.Card)) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 6.dp)) {
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = when (ui.sign) {
                    WaitSign.MAP -> Icons.Outlined.CloudDownload to OnPathColors.Green
                    WaitSign.ROUTE -> Icons.Outlined.Route to OnPathColors.Green
                    WaitSign.OFFLINE -> Icons.Outlined.CloudOff to OnPathColors.InkMuted
                    WaitSign.FAILED -> Icons.Outlined.Warning to OnPathColors.Warn
                }
                Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(ui.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = OnPathColors.Ink)
            }
            if (ui.bar) Row(Modifier.padding(top = 14.dp, end = 8.dp).semantics(mergeDescendants = true) {
                contentDescription = "${ui.percent} %"
            }, verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { ui.percent / 100f },
                    color = if (ui.paused) OnPathColors.InkMuted else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).clip(CircleShape))
                Text("${ui.percent} %", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = OnPathColors.Ink, softWrap = false, modifier = Modifier.padding(start = 12.dp))
            }
            if (ui.steps.isNotEmpty()) Column(Modifier.padding(top = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                WaitSteps.shown(ui.steps).forEach { StepRow(it, large) }
            }
            // one button: the line beside it; at a large font or with more buttons the line on its own row and the
            // buttons right-aligned under it, wrapping (never squeezed, never a word broken)
            val btns: @Composable () -> Unit = {
                buttons.forEach { b ->
                    // a long verb ("Jetzt über mobile Daten laden") wraps at a large font rather than being cut
                    TextButton(onClick = b.onClick, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(b.label, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }
            if (large || buttons.size > 1) {
                ui.line?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = OnPathColors.InkMuted,
                        modifier = Modifier.padding(top = 10.dp, end = 8.dp))
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) { btns() }
            } else Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ui.line ?: "", style = MaterialTheme.typography.bodyMedium, color = OnPathColors.InkMuted,
                    modifier = Modifier.weight(1f))
                btns()
            }
        }
    }
}

@Composable
private fun StepRow(step: WaitStep, large: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // the sign as tall as the text (grows with the phone's font size)
        val h = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.fontSize.toDp() } + 2.dp
        Box(Modifier.size(h), contentAlignment = Alignment.Center) {
            when (step.state) {
                WaitStepState.DONE -> Icon(Icons.Filled.Check, null, tint = OnPathColors.Green, modifier = Modifier.size(h))
                WaitStepState.NOW -> Box(Modifier.size(h * 0.6f).background(OnPathColors.Green, CircleShape))
                WaitStepState.LATER -> Box(Modifier.size(h * 0.6f).border(1.5.dp, OnPathColors.InkMuted.copy(alpha = 0.5f), CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(if (large) step.short ?: step.label else step.label, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (step.state == WaitStepState.NOW) FontWeight.SemiBold else FontWeight.Normal,
            color = when (step.state) {
                WaitStepState.NOW -> OnPathColors.Ink
                WaitStepState.DONE -> OnPathColors.InkMuted
                WaitStepState.LATER -> OnPathColors.InkMuted.copy(alpha = 0.75f)
            })
    }
}

/** The window over the map (a Dialog: back and a tap outside do nothing – Cancel is the way out). */
@Composable
fun WaitWindowDialog(ui: WaitWindowUi, buttons: List<WaitButton>) {
    androidx.compose.ui.window.Dialog(onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        WaitWindowView(ui, buttons)
    }
}
