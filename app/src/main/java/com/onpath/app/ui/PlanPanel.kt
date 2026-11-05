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
    /** Save the (edited) plan – the blue tile during a tour; null = no plan to save. */
    val onSaveRoute: (() -> Unit)? = null,
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

        /** During a tour the sheet has no rolled-up step: its smallest step is the one-row tour status (MINI). */
        fun duringTour(size: PlanSize, tour: Boolean): PlanSize = if (tour && size == COLLAPSED) MINI else size

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
    /** The route already ends at its start: "Round trip" is greyed out (smart menu, no message). */
    val roundTrip: Boolean = false,
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
    /** The step the sheet shows: during a tour there is no rolled-up step (PlanSize.duringTour). */
    val shownSize: PlanSize get() = PlanSize.duringTour(size, tour != null)
    /** Rolled up to its one row (never during a tour). */
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
    val tour = ui.tour != null
    // leaving the points: the list goes back to its top, so the profile is what the smaller steps show
    LaunchedEffect(full) { if (!full && scroll.value > 0) scroll.animateScrollTo(0) }
    StepSheet(
        step = size.step,
        onStep = { cb.onResize(PlanSize.duringTour(PlanSize.ofStep(it), tour)) },
        onHandleTap = { cb.onResize(size.tapped()) },
        state = state,
        onDragging = cb.onSheetDrag,
        // the rolled-up row has its own small Start; during a tour every step has the (empty) bar
        barFrom = if (tour) 0 else PlanSize.MINI.step,
        // Plan is blue (owner 2026-11-03): a light blue sheet with a blue handle and top edge – also during a tour
        surfaceColor = OnPathColors.PlanTint, accent = OnPathColors.PlanAccent,
        bottomBar = { PlanBar(ui, cb) },
    ) {
        // the pulled-up sheet has ONE fixed height (owner): deleting / adding / moving points (or the routing
        // bar) never makes its top edge jump; the scrolling area takes what is left
        val bodyH = (LocalConfiguration.current.screenHeightDp * FULL_SHEET_FRACTION).dp -
            if (tour && LocalPlanBarStyle.current == PlanBarStyle.TODAY) TOUR_BAR else PRIMARY_BAR
        val sheet = this
        Column(Modifier.fillMaxWidth().height(bodyH)) { PlanBody(sheet, ui, cb, scroll, state::barShown) }
    }
}

/**
 * The sheet's first line: activity chip · time + distance. The chip always names the activity ("Wandern · T2", owner
 * 2026-10-30) and time and distance are always shown in full (owner rule) – when both do not fit beside each other
 * (German, a long day, a large phone font), time and distance move to their own line under the chip. No ⋯ and no Save
 * here any more (owner 2026-10-31): Save is ☰ → Save route, the route actions are the row of [PlanActions].
 */
@Composable
private fun HeaderLine(chip: @Composable () -> Unit, title: @Composable () -> Unit) {
    Layout(content = { chip(); title() }) { ms, c ->
        val gap = 8.dp.roundToPx()
        val below = 2.dp.roundToPx()
        val max = c.maxWidth
        val ch = ms[0].measure(Constraints(maxWidth = max))
        val besideW = (max - ch.width - gap).coerceAtLeast(0)
        val oneLine = ms[1].maxIntrinsicWidth(Constraints.Infinity) <= besideW
        val t = ms[1].measure(Constraints(maxWidth = if (oneLine) besideW else max))
        val line1 = maxOf(ch.height, if (oneLine) t.height else 0)
        val h = if (oneLine) line1 else line1 + below + t.height
        layout(max, h) {
            ch.placeRelative(0, (line1 - ch.height) / 2)
            if (oneLine) t.placeRelative(ch.width + gap, (line1 - t.height) / 2) else t.placeRelative(0, line1 + below)
        }
    }
}

/**
 * The sheet's route actions (owner 2026-10-31, the ⋯ menu is gone): three small icons, each with its short label beside
 * it (never icon-only) – ✕ Close (asks first with unsaved changes; the saved route stays in My routes), ⇅ Reverse,
 * ↻ Round trip. Each ≥ 48 dp, spread evenly; a word is never cut – with a large phone font the row wraps (FlowRow).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PlanActions(ui: PlanUi, cb: PlanCallbacks) {
    val style = LocalPlanBarStyle.current
    // owner 2026-11-05: the plan-edit actions as pills in the plan's blue – V5 / V6 light ("blau hell"), V7 filled ("blau voll")
    val tonal = when (style) {
        PlanBarStyle.CLEAR_IN_PILLS, PlanBarStyle.CLEAR_TOP_RIGHT -> PillLook.BLUE_LIGHT
        PlanBarStyle.CLEAR_IN_PILLS_FILLED -> PillLook.BLUE_FILLED
        else -> PillLook.PLAIN
    }
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
        // "Neu planen" is in the bottom row's blue group (owner 2026-11-05, F); here only while that row is not shown
        // (one point without a tour), so it is always there exactly once
        when (style) {
            // V5 / V5a: ✕ as the row's first item, icon only (TalkBack "Plan löschen") – with and without a tour
            PlanBarStyle.CLEAR_IN_PILLS, PlanBarStyle.CLEAR_IN_PILLS_PLAIN, PlanBarStyle.CLEAR_IN_PILLS_FILLED ->
                PlanClearIcon(RouteRules.canClear(ui.pointCount), tonal, cb.onClear)
            // V6: ✕ sits at the header line's right end
            PlanBarStyle.CLEAR_TOP_RIGHT -> Unit
            else -> {
                val inBar = if (style == PlanBarStyle.TODAY) ui.tour != null else PlanBarRow.shown(ui)
                if (!inBar) PlanAction(Icons.Filled.Close, str(Res.string.plan_close_plan), RouteRules.canClear(ui.pointCount), cb.onClear)
            }
        }
        PlanAction(Icons.Outlined.SwapVert, str(Res.string.plan_reverse), ui.pointCount >= 2, cb.onReverse, tonal)
        PlanAction(Icons.Outlined.Loop, str(Res.string.plan_round_trip), ui.pointCount >= 2 && !ui.roundTrip, cb.onRoundTrip, tonal)
    }
}

/** How the plan-edit pills look: today a faint grey; light blue + dark blue words; plan blue + white words. */
enum class PillLook { PLAIN, BLUE_LIGHT, BLUE_FILLED }

