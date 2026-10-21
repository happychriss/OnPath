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
    /** Route ⋯ → Time details (how the time was calculated). */
    val onTimeDetails: () -> Unit = {},
    /** Route ⋯ → Points…: the list of route points with Delete (a way that does not depend on map gestures). */
    val onPoints: () -> Unit = {},
    val onHighlight: (GeoPoint?) -> Unit = {},
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
 * Plan sheet sizes: MINI = one line + primary button, COMPACT = stats + "Elevation & surfaces" row (default),
 * FULL = also the elevation profile and way types / surfaces ("Elevation & surfaces" row or swipe up).
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
) {
    /** Without a route navigation just records; while a route is being computed, wait for it. */
    val canStart: Boolean get() = pointCount < 2 || route != null
}

/**
 * The plan bottom sheet: status first (time, distance, ascent/descent, highest point, difficulty – read-only
 * text and a badge) with undo/redo, the profile behind "Elevation & surfaces", and ONE
 * filled primary button (only with a route, or "Continue navigation" while a tour runs). Points are managed on the
 * map only (hold a marker → point menu). Route edits (reverse, round trip, clear) are in ⋯; save / load / export
 * are in the top ⋮ menu and Manage routes.
 */
@Composable
fun PlanPanel(ui: PlanUi, cb: PlanCallbacks, menuInitiallyOpen: Boolean = false) {
    BottomPanel(
        onSwipe = { down -> cb.onResize(if (down) ui.size.smaller() else ui.size.bigger()) },
        onHandleTap = { cb.onResize(ui.size.tapped()) },
    ) {
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
        if (!mini) SubLine(ui, route)
        if (!mini && route != null) SavedLine(ui)
        if (ui.routing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(CircleShape))
        ui.error?.let { ErrorBox(it) }
        ui.cellCard?.let { CellCard(it, cb.cellCard, compact = mini) }
        if (!mini && route != null && !ui.routing) {
            if (ui.size == PlanSize.FULL) {
                Spacer(Modifier.height(6.dp))
                ElevationProfile(route.points, height = 84.dp, gradient = true, onHighlight = cb.onHighlight)
                GradientLegend()
                RouteBreakdown(route.wayTypes, route.surfaces, Modifier.padding(top = 8.dp), initiallyExpanded = true)
            } else {
                // Mini profile, always visible (Komoot-like) – tap for the full profile and way types.
                Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    ElevationProfile(route.points, height = 52.dp, gradient = true)
                    Box(Modifier.matchParentSize().clickable(role = Role.Button, onClickLabel = "Show elevation profile") {
                        cb.onResize(PlanSize.FULL)
                    })
                }
            }
        }
        // The one primary button – not during a tour (Continue is in the tour controls above).
        if (ui.tour == null && !ui.tourRunning && ui.pointCount >= 2) {
            Spacer(Modifier.height(12.dp))
            PrimaryAction(ui, cb)
        } else Spacer(Modifier.height(8.dp))
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
private fun SubLine(ui: PlanUi, route: PlannedRoute?) {
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
            DifficultyBadge(RouteSummary.difficulty(ui.activity, route.distanceM, route.ascentM))
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
    val two = ui.pointCount >= 2
    fun run(a: () -> Unit): () -> Unit = { close(); a() }
    // Everything about this route in one place (owner): save / export, edits, clear.
    MenuItem(Icons.AutoMirrored.Filled.Redo, "Redo", enabled = ui.canRedo, onClick = run(cb.onRedo))
    HorizontalDivider()
    MenuItem(Icons.Outlined.Place, "Points… (delete)", enabled = ui.pointCount >= 1, onClick = run(cb.onPoints))
    HorizontalDivider()
    MenuItem(Icons.Outlined.Save, "Save route", enabled = ui.route != null, onClick = run(cb.onSave))
    MenuItem(Icons.Outlined.Share, "Export GPX", enabled = ui.route != null, onClick = run(cb.onExport))
    MenuItem(Icons.Outlined.Schedule, "Time details", enabled = ui.route != null, onClick = run(cb.onTimeDetails))
    HorizontalDivider()
    MenuItem(Icons.Outlined.SwapVert, "Reverse route", enabled = two, onClick = run(cb.onReverse))
    MenuItem(Icons.Outlined.Loop, "Round trip (back to start)", enabled = two && !ui.roundTrip, onClick = run(cb.onRoundTrip))
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

/** One row of the point list: "Start", "Stop 2", "Destination" + the place name when known. */
data class PointRow(val index: Int, val role: String, val name: String?)

object PointRows {
    fun of(count: Int, name: (Int) -> String?): List<PointRow> = (0 until count).map { i ->
        PointRow(i, when { i == 0 -> "Start"; i == count - 1 -> "Destination"; else -> "Stop ${i + 1}" }, name(i))
    }
}

/**
 * Route ⋯ → Points…: every route point with a Delete button (owner 2026-10-21 – deleting must always work, also when
 * holding a marker on the map fails). Undo brings a deleted point back.
 */
@Composable
fun PointListDialog(rows: List<PointRow>, onDelete: (Int) -> Unit, onClose: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Done") } },
        title = { Text("Route points") },
        text = {
            Column {
                if (rows.isEmpty()) Text("No points.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (r in rows) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(stopColor(r.index, rows.size)), contentAlignment = Alignment.Center) {
                        Text("${r.index + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.role, style = MaterialTheme.typography.bodyLarge)
                        r.name?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    IconButton(onClick = { onDelete(r.index) }, modifier = Modifier.size(48.dp)
                        .semantics { contentDescription = "Delete ${r.role}" }) {
                        Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    )
}
