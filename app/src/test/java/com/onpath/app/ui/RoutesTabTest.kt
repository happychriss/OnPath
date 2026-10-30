package com.onpath.app.ui

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.plansync.RouteItems
import com.onpath.app.recording.DemoLocation
import org.junit.Assert.assertEquals
import org.junit.Test

/** My routes' two tabs (owner 2026-10-30): Planned = planned routes + imported GPX, Done = recorded tours (demo too). */
class RoutesTabTest {
    private fun tour(id: Long, kind: TourKind, name: String = "T$id") =
        Tour(id, kind, name, 1_760_000_000_000 + id, "HIKE", 2, 1000.0, 0.0, 0.0, 600.0, "done", emptyList())

    private val planned = tour(1, TourKind.PLANNED)
    private val recorded = tour(2, TourKind.RECORDED)
    private val imported = tour(3, TourKind.IMPORTED)
    private val demo = tour(4, TourKind.RECORDED, DemoLocation.TOUR_PREFIX + "Algund")

    @Test fun eachKindHasItsTab() {
        assertEquals(RoutesTab.PLANNED, RoutesTab.of(TourKind.PLANNED))
        assertEquals(RoutesTab.PLANNED, RoutesTab.of(TourKind.IMPORTED))
        assertEquals(RoutesTab.DONE, RoutesTab.of(TourKind.RECORDED))
    }

    @Test fun splitKeepsOrderAndPutsDemoToursInDone() {
        val all = listOf(demo, imported, recorded, planned) // newest first, as My routes gets them
        assertEquals(listOf(imported, planned), RoutesTab.filter(all, RoutesTab.PLANNED))
        assertEquals(listOf(demo, recorded), RoutesTab.filter(all, RoutesTab.DONE))
    }

    @Test fun emptyListGivesEmptyTabs() {
        assertEquals(emptyList<Tour>(), RoutesTab.filter(emptyList(), RoutesTab.PLANNED))
        assertEquals(emptyList<Tour>(), RoutesTab.filter(listOf(recorded), RoutesTab.PLANNED))
        assertEquals(emptyList<Tour>(), RoutesTab.filter(listOf(planned, imported), RoutesTab.DONE))
    }

    /** Planned shows exactly what Browser sync carries (C16): a finished tour is in Planned ⇔ it syncs. */
    @Test fun plannedTabIsWhatSyncs() {
        for (t in listOf(planned, recorded, imported, demo)) {
            assertEquals(t.name, RouteItems.synced(t), RoutesTab.of(t.kind) == RoutesTab.PLANNED)
        }
    }

    @Test fun plannedIsTheDefault() = assertEquals(RoutesTab.PLANNED, RoutesTab.entries.first())
}