/** The light blue of V5 / V6 and its dark blue words (sketch: --planc / #163c6b, ≥ 9:1). */
private val PillBlueLight = Color(0xFFDDE7F4)
private val PillBlueInk = Color(0xFF163C6B)

@Composable
private fun pillFill(enabled: Boolean, look: PillLook): Color = when (look) {
    PillLook.PLAIN -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.7f else 0.35f)
    PillLook.BLUE_LIGHT -> PillBlueLight.copy(alpha = if (enabled) 1f else 0.5f)
    PillLook.BLUE_FILLED -> if (enabled) OnPathColors.ActionEdit else OnPathColors.ActionEdit.copy(alpha = 0.25f)
}

@Composable
private fun pillInk(enabled: Boolean, look: PillLook): Color {
    val c = when (look) {
        PillLook.PLAIN -> MaterialTheme.colorScheme.onSurface
        PillLook.BLUE_LIGHT -> PillBlueInk
        PillLook.BLUE_FILLED -> OnPathColors.CardWhite
    }
    return if (enabled) c else if (look == PillLook.BLUE_FILLED) c.copy(alpha = 0.7f) else c.copy(alpha = 0.38f)
}

/** V5 / V6: ✕ without a word – a 40 dp circle in a 48 dp touch target; TalkBack hears "Plan löschen" (plan_clear_desc). */
@Composable
private fun PlanClearIcon(enabled: Boolean, tonal: PillLook, onClick: () -> Unit) {
    val label = str(Res.string.plan_clear_desc)
    Box(Modifier.size(48.dp).clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }
        .padding(4.dp).clip(CircleShape)
        .background(pillFill(enabled, tonal)),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Close, null, tint = pillInk(enabled, tonal), modifier = Modifier.size(20.dp))
    }
}

/** V6 (Google Maps sheet logic): the header line with a 48 dp ✕ "Plan löschen" at its right end; other styles: the line alone. */
@Composable
private fun HeaderWithClear(ui: PlanUi, cb: PlanCallbacks, line: @Composable () -> Unit) {
    if (LocalPlanBarStyle.current != PlanBarStyle.CLEAR_TOP_RIGHT) { line(); return }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f)) { line() }
        PlanClearIcon(RouteRules.canClear(ui.pointCount), PillLook.BLUE_LIGHT, cb.onClear)
    }
}

@Composable
private fun PlanAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit,
                       tonal: PillLook = PillLook.PLAIN) {
    val tint = pillInk(enabled, tonal)
    // icon and word side by side (owner 2026-10-31: the stacked label made the row twice as high); a light pill so it
    // reads as a button, calm like the activity field (owner 2026-11-01); 40 dp to see, 48 dp to touch
    Row(Modifier.heightIn(min = 48.dp).padding(vertical = 4.dp).clip(CircleShape)
        .background(pillFill(enabled, tonal))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1,
            modifier = Modifier.padding(start = 6.dp))
    }
}

/** Share of the screen height the pulled-up plan sheet always takes (below the grab handle). */
internal const val FULL_SHEET_FRACTION = 0.62f
/** Height of the bottom row at the sheet's bottom (4 dp gap + 48 dp button) – the proposals: with and without a tour. */
private val PRIMARY_BAR = 52.dp
/** Above and below today's tile bar during a tour. */
private val TOUR_BAR_GAP = 6.dp
/** Today's bottom bar during a tour: the tiles + their gaps. */
private val TOUR_BAR = TourActions.BAR_HEIGHT + TOUR_BAR_GAP * 2

