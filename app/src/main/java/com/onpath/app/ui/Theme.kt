package com.onpath.app.ui

import com.onpath.strings.*
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
    /**
     * Primary: the muted forest green of onpath.earth (white on it 7.5:1). The launcher icon keeps its brighter
     * `#1F6E43` (res/values/colors.xml) – the logo is separate from the UI palette.
     */
    val Green = Color(0xFF245F46)
    /**
     * Light olive container (selected picker row, "easy" badge): two shades stronger than the website's `#EDF1E3`, so
     * it still stands out on the grey-green dialogs in sunlight.
     */
    val GreenLight = Color(0xFFE1EAD5)
    /** Text on light surfaces: the website's dark green-grey (11.4:1 on the background). */
    val Ink = Color(0xFF243C31)
    /** Page background (website `#FAFAF6`). */
    val Paper = Color(0xFFFAFAF6)
    val Route = Color(0xFF1E3FAE)
    val Gpx = Color(0xFF8E24AA)
    val Rec = Color(0xFFEF6C00)
    val Good = Color(0xFF2E7D32)
    /** GPS "weak" dot only (map furniture): bright, never for text. */
    val Weak = Color(0xFFF9A825)
    val Grey = Color(0xFF6B6F6A)
    /** Own position / "where I face" arrow on the map (map furniture, like the location puck). */
    val Position = Color(0xFF1A73E8)
    /**
     * The "where I face" arrow while the compass is unsure (map/CompassTrust, owner: grey, never hidden). Same as
     * res/drawable/onpath_bearing_unsure.xml.
     */
    val PositionUnsure = Color(0xFF8A8F94)

    // ---- Rest view while cycling (ui/RestView.kt): black = OLED pixels off; grey, not white, so it does not dazzle ----
    val RestBackground = Color(0xFF000000)
    /** Arrow and distance: 10:1 on black – readable at a glance in the sun at the phone's normal brightness. */
    val RestInk = Color(0xFFB3B3B3)
    /** Direction, time left, arrival, the tap hint: 6:1 on black (above the 4.5:1 text minimum). */
    val RestDim = Color(0xFF8A8A8A)
    /**
     * Small secondary text on the dark Navigate slider track ("Change route · Pause · End"): the rest view's grey,
     * ≥ 5:1 on the track even over a white map.
     */
    val OnDarkMuted = RestInk

    // ---- Message kinds: one colour = one meaning ----
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

    /** Aliases where the colour's name reads better at the call site. */
    val Amber = Warn
    val Bad = Error
}

private val scheme = lightColorScheme(
    primary = OnPathColors.Green,
    onPrimary = Color.White,
    primaryContainer = OnPathColors.GreenLight,
    onPrimaryContainer = Color(0xFF173A29),
    secondary = OnPathColors.Route,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE3F7),
    onSecondaryContainer = Color(0xFF0E1E57),
    surface = Color.White,
    onSurface = OnPathColors.Ink,
    surfaceVariant = Color(0xFFF1F3EF),
    onSurfaceVariant = Color(0xFF4A4F4A),
    background = OnPathColors.Paper,
    onBackground = OnPathColors.Ink,
    tertiary = OnPathColors.Warn,
    onTertiary = Color.White,
    tertiaryContainer = OnPathColors.WarnContainer,
    onTertiaryContainer = OnPathColors.OnWarnContainer,
    error = OnPathColors.Error,
    errorContainer = OnPathColors.ErrorContainer,
    onErrorContainer = OnPathColors.OnErrorContainer,
    inversePrimary = Color(0xFFA6CBAF),
    outline = Color(0xFF737970),
    outlineVariant = Color(0xFFC3C8C0),
    // Neutral (slightly green) containers instead of Material's default purple tint:
    // menus, sheets, cards and chips all take these.
    surfaceTint = OnPathColors.Green,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDADDD7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = OnPathColors.Paper,
    surfaceContainer = Color(0xFFF1F3EF),
    surfaceContainerHigh = Color(0xFFEBEEE9),
    surfaceContainerHighest = Color(0xFFE5E8E3),
)

/**
 * One type scale, five steps (all sp, so the phone's font size applies):
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

/**
 * Name on buttons: "Start navigation · Gravel bike". German uses the short MTB / E-MTB there (the long name gets cut);
 * the picker keeps the full names ([Activity.label]).
 */
val Activity.navName: String
    get() = Txt.s(when (this) {
        Activity.BIKE_TOURING -> Res.string.activity_bike_touring_button
        Activity.BIKE_TREKKING -> Res.string.activity_bike_trekking_button
        Activity.EBIKE -> Res.string.activity_ebike_button
        Activity.MTB -> Res.string.activity_mtb_button
        Activity.EMTB -> Res.string.activity_emtb_button
        Activity.HIKE -> Res.string.activity_hike_button
    })
