package com.onpath.app.ui

import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource
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
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.RouteSummary
import com.onpath.app.routing.Share

/** Labels and colours for the contract keys (RoutingContract.kt). */
private val WAY = mapOf(
    "cycleway" to (Res.string.plan_way_cycleway to Color(0xFF3B8E3B)),
    "path" to (Res.string.plan_way_path to Color(0xFF8D5A3B)),
    "footway" to (Res.string.plan_way_footway to Color(0xFFC08A5B)),
    "track" to (Res.string.plan_way_track to Color(0xFFC9A64E)),
    "quiet_road" to (Res.string.plan_way_quiet_road to Color(0xFF9AA5B1)),
    "road" to (Res.string.plan_way_road to Color(0xFF5F6B78)),
    "busy_road" to (Res.string.plan_way_busy_road to Color(0xFFC62828)),
    "steps" to (Res.string.plan_way_steps to Color(0xFF4E342E)),
    "ferry" to (Res.string.plan_way_ferry to Color(0xFF2F86C9)),
)
private val SURFACE = mapOf(
    "asphalt" to (Res.string.plan_surface_asphalt to Color(0xFF546E7A)),
    "paved" to (Res.string.plan_surface_paved to Color(0xFF90A4AE)),
    "gravel" to (Res.string.plan_surface_gravel to Color(0xFFD1B074)),
    "unpaved" to (Res.string.plan_surface_unpaved to Color(0xFFA1887F)),
    "ground" to (Res.string.plan_surface_ground to Color(0xFF8D6E63)),
    "natural_trail" to (Res.string.plan_surface_natural_trail to Color(0xFF6D8B3A)),
    "alpine" to (Res.string.plan_surface_alpine to Color(0xFF7B7F86)),
    "unknown" to (Res.string.plan_surface_unknown to Color(0xFFE6E6E6)),
)
private val OTHER = Res.string.plan_other to Color(0xFFD5D5D5)

/**
 * Way types and surfaces. Collapsed by default to one summary line (e.g. "Cycleway 92 % · Asphalt 98 %")
 * so the planning panel leaves enough map to tap on; tap to show the two bars with a km legend.
 * Nothing is shown if both lists are empty.
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
                Text(summaryLine(wayTypes, surfaces), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            androidx.compose.material3.Icon(
                if (expanded) Icons.Filled.ExpandLess
                else Icons.Filled.ExpandMore,
                contentDescription = str(if (expanded) Res.string.plan_hide_details else Res.string.plan_show_details),
            )
        }
        if (expanded) {
            if (wayTypes.isNotEmpty()) BreakdownBar(str(Res.string.plan_way_types), wayTypes, WAY)
            if (surfaces.isNotEmpty()) BreakdownBar(str(Res.string.plan_surfaces), surfaces, SURFACE)
        }
    }
}

/** "Cycleway 92 % · Asphalt 98 %" – the largest way type and surface. */
internal fun summaryLine(wayTypes: List<Share>, surfaces: List<Share>): String {
    fun top(list: List<Share>, names: Map<String, Pair<StringResource, Color>>): String? {
        val total = list.sumOf { it.meters }
        val first = list.maxByOrNull { it.meters } ?: return null
        if (total <= 0) return null
        val pct = Math.round(first.meters / total * 100)
        return "${names[first.key]?.first?.let { Txt.s(it) } ?: first.key.replace('_', ' ')} $pct %"
    }
    return listOfNotNull(top(wayTypes, WAY), top(surfaces, SURFACE)).joinToString(" · ")
}

/** Categories below 3 % are merged into "Other" so the legend stays short. */
internal fun foldSmall(shares: List<Share>, minFraction: Double = 0.03): List<Share> {
    val total = shares.sumOf { it.meters }
    if (total <= 0) return emptyList()
    val (big, small) = shares.partition { it.meters / total >= minFraction && it.key != "other" }
    val rest = small.sumOf { it.meters }
    return big + if (rest > 0) listOf(Share("other", rest)) else emptyList()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BreakdownBar(title: String, shares: List<Share>, names: Map<String, Pair<StringResource, Color>>) {
    val parts = foldSmall(shares)
    if (parts.isEmpty()) return
    Column {
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
            parts.forEach { s ->
                Box(Modifier.weight(s.meters.toFloat().coerceAtLeast(1f)).height(8.dp).background((names[s.key] ?: OTHER).second))
            }
        }
        FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            parts.forEach { s ->
                val (label, color) = names[s.key] ?: OTHER
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(4.dp))
                    Text("${str(label)} ${Format.distance(s.meters)}", style = MaterialTheme.typography.labelSmall)
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
