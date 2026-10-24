package com.onpath.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.poi.Poi
import com.onpath.app.search.Place
import com.onpath.app.search.SearchKinds

/** What the card shows besides the place: its role in the route now, or what adding it would do. */
data class PlaceCardUi(
    val place: Place,
    /** Index in the waypoint list when the place already is a route point. */
    val routeIndex: Int? = null,
    val waypointCount: Int = 0,
    /** "as stop 2 – fits best · about +0.9 km, +15 min" (AddPreview) or "is stop 2". */
    val subtitle: String = "",
)

data class PlaceCardActions(
    val onAdd: () -> Unit = {},
    val onRemove: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onDestination: () -> Unit = {},
    val onGoogle: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * The one place card (proposal C) for search results and POIs tapped on the map while planning:
 * name, kind / address, elevation, opening hours, ONE primary "Add to route" button that says what it
 * will do before the tap, and As start · As destination · Google Maps. All data is OpenStreetMap.
 */
@Composable
fun PlaceCard(ui: PlaceCardUi, actions: PlaceCardActions, modifier: Modifier = Modifier) {
    val de = Poi.isGerman()
    val p = ui.place
    val kind = p.kindInfo
    val color = Color(SearchKinds.argb(p.kind))
    val isStop = ui.routeIndex != null
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindIcon(p, 36)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title(de), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(kind.label(de) + (p.ele?.let { " · $it m" } ?: "") + (p.municipality?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                        color = color, maxLines = 1)
                }
                IconButton(onClick = actions.onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            // owner 2026-10-22: stays on the card (never disappears by itself)
            p.note?.let { n ->
                Row(Modifier.padding(top = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, contentDescription = null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(n, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val details = listOfNotNull(p.address) + p.openingHoursLines.take(3).let { h ->
                if (h.isEmpty()) emptyList() else listOf(h.joinToString("\n") + if (p.openingHoursLines.size > 3) " …" else "")
            }
            for (d in details) {
                Text(d, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = if (isStop) actions.onRemove else actions.onAdd,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Icon(if (isStop) Icons.Filled.Remove else Icons.Filled.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (isStop) "Remove from route" else "Add to route", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        if (ui.subtitle.isNotEmpty()) Text(ui.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val small = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    val atStart = ui.routeIndex == 0
                    val atEnd = ui.routeIndex != null && ui.routeIndex == ui.waypointCount - 1 && ui.waypointCount > 1
                    OutlinedButton(onClick = actions.onStart, enabled = !atStart, contentPadding = small,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("As start", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(onClick = actions.onDestination, enabled = !atEnd, contentPadding = small,
                        modifier = Modifier.weight(1.25f).heightIn(min = 48.dp)) {
                        Text("As destination", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(onClick = actions.onGoogle, contentPadding = small,
                        modifier = Modifier.weight(1.2f).heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Map, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Google", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
