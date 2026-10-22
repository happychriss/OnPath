package com.onpath.app.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.poi.Poi
import com.onpath.app.routing.Activity
import com.onpath.app.ui.MapPlaceholder
import com.onpath.app.ui.OnPathTheme
import com.onpath.app.ui.PlanTopBar
import com.onpath.app.ui.search.OnlineState
import com.onpath.app.ui.search.PlaceCard
import com.onpath.app.ui.search.PlaceCardUi
import com.onpath.app.ui.search.SearchActions
import com.onpath.app.ui.search.SearchScreen
import com.onpath.app.ui.search.SearchUi
import org.junit.Rule
import org.junit.Test

/** Screenshots of the search view, its empty state and the place card (390 dp width). */
class SearchScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420),
        ),
        showSystemUi = false,
    )

    private fun hit(p: Place, d: Double, near: Boolean = false) = SearchHit(p, 0.0, 2, d, near)

    private val hut = Place("n1", "Meraner Hütte", "hut", 46.732, 11.091, ele = 1960,
        poi = Poi("n1", "hut", "Meraner Hütte", 46.732, 11.091, 1960, openingHours = "Jun-Oct Mo-Su 07:00-22:00"))

    /** One mixed list (owner 2026-10-22): offline places, trails, an address with its village, an online result. */
    @Test fun searchResults() = paparazzi.snapshot {
        OnPathTheme {
            SearchScreen(SearchUi(
                query = "meran",
                hits = listOf(
                    SearchHit(Place("m1", "Meran", "place", 46.67, 11.16, sub = "town", source = Place.Source.MAP), 128.0, 3, 6100.0, false),
                    SearchHit(hut, 98.0, 2, 2400.0, true),
                    SearchHit(Place("t1", "Meraner Höhenweg", "trail", 46.75, 11.05, source = Place.Source.TRAIL), 96.0, 2, 900.0, true),
                    SearchHit(Place("a:c:1:5", "Meraner Straße 5 · Völlan", "address", 46.61, 11.12, address = "Via Merano 5 · 39011",
                        source = Place.Source.MAP, town = "Lana", village = "Völlan"), 80.0, 2, 4200.0, false),
                    SearchHit(Place("n2", "Gasthaus Merana", "restaurant", 46.6, 11.2), 70.0, 2, 8300.0, false),
                ),
                online = listOf(SearchHit(Place("o:N1", "Meraner Straße 12", "address", 46.5, 11.35, address = "39100 Bozen",
                    source = Place.Source.ONLINE), 75.0, 2, 25300.0, false)),
                onlineState = OnlineState.DONE, hasRoute = true,
            ), SearchActions(), autoFocus = false)
        }
    }

    @Test fun searchEmptyOffline() = paparazzi.snapshot {
        OnPathTheme {
            SearchScreen(SearchUi(query = "Spar Algund", category = SearchKinds.Category.STATIONS, onlineState = OnlineState.FAILED),
                SearchActions(), autoFocus = false)
        }
    }

    @Test fun searchRecent() = paparazzi.snapshot {
        OnPathTheme {
            SearchScreen(SearchUi(recent = listOf(hit(hut, 2400.0),
                hit(Place("m1", "Algund", "place", 46.68, 11.12, sub = "village", source = Place.Source.MAP), 3100.0))),
                SearchActions(), autoFocus = false)
        }
    }

    @Test fun placeCard() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    PlanTopBar({}, com.onpath.app.ui.PlanMenu(), Modifier.fillMaxWidth())
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp)) {
                        PlaceCard(PlaceCardUi(hut, null, 3, "as stop 2 – fits best · about +0.9 km, +15 min"),
                            com.onpath.app.ui.search.PlaceCardActions())
                        PlaceCard(PlaceCardUi(Place("o:N1", "Laubengasse 10", "address", 46.67, 11.16, address = "39012 Meran",
                            source = Place.Source.ONLINE), 2, 3, "is the destination"), com.onpath.app.ui.search.PlaceCardActions())
                        // a shared place positioned from its address keeps the note (owner 2026-10-22)
                        PlaceCard(PlaceCardUi(Place("shared:1", "Gelateria Costantin", "other", 46.67, 11.16,
                            address = "Passeggiata Lungo Passirio 1, Meran", note = SharedPosition.NOTE)),
                            com.onpath.app.ui.search.PlaceCardActions())
                    }
                }
            }
        }
    }
}
