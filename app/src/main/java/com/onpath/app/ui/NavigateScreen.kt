package com.onpath.app.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format
import com.onpath.app.data.FollowProgress
import com.onpath.app.navigation.NavPrompt
import com.onpath.app.navigation.NavText
import com.onpath.app.navigation.RouteHint
import com.onpath.app.recording.RecState
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.rotate
import com.onpath.app.routing.Activity
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
    /** Off route (confirmed): the question, "finding a way back…" or the arrow banner; null = on route. */
    val offRoute: OffRouteUi? = null,
    /** Next turn on the followed line (≤ 500 m ahead, on route); null = no hint. */
    val turn: TurnHint? = null,
    /** Map orientation: walking direction up (true) or north up (false); drives the orientation button. */
    val headingUp: Boolean = true,
    /** Arrival clock "16:40" (to go + break allowance), shown large on the right; null = none. */
    val arriveAt: String? = null,
    /** Recording time without breaks / breaks so far (s); null = not known (shows the elapsed clock instead). */
    val movingS: Double? = null,
    val breakS: Double? = null,
    /** "Your pace on this trip: up 0.9× · down 1.1×" in the opened card; null = nothing learned yet. */
    val paceNote: String? = null,
    /** What is navigated / recorded as (icon first in the status card, tap = check-and-switch sheet); null = not shown. */
    val activity: Activity? = null,
    val sac: Int = 3,
    /** The check-and-switch sheet is open (stays until a choice is made). */
    val activityCheck: ActivityCheckUi? = null,
    /** "46.54120 N 10.90320 E · ±8 m · 1234 m" (emergency calls) in the opened card; null = no fix yet. */
    val position: String? = null,
    /** "46.54120, 10.90320" – copied when the position line is tapped. */
    val positionCopy: String? = null,
    /** Age of the last fix (s) – shown with "Waiting for GPS" so an old position is not taken for the current one. */
    val fixAgeS: Long? = null,
)

data class TurnHint(val kind: TurnKind, val distanceM: Double)

/**
 * Off-route state for the screen. [arrowDeg] = direction to the route relative to the screen's "up" (map bearing
 * already taken off), null without a direction. Nothing here times out: it stays until answered or back on route.
 */
data class OffRouteUi(val prompt: NavPrompt, val toRoute: RouteHint? = null, val arrowDeg: Float? = null)

data class ToGo(
    val distanceM: Double, val ascentM: Double, val timeS: Double, val offM: Double?,
    val descentM: Double = 0.0,
    /** The rest of the followed line from the current position (for the elevation profile). */
    val profile: List<GeoPoint> = emptyList(),
    /**
     * Can the time be trusted now? NOT_ON_ROUTE: "— not on route" instead of a time; PAUSED: the last time greyed with
     * "estimate paused · [pausedBecause]" (owner rules 6 and 8, docs/plan-time-estimate.md).
     */
    val state: com.onpath.app.time.EstimateState = com.onpath.app.time.EstimateState.OK,
    val pausedBecause: String? = null,
    /** Where the time counts to: the last stop's name under the arrival clock; null = the (unnamed) destination. */
    val target: String? = null,
)

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
    onTakeRoute: (Int) -> Unit = {},
    onKeepRoute: (Int) -> Unit = {},
    onActivity: () -> Unit = {},
    onChooseActivity: (Activity) -> Unit = {},
    onKeepActivity: () -> Unit = {},
    /** Finger on the elevation profile: that spot on the route (null never sent) / finger left. */
    onProfileScrub: (GeoPoint) -> Unit = {},
    onProfileRelease: () -> Unit = {},
    /** A background download runs (after 10 s): small "⬇ 40 %" chip under the status; tap → details. */
    download: BackgroundDownloadUi? = null,
    onDownload: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // cards glide in and out instead of popping and pushing the status card (polish audit A3)
            Appearing(ui.turn) { TurnCard(it) }
            // status (finding a way back / no way / kept) on top: readable at a glance
            Appearing(ui.offRoute?.takeIf { it.prompt !is NavPrompt.Question }) { OffRouteBanner(it) }
            if (ui.toGo != null || ui.recordedM != null) {
                var profileOpen by rememberSaveable { mutableStateOf(false) }
                NavStatusCard(ui, onResume, profileOpen, onToggleProfile = { profileOpen = !profileOpen }, onActivity = onActivity,
                    onProfileScrub = onProfileScrub, onProfileRelease = onProfileRelease)
            }
            download?.let { BackgroundDownloadChip(it, onDownload, Modifier.align(Alignment.End)) }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            // the question sits low, in thumb reach (one hand, gloves), above the map controls – never under them
            Appearing(ui.offRoute?.prompt as? NavPrompt.Question) { q ->
                RerouteQuestionCard(q, onTake = { onTakeRoute(q.id) }, onKeep = { onKeepRoute(q.id) },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            }
            footer() // map controls (compass/orientation, eye, locate) are grouped in the footer
            SlideToPlan(onSlideToPlan, Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                startFraction = slideStartFraction)
        }
        // over the controls: nothing else is touched by accident while choosing; no tap-outside dismiss
        ui.activityCheck?.let { ActivitySwitchSheet(it, onChooseActivity, onKeepActivity, Modifier.align(Alignment.BottomCenter)) }
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

