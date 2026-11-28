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
import com.onpath.app.poi.Poi
import com.onpath.app.poi.PoiDetails
import com.onpath.app.poi.PoiGoal
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Chriss 2026-11-28 09:41 (picture halten-lernen-abc): (a) the pill „Halten, um hier zu planen“ at a tap on the empty map
 * (F-PLAN-24), (c) the place info with „Als Ziel“ + „Google Maps ↗“ (F-PLAN-25). Pixel 8 (≈ 411 dp) and 360 dp, font 1.0 and
 * 1.3, German – at large font the two buttons stand one above the other, nothing cut.
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
    private val glyph = com.onpath.app.poi.PoiSymbol(null, true)
    private val curry = Poi("n1", "fast_food", "Curry 73", 51.16, 6.94, openingHours = "Mo-Sa 11:00-21:00")

    /** (a) a tap at 40 % of the width, 40 % down; and one at the right edge (the pill stays inside the map). */
    @Test fun pill() = paparazzi.snapshot { OnPathTheme { Box(Modifier.fillMaxSize()) {
        MapPlaceholder(Modifier.fillMaxSize())
        HoldPill(HoldPillState().apply { show(Offset(430f, 960f)) })
        HoldPill(HoldPillState().apply { show(Offset(1070f, 1500f)) })
    } } }

    /** (c) the place info in Plan: „Als Ziel“ + „Google Maps ↗“. */
    @Test fun placeInfoGoal() = paparazzi.snapshot { OnPathTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { PoiDetails(curry, onClose = {}, symbol = glyph, goal = PoiGoal("Als Ziel") {}) }
    } }

    /** Navigate / no usable „Als Ziel“: Google Maps alone, as before. */
    @Test fun placeInfoReadOnly() = paparazzi.snapshot { OnPathTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { PoiDetails(curry, onClose = {}, symbol = glyph) }
    } }
}

class HoldHintPixel8Test : HoldHintShots(420, 1.0f)
class HoldHintPixel8LargeFontTest : HoldHintShots(420, 1.3f)
class HoldHint360Test : HoldHintShots(480, 1.0f)
class HoldHint360LargeFontTest : HoldHintShots(480, 1.3f)
