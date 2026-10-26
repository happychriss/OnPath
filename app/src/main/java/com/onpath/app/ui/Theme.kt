package com.onpath.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
    /** GPS "weak" dot only (map furniture): bright, never for text. */
    val Weak = Color(0xFFF9A825)
    val Grey = Color(0xFF6B6F6A)
    /** Own position / "where I face" arrow on the map (map furniture, like the location puck). */
    val Position = Color(0xFF1A73E8)

    // ---- Message kinds (review 2026-10-24 A): one colour = one meaning ----
    /** ACTION: the blue strip / card that asks a question with buttons – never a status or a failure. */
    val InfoBlue = Color(0xFF1565C0)
    /** WARNING: amber text / icons on white (route spots, off route, no GPS, a download that failed). */
    val Warn = Color(0xFF9A5B00)
    val WarnContainer = Color(0xFFFFF0D2)
    val OnWarnContainer = Color(0xFF2C1A00)
    /** ERROR: something the user asked for did not work (and delete buttons). */
    val Error = Color(0xFFC62828)
    val ErrorContainer = Color(0xFFFDE0DC)
    val OnErrorContainer = Color(0xFF5F1410)

    /** Old names, kept so the meaning reads at the call site. */
    val Amber = Warn
    val Bad = Error
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
    tertiary = OnPathColors.Warn,
    onTertiary = Color.White,
    tertiaryContainer = OnPathColors.WarnContainer,
    onTertiaryContainer = OnPathColors.OnWarnContainer,
    error = OnPathColors.Error,
    errorContainer = OnPathColors.ErrorContainer,
    onErrorContainer = OnPathColors.OnErrorContainer,
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

/**
 * One type scale, five steps (review 2026-10-24 A; all sp, so the phone's font size applies):
 * - Caption 12 (`labelSmall`): map furniture only – scale bar, ©, profile axes / legend.
 * - Small 14 (`bodySmall`, `bodyMedium`, `labelMedium`): detail lines, subtitles, settings explanations, chips.
 * - Body 16 (`bodyLarge`, `titleSmall`, `titleMedium`, `labelLarge`): notice titles, dialogs, list titles, buttons.
 * - Title 22 (`titleLarge`): plan sheet time, screen titles, Navigate time · distance, "Off route".
 * - Display 30 bold (`headlineMedium`): Navigate turn distance, arrival clock.
 * Nothing a hiker must read is below 14; `fontSize =` literals in ui/ are kept out by FontSizeRuleTest.
 */
val OnPathType: Typography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = t.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
        titleSmall = t.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = t.bodySmall.copy(fontSize = 14.sp, lineHeight = 19.sp),
        labelLarge = t.labelLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
        labelMedium = t.labelMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
        labelSmall = t.labelSmall.copy(fontSize = 12.sp, lineHeight = 15.sp),
    )
}

@Composable
fun OnPathTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = OnPathType, content = content)
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

/** Name on the primary button: "Start navigation · Gravel bike". */
val Activity.navName: String
    get() = label