/** Compass needle: red half pointing north with an "N" above it, grey half south. */
@Composable
internal fun NorthNeedle(modifier: Modifier) {
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
 * Status card: activity icon (48 dp button → check-and-switch sheet), "2 h 50 min · 7.4 km" (to go), "↑ 512 m ↓ 310 m",
 * "arrive ≈ 16:40" large on the right (to go + break allowance) and "● moving 1:12 · breaks 0:11" (moving time
 * without breaks; docs/plan-time-estimate.md §5). Buttons: the icon, "Resume" while paused, the profile chevron (48 dp);
 * the rest of the card is not clickable.
 */
@Composable
internal fun NavStatusCard(ui: NavUi, onResume: () -> Unit, profileOpen: Boolean = false, onToggleProfile: () -> Unit = {},
                           onActivity: () -> Unit = {}, onProfileScrub: (GeoPoint) -> Unit = {},
                           onProfileRelease: () -> Unit = {}) {
    val hasProfile = (ui.toGo?.profile?.size ?: 0) >= 2
    val canOpen = hasProfile || ui.position != null
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp,
        // only the activity icon and the chevron are buttons (gloves: no tap target as big as the card)
        modifier = Modifier.fillMaxWidth()) {
      Column {
        Row(Modifier.heightIn(min = 56.dp).padding(start = if (ui.activity != null) 8.dp else 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // the activity first (owner: a wrong activity is a disaster); its own 48 dp target opens the check sheet
            ui.activity?.let { a ->
                Surface(onClick = onActivity, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = "Activity: ${NavText.activityName(a, ui.sac)}. Tap to check or switch"
                    }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(a.icon, null, tint = OnPathColors.Green, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                ui.toGo?.let { g ->
                    if (g.state == com.onpath.app.time.EstimateState.NOT_ON_ROUTE) {
                        // never a stale time (owner rule 6): what is left counts again once back on the route
                        Text("— not on route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        val off = g.offM
                        if (off != null && off > 0) Text("${Format.distance(off)} from the route", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    } else {
                        val paused = g.state == com.onpath.app.time.EstimateState.PAUSED
                        // the time first: with the activity icon and the arrival clock beside it, an ellipsis cuts the distance, never the time
                        Text("${Format.duration(g.timeS)} · ${Format.distance(g.distanceM)}", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else Color.Unspecified)
                        if (paused) Text("estimate paused · ${g.pausedBecause ?: "no position"}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Text("↑ ${Format.meters(g.ascentM)}  ↓ ${Format.meters(g.descentM)}",
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val off = g.offM
                        if (off != null && off > 150 && ui.offRoute == null) Text("${Format.distance(off)} from the route", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                ui.recordedM?.let { d ->
                    Row(Modifier.padding(top = if (ui.toGo != null) 2.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                        RecDot(ui.paused, Modifier.size(8.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(recordingLine(ui, d),
                            style = if (ui.toGo == null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            if (ui.paused && ui.recordedM != null) TextButton(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) { Text("Resume") }
            else if (ui.toGo != null && ui.arriveAt != null && ui.toGo.state != com.onpath.app.time.EstimateState.NOT_ON_ROUTE) Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp).width(IntrinsicSize.Max).semantics(mergeDescendants = true) {}) {
                val paused = ui.toGo.state == com.onpath.app.time.EstimateState.PAUSED
                // "arrive at <target> ≈ 16:40": the clock large, the target small under it, no wider than the clock
                // (the time and the moving line on the left keep their room)
                Text("arrive ≈", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(ui.arriveAt, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1,
                    color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else Color.Unspecified)
                ui.toGo.target?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.then(NoIntrinsicWidth).fillMaxWidth())
                }
            }
            if (canOpen) androidx.compose.material3.IconButton(onClick = onToggleProfile, modifier = Modifier.size(48.dp)) {
                Icon(if (profileOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (profileOpen) "Hide elevation profile" else "Show elevation profile",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Chevron: elevation profile of what is left + own position (stays open until tapped again).
        if (profileOpen && canOpen) {
            if (hasProfile) ElevationProfile(ui.toGo!!.profile, height = 84.dp, gradient = true,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                onHighlight = { p -> p?.let(onProfileScrub) }, onRelease = onProfileRelease)
            ui.paceNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
            }
            ui.position?.let { PositionLine(it, ui.positionCopy) }
        }
      }
    }
}

/** The child does not widen an IntrinsicSize column (the target under the arrival clock is cut, not the clock's room). */
private object NoIntrinsicWidth : androidx.compose.ui.layout.LayoutModifier {
    override fun androidx.compose.ui.layout.MeasureScope.measure(
        measurable: androidx.compose.ui.layout.Measurable, constraints: androidx.compose.ui.unit.Constraints,
    ): androidx.compose.ui.layout.MeasureResult {
        val p = measurable.measure(constraints)
        return layout(p.width, p.height) { p.place(0, 0) }
    }
    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicWidth(
        measurable: androidx.compose.ui.layout.IntrinsicMeasurable, height: Int) = 0
    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicWidth(
        measurable: androidx.compose.ui.layout.IntrinsicMeasurable, height: Int) = 0
}

/**
 * Own position for an emergency call; tap copies "lat, lon" (paste into a message or any map app). The confirmation
 * "copied ✓" stays in the line (owner rule: no self-dismissing toast) while the screen is shown.
 */
@Composable
private fun PositionLine(text: String, copy: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(enabled = copy != null, onClickLabel = "Copy position") {
            try {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Position", copy))
                copied = true
            } catch (e: Exception) {
                com.onpath.app.debug.Problems.warn("ui", "Position could not be copied", e)
            }
        }
        .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.MyLocation, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (copied) "$text · copied ✓" else text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * "moving 1:12 · breaks 0:11" while navigating (the card is narrow: the recorded distance gives way, the state word only
 * when it matters: paused, no GPS); a free recording: "Recording · 2.1 km · moving 0:28 · breaks 0:05".
 * Without moving time: the elapsed clock as before.
 */
internal fun recordingLine(ui: NavUi, distanceM: Double): String {
    val age = ui.fixAgeS?.takeIf { it >= 60 }?.let { " · last fix ${it / 60} min ago" }.orEmpty()
    val status = when { ui.paused -> "Paused"; ui.waitingGps -> "Waiting for GPS$age"; else -> "Recording" }
    val moving = ui.movingS ?: return "$status · ${Format.distance(distanceM)} · ${Format.clock(ui.elapsedS)}"
    val parts = mutableListOf<String>()
    if (ui.toGo == null || ui.paused || ui.waitingGps) parts += status
    if (ui.toGo == null) parts += Format.distance(distanceM)
    parts += "moving ${com.onpath.app.time.TimeEstimate.hm(moving)}"
    val b = ui.breakS ?: 0.0
    if (b >= 60) parts += "breaks ${com.onpath.app.time.TimeEstimate.hm(b)}"
    return parts.joinToString(" · ")
}

/** "Off route – finding a way back…" (spinner) / "– no way back found" / "– original route kept" with an arrow to the route. */
@Composable
private fun OffRouteBanner(o: OffRouteUi) {
    val title = NavText.title(o.prompt)
    val hint = NavText.toRoute(o.toRoute)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer, shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = listOfNotNull(title, hint).joinToString(". ") }) {
        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                when {
                    o.prompt == NavPrompt.Searching -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    o.arrowDeg != null -> Icon(Icons.Filled.Navigation, null, modifier = Modifier.size(36.dp).rotate(o.arrowDeg))
                    else -> Icon(Icons.Outlined.WrongLocation, null, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/**
 * The off-route question (owner: ask every time, never re-route silently): stays until one of the two big buttons is
 * pressed or the user is back on the route. Buttons 56 dp high, full half width each – fine with gloves.
 */
@Composable
internal fun RerouteQuestionCard(q: NavPrompt.Question, onTake: () -> Unit, onKeep: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(2.dp, OnPathColors.Route), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(q.switch?.activity?.icon ?: Icons.Outlined.AltRoute, null, tint = OnPathColors.Route, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(NavText.title(q), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(NavText.question(q), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onKeep, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(NavText.KEEP, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                Button(onClick = onTake, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OnPathColors.Route)) {
                    Text(NavText.TAKE, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Stateful wrapper: remaining distance along the followed line and the arrival check. While the service navigates,
 * its progress is the one truth (the same as the time to go: direction-aware, "not on route" when off it); without
 * it (no tour yet) the nearest point of the line.
 */
@Composable
internal fun NavigateMode(vm: AppViewModel, rec: RecState?, location: Location?, mapBearing: Float, footer: @Composable () -> Unit) {
    val nav = rec?.nav?.takeIf { it.line.size >= 2 }
    val line = nav?.line ?: vm.followLine
    val progress = remember(line) { line?.let { FollowProgress(it) } }
    // turns on the followed line, computed once per line (pure, TurnDetector)
    val turns = remember(line) { line?.let { TurnDetector.turns(it) } ?: emptyList() }
    val total = remember(line) { line?.let { com.onpath.app.data.Geo.length(it) } ?: 0.0 }
    // same time model as the plan sheet (time/RouteTimes): no jump when navigation starts
    val times = remember(line, vm.activity) { line?.let { com.onpath.app.time.RouteTimes.of(it, vm.activity) } }
    val hint = remember(line) { intArrayOf(0) }
    val here = location?.let { GeoPoint(it.latitude, it.longitude) }
    val remaining = remember(progress, location, nav?.shownAlongM, nav?.offM) {
        when {
            progress == null -> null
            nav != null -> nav.shownAlongM?.let { progress.at(it, nav.offM) }
            here == null -> null
            else -> progress.remaining(here, hint[0])?.also { hint[0] = it.index }
        }
    }
    LaunchedEffect(location) { if (here != null) vm.checkArrival(here, remaining?.distanceM) }
    val next = remember(turns, remaining, rec?.offRouteAlert) {
        nextTurnHint(turns, total, remaining?.distanceM, remaining?.offM, rec?.offRouteAlert == true)
    }
    LaunchedEffect(next?.first) { vm.navTurn = next?.first }
    DisposableEffect(Unit) { onDispose { vm.navTurn = null } }
    val tt = rec?.time
    // the service's calm, learned value while it knows the line; else the plain model from here
    val toGoS = remaining?.let { r -> tt?.toGoS ?: toGoSeconds(times, r.distanceM, vm.planFactors()) }
    val arrive = toGoS?.let { s ->
        tt?.arrivalMs?.takeIf { tt.toGoS != null } ?: com.onpath.app.time.TimeEstimate.arrivalMs(System.currentTimeMillis(), s)
    }?.let { com.onpath.app.time.TimeEstimate.clock(it) }
    // the named last stop; an unnamed one is just "the destination" (no extra line: the card has no room to spare)
    val target = nav?.session?.stops?.lastOrNull()?.name
    val notOnRoute = nav != null && (remaining == null || tt?.state == com.onpath.app.time.EstimateState.NOT_ON_ROUTE)
    val ui = NavUi(
        toGo = if (notOnRoute) ToGo(0.0, 0.0, 0.0, nav?.offM, state = com.onpath.app.time.EstimateState.NOT_ON_ROUTE, target = target)
        else remaining?.let { r ->
            ToGo(r.distanceM, r.ascentM, toGoS ?: 0.0, r.offM, r.descentM,
                profile = line?.let { l -> l.subList(r.index.coerceIn(0, l.size - 1), l.size) } ?: emptyList(),
                state = tt?.state ?: com.onpath.app.time.EstimateState.OK, pausedBecause = tt?.pausedBecause?.text, target = target)
        },
        recordedM = rec?.distanceM, elapsedS = rec?.elapsedS ?: 0, paused = rec?.paused == true,
        waitingGps = rec?.gpsLost == true,
        offRoute = rec?.nav?.let { n ->
            n.prompt?.let { p -> OffRouteUi(p, n.toRoute, n.toRoute?.let { (it.bearingDeg - mapBearing).toFloat() }) }
        },
        turn = next?.let { (t, d) -> TurnHint(t.kind, d) },
        headingUp = vm.navHeadingUp,
        arriveAt = arrive,
        movingS = tt?.movingS, breakS = tt?.breakS,
        paceNote = tt?.learned?.let { paceNote(it) },
        // what the service navigates is the truth; free recording: the app's activity
        activity = rec?.nav?.session?.activity ?: vm.activity,
        sac = rec?.nav?.session?.sac ?: vm.sac,
        activityCheck = vm.activityCheck,
        position = location?.let { l ->
            NavText.position(l.latitude, l.longitude, l.accuracy.takeIf { l.hasAccuracy() }, l.altitude.takeIf { l.hasAltitude() },
                com.onpath.app.recording.LocationTracker.ageSeconds(l))
        },
        positionCopy = location?.let { NavText.positionCopy(it.latitude, it.longitude) },
        fixAgeS = location?.let { com.onpath.app.recording.LocationTracker.ageSeconds(it) },
    )
    // Back on the open sheet = "Keep" (nothing changes)
    androidx.activity.compose.BackHandler(enabled = vm.activityCheck != null) { vm.closeActivityCheck() }
    val bg = rememberBackgroundDownload(vm)
    NavigateOverlay(ui, footer, onSlideToPlan = vm::switchToPlan, onResume = { vm.pauseRecording(false) },
        onTakeRoute = vm::takeNewRoute, onKeepRoute = vm::keepOriginalRoute,
        onActivity = vm::openActivityCheck, onChooseActivity = vm::chooseActivity, onKeepActivity = vm::closeActivityCheck,
        onProfileScrub = vm::navScrub, onProfileRelease = vm::navScrubEnd,
        download = bg, onDownload = { vm.downloadDetailsOpen = true })
    BackgroundDownloadDetails(vm, bg)
}

/** Moving time from the position ([remainingM] before the end) to the end of the line, rounded for display. */
internal fun toGoSeconds(times: com.onpath.app.time.RouteTimes?, remainingM: Double, f: com.onpath.app.time.Factors): Double =
    if (times == null) 0.0 else com.onpath.app.time.TimeEstimate.round(times.remainingFrom(times.lengthM - remainingM, f))

/** "Your pace on this trip: up 0.9× · down 1.1×" – hidden while every factor is about 1. */
internal fun paceNote(f: com.onpath.app.time.Factors): String? {
    val parts = listOf("up" to f.up, "flat" to f.flat, "down" to f.down).filter { kotlin.math.abs(it.second - 1) >= 0.05 }
    if (parts.isEmpty()) return null
    return "Your pace on this trip: " + parts.joinToString(" · ") { "%s %.1f×".format(java.util.Locale.US, it.first, it.second) }
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

/**
 * Shows [content] for a non-null [value] with a short expand + fade (≈220 ms, ease-out) and lets it leave the same
 * way, keeping the last value while it goes. Nothing disappears by itself – only how it comes and goes changes.
 */
@Composable
fun <T : Any> Appearing(value: T?, content: @Composable (T) -> Unit) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    androidx.compose.animation.AnimatedVisibility(
        visible = value != null,
        enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)),
        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
    ) { last?.let { content(it) } }
}