@Composable
private fun ColumnScope.PlanBody(sheet: StepSheetScope, ui: PlanUi, cb: PlanCallbacks, scroll: ScrollState,
                                 barShown: () -> Float) {
    val route = ui.route
    val full = ui.shownSize == PlanSize.FULL
    // During a tour: the tour first, then the route – one surface (owner 2026-10-31). The first step is ONE row (status +
    // compact Continue), the map stays about two thirds free; the next step adds Pause / End tour and the route below.
    // What the first step hides is not for TalkBack / tests either (it lies under the sheet's bottom edge) – the same for
    // the rolled-up row without a tour.
    val tourMini = ui.tour != null && ui.shownSize == PlanSize.MINI
    val belowStep = if (tourMini || ui.collapsed) Modifier.clearAndSetSemantics { } else Modifier
    if (ui.tour != null) {
        // the first step is the tile bar alone (owner 2026-11-02: Continue · Save route · New plan, the same bar as
        // Navigate's, PlanBar) – the map stays free above it; no rolled-up step during a tour (PlanSize.duringTour).
        // After "Neu planen" the plan is empty: the first step shows how to begin again (owner 2026-11-03)
        if (ui.pointCount == 0) Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) { FirstPointHint() }
        else {
            // the summary row in the first step, above the bar (owner 2026-11-03: "dass man Fahrrad und 4 Minuten sieht")
            if (r3()) R3Head(ui, cb) else HeaderWithClear(ui, cb) {
                HeaderLine(chip = { ActivityButton(ui.activity, ui.sac, cb.onActivity) }, title = { Title(ui, ui.route, cb.onTimeDetails) })
            }
            if (ui.tapHint) Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) { TapHoldHint() }
            ui.offlineInfo?.let { t -> Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) { OfflineInfoLine(t) } }
        }
        Spacer(with(sheet) { Modifier.height(4.dp).stepEnd(PlanSize.COLLAPSED.step).stepEnd(PlanSize.MINI.step) })
    }
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().then(belowStep)) { SummaryLines(ui, cb, header = ui.tour == null || ui.pointCount == 0) }
        // the rolled-up row lies over the summary's first line and fades with the bar sliding in (one body, no rebuild)
        if (ui.tour == null) CollapsedRow(ui, cb, barShown, with(sheet) { Modifier.stepEnd(PlanSize.COLLAPSED.step) })
    }
    // the empty plan's hint belongs to the summary step: the sheet opens at it, so a first start sees how to begin
    // (owner 2026-11-02: "beim ersten Öffnen … sieht man es gar nicht")
    if (ui.pointCount == 0 && ui.tour == null) Box(Modifier.fillMaxWidth().padding(top = 6.dp).then(belowStep)) { FirstPointHint() }
    // a tap on the open map (hold-only): the same hand says how to set a point – in the step the sheet opens at
    else if (ui.tapHint && ui.tour == null) Box(Modifier.fillMaxWidth().padding(top = 6.dp).then(belowStep)) { TapHoldHint() }
    if (ui.offlineInfo != null && ui.tour == null && ui.pointCount > 0)
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).then(belowStep)) { OfflineInfoLine(ui.offlineInfo) }
    // without a tour the summary step ends under the summary lines
    if (ui.tour == null) Spacer(with(sheet) { Modifier.stepEnd(PlanSize.MINI.step) })
    var viewport by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    Column(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { viewport = it }.then(belowStep)
        .verticalScroll(scroll, enabled = full)) {
        SubLine(ui, route, cb.onDetails)
        if (route != null) SavedLine(ui)
        if (route != null && !ui.routing) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = Txt.s(Res.string.plan_elevation_profile) }) {
                // stable callbacks: a new PlanCallbacks (every sheet recomposition) must not recompose the profile
                val highlight = cb.onHighlight
                // the gradient legend lives on Route details only
                ElevationProfile(route.points, height = PROFILE_HEIGHT, gradient = true,
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

/**
 * Line 1 (activity · time + distance), the route state lines under it (ERROR, WARNING, INFO), then the route actions
 * (✕ Close plan · ⇅ Reverse · ↻ Round trip) from one point on.
 */
@Composable
private fun SummaryLines(ui: PlanUi, cb: PlanCallbacks, header: Boolean = true) {
    val route = ui.route
    // Line 1: activity · time + distance (Undo and Redo are map buttons; a tap on the time = Time details). During a tour
    // it sits in the first step above the tile bar (PlanBody) – not twice.
    if (header && r3()) R3Head(ui, cb)
    else if (header) HeaderWithClear(ui, cb) {
        HeaderLine(
            chip = { ActivityButton(ui.activity, ui.sac, cb.onActivity) },
            title = { Title(ui, route, cb.onTimeDetails) },
        )
    }
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
    } else if (ui.routing) RoutingBar(ui.routeProgress, ui.routeShare, Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(CircleShape))
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
    ui.warnText?.takeIf { !ui.routing }?.let { WarnLine(it, cb.onWarnings) }
    if (loadingNotice) InfoLine(ui.error!!, spinner = ui.error == RoutingDataNotice.LOADING)
    ui.changedText?.takeIf { !ui.routing }?.let { InfoLine(it, onTap = cb.onRouteChanged) }
    ui.syncRoutingText?.takeIf { !ui.routing }?.let { InfoLine(it) }
    if (ui.pointCount >= 1 && !r3()) PlanActions(ui, cb)
}

/**
 * Round 3 (owner 2026-11-05): group 1 = the plan's settings in light blue (activity · ✕ · ⇅ · ↻ · 🔖), a hairline, group 2
 * = the route's facts without buttons (time big · km · ↑ ↓ · difficulty; the highest point is on the profile's axis).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun R3Head(ui: PlanUi, cb: PlanCallbacks) {
    val b = LocalPlanBarStyle.current == PlanBarStyle.R3_B
    val canClear = RouteRules.canClear(ui.pointCount)
    val two = ui.pointCount >= 2
    val chip = @Composable { ActivityButton(ui.activity, ui.sac, cb.onActivity, container = PillBlueLight, ink = PillBlueInk) }
    if (!b) {
        // A: one row – the chip, then four signs; with a large font the signs move to the next line
        // the 48 dp touch targets round 40 dp circles already keep 8 dp apart: no extra spacing
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth()) {
            chip()
            Spacer(Modifier.width(4.dp))
            PlanIconPill(Icons.Filled.Close, str(Res.string.plan_clear_desc), canClear, cb.onClear)
            PlanIconPill(Icons.Outlined.SwapVert, str(Res.string.plan_reverse), two, cb.onReverse)
            PlanIconPill(Icons.Outlined.Loop, str(Res.string.plan_round_trip), two && !ui.roundTrip, cb.onRoundTrip)
            PlanIconPill(Icons.Outlined.BookmarkAdd, str(Res.string.plan_save_route), cb.onSaveRoute != null, { cb.onSaveRoute?.invoke() },
                dot = ui.unsaved)
        }
    } else {
        // B: the chip, ✕ and 🔖 on the right; under it ⇅ Umkehren · ↻ Rundtour with words
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            chip()
            Spacer(Modifier.weight(1f))
            PlanIconPill(Icons.Filled.Close, str(Res.string.plan_clear_desc), canClear, cb.onClear)
            PlanIconPill(Icons.Outlined.BookmarkAdd, str(Res.string.plan_save_route), cb.onSaveRoute != null, { cb.onSaveRoute?.invoke() },
                dot = ui.unsaved)
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanAction(Icons.Outlined.SwapVert, str(Res.string.plan_reverse), two, cb.onReverse, PillLook.BLUE_LIGHT)
            PlanAction(Icons.Outlined.Loop, str(Res.string.plan_round_trip), two && !ui.roundTrip, cb.onRoundTrip, PillLook.BLUE_LIGHT)
        }
    }
    // the groups apart: a light hairline in the plan's blue
    HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 2.dp), thickness = 1.dp, color = OnPathColors.PlanAccent.copy(alpha = 0.18f))
    R3Facts(ui, cb)
}

/** Group 2: time (big, tap = Time details) · km · ↑ ↓ · difficulty badge – one line when it fits, else it wraps. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun R3Facts(ui: PlanUi, cb: PlanCallbacks) {
    val route = ui.route
    if (route == null) { Title(ui, null, cb.onTimeDetails); return }
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.align(Alignment.CenterVertically)) { Title(ui, route, cb.onTimeDetails) }
        Text("↑ ${Format.meters(route.ascentM)} ↓ ${Format.meters(route.descentM)}", style = MaterialTheme.typography.bodyMedium,
            color = variant, maxLines = 1, modifier = Modifier.align(Alignment.CenterVertically))
        if (!ui.routing) DifficultyBadge(rememberRating(route, ui.activity),
            Modifier.align(Alignment.CenterVertically)
                .clickable(role = Role.Button, onClickLabel = str(Res.string.plan_show_route_details), onClick = cb.onDetails))
    }
}

/** Group 1's sign-only buttons: a light blue circle (40 dp) in a 48 dp touch target, TalkBack names it; [dot] = unsaved. */
@Composable
private fun PlanIconPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit,
                         dot: Boolean = false) {
    Box(Modifier.size(48.dp).clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(pillFill(enabled, PillLook.BLUE_LIGHT)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = pillInk(enabled, PillLook.BLUE_LIGHT), modifier = Modifier.size(20.dp))
        }
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(10.dp).clip(CircleShape)
            .background(OnPathColors.Green))
    }
}

