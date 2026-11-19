package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.search.Place
import com.onpath.app.search.SearchHit
import com.onpath.app.ui.search.SearchActions
import com.onpath.app.ui.search.SearchScreen
import com.onpath.app.ui.search.SearchUi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * „Orte merken“ (owner 2026-11-15, compact sketch LqFT7h7KhEmKRdGJUVtrG9): the empty search with ⌂ Zuhause, ★ Favoriten
 * (five, then „Alle Favoriten ›“) and Zuletzt gesucht – one-line rows; the held place's menu with „☆ Merken“ /
 * „⌂ Als Zuhause festlegen“ and its set state – both Zuhause rows hidden since 2026-11-19 (HOME_ROW_SHOWN = false).
 * English, German, German at a larger font.
 */
object KeptPlacesShots {
    private fun hit(p: Place, d: Double) = SearchHit(p, 0.0, 0, d, false)

    private val home = Place("a:1", "Mittelstraße 12 · Hilden", "address", 51.17, 6.93, source = Place.Source.MAP)
    private val favourites = listOf(
        Place("n1", "Waldcafé Lindenhof", "cafe", 51.2, 6.9) to 12_000.0,
        Place("n2", "Burgruine Hohenstein", "ruins", 51.1, 7.0, ele = 412) to 18_000.0,
        Place("n3", "Meraner Hütte", "hut", 46.732, 11.091, ele = 1960) to 640_000.0,
        Place("n4", "Neandertal", "place", 51.22, 6.95, sub = "locality", source = Place.Source.MAP) to 9_000.0,
        Place("n5", "Parkplatz Sengbachtalsperre", "parking", 51.13, 7.1) to 21_000.0,
    ).map { (p, d) -> hit(p, d) }
    private val recent = listOf(
        Place("m1", "Algund", "place", 46.68, 11.12, sub = "village", source = Place.Source.MAP) to 3_100.0,
        Place("m2", "Gerresheim", "place", 51.23, 6.86, sub = "suburb", source = Place.Source.MAP) to 9_800.0,
        Place("m3", "Zons", "place", 51.12, 6.85, sub = "town", source = Place.Source.MAP) to 16_000.0,
    ).map { (p, d) -> hit(p, d) }

    fun search(paparazzi: Paparazzi) = paparazzi.snapshot {
        OnPathTheme {
            SearchScreen(SearchUi(home = hit(home, 4_200.0), favourites = favourites, moreFavourites = 3, recent = recent),
                SearchActions(), autoFocus = false)
        }
    }

    fun menu(paparazzi: Paparazzi, set: Boolean) = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                PoiMenu(com.onpath.app.poi.Poi("n1", "cafe", "Waldcafé Lindenhof", 51.2, 6.9), onAdd = {}, onGoogle = {},
                    emptyPlan = true, favourite = set, home = set, modifier = Modifier.padding(start = 16.dp, top = 120.dp))
            }
        }
    }
}

open class KeptPlacesScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    @Test fun searchKeptPlaces() = KeptPlacesShots.search(paparazzi)
    @Test fun placeMenuKeep() = KeptPlacesShots.menu(paparazzi, set = false)
    @Test fun placeMenuKept() = KeptPlacesShots.menu(paparazzi, set = true)
}

class KeptPlacesGermanScreenshotTest : KeptPlacesScreenshotTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** German at font size 1.3: the name stays whole, the grey part is cut first. */
class KeptPlacesLargeFontGermanTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 1.3f), showSystemUi = false)

    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    @Test fun searchKeptPlaces() = KeptPlacesShots.search(paparazzi)
    @Test fun placeMenuKeep() = KeptPlacesShots.menu(paparazzi, set = false)
}
