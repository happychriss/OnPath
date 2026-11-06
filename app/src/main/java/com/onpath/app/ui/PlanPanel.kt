package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import com.onpath.app.data.RoutingDataNotice
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.PlanPointRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalConfiguration

/** Everything the plan panel can ask for; defaults keep previews and tests short. */
class PlanCallbacks(
    val onUndo: () -> Unit = {},
    /** ✕ "Close plan" in the sheet's action row: the plan leaves the screen (asks first with unsaved changes). */
    val onClear: () -> Unit = {},
    /** Primary action: follow the route (free recording is in the ⋮ menu). */
    val onStartNavigation: () -> Unit = {},
    /** Primary action while a tour records: re-route from here and go back to Navigate. */
    val onContinue: () -> Unit = {},
    val onReverse: () -> Unit = {},
    val onRoundTrip: () -> Unit = {},
    /** Activity icon at the start of the sheet: opens the activity picker. */
    val onActivity: () -> Unit = {},
    /** Profile or difficulty badge tapped: route details (way types, surfaces, difficulty, large profile). */
    val onDetails: () -> Unit = {},
    /** Tap on time + distance: Time details (how the time was calculated). */
    val onTimeDetails: () -> Unit = {},
    /** Points section (full sheet): row [from] dropped at row [to] (hold ≡ and drag). */
    val onMovePoint: (from: Int, to: Int) -> Unit = { _, _ -> },
    /** Points section: ✕ of a row – deleted at once, Undo brings it back. */
    val onDeletePoint: (row: Int) -> Unit = {},
    /** Points section: row tapped – the map shows that point. */
    val onShowPoint: (row: Int) -> Unit = {},
    val onHighlight: (GeoPoint?) -> Unit = {},
    /** The sheet is being dragged (true) / let go (false) – map regions wait to join meanwhile. */
    val onSheetDrag: (Boolean) -> Unit = {},
    /** "Cancel" next to the routing bar (only while a route takes a while). */
    val onCancelRouting: () -> Unit = {},
    /** "Plan anyway" / "Calculate" of the not-routed notice. */
    val onPlanAnyway: () -> Unit = {},
    /** "Show" of the unreachable-point line: the map moves to the missing piece. */
    val onShowGap: () -> Unit = {},
    /** Tap on the amber warning line: the list of spots (or the whole route when there are none). */
    val onWarnings: () -> Unit = {},
    /** Tap on the grey "New route for …" line: it goes, the map shows the route. */
    val onRouteChanged: () -> Unit = {},
    val onResize: (PlanSize) -> Unit = {},
    /** Tour controls at the top of the sheet (only while a tour records). */
    val onPause: (Boolean) -> Unit = {},
    val onEndTour: () -> Unit = {},
)

/** Recording status shown at the top of the plan sheet during a tour. */
/** [recordOnly]: the route guidance was ended during the tour – the status line says "Recording only". */
data class TourState(val distanceM: Double, val elapsedS: Long, val paused: Boolean, val recordOnly: Boolean = false)

/**
 * Plan sheet steps (owner 2026-10-31): COLLAPSED = rolled up, ONE row "🚲 4 h 40 min · 94.2 km ⚠2 [▶ Start]" – the map
 * almost free; MINI = the summary (activity · time + distance, the route lines, ✕ Close · ⇅ Reverse · ↻ Round trip, the
 * Start button) – where the sheet opens after planning; COMPACT = + the large elevation profile with the finger ruler;
 * FULL = + the Points list. The primary button rides on the bottom edge from MINI on (COLLAPSED has its own small
 * Start). Dragged down to COLLAPSED the sheet stays there through route updates until the user opens it again.
 * Way types & surfaces are in the route details only.
 * During a tour (owner 2026-10-31) there is no COLLAPSED step: MINI is ONE row – "● Tour running · km · time" + a
 * compact [Continue] – so about two thirds of the map are free for holds; COMPACT adds Pause / End tour (one drag + one
 * tap) and the route summary.
 */
enum class PlanSize {
    FULL, COMPACT, MINI, COLLAPSED;

    fun bigger() = when (this) { COLLAPSED -> MINI; MINI -> COMPACT; else -> FULL }
    /** Handle tap: grow one step; on FULL go back to COMPACT. */
    fun tapped() = if (this == FULL) COMPACT else bigger()
    /** Index in the sheet's steps, smallest first (StepSheet). */
    val step: Int get() = when (this) { COLLAPSED -> 0; MINI -> 1; COMPACT -> 2; FULL -> 3 }

    companion object {
        fun ofStep(i: Int): PlanSize = when { i <= 0 -> COLLAPSED; i == 1 -> MINI; i == 2 -> COMPACT; else -> FULL }

        /** Back in Plan from Navigate: during a tour the sheet starts at its one-row step (owner 2026-10-31). */
        fun backInPlan(current: PlanSize, tourRunning: Boolean): PlanSize = if (tourRunning) MINI else current

        /** The tour ended: its one-row step becomes the usual profile step again (the plan sheet as before the tour). */
        fun afterTour(current: PlanSize): PlanSize = if (current == MINI) COMPACT else current
    }
}