/** Round 3, group 3: ONE full-width green button – "▶ Los geht's" / "‹ Navigation"; TalkBack hears the full label. */
@Composable
private fun GoFullButton(ui: PlanUi, cb: PlanCallbacks) {
    val full = primaryLabel(ui)
    Button(onClick = { goClick(ui, cb) }, enabled = ui.tourRunning || ui.canStart,
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
    when {
        share != null -> LinearProgressIndicator(progress = { share }, modifier = modifier)
        PlanStatus.hasLegs(progress) -> LinearProgressIndicator(progress = { PlanStatus.share(progress!!) }, modifier = modifier)
        else -> LinearProgressIndicator(modifier)
    }
}

/** The large profile of the profile step. */
private val PROFILE_HEIGHT = 120.dp

/**
 * Bottom of the sheet (owner 2026-11-05, onpath.private#30 = F: "the double row takes too much plan space – make it
 * compact"): ONE row as high as one button. Left the filled green [GoButton], only as wide as its words – "▶ Los geht's",
 * during a tour "‹ Navigation"; right, filling the rest, ONE blue outlined group [PlanGroup] "Speichern | Neu planen".
 * Filled = navigation, outlined = plan; no red. Every word on one line – with a large font the group moves to its own
 * line under the button ([PlanBarRow]). Without a tour from two points on (the rolled-up step has its own Start), during
 * a tour in every step.
 */
@Composable
private fun ColumnScope.PlanBar(ui: PlanUi, cb: PlanCallbacks) {
    val style = LocalPlanBarStyle.current
    if (style == PlanBarStyle.TODAY) { TodayBar(ui, cb); return }
    if (PlanBarRow.shown(ui)) {
        Spacer(Modifier.height(PlanBarRow.GAP))
        val canNew = RouteRules.canClear(ui.pointCount)
        // rolled up, the bar lies below the screen's edge: the row's own Start is the one for TalkBack / tests
        Box(if (ui.tour == null && ui.collapsed) Modifier.clearAndSetSemantics { } else Modifier) {
            when (style) {
                PlanBarStyle.F_GROUP -> PlanBarLayout(go = { GoButton(ui, cb) }, group = {
                    PlanGroup(onSave = cb.onSaveRoute, canNewPlan = canNew, onNewPlan = cb.onClear)
                })
                PlanBarStyle.MAPS_PILLS -> MapsPillsRow(ui, cb, canNew)
                PlanBarStyle.ICON_BUTTONS -> IconButtonsRow(ui, cb, canNew)
                PlanBarStyle.SPLIT -> SplitGoButton(ui, cb, canNew)
                PlanBarStyle.CLEAR_IN_PILLS, PlanBarStyle.CLEAR_IN_PILLS_PLAIN, PlanBarStyle.CLEAR_TOP_RIGHT,
                PlanBarStyle.CLEAR_IN_PILLS_FILLED -> GoSaveRow(ui, cb)
                PlanBarStyle.R3_A, PlanBarStyle.R3_B -> GoFullButton(ui, cb)
                PlanBarStyle.TODAY -> Unit
            }
        }
    } else Spacer(Modifier.height(8.dp))
}

/** Design proposal 2026-11-05 (owner: compact, Material 3 + Google Maps logic): the bottom row's look, set only by the screenshot test. */
enum class PlanBarStyle { TODAY, F_GROUP, MAPS_PILLS, ICON_BUTTONS, SPLIT, CLEAR_IN_PILLS, CLEAR_IN_PILLS_PLAIN, CLEAR_TOP_RIGHT, CLEAR_IN_PILLS_FILLED,
    /** Round 3: plan settings · route facts · one button – A: one row of signs, B: two rows with words. */
    R3_A, R3_B }

/** Round 3 (owner 2026-11-05): the summary in three groups. */
@Composable
private fun r3(): Boolean = LocalPlanBarStyle.current.let { it == PlanBarStyle.R3_A || it == PlanBarStyle.R3_B }

/** V5 / V6: the green button takes the rest of the width, one blue outlined "Speichern" as wide as its words. */
@Composable
private fun GoSaveRow(ui: PlanUi, cb: PlanCallbacks) {
    val blue = OnPathColors.ActionEdit
    val full = primaryLabel(ui)
    val go = goLabel(ui)
    val save = str(Res.string.plan_bar_save)
    val style = MaterialTheme.typography.labelLarge
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // both words always whole on one line: when they do not fit with their signs (a large font), the signs go and the
        // padding shrinks
        val goW = with(density) { tm.measure(go, style).size.width.toDp() }
        val saveW = with(density) { tm.measure(save, style).size.width.toDp() }
        val compact = (16.dp + 20.dp + 6.dp + goW + 24.dp) + 8.dp + (12.dp + 18.dp + 6.dp + saveW + 16.dp) > maxWidth
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { goClick(ui, cb) }, enabled = ui.tourRunning || ui.canStart,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (compact) 8.dp else 16.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = full }) {
                if (!compact) { Icon(goIcon(ui), null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
                Text(go, style = style, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics { })
            }
            val canSave = cb.onSaveRoute != null
            androidx.compose.material3.OutlinedButton(onClick = { cb.onSaveRoute?.invoke() }, enabled = canSave,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, if (canSave) blue else blue.copy(alpha = 0.3f)),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = blue),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = if (compact) 12.dp else 12.dp,
                    end = if (compact) 12.dp else 16.dp),
                modifier = Modifier.heightIn(min = 48.dp)) {
                if (!compact) { Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                Text(save, style = style, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Today's bar unless a screenshot test provides another proposal. */
val LocalPlanBarStyle = androidx.compose.runtime.staticCompositionLocalOf { PlanBarStyle.TODAY }

/** Today's bar: the one primary button; during a tour the tile bar ([TourEditBar]). */
@Composable
private fun ColumnScope.TodayBar(ui: PlanUi, cb: PlanCallbacks) {
    val t = ui.tour
    if (t != null) {
        Spacer(Modifier.height(TOUR_BAR_GAP))
        TourEditBar(t.paused, cb.onPause, cb.onContinue, cb.onSaveRoute, canNewPlan = ui.pointCount > 0, onNewPlan = cb.onClear,
            Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(TOUR_BAR_GAP))
    } else if (!ui.tourRunning && ui.pointCount >= 2) {
        Spacer(Modifier.height(4.dp))
        // rolled up, the bar lies below the screen's edge: the row's own Start is the one for TalkBack / tests
        Box(if (ui.collapsed) Modifier.clearAndSetSemantics { } else Modifier) { PrimaryAction(ui, cb) }
    } else Spacer(Modifier.height(8.dp))
}

/** Today's one filled button of the sheet. */
@Composable
private fun PrimaryAction(ui: PlanUi, cb: PlanCallbacks) {
    // the button names the activity (owner: a wrong activity is a disaster)
    Button(onClick = if (ui.tourRunning) cb.onContinue else cb.onStartNavigation, enabled = ui.tourRunning || ui.canStart,
        modifier = Modifier.fillMaxWidth().height(48.dp)) {
        // without a line it only records: the record dot of ☰ → Start recording, not the navigation arrow
        Icon(if (!ui.tourRunning && ui.start == PlanStart.RECORD_ONLY) Icons.Filled.FiberManualRecord else Icons.Filled.Navigation, null)
        Spacer(Modifier.width(8.dp))
        Text(primaryLabel(ui), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** V2 (Google Maps directions sheet): a green filled pill + two separate blue outlined pill chips, scrolls sideways. */
@Composable
private fun MapsPillsRow(ui: PlanUi, cb: PlanCallbacks, canNew: Boolean) {
    val blue = OnPathColors.ActionEdit
    val full = primaryLabel(ui)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { goClick(ui, cb) }, enabled = ui.tourRunning || ui.canStart,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 16.dp),
            modifier = Modifier.semantics { contentDescription = full }) {
            Icon(if (ui.tourRunning) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.Filled.Navigation, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(goLabel(ui), maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics { })
        }
        PlanChip(Icons.Outlined.BookmarkAdd, str(Res.string.plan_bar_save), cb.onSaveRoute != null, blue) { cb.onSaveRoute?.invoke() }
        PlanChip(Icons.Outlined.RestartAlt, str(Res.string.plan_close_plan), canNew, blue, cb.onClear)
    }
}

@Composable
private fun PlanChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, blue: Color,
                     onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, enabled = enabled,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) blue else blue.copy(alpha = 0.3f)),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = blue),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 12.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, maxLines = 1, softWrap = false)
    }
}

