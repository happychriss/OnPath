package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format
import com.onpath.app.routing.Activity
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.RouteSummary

/**
 * Route details (owner 2026-10-21): opened by tapping the profile or the difficulty badge in the plan sheet – the
 * summary with difficulty, the large elevation profile and the way types / surfaces. Read-only, Back closes it.
 */
@Composable
fun RouteDetailsScreen(route: PlannedRoute, activity: Activity, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Route details", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Format.duration(route.durationS), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(Format.distance(route.distanceM), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val top = route.points.mapNotNull { it.ele }.maxOrNull()
                    Text("↑ ${Format.meters(route.ascentM)}  ↓ ${Format.meters(route.descentM)}" +
                        (top?.let { "  ·  max ${Format.meters(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    DifficultyBadge(RouteSummary.difficulty(activity, route.distanceM, route.ascentM))
                }
                Column {
                    ElevationProfile(route.points, height = 140.dp, gradient = true)
                    GradientLegend()
                }
                RouteBreakdown(route.wayTypes, route.surfaces, initiallyExpanded = true)
            }
        }
    }
}
