package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.ui.RoutesTab
import com.onpath.app.ui.routes.RouteSort.Choice
import com.onpath.app.ui.routes.RouteSort.Key
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteSortTest {
    private fun t(id: Long, created: Long, km: Double, h: Double, up: Double, kind: TourKind = TourKind.PLANNED, name: String = "R$id") =
        Tour(id, kind, name, created, "HIKE", 2, km * 1000, up, up, h * 3600, "done", emptyList())

    private val a = t(1, 100, 14.2, 6.1, 1210.0, name = "Hirzer")
    private val b = t(2, 300, 9.6, 5.2, 1350.0, name = "Ifinger")
    private val c = t(3, 500, 31.5, 2.0, 120.0, name = "Etschradweg")
    private val d = t(4, 400, 8.1, 2.1, 210.0, kind = TourKind.IMPORTED, name = "Waalweg")
    private val list = listOf(a, b, c, d)

    private fun ids(l: List<Tour>) = l.map { it.id }

    @Test fun newestFirstIsTheDefault() {
        assertEquals(Choice(Key.NEWEST, true), RouteSort.DEFAULT)
        assertEquals(listOf(3L, 4L, 2L, 1L), ids(RouteSort.sort(list, RouteSort.DEFAULT)))
    }

    @Test fun oldestFirst() = assertEquals(listOf(1L, 2L, 4L, 3L), ids(RouteSort.sort(list, Choice(Key.OLDEST))))

    @Test fun biggestFirstForLengthDurationAscent() {
        assertEquals(listOf(3L, 1L, 2L, 4L), ids(RouteSort.sort(list, Choice(Key.LENGTH))))
        assertEquals(listOf(1L, 2L, 4L, 3L), ids(RouteSort.sort(list, Choice(Key.DURATION))))
        assertEquals(listOf(2L, 1L, 4L, 3L), ids(RouteSort.sort(list, Choice(Key.ASCENT))))
    }

    @Test fun flippedIsSmallestFirst() {
        assertEquals(listOf(4L, 2L, 1L, 3L), ids(RouteSort.sort(list, Choice(Key.LENGTH, biggestFirst = false))))
        assertEquals(listOf(3L, 4L, 1L, 2L), ids(RouteSort.sort(list, Choice(Key.ASCENT, biggestFirst = false))))
    }

    @Test fun tiesGoNewestFirstWhateverTheInputOrder() {
        val x = t(10, 100, 5.0, 1.0, 100.0); val y = t(11, 200, 5.0, 1.0, 100.0); val z = t(12, 200, 5.0, 1.0, 100.0)
        for (input in listOf(listOf(x, y, z), listOf(z, x, y), listOf(y, z, x))) {
            assertEquals(listOf(12L, 11L, 10L), ids(RouteSort.sort(input, Choice(Key.LENGTH))))
            assertEquals(listOf(12L, 11L, 10L), ids(RouteSort.sort(input, Choice(Key.LENGTH, biggestFirst = false))))
        }
    }

    @Test fun tapTheChosenChipTurnsItRound() {
        val len = RouteSort.tap(RouteSort.DEFAULT, Key.LENGTH)
        assertEquals(Choice(Key.LENGTH, true), len)
        assertEquals(Choice(Key.LENGTH, false), RouteSort.tap(len, Key.LENGTH))
        assertEquals(Choice(Key.LENGTH, true), RouteSort.tap(RouteSort.tap(len, Key.LENGTH), Key.LENGTH))
        // another key starts biggest first again
        assertEquals(Choice(Key.ASCENT, true), RouteSort.tap(Choice(Key.LENGTH, false), Key.ASCENT))
        // the date chips have no direction: a second tap changes nothing
        assertEquals(Choice(Key.NEWEST), RouteSort.tap(Choice(Key.NEWEST), Key.NEWEST))
        assertEquals(Choice(Key.OLDEST), RouteSort.tap(Choice(Key.NEWEST), Key.OLDEST))
    }

    @Test fun storedFormRoundTripsAndToleratesJunk() {
        for (k in Key.entries) for (big in listOf(true, false)) {
            val ch = Choice(k, big)
            assertEquals(ch, RouteSort.decode(RouteSort.encode(ch)))
        }
        assertEquals(RouteSort.DEFAULT, RouteSort.decode(null))
        assertEquals(RouteSort.DEFAULT, RouteSort.decode(""))
        assertEquals(RouteSort.DEFAULT, RouteSort.decode("DIFFICULTY:down")) // a newer build's key
        assertEquals(Choice(Key.ASCENT, true), RouteSort.decode("ASCENT"))
    }

    /** With the search and the tabs: the order holds within what the tab and the search leave. */
    @Test fun worksAfterTabAndSearch() {
        val rec1 = t(20, 600, 20.0, 3.0, 500.0, kind = TourKind.RECORDED, name = "Tour Hirzer")
        val rec2 = t(21, 700, 40.0, 4.0, 300.0, kind = TourKind.RECORDED, name = "Tour Meran")
        val all = list + rec1 + rec2
        val planned = RoutesTab.filter(all, RoutesTab.PLANNED)
        assertEquals(listOf(3L, 1L, 2L, 4L), ids(RouteSort.sort(planned, Choice(Key.LENGTH))))
        val done = RoutesTab.filter(all, RoutesTab.DONE)
        assertEquals(listOf(21L, 20L), ids(RouteSort.sort(done, Choice(Key.LENGTH))))
        assertEquals(listOf(20L, 21L), ids(RouteSort.sort(done, Choice(Key.ASCENT))))
        val found = RoutesTab.search(RoutesTab.filter(all, RoutesTab.PLANNED), "i")
        assertEquals(listOf(1L, 2L), ids(RouteSort.sort(found, Choice(Key.LENGTH))))
        assertEquals(listOf(2L, 1L), ids(RouteSort.sort(found, Choice(Key.LENGTH, biggestFirst = false))))
    }
}