/** V3: the green button with its words takes the width; Save and New plan as two round tonal blue icon buttons. */
@Composable
private fun IconButtonsRow(ui: PlanUi, cb: PlanCallbacks, canNew: Boolean) {
    val full = primaryLabel(ui)
    val tonal = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = OnPathColors.ActionEdit.copy(alpha = 0.14f), contentColor = OnPathColors.ActionEdit)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { goClick(ui, cb) }, enabled = ui.tourRunning || ui.canStart,
            modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = full }) {
            Icon(goIcon(ui), null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(goLabel(ui), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false,
                modifier = Modifier.clearAndSetSemantics { })
        }
        androidx.compose.material3.FilledTonalIconButton(onClick = { cb.onSaveRoute?.invoke() }, enabled = cb.onSaveRoute != null,
            colors = tonal, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.BookmarkAdd, str(Res.string.plan_bar_save))
        }
        androidx.compose.material3.FilledTonalIconButton(onClick = cb.onClear, enabled = canNew, colors = tonal,
            modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.RestartAlt, str(Res.string.plan_close_plan))
        }
    }
}

/** V4 (Material 3 Expressive split button): one green "▶ Los geht's | ▾" – the menu holds Speichern / Neu planen. */
@Composable
private fun SplitGoButton(ui: PlanUi, cb: PlanCallbacks, canNew: Boolean) {
    val full = primaryLabel(ui)
    var open by remember { mutableStateOf(false) }
    val big = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 4.dp, bottomEnd = 4.dp)
    val small = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 24.dp, bottomEnd = 24.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Button(onClick = { goClick(ui, cb) }, enabled = ui.tourRunning || ui.canStart, shape = big,
            modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = full }) {
            Icon(goIcon(ui), null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(goLabel(ui), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false,
                modifier = Modifier.clearAndSetSemantics { })
        }
        Box {
            Button(onClick = { open = true }, shape = small, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.width(56.dp).height(48.dp)) {
                Icon(Icons.Filled.ArrowDropDown, str(Res.string.plan_bar_save) + " · " + str(Res.string.plan_close_plan))
            }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                androidx.compose.material3.DropdownMenuItem(text = { Text(str(Res.string.plan_save_route)) },
                    leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, null) }, enabled = cb.onSaveRoute != null,
                    onClick = { open = false; cb.onSaveRoute?.invoke() })
                androidx.compose.material3.DropdownMenuItem(text = { Text(str(Res.string.plan_close_plan)) },
                    leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) }, enabled = canNew,
                    onClick = { open = false; cb.onClear() })
            }
        }
    }
}

