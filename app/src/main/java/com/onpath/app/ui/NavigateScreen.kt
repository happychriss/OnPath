package com.onpath.app.ui

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.WrongLocation
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format
import com.onpath.app.data.FollowProgress
import com.onpath.app.recording.RecState
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.TurnDetector
import com.onpath.app.routing.TurnKind

/** What the Navigate screen shows (stateless, so screenshot tests can build it). */
data class NavUi(
    /** What is left of the followed line; null for free recording or without a fix. */
    val toGo: ToGo? = null,
    /** Recorded so far; null when no recording runs (e.g. an unfinished tour is being asked about). */
    val recordedM: Double? = null,
    val elapsedS: Long = 0,
    val paused: Boolean = false,
    val waitingGps: Boolean = false,
    /** Distance off the route while the off-route warning is active. */
    val offRouteM: Int? = null,
    /** Next turn on the followed line (≤ 500 m ahead, on route); null = no hint. */
    val turn: TurnHint? = null,
    /** Map orientation: walking direction up (true) or north up (false); drives the orientation button. */
    val headingUp: Boolean = true,
)

data class TurnHint(val kind: TurnKind, val distanceM: Double)

data class ToGo(val distanceM: Double, val ascentM: Double, val timeS: Double, val offM: Double?)

/**
 * Navigate mode, Google Maps style. Status on top (read-only): the next-turn card, what is left and the
 * recording state, then the off-route warning. Controls at the bottom right / bottom: orientation and locate
 * FABs, slide-to-plan. Ending the tour happens in Plan ("End tour") or on arrival – never by a stray touch here.
 * Map taps change nothing here (MapHost checks AppViewModel.canEditMap).
 */
@Composable
fun NavigateOverlay(
    ui: NavUi,
    footer: @Composable () -> Unit,
    onSlideToPlan: () -> Unit,
    onResume: () -> Unit = {},
    slideStartFraction: Float = 0f,
    onToggleOrientation: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ui.turn?.let { TurnCard(it) }
            if (ui.toGo != null || ui.recordedM != null) NavStatusCard(ui, onResume)
            ui.offRouteM?.let { OffRouteBanner(it) }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            OrientationButton(ui.headingUp, onToggleOrientation,
                Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 12.dp))
            footer()
            SlideToPlan(onSlideToPlan, Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                startFraction = slideStartFraction)
        }
    }
}