data class PlanUi(
    val pointCount: Int,
    val route: PlannedRoute?,
    val routing: Boolean,
    val error: String?,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val activity: Activity,
    val size: PlanSize = PlanSize.COMPACT,
    /** Hiking level (SAC T1–T5): the activity chip shows "Hike · T2". */
    val sac: Int = com.onpath.app.data.LastActivity.DEFAULT_SAC,
    /** A tour records: the primary action is "Continue navigation" instead of Start; Clear ends the route guidance. */
    val tourRunning: Boolean = false,
    /** Name the plan was last saved or loaded as (null = never). */
    val savedName: String? = null,
    /** The plan differs from [savedName]'s points (RouteRules.isUnsaved). */
    val unsaved: Boolean = false,
    /**
     * The plan is a round trip: "↻ Rundtour" shows on (filled, ✓) and a tap takes the way back out again (owner
     * 2026-11-06, onpath.private#40 – a switch, not a greyed-out button).
     */
    val roundTrip: Boolean = false,
    /** "↻ Rundtour" can be switched now (on: ≥ 2 points not ending at the start; off: [roundTrip]). */
    val canRoundTrip: Boolean = true,
    /** "⇅ Umkehren" changes the plan – false for an out-and-back over one point (A → B → A): greyed out (#40). */
    val canReverse: Boolean = true,
    /** A tour records: its status + Continue / Pause / End sit at the top of this sheet (one surface). */
    val tour: TourState? = null,
    /** Rows of the Points section (full sheet), see PlanPoints.rows. */
    val points: List<PlanPointRow> = emptyList(),
    /** The route has been calculating for a while: show "Calculating route… Cancel". */
    val routingSlow: Boolean = false,
    /** Legs done / legs to route while calculating (PlanStatus.calculating) – the wait card's text; null = unknown. */
    val routeProgress: Pair<Int, Int>? = null,
    /** The wait bar's share 0..1 (legs done + the running leg's search, [WaitBar]); null = the running bar. */
    val routeShare: Float? = null,
    /** The plan is left without a route on purpose (Cancel with nothing to take back): notice + "Plan anyway". */
    val notRouted: NotRouted? = null,
    /** A plan point the route does not reach (PlanGaps.text): one red line + "Show", while it holds. */
    val gapText: String? = null,
    /** Something to know about the way (RouteWarnings: pushing, steps, steep): one amber line. */
    val warnText: String? = null,
    /** How many route hints [warnText] sums up (RouteWarnings.count): the ⚠ badge of the rolled-up row. */
    val warnCount: Int = 0,
    /** "New route for … – check it" after an activity change: one grey INFO line (not a warning). */
    val changedText: String? = null,
    /** "Planned in the browser with other routing – …" (a synced plan, another routing fingerprint): grey INFO, no tap. */
    val syncRoutingText: String? = null,
    /** A tap on the open map: "Gedrückt halten, um hier einen Punkt zu setzen." in the summary step (owner 2026-11-03). */
    val tapHint: Boolean = false,
    /** A tap on the plan's mode pill: the offline state explained in one line ([OfflineReady.explain]); null = none. */
    val offlineInfo: String? = null,
) {
    /** What Start does (the shown track has its own panel): navigate the line, wait for it, or only record. */
    val start: PlanStart get() = PlanStart.of(trackPoints = null, hasRoute = route != null, pointCount = pointCount)
    /** Without a point / with one point Start only records; while a route is being computed, wait for it. */
    val canStart: Boolean get() = start != PlanStart.WAIT_FOR_ROUTE
    /** The step the sheet shows – during a tour too it rolls up to one row (owner 2026-11-05). */
    val shownSize: PlanSize get() = size
    /** Rolled up to its one row. */
    val collapsed: Boolean get() = shownSize == PlanSize.COLLAPSED
}

/**
 * The plan bottom sheet, four steps of ONE body (StepSheet: composed once, a step is only how much
 * shows – dragging never rebuilds it): rolled up = one row (activity icon · time · distance · ⚠ count · small Start),
 * laid over the summary's first line; summary = activity · time + distance, the route lines and the action row; profile
 * = + ascent / descent, difficulty badge (→ route details) and the large elevation profile (slide a finger: the dot runs
 * along the route on the map); points = + the Points list (≡ drag, ✕ delete, tap → map). Way types and surfaces are in
 * the route details only. The ONE filled primary button rides on the sheet's bottom edge from the summary step on.
 */
@Composable
fun PlanPanel(ui: PlanUi, cb: PlanCallbacks, sheetState: StepSheetState? = null) {
    val state = sheetState ?: rememberStepSheetState(PlanSize.entries.size, ui.shownSize.step)
    val scroll = rememberScrollState()
    val size = ui.shownSize
    val full = size == PlanSize.FULL
    // leaving the points: the list goes back to its top, so the profile is what the smaller steps show
    LaunchedEffect(full) { if (!full && scroll.value > 0) scroll.animateScrollTo(0) }
    StepSheet(
        step = size.step,
        onStep = { cb.onResize(PlanSize.ofStep(it)) },
        onHandleTap = { cb.onResize(size.tapped()) },
        state = state,
        onDragging = cb.onSheetDrag,
        // the rolled-up row has its own small green button
        barFrom = PlanSize.MINI.step,
        // Plan is blue (owner 2026-11-03): a light blue sheet with a blue handle and top edge – also during a tour
        surfaceColor = OnPathColors.PlanTint, accent = OnPathColors.PlanAccent,
        // during a tour (owner 2026-11-06, C): the green way back to Navigate sits where "Los geht's" sits without one,
        // in every step – the sheet rests on it
        foot = if (ui.tour != null || ui.tourRunning) ({ BackToNavigationButton({ goClick(ui, cb) }) }) else null,
        bottomBar = { PlanBar(ui, cb) },
    ) {
        // the pulled-up sheet has ONE fixed height (owner): deleting / adding / moving points (or the routing
        // bar) never makes its top edge jump; the scrolling area takes what is left
        val bodyH = (LocalConfiguration.current.screenHeightDp * FULL_SHEET_FRACTION).dp - PRIMARY_BAR
        val sheet = this
        Column(Modifier.fillMaxWidth().height(bodyH)) { PlanBody(sheet, ui, cb, scroll, state::barShown) }
    }
}

