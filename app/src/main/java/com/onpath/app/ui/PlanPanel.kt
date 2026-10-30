package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Save
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import com.onpath.app.data.RoutingDataNotice
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
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
    val onClear: () -> Unit = {},
    /** Primary action: follow the route (free recording is in the ⋮ menu). */
    val onStartNavigation: () -> Unit = {},
    /** Primary action while a tour records: re-route from here and go back to Navigate. */
    val onContinue: () -> Unit = {},
    val onReverse: () -> Unit = {},
    val onRoundTrip: () -> Unit = {},
    /** Activity icon at the start of the sheet: opens the activity picker. */
    val onActivity: () -> Unit = {},
    /** Route ⋯: Save (name / overwrite choice). */
    val onSave: () -> Unit = {},
    /** Route ⋯ → Share plan: the plan as a plan.onpath.earth link (A-005), from two points. */
    val onShare: () -> Unit = {},
    /** Profile or difficulty badge tapped: route details (way types, surfaces, difficulty, large profile). */
    val onDetails: () -> Unit = {},
    /** Route ⋯ → Time details (how the time was calculated). */
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
data class TourState(val distanceM: Double, val elapsedS: Long, val paused: Boolean)

/**
 * Plan sheet steps: MINI = summary only (time, distance), COMPACT = + the large elevation profile
 * with the finger ruler (default), FULL = + the Points list (Reverse / Round trip). The primary button stays at the
 * bottom in every step. Way types & surfaces are in the route details only.
 */
enum class PlanSize {
    FULL, COMPACT, MINI;

    fun bigger() = if (this == MINI) COMPACT else FULL
    /** Handle tap: grow one step; on FULL go back to COMPACT. */
    fun tapped() = if (this == FULL) COMPACT else bigger()
    /** Index in the sheet's steps, smallest first (StepSheet). */
    val step: Int get() = when (this) { MINI -> 0; COMPACT -> 1; FULL -> 2 }

