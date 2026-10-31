package com.onpath.app.ui

import com.onpath.strings.*

import android.location.Location
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.routing.JunctionCue
import kotlinx.coroutines.delay

/** What the rest view shows: the next turn / roundabout / destination, the distance to it, time left and arrival. */
data class RestUi(
    val event: RestEvent,
    val distanceM: Double,
    /** Time to go (s) of the whole route; null = not known. */
    val toGoS: Double? = null,
    /** Arrival clock "16:40"; null = none. */
    val arriveAt: String? = null,
)

/**
 * The rest view while cycling (owner 2026-10-30, Settings → Navigation → Screen while cycling: Smart): far from the next
 * turn the map gives way to a black screen (OLED: black pixels are off) with the next turn's sign, the distance to it,
 * time left and arrival – dim grey, readable at a glance. A tap anywhere brings the full map ([SmartScreen.TAP_MS]).
 * The screen stays on (a tap must work); the map below draws nothing meanwhile (MapController.setQuiet).
 */
@Composable
fun RestView(ui: RestUi, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val label = restLabel(ui.event)
    val distance = restDistance(ui.distanceM)
    val tapLabel = str(Res.string.nav_rest_tap)
    val description = str(Res.string.nav_rest_cd, label, distance)
    Box(modifier.fillMaxSize().background(OnPathColors.RestBackground)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)
        .clearAndSetSemantics { contentDescription = description; onClick(tapLabel) { onTap(); true } }
        .statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            CompositionLocalProvider(LocalContentColor provides OnPathColors.RestInk) {
                RestSign(ui.event, Modifier.size(150.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(distance, style = MaterialTheme.typography.headlineMedium, color = OnPathColors.RestInk, maxLines = 1)
            Text(label, style = MaterialTheme.typography.titleLarge, color = OnPathColors.RestDim, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val time = ui.toGoS?.let { Format.durationShort(it) }
            val arrive = ui.arriveAt?.let { str(Res.string.nav_arrive) + " " + it }
            if (time != null || arrive != null) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                time?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = OnPathColors.RestDim, maxLines = 1) }
                arrive?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = OnPathColors.RestDim, maxLines = 1) }
            }
            Text(tapLabel, style = MaterialTheme.typography.bodyMedium, color = OnPathColors.RestDim)
        }
    }
}

/** The turn card's sign, large: roundabout drawing, turn arrow, or a flag for the destination (colour from LocalContentColor). */
@Composable
private fun RestSign(e: RestEvent, modifier: Modifier) = when {
    e.roundabout != null -> RoundaboutSign(e.roundabout, e.angle, modifier)
    e.kind != null -> Icon(turnIcon(e.kind), null, modifier = modifier)
    else -> Icon(Icons.Filled.Flag, null, modifier = modifier)
}

/** "Right", "Roundabout · exit 2", "Destination". */
internal fun restLabel(e: RestEvent): String = when {
    e.roundabout != null -> JunctionCue.ROUNDABOUT.text(e.roundabout)
    e.kind != null -> e.kind.label
    else -> Txt.s(Res.string.nav_rest_destination)
}

/** Like the turn card below 1 km ("450 m"), then "2.4 km". */
internal fun restDistance(m: Double): String = if (m < 995) turnDistance(m) else Format.distance(m)

/**
 * Last finger-down anywhere on the app's screen (MainActivity.dispatchTouchEvent; uptime ms): any touch keeps the full
 * map for [SmartScreen.TAP_MS] – a tap on the rest view, a pan, a button.
 */
object ScreenTouch {
    var lastMs by mutableLongStateOf(0L)
        private set

    fun note() { lastMs = SystemClock.uptimeMillis() }
}

/**
 * Something needs the user (owner: then the full map) – a dialog, sheet or the error box over Navigate. The
 * navigation states (off route, arrival question, GPS) are added by NavigateMode.
 */
internal fun AppViewModel.restBlocked(): Boolean = arrivalAsk || endAsk != null || routeBackAsk || longRouteAsk != null ||
    navSettingsOpen || selectedPoi != null || activityCheck != null || downloadDetailsOpen || errorCard != null ||
    askUpdateDuringTour || unfinished != null || finishedTour != null || setupOpen || showSettings || routesOpen ||
    timeDetails != null

/**
 * The rest view decision for this Navigate screen ([SmartScreen], re-checked on every input change and when a hold
 * ends). [on] = allowed now (ScreenPolicy.rest and a tour follows a line); [fix] = the latest position (its speed).
 */
@Composable
internal fun rememberRest(on: Boolean, attention: Boolean, target: RestTarget?, fix: Location?): Boolean {
    val gate = remember { SmartScreen() }
    LaunchedEffect(fix) {
        gate.noteSpeed(fix?.takeIf { it.hasSpeed() }?.speed?.toDouble(), SystemClock.uptimeMillis())
    }
    val input = SmartScreen.In(on, attention, target?.key, target?.distanceM, if (on) ScreenTouch.lastMs else 0L)
    var rest by remember { mutableStateOf(false) }
    LaunchedEffect(input) {
        while (true) {
            val now = SystemClock.uptimeMillis()
            rest = gate.rest(input, now)
            delay(gate.recheckInMs(now).coerceIn(50L, 1_000L))
        }
    }
    return rest && on
}

/** The rest view over Navigate: fades in calmly, away quickly (the map is ready underneath). */
@Composable
internal fun RestLayer(ui: RestUi?, onTap: () -> Unit) {
    var last by remember { mutableStateOf(ui) }
    if (ui != null) last = ui
    AnimatedVisibility(visible = ui != null, enter = fadeIn(tween(400)), exit = fadeOut(tween(120))) {
        last?.let { RestView(it, onTap) }
    }
}
