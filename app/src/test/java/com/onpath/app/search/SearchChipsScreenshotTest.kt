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
 * The six search chips (owner 2026-10-31: Orte · Essen · Sehenswert · Wasser · Bahn · Parken), "Food" chosen, in English
 * and German, normal and at the phone's largest font (2.0): the row scrolls sideways, no chip is ever cut.
 */
open class SearchChipsScreenshotTest {
    protected open val fontScale: Float get() = 1.0f

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = fontScale, screenHeight = 1400), showSystemUi = false)

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

class SearchChipsLargeFontTest : SearchChipsScreenshotTest() {
    override val fontScale: Float get() = 2.0f
}

class SearchChipsLargeFontGermanTest : SearchChipsScreenshotTest() {
    override val fontScale: Float get() = 2.0f
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