    companion object {
        fun ofStep(i: Int): PlanSize = when { i <= 0 -> MINI; i == 1 -> COMPACT; else -> FULL }
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
    /** The plan is left without a route on purpose (Cancel with nothing to take back): notice + "Plan anyway". */
    val notRouted: NotRouted? = null,
    /** A plan point the route does not reach (PlanGaps.text): one red line + "Show", while it holds. */
    val gapText: String? = null,
    /** Something to know about the way (RouteWarnings: pushing, steps, steep): one amber line. */
    val warnText: String? = null,
    /** "New route for … – check it" after an activity change: one grey INFO line (not a warning). */
    val changedText: String? = null,
) {
    /** Without a route navigation just records; while a route is being computed, wait for it. */
    val canStart: Boolean get() = pointCount < 2 || route != null
}

/**
 * The plan bottom sheet, three steps of ONE body (StepSheet: composed once, a step is only how much
 * shows – dragging never rebuilds it): small = the summary line (activity · time + distance · ⋯); profile = + ascent /
 * descent, difficulty badge (→ route details) and the large elevation profile (slide a finger: the dot runs along the
 * route on the map); points = + the Points list (≡ drag, ✕ delete, tap → map; Reverse / Round trip). Way types and
 * surfaces are in the route details only. The ONE filled primary button rides on the sheet's bottom edge in every step.
 */
@Composable
fun PlanPanel(ui: PlanUi, cb: PlanCallbacks, menuInitiallyOpen: Boolean = false, sheetState: StepSheetState? = null) {
    val state = sheetState ?: rememberStepSheetState(PlanSize.entries.size, ui.size.step)
    val scroll = rememberScrollState()
    val full = ui.size == PlanSize.FULL
    // leaving the points: the list goes back to its top, so the profile is what the smaller steps show
    LaunchedEffect(full) { if (!full && scroll.value > 0) scroll.animateScrollTo(0) }
    StepSheet(
        step = ui.size.step,
        onStep = { cb.onResize(PlanSize.ofStep(it)) },
        onHandleTap = { cb.onResize(ui.size.tapped()) },
        state = state,
        onDragging = cb.onSheetDrag,
        bottomBar = { PlanBar(ui, cb) },
    ) {
        // the pulled-up sheet has ONE fixed height (owner): deleting / adding / moving points (or the routing
        // bar) never makes its top edge jump; the scrolling area takes what is left
        val bodyH = (LocalConfiguration.current.screenHeightDp * FULL_SHEET_FRACTION).dp - PRIMARY_BAR
        val sheet = this
        Column(Modifier.fillMaxWidth().height(bodyH)) { PlanBody(sheet, ui, cb, menuInitiallyOpen, scroll) }
    }
}

/**
 * The sheet's first line: activity chip · time + distance · [Save] ⋯. The chip always names the activity ("Wandern · T2",
 * owner 2026-10-30) and time and distance are always shown in full (owner rule) – when both do not fit beside each
 * other (German, a long day, a large phone font), time and distance move to their own line under the chip. [save] /
 * [saveIcon]: the Save button with its label, or only its icon when chip + label + ⋯ do not fit in one line (large
 * font); both null = no Save button (RouteRules.saveAtHand).
 */
@Composable
private fun HeaderLine(
    chip: @Composable () -> Unit, title: @Composable () -> Unit, overflow: @Composable () -> Unit,
    save: (@Composable () -> Unit)? = null, saveIcon: (@Composable () -> Unit)? = null,
) {
    Layout(content = { chip(); title(); overflow(); save?.invoke(); saveIcon?.invoke() }) { ms, c ->
        val gap = 8.dp.roundToPx()
        val below = 2.dp.roundToPx()
        val max = c.maxWidth
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val ov = ms[2].measure(loose)
        // the Save button: with its label when the chip still fits whole beside it, else only the icon
        val sv = if (ms.size < 5) null else {
            val chipW = ms[0].maxIntrinsicWidth(Constraints.Infinity)
            val full = ms[3].measure(loose)
            if (chipW + gap + full.width + ov.width <= max) full else ms[4].measure(loose)
        }
        val svW = sv?.let { it.width + gap / 2 } ?: 0
        val ch = ms[0].measure(Constraints(maxWidth = (max - ov.width - svW - gap).coerceAtLeast(0)))
        val besideW = (max - ch.width - gap - svW - ov.width).coerceAtLeast(0)
        val oneLine = ms[1].maxIntrinsicWidth(Constraints.Infinity) <= besideW
        val t = ms[1].measure(Constraints(maxWidth = if (oneLine) besideW else max))
        val line1 = maxOf(ch.height, ov.height, sv?.height ?: 0, if (oneLine) t.height else 0)
        val h = if (oneLine) line1 else line1 + below + t.height
        layout(max, h) {
            ch.placeRelative(0, (line1 - ch.height) / 2)
            ov.placeRelative(max - ov.width, (line1 - ov.height) / 2)
            sv?.placeRelative(max - ov.width - svW, (line1 - sv.height) / 2)
            if (oneLine) t.placeRelative(ch.width + gap, (line1 - t.height) / 2) else t.placeRelative(0, line1 + below)
        }
    }
}

/**
 * The sheet's Save button (RouteRules.saveAtHand): green tonal "Save" – the one filled button stays Start navigation.
 * [iconOnly] = the narrow form for a large phone font (TalkBack still hears "Save route").
 */
@Composable
private fun SaveButton(enabled: Boolean, onClick: () -> Unit, iconOnly: Boolean) {
    val colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
    if (iconOnly) {
        androidx.compose.material3.FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
            Icon(Icons.Outlined.Save, contentDescription = str(Res.string.plan_save_route))
        }
    } else androidx.compose.material3.FilledTonalButton(onClick = onClick, enabled = enabled, colors = colors,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 14.dp),
        modifier = Modifier.heightIn(min = 40.dp)) {
        Icon(Icons.Outlined.Save, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(str(Res.string.common_save), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Share of the screen height the pulled-up plan sheet always takes (below the grab handle). */
internal const val FULL_SHEET_FRACTION = 0.62f
/** Height of the primary button row at the sheet's bottom (12 dp gap + 56 dp button). */
private val PRIMARY_BAR = 68.dp

@Composable
private fun ColumnScope.PlanBody(sheet: StepSheetScope, ui: PlanUi, cb: PlanCallbacks, menuInitiallyOpen: Boolean, scroll: ScrollState) {
    val route = ui.route
    val full = ui.size == PlanSize.FULL
    // During a tour: the tour first (status + Continue / Pause / End), then the route – one surface.
    ui.tour?.let { t ->
        TourControls(t.distanceM, t.elapsedS, t.paused, cb.onPause, cb.onContinue, cb.onEndTour, ui.activity)
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
    // Line 1: activity · time + distance · [Save] ⋯ (Undo and Redo are map buttons).
    val saveAtHand = RouteRules.saveAtHand(ui.pointCount, ui.unsaved)
    HeaderLine(
        chip = { ActivityButton(ui.activity, ui.sac, cb.onActivity) },
        title = { Title(ui, route) },
        overflow = { SheetOverflow(ui, cb, menuInitiallyOpen) },
        save = if (saveAtHand) ({ SaveButton(route != null, cb.onSave, iconOnly = false) }) else null,
        saveIcon = if (saveAtHand) ({ SaveButton(route != null, cb.onSave, iconOnly = true) }) else null,
    )
    if (ui.routing && ui.routingSlow) {
        // a long calculation can be stopped (the last change is taken back)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(PlanStatus.CALCULATING, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(CircleShape))
            }
            androidx.compose.material3.TextButton(onClick = cb.onCancelRouting) { Text(str(Res.string.common_cancel)) }
        }
    } else if (ui.routing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(CircleShape))
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
    Spacer(with(sheet) { Modifier.stepEnd(PlanSize.MINI.step) })
    var viewport by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    Column(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { viewport = it }
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
                ui.points, canReverse = ui.pointCount >= 2, canRoundTrip = ui.pointCount >= 2 && !ui.roundTrip,
                onReverse = cb.onReverse, onRoundTrip = cb.onRoundTrip,
                onMove = cb.onMovePoint, onDelete = cb.onDeletePoint, onShow = cb.onShowPoint,
                modifier = Modifier.padding(top = 4.dp),
                scroll = scroll, viewport = { viewport },
            )
        }
    }
}

