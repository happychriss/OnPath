package com.onpath.app.ui

import com.onpath.strings.*

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
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.WrongLocation
import androidx.compose.material.icons.outlined.Warning
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
    /** "Faster than planned uphill" in the opened card; null = nothing learned yet or about as planned. */
    val paceNote: String? = null,
    /**
     * "New route · was 13:15" – the followed line changed (plan edit, re-route) and moved the arrival by
     * more than 5 min; stays until the next change (owner: nothing disappears by itself). null = none.
     */
    val routeNote: String? = null,
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

/** [roundabout]: non-null = the turn is a roundabout, with the exit to take (0 = unknown) – owner 2026-10-28. */
data class TurnHint(val kind: TurnKind, val distanceM: Double, val roundabout: Int? = null) {
    val label: String get() = roundabout?.let { com.onpath.app.routing.JunctionCue.ROUNDABOUT.text(it) } ?: kind.label
}

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
    /** A background download runs (after 10 s): small "⬇ Bozen · 40 %" chip under the status (name cut to fit); tap → details. */
    download: BackgroundDownloadUi? = null,
    onDownload: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopCenter).modeEdge(ModeEdge.TOP).fillMaxWidth().statusBarsPadding()
                .padding(top = 8.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // cards glide in and out instead of popping and pushing the status card (polish audit A3)
            Appearing(ui.turn) { TurnCard(it) }
            // ONE amber card while off route (owner C1 2026-10-24): "Off route", distance + direction, the state
            Appearing(ui.offRoute?.takeIf { it.prompt !is NavPrompt.Question }) { OffRouteCard(it) }
            if (ui.toGo != null || ui.recordedM != null) {
                var profileOpen by rememberSaveable { mutableStateOf(false) }
                NavStatusCard(ui, onResume, profileOpen, onToggleProfile = { profileOpen = !profileOpen }, onActivity = onActivity,
                    onProfileScrub = onProfileScrub, onProfileRelease = onProfileRelease)
            }
            download?.let { BackgroundDownloadChip(it, onDownload, Modifier.align(Alignment.End)) }
        }
        Column(Modifier.align(Alignment.BottomCenter).modeEdge(ModeEdge.BOTTOM).fillMaxWidth()) {
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
    val text = if (t.distanceM < 15) str(Res.string.nav_turn_now, t.label)
        else str(Res.string.nav_turn_in, t.label, turnDistance(t.distanceM))
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary, shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = text }) {
        Row(Modifier.heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (t.roundabout != null) Icons.Filled.RoundaboutRight else turnIcon(t.kind), null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(turnDistance(t.distanceM), style = MaterialTheme.typography.headlineMedium)
                Text(t.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
        }
    }
}
/** Rounded like a navigation display: 10 m steps below 200 m, 50 m steps above. */
internal fun turnDistance(m: Double): String = when {
    m < 15 -> Txt.s(Res.string.nav_turn_distance_now)
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
        val label = measurer.measure("N", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333)))
        drawText(label, topLeft = Offset(cx - label.size.width / 2f, top - label.size.height + 1f))
    }
}

