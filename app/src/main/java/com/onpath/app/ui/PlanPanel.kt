package com.onpath.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
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
    val onGo: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onToggleHidden: () -> Unit = {},
    val onReverse: () -> Unit = {},
    val onRoundTrip: () -> Unit = {},
    val onPointMenu: (Int) -> Unit = {},
    val onHighlight: (GeoPoint?) -> Unit = {},
    val onResize: (PlanSize) -> Unit = {},
)

/** Plan panel sizes: swipe down = smaller, swipe up or tap the handle = bigger. */
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
    val hidden: Boolean = false,
    val size: PlanSize = PlanSize.FULL,
)

@Composable
fun PlanPanel(ui: PlanUi, cb: PlanCallbacks) {
    BottomPanel(
        onSwipe = { down -> cb.onResize(if (down) ui.size.smaller() else ui.size.bigger()) },
        onHandleTap = { cb.onResize(ui.size.tapped()) },
    ) {
        val route = ui.route
        val mini = ui.size == PlanSize.MINI
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (mini && route != null) {
                // One line instead of the title: the key numbers.
                Text(
                    "${Format.distance(route.distanceM)} · ↑ ${Format.meters(route.ascentM)} · ${Format.duration(route.durationS)}",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text("Plan a route", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(8.dp))
            if (route != null && !ui.routing && !mini) DifficultyBadge(RouteSummary.difficulty(ui.activity, route.distanceM, route.ascentM))
            if (!(mini && route != null)) Spacer(Modifier.weight(1f))
            // The eye only hides the route on the map; the panel stays as it is.
            if (ui.hidden) ActionButtonSmall(Icons.Filled.VisibilityOff, "Show route again", cb.onToggleHidden)
            else ActionButtonSmall(Icons.Filled.Visibility, "Hide route to see the map", cb.onToggleHidden)
            PlanOverflow(enabled = ui.pointCount >= 2, cb)
            ActionButtonSmall(Icons.Filled.Close, "Close planning", cb.onClose)
        }
        if (ui.routing) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp)) else Spacer(Modifier.height(3.dp))
        if (ui.hidden) Hint("Route hidden – tap the eye to show it. Map taps are paused.")
        ui.error?.let { ErrorBox(it) }
        if (mini) return@BottomPanel
        Spacer(Modifier.height(6.dp))
        when {
            ui.pointCount == 0 -> Hint("Tap the map to set the start, or use “From me”.")
            ui.pointCount == 1 -> Hint("Tap the map to set the destination.")
            route != null -> {
                StatsRow(route.distanceM, route.ascentM, route.descentM, route.durationS)
                if (ui.size == PlanSize.FULL) {
                    Spacer(Modifier.height(6.dp))
                    ElevationProfile(route.points, height = 84.dp, gradient = true, onHighlight = cb.onHighlight)
                    GradientLegend()
                    RouteBreakdown(route.wayTypes, route.surfaces, Modifier.padding(top = 8.dp))
                }
            }
        }
        if (ui.pointCount > 0 && ui.size == PlanSize.FULL) PointChips(ui.pointCount, cb.onPointMenu)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionButton(Icons.AutoMirrored.Filled.Undo, "Undo", cb.onUndo, enabled = ui.canUndo)
            ActionButton(Icons.AutoMirrored.Filled.Redo, "Redo", cb.onRedo, enabled = ui.canRedo)
            ActionButton(Icons.Filled.MyLocation, "From me", cb.onFromMe)
            ActionButton(Icons.Filled.DeleteSweep, "Clear", cb.onClear, enabled = ui.pointCount > 0)
            ActionButton(Icons.Filled.Save, "Save", cb.onSave, enabled = route != null)
            ActionButton(Icons.Filled.Share, "GPX", cb.onShare, enabled = route != null)
        }
        Button(onClick = cb.onGo, enabled = route != null, modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(8.dp)); Text("Follow & record", fontSize = 16.sp)
        }
    }
}

@Composable
private fun PlanOverflow(enabled: Boolean, cb: PlanCallbacks) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, "More route actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Reverse route") }, enabled = enabled,
                leadingIcon = { Icon(Icons.Filled.SwapVert, null) }, onClick = { open = false; cb.onReverse() })
            DropdownMenuItem(text = { Text("Round trip (back to start)") }, enabled = enabled,
                leadingIcon = { Icon(Icons.Filled.Loop, null) }, onClick = { open = false; cb.onRoundTrip() })
        }
    }
}

@Composable
private fun ErrorBox(error: String) {
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFFFF4E0)).padding(10.dp)) {
        Icon(Icons.Filled.Warning, null, tint = Color(0xFFB26A00), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(error, fontSize = 13.sp, color = Color(0xFF5A3A00))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PointChips(count: Int, onMenu: (Int) -> Unit) {
    Spacer(Modifier.height(8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val c = when { i == 0 -> OnPathColors.Good; i == count - 1 && count > 1 -> OnPathColors.Bad; else -> OnPathColors.Route }
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(c)
                    .combinedClickable(onClick = { onMenu(i) }, onClickLabel = "Point ${i + 1} options",
                        onLongClick = { onMenu(i) }),
                contentAlignment = Alignment.Center,
            ) { Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold) }
        }
        Text("Tap a number for options", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
