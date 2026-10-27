package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Owner 2026-10-27 ("die komischen schwarzen Status-Codes … rausnehmen"): no dark message bar. A result goes where the
 * user acted (a line in My routes / Settings), an error without such a place gets the light error card, an INFO without
 * a place is not shown. Review 2026-10-24 A / B10 / C9.
 */
class NoticesTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 1200, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    private fun surface(kind: NoticeKind, place: NoticePlace, routes: Boolean = false, settings: Boolean = false) =
        NoticeRouting.surface(kind, place, routesOpen = routes, settingsOpen = settings)

    @Test fun aResultGoesWhereTheUserActed() {
        for (kind in NoticeKind.entries) {
            assertEquals(NoticeSurface.ROUTES_LINE, surface(kind, NoticePlace.ROUTES, routes = true))
            assertEquals(NoticeSurface.SETTINGS_LINE, surface(kind, NoticePlace.SETTINGS, settings = true))
            // the other screen being open does not matter: the line belongs to the place of the action
            assertEquals(NoticeSurface.ROUTES_LINE, surface(kind, NoticePlace.ROUTES, routes = true, settings = true))
            assertEquals(NoticeSurface.SETTINGS_LINE, surface(kind, NoticePlace.SETTINGS, routes = true, settings = true))
        }
    }

    @Test fun anErrorWithoutItsPlaceGetsTheCard() {
        // the place was closed meanwhile (import from another app, restore offer, update banner on the map) …
        assertEquals(NoticeSurface.ERROR_CARD, surface(NoticeKind.ERROR, NoticePlace.ROUTES))
        assertEquals(NoticeSurface.ERROR_CARD, surface(NoticeKind.ERROR, NoticePlace.SETTINGS, routes = true))
        // … or there is none: the map, the background (recording, backup, a crash)
        for (open in listOf(false, true)) {
            assertEquals(NoticeSurface.ERROR_CARD, surface(NoticeKind.ERROR, NoticePlace.MAP, routes = open, settings = open))
            assertEquals(NoticeSurface.ERROR_CARD, surface(NoticeKind.ERROR, NoticePlace.BACKGROUND, routes = open, settings = open))
        }
    }

    @Test fun anInfoWithoutItsPlaceIsNotShown() {
        assertEquals(NoticeSurface.LOG_ONLY, surface(NoticeKind.INFO, NoticePlace.ROUTES))
        assertEquals(NoticeSurface.LOG_ONLY, surface(NoticeKind.INFO, NoticePlace.SETTINGS, routes = true))
        for (open in listOf(false, true)) {
            assertEquals(NoticeSurface.LOG_ONLY, surface(NoticeKind.INFO, NoticePlace.MAP, routes = open, settings = open))
            assertEquals(NoticeSurface.LOG_ONLY, surface(NoticeKind.INFO, NoticePlace.BACKGROUND, routes = open, settings = open))
        }
    }

    /** The wiring (the view model needs Android): the old bar is gone, every message goes through say / sayError / sayInfo. */
    @Test fun noDarkBarAnyMore() {
        val main = File("src/main/java/com/onpath/app")
        val src = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }
        src.forEach { (name, text) ->
            assertTrue("$name: the dark bar look is gone", "inverseSurface" !in text && "Snackbar" !in text)
            assertTrue("$name: the old message API is gone", !Regex("""\bvm\.message\b|\bshowError\(|\bmessage = Txt\.""").containsMatchIn(text))
            assertTrue("$name: MessageBar is gone", !Regex("""\bMessageBar\(""").containsMatchIn(text))
        }
        val vm = src.getValue("AppViewModel.kt")
        // the lines last until their screen is left
        assertTrue(vm.contains("set(v) { if (!v) routesNote = null; routesShown = v }"))
        assertTrue(vm.contains("set(v) { if (!v) settingsNote = null; settingsShown = v }"))
        // results that are visible anyway are not said (owner example: "Track saved to My routes.")
        assertTrue("msg_track_saved" !in vm && "msg_track_to_bin" !in vm && "msg_round_trip_already" !in vm)
        // newer routing data changed the route: the plan sheet's grey line, a tap shows the route
        assertTrue(vm.contains("routeChangedNotice = Txt.s(Res.string.msg_route_updated_newer_data)"))
    }

    @Test fun errorCardAndLines() = paparazzi.snapshot {
        OnPathTheme {
            Column(Modifier.background(Color(0xFFE8EEDC)).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ErrorCard("The track cannot be saved any more (phone storage full or damaged). Free up space – recording keeps trying.", onOk = {})
                ErrorCard("Couldn't find this place. Try again when you have internet.", onOk = {})
                Surface(color = Color.White) {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        NoticeLine(Notice("1 track and 2 routes restored.", NoticeKind.INFO))
                        NoticeLine(Notice("Export failed – the phone's storage may be full. Your tracks and routes are unchanged.", NoticeKind.ERROR))
                    }
                }
                OutlinedButton(onClick = {}) { BusyContent(Busy.DIAGNOSTICS.text) }
            }
        }
    }
}