/**
 * Status card, readable at a glance (review 2026-10-24 B1): activity icon (48 dp button → check-and-switch sheet, the
 * orange recording dot on it), "2 h 50 min · 7.4 km" (to go, 22 sp), "↑ 512 m ↓ 310 m" (16 sp), "Arrive 16:40" on the
 * right (clock 30 sp bold). The recording line only when it matters (owner C3): "Paused", or the amber "No GPS ·
 * position 4 min old"; moving time / breaks are in the opened card. Buttons: the icon, "Resume" while paused, the
 * chevron (48 dp); the rest of the card is not clickable.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NavStatusCard(ui: NavUi, onResume: () -> Unit, profileOpen: Boolean = false, onToggleProfile: () -> Unit = {},
                           onActivity: () -> Unit = {}, onProfileScrub: (GeoPoint) -> Unit = {},
                           onProfileRelease: () -> Unit = {}) {
    val hasProfile = (ui.toGo?.profile?.size ?: 0) >= 2
    val movingLine = ui.recordedM?.let { movingLine(ui) }
    val canOpen = hasProfile || ui.position != null || (movingLine != null && ui.toGo != null)
    // the amber off-route card says where the route is – not repeated here (the question card at the bottom does not)
    val offCard = ui.offRoute != null && ui.offRoute.prompt !is NavPrompt.Question
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
                        contentDescription = Txt.s(Res.string.nav_activity_cd, NavText.activityName(a, ui.sac))
                    }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(a.icon, null, tint = OnPathColors.Green, modifier = Modifier.size(28.dp))
                        // recording: the orange dot on the icon instead of a line (owner C3)
                        if (ui.recordedM != null) RecDot(ui.paused, Modifier.align(Alignment.TopEnd).padding(5.dp).size(9.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                ui.toGo?.let { g ->
                    if (g.state == com.onpath.app.time.EstimateState.NOT_ON_ROUTE) {
                        // never a stale time (owner rule 6): what is left counts again once back on the route. While the
                        // amber off-route card shows, it says it (one place, one distance – review B2).
                        if (!offCard) {
                            Text(str(Res.string.nav_not_on_route), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            val off = g.offM
                            if (off != null && off > 0) Text(str(Res.string.nav_from_route, Format.distance(off)), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    } else {
                        val paused = g.state == com.onpath.app.time.EstimateState.PAUSED
                        // time and distance to go side by side, both big (owner 2026-10-28, bike): "2:50 h  7.4 km";
                        // the short time form makes room beside the arrival clock. Where even that does not fit
                        // (small phone, long values) the distance wraps under the time – same size, never smaller.
                        val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(Format.durationShort(g.timeS), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                                color = if (paused) dim else Color.Unspecified)
                            Text(Format.distance(g.distanceM), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                                color = if (paused) dim else Color.Unspecified)
                        }
                        if (paused) Text(timePausedText(g, ui.waitingGps), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Text("↑ ${Format.meters(g.ascentM)}  ↓ ${Format.meters(g.descentM)}",
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (ui.recordedM != null && ui.waitingGps) {
                    // no fix: the arrow and the to-go are stale – a warning, not grey small text (review B3)
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Warning, null, tint = OnPathColors.Warn, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(noGpsLine(ui.fixAgeS), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                            color = OnPathColors.Warn, maxLines = 2)
                    }
                } else ui.recordedM?.let { d ->
                    recordingLine(ui, d, offCard)?.let { line ->
                        Row(Modifier.padding(top = if (ui.toGo != null) 2.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (ui.activity == null) { RecDot(ui.paused, Modifier.size(8.dp)); Spacer(Modifier.width(8.dp)) }
                            Text(line,
                                style = if (ui.toGo == null || offCard) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (ui.paused && ui.recordedM != null) TextButton(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_resume)) }
            else if (ui.toGo != null && ui.arriveAt != null && ui.toGo.state != com.onpath.app.time.EstimateState.NOT_ON_ROUTE) Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp).width(IntrinsicSize.Max).semantics(mergeDescendants = true) {}) {
                val paused = ui.toGo.state == com.onpath.app.time.EstimateState.PAUSED
                // "Arrive 16:40 <target>": the clock large, the target under it, no wider than the clock
                // (the time on the left keeps its room)
                Text(str(Res.string.nav_arrive), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(ui.arriveAt, style = MaterialTheme.typography.headlineMedium, maxLines = 1,
                    color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else Color.Unspecified)
                ui.toGo.target?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.then(NoIntrinsicWidth).fillMaxWidth())
                }
            }
            if (canOpen) androidx.compose.material3.IconButton(onClick = onToggleProfile, modifier = Modifier.size(48.dp)) {
                Icon(if (profileOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = str(if (profileOpen) Res.string.nav_profile_hide else Res.string.nav_profile_show),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ui.routeNote?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
        // Chevron: elevation profile of what is left + own position (stays open until tapped again).
        if (profileOpen && canOpen) {
            if (hasProfile) ElevationProfile(ui.toGo!!.profile, height = 84.dp, gradient = true,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                onHighlight = { p -> p?.let(onProfileScrub) }, onRelease = onProfileRelease)
            // moving time / breaks live here (owner C3), then the pace in plain words (owner C4)
            if (ui.toGo != null) movingLine?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp))
            }
            ui.paceNote?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val clipLabel = str(Res.string.nav_position_clip)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(enabled = copy != null, onClickLabel = str(Res.string.nav_copy_position)) {
            try {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(clipLabel, copy))
                copied = true
            } catch (e: Exception) {
                com.onpath.app.debug.Problems.warn("ui", "Position could not be copied", e)
            }
        }
        .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.MyLocation, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (copied) str(Res.string.nav_copied, text) else text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The recording line of the status card, only when it matters (owner C3 2026-10-24): a free recording (it is the card's
 * content): "Recording · 2.1 km · moving 0:28 · breaks 0:05"; paused: "Paused · moving 1:12"; off route (the card has
 * nothing else then): "Recording · 2.1 km". Normal navigation → null (the orange dot on the activity icon says it).
 * No GPS is the amber [noGpsLine] instead. Without moving time: the elapsed clock as before.
 */
