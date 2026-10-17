package com.onpath.app.poi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Content for a bottom sheet (e.g. inside ModalBottomSheet) describing one OSM POI.
 * All data shown is OpenStreetMap; Google Maps is only an external link.
 */
@Composable
fun PoiDetails(poi: Poi, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val de = Poi.isGerman()
    val kind = poi.kindInfo
    val color = Color(kind.group.argb)

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
            .navigationBarsPadding()
    ) {
        // Header: colour dot, title, kind, close
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(color, CircleShape).border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(kind.glyph, color = Color.White, fontSize = if (kind.glyph.length > 1) 13.sp else 20.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(poi.title(de), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(kind.label(de))
                        poi.ele?.let { append(" · $it m") }
                    },
                    style = MaterialTheme.typography.bodyMedium, color = color,
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = if (de) "Schließen" else "Close")
            }
        }

        Spacer(Modifier.size(8.dp))
        HorizontalDivider()

        poi.cuisineLabel?.let { DetailRow(Icons.Outlined.Restaurant, if (de) "Küche" else "Cuisine", it) }
        if (poi.openingHoursLines.isNotEmpty()) {
            DetailRow(Icons.Outlined.Schedule, if (de) "Öffnungszeiten" else "Opening hours",
                poi.openingHoursLines.joinToString("\n"))
        }
        poi.firstPhone?.let { ph ->
            DetailRow(Icons.Outlined.Phone, if (de) "Telefon" else "Phone", ph, link = true) {
                PoiIntents.dial(context, ph)
            }
        }
        PoiLinks.websiteLabel(poi.website)?.let { label ->
            DetailRow(Icons.Outlined.Public, "Website", label, link = true) {
                PoiIntents.openWebsite(context, poi.website)
            }
        }
        PoiLinks.wikipediaLabel(poi.wikipedia)?.let { label ->
            DetailRow(Icons.Outlined.Info, "Wikipedia", label, link = true) {
                PoiIntents.openWikipedia(context, poi.wikipedia)
            }
        }

        Spacer(Modifier.size(16.dp))
        Button(
            onClick = { PoiIntents.openInGoogleMaps(context, poi) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(),
        ) {
            Icon(Icons.Outlined.Map, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(if (de) "In Google Maps öffnen" else "Open in Google Maps", fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold)
        }
        Text(
            if (de) "Online · Bewertungen, Fotos und aktuelle Infos bei Google"
            else "Online · reviews, photos and current info on Google",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Text(
            "© OpenStreetMap",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String, link: Boolean = false,
                      onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge,
                color = if (link) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
    }
}
