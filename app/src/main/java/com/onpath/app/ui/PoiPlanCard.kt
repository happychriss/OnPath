package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.unit.sp
import com.onpath.app.poi.Poi

/**
 * Small card above the plan panel for a POI tapped while planning: what it is, opening hours from
 * OSM (offline), and two actions – add it to the route (or remove it) and open it in Google Maps.
 * Planning is not interrupted; tapping the map closes the card.
 */
@Composable
fun PoiPlanCard(
    poi: Poi,
    isStop: Boolean,
    onToggleStop: () -> Unit,
    onGoogle: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val de = Poi.isGerman()
    val kind = poi.kindInfo
    val color = Color(kind.group.argb)
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                    Text(kind.glyph, color = Color.White, fontWeight = FontWeight.Bold,
                        fontSize = if (kind.glyph.length > 1) 11.sp else 15.sp, maxLines = 1)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(poi.title(de), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(kind.label(de) + (poi.ele?.let { " · $it m" } ?: ""), fontSize = 13.sp, color = color, maxLines = 1)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            val hours = poi.openingHoursLines
            if (hours.isNotEmpty()) {
                Text(hours.take(3).joinToString("\n") + if (hours.size > 3) " …" else "", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggleStop, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp)) {
                    Icon(if (isStop) Icons.Filled.Remove else Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isStop) "Remove from route" else "Add to route", maxLines = 1)
                }
                OutlinedButton(onClick = onGoogle, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.Map, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Google Maps", maxLines = 1)
                }
            }
        }
    }
}