internal fun recordingLine(ui: NavUi, distanceM: Double, offRoute: Boolean = false): String? {
    val free = ui.toGo == null
    if (!free && !ui.paused && !offRoute) return null
    val status = Txt.s(if (ui.paused) Res.string.nav_status_paused else Res.string.nav_status_recording)
    val moving = ui.movingS ?: return "$status · ${Format.distance(distanceM)} · ${Format.clock(ui.elapsedS)}"
    val parts = mutableListOf(status)
    if (free || offRoute) parts += Format.distance(distanceM)
    if (free || ui.paused) {
        parts += Txt.s(Res.string.nav_moving_lower, com.onpath.app.time.TimeEstimate.hm(moving))
        val b = ui.breakS ?: 0.0
        if (free && b >= 60) parts += Txt.s(Res.string.nav_breaks, com.onpath.app.time.TimeEstimate.hm(b))
    }
    return parts.joinToString(" · ")
}

/** "Moving 1:12 · breaks 0:11" for the opened card; null without moving time. */
internal fun movingLine(ui: NavUi): String? {
    val moving = ui.movingS ?: return null
    val b = ui.breakS ?: 0.0
    return Txt.s(Res.string.nav_moving, com.onpath.app.time.TimeEstimate.hm(moving)) +
        if (b >= 60) " · " + Txt.s(Res.string.nav_breaks, com.onpath.app.time.TimeEstimate.hm(b)) else ""
}

/** "No GPS · position 4 min old" (the age once it is a minute or more). */
internal fun noGpsLine(fixAgeS: Long?): String =
    Txt.s(Res.string.nav_no_gps) + (fixAgeS?.takeIf { it >= 60 }?.let { " · " + Txt.s(Res.string.nav_position_age, (it / 60).toInt()) } ?: "")

/** "Time paused" (the no-GPS line above says why) / "Time paused · no progress". */
internal fun timePausedText(g: ToGo, waitingGps: Boolean): String =
    if (waitingGps || g.pausedBecause == null) Txt.s(Res.string.nav_time_paused)
    else Txt.s(Res.string.nav_time_paused) + " · ${g.pausedBecause}"

/**
 * The ONE off-route card (owner C1 2026-10-24, WARNING look): amber container, big arrow to the route, "Off route"
 * (22 sp bold), "120 m north-east (direct)" (16 sp) and the state in small text ("Looking for a way back…", "No way
 * back found", "Following the original route", "No map here to find a way back").
 */
