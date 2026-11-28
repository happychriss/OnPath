package com.onpath.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chriss 2026-11-28 (F-PLAN-24 / F-PLAN-25): the map asks [com.onpath.app.domain.HoldHint] at a tap and counts the holds that
 * did something (all three run paths); the place info's list is the hold menu's rows, built by the one [PlaceMenuFor].
 */
class HoldHintWiringTest {
    private fun src(p: String) = File("src/main/java/com/onpath/app/$p").readText()

    @Test fun theMapShowsThePillAtATapAndCountsHolds() {
        val h = src("ui/MapHost.kt")
        assertTrue(h.contains("HoldHint.pillOnTap(edit, press, a, poi != null, marker, pts.isEmpty(),"))
        assertTrue("the pill sits at the tapped spot", h.contains("holdPill.show(Offset(screen.x, screen.y))"))
        assertTrue("gone at the next finger-down", h.contains("vm.map.onMapTouch(); holdPill.hide()"))
        assertTrue("drawn over the map", h.contains("HoldPill(holdPill)"))
        assertTrue("counted where a press runs", h.contains("run(a, poi).also { count(a);") && h.contains("count(a); run(a, found)"))
    }

    @Test fun thePlaceInfoListIsTheHoldMenusRows() {
        assertTrue(src("ui/MapScreen.kt").contains("actions = placeInfoActions(vm, poi, gps)"))
        assertTrue("the map's hold menu uses the same builder", src("ui/MapOverlays.kt").contains("menu(card: Boolean) = PlaceMenuFor(vm, poi, gps, startHere, card = card,"))
        val g = src("ui/PlaceMenuFor.kt")
        assertTrue("Plan only", g.contains("vm.tourSession.mode != AppMode.PLAN || !vm.canEditMap"))
        assertTrue("the info's list: rows only, first tinted", g.contains("rowsOnly = rowsOnly, tintFirst = rowsOnly") && g.contains("rowsOnly = true"))
    }
}
