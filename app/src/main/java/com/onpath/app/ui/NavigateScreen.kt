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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Flag
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format
import com.onpath.app.data.FollowProgress
import com.onpath.app.navigation.NavPrompt
import com.onpath.app.navigation.NavText
import com.onpath.app.navigation.RouteHint
import com.onpath.app.recording.RecState
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Flag
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
    /** No GPS while standing still or only briefly (owner 2026-11-01): a quiet grey state, not the amber ⚠ warning. */
    val quietGps: Boolean = false,
    /** No GPS while the phone lies still (its motion sensor, owner 2026-11-02): nothing about GPS – the usual line. */
    val stillGps: Boolean = false,
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
    /**
     * Tour started indoors, no GPS fix yet: "Approximate position (Wi-Fi, ±80 m) – GPS comes outside" – the amber line
     * instead of "No GPS" while what is left counts from the Wi-Fi / fused fix ([com.onpath.app.navigation.Provisional]).
     */
    val approx: String? = null,
    /** OnPath's notifications are off during this tour: the amber box under the status card ([notificationsOffMessage]). */
    val notificationsOff: Boolean = false,
)

/** [roundabout]: non-null = the turn is a roundabout, with the exit to take (0 = unknown). */
data class TurnHint(val kind: TurnKind, val distanceM: Double, val roundabout: Int? = null,
                    /** BRouter's angle of the roundabout turn (> 0 right): where the drawn sign's exit goes. */
                    val roundaboutAngle: Float? = null,
                    /** The new route starts behind: "Umdrehen" with the U sign (owner 2026-11-01). */
                    val turnRound: Boolean = false,
                    /** No turn near: "↑ Geradeaus · 2,4 km" to the next one (the card always there, owner 2026-11-01). */
                    val straight: Boolean = false,
                    /** The kind of way the turn goes onto, as the voice says it ("Links auf den Radweg"); null = no word. */
                    val onto: com.onpath.app.navigation.WayKind? = null,
                    /** The next plan stop comes first: "1,2 km · Stopp 2 · Leiteralm" with the stop's sign ([NextStop]). */
                    val stop: StopAhead? = null) {
    val label: String get() = if (stop != null) stop.label else if (straight) Txt.s(Res.string.nav_straight_on) else if (turnRound) Txt.s(Res.string.nav_turn_round)
        else roundabout?.let { com.onpath.app.routing.JunctionCue.ROUNDABOUT.text(it) }
            ?: onto?.let { com.onpath.app.navigation.VoiceText.onto(kind.label, it) } ?: kind.label
}

/**
 * Off-route state for the screen. [arrowDeg] = direction to the route relative to the screen's "up" (map bearing
 * already taken off), null without a direction. Nothing here times out: it stays until answered or back on route.
 */
data class OffRouteUi(val prompt: NavPrompt, val toRoute: RouteHint? = null, val arrowDeg: Float? = null) {
    /** The ONE amber off-route card on top (searching, no way, kept) – not the questions below, not the far start. */
    val amber: Boolean get() = prompt is NavPrompt.NoWay || prompt == NavPrompt.Kept
}

