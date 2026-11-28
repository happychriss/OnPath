package com.onpath.app.ui.routes

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.OnPathTheme
import com.onpath.app.ui.RoutesScreen
import com.onpath.app.domain.RoutesTab
import androidx.compose.ui.text.TextLayoutResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.math.sin

/**
 * Saved multi-day routes in My routes (owner 2026-11-27, "ja"): the card's „8 Tage“ badge on its picture, Gemacht's
 * „Tag 3 von …“ line, and the day sheet – day 1 ridden (pill „Tag 1“, under it „✓ gefahren 2.12.“), day 2 begun and the next
 * (filled pill, „angefangen 3.12.“, MD-9), the rows' aligned columns km · ↑ m · time with the night signs and ›. German, at the
 * normal and the largest font (2.0); the day sheet also in English. The ridden / begun line is never cut: no „…“, no line
 * broken inside a word ([assertStatesWhole]).
 */
abstract class DayPickScreenshotTest(fontScale: Float, private val locale: Locale = Locale.GERMANY) {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = fontScale, screenHeight = 3000), showSystemUi = false)
    private val before = Locale.getDefault()
    @Before fun language() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    private val line = (0..300).map { i -> GeoPoint(51.17 - i * 0.0101, 6.94 + i * 0.0155, 80.0 + 300 * sin(i / 30.0)) }
    private val created = 1795867200000L // 28 Nov 2026
    private val route = Tour(1, TourKind.PLANNED, "Von Hilden nach München", created, "BIKE_TOURING", 3, 616_000.0, 4_210.0, 4_100.0, 33 * 3600.0, "done",
        listOf(GpxWaypoint(line.first().lat, line.first().lon, "Hilden"), GpxWaypoint(line.last().lat, line.last().lon, "München")),
        uuid = "uuid-hilden", days = MultiDay(8))
    private val plain = Tour(2, TourKind.PLANNED, "Feierabendrunde", created - 86_400_000L, "BIKE_TOURING", 3, 34_000.0, 210.0, 210.0, 7200.0, "done",
        emptyList(), uuid = "uuid-plain")
    private val rode = Tour(3, TourKind.RECORDED, "Fahrt 2. Dez.", 1796212800000L, "BIKE_TOURING", 3, 81_400.0, 540.0, 520.0, 4.6 * 3600, "done",
        emptyList(), uuid = "uuid-rode", planUuid = "uuid-hilden", planDay = 3, planName = "Von Hilden nach München", planOpens = true)

    private fun row(n: Int, from: String?, to: String?, km: Double, up: Double, h: Double, end: DayEndKind?, ridden: Long? = null, started: Long? = null) =
        DayPickRow(n, from, to, km * 1000, up, h * 3600, end, ridden, (n - 1) * 37, n * 37, startedMs = started)
    private val ui = DayPickUi(route, line, listOf(
        row(1, "Hilden", "Köln", 48.0, 210.0, 2.75, DayEndKind.OVERNIGHT, ridden = 1796212800000L),
        row(2, "Köln", "Koblenz", 104.0, 620.0, 5.9, DayEndKind.HOTEL, started = 1796299200000L),
        row(3, "Koblenz", "Mainz", 81.0, 540.0, 4.5, DayEndKind.OVERNIGHT),
        row(4, "Mainz", null, 76.0, 380.0, 4.2, DayEndKind.PROPOSAL),
        row(5, null, null, 88.0, 690.0, 5.1, DayEndKind.PROPOSAL),
        row(6, null, "Ulm", 79.0, 720.0, 4.8, DayEndKind.HOTEL),
        row(7, "Ulm", "Augsburg", 82.0, 450.0, 4.4, DayEndKind.OVERNIGHT),
        row(8, "Augsburg", "München", 58.0, 600.0, 3.3, null),
    ), next = 2)

    @Test fun cardWithBadge() = paparazzi.snapshot { OnPathTheme { RoutesScreen(listOf(route, plain), {}, {}, {}, {}, {}) } }

    @Test fun doneTourRodeADay() = paparazzi.snapshot {
        OnPathTheme { RoutesScreen(listOf(route, rode), {}, {}, {}, {}, {}, initialTab = RoutesTab.DONE) }
    }

    /** The day states as laid out in the last snapshot. */
    private val states = ArrayList<TextLayoutResult>()

    @Test fun daySheet() {
        paparazzi.snapshot {
            OnPathTheme { DayPickSheet(ui, canRide = true, snapshot = null, onRide = {}, onDetail = {}, onClose = {}, stateLaidOut = { states += it }) {} }
        }
        assertStatesWhole()
    }

    /** Day 1's „✓ gefahren …“ and day 2's „angefangen …“ were laid out, neither cut short nor broken inside a word. */
    private fun assertStatesWhole() {
        val texts = states.map { it.layoutInput.text.text }.distinct()
        assertEquals("both states drawn: $texts", 2, texts.size)
        for (r in states) {
            val t = r.layoutInput.text.text
            assertFalse("cut: \"$t\"", r.hasVisualOverflow)
            for (i in 0 until r.lineCount - 1) {
                val end = r.getLineEnd(i)
                assertTrue("\"$t\" broken inside a word after \"${t.substring(0, end)}\"", end in 1 until t.length && (t[end - 1].isWhitespace() || t[end].isWhitespace()))
            }
        }
    }

    @Test fun daySheetDuringATour() = paparazzi.snapshot {
        OnPathTheme { DayPickSheet(ui, canRide = false, snapshot = null, onRide = {}, onDetail = {}, onClose = {}) {} }
    }
}

class DayPickGermanTest : DayPickScreenshotTest(1.0f)
class DayPickLargeFontGermanTest : DayPickScreenshotTest(2.0f)
class DayPickEnglishTest : DayPickScreenshotTest(1.0f, Locale.US)
