package com.onpath.app.ui

import androidx.compose.material.icons.outlined.Delete

import androidx.compose.material.icons.outlined.Place

import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Save
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.PlanPointRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import com.onpath.app.routing.RouteSummary

/** Everything the plan panel can ask for; defaults keep previews and tests short. */
class PlanCallbacks(
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onClear: () -> Unit = {},
    /** Primary action: follow the route (free recording is in the ⋮ menu). */
    val onStartNavigation: () -> Unit = {},
    /** Primary action while a tour records: re-route from here and go back to Navigate. */
    val onContinue: () -> Unit = {},
    val onReverse: () -> Unit = {},
    val onRoundTrip: () -> Unit = {},
    /** Activity icon at the start of the sheet: opens the activity picker. */
    val onActivity: () -> Unit = {},
    /** Route ⋯: Save (name / overwrite choice), Export GPX of the planned route. */
    val onSave: () -> Unit = {},
    val onExport: () -> Unit = {},
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
    /** "Cancel" next to the routing bar (only while a route takes a while). */
    val onCancelRouting: () -> Unit = {},
    val onResize: (PlanSize) -> Unit = {},
    /** Tour controls at the top of the sheet (only while a tour records). */
    val onPause: (Boolean) -> Unit = {},
    val onEndTour: () -> Unit = {},
    /** The map card (CellCard): mobile data answer, retry, close. */
    val cellCard: CellCardCallbacks = CellCardCallbacks(),
)

/** Recording status shown at the top of the plan sheet during a tour. */
data class TourState(val distanceM: Double, val elapsedS: Long, val paused: Boolean)

/**
 * Plan sheet sizes (owner 2026-10-21): MINI = summary only, COMPACT = summary + elevation profile (default),
 * FULL = the plan editor (owner 2026-10-22): one line (time · distance), then one scrolling area – elevation profile
 * (finger slides the dot along the route), Points (+ Reverse / Round trip), way types & surfaces. The primary button
 * stays at the bottom in every size.
 */
enum class PlanSize {
    FULL, COMPACT, MINI;

    fun smaller() = if (this == FULL) COMPACT else MINI
    fun bigger() = if (this == MINI) COMPACT else FULL
    /** Handle tap: grow one step; on FULL go back to COMPACT. */
    fun tapped() = if (this == FULL) COMPACT else bigger()
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
    /** A tour records: the primary action is "Continue navigation" instead of Start; Clear is disabled. */
    val tourRunning: Boolean = false,
    /** Name the plan was last saved or loaded as (null = never). */
    val savedName: String? = null,
    /** The plan differs from [savedName]'s points (RouteRules.isUnsaved). */
    val unsaved: Boolean = false,
    /** The route already ends at its start: "Round trip" is greyed out (smart menu, no message). */
    val roundTrip: Boolean = false,
    /** A tour records: its status + Continue / Pause / End sit at the top of this sheet (one surface). */
    val tour: TourState? = null,
    /** Detailed map being fetched for this area (CellCard, map-cells.md §9); null = nothing to say. */
    val cellCard: CellCardUi? = null,
    /** Rows of the Points section (full sheet), see PlanPoints.rows. */
    val points: List<PlanPointRow> = emptyList(),
    /** The route has been calculating for a while: show "Calculating route… Cancel". */
    val routingSlow: Boolean = false,
) {
    /** Without a route navigation just records; while a route is being computed, wait for it. */
    val canStart: Boolean get() = pointCount < 2 || route != null
}

/**
 * The plan bottom sheet: status first (time, distance, ascent/descent, highest point, difficulty – read-only
 * text and a badge), then by size (PlanSize) the elevation profile (half) or the point editor (full),
 * and ONE filled primary button fixed at the bottom (only with a route, or "Continue navigation" while a tour runs).
 * Route edits (save, export, reverse, round trip, clear) are also in ⋯.
 */