/** Next turn, the most prominent thing on screen (like Google Maps' green maneuver card): arrow, distance, direction. */
@Composable
private fun TurnCard(t: TurnHint) {
    val text = if (t.distanceM < 15) "${t.kind.label} now" else "${t.kind.label} in ${turnDistance(t.distanceM)}"
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary, shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = text }) {
        Row(Modifier.heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(turnIcon(t.kind), null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(turnDistance(t.distanceM), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(t.kind.label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
/** Rounded like a navigation display: 10 m steps below 200 m, 50 m steps above. */
internal fun turnDistance(m: Double): String = when {
    m < 15 -> "Now"
    m < 195 -> "${Math.round(m / 10) * 10} m"
    else -> "${Math.round(m / 50) * 50} m"
}

private fun turnIcon(k: TurnKind): ImageVector = when (k) {
    TurnKind.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft
    TurnKind.LEFT -> Icons.Filled.TurnLeft
    TurnKind.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    TurnKind.U_TURN_LEFT -> Icons.Filled.UTurnLeft
    TurnKind.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
    TurnKind.RIGHT -> Icons.Filled.TurnRight
    TurnKind.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    TurnKind.U_TURN_RIGHT -> Icons.Filled.UTurnRight
}

/**
 * Round map-direction button: navigation arrow = walking direction up, "N" compass needle = north up.
 * A tap switches and re-centres on the position.
 */
@Composable
internal fun OrientationButton(headingUp: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val what = if (headingUp) "Map turns with your walking direction. Tap for north up"
        else "Map is north up. Tap to turn it with your walking direction"
    MapFab(onClick, modifier.semantics { contentDescription = what }, contentColor = OnPathColors.Route) {
        if (headingUp) Icon(Icons.Filled.Navigation, null, modifier = Modifier.size(24.dp))
        else NorthNeedle(Modifier.size(34.dp))
    }
}

/** Compass needle: red half pointing north with an "N" above it, grey half south. */
@Composable
private fun NorthNeedle(modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w / 2; val top = h * 0.36f; val bottom = h * 0.98f; val mid = (top + bottom) / 2; val half = w * 0.16f
        drawPath(Path().apply { moveTo(cx, top); lineTo(cx + half, mid); lineTo(cx - half, mid); close() }, OnPathColors.Bad)
        drawPath(Path().apply { moveTo(cx, bottom); lineTo(cx + half, mid); lineTo(cx - half, mid); close() }, Color(0xFF9E9E9E))
        val label = measurer.measure("N", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333)))
        drawText(label, topLeft = Offset(cx - label.size.width / 2f, top - label.size.height + 1f))
    }
}

/**
 * Read-only status card: "7.4 km to go · ↑ 512 m · 2 h 54 min" and "● Recording · 5.3 km · 1:23:45".
 * The only button is "Resume" while paused (from the notification).
 */
@Composable
private fun NavStatusCard(ui: NavUi, onResume: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ui.toGo?.let { g ->
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${Format.distance(g.distanceM)} to go", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("  ·  ↑ ${Format.meters(g.ascentM)}  ·  ${Format.duration(g.timeS)}",
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val off = g.offM
                    if (off != null && off > 150) Text("${Format.distance(off)} from the route", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                ui.recordedM?.let { d ->
                    Row(Modifier.padding(top = if (ui.toGo != null) 2.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                        RecDot(ui.paused, Modifier.size(8.dp))
                        Spacer(Modifier.width(8.dp))
                        val status = when { ui.paused -> "Paused"; ui.waitingGps -> "Waiting for GPS"; else -> "Recording" }
                        Text("$status · ${Format.distance(d)} · ${Format.clock(ui.elapsedS)}",
                            style = if (ui.toGo == null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            if (ui.paused && ui.recordedM != null) TextButton(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) { Text("Resume") }
        }
    }
}

@Composable
private fun OffRouteBanner(offM: Int) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WrongLocation, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text("Off route · $offM m away", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Stateful wrapper: remaining distance along the followed line and the arrival check. */
@Composable
internal fun NavigateMode(vm: AppViewModel, rec: RecState?, location: Location?, footer: @Composable () -> Unit) {
    val line = vm.followLine
    val progress = remember(line) { line?.let { FollowProgress(it) } }
    // turns on the followed line, computed once per line (pure, TurnDetector)
    val turns = remember(line) { line?.let { TurnDetector.turns(it) } ?: emptyList() }
    val total = remember(line) { line?.let { com.onpath.app.data.Geo.length(it) } ?: 0.0 }
    val hint = remember(line) { intArrayOf(0) }
    val here = location?.let { GeoPoint(it.latitude, it.longitude) }
    val remaining = remember(progress, location) {
        if (progress == null || here == null) null else progress.remaining(here, hint[0])?.also { hint[0] = it.index }
    }
    LaunchedEffect(location) { if (here != null) vm.checkArrival(here, remaining?.distanceM) }
    val next = remember(turns, remaining, rec?.offRouteAlert) {
        nextTurnHint(turns, total, remaining?.distanceM, remaining?.offM, rec?.offRouteAlert == true)
    }
    LaunchedEffect(next?.first) { vm.navTurn = next?.first }
    DisposableEffect(Unit) { onDispose { vm.navTurn = null } }
    val ui = NavUi(
        toGo = remaining?.let { ToGo(it.distanceM, it.ascentM, FollowProgress.timeS(it, vm.activity), it.offM) },
        recordedM = rec?.distanceM, elapsedS = rec?.elapsedS ?: 0, paused = rec?.paused == true,
        waitingGps = rec?.gpsLost == true,
        offRouteM = rec?.offRouteM?.takeIf { rec.offRouteAlert }?.toInt(),
        turn = next?.let { (t, d) -> TurnHint(t.kind, d) },
        headingUp = vm.navHeadingUp,
    )
    NavigateOverlay(ui, footer, onSlideToPlan = vm::switchToPlan, onResume = { vm.pauseRecording(false) },
        onToggleOrientation = vm::toggleNavOrientation)
}

/** Off the line by more than this: no turn hint (the off-route chip / warning takes over). */
internal const val TURN_HIDE_OFF_M = 40.0

/** The next turn ahead and its distance, or null (no line, no fix, off route, or > 500 m away). */
internal fun nextTurnHint(
    turns: List<com.onpath.app.routing.Turn>, totalM: Double, remainingM: Double?, offM: Double?, offRouteAlert: Boolean,
): Pair<com.onpath.app.routing.Turn, Double>? {
    if (turns.isEmpty() || remainingM == null || offRouteAlert || (offM ?: 0.0) > TURN_HIDE_OFF_M) return null
    return TurnDetector.nextTurn(turns, (totalM - remainingM).coerceAtLeast(0.0))
}
