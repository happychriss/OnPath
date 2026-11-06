package com.onpath.app.ui

import com.onpath.app.map.CandidateMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-02: a found place (search hit / place shared from Google Maps) is a blue pin with a name chip
 * "Fabriciusstraße ›" under it. The chip opens the pin's menu, the menu ends with "Nadel entfernen"; the pin goes only
 * when the place is added, with a new search, "Neu planen" or "Nadel entfernen" – a tap elsewhere closes only the menu.
 * In Navigate pin and chip are hidden; while the plan cannot be edited (a track shown) the chip is hidden.
 */
class FoundPlacePinTest {
    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()
    private val vm = src("ui/AppViewModel.kt")
    private val overlays = src("ui/MapOverlays.kt")
    private fun body(s: String, start: String) = s.substringAfter(start).substringBefore("\n    }\n")

    /** Rule 2: "Nadel entfernen" is the last line of the pin's menu only – a map POI's menu has none. */
    @Test fun thePinsMenuEndsWithRemovePin() {
        val menu = overlays.substringAfter("fun PoiMenu(").substringBefore("\n}\n")
        val google = menu.indexOf("Res.string.plan_open_google_maps")
        val remove = menu.indexOf("Res.string.plan_remove_pin")
        assertTrue(menu, google >= 0 && remove > google)
        assertTrue(menu.contains("onRemovePin?.let {"))
        val host = overlays.substringAfter("fun MapOverlayHost(").substringBefore("\n}\n")
        assertTrue(host.contains("val pin = vm.isCandidate(poi)"))
        assertTrue(host.contains("onRemovePin = if (pin) { { vm.closeMapOverlays(\"pin removed\"); vm.removePin("))
        for (dir in listOf("values", "values-de")) {
            val xml = File("../strings/src/commonMain/composeResources/$dir/strings_plan.xml").readText()
            assertTrue(dir, xml.contains("name=\"plan_remove_pin\""))
            assertTrue(dir, xml.contains("name=\"plan_pin_chip_desc\""))
        }
        assertTrue(File("../strings/src/commonMain/composeResources/values-de/strings_plan.xml").readText()
            .contains("<string name=\"plan_remove_pin\">Nadel entfernen</string>"))
    }

    /** Rule 1: the chip opens the same menu a hold on the pin opens. */
    @Test fun theChipOpensThePinsMenu() {
        val host = overlays.substringAfter("fun MapOverlayHost(").substringBefore("\n}\n")
        assertTrue(host.contains("onMore = vm::openPinMenu"))
        val open = body(vm, "fun openPinMenu(")
        assertTrue(open, open.contains("openPoiMenu(m.toPoi())"))
        // the hold on the pin (its head too) opens the POI menu as before
        assertTrue(src("ui/MapHost.kt").contains("com.onpath.app.map.CandidateMarker.hit((screen.x - s.x) / dp, (screen.y - s.y) / dp)"))
    }

    /** Rule 3: what removes the pin – and that a tap elsewhere does not. */
    @Test fun thePinGoesOnlyByTheOwnersFourWays() {
        val close = body(vm, "fun closeMapOverlays(")
        assertFalse("closing a menu keeps the pin: $close", close.contains("searchMark = null"))
        assertTrue(body(vm, "fun clearPlan(").contains("removePin(\"plan cleared\")"))
        val remove = body(vm, "fun removePin(")
        assertTrue(remove, remove.contains("searchMark = null"))
        // adding the place and a new search pick replace it on their own
        assertTrue(body(vm, "fun addPoiPoint(").contains("clearSearchMarkAt(poi.lat, poi.lon)"))
        assertTrue(src("ui/SearchFlow.kt").substringAfter("fun pickSearchResult(").substringBefore("\n    }\n").contains("searchMark = p"))
    }