@Composable
fun PlanPanel(ui: PlanUi, cb: PlanCallbacks, menuInitiallyOpen: Boolean = false) {
    BottomPanel(
        onSwipe = { down -> cb.onResize(if (down) ui.size.smaller() else ui.size.bigger()) },
        onHandleTap = { cb.onResize(ui.size.tapped()) },
    ) {
        val full = ui.size == PlanSize.FULL
        if (full) {
            // owner 2026-10-22: the pulled-up sheet has ONE fixed height – deleting / adding / moving points (or the
            // routing bar) never makes its top edge (and the profile) jump; the scrolling area takes what is left
            val sheetH = (LocalConfiguration.current.screenHeightDp * FULL_SHEET_FRACTION).dp
            Column(Modifier.fillMaxWidth().height(sheetH)) { PlanBody(ui, cb, menuInitiallyOpen) }
        } else PlanBody(ui, cb, menuInitiallyOpen)
    }
}

/** Share of the screen height the pulled-up plan sheet always takes (below the grab handle). */
internal const val FULL_SHEET_FRACTION = 0.62f

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PlanBody(ui: PlanUi, cb: PlanCallbacks, menuInitiallyOpen: Boolean) {
        val route = ui.route
        val mini = ui.size == PlanSize.MINI
        // During a tour: the tour first (status + Continue / Pause / End), then the route – one surface.
        ui.tour?.let { t ->
            TourControls(t.distanceM, t.elapsedS, t.paused, cb.onPause, cb.onContinue, cb.onEndTour)
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        // Line 1: activity · time + distance · ⋯ (Undo is with the map buttons, Redo in ⋯).
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActivityButton(ui.activity, cb.onActivity)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) { Title(ui, route) }
            SheetOverflow(ui, cb, menuInitiallyOpen)
        }
        val full = ui.size == PlanSize.FULL
        if (!mini && !full) SubLine(ui, route, cb.onDetails)
        if (!mini && !full && route != null) SavedLine(ui)
        if (ui.routing && ui.routingSlow) {
            // owner 2026-10-22: a long calculation can be stopped (the last change is taken back)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Calculating the route…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(CircleShape))
                }
                androidx.compose.material3.TextButton(onClick = cb.onCancelRouting) { Text("Cancel") }
            }
        } else if (full) Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp)) {
            // full: the routing bar has a fixed slot (no jump when it comes and goes)
            if (ui.routing) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape))
        } else if (ui.routing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(CircleShape))
        ui.error?.let { ErrorBox(it) }
        // in the point editor the map card is one line: it must never squeeze the points away (CI 2026-10-22)
        ui.cellCard?.let { CellCard(it, cb.cellCard, compact = mini || full) }
        if (full) {
            PlanEditor(ui, cb, Modifier.weight(1f))
        } else if (!mini && route != null && !ui.routing) {
            // Mini profile, always visible (Komoot-like) – tap for the route details.
            Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                ElevationProfile(route.points, height = 52.dp, gradient = true)
                Box(Modifier.matchParentSize().clickable(role = Role.Button, onClickLabel = "Show route details",
                    onClick = cb.onDetails).semantics { contentDescription = "Route details" })
            }
        }
        // The one primary button – not during a tour (Continue is in the tour controls above).
        if (ui.tour == null && !ui.tourRunning && ui.pointCount >= 2) {
            Spacer(Modifier.height(12.dp))
            PrimaryAction(ui, cb)
        } else Spacer(Modifier.height(8.dp))
}

/**
 * Full sheet (owner 2026-10-22): ONE scrolling area – elevation profile (slide a finger: the dot runs along the route
 * on the map), the Points list (Reverse / Round trip only here), way types & surfaces. Title line above and the primary
 * button below stay put, so 3 or 30 points work the same.
 */