/** The plan row's light blue and its dark blue words (owner 2026-11-05; ≥ 9:1). */
internal val PillBlueLight = Color(0xFFDDE7F4)
internal val PillBlueInk = Color(0xFF163C6B)

@Composable
private fun pillFill(enabled: Boolean): Color = PillBlueLight.copy(alpha = if (enabled) 1f else 0.5f)

private fun pillInk(enabled: Boolean): Color = if (enabled) PillBlueInk else PillBlueInk.copy(alpha = 0.38f)

@Composable
private fun PlanAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit,
                       on: Boolean = false, onDesc: String? = null) {
    val tint = if (on) OnPathColors.CardWhite else pillInk(enabled)
    // icon and word side by side (owner 2026-10-31: the stacked label made the row twice as high); a light pill so it
    // reads as a button, calm like the activity field (owner 2026-11-01); 40 dp to see, 48 dp to touch. [on]: a switch
    // that is on (Material 3 filter chip: filled, the sign becomes ✓) – "↻ Rundtour" on a round trip (#40)
    Row(Modifier.heightIn(min = 48.dp).padding(vertical = 4.dp).clip(CircleShape)
        .background(if (on) OnPathColors.PlanAccent else pillFill(enabled))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (on && onDesc != null) Modifier.clearAndSetSemantics {
            contentDescription = onDesc; role = Role.Button; selected = true; onClick { onClick(); true }
        } else Modifier)
        .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (on) Icons.Filled.Check else icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1,
            modifier = Modifier.padding(start = 6.dp))
    }
}

/** Share of the screen height the pulled-up plan sheet always takes (below the grab handle). */
internal const val FULL_SHEET_FRACTION = 0.62f
/** Height of the bottom row at the sheet's bottom (4 dp gap + 48 dp button), with and without a tour. */
private val PRIMARY_BAR = 52.dp
@Composable
private fun ColumnScope.PlanBody(sheet: StepSheetScope, ui: PlanUi, cb: PlanCallbacks, scroll: ScrollState,
                                 barShown: () -> Float) {
    val route = ui.route
    val full = ui.shownSize == PlanSize.FULL
    // What the rolled-up step hides is not for TalkBack / tests (it lies under the sheet's bottom edge)
    val belowStep = if (ui.collapsed) Modifier.clearAndSetSemantics { } else Modifier
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().then(belowStep)) { SummaryLines(ui, cb) }
        // the rolled-up row lies over the summary's first line and fades with the bar sliding in (one body, no rebuild)
        CollapsedRow(ui, cb, barShown, with(sheet) { Modifier.stepEnd(PlanSize.COLLAPSED.step) })
    }
    // the empty plan's hint belongs to the summary step: the sheet opens at it, so a first start sees how to begin
    // (owner 2026-11-02: "beim ersten Öffnen … sieht man es gar nicht")
    if (ui.pointCount == 0) Box(Modifier.fillMaxWidth().padding(top = 6.dp).then(belowStep)) {
        // during a tour (owner 2026-11-05): the tour goes on – new points, or back to it
        if (ui.tour != null || ui.tourRunning) TourGoesOnHint() else FirstPointHint()
    }
    // a tap on the open map (hold-only): the same hand says how to set a point – in the step the sheet opens at
    else if (ui.tapHint) Box(Modifier.fillMaxWidth().padding(top = 6.dp).then(belowStep)) { TapHoldHint() }
    if (ui.offlineInfo != null && ui.pointCount > 0)
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).then(belowStep)) { OfflineInfoLine(ui.offlineInfo) }
    // the summary step ends under the summary lines
    Spacer(with(sheet) { Modifier.stepEnd(PlanSize.MINI.step) })
    var viewport by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    Column(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { viewport = it }.then(belowStep)
        .verticalScroll(scroll, enabled = full)) {
        SubLine(ui, route)
        if (route != null) SavedLine(ui)
        // while a route is calculated the old profile stays where it is, faded and still (owner 2026-11-06: the sheet
        // went down and up on Reverse / Round trip – the profile, the badge and the lines vanished and came back)
        if (route != null) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).calculating(ui.routing)
                .semantics { contentDescription = Txt.s(Res.string.plan_elevation_profile) }) {
                // stable callbacks: a new PlanCallbacks (every sheet recomposition) must not recompose the profile
                val highlight = cb.onHighlight
                // the gradient legend lives on Route details only
                if (factsOneLine(route)) Box {
                    ElevationProfile(route.points, height = PROFILE_HEIGHT, gradient = true,
                        onHighlight = highlight, onRelease = remember(highlight) { { highlight(null) } })
                    // the difficulty sits on the profile's top right while the facts keep one line (else it ends them)
                    DifficultyBadge(rememberRating(route, ui.activity),
                        Modifier.align(Alignment.TopEnd)
                            .clickable(enabled = !ui.routing, role = Role.Button, onClickLabel = str(Res.string.plan_show_route_details), onClick = cb.onDetails))
                } else ElevationProfile(route.points, height = PROFILE_HEIGHT, gradient = true,
                    onHighlight = highlight, onRelease = remember(highlight) { { highlight(null) } })
            }
        }
        Spacer(with(sheet) { Modifier.height(4.dp).stepEnd(PlanSize.COMPACT.step) { scroll.value.toFloat() } })
        // below the profile step the list is hidden behind the button: not for touch, not for TalkBack / tests
        Box(if (full) Modifier else Modifier.clearAndSetSemantics { }) {
            PointsSection(
                ui.points,
                onMove = cb.onMovePoint, onDelete = cb.onDeletePoint, onShow = cb.onShowPoint,
                modifier = Modifier.padding(top = 4.dp),
                scroll = scroll, viewport = { viewport },
            )
        }
    }
}

