package com.onpath.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.onpath.app.routing.Activity

object OnPathColors {
    val Green = Color(0xFF1F6E43)
    val GreenLight = Color(0xFFDDEFE3)
    val Route = Color(0xFF1E3FAE)
    val Gpx = Color(0xFF8E24AA)
    val Rec = Color(0xFFEF6C00)
    val Good = Color(0xFF2E7D32)
    val Weak = Color(0xFFF9A825)
    val Bad = Color(0xFFC62828)
    val Grey = Color(0xFF6B6F6A)
}

private val scheme = lightColorScheme(
    primary = OnPathColors.Green,
    onPrimary = Color.White,
    primaryContainer = OnPathColors.GreenLight,
    onPrimaryContainer = Color(0xFF0B3A21),
    secondary = OnPathColors.Route,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE3F7),
    onSecondaryContainer = Color(0xFF0E1E57),
    surface = Color.White,
    onSurface = Color(0xFF1B1D1B),
    surfaceVariant = Color(0xFFF1F3EF),
    onSurfaceVariant = Color(0xFF4A4F4A),
    background = Color(0xFFF7F8F5),
    error = OnPathColors.Bad,
)

@Composable
fun OnPathTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

val Activity.icon: ImageVector
    get() = when (this) {
        Activity.BIKE_TOURING -> Icons.Filled.DirectionsBike
        Activity.BIKE_TREKKING -> Icons.Filled.PedalBike
        Activity.EBIKE -> Icons.Filled.ElectricBike
        Activity.MTB -> Icons.Filled.Terrain
        Activity.EMTB -> Icons.Filled.ElectricBike
        Activity.HIKE -> Icons.Filled.Hiking
    }

val Activity.shortLabel: String
    get() = when (this) {
        Activity.BIKE_TOURING -> "Touring"
        Activity.BIKE_TREKKING -> "Trekking"
        Activity.EBIKE -> "E-bike"
        Activity.MTB -> "MTB"
        Activity.EMTB -> "E-MTB"
        Activity.HIKE -> "Hike"
    }
