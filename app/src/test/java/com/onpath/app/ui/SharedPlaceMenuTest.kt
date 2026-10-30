package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WaypointInsertion
import com.onpath.app.search.AddPreview
import com.onpath.app.search.Place
import com.onpath.app.ui.SharedPlaceMenu.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Google Maps place → go there (owner 2026-10-30): a shared place opens the ring's hold menu by itself once the map has
 * flown there – once per share, the same labels and rules as holding the ring. Pure rules in [SharedPlaceMenu]; the
 * view model needs Android, so its wiring is checked in the source (like CorePlanLinkTest).
 */
class SharedPlaceMenuTest {
    private val place = Place("shared:46.6712,11.1598", "Gelateria Costantin", "other", 46.6712, 11.1598)
    private val key = SharedPlaceMenu.key(place.lat, place.lon, place.name)
    private val arrivedPx = 63.0 // 24 dp at 420 dpi

    private fun SharedPlaceMenu.at(offPx: Double?, mark: String? = key, canEdit: Boolean = true,
                                   elsewhere: Boolean = false, loading: Boolean = false) =
        check(mark, offPx, arrivedPx, canEdit, elsewhere, loading)

    @Test fun opensOnceWhenTheCameraHasArrived() {
        val m = SharedPlaceMenu()
        assertTrue(m.arrived(key))
        assertEquals(Step.WAIT, m.at(4_000.0)) // still flying
        assertEquals(Step.WAIT, m.at(null)) // camera unknown
        assertEquals(Step.OPEN, m.at(0.0)) // flyTo centres exactly on the ring
        assertEquals(Step.NONE, m.at(0.0)) // a later camera stop opens nothing
    }

    @Test fun sameShareAgainShowsTheRingOnly() {
        val m = SharedPlaceMenu()
        m.arrived(key); assertEquals(Step.OPEN, m.at(0.0))
        assertFalse(m.arrived(key))
        assertEquals(Step.NONE, m.at(0.0))
        // another place opens its menu; after that the first one is a new share again (only a repeat is ignored)
        val other = SharedPlaceMenu.key(46.70, 11.20, "Leiter Alm")
        assertTrue(m.arrived(other)); assertEquals(Step.OPEN, m.at(0.0, mark = other))
        assertFalse(m.arrived(other))
        assertTrue(m.arrived(key)); assertEquals(Step.OPEN, m.at(0.0))
        // the same share arriving twice before the camera got there: one menu
        val third = SharedPlaceMenu.key(46.71, 11.21, null)
        assertTrue(m.arrived(third)); assertTrue(m.arrived(third))
        assertEquals(Step.OPEN, m.at(0.0, mark = third)); assertEquals(Step.NONE, m.at(0.0, mark = third))
    }

    /** A share that never got its menu (the user moved on) opens it when shared again. */
    @Test fun notOpenedYetMeansAnotherTry() {
        val m = SharedPlaceMenu()
        m.arrived(key); assertTrue(m.cancel()); assertFalse(m.cancel())
        assertEquals(Step.NONE, m.at(0.0))
        assertTrue(m.arrived(key)); assertEquals(Step.OPEN, m.at(0.0))
    }

    @Test fun theUserWentOnDropsIt() {
        for ((what, step) in listOf(
            "ring gone (added / closed)" to SharedPlaceMenu().apply { arrived(key) }.at(0.0, mark = null),
            "another ring (new search)" to SharedPlaceMenu().apply { arrived(key) }.at(0.0, mark = "x"),
            // the hold menu needs an editable map: Navigate or a shown track → no menu (same rule as a hold)
            "map not editable" to SharedPlaceMenu().apply { arrived(key) }.at(0.0, canEdit = false),
            "another menu / screen open" to SharedPlaceMenu().apply { arrived(key) }.at(0.0, elsewhere = true),
        )) assertEquals(what, Step.DROP, step)
    }

    @Test fun waitsForTheMapDownloadModal() {
        val m = SharedPlaceMenu()
        m.arrived(key)
        assertEquals(Step.WAIT, m.at(0.0, loading = true))
        assertEquals(Step.OPEN, m.at(0.0))
    }

    /** The labels the menu shows: what a hold on the ring shows (PoiMenu + vm.poiAddLabel → AddPreview.label). */
    @Test fun labelsEmptyPlanVsPlanWithPoints() {
        val p = GeoPoint(place.lat, place.lon)
        // empty plan: PoiMenu(emptyPlan = true) → "Start here" · "Go here" · "Open in Google Maps"
        val overlays = File("src/main/java/com/onpath/app/ui/MapOverlays.kt").readText()
        assertTrue(overlays.contains("emptyPlan = vm.waypoints.isEmpty()"))
        val menu = overlays.substringAfter("fun PoiMenu(").substringBefore("fun PointMenu(")
        assertTrue(menu.contains("Res.string.plan_start_here") && menu.contains("Res.string.plan_go_here") &&
            menu.contains("Res.string.plan_open_google_maps"))
        // a plan with only a start: the place becomes the destination
        val a = GeoPoint(46.60, 11.10)
        assertEquals("Set as destination", AddPreview.label(WaypointInsertion.decide(listOf(a), p, null, 30.0), 1))
        // a route A → B: beyond B a new destination, beside the middle a stop
        val b = GeoPoint(46.65, 11.14)
        assertEquals("Add as new destination", AddPreview.label(WaypointInsertion.decide(listOf(a, b), p, null, 30.0), 2))
        val c = GeoPoint(46.75, 11.20)
        assertEquals("Add as stop 2", AddPreview.label(WaypointInsertion.decide(listOf(a, c), p, null, 30.0), 2))
    }

    /** The wiring the pure rules cannot see: checked in the source. */
    @Test fun wiring() {
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val show = vm.substringAfter("private fun showSharedPlace(").substringBefore("private val sharedMenu")
        assertTrue("a shared place arms the menu", show.contains("sharedMenu.arrived(SharedPlaceMenu.key(p.lat, p.lon, p.name))"))
        val open = vm.substringAfter("fun openSharedMenuIfArrived(").substringBefore("\n    }\n")
        // the very menu a hold on the ring opens (MapHost: mark?.toPoi() → vm.openPoiMenu), with canEditMap as the rule
        assertTrue(open.contains("openPoiMenu(m.toPoi())"))
        assertTrue(open.contains("canEditMap"))
        assertTrue(open.contains("loading = loadModalOpen"))
        assertTrue(File("src/main/java/com/onpath/app/ui/MapHost.kt").readText().contains("mark?.toPoi()"))
        // a finger move on the map ends the wait
        assertTrue(vm.substringAfter("fun mapGesture(on: Boolean)").substringBefore("safeCells").contains("sharedMenu.cancel()"))
        // MapScreen asks on every camera stop
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(screen.contains("LaunchedEffect(c, vm.sharedMenuWaiting, vm.cameraIdleTick, vm.loadModalOpen)"))
        assertTrue(File("src/main/java/com/onpath/app/ui/MapHost.kt").readText().contains("vm.cameraIdle()"))
    }
}