/** The plan head ([PlanHead]: plan row · facts), then the route state lines under it (ERROR, WARNING, INFO). */
@Composable
private fun SummaryLines(ui: PlanUi, cb: PlanCallbacks) {
    val route = ui.route
    PlanHead(ui, cb)
    if (ui.routing && ui.routingSlow) {
        // a long calculation can be stopped (the last change is taken back)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(PlanStatus.calculating(ui.routeProgress), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                RoutingBar(ui.routeProgress, ui.routeShare, Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(CircleShape))
            }
            androidx.compose.material3.TextButton(onClick = cb.onCancelRouting) { Text(str(Res.string.common_cancel)) }
        }
    }
    if (!ui.routing && route == null && ui.pointCount >= 2 && ui.notRouted != null) NotRoutedBar(ui.notRouted, cb.onPlanAnyway)
    // route state, one line per kind: ERROR, WARNING, INFO
    val loadingNotice = RoutingDataNotice.isNotice(ui.error)
    val routeError = ui.error?.takeIf { !loadingNotice }
    when {
        // an unreachable point and a failed leg are the same kind: one red line (+N more)
        ui.gapText != null -> ErrorLine(if (routeError != null && !PlanGaps.hasMore(ui.gapText)) PlanGaps.withMore(ui.gapText, 1) else ui.gapText,
            onShow = cb.onShowGap)
        routeError != null -> ErrorLine(routeError)
    }
    ui.warnText?.let { Box(Modifier.calculating(ui.routing)) { WarnLine(it, cb.onWarnings) } }
    if (loadingNotice) InfoLine(ui.error!!, spinner = ui.error == RoutingDataNotice.LOADING)
    ui.changedText?.let { Box(Modifier.calculating(ui.routing)) { InfoLine(it, onTap = cb.onRouteChanged) } }
    ui.syncRoutingText?.let { Box(Modifier.calculating(ui.routing)) { InfoLine(it) } }
}

/**
 * The plan head (owner 2026-11-05, R5): the plan row [✕ Neu planen] [⇅ Umkehren] [↻ Rundtour] as light blue pills with
 * words (signs only when the words do not fit; from one point on), a hairline, then the route's facts: time (big, tap =
 * Time details) · km · ↑ ↓ – the difficulty on the profile's top right while the facts keep one line, else at the end of
 * their second line, never on the curve. The activity is a button on the map ([ActivityMapButton]); Save is in ☰.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PlanHead(ui: PlanUi, cb: PlanCallbacks) {
    val canClear = RouteRules.canClear(ui.pointCount)
    val two = ui.pointCount >= 2
    val clear = str(Res.string.plan_close_plan)
    val rev = str(Res.string.plan_reverse)
    val loop = str(Res.string.plan_round_trip)
    val loopOn = str(Res.string.plan_round_trip_on_desc)
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val style = MaterialTheme.typography.labelMedium
    if (ui.pointCount >= 1) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wordW = with(density) { listOf(clear, rev, loop).sumOf { tm.measure(it, style).size.width }.toDp() }
        // three pills (12 + 20 + 6 + word + 12) + room between
        val words = PlanRow.wordsFit(wordW.value, maxWidth.value)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (words) Arrangement.SpaceBetween else Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            if (words) {
                PlanAction(Icons.Filled.Close, clear, canClear, cb.onClear)
                PlanAction(Icons.Outlined.SwapVert, rev, two && ui.canReverse, cb.onReverse)
                PlanAction(Icons.Outlined.Loop, loop, ui.roundTrip || (two && ui.canRoundTrip), cb.onRoundTrip, on = ui.roundTrip, onDesc = loopOn)
            } else {
                PlanIconPill(Icons.Filled.Close, clear, canClear, cb.onClear)
                PlanIconPill(Icons.Outlined.SwapVert, rev, two && ui.canReverse, cb.onReverse)
                PlanIconPill(Icons.Outlined.Loop, if (ui.roundTrip) loopOn else loop, ui.roundTrip || (two && ui.canRoundTrip), cb.onRoundTrip,
                    on = ui.roundTrip)
            }
        }
    }
    // the hairline under the plan row; while a route is calculated the bar runs in its place (same 7 dp – nothing moves)
    if (ui.pointCount >= 1) Box(Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 1.dp).height(3.dp), contentAlignment = Alignment.Center) {
        if (PlanCalculating.barInHairline(ui.routing, ui.routingSlow))
            RoutingBar(ui.routeProgress, ui.routeShare, Modifier.fillMaxWidth().height(3.dp).clip(CircleShape))
        else HorizontalDivider(thickness = 1.dp, color = OnPathColors.PlanAccent.copy(alpha = 0.18f))
    }
    val route = ui.route
    if (route == null) { Title(ui, null, cb.onTimeDetails); return }
    val oneLine = factsOneLine(route)
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.align(Alignment.CenterVertically)) { Title(ui, route, cb.onTimeDetails) }
        Text(instrument("↑ ${Format.meters(route.ascentM)}  ↓ ${Format.meters(route.descentM)}", MaterialTheme.typography.bodyLarge),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.align(Alignment.CenterVertically))
        // the facts wrap anyway (a large font): the difficulty goes at the end of their second line, off the profile
        if (!oneLine) DifficultyBadge(rememberRating(route, ui.activity),
            Modifier.align(Alignment.CenterVertically).calculating(ui.routing)
                .clickable(enabled = !ui.routing, role = Role.Button, onClickLabel = str(Res.string.plan_show_route_details), onClick = cb.onDetails))
    }
}

/** Time + km + ↑ ↓ fit on one line of the sheet (then the difficulty sits on the profile, else in the facts). */
@Composable
private fun factsOneLine(route: PlannedRoute): Boolean {
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val t = MaterialTheme.typography
    val w = with(density) {
        // measured as drawn: big digits, small units (Instrument)
        (tm.measure(instrument(Format.duration(route.durationS), t.titleLarge), t.titleLarge).size.width +
            tm.measure(instrument(Format.distance(route.distanceM), t.titleMedium), t.titleMedium).size.width +
            tm.measure(instrument("↑ ${Format.meters(route.ascentM)}  ↓ ${Format.meters(route.descentM)}", t.bodyLarge), t.bodyLarge).size.width).toDp()
    }
    // the sheet's 16 dp sides; 8 dp inside the title, 10 dp between the pieces
    return PlanRow.factsOneLine(w.value, LocalConfiguration.current.screenWidthDp.toFloat())
}