@Composable
private fun PlanEditor(ui: PlanUi, cb: PlanCallbacks, modifier: Modifier) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val route = ui.route
    Column(modifier.fillMaxWidth().onGloballyPositioned { viewport = it }.verticalScroll(scroll)) {
        if (route != null && !ui.routing) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "Elevation profile" }) {
                ElevationProfile(route.points, height = 96.dp, gradient = true,
                    onHighlight = cb.onHighlight, onRelease = { cb.onHighlight(null) })
                GradientLegend()
            }
        }
        PointsSection(
            ui.points, canReverse = ui.pointCount >= 2, canRoundTrip = ui.pointCount >= 2 && !ui.roundTrip,
            onReverse = cb.onReverse, onRoundTrip = cb.onRoundTrip,
            onMove = cb.onMovePoint, onDelete = cb.onDeletePoint, onShow = cb.onShowPoint,
            modifier = Modifier.padding(top = 8.dp),
            scroll = scroll, viewport = { viewport },
        )
        if (route != null && !ui.routing) {
            RouteBreakdown(route.wayTypes, route.surfaces, Modifier.padding(top = 8.dp), initiallyExpanded = true)
        }
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
        Text(if (saved) "Saved as “$name”" else "“$name” · changed, not saved",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Read-only status, first line: time + distance (or "Plan a route"). */
@Composable
private fun Title(ui: PlanUi, route: PlannedRoute?) {
    if (route == null) {
        Text("Plan a route", style = MaterialTheme.typography.titleLarge, maxLines = 1)
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
        Text(
            when (ui.pointCount) {
                0 -> "Hold the map to start there – or to go there from your location. Hold a point or place for options. " +
                    "To just record, use ☰ → Start recording."
                1 -> "Hold the map to add the destination. Hold a point or place for options."
                else -> "Calculating the route…"
            },
            style = MaterialTheme.typography.bodyMedium, color = variant,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val top = route.points.mapNotNull { it.ele }.maxOrNull()
        Text(
            "↑ ${Format.meters(route.ascentM)}  ↓ ${Format.meters(route.descentM)}" +
                (top?.let { "  ·  max ${Format.meters(it)}" } ?: ""),
            style = MaterialTheme.typography.bodyMedium, color = variant, maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!ui.routing) {
            Spacer(Modifier.width(8.dp))
            DifficultyBadge(RouteSummary.difficulty(ui.activity, route.distanceM, route.ascentM),
                Modifier.clickable(role = Role.Button, onClickLabel = "Show route details", onClick = onDetails))
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
    val m = Modifier.fillMaxWidth().height(56.dp)
    when {
        // the button names the activity (owner: a wrong activity is a disaster)
        ui.tourRunning -> Button(onClick = cb.onContinue, modifier = m) {
            Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(8.dp))
            Text(primaryLabel(ui), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        else -> Button(onClick = cb.onStartNavigation, enabled = ui.canStart, modifier = m) {
            Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(8.dp))
            Text(primaryLabel(ui), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Start navigation · Hike" / "Continue navigation · Trekking bike". */
internal fun primaryLabel(ui: PlanUi): String =
    (if (ui.tourRunning) "Continue navigation" else "Start navigation") + " · " + ui.activity.navName

/** ⋯ of the sheet: route edits only (reverse, round trip, clear). */
@Composable
private fun SheetOverflow(ui: PlanUi, cb: PlanCallbacks, initiallyOpen: Boolean) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.MoreHoriz, contentDescription = "Route options")
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
    // About this route: redo, save / export, time details, clear.
    MenuItem(Icons.AutoMirrored.Filled.Redo, "Redo", enabled = ui.canRedo, onClick = run(cb.onRedo))
    HorizontalDivider()
    MenuItem(Icons.Outlined.Save, "Save route", enabled = ui.route != null, onClick = run(cb.onSave))
    MenuItem(Icons.Outlined.Share, "Export GPX", enabled = ui.route != null, onClick = run(cb.onExport))
    MenuItem(Icons.Outlined.Schedule, "Time details", enabled = ui.route != null, onClick = run(cb.onTimeDetails))
    // Reverse / Round trip live above the points (full sheet) only – owner 2026-10-22: not twice
    HorizontalDivider()
    MenuItem(Icons.Outlined.DeleteSweep, "Clear route", enabled = RouteRules.canClear(ui.pointCount, ui.tourRunning),
        onClick = run(cb.onClear))
}

@Composable
private fun ErrorBox(error: String) {
    Spacer(Modifier.height(8.dp))
    Surface(shape = MaterialTheme.shapes.small, color = Color(0xFFFFF4E0), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp)) {
            Icon(Icons.Outlined.Warning, null, tint = Color(0xFFB26A00), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = Color(0xFF5A3A00))
        }
    }
}


/** The route's activity as a small tonal icon button with ▾ (opens the activity picker). */
@Composable
private fun ActivityButton(activity: Activity, onClick: () -> Unit) {
    androidx.compose.material3.Surface(onClick = onClick, shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Activity: ${activity.label}. Tap to change" }) {
        Row(Modifier.padding(start = 8.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(activity.icon, null, tint = OnPathColors.Green, modifier = Modifier.size(24.dp))
            Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
