package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.routing.Activity
import com.onpath.app.routing.BreakdownRules
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.RouteSummary
import com.onpath.app.routing.Share

/**
 * Way types and surfaces. Collapsed by default to one summary line (e.g. "Cycleway 92 % · Asphalt 98 %")
 * so the planning panel leaves enough map to tap on; tap to show the two bars with a km legend.
 * Nothing is shown if both lists are empty. Labels, colours, the fold and the line: [BreakdownRules] (shared with the web).
 */
@Composable
fun RouteBreakdown(
    wayTypes: List<Share>,
    surfaces: List<Share>,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    if (wayTypes.isEmpty() && surfaces.isEmpty()) return
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(initiallyExpanded) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(str(Res.string.plan_way_types_surfaces), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(BreakdownRules.summaryLine(wayTypes, surfaces), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            androidx.compose.material3.Icon(
                if (expanded) Icons.Filled.ExpandLess
                else Icons.Filled.ExpandMore,
                contentDescription = str(if (expanded) Res.string.plan_hide_details else Res.string.plan_show_details),
            )
        }
        if (expanded) {
            if (wayTypes.isNotEmpty()) BreakdownBar(str(Res.string.plan_way_types), wayTypes, BreakdownRules.Kind.WAY)
            if (surfaces.isNotEmpty()) BreakdownBar(str(Res.string.plan_surfaces), surfaces, BreakdownRules.Kind.SURFACE)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BreakdownBar(title: String, shares: List<Share>, kind: BreakdownRules.Kind) {
    val parts = BreakdownRules.foldSmall(shares)
    if (parts.isEmpty()) return
    Column {
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
            parts.forEach { s ->
                Box(Modifier.weight(s.meters.toFloat().coerceAtLeast(1f)).height(8.dp).background(Color(BreakdownRules.entry(kind, s.key).argb)))
            }
        }
        FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            parts.forEach { s ->
                val e = BreakdownRules.entry(kind, s.key)
                val color = Color(e.argb)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(4.dp))
                    Text("${str(e.label)} ${Format.distance(s.meters)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** "Moderate", or "Moderate · T3" when the trail technique raised the label (like Komoot). */
@Composable
fun DifficultyBadge(r: RouteSummary.Rating, modifier: Modifier = Modifier) {
    val (bg, fg) = when (r.level) {
        RouteSummary.Difficulty.EASY -> OnPathColors.GreenLight to OnPathColors.Green
        RouteSummary.Difficulty.MODERATE -> OnPathColors.WarnContainer to OnPathColors.Warn
        RouteSummary.Difficulty.DIFFICULT -> OnPathColors.ErrorContainer to OnPathColors.Error
    }
    Text(r.text, modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
        color = fg, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

/** The rating of [route] for [activity], computed once per route (walks the way attributes of every point). */
@Composable
fun rememberRating(route: PlannedRoute, activity: Activity): RouteSummary.Rating =
    remember(route, activity) {
        RouteSummary.rating(activity, route.distanceM, route.ascentM, route.points)
    }