/**
 * While a route is calculated the sheet keeps its height (owner 2026-11-06, Pixel: on Reverse / Round trip the sheet went
 * down and up): what is there stays, faded and not touchable; the bar runs in the plan row's hairline. Only the slow case
 * (text + Cancel, after seconds) takes room of its own.
 */
object PlanCalculating {
    const val FADED = 0.4f
    /** The bar sits in the hairline's place – unless the slow line (with its own bar) shows. */
    fun barInHairline(routing: Boolean, slow: Boolean): Boolean = routing && !slow
    /** Lines and profile stay while calculating (never hidden by it): what shows is the same in both states. */
    fun shows(hasThing: Boolean, @Suppress("UNUSED_PARAMETER") routing: Boolean): Boolean = hasThing
}

/** Faded and not touchable while a route is calculated ([PlanCalculating]); the layout stays the same. */
private fun Modifier.calculating(on: Boolean): Modifier = if (!on) this else this
    .graphicsLayer { alpha = PlanCalculating.FADED }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

/** The plan head's fit rules (pure, dp). */
object PlanRow {
    /** Three pills with words (each 12 + 20 + 6 + word + 12 dp) fit the row – else signs only. */
    fun wordsFit(wordsWidthDp: Float, maxWidthDp: Float): Boolean = 150f + wordsWidthDp <= maxWidthDp

    /** Time + km + ↑ ↓ ([textWidthDp] together, 18 dp between them) fit one line of the sheet (16 dp sides). */
    fun factsOneLine(textWidthDp: Float, screenWidthDp: Float): Boolean = textWidthDp + 18f <= screenWidthDp - 32f
}

/**
 * The rolled-up sheet (owner 2026-11-05, R5): ONE row `4 h 36 min · 12,5 km · ↑842 ↓310  ⚠2 [▶ Los geht's]` – no
 * activity sign (it is on the map), the hints' ⚠ count, a compact green button ("▶ Weiter geht's" during a tour; TalkBack:
 * the full label). Never cut: when it does not fit, ↑ ↓ go first, then the button moves under the time. A tap on the row
 * opens the summary step; laid over the summary and faded by [barShown] (1 = the summary's bar is fully in → gone).
 */
