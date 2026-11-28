package com.onpath.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chriss 2026-11-28 (F-PLAN-24 / F-PLAN-25): the map asks [com.onpath.app.domain.HoldHint] at a tap and counts the holds that
 * did something (all three run paths); the place info gets the hold menu's „Als Ziel“ only through [poiGoal].
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

    @Test fun thePlaceInfoTakesTheHoldMenusGoal() {
        assertTrue(src("ui/MapScreen.kt").contains("goal = poiGoal(vm, poi, gps)"))
        val g = src("ui/PoiGoal.kt")
        assertTrue("same rows as the POI menu", g.contains("shownMenuRows(PinMenuItems.of(") && g.contains("PinMenuItems.goal("))
        assertTrue("the rows' own actions", g.contains("poiFromMyLocation(poi)") && g.contains("addPoiPoint(poi)") && g.contains("poiAsNewDestination(poi)"))
        assertTrue("Plan only", g.contains("vm.tourSession.mode != AppMode.PLAN || !vm.canEditMap"))
    }
}
