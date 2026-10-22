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
    /** Amber text / icons on white (route warnings): darker than [Weak] so it reads in sunlight. */
    val Amber = Color(0xFF9A5B00)
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
    onBackground = Color(0xFF1B1D1B),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF0D2),
    onTertiaryContainer = Color(0xFF2C1A00),
    error = OnPathColors.Bad,
    errorContainer = Color(0xFFFDE0DC),
    onErrorContainer = Color(0xFF5F1410),
    inversePrimary = Color(0xFF8ED6A8),
    outline = Color(0xFF737970),
    outlineVariant = Color(0xFFC3C8C0),
    // Neutral (slightly green) containers instead of Material's default purple tint:
    // menus, sheets, cards and chips all take these.
    surfaceTint = OnPathColors.Green,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDADDD7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8F5),
    surfaceContainer = Color(0xFFF1F3EF),
    surfaceContainerHigh = Color(0xFFEBEEE9),
    surfaceContainerHighest = Color(0xFFE5E8E3),
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

/** Name on the primary button: "Start navigation · Trekking bike". */
val Activity.navName: String
    get() = when (this) {
        Activity.BIKE_TOURING -> "Touring bike"
        Activity.BIKE_TREKKING -> "Trekking bike"
        Activity.EBIKE -> "E-bike"
        Activity.MTB -> "MTB"
        Activity.EMTB -> "E-MTB"
        Activity.HIKE -> "Hike"
    }