/** Start, or back to the navigation during a tour (also resumes a paused recording). */
private fun goClick(ui: PlanUi, cb: PlanCallbacks) {
    val tour = ui.tour
    if (tour != null) { if (tour.paused) cb.onPause(false); cb.onContinue() }
    else if (ui.tourRunning) cb.onContinue() else cb.onStartNavigation()
}

/** The bar's one row: [go] at its own width, [group] the rest – or, when its words do not fit there, a line of its own. */
@Composable
private fun PlanBarLayout(go: @Composable () -> Unit, group: @Composable () -> Unit) {
    Layout(content = { go(); group() }) { ms, c ->
        val gap = PlanBarRow.GAP_BETWEEN.roundToPx()
        val line = PlanBarRow.LINE_GAP.roundToPx()
        val max = c.maxWidth
        val g = ms[0].measure(Constraints(maxWidth = max))
        val groupNeeds = ms[1].maxIntrinsicWidth(Constraints.Infinity)
        val wraps = PlanBarRow.wraps(g.width, groupNeeds, gap, max)
        // beside it the group fills the rest; on its own line the whole width
        val w = if (wraps) max else (max - g.width - gap).coerceAtLeast(0)
        val pw = ms[1].measure(Constraints(minWidth = w, maxWidth = w))
        val h = if (wraps) g.height + line + pw.height else maxOf(g.height, pw.height)
        layout(max, h) {
            if (wraps) { g.placeRelative(0, 0); pw.placeRelative(0, g.height + line) }
            else { g.placeRelative(0, (h - g.height) / 2); pw.placeRelative(g.width + gap, (h - pw.height) / 2) }
        }
    }
}

/** Rules of the sheet's bottom row (pure). */
object PlanBarRow {
    /** Above the row (the sheet's foot gives the air below). */
    val GAP = 4.dp
    /** Between the green button and the group. */
    val GAP_BETWEEN = 8.dp
    /** Between the button and the group when the group has its own line (large font). */
    val LINE_GAP = 8.dp

    /** Without a tour the bar starts at two points (one point: ✕ Neu planen stays in the pill row); during a tour always. */
    fun shown(ui: PlanUi): Boolean = ui.tour != null || (!ui.tourRunning && ui.pointCount >= 2)

    /** The group's words do not fit beside the button: it goes to its own line (never cut, never two lines). */
    fun wraps(goWidth: Int, groupWidth: Int, gap: Int, maxWidth: Int): Boolean = goWidth + gap + groupWidth > maxWidth
}

/**
 * The rolled-up sheet (owner 2026-10-31): ONE row `🚲 4 h 40 min · 94.2 km ⚠2 [▶ Start]` – activity icon, time ·
 * distance (never cut: the distance wraps under the time with a large font), a small ⚠ count while the route has hints
 * (red ✕ for a point it does not reach / a failed leg) and a compact green Start (≥ 48 dp; TalkBack hears the full
 * "Start navigation · Bike"). A tap on the row opens the summary step; dragging up does too. Laid over the summary's
 * first line and faded by [barShown] (1 = the summary step's bar is fully in → the row is gone); it takes touches only
 * while the sheet is rolled up, so in the other steps the chip and the time under it work.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CollapsedRow(ui: PlanUi, cb: PlanCallbacks, barShown: () -> Float, stop: Modifier) {
    val on = ui.collapsed
    val route = ui.route
    Column(Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - barShown() }
        .background(MaterialTheme.colorScheme.surface)
        .then(if (on) Modifier.clickable(role = Role.Button, onClickLabel = str(Res.string.plan_sheet_expand)) {
            cb.onResize(PlanSize.MINI)
        } else Modifier.clearAndSetSemantics { })) {
        CollapsedLine(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ui.activity.icon, com.onpath.app.data.LastActivity.name(ui.activity, ui.sac), tint = OnPathColors.Green,
                        modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    // time and distance never cut (owner): each one piece (no break inside), the distance moves under the
                    // time when both do not fit beside each other
                    androidx.compose.foundation.layout.FlowRow {
                        when {
                            route != null -> {
                                Text(Format.duration(route.durationS).replace(' ', '\u00A0'),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    modifier = Modifier.padding(end = 8.dp))
                                Text(Format.distance(route.distanceM).replace(' ', '\u00A0'),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            ui.routing -> Text(PlanStatus.CALCULATING, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> Text(str(Res.string.plan_plan_a_route), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            },
            end = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val routeError = ui.error?.takeIf { !RoutingDataNotice.isNotice(it) }
                    if (ui.gapText != null || routeError != null) {
                        Icon(Icons.Outlined.Cancel, null, tint = OnPathColors.Error, modifier = Modifier.padding(end = 6.dp).size(20.dp))
                    }
                    if (!ui.routing && ui.warnText != null && ui.warnCount > 0) {
                        val desc = Txt.p(Res.plurals.plan_hint_count, ui.warnCount, ui.warnCount)
                        Row(Modifier.padding(end = 6.dp).semantics(mergeDescendants = true) { contentDescription = desc },
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Warning, null, tint = OnPathColors.Amber, modifier = Modifier.size(20.dp))
                            Text("${ui.warnCount}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                color = OnPathColors.Amber, modifier = Modifier.padding(start = 2.dp))
                        }
                    }
                    if (ui.pointCount >= 2) {
                        Spacer(Modifier.width(4.dp))
                        CompactStart(ui, cb, on)
                    }
                }
            },
        )
        Spacer(stop)
    }
}

/**
 * The rolled-up row's layout: [title] (icon · time · distance) on the left, [end] (badges + Start) on the right, 48 dp
 * high. When the title cannot keep its pieces whole beside the end (a large phone font), the end goes to a second line,
 * right-aligned – a word is never cut.
 */
