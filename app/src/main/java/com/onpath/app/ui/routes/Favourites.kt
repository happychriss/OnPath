package com.onpath.app.ui.routes

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.onpath.app.ui.OnPathColors
import com.onpath.strings.*

/** The star on a row, 48 dp to tap: filled gold = favourite, an outline in the muted ink = not. Says what a tap does. */
@Composable
fun FavouriteStar(on: Boolean, name: String, onToggle: (Boolean) -> Unit) {
    val spoken = str(if (on) Res.string.routes_fav_on else Res.string.routes_fav_off, name)
    IconToggleButton(checked = on, onCheckedChange = onToggle, modifier = Modifier.size(48.dp).semantics { contentDescription = spoken }) {
        Icon(if (on) Icons.Filled.Star else Icons.Outlined.StarOutline, contentDescription = null,
            tint = if (on) OnPathColors.Favourite else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
    }
}

/** "☆ Favoriten" at the start of the sort chips: on = light olive (as every selection) with a gold ★. */
@Composable
fun FavouriteChip(on: Boolean, onToggle: (Boolean) -> Unit) {
    val spoken = str(if (on) Res.string.routes_fav_chip_on else Res.string.routes_fav_chip_off)
    FilterChip(selected = on, onClick = { onToggle(!on) },
        label = { Text(str(Res.string.routes_fav_chip), style = MaterialTheme.typography.labelMedium, maxLines = 1) },
        leadingIcon = {
            Icon(if (on) Icons.Filled.Star else Icons.Outlined.StarOutline, null, Modifier.size(FilterChipDefaults.IconSize),
                tint = if (on) OnPathColors.Favourite else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = OnPathColors.GreenLight),
        modifier = Modifier.semantics { contentDescription = spoken },
    )
}
