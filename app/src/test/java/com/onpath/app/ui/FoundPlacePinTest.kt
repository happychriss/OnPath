package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
import com.onpath.app.domain.MapGestures
import com.onpath.app.domain.PinMenuItems
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
    private fun src(f: String) = sourceFile(f).readText()
    private val vm = src("ui/AppViewModel.kt")
    private val placeAdd = src("ui/plan/PlaceAddSlice.kt")
    /** The pin menu, closing the menus and the pin's removal live here (rebuild phase 2 piece 12; PlaceMenusFlowTest runs them). */
    private val menus = src("ui/plan/PlaceMenusSlice.kt")
    private val overlays = src("ui/MapOverlays.kt")
    private fun body(s: String, start: String) = s.substringAfter(start).substringBefore("\n    }\n")

    /** Rule 2: "Nadel entfernen" is the last line of the pin's menu only – a map POI's menu has none. */
    @Test fun thePinsMenuEndsWithRemovePin() {
        val menu = overlays.substringAfter("fun PoiMenu(").substringBefore("\n}\n")
        val google = menu.indexOf("Res.string.plan_open_google_maps")
        val remove = menu.indexOf("Res.string.plan_remove_pin")
        assertTrue(menu, google >= 0 && remove > google)
        assertTrue(menu, menu.contains("PinMenuItems.Row.REMOVE_PIN -> HoldMenuItem(Icons.Outlined.WrongLocation, str(Res.string.plan_remove_pin), onClick = { onRemovePin?.invoke() })"))
        val host = overlays.substringAfter("fun MapOverlayHost(").substringBefore("\n}\n")
        assertTrue(host.contains("val pin = vm.searchFlow.isCandidate(poi)"))
        assertTrue(host.contains("onRemovePin = if (pin) { { vm.placeMenus.closeMapOverlays(\"pin removed\"); vm.placeMenus.removePin("))
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
        assertTrue(host, host.contains("PinChip(m.title(), onClick = vm.placeMenus::openPinMenu)"))
        assertTrue("the slice's openPinMenu was found", menus.contains("    fun openPinMenu() {"))
        val open = body(menus, "    fun openPinMenu() {")
        assertTrue(open, open.contains("openPoiMenu(m.toPoi())"))
        // the hold on the pin (its head too) opens the POI menu as before
        assertTrue(src("ui/MapHost.kt").contains("com.onpath.app.map.CandidateMarker.hit((screen.x - s.x) / dp, (screen.y - s.y) / dp)"))
    }

    /** Rule 3: what removes the pin – and that a tap elsewhere does not. */
    @Test fun thePinGoesOnlyByTheOwnersFourWays() {
        assertTrue("the slice's closeMapOverlays was found", menus.contains("    fun closeMapOverlays(why: String = \"closed\") {"))
        val close = body(menus, "    fun closeMapOverlays(why: String = \"closed\") {")
        assertFalse("closing a menu keeps the pin: $close", close.contains("searchMark = null"))
        // Clear removes the pin (after the plan went and the menus closed): PlanEditFlowTest.clearKeepsThePlanForZuletztFirstThenEmptiesInOneUndoStepAndNeverAsks
        assertTrue("the slice's removePin was found", menus.contains("    fun removePin(why: String) {"))
        val remove = body(menus, "    fun removePin(why: String) {")
        assertTrue(remove, remove.contains("host.searchMark = null"))
        // adding the place and a new search pick replace it on their own
        // (the add lives in PlaceAddSlice now, rebuild phase 2 piece 11; PlaceAddFlowTest.aFoundPlacePinGoesIntoThePlan… shows the pin go)
        val addPoi = "    fun addPoiPoint(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) {"
        assertTrue("the slice's addPoiPoint was found", placeAdd.contains(addPoi))
        assertTrue(body(placeAdd, addPoi).contains("val pin = host.takePin(poi.lat, poi.lon)"))
        assertTrue(src("ui/SearchFlow.kt").substringAfter("fun pickSearchResult(").substringBefore("\n    }\n").contains("searchMark = p"))
    }

    /**
     * Owner 2026-11-13 ("A mit Ausweich B", replaces the 2026-11-04 card with "Hierhin" / "+ Hinzufügen" · "⋯ Mehr"): the
     * chip under the pin is its name only, one button that opens the pin's menu; it hides while that menu is open (the
     * menu has the name on top).
     */
    @Test fun theChipIsTheNameOnly() {
        val host = overlays.substringAfter("fun MapOverlayHost(").substringBefore("\n}\n")
        assertTrue(host, host.contains("vm.searchFlow.searchMark?.takeIf { vm.placeMenus.poiMenu?.let(vm.searchFlow::isCandidate) != true }"))
        val chip = overlays.substringAfter("fun PinChip(").substringBefore("\n}\n")
        assertTrue(chip, chip.contains("str(Res.string.plan_pin_chip_desc, name)"))
        assertFalse("no action buttons any more: $chip", chip.contains("plan_go_here") || chip.contains("plan_pin_add"))
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
