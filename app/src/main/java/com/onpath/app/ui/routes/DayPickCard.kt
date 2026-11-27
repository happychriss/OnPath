package com.onpath.app.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.OnPathShapes
import com.onpath.strings.*

// My routes' cards of multi-day routes (owner 2026-11-27, "ja"): the badge on the map picture and Gemacht's "Tag 3 von …".

/**
 * The badge top-left on a saved multi-day route's map picture: a small plan-blue pill, calendar sign + „8 Tage“. Nothing for
 * any other route. Decorative for TalkBack (the facts line under the name says the days too).
 */
@Composable
fun DaysBadge(t: Tour, modifier: Modifier = Modifier) {
    val d = t.days?.takeIf { t.kind == TourKind.PLANNED } ?: return
    // a label on the picture, not text to read: it keeps its size at a large font (the tile shrinks then – the facts line
    // under the name says „8 Tage“ in the large font)
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
            androidx.compose.ui.unit.Density(density.density, minOf(density.fontScale, 1f))) { Badge(d.days, sign = density.fontScale < SMALL_TILE_SCALE, modifier) }
}

@Composable
private fun Badge(days: Int, sign: Boolean, modifier: Modifier) {
    Row(modifier.padding(4.dp).background(OnPathColors.PlanAccent, OnPathShapes.Pill).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (sign) {
            Icon(Icons.Outlined.CalendarMonth, null, tint = OnPathColors.CardWhite, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(plural(Res.plurals.multiday_days_count, days, days), style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold, color = OnPathColors.CardWhite, maxLines = 1, softWrap = false)
    }
}

/** From this font scale My routes' tile is the small one (Screens.kt LARGE_FONT_TILE_SCALE): the badge without its sign fits. */
private const val SMALL_TILE_SCALE = 1.5f

/**
 * Gemacht: a recorded tour that rode one day of a multi-day route – „Tag 3 von Von Hilden nach München“; a tap opens the
 * route's day sheet while the route is in My routes ([Tour.planOpens]), else it is just the text. Nothing for other tours.
 */
@Composable
fun DayOfLine(t: Tour, onDays: (String) -> Unit) {
    val uuid = t.planUuid ?: return
    val day = t.planDay?.takeIf { it > 0 && t.kind == TourKind.RECORDED } ?: return
    val name = t.planName ?: return
    val text = str(Res.string.daypick_day_of, day.toString(), name)
    val opens = t.planOpens
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (opens) FontWeight.Medium else FontWeight.Normal,
        color = if (opens) OnPathColors.PlanAccent else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp)
            .then(if (opens) Modifier.heightIn(min = 24.dp).clickable(role = Role.Button) { onDays(uuid) } else Modifier))
}