@Composable
private fun CollapsedLine(title: @Composable () -> Unit, end: @Composable () -> Unit) {
    Layout(content = { title(); end() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { ms, c ->
        val gap = 8.dp.roundToPx()
        val max = c.maxWidth
        val e = ms[1].measure(Constraints(maxWidth = max))
        val besideW = (max - e.width - gap).coerceAtLeast(0)
        val beside = ms[0].minIntrinsicWidth(Constraints.Infinity) <= besideW
        val t = ms[0].measure(Constraints(maxWidth = if (beside) besideW else max))
        val minH = c.minHeight
        if (beside) {
            val h = maxOf(t.height, e.height, minH)
            layout(max, h) {
                t.placeRelative(0, (h - t.height) / 2)
                e.placeRelative(max - e.width, (h - e.height) / 2)
            }
        } else {
            val h = maxOf(t.height + e.height, minH)
            layout(max, h) {
                t.placeRelative(0, 0)
                e.placeRelative(max - e.width, t.height)
            }
        }
    }
}

/**
 * The rolled-up row's small green Start: icon + "Start", 48 dp high. Outside the rolled-up step it is only drawn (the row
 * is faded out): no touch target that could block the time / chip under it.
 */
@Composable
private fun CompactStart(ui: PlanUi, cb: PlanCallbacks, active: Boolean) {
    val icon = if (ui.start == PlanStart.RECORD_ONLY) Icons.Filled.FiberManualRecord else Icons.Filled.Navigation
    val content: @Composable () -> Unit = {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(str(Res.string.plan_start_short), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
    val desc = primaryLabel(ui)
    if (active) {
        Button(onClick = cb.onStartNavigation, enabled = ui.canStart,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc }) {
            content()
        }
    } else {
        Row(Modifier.heightIn(min = 48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onPrimary) { content() }
        }
    }
}

/** The plan has no route on purpose: says why, and calculates it on request (stays until then – no timeout). */
@Composable
private fun NotRoutedBar(why: NotRouted, onPlanAnyway: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(PlanStatus.notRoutedText(why), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = onPlanAnyway) { Text(PlanStatus.notRoutedAction(why)) }
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
        Text(str(Res.string.plan_plan_a_route), style = MaterialTheme.typography.titleLarge, maxLines = 1)
        return
    }
    Row(Modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
        .clickable(role = Role.Button, onClickLabel = str(Res.string.plan_time_details), onClick = onTimeDetails),
        verticalAlignment = Alignment.CenterVertically) {
        Text(Format.duration(route.durationS), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(Format.distance(route.distanceM),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 1.dp))
    }
}

/**
 * The empty plan (owner 2026-11-02, for a first start): ✋ "Wohin soll’s gehen? Halte dein Ziel auf der Karte gedrückt –
 * oder such es oben in der Suchleiste." – the question bold, both ways in one sentence, as high as the old line. The
 * hand is the one sign for "hold the map".
 */
@Composable
private fun FirstPointHint() {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.TouchApp, null, tint = OnPathColors.PlanAccent, modifier = Modifier.padding(top = 1.dp).size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                append(str(Res.string.plan_hint_first_point_question))
            }
            append(" ")
            append(str(Res.string.plan_hint_first_point))
        }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Read-only status, second line: ascent / descent / highest point + difficulty badge, or what to do next. */
@Composable
private fun SubLine(ui: PlanUi, route: PlannedRoute?, onDetails: () -> Unit = {}) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    if (route == null) {
        if (ui.notRouted != null && !ui.routing && ui.pointCount >= 2) return // the not-routed notice says it
        if (ui.pointCount == 0) return // the hint sits in the summary step (FirstPointHint, PlanBody)
        // "Calculating the route…" only while a request runs (not after Cancel)
        Text(PlanStatus.noRouteLine(ui.pointCount, ui.routing, ui.notRouted),
            style = MaterialTheme.typography.bodyLarge, color = variant)
        return
    }
    if (r3()) return // round 3: the facts are group 2 (R3Facts)
    Row(verticalAlignment = Alignment.CenterVertically) {
        val top = route.points.mapNotNull { it.ele }.maxOrNull()
        Text(
            "↑ ${Format.meters(route.ascentM)}  ↓ ${Format.meters(route.descentM)}" +
                (top?.let { "  ·  " + str(Res.string.plan_max_height, Format.meters(it)) } ?: ""),
            style = MaterialTheme.typography.bodyLarge, color = variant, maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!ui.routing) {
            Spacer(Modifier.width(8.dp))
            DifficultyBadge(rememberRating(route, ui.activity),
                Modifier.clickable(role = Role.Button, onClickLabel = str(Res.string.plan_show_route_details), onClick = onDetails))
        }
    }
}

