package com.onpath.app.ui

import com.onpath.app.ui.PinMenuItems.Row
import com.onpath.app.ui.PinMenuPlace.Decision
import com.onpath.app.ui.PinMenuPlace.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-13 ("A mit Ausweich B"): a picked search result opens the pin's hold menu at once, above the pin (A);
 * where it does not fit – by its measured height – the same rows as a card at the bottom (B). The rows are those a hold
 * on that spot gives, in the same order, during a tour only what the hold menu offers then.
 */
class PinMenuPlaceTest {
    // the free map 300..1500 px, gap 58, tip room 63 (2.625 px per dp)
    private fun decide(tip: Float, menuH: Int, mayPan: Boolean = true) =
        PinMenuPlace.decide(tip - 105f, tip, menuH, 300, 1500, 58, 63, mayPan)

    @Test fun aMenuThatFitsAboveStaysThereAndTheMapStill() =
        assertEquals(Decision(Kind.ABOVE), decide(tip = 1200f, menuH = 700))

    @Test fun theMapMovesDownSoTheMenuFitsAbove() {
        // the pin in the middle (900): above would start at 900 - 105 - 58 - 700 = 37 – 263 px short of the top bar
        val d = decide(tip = 900f, menuH = 700)
        assertEquals(Kind.ABOVE, d.kind)
        assertEquals(263, d.panPx)
    }

    @Test fun theMapNeverPushesThePinUnderTheSheet() {
        // 900 + 263 would leave the tip 337 px above the sheet: fine; a taller menu would push it under → the card
        assertEquals(Kind.CARD, decide(tip = 900f, menuH = 1000).kind)
        assertEquals(Kind.CARD, decide(tip = 1100f, menuH = 1000).kind)
    }

    @Test fun withoutMovingTheMapUnderThePinWhenThatFits() {
        // the pin near the top bar and the map may not move (following): under the tip
        assertEquals(Decision(Kind.BELOW), decide(tip = 400f, menuH = 700, mayPan = false))
        // may move: above it, after the move
        assertEquals(Kind.ABOVE, decide(tip = 400f, menuH = 700).kind)
    }

    @Test fun largestFontOrSmallMapGivesTheCard() {
        // the largest font: a 1300 px menu fits neither above nor below in 1200 px of map
        assertEquals(Decision(Kind.CARD), decide(tip = 900f, menuH = 1300))
        assertEquals(Decision(Kind.CARD), decide(tip = 900f, menuH = 1300, mayPan = false))
    }

    @Test fun decidedByHeightNotByFontScale() {
        // the same measured height gives the same answer – nothing in the rule knows the font
        assertEquals(decide(tip = 900f, menuH = 700), decide(tip = 900f, menuH = 700))
        assertEquals(Kind.ABOVE, decide(tip = 900f, menuH = 400).kind)
    }

    @Test fun theCardKeepsThePinVisible() {
        // card top at 1300: the tip at 1400 goes up to 1300 - 63 = 1237 (−163)
        assertEquals(-163, PinMenuPlace.cardPan(headY = 1295f, tipY = 1400f, cardTop = 1300, top = 300, tipRoom = 63))
        // already above the card: the map stays
        assertEquals(0, PinMenuPlace.cardPan(headY = 795f, tipY = 900f, cardTop = 1300, top = 300, tipRoom = 63))
        // never so far that the head goes under the top bar
        assertEquals(-50, PinMenuPlace.cardPan(headY = 350f, tipY = 455f, cardTop = 400, top = 300, tipRoom = 63))
    }

    // ---- the rows: the same as a hold on that spot ----

    private val stopMenu = PlaceAddMenu.menu(3, touring = false, zone = PlaceAddMenu.Zone.STOP)

    @Test fun emptyPlan() = assertEquals(listOf(Row.START_HERE, Row.GO_HERE, Row.GOOGLE, Row.REMOVE_PIN),
        PinMenuItems.of(emptyPlan = true, startHere = true, menu = null, pin = true))

    @Test fun emptyPlanDuringATourOffersNoStartHere() = assertEquals(listOf(Row.GO_HERE, Row.GOOGLE, Row.REMOVE_PIN),
        PinMenuItems.of(emptyPlan = true, startHere = MapGestures.offersStartHere(touring = true), menu = null, pin = true))

    @Test fun onePoint() = assertEquals(listOf(Row.ADD, Row.GOOGLE, Row.REMOVE_PIN),
        PinMenuItems.of(emptyPlan = false, startHere = true, menu = PlaceAddMenu.menu(1, false, null), pin = true))

    @Test fun routeAClearStop() {
        assertEquals(listOf(Row.ADD_STOP, Row.GOOGLE, Row.REMOVE_PIN, Row.MORE),
            PinMenuItems.of(emptyPlan = false, startHere = true, menu = stopMenu, pin = true))
        // "⋯ Mehr" tapped: the hidden alternative in its place
        assertEquals(listOf(Row.ADD_STOP, Row.GOOGLE, Row.REMOVE_PIN, Row.NEW_DESTINATION),
            PinMenuItems.of(emptyPlan = false, startHere = true, menu = stopMenu, pin = true, more = true))
    }

    @Test fun routeDoubtfulAndFar() {
        assertEquals(listOf(Row.NEW_DESTINATION, Row.ADD_STOP, Row.GOOGLE, Row.REMOVE_PIN),
            PinMenuItems.of(false, true, PlaceAddMenu.menu(3, false, PlaceAddMenu.Zone.GOAL_FIRST), pin = true))
        assertEquals(listOf(Row.NEW_DESTINATION, Row.GOOGLE, Row.REMOVE_PIN, Row.MORE),
            PinMenuItems.of(false, true, PlaceAddMenu.menu(3, false, PlaceAddMenu.Zone.GOAL), pin = true))
    }

    @Test fun duringATourTheSameAddEntriesAsAHold() =
        assertEquals(PinMenuItems.of(false, false, PlaceAddMenu.menu(3, true, PlaceAddMenu.Zone.STOP), pin = true),
            listOf(Row.ADD_STOP, Row.GOOGLE, Row.REMOVE_PIN, Row.MORE))

    @Test fun aMapPoiHasNoRemovePin() = assertEquals(listOf(Row.START_HERE, Row.GO_HERE, Row.GOOGLE),
        PinMenuItems.of(emptyPlan = true, startHere = true, menu = null, pin = false))

    // ---- wiring ----

    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()

    @Test fun aSearchPickOpensThePinsMenuLikeASharedPlace() {
        val pick = src("ui/SearchFlow.kt").substringAfter("fun pickSearchResult(").substringBefore("\n    }\n")
        assertTrue(pick, pick.contains("sharedMenuWaiting = sharedMenu.arrived(SharedPlaceMenu.key(p.lat, p.lon, p.name))"))
        val host = src("ui/MapOverlays.kt").substringAfter("fun MapOverlayHost(").substringBefore("\n}\n")
        // the pin's menu goes through PinMenuOverlay (above, else the card), a map POI's as before
        assertTrue(host, host.contains("PinMenuOverlay(poi,"))
        assertTrue(host, host.contains("Anchored(c, GeoPoint(poi.lat, poi.lon), cameraTick, topPx, bottomPx, onGone = gone) { menu(false) }"))
        // the menu's rows are PinMenuItems' – the hold's and the pick's are one list
        assertTrue(src("ui/MapOverlays.kt").substringAfter("fun PoiMenu(").substringBefore("\n}\n").contains("PinMenuItems.of(emptyPlan, startHere, menu"))
    }
}
