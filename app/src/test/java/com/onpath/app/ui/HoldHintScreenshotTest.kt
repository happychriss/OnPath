package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.domain.PlaceAddMenu
import com.onpath.app.poi.Poi
import com.onpath.app.poi.PoiDetails
import com.onpath.app.poi.PoiSymbol
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Chriss 2026-11-28: (a) the pill „Halten, um hier zu planen“ at a tap on the empty map (09:41, F-PLAN-24); the place info's
 * list (10:02, picture ort-info-aktionen, F-PLAN-25) – the hold menu's rows under the details, the first one tinted, Google
 * Maps last: empty plan with and without GPS, with a plan, read-only (Navigate). Pixel 8 (≈ 411 dp) and 360 dp, font 1.0
 * and 1.3, German.
 */
abstract class HoldHintShots(dpi: Int, font: Float) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2400, xdpi = dpi, ydpi = dpi,
            density = Density.create(dpi), fontScale = font),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    /** The glyph, not the sprite (loaded late, flaky in pictures). */
    private val glyph = PoiSymbol(null, true)
    private val curry = Poi("n1", "fast_food", "Curry 73", 51.16, 6.94, openingHours = "Mo-Sa 11:00-21:00")

    /** (a) a tap at 40 % of the width, 40 % down; and one at the right edge (the pill stays inside the map). */
    @Test fun pill() = paparazzi.snapshot { OnPathTheme { Box(Modifier.fillMaxSize()) {
        MapPlaceholder(Modifier.fillMaxSize())
        HoldPill(HoldPillState().apply { show(Offset(430f, 960f)) })
        HoldPill(HoldPillState().apply { show(Offset(1070f, 1500f)) })
    } } }

    private fun info(empty: Boolean, blocked: String? = null, menu: PlaceAddMenu.Menu? = null) = paparazzi.snapshot { OnPathTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            PoiDetails(curry, onClose = {}, symbol = glyph, actions = {
                PoiMenu(curry, onAdd = {}, onGoogle = {}, emptyPlan = empty, fromMeBlocked = blocked, menu = menu,
                    favourite = false, onRoundTrip = if (empty) ({}) else null, rowsOnly = true, tintFirst = true)
            })
        }
    } }

    /** Empty plan with a position: Als Ziel (tinted) · Als Start | Rundtouren vorschlagen | Merken · Google Maps ↗. */
    @Test fun placeInfoEmptyPlan() = info(empty = true)
    /** Without a fix: Als Start first (tinted), „Als Ziel“ greyed „GPS wird gesucht…“. */
    @Test fun placeInfoNoGps() = info(empty = true, blocked = "GPS wird gesucht…")
    /** With a plan: Als Stopp (tinted) · Als Ziel | Merken · Google Maps ↗. */
    @Test fun placeInfoWithPlan() = info(empty = false,
        menu = PlaceAddMenu.Menu(listOf(PlaceAddMenu.Item.ADD_STOP, PlaceAddMenu.Item.NEW_DESTINATION)))

    /** Navigate / a shown track: Google Maps alone, as before. */
    @Test fun placeInfoReadOnly() = paparazzi.snapshot { OnPathTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { PoiDetails(curry, onClose = {}, symbol = glyph) }
    } }
}

class HoldHintPixel8Test : HoldHintShots(420, 1.0f)
class HoldHintPixel8LargeFontTest : HoldHintShots(420, 1.3f)
class HoldHint360Test : HoldHintShots(480, 1.0f)
class HoldHint360LargeFontTest : HoldHintShots(480, 1.3f)