@Composable
private fun CollapsedRow(ui: PlanUi, cb: PlanCallbacks, barShown: () -> Float, stop: Modifier) {
    val on = ui.collapsed
    val route = ui.route
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - barShown() }
        .background(OnPathColors.PlanTint)
        .then(if (on) Modifier.clickable(role = Role.Button, onClickLabel = str(Res.string.plan_sheet_expand)) {
            cb.onResize(PlanSize.MINI)
        } else Modifier.clearAndSetSemantics { })) {
        Layout(content = {
            // 1: time · km, one piece
            when {
                route != null -> Text(androidx.compose.ui.text.buildAnnotatedString {
                    val st = MaterialTheme.typography.titleMedium
                    append(instrument(Format.duration(route.durationS).replace(' ', '\u00A0'), st))
                    withStyle(androidx.compose.ui.text.SpanStyle(color = variant)) {
                        append(Instrument.styled(" · " + Format.distance(route.distanceM).replace(' ', '\u00A0'), st,
                            numberWeight = FontWeight.Normal))
                    }
                }, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                ui.routing -> Text(PlanStatus.CALCULATING, style = MaterialTheme.typography.bodyMedium, color = variant)
                else -> Text(str(EmptyPlanTitle.of(ui)), style = MaterialTheme.typography.titleMedium)
            }
            // 2: ↑ ↓ (the first to go)
            if (route != null) Text(Instrument.styled(" · " + CollapsedFacts.elevation(route.ascentM, route.descentM),
                MaterialTheme.typography.bodyMedium, numberWeight = FontWeight.Normal),
                style = MaterialTheme.typography.bodyMedium, color = variant, maxLines = 1, softWrap = false)
            else Box {}
            // 3: ⚠ count + the green button
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!ui.routing && ui.warnText != null && ui.warnCount > 0) {
                    Icon(Icons.Outlined.Warning, null, tint = OnPathColors.Amber, modifier = Modifier.size(20.dp))
                    Text("${ui.warnCount}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        color = OnPathColors.Amber, modifier = Modifier.padding(start = 2.dp, end = 6.dp))
                }
                // during a tour no button (owner 2026-11-05/06: the green "Navigation ›" in the foot leads back); from two points
                // (a plain recording is in ☰ – owner 2026-11-06: "Los geht's ohne Route" in the sheet was confusing)
                if (ui.pointCount >= 2 && ui.tour == null && !ui.tourRunning) CompactGo(ui, cb, on)
            }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { ms, c ->
            val gap = 8.dp.roundToPx()
            val max = c.maxWidth
            val e = ms[2].measure(Constraints(maxWidth = max))
            val tw = ms[0].maxIntrinsicWidth(Constraints.Infinity)
            val uw = ms[1].maxIntrinsicWidth(Constraints.Infinity)
            val fit = CollapsedFacts.fit(tw, uw, e.width, gap, max)
            val withUp = fit == CollapsedFacts.Fit.ALL
            val beside = fit != CollapsedFacts.Fit.WRAP
            val t = ms[0].measure(Constraints(maxWidth = if (beside) max - e.width - gap else max))
            val u = if (withUp) ms[1].measure(Constraints(maxWidth = max)) else null
            val minH = c.minHeight
            if (beside) {
                val h = maxOf(t.height, e.height, minH)
                layout(max, h) {
                    t.placeRelative(0, (h - t.height) / 2)
                    u?.placeRelative(t.width, (h - u.height) / 2)
                    e.placeRelative(max - e.width, (h - e.height) / 2)
                }
            } else {
                val h = maxOf(t.height + e.height, minH)
                layout(max, h) { t.placeRelative(0, 0); e.placeRelative(max - e.width, t.height) }
            }
        }
        Spacer(stop)
    }
}

/** The rolled-up row's short forms (pure). */
object CollapsedFacts {
    /** How the rolled-up row fits: all in one line; without ↑ ↓ (the first to go); the button under the time. */
    enum class Fit { ALL, NO_ELEVATION, WRAP }

    /** Widths in px: time · km, ↑ ↓, the button (+ ⚠); nothing is ever cut. */
    fun fit(timeKm: Int, elevation: Int, end: Int, gap: Int, max: Int): Fit = when {
        timeKm + elevation + gap + end <= max -> Fit.ALL
        timeKm + gap + end <= max -> Fit.NO_ELEVATION
        else -> Fit.WRAP
    }

    /** "↑842 ↓310" – metres without the unit, the short form the owner chose for the rolled-up row (2026-11-05). */
    fun elevation(ascentM: Double, descentM: Double): String =
        "↑${Math.round(ascentM)}\u00A0↓${Math.round(descentM)}"
}

/** The rolled-up row's compact green button, 48 dp; outside the rolled-up step only drawn (no touch target). */
@Composable
private fun CompactGo(ui: PlanUi, cb: PlanCallbacks, active: Boolean) {
    val content: @Composable () -> Unit = {
        Icon(goIcon(ui), null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(goLabel(ui), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
    val desc = primaryLabel(ui)
    if (active) {
        Button(colors = ActionButtons.go(), onClick = { goClick(ui, cb) }, enabled = ui.tourRunning || ui.canStart,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 16.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc }) { content() }
    } else {
        Row(Modifier.heightIn(min = 48.dp).clip(CircleShape).background(OnPathColors.ActionGo)
            .padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onPrimary) { content() }
        }
    }
}

/**
 * The activity as a round button on the map (owner 2026-11-05, R5), top left under the search pill: the plan's light blue
 * like the map buttons, a green activity sign, for Hike a small "T2" under it; tap = the activity picker. TalkBack:
 * "Aktivität: Wandern · T2. Tippen zum Ändern".
 */
@Composable
fun ActivityMapButton(activity: Activity, sac: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val name = com.onpath.app.data.LastActivity.chipText(activity, sac)
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = Txt.s(Res.string.plan_activity_chip_desc, name) },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = CircleShape, color = OnPathColors.PlanTint, shadowElevation = 3.dp,
            modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(activity.icon, null, tint = OnPathColors.Green, modifier = Modifier.size(26.dp))
            }
        }
        if (activity == Activity.HIKE) Surface(shape = CircleShape, color = OnPathColors.PlanTint, shadowElevation = 2.dp,
            modifier = Modifier.padding(top = 3.dp)) {
            Text("T$sac", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = PillBlueInk,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }
}

/** The plan row's sign-only buttons (a large font): a light blue circle (40 dp) in a 48 dp touch target, TalkBack names it. */
@Composable
private fun PlanIconPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit,
                         on: Boolean = false) {
    Box(Modifier.size(48.dp).clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label; if (on) selected = true }, contentAlignment = Alignment.Center) {
        // a switch that is on: filled plan blue, white sign (the round trip sign stays – a ✓ alone would not say what)
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (on) OnPathColors.PlanAccent else pillFill(enabled)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (on) OnPathColors.CardWhite else pillInk(enabled), modifier = Modifier.size(20.dp))
        }
    }
}

/** The one full-width green button: "▶ Los geht's" / "▶ Weiter geht's"; TalkBack hears the full label with the activity. */
@Composable
private fun GoButton(ui: PlanUi, cb: PlanCallbacks) {
    val full = primaryLabel(ui)
    Button(colors = ActionButtons.go(), onClick = { goClick(ui, cb) }, enabled = ui.tourRunning || ui.canStart,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = full }) {
        Icon(goIcon(ui), null, Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(goLabel(ui), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false,
            modifier = Modifier.clearAndSetSemantics { })
    }
}

/**
 * The bar while a route is calculated (owner 2026-11-05): [share] = legs done + the running leg's search ([WaitBar]) –
 * it moves within one long leg too; without it legs done / legs to route ([PlanStatus.hasLegs]); else the running bar.
 */