data class ToGo(
    val distanceM: Double, val ascentM: Double, val timeS: Double, val offM: Double?,
    val descentM: Double = 0.0,
    /** The rest of the followed line from the current position (for the elevation profile). */
    val profile: List<GeoPoint> = emptyList(),
    /** Up to 3 plan stops ahead with their arrival clock, under the profile (owner 2026-11-05); empty = none. */
    val stops: List<StopRow> = emptyList(),
    /**
     * Can the time be trusted now? NOT_ON_ROUTE: "— not on route" instead of a time; PAUSED: the last time greyed with
     * "estimate paused · [pausedBecause]" (owner rules 6 and 8, docs/plan-time-estimate.md).
     */
    val state: com.onpath.app.time.EstimateState = com.onpath.app.time.EstimateState.OK,
    val pausedBecause: String? = null,
    /** Where the time counts to: the last stop's name under the arrival clock; null = the (unnamed) destination. */
    val target: String? = null,
    /**
     * Never on the route yet ([com.onpath.app.navigation.NavSession.reached] false): the distance to the route's START
     * – the far-start box's number ([com.onpath.app.navigation.NavPrompt.FarStart.distanceM]); null = reached / unknown.
     */
    val startM: Double? = null,
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
    /** Behind the slider (owner 2026-11-01): Pause / Resume and End (asks first). */
    onPause: (Boolean) -> Unit = {},
    onEnd: () -> Unit = {},
    /** Screenshot tests: the bar the slider unlocks, open. */
    actionsOpen: Boolean = false,
    /**
     * Settings → Navigation "Sperre beim Navigieren" (owner 2026-11-05, default on): the slider unlocks. Off: no slider,
     * Pause / Tour beenden in the status card and the big blue "Plan ändern" are shown at once and never lock.
     */
    lock: Boolean = true,
    slideStartFraction: Float = 0f,
    onTakeRoute: (Int) -> Unit = {},
    onKeepRoute: (Int) -> Unit = {},
    /** "Off route – re-plan?" – Yes: the new route via the stop in question (StopSkip). */
    onTakeVia: (Int) -> Unit = {},
    /** "Route starts 31 km away" (never on the route yet): Guide me / Just record; OK on "no way to the route". */
    onLeadThere: (Int) -> Unit = {},
    onJustRecord: (Int) -> Unit = {},
    /** "Hier einsteigen" on the mid-route question (owner 2026-11-03); "Zu ① führen" is [onLeadThere]. */
    onJoinHere: (Int) -> Unit = {},
    onLeadFailedOk: (Int) -> Unit = {},
    onActivity: () -> Unit = {},
    onChooseActivity: (Activity) -> Unit = {},
    onKeepActivity: () -> Unit = {},
    /** Hike's T chip in the activity sheet (K1). */
    onHikeLevel: (Int) -> Unit = {},
    /** Finger on the elevation profile: that spot on the route (null never sent) / finger left. */
    onProfileScrub: (GeoPoint) -> Unit = {},
    onProfileRelease: () -> Unit = {},
    /** A background download runs (after 10 s): small "⬇ Bozen · 40 %" chip under the status (name cut to fit); tap → details. */
    download: BackgroundDownloadUi? = null,
    onDownload: () -> Unit = {},
    /** The "Notifications off" box: tap → phone setup step, OK → gone for this tour. */
    onNotifications: () -> Unit = {},
    onNotificationsOk: () -> Unit = {},
    /** An error without a screen of its own (AppViewModel.errorCard): the red box with OK. */
    error: String? = null,
    onErrorOk: () -> Unit = {},
    /**
     * What covers the map (dp): the top column or the message box under it (below the status bar), the slider at the
     * bottom (with the navigation bar) – a route fit leaves that room free (FitPaddingRule).
     */
    onCover: (topDp: Int, bottomDp: Int) -> Unit = { _, _ -> },
    /** Developer tools unlocked: the "Tour-Sim" chip ([NavSimChip]) under the status; null = none (everyone else). */
    sim: (@Composable () -> Unit)? = null,
    /** "App verlassen?" answered with Verlassen: the app goes to the background, the recording goes on (N4). */
    onLeaveApp: () -> Unit = {},
    /** A plan / done tour tapped in My routes during the tour is followed now ([NewPlanLine]); null = no line. */
    newPlan: NewPlanNote? = null,
    onNewPlanUndo: () -> Unit = {},
    onNewPlanGone: () -> Unit = {},
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // the top column's height (turn card, status, chips) below the status bar: the one box goes 12 dp below it
    var topColumnDp by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var messageBottomDp by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var sliderDp by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // ONE bar at the bottom with and without the lock (owner 2026-11-06: "Stopp und Pause an der Stelle ist logischer"):
    // "⏸ Pause | ✎ Planen", paused "Weiter · Beenden · round ✎" ([TourBottomBar]); the status card has no buttons.
    // Locked (owner 2026-11-06 22:20, variant A of the hold lock – replaces the slider and its unlocked state): the same
    // buttons light, each fires only when held ~1 s ([HoldButton]); the lock off in the settings: a plain tap.
    // [actionsOpen] (screenshot tests) draws the plain-tap look.
    val hold = lock && !actionsOpen
    val closableBar = false
    // Android's back while a tour records (owner 2026-11-06, flow map N4): ask instead of leaving the app – a novice took
    // "app gone" for "tour gone" and might start a second one. A second back closes the question (= Bleiben).
    var leaveAsk by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = LeaveAppRule.backAsks(recording = ui.recordedM != null,
        barOpen = closableBar, sheetOpen = ui.activityCheck != null)) { leaveAsk = !leaveAsk }
    val recordingNow = ui.recordedM != null
    LaunchedEffect(recordingNow) { if (!recordingNow) leaveAsk = false }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopCenter).modeEdge(ModeEdge.TOP).fillMaxWidth().statusBarsPadding()
                .onSizeChanged { topColumnDp = with(density) { it.height.toDp().value.toInt() } }
                .padding(top = 8.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // cards glide in and out instead of popping and pushing the status card
            Appearing(ui.turn) { TurnCard(it) }
            // ONE amber card while off route (owner): "Off route", distance + direction, the state
            Appearing(ui.offRoute?.takeIf { it.amber }) { OffRouteCard(it) }
            if (ui.toGo != null || ui.recordedM != null) {
                var profileOpen by rememberSaveable { mutableStateOf(false) }
                NavStatusCard(ui, onResume, profileOpen, onToggleProfile = { profileOpen = !profileOpen }, onActivity = onActivity,
                    onProfileScrub = onProfileScrub, onProfileRelease = onProfileRelease,
                    // the lock off (owner 2026-11-06): no buttons in the card – Pause | Planen at the bottom ([TourBottomBar])
                    actions = null)
            }
            download?.let { BackgroundDownloadChip(it, onDownload, Modifier.align(Alignment.End)) }
            sim?.let { Box(Modifier.align(Alignment.End)) { it() } }
        }
        Column(Modifier.align(Alignment.BottomCenter).modeEdge(ModeEdge.BOTTOM).fillMaxWidth()) {
            footer() // map controls (compass/orientation, eye, locate) are grouped in the footer
            val bottom = Modifier.onSizeChanged { sliderDp = with(density) { it.height.toDp().value.toInt() } }
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            // a tour: Pause | Planen (paused Weiter · Beenden · ✎); no tour: one big blue Planen – held while locked
            if (ui.recordedM != null) TourBottomBar(ui.paused, onPause, onEnd = onEnd, onPlan = onSlideToPlan, bottom, hold = hold)
            else ChangePlanButton(onSlideToPlan, bottom, hold = hold)
        }
        // "Neuer Plan – deine Aufnahme läuft weiter" just above the bottom bar, over the map (nothing moves under it), left
        // of the map controls' column (it covered "locate" across the full width – merge review 2026-11-06)
        newPlan?.let { n ->
            LaunchedEffect(n.id) { kotlinx.coroutines.delay(NEW_PLAN_LINE_MS); onNewPlanGone() }
            NewPlanLine(if (n.undo) onNewPlanUndo else null,
                Modifier.align(Alignment.BottomStart)
                    .padding(start = 16.dp, end = NEW_PLAN_LINE_END, bottom = (sliderDp + 8).dp))
        }
        // ONE box under the turn / status column (docs/design/ui-style.md, owner 2026-10-31 – the re-route and
        // activity-switch question moved here from the bottom, 56 dp buttons kept); the question wins, the rest wait.
        // Drawn over the map controls: a tall question (three answers, large font) is never hidden under a button.
        val question = ui.offRoute?.prompt as? NavPrompt.Question
        // never on the route yet (owner 2026-10-31): the calm far-start question; "Guide me" found nothing → red note
        val far = ui.offRoute?.prompt as? NavPrompt.FarStart
        val leadFailed = ui.offRoute?.prompt as? NavPrompt.LeadFailed
        // reached beside a later part (owner 2026-11-03): "Du stehst mitten auf der Route" · Hier einsteigen / Zu ① führen
        val mid = ui.offRoute?.prompt as? NavPrompt.MidStart
        val message = Messages.pick(listOf(
            if (leaveAsk) leaveAppMessage(onStay = { leaveAsk = false }, onLeave = { leaveAsk = false; onLeaveApp() }) else null,
            question?.let { q -> rerouteMessage(q, onTake = { onTakeRoute(q.id) }, onKeep = { onKeepRoute(q.id) }, onVia = { onTakeVia(q.id) }) },
            ui.offRoute?.takeIf { it.prompt == NavPrompt.Searching }?.let { o -> searchingMessage(o, onKeep = { onKeepRoute(-1) }) },
            far?.let { p -> farStartMessage(p, onLead = { onLeadThere(p.id) }, onJustRecord = { onJustRecord(p.id) }) },
            mid?.let { p -> midStartMessage(p, onLead = { onLeadThere(p.id) }, onJoin = { onJoinHere(p.id) }) },
            leadFailed?.let { e -> errorMessage(NavText.title(e)) { onLeadFailedOk(e.id) } },
            error?.let { errorMessage(it, onErrorOk) },
            if (ui.notificationsOff) notificationsOffMessage(onNotifications, onNotificationsOk) else null,
        ))
        MessageSlot(message, MessagePlacement.topDp(MessageScreen.NAVIGATE, topColumnDp), Modifier.modeEdge(ModeEdge.TOP),
            onBottomDp = { messageBottomDp = it })
        // a route fit ("Show whole route") keeps clear of the column / the box and of the slider
        val shownMessageBottom = if (message == null) 0 else messageBottomDp
        val cover by androidx.compose.runtime.rememberUpdatedState(onCover)
        androidx.compose.runtime.SideEffect { cover(maxOf(topColumnDp, shownMessageBottom), sliderDp) }
        // over the controls: nothing else is touched by accident while choosing; no tap-outside dismiss
        ui.activityCheck?.let { ActivitySwitchSheet(it, onChooseActivity, onKeepActivity, Modifier.align(Alignment.BottomCenter), onHikeLevel = onHikeLevel) }
    }
}