/** The large profile of the profile step. */
private val PROFILE_HEIGHT = 120.dp

/** Bottom of the sheet: the one primary button – not during a tour (Continue is in the tour controls above). */
@Composable
private fun ColumnScope.PlanBar(ui: PlanUi, cb: PlanCallbacks) {
    if (ui.tour == null && !ui.tourRunning && ui.pointCount >= 2) {
        Spacer(Modifier.height(12.dp))
        PrimaryAction(ui, cb)
    } else Spacer(Modifier.height(8.dp))
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
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
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
        Text(text, style = MaterialTheme.typography.bodyMedium, color = grey,
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
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(MaterialTheme.shapes.small)
        .clickable(role = Role.Button, onClickLabel = str(Res.string.plan_warn_show_spots)) { onTap() }
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Warning, null, tint = amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = amber,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = amber, modifier = Modifier.size(20.dp))
    }
}

/** Visible save state instead of a message: "Saved as “Name”", or that it changed since. */
@Composable
private fun SavedLine(ui: PlanUi) {
    val name = ui.savedName ?: return
    val saved = !ui.unsaved
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (saved) Icons.Outlined.CheckCircle else Icons.Outlined.Edit, null,
            tint = if (saved) OnPathColors.Good else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (saved) str(Res.string.plan_saved_as, name) else str(Res.string.plan_changed_not_saved, name),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Read-only status, first line: time + distance (or "Plan a route"). */
@Composable
private fun Title(ui: PlanUi, route: PlannedRoute?) {
    if (route == null) {
        Text(str(Res.string.plan_plan_a_route), style = MaterialTheme.typography.titleLarge, maxLines = 1)
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(Format.duration(route.durationS), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(Format.distance(route.distanceM),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 1.dp))
    }
}

/** Read-only status, second line: ascent / descent / highest point + difficulty badge, or what to do next. */
@Composable
private fun SubLine(ui: PlanUi, route: PlannedRoute?, onDetails: () -> Unit = {}) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    if (route == null) {
        if (ui.notRouted != null && !ui.routing && ui.pointCount >= 2) return // the not-routed notice says it
        // "Calculating the route…" only while a request runs (not after Cancel)
        Text(PlanStatus.noRouteLine(ui.pointCount, ui.routing, ui.notRouted),
            style = MaterialTheme.typography.bodyMedium, color = variant)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val top = route.points.mapNotNull { it.ele }.maxOrNull()
        Text(
            "↑ ${Format.meters(route.ascentM)}  ↓ ${Format.meters(route.descentM)}" +
                (top?.let { "  ·  " + str(Res.string.plan_max_height, Format.meters(it)) } ?: ""),
            style = MaterialTheme.typography.bodyMedium, color = variant, maxLines = 1,
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

/** The one filled button of the sheet. */
@Composable
private fun PrimaryAction(ui: PlanUi, cb: PlanCallbacks) {
    // the button names the activity (owner: a wrong activity is a disaster)
    Button(onClick = if (ui.tourRunning) cb.onContinue else cb.onStartNavigation, enabled = ui.tourRunning || ui.canStart,
        modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(8.dp))
        Text(primaryLabel(ui), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Start navigation · Hike" / "Continue navigation · Gravel bike". */
internal fun primaryLabel(ui: PlanUi): String =
    Txt.s(if (ui.tourRunning) Res.string.plan_continue_navigation_for else Res.string.plan_start_navigation_for, ui.activity.navName)

/** ⋯ of the sheet: route edits only (reverse, round trip, clear). */
@Composable
private fun SheetOverflow(ui: PlanUi, cb: PlanCallbacks, initiallyOpen: Boolean) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.MoreHoriz, contentDescription = str(Res.string.plan_route_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SheetMenuItems(ui, cb, close = { open = false })
        }
    }
}

/** The items of the sheet's ⋯ menu (also rendered on their own by the screenshot tests). */
@Composable
internal fun SheetMenuItems(ui: PlanUi, cb: PlanCallbacks, close: () -> Unit) {
    fun run(a: () -> Unit): () -> Unit = { close(); a() }
    // About this route: save, share, time details, clear. No "Export GPX" here (owner: it clutters next to saving) –
    // export lives in My routes (⋮ Export GPX / Export all).
    MenuItem(Icons.Outlined.Save, str(Res.string.plan_save_route), enabled = ui.route != null, onClick = run(cb.onSave))
    MenuItem(Icons.Outlined.Link, str(Res.string.plan_share_plan), enabled = ui.pointCount >= 2, onClick = run(cb.onShare))
    MenuItem(Icons.Outlined.Schedule, str(Res.string.plan_time_details), enabled = ui.route != null, onClick = run(cb.onTimeDetails))
    // Reverse / Round trip live above the points (full sheet) only – not twice
    HorizontalDivider()
    MenuItem(Icons.Outlined.DeleteSweep, str(Res.string.plan_clear_route), enabled = RouteRules.canClear(ui.pointCount),
        onClick = run(cb.onClear))
}

/** The route's activity as a small tonal icon button with ▾ (opens the activity picker). */
@Composable
private fun ActivityButton(activity: Activity, sac: Int, onClick: () -> Unit) {
    val name = com.onpath.app.data.LastActivity.chipText(activity, sac) // "Hike · T2"
    androidx.compose.material3.Surface(onClick = onClick, shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = Txt.s(Res.string.plan_activity_chip_desc, name) }) {
        Row(Modifier.padding(start = 8.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(activity.icon, null, tint = OnPathColors.Green, modifier = Modifier.size(24.dp))
            // short names ("Gravel", "E-MTB"; Activity.chip) so time and distance keep their room
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