@Composable
private fun RoutingBar(progress: Pair<Int, Int>?, share: Float?, modifier: Modifier) {
    // planning is blue (owner's colour rule 2026-11-06) – not the walnut base colour
    val c = OnPathColors.ActionEdit; val t = OnPathColors.ActionEdit.copy(alpha = 0.18f)
    when {
        share != null -> LinearProgressIndicator(progress = { share }, modifier = modifier, color = c, trackColor = t)
        PlanStatus.hasLegs(progress) -> LinearProgressIndicator(progress = { PlanStatus.share(progress!!) }, modifier = modifier, color = c, trackColor = t)
        else -> LinearProgressIndicator(modifier, color = c, trackColor = t)
    }
}

/** The large profile of the profile step. */
private val PROFILE_HEIGHT = 120.dp

/**
 * Bottom of the sheet (owner 2026-11-05, R5): ONE full-width green button "▶ Los geht's" ([GoButton]) from two points on
 * (the rolled-up row has its own); during a tour none here – the sheet's foot carries the green "Navigation ›"
 * ([BackToNavigationButton], owner 2026-11-06, C) in every step.
 */
@Composable
private fun ColumnScope.PlanBar(ui: PlanUi, cb: PlanCallbacks) {
    if (PlanBarRow.shown(ui)) {
        Spacer(Modifier.height(PlanBarRow.GAP))
        // rolled up, the bar lies below the screen's edge: the row's own button is the one for TalkBack / tests
        Box(if (ui.collapsed) Modifier.clearAndSetSemantics { } else Modifier) { GoButton(ui, cb) }
    } else Spacer(Modifier.height(8.dp))
}

/** Start, or back to the navigation during a tour (also resumes a paused recording). */
private fun goClick(ui: PlanUi, cb: PlanCallbacks) {
    val tour = ui.tour
    if (tour != null) { if (tour.paused) cb.onPause(false); cb.onContinue() }
    else if (ui.tourRunning) cb.onContinue() else cb.onStartNavigation()
}

/** Rules of the sheet's bottom row (pure). */
object PlanBarRow {
    /** Above the button (the sheet's foot gives the air below). */
    val GAP = 4.dp

    /**
     * Without a tour the button starts at two points; a plain recording without a plan is ☰ → "Ohne Plan aufzeichnen"
     * (flow map H1, owner 2026-11-06: "Los geht's ohne Route" in the sheet was confusing – hidden in the menu, rarely
     * needed). During a tour none: the green "Navigation ›" in the sheet's foot leads back (owner 2026-11-06, C).
     */
    fun shown(ui: PlanUi): Boolean = ui.tour == null && !ui.tourRunning && ui.pointCount >= 2
}

/** The plan has no route on purpose: says why, and calculates it on request (stays until then – no timeout). */
@Composable
private fun NotRoutedBar(why: NotRouted, onPlanAnyway: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(PlanStatus.notRoutedText(why), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(colors = ActionButtons.editText(), onClick = onPlanAnyway) { Text(PlanStatus.notRoutedAction(why)) }
    }
}

/**
 * ERROR line: the route does not do what was asked – a plan point it does not reach or a leg the router could not do
 * ("Point 1 → 2: no path found"). Red ✕ + bold red text, in the summary (visible in the small step), no OK and never
 * self-dismissing – it goes when the plan changes. "Show" = the map goes there.
 */
@Composable
private fun ErrorLine(text: String, onShow: (() -> Unit)? = null) {
    val red = OnPathColors.Error
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Cancel, null, tint = red, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
            color = red, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onShow != null) androidx.compose.material3.TextButton(onClick = onShow) {
            Text(str(Res.string.common_show), fontWeight = FontWeight.Bold, color = red)
        }
    }
}

/**
 * INFO line: good to know, nothing to do – grey text, no box, a small spinner while something loads. Tappable when
 * [onTap] is set (then a ›).
 */
@Composable
internal fun InfoLine(text: String, spinner: Boolean = false, onTap: (() -> Unit)? = null) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(MaterialTheme.shapes.small)
        .then(if (onTap != null) Modifier.clickable(role = Role.Button) { onTap() } else Modifier)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (spinner) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = grey)
        else Icon(Icons.Outlined.Info, null, tint = grey, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = grey,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onTap != null) Icon(Icons.Filled.ChevronRight, null, tint = grey, modifier = Modifier.size(20.dp))
    }
}

/**
 * WARNING line: ONE amber line when the route has something to know ("1.2 km pushing ·
 * steps · 2 sections above T2") or changed with the activity. The whole line is the tap target (→ list of spots).
 */
@Composable
internal fun WarnLine(text: String, onTap: () -> Unit) {
    val amber = OnPathColors.Amber
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .clickable(role = Role.Button, onClickLabel = str(Res.string.plan_warn_show_spots)) { onTap() }
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Warning, null, tint = amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = amber,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = amber, modifier = Modifier.size(20.dp))
    }
}

/**
 * Visible save state instead of a message: "Saved as “Name”", or "“Name” · changed, not saved". Only a line (ANZEIGE):
 * the name is changed in Save's dialog or My routes → ⋮ → Rename (owner 2026-10-31, no rename in place).
 */
