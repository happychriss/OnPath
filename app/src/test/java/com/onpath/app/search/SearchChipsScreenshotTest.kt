package com.onpath.app.search

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.ui.OnPathTheme
import com.onpath.app.ui.search.SearchActions
import com.onpath.app.ui.search.SearchScreen
import com.onpath.app.ui.search.SearchUi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The seven search categories (owner 2026-11-27, variant A: round signs, Orte · Sehenswert · Essen · Wasser · Bahn · Parken ·
 * Schlafen), "Food" chosen, German, normal, on a small phone and at the largest font (2.0): side by side when they fit,
 * else the row scrolls sideways – no word is ever cut.
 */
/** English frames dropped (test audit 2026-11-07: the German twin at the same font size is the check); the subclasses draw. */
abstract class SearchChipsScreenshotTest {
    protected open val fontScale: Float get() = 1.0f
    /** The screen's width in px at the Pixel 5's density (2.75): 1080 = 393 dp. */
    protected open val widthPx: Int get() = 1080

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = fontScale, screenWidth = widthPx, screenHeight = 1400), showSystemUi = false)

    @Test fun chips() = paparazzi.snapshot {
        OnPathTheme {
            SearchScreen(SearchUi(query = "", category = SearchKinds.Category.FOOD, hits = listOf(
                SearchHit(Place("n1", "Restaurant Olive", "restaurant", 51.1655, 6.9395), 90.0, 1, 600.0, false),
                SearchHit(Place("n2", "Meraner Hütte", "hut", 46.732, 11.091, ele = 1960), 80.0, 1, 2400.0, false),
            )), SearchActions(), autoFocus = false)
        }
    }
}

class SearchChipsGermanScreenshotTest : SearchChipsScreenshotTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}


class SearchChipsLargeFontGermanTest : SearchChipsScreenshotTest() {
    override val fontScale: Float get() = 2.0f
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** A small phone, 360 dp (owner 2026-11-27: „wie sieht es bei einem kleineren Handy aus?“): all seven signs still side by side. */
class SearchChipsSmallPhoneGermanTest : SearchChipsScreenshotTest() {
    override val widthPx: Int get() = 990
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