    /**
     * Owner 2026-11-04 (design B): the card under the pin carries the most common action – "+ Hinzufügen" (owner 2026-11-05, #26: neither route nor plan) adds the place
     * like the menu's add; on an empty plan "Hierhin" goes there from here (without a position the menu opens, it says
     * why); "⋯ Mehr" opens the pin's menu.
     */
    @Test fun theCardsActionAddsThePlace() {
        val host = overlays.substringAfter("fun MapOverlayHost(").substringBefore("\n}\n")
        assertTrue(host, host.contains("str(if (empty) Res.string.plan_go_here else Res.string.plan_pin_add)"))
        assertTrue(host, host.contains("if (!empty) vm.addPoiPoint(m.toPoi()) else if (blocked == null) vm.poiFromMyLocation(m.toPoi()) else vm.openPinMenu()"))
        for (dir in listOf("values", "values-de")) {
            val xml = File("../strings/src/commonMain/composeResources/$dir/strings_plan.xml").readText()
            for (k in listOf("plan_pin_add", "plan_pin_more", "plan_pin_action_desc")) assertTrue("$dir $k", xml.contains("name=\"$k\""))
        }
        assertTrue(File("../strings/src/commonMain/composeResources/values-de/strings_plan.xml").readText()
            .contains("<string name=\"plan_pin_add\">Hinzufügen</string>"))
        assertTrue(File("../strings/src/commonMain/composeResources/values/strings_plan.xml").readText()
            .contains("<string name=\"plan_pin_add\">Add</string>"))
    }

    /** Rule 5: pin hidden in Navigate; the chip only where the plan can be edited. */
    @Test fun hiddenInNavigateChipOnlyWhenEditable() {
        assertTrue(src("ui/MapScreen.kt").contains("val m = vm.searchMark?.takeIf { vm.mode == AppMode.PLAN }"))
        val host = overlays.substringAfter("fun MapOverlayHost(").substringBefore("\n}\n")
        val guard = host.indexOf("if (!vm.canEditMap) return")
        assertTrue(guard >= 0 && guard < host.indexOf("PinChip("))
    }

    @Test fun longNamesEndInAnEllipsis() {
        assertEquals("Fabriciusstraße", PinChipRules.label("Fabriciusstraße"))
        assertEquals("Gasthaus zur alten…", PinChipRules.label("Gasthaus zur alten Mühle am Bach"))
        assertEquals("Rifugio Comici-Hütt…", PinChipRules.label("Rifugio Comici-Hütte am Pass"))
        assertTrue(PinChipRules.label("Gasthaus zur alten Mühle am Bach").length <= PinChipRules.MAX_CHARS)
    }

    @Test fun theChipStaysOnScreen() {
        // centred under the tip
        assertEquals(400 - 100 to (500 + 6 - 8), PinChipRules.place(400f, 500f, 200, 64, 8, 6, 12, 1068, 100, 1800))
        // at the left / right edge it moves in
        assertEquals(12, PinChipRules.place(20f, 500f, 200, 64, 8, 6, 12, 1068, 100, 1800)!!.first)
        assertEquals(1068 - 200, PinChipRules.place(1060f, 500f, 200, 64, 8, 6, 12, 1068, 100, 1800)!!.first)
        // under the sheet or above the top bar: not shown (the pin stays)
        assertNull(PinChipRules.place(400f, 1780f, 200, 64, 8, 6, 12, 1068, 100, 1800))
        assertNull(PinChipRules.place(400f, 50f, 200, 64, 8, 6, 12, 1068, 100, 1800))
    }

    @Test fun holdingThePinsHeadCounts() {
        assertTrue(CandidateMarker.hit(0f, 0f))
        assertTrue("the head", CandidateMarker.hit(0f, -CandidateMarker.TIP_DP))
        assertTrue("the head's edge", CandidateMarker.hit(CandidateMarker.HEAD_DP, -CandidateMarker.TIP_DP))
        assertFalse("well beside", CandidateMarker.hit(40f, -CandidateMarker.TIP_DP))
        assertFalse("well below", CandidateMarker.hit(0f, 40f))
    }

    /**
     * Owner 2026-11-02 (empty plan, 4-line menu): with no room above the pin's head the menu goes under the name chip –
     * never over the pin. Head at y 400 (tip 450), chip down to 500, menu 300 high, top bar at 200.
     */
    @Test fun theMenuNeverCoversThePin() {
        val (_, y) = MapGestures.placeOverlay(500f, 400f, 300, 300, 12, 200, 1068, 1800, 22, belowAy = 500f)
        assertEquals("under the chip", 522, y)
        // room neither above nor below inside the map area: over the top bar rather than over the pin
        val tight = MapGestures.placeOverlay(500f, 400f, 300, 300, 12, 200, 1068, 700, 22, belowAy = 500f, looseTop = 12, looseBottom = 1888)
        assertEquals(400 - 22 - 300, tight.second)
        // room above: above the head as before
        assertEquals(400 - 22 - 300, MapGestures.placeOverlay(500f, 400f, 300, 300, 12, 50, 1068, 1800, 22, belowAy = 500f).second)
    }
}