@Composable
private fun SavedLine(ui: PlanUi) {
    val name = ui.savedName ?: return
    val saved = !ui.unsaved
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (saved) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
            tint = if (saved) OnPathColors.Good else grey, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (saved) str(Res.string.plan_saved_as, name) else str(Res.string.plan_changed_not_saved, name),
            style = MaterialTheme.typography.bodySmall, color = grey,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** First line: time + distance (or "Plan a route"); a tap on them = Time details (how the time was calculated). */
@Composable
private fun Title(ui: PlanUi, route: PlannedRoute?, onTimeDetails: () -> Unit) {
    if (route == null) {
        Text(str(EmptyPlanTitle.of(ui)), style = MaterialTheme.typography.titleLarge, maxLines = 1)
        return
    }
    Row(Modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
        .clickable(role = Role.Button, onClickLabel = str(Res.string.plan_time_details), onClick = onTimeDetails),
        verticalAlignment = Alignment.CenterVertically) {
        Text(instrument(Format.duration(route.durationS), MaterialTheme.typography.titleLarge),
            style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(instrument(Format.distance(route.distanceM), MaterialTheme.typography.titleMedium),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 1.dp))
    }
}

/**
 * The title of a plan without a route (owner 2026-11-06, start screen): an empty plan with no tour asks
 * "Wohin soll’s gehen?" – the question is the title, "Neuer Plan" was a label nobody needed; otherwise (one point,
 * during a tour) "Neuer Plan" as before.
 */
internal object EmptyPlanTitle {
    fun asks(pointCount: Int, touring: Boolean): Boolean = pointCount == 0 && !touring
    fun of(ui: PlanUi) = if (asks(ui.pointCount, ui.tour != null || ui.tourRunning)) Res.string.plan_hint_first_point_question
        else Res.string.plan_plan_a_route
}

/**
 * The empty plan (owner 2026-11-02, for a first start; 2026-11-06 the question moved up into the title): ✋ "Halte dein
 * Ziel auf der Karte gedrückt – oder such es oben in der Suchleiste." – both ways in one sentence. The hand is the one
 * sign for "hold the map".
 */
@Composable
private fun FirstPointHint() {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.TouchApp, null, tint = OnPathColors.PlanAccent, modifier = Modifier.padding(top = 1.dp).size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(str(Res.string.plan_hint_first_point), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The empty plan during a tour: "Tour läuft weiter – setz neue Punkte oder tippe unten auf Weiter geht’s." */
@Composable
private fun TourGoesOnHint() {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.TouchApp, null, tint = OnPathColors.PlanAccent, modifier = Modifier.padding(top = 1.dp).size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(str(Res.string.plan_hint_tour_goes_on), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A tap on the open map in Plan sets nothing (hold-only gestures, owner): ✋ "Gedrückt halten, um hier einen Punkt zu
 * setzen." (owner 2026-11-03, novice fix) – an ANZEIGE line, it stays until the user acts ([AppViewModel.tapHoldHint]).
 */
@Composable
private fun TapHoldHint() {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(Icons.Filled.TouchApp, null, tint = OnPathColors.PlanAccent, modifier = Modifier.padding(top = 1.dp).size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(str(Res.string.plan_hint_tap_hold), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The plan's mode pill was tapped (owner 2026-11-03, "Ohne Netz ✓ / ⟳ / ⚠"): what it means for this route, in one calm
 * ANZEIGE line – it stays until the plan changes, the pill is tapped again or the mode changes ([AppViewModel.offlineInfo]).
 */
@Composable
private fun OfflineInfoLine(text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(Icons.Filled.CloudDone, null, tint = OnPathColors.PlanAccent, modifier = Modifier.padding(top = 1.dp).size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Without a route: what to do next or that it is being calculated (the facts of a route are in [PlanHead]). */
@Composable
private fun SubLine(ui: PlanUi, route: PlannedRoute?) {
    if (route != null) return
    if (ui.notRouted != null && !ui.routing && ui.pointCount >= 2) return // the not-routed notice says it
    if (ui.pointCount == 0) return // the hint sits in the summary step (FirstPointHint, PlanBody)
    // "Calculating the route…" only while a request runs (not after Cancel)
    Text(PlanStatus.noRouteLine(ui.pointCount, ui.routing, ui.notRouted),
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun stopColor(i: Int, count: Int): Color = when {
    i == 0 -> OnPathColors.Good
    i == count - 1 && count > 1 -> OnPathColors.Bad
    else -> OnPathColors.Route
}

/** The green button's word: "Los geht's" / during a tour "Navigation" (owner 2026-11-05, F). */
internal fun goLabel(ui: PlanUi): String = Txt.s(if (ui.tourRunning) Res.string.plan_bar_carry_on else Res.string.plan_bar_go)

/** ‹ back to the navigation during a tour, ▶ to start, ● when it only records (no line to follow). */
private fun goIcon(ui: PlanUi): androidx.compose.ui.graphics.vector.ImageVector = when {
    ui.start == PlanStart.RECORD_ONLY -> Icons.Filled.FiberManualRecord
    else -> Icons.Filled.PlayArrow
}

/**
 * TalkBack's words for the green button: "Los geht's · Schloss Nordkirchen" (owner 2026-11-02, onpath.private#16: the
 * destination's name; unnamed destination or a round trip: the activity, "Los geht's · Wandern") / "Weiter · Gravelbike";
 * no point or only one: "Los geht’s · ohne Route" (owner 2026-11-03, fewer terms: Planen / Unterwegs, Route / Tour).
 */
internal fun primaryLabel(ui: PlanUi): String = when {
    ui.tourRunning -> Txt.s(Res.string.plan_carry_on_for, ui.activity.navName)
    ui.start == PlanStart.RECORD_ONLY -> Txt.s(Res.string.plan_start_recording_for)
    else -> Txt.s(Res.string.plan_start_navigation_for, destinationName(ui.points) ?: ui.activity.navName)
}

/** The destination's own name (a place, a search result), or null when it has none (then "Ziel") or the plan is a loop. */
internal fun destinationName(points: List<com.onpath.app.routing.PlanPointRow>): String? {
    val goal = points.lastOrNull()?.takeIf { it.role == com.onpath.app.routing.PointRole.GOAL && !it.loop } ?: return null
    return goal.name.takeIf { it.isNotBlank() && it != Txt.s(Res.string.plan_point_destination) }
}