@Composable
private fun OffRouteCard(o: OffRouteUi) {
    val hint = NavText.toRoute(o.toRoute)
    val state = NavText.state(o.prompt)
    val offRoute = str(Res.string.nav_off_route)
    Surface(shape = MaterialTheme.shapes.large, color = OnPathColors.WarnContainer,
        contentColor = OnPathColors.OnWarnContainer, shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = listOfNotNull(offRoute, hint, state).joinToString(". ")
        }) {
        Row(Modifier.heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when {
                    o.arrowDeg != null -> Icon(Icons.Filled.Navigation, null, tint = OnPathColors.Warn,
                        modifier = Modifier.size(44.dp).rotate(o.arrowDeg))
                    o.prompt == NavPrompt.Searching -> CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp,
                        color = OnPathColors.Warn)
                    else -> Icon(Icons.Outlined.WrongLocation, null, tint = OnPathColors.Warn, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(offRoute, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                hint?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (o.prompt == NavPrompt.Searching && o.arrowDeg != null) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = OnPathColors.OnWarnContainer)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(state, style = MaterialTheme.typography.bodyMedium)
                }
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
                // turn round: the U-turn sign, so a way back on the same road is not mistaken for "nothing happened"
                Icon(q.switch?.activity?.icon ?: if (q.turnRound) Icons.Filled.UTurnLeft else Icons.Outlined.AltRoute, null,
                    tint = OnPathColors.Route, modifier = Modifier.size(if (q.turnRound) 36.dp else 28.dp))
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
    // a roundabout from the router's instructions wins over the drawn arrow when it comes first (owner 2026-10-28)
    val cues = remember { com.onpath.app.navigation.JunctionCues() }
    val roundabout = remember(line, remaining, nav?.version, rec?.offRouteAlert) {
        if (line == null || remaining == null || rec?.offRouteAlert == true) null
        else cues.roundaboutAhead(line, nav?.version ?: 0, (total - remaining.distanceM).coerceAtLeast(0.0))
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
        turn = turnHint(next?.let { (t, d) -> t.kind to d }, roundabout),
        headingUp = vm.navHeadingUp,
        arriveAt = arrive,
        movingS = tt?.movingS, breakS = tt?.breakS,
        paceNote = tt?.learned?.let { paceNote(it) },
        routeNote = tt?.lineNote?.text(),
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

/**
 * The pace in plain words (owner C4 2026-10-24): "Faster than planned uphill", "Slower than planned downhill",
 * "Faster than planned uphill · slower on the flat", "Faster than planned" (all three) – only when clearly different
 * (time factor ≤ 0.85 or ≥ 1.15); null otherwise.
 */
internal fun paceNote(f: com.onpath.app.time.Factors): String? {
    val groups = listOf(Res.string.nav_pace_uphill to f.up, Res.string.nav_pace_flat to f.flat, Res.string.nav_pace_downhill to f.down)
    val faster = groups.filter { it.second <= 1 - PACE_CLEAR }.map { Txt.s(it.first) }
    val slower = groups.filter { it.second >= 1 + PACE_CLEAR }.map { Txt.s(it.first) }
    // "faster than planned uphill and downhill" (all three: no places)
    fun part(all: org.jetbrains.compose.resources.StringResource, where: org.jetbrains.compose.resources.StringResource,
             places: List<String>) = if (places.size == 3) Txt.s(all)
        else Txt.s(where, places.reduce { a, b -> Txt.s(Res.string.nav_pace_and, a, b) })
    val parts = listOfNotNull(faster.takeIf { it.isNotEmpty() }?.let { part(Res.string.nav_pace_faster, Res.string.nav_pace_faster_where, it) },
        slower.takeIf { it.isNotEmpty() }?.let { part(Res.string.nav_pace_slower, Res.string.nav_pace_slower_where, it) })
    if (parts.isEmpty()) return null
    return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

/** A learned time factor this far from 1 is "clearly different" (paceNote). */
internal const val PACE_CLEAR = 0.15

/** Off the line by more than this: no turn hint (the off-route chip / warning takes over). */
internal const val TURN_HIDE_OFF_M = 40.0

/** The next turn ahead and its distance, or null (no line, no fix, off route, or > 500 m away). */
/** The hint shown: the next roundabout when it comes before the next drawn turn (+ [ROUNDABOUT_SLACK_M]), else the turn. */
internal fun turnHint(turn: Pair<TurnKind, Double>?, roundabout: Pair<Int, Double>?): TurnHint? = when {
    roundabout != null && (turn == null || roundabout.second <= turn.second + ROUNDABOUT_SLACK_M) ->
        TurnHint(turn?.first ?: TurnKind.RIGHT, roundabout.second, roundabout = roundabout.first)
    turn != null -> TurnHint(turn.first, turn.second)
    else -> null
}

/** A roundabout this little after the drawn turn is the same place (its entry bend). */
internal const val ROUNDABOUT_SLACK_M = 40.0

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
