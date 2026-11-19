package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.domain.HomeRule
import com.onpath.app.search.Place
import com.onpath.app.search.SearchHit
import com.onpath.app.ui.search.HomeMenuItems
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
 * „⌂ Als Zuhause festlegen“ and its set state. English, German, German at a larger font.
 *
 * Zuhause „like Google Maps“ (owner 2026-11-18, [HomeRule]): the menu with no home yet (the set row), on the home (the
 * remove row), on another place while a home exists (no home row); the empty search without a home („⌂ Zuhause
 * festlegen“ + the line a tap shows) and with one (⋮: Ändern / Entfernen, and the line after „Ändern“).
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

    /** No home set: „⌂ Zuhause festlegen“ on top; [hint] = after a tap on it, the line how. */
    fun searchNoHome(paparazzi: Paparazzi, hint: Boolean) = paparazzi.snapshot {
        OnPathTheme {
            SearchScreen(SearchUi(favourites = favourites.take(2), recent = recent, homeHint = hint), SearchActions(), autoFocus = false)
        }
    }

    /** Home set: its ⋮ open (the items drawn on their own – a popup is not in a Paparazzi picture). */
    fun searchHomeMenu(paparazzi: Paparazzi, top: Int = 148) = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                SearchScreen(SearchUi(home = hit(home, 4_200.0), favourites = favourites.take(2), recent = recent), SearchActions(), autoFocus = false)
                Surface(Modifier.align(Alignment.TopEnd).padding(top = top.dp, end = 8.dp).width(160.dp), shape = OnPathShapes.Small,
                    shadowElevation = 3.dp, tonalElevation = 3.dp) {
                    Column(Modifier.padding(vertical = 8.dp)) { HomeMenuItems({}, {}) {} }
                }
            }
        }
    }

    /** After ⋮ „Ändern“: the line under the home row. */
    fun searchHomeChange(paparazzi: Paparazzi) = paparazzi.snapshot {
        OnPathTheme {
            SearchScreen(SearchUi(home = hit(home, 4_200.0), favourites = favourites.take(2), recent = recent, homeChange = true),
                SearchActions(), autoFocus = false)
        }
    }

    fun menu(paparazzi: Paparazzi, favourite: Boolean, home: HomeRule.MenuRow) = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                PoiMenu(com.onpath.app.poi.Poi("n1", "cafe", "Waldcafé Lindenhof", 51.2, 6.9), onAdd = {}, onGoogle = {},
                    emptyPlan = true, favourite = favourite, home = home, modifier = Modifier.padding(start = 16.dp, top = 120.dp))
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
    @Test fun searchNoHome() = KeptPlacesShots.searchNoHome(paparazzi, hint = false)
    @Test fun searchNoHomeHint() = KeptPlacesShots.searchNoHome(paparazzi, hint = true)
    @Test fun searchHomeMenu() = KeptPlacesShots.searchHomeMenu(paparazzi)
    @Test fun searchHomeChange() = KeptPlacesShots.searchHomeChange(paparazzi)
    /** No home yet: „⌂ Als Zuhause festlegen“. */
    @Test fun placeMenuKeep() = KeptPlacesShots.menu(paparazzi, favourite = false, home = HomeRule.MenuRow.SET)
    /** The place is the home (and a favourite): „⌂ Zuhause entfernen“. */
    @Test fun placeMenuKept() = KeptPlacesShots.menu(paparazzi, favourite = true, home = HomeRule.MenuRow.REMOVE)
    /** Another place while a home exists: no home row. */
    @Test fun placeMenuOtherHome() = KeptPlacesShots.menu(paparazzi, favourite = false, home = HomeRule.MenuRow.NONE)
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
    @Test fun searchNoHomeHint() = KeptPlacesShots.searchNoHome(paparazzi, hint = true)
    @Test fun searchHomeMenu() = KeptPlacesShots.searchHomeMenu(paparazzi)
    @Test fun searchHomeChange() = KeptPlacesShots.searchHomeChange(paparazzi)
    @Test fun placeMenuKeep() = KeptPlacesShots.menu(paparazzi, favourite = false, home = HomeRule.MenuRow.SET)
    @Test fun placeMenuKept() = KeptPlacesShots.menu(paparazzi, favourite = true, home = HomeRule.MenuRow.REMOVE)
    @Test fun placeMenuOtherHome() = KeptPlacesShots.menu(paparazzi, favourite = false, home = HomeRule.MenuRow.NONE)
}
