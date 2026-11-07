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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.shadow
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
    /** Secondary text: detail lines, explanations (Material `onSurfaceVariant`). */
    val InkMuted = Color(0xFF4A4F4A)
    /** White: fields one types into, rings around point signs and text / icons on a coloured button – no surfaces. */
    val CardWhite = Color(0xFFFFFFFF)
    /** Page background (website `#FAFAF6`). */
    val Paper = Color(0xFFFAFAF6)
    /**
     * Paper one shade darker (owner 2026-11-06: "überall den Papierton"): the page behind paper cards (Settings, My
     * routes) and boxes inside a paper surface – so the cards and the facts box still stand out without any white.
     */
    val PaperDim = Color(0xFFF1F0E9)
    val Route = Color(0xFF1E3FAE)
    /**
     * The plan's numbered points, on the map and in the Points list (owner 2026-11-01, like Komoot): the start in the
     * app's dark green, stops near black, the goal dark red – no white ring; they stand apart from the blue route by hue.
     * Keep in step with MapController.POINT_*.
     */
    val PointStart = Color(0xFF245F46)
    val PointStop = Color(0xFF262A2E)
    val PointGoal = Color(0xFFA32020)
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

    // ---- Actions: one colour = one kind of action (owner 2026-11-02, docs/design/ui-style.md "Colour rule") ----
    /**
     * Navigation: start / continue / pause / end, back to the guidance – everything that goes to the navigation is this
     * one green (owner 2026-11-06: "alles blau, und was zur Navigation geht, grün"; white 7.5:1). It is [Green]: the big
     * green buttons used to take the theme's primary, which is [Walnut] now.
     */
    val ActionGo = Color(0xFF245F46)
    /**
     * Edit / plan: change the route – ink blue (owner 2026-11-06 23:00, variant C of the blue page: "das Blau sieht
     * verspielt aus"; like the trail marks on a printed map; white on it 8.9:1, it on paper 8.5:1), told apart from
     * [ActionGo]'s forest green by hue. Calmer than the route line's [Route].
     */
    val ActionEdit = Color(0xFF2B4C6F)
    /** The light ink blue of a plan button that is not the main one (Planen while locked, Speichern of a draft): ink ≥ 7:1. */
    val EditTonal = Color(0xFFDCE3EC)
    val OnEditTonal = Color(0xFF1B2F45)
    /**
     * Neutral, the theme's primary (owner 2026-11-06, onpath.private#36 "Wir nehmen erstmal Walnuss"): every button
     * without a meaning of its own – Cancel, OK, Close, settings – a dark earth tone, so green means only "to the
     * navigation" and blue only "plan" (white 10.2:1; told apart from green also with red-green weakness).
     */
    val Walnut = Color(0xFF4A3F37)
    /**
     * The map compass's needle (owner 2026-11-07 00:50: "die rote Nadel ist unstylisch – ein blauer Kompass"): north half ink
     * blue ([ActionEdit], 8.5:1 on paper), south half a walnut-tinted grey (3.6:1 on paper, ≥ 3:1 for a graphic) – no red
     * (red only deletes). The "N" above it in [Walnut], the calmer of the two.
     */
    val CompassNorth = ActionEdit
    val CompassSouth = Color(0xFF8C827A)
    /** Light walnut container (selected rows, chips): [Walnut] ink on it ≥ 8:1. */
    val WalnutLight = Color(0xFFEDE6DF)

    // ---- Plan mode (owner 2026-11-06 23:00, variant C): recognised by signs, not by tinted surfaces – the PLANEN pill,
    // the sheet's blue top edge and handle, blue icons and blue buttons; no light blue surfaces, no blue glow round the
    // screen (they made the warm paper app look cool and playful) ----
    /** Plan's accent: search glass, ☰, map-button icons, the sheet's handle + top edge, the mode label – [ActionEdit]. */
    val PlanAccent = ActionEdit
    /** Plan's surfaces (sheet, search pill, ☰, map buttons): paper like every surface – no blue tint any more (variant C). */
    val PlanTint = Paper
    /** The "Ohne Netz ✓" sign on the plan's mode pill ([PlanAccent]): a light green, ≥ 4.5:1 on it (OfflineReady). */
    val OnPlanGood = Color(0xFFB9F6CA)
    /** The "Ohne Netz ⚠" sign on the plan's mode pill: a light amber, ≥ 4.5:1 on [PlanAccent]. */
    val OnPlanWarn = Color(0xFFFFE082)

    // ---- Message kinds (docs/design/ui-style.md): one colour = one meaning ----
    /** FRAGE: the blue left edge of a question box (MessageCard) – the ink blue ([ActionEdit], variant C), never a status. */
    val InfoBlue = ActionEdit
    /**
     * Tags (ui-style.md "Tags"): the light blue pill after a name that says the item's state in one word ("New",
     * "Changed" in My routes) – the ink blue family ([EditTonal] / [OnEditTonal]), ink ≥ 7:1 on the container.
     */
    val TagContainer = EditTonal
    val TagInk = OnEditTonal
    /** Attention: amber text / icons on white (route spots, off route, no GPS, a download that failed, HINWEIS_WARN edge). */
    val Warn = Color(0xFF9A5B00)
    val WarnContainer = Color(0xFFFFF0D2)
    val OnWarnContainer = Color(0xFF2C1A00)
    /** ERROR: something the user asked for did not work (HINWEIS_ERROR edge, red lines, delete buttons). */
    val Error = Color(0xFFC62828)
    val ErrorContainer = Color(0xFFFDE0DC)
    val OnErrorContainer = Color(0xFF5F1410)

    /** Aliases where the colour's name reads better at the call site. */
    val Amber = Warn
    val Bad = Error
}

