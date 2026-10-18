package com.onpath.app.ui

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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
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
    val onFromMe: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onShare: () -> Unit = {},
    /** Primary action: follow the route (or just record without one). */
    val onStartNavigation: () -> Unit = {},
    /** Primary action while a tour records: re-route from here and go back to Navigate. */
    val onContinue: () -> Unit = {},
    val onReverse: () -> Unit = {},
    val onRoundTrip: () -> Unit = {},
    val onHighlight: (GeoPoint?) -> Unit = {},
    val onResize: (PlanSize) -> Unit = {},
)

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
    /** A tour records: the primary action is "Continue navigation" instead of Start. */
    val tourRunning: Boolean = false,
) {
    /** Without a route navigation just records; while a route is being computed, wait for it. */
    val canStart: Boolean get() = pointCount < 2 || route != null
}

/**
 * The plan bottom sheet: status first (time, distance, ascent/descent, highest point, difficulty – read-only
 * text and a badge) with undo/redo, the profile behind "Elevation & surfaces", and ONE
 * filled primary button. Points are managed on the map only (tap a marker → "Delete point"). Rare route actions (reverse, round trip, from my location, save, GPX, clear) are in ⋯.
 */
@Composable
fun PlanPanel(ui: PlanUi, cb: PlanCallbacks, menuInitiallyOpen: Boolean = false) {
    BottomPanel(
        onSwipe = { down -> cb.onResize(if (down) ui.size.smaller() else ui.size.bigger()) },
        onHandleTap = { cb.onResize(ui.size.tapped()) },
    ) {
        val route = ui.route
        val mini = ui.size == PlanSize.MINI
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Title(ui, route, mini) }
            if (!mini) {
                IconButton(onClick = cb.onUndo, enabled = ui.canUndo, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = cb.onRedo, enabled = ui.canRedo, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }
            }
            SheetOverflow(ui, cb, menuInitiallyOpen)
        }
        if (!mini) SubLine(ui, route)
        if (ui.routing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(CircleShape))
        ui.error?.let { ErrorBox(it) }
        if (!mini) {
            if (ui.pointCount == 0) { Spacer(Modifier.height(8.dp)); FromMeRow(cb) }
            if (route != null && !ui.routing) {
                HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DetailsToggle(ui, route, cb)
                if (ui.size == PlanSize.FULL) {
                    ElevationProfile(route.points, height = 84.dp, gradient = true, onHighlight = cb.onHighlight)
                    GradientLegend()
                    RouteBreakdown(route.wayTypes, route.surfaces, Modifier.padding(top = 8.dp), initiallyExpanded = true)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryAction(ui, cb)
    }
}

/** Read-only status, first line: time + distance (or "Plan a route"). */
@Composable
private fun Title(ui: PlanUi, route: PlannedRoute?, mini: Boolean) {
    if (route == null) {
        Text("Plan a route", style = MaterialTheme.typography.titleLarge, maxLines = 1)
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(Format.duration(route.durationS), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(Format.distance(route.distanceM) + if (mini) " · ↑ ${Format.meters(route.ascentM)}" else "",
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
                0 -> "Hold the map to set a start, or search a place. Without a route you can just record."
                1 -> "Hold the map to set the destination."
                else -> "Calculating the route…"
            },
            style = MaterialTheme.typography.bodyMedium, color = variant,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val top = route.points.mapNotNull { it.ele }.maxOrNull()
        Text(
            "↑ ${Format.meters(route.ascentM)}  ↓ ${Format.meters(route.descentM)}" + (top?.let { "  ·  max ${Format.meters(it)}" } ?: ""),
            style = MaterialTheme.typography.bodyMedium, color = variant, maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!ui.routing) {
            Spacer(Modifier.width(8.dp))
            DifficultyBadge(RouteSummary.difficulty(ui.activity, route.distanceM, route.ascentM))
        }
    }
}

/** Without points: "Start from my location". Points are managed on the map (tap a marker → delete overlay). */
@Composable
private fun FromMeRow(cb: PlanCallbacks) {
    FilledTonalButton(onClick = cb.onFromMe, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text("Start from my location")
    }
}

internal fun stopColor(i: Int, count: Int): Color = when {
    i == 0 -> OnPathColors.Good
    i == count - 1 && count > 1 -> OnPathColors.Bad
    else -> OnPathColors.Route
}

/** "Elevation & surfaces" – shows / hides the profile and the way type bars (FULL ⇄ COMPACT). */
@Composable
private fun DetailsToggle(ui: PlanUi, route: PlannedRoute, cb: PlanCallbacks) {
    val open = ui.size == PlanSize.FULL
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide elevation profile" else "Show elevation profile") {
                cb.onResize(if (open) PlanSize.COMPACT else PlanSize.FULL)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Landscape, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Elevation & surfaces", style = MaterialTheme.typography.labelLarge)
            val summary = summaryLine(route.wayTypes, route.surfaces)
            if (!open && summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null,
            modifier = Modifier.padding(end = 12.dp))
    }
}

/** The one filled button of the sheet. */
@Composable
private fun PrimaryAction(ui: PlanUi, cb: PlanCallbacks) {
    val m = Modifier.fillMaxWidth().height(56.dp)
    when {
        ui.tourRunning -> Button(onClick = cb.onContinue, modifier = m) {
            Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(8.dp))
            Text("Continue navigation", style = MaterialTheme.typography.titleMedium)
        }
        ui.pointCount < 2 -> Button(onClick = cb.onStartNavigation, modifier = m) {
            Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text("Start recording", style = MaterialTheme.typography.titleMedium)
        }
        else -> Button(onClick = cb.onStartNavigation, enabled = ui.canStart, modifier = m) {
            Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(8.dp))
            Text("Start navigation", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** ⋯ of the sheet: route actions that are not needed on every visit. */
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
    val hasRoute = ui.route != null
    fun run(a: () -> Unit): () -> Unit = { close(); a() }
    MenuItem(Icons.Outlined.MyLocation, "Start from my location", onClick = run(cb.onFromMe))
    MenuItem(Icons.Outlined.SwapVert, "Reverse route", enabled = two, onClick = run(cb.onReverse))
    MenuItem(Icons.Outlined.Loop, "Round trip (back to start)", enabled = two, onClick = run(cb.onRoundTrip))
    HorizontalDivider()
    MenuItem(Icons.Outlined.Save, "Save to My tours", enabled = hasRoute, onClick = run(cb.onSave))
    MenuItem(Icons.Outlined.Share, "Export GPX", enabled = hasRoute, onClick = run(cb.onShare))
    HorizontalDivider()
    MenuItem(Icons.Outlined.DeleteSweep, "Clear route", enabled = ui.pointCount > 0, onClick = run(cb.onClear))
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