internal fun stopColor(i: Int, count: Int): Color = when {
    i == 0 -> OnPathColors.Good
    i == count - 1 && count > 1 -> OnPathColors.Bad
    else -> OnPathColors.Route
}

/**
 * The one filled (green) button of the sheet, only as wide as its words (owner 2026-11-05, F): "▶ Los geht's", during a
 * tour "‹ Navigation" (back to it; also resumes a paused recording). TalkBack hears the full [primaryLabel] – the
 * destination or the activity ("Los geht's · Schloss Nordkirchen", "Weiter · Gravelbike"); the chip shows the activity.
 */
@Composable
private fun GoButton(ui: PlanUi, cb: PlanCallbacks) {
    val tour = ui.tour
    val full = primaryLabel(ui)
    Button(onClick = {
        if (tour != null) { if (tour.paused) cb.onPause(false); cb.onContinue() }
        else if (ui.tourRunning) cb.onContinue() else cb.onStartNavigation()
    }, enabled = ui.tourRunning || ui.canStart,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 18.dp),
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = full }) {
        // without a line it only records: the record dot, not ▶
        Icon(goIcon(ui), null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(goLabel(ui), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
            modifier = Modifier.clearAndSetSemantics { })
    }
}

/** The green button's word: "Los geht's" / during a tour "Navigation" (owner 2026-11-05, F). */
internal fun goLabel(ui: PlanUi): String = Txt.s(if (ui.tourRunning) Res.string.plan_bar_navigation else Res.string.plan_bar_go)

/** ‹ back to the navigation during a tour, ▶ to start, ● when it only records (no line to follow). */
private fun goIcon(ui: PlanUi): androidx.compose.ui.graphics.vector.ImageVector = when {
    ui.tourRunning -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
    ui.start == PlanStart.RECORD_ONLY -> Icons.Filled.FiberManualRecord
    else -> Icons.Filled.PlayArrow
}

/**
 * The plan's blue group (owner 2026-11-05, F): ONE outlined pill split in two – "Speichern" (Save's name dialog; greyed
 * without a plan to save) | "Neu planen" (the one clear path: a saved plan clears, an unsaved one asks "Discard
 * changes?" first; greyed when empty). Each half ≥ 48 dp, its word on one line, both halves equally wide.
 */
@Composable
private fun PlanGroup(onSave: (() -> Unit)?, canNewPlan: Boolean, onNewPlan: () -> Unit) {
    val blue = OnPathColors.ActionEdit
    Surface(shape = CircleShape, color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.5.dp, blue)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            GroupHalf(str(Res.string.plan_bar_save), onSave != null, blue, Modifier.weight(1f)) { onSave?.invoke() }
            Box(Modifier.width(1.5.dp).fillMaxHeight().background(blue))
            GroupHalf(str(Res.string.plan_close_plan), canNewPlan, blue, Modifier.weight(1f), onNewPlan)
        }
    }
}

@Composable
private fun GroupHalf(label: String, enabled: Boolean, blue: Color, modifier: Modifier, onClick: () -> Unit) {
    val ink = if (enabled) blue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1,
            softWrap = false)
    }
}

/**
 * TalkBack's words for the green button: "Los geht's · Schloss Nordkirchen" (owner 2026-11-02, onpath.private#16: the
 * destination's name; unnamed destination or a round trip: the activity, "Los geht's · Wandern") / "Weiter · Gravelbike";
 * no point or only one: "Los geht’s · ohne Route" (owner 2026-11-03, fewer terms: Planen / Unterwegs, Route / Tour).
 */
internal fun primaryLabel(ui: PlanUi): String = when {
    ui.tourRunning -> Txt.s(Res.string.plan_continue_navigation_for, ui.activity.navName)
    ui.start == PlanStart.RECORD_ONLY -> Txt.s(Res.string.plan_start_recording_for)
    else -> Txt.s(Res.string.plan_start_navigation_for, destinationName(ui.points) ?: ui.activity.navName)
}

/** The destination's own name (a place, a search result), or null when it has none (then "Ziel") or the plan is a loop. */
internal fun destinationName(points: List<com.onpath.app.routing.PlanPointRow>): String? {
    val goal = points.lastOrNull()?.takeIf { it.role == com.onpath.app.routing.PointRole.GOAL && !it.loop } ?: return null
    return goal.name.takeIf { it.isNotBlank() && it != Txt.s(Res.string.plan_point_destination) }
}

/** The route's activity as a small tonal icon button with ▾ (opens the activity picker). */
@Composable
private fun ActivityButton(activity: Activity, sac: Int, onClick: () -> Unit, container: Color? = null, ink: Color? = null) {
    val name = com.onpath.app.data.LastActivity.chipText(activity, sac) // "Hike · T2"
    androidx.compose.material3.Surface(onClick = onClick, shape = MaterialTheme.shapes.small,
        color = container ?: MaterialTheme.colorScheme.secondaryContainer,
        contentColor = ink ?: MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = Txt.s(Res.string.plan_activity_chip_desc, name) }) {
        Row(Modifier.padding(start = 8.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(activity.icon, null, tint = OnPathColors.Green, modifier = Modifier.size(24.dp))
            // short names ("Gravel", "E-MTB"; Activity.chip) so time and distance keep their room
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