/**
 * The colour of a button by what it does (owner 2026-11-06, docs/design/flow-map.md "Colour rule audit"): to the
 * navigation green ([OnPathColors.ActionGo]), plan blue ([OnPathColors.ActionEdit]); without either the theme's walnut.
 */
object ActionButtons {
    @Composable fun go() = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionGo, contentColor = Color.White)
    @Composable fun edit() = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionEdit, contentColor = Color.White)
    @Composable fun goText() = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = OnPathColors.ActionGo)
    @Composable fun editText() = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = OnPathColors.ActionEdit)
    @Composable fun editOutlined() = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = OnPathColors.ActionEdit)
    /** Pause in the plan's tour row: the light green of Navigate's Pause ([TourActions.GoTonal]). */
    @Composable fun goTonal() = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
        containerColor = TourActions.GoTonal, contentColor = TourActions.OnGoTonal)
}

private val scheme = lightColorScheme(
    // neutral walnut (#36) – navigation green and plan blue are set where a button means one of them ([ActionButtons])
    primary = OnPathColors.Walnut,
    onPrimary = Color.White,
    primaryContainer = OnPathColors.WalnutLight,
    onPrimaryContainer = Color(0xFF2B231D),
    secondary = OnPathColors.Route,
    onSecondary = Color.White,
    // selected chips / segments (FilterChip, SegmentedButton, the activity chip) are neutral walnut, not light blue
    // (owner 2026-11-06: neutral = walnut; the light blue made every choice look like "plan")
    secondaryContainer = OnPathColors.WalnutLight,
    onSecondaryContainer = Color(0xFF2B231D),
    // paper is the ground of every surface (owner 2026-11-06), also what floats over the map (search pill, pin card,
    // message boxes, Navigate's cards – the shadow lifts them off the map); white only for fields one types into
    surface = OnPathColors.Paper,
    onSurface = OnPathColors.Ink,
    surfaceVariant = Color(0xFFEFEEE7),
    onSurfaceVariant = OnPathColors.InkMuted,
    background = OnPathColors.PaperDim,
    onBackground = OnPathColors.Ink,
    tertiary = OnPathColors.Warn,
    onTertiary = Color.White,
    tertiaryContainer = OnPathColors.WarnContainer,
    onTertiaryContainer = OnPathColors.OnWarnContainer,
    error = OnPathColors.Error,
    errorContainer = OnPathColors.ErrorContainer,
    onErrorContainer = OnPathColors.OnErrorContainer,
    inversePrimary = Color(0xFFD9C8BA),
    outline = Color(0xFF737970),
    outlineVariant = Color(0xFFC3C8C0),
    // Neutral (slightly green) containers instead of Material's default purple tint:
    // menus, sheets, cards and chips all take these.
    surfaceTint = OnPathColors.Walnut,
    surfaceBright = OnPathColors.Paper,
    surfaceDim = Color(0xFFDADDD7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = OnPathColors.Paper,
    surfaceContainer = OnPathColors.Paper,       // menus
    surfaceContainerHigh = OnPathColors.Paper,   // dialogs
    surfaceContainerHighest = Color(0xFFEAE9E1), // filled fields, a lifted row – one step darker than paper
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

/**
 * Numbers like an instrument (owner 2026-11-06, polish 3): in a value the digits are big and semi-bold, the unit one
 * step of the type scale smaller ([unitSize]), every digit the same width ("tnum") – so Navigate's time and distance
 * never jiggle while they count, and **2,9** km · **↑ 82** m · **50** min reads at a glance. The text itself is not
 * changed (screen readers, tests and copy read the same words); only spans are laid over it. Pure, unit-tested.
 */
object Instrument {
    /** One run of a value text: [number] = digits (with their , . : inside) and a leading ↑ / ↓ / + / −. */
    data class Run(val start: Int, val end: Int, val number: Boolean)

    private fun digit(c: Char) = c in '0'..'9'

    /** The text cut into number and unit runs, covering it whole and in order. */
    fun runs(text: String): List<Run> {
        if (text.isEmpty()) return emptyList()
        fun isNum(i: Int): Boolean {
            val c = text[i]
            if (digit(c)) return true
            // a separator inside a number: 2,9 · 2.9 · 2:50
            if ((c == ',' || c == '.' || c == ':') && i > 0 && i + 1 < text.length && digit(text[i - 1]) && digit(text[i + 1])) return true
            // a sign right before the number ("↑ 82", "↑82", "+1,2"): belongs to it
            if (c == '↑' || c == '↓' || c == '+' || c == '−') {
                var j = i + 1
                while (j < text.length && text[j] == ' ') j++
                return j < text.length && digit(text[j])
            }
            // the space between a sign and its digits
            if (c == ' ' && i > 0 && text[i - 1] in "↑↓" && i + 1 < text.length && digit(text[i + 1])) return true
            return false
        }
        val out = mutableListOf<Run>()
        var start = 0
        var num = isNum(0)
        for (i in 1 until text.length) {
            val n = isNum(i)
            if (n != num) { out += Run(start, i, num); start = i; num = n }
        }
        out += Run(start, text.length, num)
        return out
    }

    /** The unit's size: one step down the scale (30 → 22, 22 → 16, 16 → 14); 14 and 12 stay (nothing below 14 to read). */
    fun unitSize(base: androidx.compose.ui.unit.TextUnit): androidx.compose.ui.unit.TextUnit = when {
        base == androidx.compose.ui.unit.TextUnit.Unspecified -> base
        base.value >= 30f -> 22.sp
        base.value >= 22f -> 16.sp
        base.value >= 16f -> 14.sp
        else -> base
    }

    /**
     * [text] (a formatted value or a line of them: "4 h 36 min", "12,5 km · ↑ 842 m") with big digits and small units
     * for a Text whose style is [base]. [unitColor] dims the units (Unspecified = the text's colour).
     */
    fun styled(text: String, base: androidx.compose.ui.text.TextStyle, unitColor: Color = Color.Unspecified,
               numberWeight: FontWeight = FontWeight.SemiBold): androidx.compose.ui.text.AnnotatedString =
        androidx.compose.ui.text.buildAnnotatedString {
            append(text)
            // a bold style (the display step) keeps its weight; lighter ones get [numberWeight]
            val w = base.fontWeight?.takeIf { it.weight > numberWeight.weight } ?: numberWeight
            val num = androidx.compose.ui.text.SpanStyle(fontWeight = w, fontFeatureSettings = "tnum")
            val unit = androidx.compose.ui.text.SpanStyle(fontSize = unitSize(base.fontSize), fontWeight = FontWeight.Normal,
                color = unitColor)
            for (r in runs(text)) addStyle(if (r.number) num else unit, r.start, r.end)
        }
}

/** [Instrument.styled] with the current theme's type scale – for `Text(instrument(…, style), style = style)`. */
fun instrument(text: String, base: androidx.compose.ui.text.TextStyle, unitColor: Color = Color.Unspecified) =
    Instrument.styled(text, base, unitColor)

@Composable
fun OnPathTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = OnPathType, shapes = OnPathShapes.material, content = content)
}