/** How long [NewPlanLine] stays (ms): long enough to read it and reach "Rückgängig" with gloves. */
internal const val NEW_PLAN_LINE_MS = 8_000L

/** [NewPlanLine]'s right edge: clear of the map controls' column (16 dp margin + one [MAP_FAB] + 8 dp air). */
internal val NEW_PLAN_LINE_END = 16.dp + MAP_FAB + 8.dp

/**
 * A plan or a done tour tapped in My routes during a tour is followed at once, without a question (owner 2026-11-06
 * 22:05): this light card above the bottom bar says "Neuer Plan – deine Aufnahme läuft weiter" for [NEW_PLAN_LINE_MS]
 * (no dark message bar – owner; paper like every floating card, owner 2026-11-06) and, when a plan was replaced, "Rückgängig" (blue: it
 * plans) brings that plan back ([AppViewModel.undoNewPlan]; it is also under My routes → Zuletzt).
 */
@Composable
internal fun NewPlanLine(onUndo: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = OnPathColors.Paper, modifier = modifier.fillMaxWidth().warmShadow(OnPathShadow.LOW, MaterialTheme.shapes.large)) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(str(Res.string.plan_new_during_tour), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            onUndo?.let {
                androidx.compose.material3.TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = OnPathColors.ActionEdit)) {
                    Text(str(Res.string.plan_undo), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Next turn, the most prominent thing on screen (like Google Maps' green maneuver card): arrow, distance, direction. */
@Composable
private fun TurnCard(t: TurnHint) {
    val spoken = StopTokens.plain(t.label)
    val text = if (t.distanceM < 15 && !t.straight && t.stop == null) str(Res.string.nav_turn_now, spoken)
        else str(Res.string.nav_turn_in, spoken, turnDistance(t.distanceM))
    Surface(shape = MaterialTheme.shapes.large, color = OnPathColors.ActionGo,
        contentColor = OnPathColors.CardWhite,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = text }.warmShadow(OnPathShadow.HIGH, MaterialTheme.shapes.large)) {
        Row(Modifier.heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.stop != null) StopSign(t.stop, Modifier.size(48.dp))
            else if (t.roundabout != null) RoundaboutSign(t.roundabout, t.roundaboutAngle, Modifier.size(48.dp))
            else Icon(if (t.straight) Icons.Filled.ArrowUpward else turnIcon(t.kind), null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(16.dp))
            // the way word goes on the same line as the direction ("Links auf den Radweg"): the card never grows, a long
            // label ends in "…" (owner: nothing jumps)
            Column(Modifier.weight(1f)) {
                Text(instrument(turnDistance(t.distanceM), MaterialTheme.typography.headlineMedium),
                    style = MaterialTheme.typography.headlineMedium)
                StopText(t.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
/**
 * The plan point's sign on the turn card, like on the map (owner 2026-11-01: near-black numbered stops, dark red goal),
 * with a white ring so it reads on the green card.
 */
@Composable
private fun StopSign(s: StopAhead, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).background(OnPathColors.CardWhite, androidx.compose.foundation.shape.CircleShape)
            .padding(2.5.dp).background(if (s.goal) OnPathColors.PointGoal else OnPathColors.PointStop,
                androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            if (s.goal) Icon(Icons.Filled.Flag, null, tint = OnPathColors.CardWhite, modifier = Modifier.size(22.dp))
            else Text(s.number.toString(), color = OnPathColors.CardWhite, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
        }
    }
}

/** One stop ahead under the profile: the map's sign (small), the name ("…"), then distance and "an 14:20" – never cut. */
@Composable
private fun StopAheadRow(r: StopRow) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 2.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically) {
        // grows with a large font (the number is never cut): at least 28 dp, round while it fits
        Box(Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).background(if (r.stop.goal) OnPathColors.PointGoal else OnPathColors.PointStop,
            androidx.compose.foundation.shape.CircleShape).padding(horizontal = 6.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
            if (r.stop.goal) Icon(Icons.Filled.Flag, null, tint = OnPathColors.CardWhite, modifier = Modifier.size(16.dp))
            else Text(r.stop.number.toString(), color = OnPathColors.CardWhite, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(instrument(Format.distance(r.stop.distanceM), MaterialTheme.typography.bodyLarge),
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, softWrap = false)
            r.arriveAt?.let { Text(str(Res.string.nav_stop_arrive, it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
        }
    }
}

/** Rounded like a navigation display: 10 m steps below 200 m, 50 m steps above. */
internal fun turnDistance(m: Double): String = when {
    m < 15 -> Txt.s(Res.string.nav_turn_distance_now)
    m < 195 -> "${Math.round(m / 10) * 10} m"
    m < 1_000 -> "${Math.round(m / 50) * 50} m"
    else -> Format.distance(m)
}

internal fun turnIcon(k: TurnKind): ImageVector = when (k) {
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
 * Status card, readable at a glance: activity icon (48 dp button → check-and-switch sheet, the
 * orange recording dot on it), "2 h 50 min · 7.4 km" (to go, 22 sp), "↑ 512 m ↓ 310 m" (16 sp), "Arrive 16:40" on the
 * right (clock 30 sp bold). The recording line only when it matters (owner): "Paused", or the amber "No GPS ·
 * position 4 min old"; moving time / breaks are in the opened card. Buttons: the icon, "Resume" while paused, the
 * chevron (48 dp); the rest of the card is not clickable.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NavStatusCard(ui: NavUi, onResume: () -> Unit, profileOpen: Boolean = false, onToggleProfile: () -> Unit = {},
                           onActivity: () -> Unit = {}, onProfileScrub: (GeoPoint) -> Unit = {},
                           onProfileRelease: () -> Unit = {},
                           /** Extra content under the route info (none since 2026-11-06: the tour's buttons are the bottom bar, [TourBottomBar]). */
                           actions: (@Composable () -> Unit)? = null) {
    val hasProfile = (ui.toGo?.profile?.size ?: 0) >= 2
    val movingLine = ui.recordedM?.let { movingLine(ui) }
    val canOpen = hasProfile || ui.position != null || (movingLine != null && ui.toGo != null)
    // the amber off-route card says where the route is – not repeated here (the question card at the bottom does not)
    val offCard = ui.offRoute?.amber == true
    // over the map: paper like every surface (owner 2026-11-06), the shadow lifts it off the map
    Surface(shape = MaterialTheme.shapes.large, color = OnPathColors.Paper,
        // only the activity icon and the chevron are buttons (gloves: no tap target as big as the card)
        modifier = Modifier.fillMaxWidth().warmShadow(OnPathShadow.LOW, MaterialTheme.shapes.large)) {
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
                        // no "● REC" sign any more (owner 2026-11-06: the running time shows the recording); paused the
                        // icon is grey and the card's line says "Pausiert"
                        Icon(a.icon, null, tint = if (ui.paused && ui.recordedM != null) OnPathColors.Grey else OnPathColors.Green,
                            modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                // a tour without a route (owner 2026-11-05): say so plainly instead of an empty route line
                if (ui.toGo == null && ui.recordedM != null) Text(str(Res.string.nav_free_tour), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2)
                ui.toGo?.let { g ->
                    if (g.state == com.onpath.app.time.EstimateState.NOT_ON_ROUTE) {
                        // never a stale time (owner rule 6): what is left counts again once back on the route. While the
                        // amber off-route card shows, it says it (one place, one distance).
                        if (!offCard) {
                            // never reached: "Not on the route yet · Start 31 km away" (the far-start box's number)
                            val (title, line2) = NavText.notOnRoute(g.startM, g.offM)
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (line2 != null) Text(line2, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    } else {
                        val paused = g.state == com.onpath.app.time.EstimateState.PAUSED
                        // time and distance to go side by side, both big (owner): "2:50 h  7.4 km";
                        // the short time form makes room beside the arrival clock. Where even that does not fit
                        // (small phone, long values) the distance wraps under the time – same size, never smaller.
                        val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // instrument numbers (owner 2026-11-06): equal-width digits, nothing jiggles while counting
                            Text(instrument(Format.durationShort(g.timeS), MaterialTheme.typography.titleLarge),
                                style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false,
                                color = if (paused) dim else Color.Unspecified)
                            Text(instrument(Format.distance(g.distanceM), MaterialTheme.typography.titleLarge),
                                style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false,
                                color = if (paused) dim else Color.Unspecified)
                        }
                        if (paused) Text(timePausedText(g, ui.waitingGps), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Text(instrument("↑ ${Format.meters(g.ascentM)}  ↓ ${Format.meters(g.descentM)}", MaterialTheme.typography.bodyLarge),
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // (the provisional-position line has its own full-width row under this one)
                if (ui.recordedM != null && ui.waitingGps && !ui.stillGps && ui.approx == null) {
                    // no fix: the arrow and the to-go are stale – a warning, not grey small text; standing still (owner
                    // 2026-11-01: a restaurant) only a quiet grey state
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!ui.quietGps) {
                            Icon(Icons.Outlined.Warning, null, tint = OnPathColors.Warn, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(noGpsLine(ui.fixAgeS), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                            color = if (ui.quietGps) MaterialTheme.colorScheme.onSurfaceVariant else OnPathColors.Warn, maxLines = 2)
                    }
                } else ui.recordedM?.let { d ->
                    recordingLine(ui, d, offCard)?.let { line ->
                        Row(Modifier.padding(top = if (ui.toGo != null) 2.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (ui.activity == null) { RecDot(ui.paused, Modifier.size(8.dp)); Spacer(Modifier.width(8.dp)) }
                            Text(line,
                                style = if (offCard) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            // paused: "Fortsetzen" is in the card's action row once unlocked (owner 2026-11-06: locked + paused = the slider)
            if (ui.toGo != null && ui.arriveAt != null && ui.toGo.state != com.onpath.app.time.EstimateState.NOT_ON_ROUTE) Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp).widthIn(min = targetWordWidth(ui.toGo.target))
                    .width(IntrinsicSize.Max).semantics(mergeDescendants = true) {}) {
                val paused = ui.toGo.state == com.onpath.app.time.EstimateState.PAUSED
                // "Arrive 16:40 <target>": the clock large, the target under it, as wide as the clock or its longest
                // word (the time on the left keeps its room); two lines, so a name like "Gasthof Hochmuth" is not cut
                // to "Gasthof H…" (field A-019) and no word is split – a longer name ends in "…" on the second line
                Text(str(Res.string.nav_arrive), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(ui.arriveAt, style = MaterialTheme.typography.headlineMedium, maxLines = 1,
                    color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else Color.Unspecified)
                ui.toGo.target?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.then(NoIntrinsicWidth).fillMaxWidth())
                }
            }
            if (canOpen) androidx.compose.material3.IconButton(onClick = onToggleProfile, modifier = Modifier.size(48.dp)) {
                Icon(if (profileOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = str(if (profileOpen) Res.string.nav_profile_hide else Res.string.nav_profile_show),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ui.recordedM != null) ui.approx?.let {
            // indoors at the start: what is left counts from a Wi-Fi fix – amber ⚠, the card's full width (owner)
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, null, tint = OnPathColors.Warn, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = OnPathColors.Warn, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        ui.routeNote?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
        // unlocked: Pause / Tour beenden directly under the route info (owner 2026-11-05, Navigate C)
        actions?.invoke()
        // Chevron: elevation profile of what is left + own position (stays open until tapped again).
        if (profileOpen && canOpen) {
            if (hasProfile) ElevationProfile(ui.toGo!!.profile, height = 84.dp, gradient = true,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                onHighlight = { p -> p?.let(onProfileScrub) }, onRelease = onProfileRelease,
                marks = ui.toGo.stops.map { ProfileMark(it.stop.distanceM, it.stop.number, it.stop.goal) })
            // the next stops ahead (owner 2026-11-05, like Komoot): sign · name · distance · "an 14:20"
            ui.toGo?.stops?.takeIf { it.isNotEmpty() }?.let { rows ->
                Column(Modifier.padding(start = 12.dp, end = 16.dp, bottom = 8.dp)) { rows.forEach { StopAheadRow(it) } }
            }
            // moving time / breaks live here, then the pace in plain words (owner)
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

/**
 * Width of the longest word of the destination name under the arrival clock (bodySmall): the column is at least that
 * wide, so a word is never split ("Leiteral / m" at the largest font) – at most 40 % of the screen.
 */
@Composable
private fun targetWordWidth(target: String?): androidx.compose.ui.unit.Dp {
    if (target.isNullOrBlank()) return 0.dp
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    val density = androidx.compose.ui.platform.LocalDensity.current
    val capDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.4f
    val wordPx = remember(target, style, density) {
        target.split(' ').filter { it.isNotEmpty() }.maxOfOrNull { measurer.measure(it, style, maxLines = 1).size.width } ?: 0
    }
    return (with(density) { wordPx.toDp() }.value + 1f).coerceAtMost(capDp).dp
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
 * The recording line of the status card, only when it matters (owner): a free recording (it is the card's
 * content): "Recording · 2.1 km · moving 0:28 · breaks 0:05"; paused: "Paused · moving 1:12"; off route (the card has
 * nothing else then): "Recording · 2.1 km". Normal navigation → null (the orange dot on the activity icon says it).
 * No GPS is the amber [noGpsLine] instead. Without moving time: the elapsed clock.
 */
internal fun recordingLine(ui: NavUi, distanceM: Double, offRoute: Boolean = false): String? {
    val free = ui.toGo == null
    // off the route no recording line either (owner 2026-11-01: "überflüssig – der rote Punkt am Rad zeigt es")
    if (!free && !ui.paused) return null
    val status = Txt.s(if (ui.paused) Res.string.nav_status_paused else Res.string.nav_status_recording)
    val moving = ui.movingS ?: return "$status · ${Format.distance(distanceM)} · ${Format.clock(ui.elapsedS)}"
    val parts = mutableListOf(status)
    if (free) parts += Format.distance(distanceM)
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
/** "Approximate position (Wi-Fi, ±80 m) – GPS comes outside" / "… (±80 m) – GPS still weak". */
internal fun approxLine(fix: com.onpath.app.navigation.Provisional.Fix): String = Txt.s(
    if (fix.source == com.onpath.app.navigation.Provisional.Source.WIFI) Res.string.nav_approx_wifi else Res.string.nav_approx_weak_gps,
    fix.accuracyM)

internal fun noGpsLine(fixAgeS: Long?): String =
    Txt.s(Res.string.nav_no_gps) + (fixAgeS?.takeIf { it >= 60 }?.let { " · " + Txt.s(Res.string.nav_position_age, (it / 60).toInt()) } ?: "")

/** "Time paused" (the no-GPS line above says why) / "Time paused · no progress". */
internal fun timePausedText(g: ToGo, waitingGps: Boolean): String =
    if (waitingGps || g.pausedBecause == null) Txt.s(Res.string.nav_time_paused)
    else Txt.s(Res.string.nav_time_paused) + " · ${g.pausedBecause}"

/**
 * The ONE off-route card (owner, WARNING look): amber container, big arrow to the route, "Off route"
 * (22 sp bold), "120 m north-east (direct)" (16 sp) and the state in small text ("Looking for a way back…", "No way
 * back found", "Following the original route", "No map here to find a way back").
 */
@Composable
private fun OffRouteCard(o: OffRouteUi) {
    val hint = NavText.toRoute(o.toRoute)
    val state = NavText.state(o.prompt)
    val offRoute = str(Res.string.nav_off_route)
    Surface(shape = MaterialTheme.shapes.large, color = OnPathColors.WarnContainer,
        contentColor = OnPathColors.OnWarnContainer,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = listOfNotNull(offRoute, hint, state).joinToString(". ")
        }.warmShadow(OnPathShadow.LOW, MaterialTheme.shapes.large)) {
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
 * The off-route question (owner: ask every time, never re-route silently) as a FRAGE box under the status card: stays
 * until one of the big buttons is pressed or the user is back on the route. Buttons 56 dp – fine with gloves. With a
 * stop in question (StopSkip, owner 2026-11-01 / 2026-11-03): "You're 120 m off the route" – Keep original · ③ auslassen ·
 * Über ③ (primary; ③ = the map's numbered circle, [StopText]).
 * The activity switch ("New route for MTB") is the same question with the activity's icon.
 */
@Composable
internal fun rerouteMessage(q: NavPrompt.Question, onTake: () -> Unit, onKeep: () -> Unit, onVia: () -> Unit = {}): Message {
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val maxName = if (large) NavText.NAME_MAX_LARGE else NavText.NAME_MAX
    // turn round: the U-turn sign, so a way back on the same road is not mistaken for "nothing happened"
    val icon = q.switch?.activity?.icon ?: if (q.turnRound) Icons.Filled.UTurnLeft else Icons.Outlined.AltRoute
    // a stop in question (owner 2026-11-03): never left out by "Yes" alone – "Über ③" (primary) · "③ auslassen"
    val buttons = when {
        q.via != null -> listOf(MessageButton(NavText.KEEP, onKeep), MessageButton(NavText.skipButton(q), onTake),
            MessageButton(NavText.viaButton(q), onVia))
        // no way via the stop was found: leaving it out is still said on the button, never hidden behind "New route"
        q.skipStop != null -> listOf(MessageButton(NavText.KEEP, onKeep), MessageButton(NavText.skipButton(q), onTake))
        else -> listOf(MessageButton(NavText.KEEP, onKeep), MessageButton(NavText.TAKE, onTake))
    }
    return Message(MessageKind.FRAGE, NavText.title(q), NavText.question(q, maxName), buttons, icon = icon, navigate = true)
}

/**
 * Off the route, the way back still being computed (owner 2026-11-01: "erst piep, piep, anbieten, dann gemeinsam
 * suchen"): the question comes at once – "Abseits der Route", how far and where the route is; "Keep original" works
 * at once, "New route" shows a spinner until the way is found (then the normal question, [rerouteMessage]).
 */
internal fun searchingMessage(o: OffRouteUi, onKeep: () -> Unit): Message =
    Message(MessageKind.FRAGE, Txt.s(Res.string.nav_title_searching), NavText.toRoute(o.toRoute),
        listOf(MessageButton(NavText.KEEP, onKeep), MessageButton(NavText.TAKE) {}.apply { busy = true }),
        icon = Icons.Outlined.AltRoute, navigate = true)

/**
 * Never on the route yet and away from it (owner 2026-10-31): "Route starts 31 km away" · "south-west (direct) ·
 * recording" · Just record / Guide me (FRAGE). Calm: no vibration, no sound; stays until answered or the route is
 * reached. After "Guide me" only "Just record" stays while the way there is computed ("Finding a way to the start…").
 */
internal fun farStartMessage(p: NavPrompt.FarStart, onLead: () -> Unit, onJustRecord: () -> Unit): Message {
    val buttons = if (p.searching) listOf(MessageButton(NavText.JUST_RECORD, onJustRecord))
        else listOf(MessageButton(NavText.JUST_RECORD, onJustRecord), MessageButton(NavText.LEAD, onLead))
    return Message(MessageKind.FRAGE, NavText.title(p), NavText.state(p), buttons, icon = Icons.Outlined.Flag, navigate = true)
}

/**
 * The route reached beside a later part of it, not at point 1 (owner 2026-11-03: "every tour starts at point 1 and skips
 * nothing"): "Du stehst mitten auf der Route." · "Die Tour beginnt bei ①." · Hier einsteigen / **Zu ① führen** (the
 * primary). Calm (no vibration); stays until answered or the user is at the start. "Hier einsteigen" = the points before
 * count as passed (grey, not deleted). While the way to ① is computed only "Hier einsteigen" stays.
 */
internal fun midStartMessage(p: NavPrompt.MidStart, onLead: () -> Unit, onJoin: () -> Unit): Message {
    val buttons = if (p.searching) listOf(MessageButton(NavText.MID_JOIN, onJoin))
        else listOf(MessageButton(NavText.MID_JOIN, onJoin), MessageButton(NavText.MID_LEAD, onLead))
    return Message(MessageKind.FRAGE, NavText.title(p), NavText.state(p), buttons, icon = Icons.Outlined.Flag, navigate = true)
}

/**
 * Back in Navigate while a tour records (owner 2026-11-06, flow map N4): "App verlassen? Deine Tour wird weiter
 * aufgezeichnet." · Bleiben / Verlassen. Neither is a navigation or plan action (colour rule: neutral).
 */
internal fun leaveAppMessage(onStay: () -> Unit, onLeave: () -> Unit): Message =
    Message(MessageKind.FRAGE, Txt.s(Res.string.nav_leave_title), Txt.s(Res.string.nav_leave_text),
        listOf(MessageButton(Txt.s(Res.string.nav_leave_stay), onStay), MessageButton(Txt.s(Res.string.nav_leave_go), onLeave)))

/** When Android's back asks "App verlassen?" in Navigate (N4) – pure, unit-tested. */
object LeaveAppRule {
    /** Only while a tour records; the unlocked bar and the activity sheet close first with back. */
    fun backAsks(recording: Boolean, barOpen: Boolean, sheetOpen: Boolean): Boolean = recording && !barOpen && !sheetOpen
}

/**
 * A tour runs with OnPath's notifications off ([com.onpath.app.navigation.NotificationsOff]): "⚠ Notifications off / No
 * turns … on the lock screen" + OK (HINWEIS_WARN). Tap on the text → the phone setup step; OK → gone for this tour.
 * Never goes by itself (owner: no self-dismissing messages).
 */
@Composable
internal fun notificationsOffMessage(onSetUp: () -> Unit, onOk: () -> Unit): Message =
    Message(MessageKind.HINWEIS_WARN, str(Res.string.nav_notif_off), str(Res.string.nav_notif_off_line),
        listOf(MessageButton(str(Res.string.common_ok), onOk)), onTap = onSetUp, tapLabel = str(Res.string.nav_notif_off_click))

/**
 * Stateful wrapper: remaining distance along the followed line and the arrival check. While the service navigates,
 * its progress is the one truth (the same as the time to go: direction-aware, "not on route" when off it); without
 * it (no tour yet) the nearest point of the line.
 */
@Composable
internal fun NavigateMode(vm: AppViewModel, rec: RecState?, location: Location?, mapBearing: Float,
                          onCover: (topDp: Int, bottomDp: Int) -> Unit = { _, _ -> }, footer: @Composable () -> Unit) {
    val nav = rec?.nav?.takeIf { it.line.size >= 2 }
    val line = nav?.line ?: vm.followLine
    val progress = remember(line) { line?.let { FollowProgress(it) } }
    // the clock times ("an 16:35", arrival) are "now + time to go": without a new position nothing recomposed, and a
    // stop's time fell behind the clock (owner's Pixel 2026-11-06: "an 16:35" at 16:36) – recompose at every minute
    val minute by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { kotlinx.coroutines.delay(MinuteClock.untilNextMs(System.currentTimeMillis())); value = System.currentTimeMillis() }
    }
    // turns on the followed line, once per line: the junctions the phone vibrates for and the voice names
    // (ScreenTurns: BRouter's hints + JogRule; a GPX track without hints: the geometry, TurnDetector)
    val hike = (rec?.nav?.session?.activity ?: vm.activity) == com.onpath.app.routing.Activity.HIKE
    val turns = remember(line, hike) { line?.let { com.onpath.app.navigation.ScreenTurns.of(it, hike) } ?: emptyList() }
    // the way word the voice says at each turn, the same on the card ("Links auf den Radweg", owner idea 2026-11-01)
    val onto = remember(line, turns, hike) { line?.let { com.onpath.app.navigation.ScreenTurns.onto(it, turns, hike) } ?: emptyMap() }
    val total = remember(line) { line?.let { com.onpath.app.data.Geo.length(it) } ?: 0.0 }
    // same time model as the plan sheet (time/RouteTimes): no jump when navigation starts
    val times = remember(line, vm.activity) { line?.let { com.onpath.app.time.RouteTimes.of(it, vm.activity) } }
    val hint = remember(line) { intArrayOf(0) }
    val here = location?.let { GeoPoint(it.latitude, it.longitude) }
    // started indoors (owner): until the tour's first GPS fix the app's Wi-Fi / fused fix stands in – here only; the
    // service never sees it (no track point, no off-route, no re-route) and the arrival question waits for GPS
    val prov = location?.let { l ->
        com.onpath.app.navigation.Provisional.of(rec != null, rec?.paused == true, rec?.hadGpsFix == true, l.provider,
            l.hasAccuracy(), l.accuracy, (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L)
    }
    val remaining = remember(progress, location, nav?.shownAlongM, nav?.offM, prov) {
        when {
            progress == null -> null
            prov != null -> here?.let { progress.remaining(it, hint[0]) }?.also { hint[0] = it.index }
            nav != null -> nav.shownAlongM?.let { progress.at(it, nav.offM) }
            here == null -> null
            else -> progress.remaining(here, hint[0])?.also { hint[0] = it.index }
        }
    }
    // a provisional position far from the line: "not on route" (calm), never a "to go" from the line's nearest point
    val provOff = prov != null && remaining != null && !com.onpath.app.navigation.Provisional.onRoute(remaining.offM, prov)
    // the arrival is checked in the recording service (review H2: also with the phone in the pocket) – RecState.arrived
    val next = remember(turns, remaining, rec?.offRouteAlert, provOff) {
        if (provOff) null else nextTurnHint(turns, total, remaining?.distanceM, remaining?.offM, rec?.offRouteAlert == true)
    }
    // a roundabout from the router's instructions wins over the drawn arrow when it comes first
    val cues = remember { com.onpath.app.navigation.JunctionCues() }
    val roundabout = remember(line, remaining, nav?.version, rec?.offRouteAlert) {
        if (line == null || remaining == null || rec?.offRouteAlert == true) null
        else cues.roundabout(line, nav?.version ?: 0, (total - remaining.distanceM).coerceAtLeast(0.0))
    }?.let { it.exit to it.distanceM }
    val roundaboutAngle = remember(line, remaining, nav?.version) {
        if (line == null || remaining == null) null
        else cues.roundabout(line, nav?.version ?: 0, (total - remaining.distanceM).coerceAtLeast(0.0))?.angle
    }
    // the map badge shows the drawn arrow – not while the card says "Roundabout" (it would say left / right there)
    val shownTurn = next?.first?.takeIf { turnHint(next.first.kind to next.second, roundabout)?.roundabout == null }
    LaunchedEffect(shownTurn) { vm.navTurn = shownTurn }
    DisposableEffect(Unit) { onDispose { vm.navTurn = null } }
    // provisional: the service knows no position yet – its time state ("no GPS") does not apply, the plain model counts
    val tt = rec?.time?.takeIf { prov == null }
    // the service's calm, learned value while it knows the line; else the plain model from here
    val toGoS = remaining?.let { r -> tt?.toGoS ?: toGoSeconds(times, r.distanceM, vm.planFactors()) }
    val arrive = toGoS?.let { s ->
        tt?.arrivalMs?.takeIf { tt.toGoS != null } ?: com.onpath.app.time.TimeEstimate.arrivalMs(System.currentTimeMillis(), s)
    }?.let { com.onpath.app.time.TimeEstimate.clock(it) }
    // the named last stop; an unnamed one is just "the destination" (no extra line: the card has no room to spare)
    val target = nav?.session?.stops?.lastOrNull()?.name
    val notOnRoute = if (prov != null) line != null && (remaining == null || provOff)
        else nav != null && (remaining == null || tt?.state == com.onpath.app.time.EstimateState.NOT_ON_ROUTE)
    // never on the route yet: the card counts from the START like the far-start box (its number while it shows)
    val startM = nav?.session?.takeIf { !it.reached }?.let { s ->
        (nav.prompt as? com.onpath.app.navigation.NavPrompt.FarStart)?.distanceM
            ?: s.original.firstOrNull()?.let { st -> here?.let { com.onpath.app.data.Geo.distance(it, st) } }
    }
    val ui = NavUi(
        toGo = if (notOnRoute) ToGo(0.0, 0.0, 0.0, if (prov != null) remaining?.offM else nav?.offM,
            state = com.onpath.app.time.EstimateState.NOT_ON_ROUTE, target = target, startM = startM)
        else remaining?.let { r ->
            ToGo(r.distanceM, r.ascentM, toGoS ?: 0.0, r.offM, r.descentM,
                profile = line?.let { l -> l.subList(r.index.coerceIn(0, l.size - 1), l.size) } ?: emptyList(),
                // the next 2–3 stops (owner 2026-11-05): the same rules as the turn card's stop, the same time model as the clock
                stops = nav?.session?.let { sess ->
                    val now = maxOf(System.currentTimeMillis(), minute)
                    val f = vm.planFactors()
                    val plainHere = toGoSeconds(times, r.distanceM, f)
                    NextStop.aheadList(sess, (total - r.distanceM).coerceAtLeast(0.0)).map { st ->
                        val mv = NextStop.movingS(toGoS ?: plainHere, plainHere, toGoSeconds(times, (r.distanceM - st.distanceM).coerceAtLeast(0.0), f))
                        StopRow(st, if (toGoS == null && times == null) null
                            else com.onpath.app.time.TimeEstimate.clock(com.onpath.app.time.TimeEstimate.arrivalMs(now, mv)))
                    }
                } ?: emptyList(),
                state = tt?.state ?: com.onpath.app.time.EstimateState.OK, pausedBecause = tt?.pausedBecause?.text, target = target)
        },
        recordedM = rec?.distanceM, elapsedS = rec?.elapsedS ?: 0, paused = rec?.paused == true,
        waitingGps = rec?.gpsLost == true && prov == null,
        quietGps = rec?.gpsQuiet == true,
        stillGps = rec?.gpsStill == true,
        offRoute = rec?.nav?.let { n ->
            n.prompt?.let { p -> OffRouteUi(p, n.toRoute, n.toRoute?.let { (it.bearingDeg - mapBearing).toFloat() }) }
        },
        // a new route that starts behind: "Umdrehen" first (owner 2026-11-01: after a re-route one saw nothing and did
        // not know where to go) – until the Navigator clears the U sign (walked the new way)
        turn = (nav?.uTurnAt?.let { u -> TurnHint(TurnKind.U_TURN_LEFT, here?.let { com.onpath.app.data.Geo.distance(it, u) } ?: 0.0, turnRound = true) }
            ?: turnHint(next?.let { (t, d) -> t.kind to d }, roundabout)?.let { h ->
                when {
                    h.roundabout != null -> h.copy(roundaboutAngle = roundaboutAngle)
                    next?.first?.turnRound == true -> h.copy(turnRound = true)   // an out-and-back: "Umdrehen"
                    else -> h.copy(onto = next?.first?.index?.let { onto[it] })
                }
            }
            // no turn near: the card stays, "↑ Geradeaus" to the next turn / the goal – nothing jumps (owner 2026-11-01)
            ?: remaining?.takeIf { !provOff && rec?.offRouteAlert != true && (it.offM ?: 0.0) <= TURN_HIDE_OFF_M }?.let { r ->
                val along = (total - r.distanceM).coerceAtLeast(0.0)
                val to = turns.firstOrNull { it.distanceFromStart > along + 5.0 }?.distanceFromStart ?: total
                TurnHint(TurnKind.SLIGHT_RIGHT, (to - along).coerceAtLeast(0.0), straight = true)
            })?.let { h ->
                // the next stop comes before that turn: "1,2 km · Stopp 2 · Leiteralm" (owner 2026-11-03) – "Umdrehen" stays
                val stop = remaining?.let { r -> nav?.session?.let { NextStop.ahead(it, (total - r.distanceM).coerceAtLeast(0.0)) } }
                if (!h.turnRound && NextStop.shown(h.distanceM, stop)) TurnHint(TurnKind.SLIGHT_RIGHT, stop!!.distanceM, stop = stop) else h
            },
        headingUp = vm.navHeadingUp,
        arriveAt = arrive,
        movingS = rec?.time?.movingS, breakS = rec?.time?.breakS,
        paceNote = rec?.time?.learned?.let { paceNote(it) },
        routeNote = rec?.time?.lineNote?.text(),
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
        approx = prov?.let { approxLine(it) },
        notificationsOff = com.onpath.app.navigation.NotificationsOff.shown(rec?.tourId,
            com.onpath.app.setup.PhoneCheck.isOk(com.onpath.app.setup.CheckId.NOTIFICATIONS, vm.phone), vm.notificationsOkTour),
    )
    // Back on the open sheet = "Keep" (nothing changes)
    androidx.activity.compose.BackHandler(enabled = vm.activityCheck != null) { vm.closeActivityCheck() }
    // developer tools: the Navigate "Sim" follows a changed line (plan edit, new route) from where it is
    LaunchedEffect(line) { vm.navSimFollow(line) }
    val bg = rememberBackgroundDownload(vm)
    val activity = androidx.compose.ui.platform.LocalContext.current as? com.onpath.app.MainActivity
    // Cycling, "Screen while cycling: Smart" (owner): far from the next turn the dim rest view covers the map (RestView.kt)
    val restEvents = remember(line, turns) { line?.let { RestEvents.of(it, turns) } ?: emptyList() }
    val restTarget = remember(restEvents, remaining, notOnRoute) {
        RestEvents.next(restEvents, remaining?.takeUnless { notOnRoute }?.let { (total - it.distanceM).coerceAtLeast(0.0) })
    }
    val restOn = ScreenPolicy.of(vm.settings, ui.activity ?: vm.activity).rest && rec != null && nav != null
    val restWanted = rememberRest(restOn,
        // until the tour's first GPS fix (provisional Wi-Fi / fused position, amber line) always the full map
        attention = SmartScreen.needsUser(paused = rec?.paused == true, gpsLost = rec?.gpsLost == true && rec.gpsStill != true,
            hadGpsFix = rec?.hadGpsFix == true, provisional = prov != null, offRouteAlert = rec?.offRouteAlert == true,
            prompt = nav?.prompt != null, notOnRoute = notOnRoute, noFix = location == null, notFollowing = !vm.followMe,
            dialog = vm.restBlocked()),
        target = restTarget, fix = location)
    val rest = restWanted && restTarget != null
    LaunchedEffect(rest) { vm.screenRest = rest }
    DisposableEffect(Unit) { onDispose { vm.screenRest = false } }
    // the hold time of the locked bar (Settings → Navigation, owner 2026-11-06 22:50: 0–2 s in 0,5 s steps)
    Box(Modifier.fillMaxSize()) { androidx.compose.runtime.CompositionLocalProvider(LocalHoldMs provides vm.settings.navHoldMs,
        LocalHoldLearned provides vm.holdLearned, LocalOnHoldFired provides vm::holdFired) {
        NavigateOverlay(ui, footer, onSlideToPlan = { activity?.leaveNavigate(vm::switchToPlan) ?: vm.switchToPlan() }, onResume = { vm.pauseRecording(false) },
            onLeaveApp = { com.onpath.app.debug.Problems.trace("app", "Back in Navigate: left the app, the tour records on"); activity?.moveTaskToBack(true) },
            newPlan = vm.newPlanNote, onNewPlanUndo = vm::undoNewPlan, onNewPlanGone = vm::dropNewPlanNote,
            onPause = vm::pauseRecording, onEnd = { vm.askEndTour() }, lock = vm.settings.navLock,
            onTakeRoute = vm::takeNewRoute, onKeepRoute = vm::keepOriginalRoute, onTakeVia = vm::takeViaRoute,
            onLeadThere = vm::leadToStart, onJustRecord = vm::justRecord, onJoinHere = vm::joinHere, onLeadFailedOk = vm::leadFailedOk,
            onActivity = vm::openActivityCheck, onChooseActivity = vm::chooseActivity, onKeepActivity = vm::closeActivityCheck,
            onHikeLevel = vm::chooseHikeLevel,
            onProfileScrub = vm::navScrub, onProfileRelease = vm::navScrubEnd,
            download = bg, onDownload = { vm.downloadDetailsOpen = true },
            onNotifications = { vm.openSetupStep(com.onpath.app.setup.CheckId.NOTIFICATIONS) },
            onNotificationsOk = { vm.notificationsOkTour = rec?.tourId },
            error = vm.errorCard, onErrorOk = { vm.errorCard = null }, onCover = onCover,
            sim = if (!vm.devToolsShown) null else { {
                val demo by com.onpath.app.recording.DemoLocation.state.collectAsState()
                NavSimChip(demo, onStart = { vm.startNavSim(line) }, onFactor = com.onpath.app.recording.DemoLocation::setFactor,
                    onStop = { com.onpath.app.recording.DemoLocation.stop("stopped on the Navigate page") },
                    onPause = com.onpath.app.recording.DemoLocation::setPaused)
            } })
        }
        RestLayer(restTarget?.takeIf { rest }?.let { RestUi(it.event, it.distanceM, ui.toGo?.timeS, ui.arriveAt) },
            onTap = ScreenTouch::note)
    }
    BackgroundDownloadDetails(vm, bg)
}

/** Moving time from the position ([remainingM] before the end) to the end of the line, rounded for display. */
internal fun toGoSeconds(times: com.onpath.app.time.RouteTimes?, remainingM: Double, f: com.onpath.app.time.Factors): Double =
    if (times == null) 0.0 else com.onpath.app.time.TimeEstimate.round(times.remainingFrom(times.lengthM - remainingM, f))

/**
 * The pace in plain words (owner): "Faster than planned uphill", "Slower than planned downhill",
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

/** The hint shown: the next roundabout when it comes before the next drawn turn (+ [ROUNDABOUT_SLACK_M]), else the turn. */
internal fun turnHint(turn: Pair<TurnKind, Double>?, roundabout: Pair<Int, Double>?): TurnHint? = when {
    roundabout != null && (turn == null || roundabout.second <= turn.second + ROUNDABOUT_SLACK_M) ->
        TurnHint(turn?.first ?: TurnKind.RIGHT, roundabout.second, roundabout = roundabout.first)
    turn != null -> TurnHint(turn.first, turn.second)
    else -> null
}

/** A roundabout this little after the drawn turn is the same place (its entry bend). */
internal const val ROUNDABOUT_SLACK_M = 40.0

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