/** The theme's values outside composition (tests). */
object OnPathThemeValues {
    val colors get() = scheme
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
 * One corner family (owner 2026-11-06, polish 6 "Ecken aus einem Guss"): pills and buttons fully round, cards and boxes
 * 20 dp, small elements (a row's highlight, a bar, a badge area, a field) 12 dp; sheets and dialogs keep Material's 28 dp.
 * The Material shapes follow, so chips, cards, menus and fields take the family without a value of their own.
 */
object OnPathShapes {
    const val PILL_PERCENT = 50
    const val CARD_DP = 20
    const val SMALL_DP = 12
    const val SHEET_DP = 28
    val Pill = androidx.compose.foundation.shape.RoundedCornerShape(percent = PILL_PERCENT)
    val Card = androidx.compose.foundation.shape.RoundedCornerShape(CARD_DP.dp)
    val Small = androidx.compose.foundation.shape.RoundedCornerShape(SMALL_DP.dp)
    val material = androidx.compose.material3.Shapes(
        extraSmall = Small, small = Small, medium = Card, large = Card,
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(SHEET_DP.dp),
    )
}

/**
 * Two shadow heights in a warm walnut tone instead of Material's grey-black (owner 2026-11-06, polish 6): [LOW] for what
 * sits on a page or the map's edge (settings cards, map buttons, the search pill, small pills), [HIGH] for what floats
 * over the map and asks for attention (message boxes, sheets, the pin and place cards, a lifted row). Use [warmShadow]
 * on the Surface's modifier instead of its shadowElevation.
 */
object OnPathShadow {
    val LOW = 2.dp
    val HIGH = 6.dp
    /** The shadow's tone: dark walnut (Android draws it with the system's shadow alphas, so it stays soft). */
    val Tone = Color(0xFF3B2A1E)

    /** The old free elevations mapped to the two heights: up to 3 dp = [LOW], above = [HIGH], none = none. */
    fun level(elevation: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp = when {
        elevation <= 0.dp -> 0.dp
        elevation <= 3.dp -> LOW
        else -> HIGH
    }
}

/** A warm shadow ([OnPathShadow]) under [shape]; put it last on a Surface's modifier and leave its shadowElevation at 0. */
fun androidx.compose.ui.Modifier.warmShadow(elevation: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape): androidx.compose.ui.Modifier =
    if (elevation <= 0.dp) this
    else this.then(androidx.compose.ui.Modifier.shadow(elevation, shape, clip = false,
        ambientColor = OnPathShadow.Tone, spotColor = OnPathShadow.Tone))
