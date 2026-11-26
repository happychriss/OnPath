package com.onpath.app.domain

import com.onpath.app.testkit.sourceFile
import com.onpath.app.domain.PinMenuItems.Row
import com.onpath.app.domain.PinMenuPlace.Decision
import com.onpath.app.domain.PinMenuPlace.Kind
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

    /**
     * Release flow 10 (GitHub run 44497175210, 2026-11-19): the shared place's menu with „Rundtour von hier“ is 1010 px
     * high; the pin in the middle of the map 338..1938 (tip 1102, pin 124 px) → the map moves 428 px. The effect that
     * moves the map ran twice for that one decision: 2 × 428 put the tip at 1958, under the sheet – the name chip had no
     * room once the menu closed. One opened menu moves the map once, however often it is asked.
     */
    @Test fun oneOpenedMenuMovesTheMapOnce() {
        val tip = 1102f
        val d = PinMenuPlace.decide(tip - 124f, tip, 1010, 338, 1938, 58, 63)
        assertEquals(Decision(Kind.ABOVE, 428), d)
        val once = PinMenuPlace.PanOnce()
        once.want(d.panPx)
        once.want(d.panPx) // measured again: still the one move
        val moved = once.take() + once.take() + once.take() // the effect launched before and again after the wish
        assertEquals(428, moved)
        assertTrue("the tip stays above the sheet: ${tip + moved}", tip + moved <= 1938 - 63)
    }

    @Test fun noWishNoMove() {
        val once = PinMenuPlace.PanOnce()
        assertEquals(0, once.take())
        assertTrue(!once.asked)
        once.want(-163) // the card's move, asked later
        assertTrue(once.asked)
        assertEquals(-163, once.take())
        once.want(-50) // a second wish after the move: ignored
        assertEquals(0, once.take())
    }

    @Test fun theOverlayMovesTheMapThroughPanOnce() {
        val overlay = src("ui/MapOverlays.kt").substringAfter("internal fun PinMenuOverlay(").substringBefore("\n}\n")
        val effect = overlay.lines().first { "LaunchedEffect(" in it }
        // the effect reads no state of its own: what it moves comes from take() – once per opened menu
        assertTrue(effect, effect.contains(".take()") && !effect.contains("onPan(pan)"))
    }

    // ---- the rows: the same as a hold on that spot ----
    // Test clean-up G20 (2026-11-19): the nine row tests are one table (every case kept). It is NOT PlaceAddMenuTest.theMenuPerZone: that one maps
    // a zone to the add entries, this one is PinMenuItems.of – the rows around them (Start / Go here, Google, Remove pin, kept places); the
    // web planner's twin of these numbers is web/app/test/pinmenu-check.mjs.

    private fun rows(emptyPlan: Boolean, startHere: Boolean, menu: PlaceAddMenu.Menu?, pin: Boolean, roundTrip: Boolean = false) =
        PinMenuItems.of(emptyPlan, startHere, menu, pin, roundTrip = roundTrip)

    @Test fun theRowsAreAHoldsRowsWithRemovePinLastOnlyOnThePin() {
        val stop = PlaceAddMenu.menu(3, touring = false, zone = PlaceAddMenu.Zone.STOP)
        // empty plan; during a tour no "Hier starten"
        assertEquals("empty plan", listOf(Row.GO_HERE, Row.START_HERE, Row.GOOGLE, Row.REMOVE_PIN), rows(true, true, null, pin = true))
        assertEquals("empty plan during a tour offers no start here", listOf(Row.GO_HERE, Row.GOOGLE, Row.REMOVE_PIN),
            rows(true, MapGestures.offersStartHere(touring = true), null, pin = true))
        // one point
        assertEquals("one point", listOf(Row.ADD, Row.GOOGLE, Row.REMOVE_PIN), rows(false, true, PlaceAddMenu.menu(1, false, null), pin = true))
        // Owner 2026-11-26 night: the likeliest on top (tinted), the rest below
        assertEquals("a clear stop", listOf(Row.ADD_STOP, Row.NEW_DESTINATION, Row.GOOGLE, Row.REMOVE_PIN), rows(false, true, stop, pin = true))
        assertEquals("doubtful, the goal likelier", listOf(Row.NEW_DESTINATION, Row.ADD_STOP, Row.GOOGLE, Row.REMOVE_PIN),
            rows(false, true, PlaceAddMenu.menu(3, false, PlaceAddMenu.Zone.GOAL_FIRST), pin = true))
        assertEquals("far", listOf(Row.NEW_DESTINATION, Row.ADD_STOP, Row.GOOGLE, Row.REMOVE_PIN),
            rows(false, true, PlaceAddMenu.menu(3, false, PlaceAddMenu.Zone.GOAL), pin = true))
        assertEquals("planning, only the goal", listOf(Row.NEW_DESTINATION, Row.GOOGLE, Row.REMOVE_PIN),
            rows(false, true, PlaceAddMenu.planningRows(PlaceAddMenu.HoldAction.NEW_GOAL, PlaceAddMenu.Detours(500.0, 50.0)), pin = true))
        assertEquals("during a tour the same add entries as a hold", listOf(Row.ADD_STOP, Row.NEW_DESTINATION, Row.GOOGLE, Row.REMOVE_PIN),
            rows(false, false, PlaceAddMenu.menu(3, true, PlaceAddMenu.Zone.STOP), pin = true))
        // a map POI has no Remove pin
        assertEquals("a map POI", listOf(Row.GO_HERE, Row.START_HERE, Row.GOOGLE), rows(true, true, null, pin = false))
    }

    /**
     * Tour suggestions on (the default since 2026-11-18): „Rundtour ab hier“ last in the route group, after „Als Ziel“
     * (owner 2026-11-26) – only where „Als Start“ is offered.
     */
    @Test fun roundTripFollowsStartHere() {
        assertEquals(listOf(Row.GO_HERE, Row.START_HERE, Row.ROUND_TRIP, Row.GOOGLE), rows(true, true, null, pin = false, roundTrip = true))
        assertEquals(listOf(Row.GO_HERE, Row.GOOGLE), rows(true, false, null, pin = false, roundTrip = true))
        assertEquals(listOf(Row.ADD, Row.GOOGLE), rows(false, true, PlaceAddMenu.menu(1, false, null), pin = false, roundTrip = true))
    }

    /**
     * The spec's table „New point“ for a POI / search pin (owner 2026-11-26, docs/team/items/2026-11-26-context-menus-wording.md):
     * groups Route → Ort → Entfernen, a thin line where the group changes – never inside a group, never on top.
     */
    @Test fun theSpecTableNewPointGroupsAndLines() {
        fun lines(r: List<Row>) = r.indices.filter { PinMenuItems.lineBefore(r, it) }
        // POI / pin, empty plan: Als Start · Als Ziel · Rundtour ab hier | Merken · (Zuhause) · Google | Nadel entfernen
        val empty = PinMenuItems.of(emptyPlan = true, startHere = true, menu = null, pin = true, keep = true, roundTrip = true)
        assertEquals(listOf(Row.GO_HERE, Row.START_HERE, Row.ROUND_TRIP, Row.FAVOURITE, Row.HOME, Row.GOOGLE, Row.REMOVE_PIN), empty)
        assertEquals(listOf(3, 6), lines(empty))
        // 1 point: Als Ziel | Merken · Google | Nadel entfernen
        val one = PinMenuItems.of(false, true, PlaceAddMenu.menu(1, false, null), pin = true, keep = true).filter { it != Row.HOME }
        assertEquals(listOf(Row.ADD, Row.FAVOURITE, Row.GOOGLE, Row.REMOVE_PIN), one)
        assertEquals(listOf(1, 3), lines(one))
        // 2+: Als Stopp · Als Ziel | Merken · Google | Nadel entfernen
        val two = PinMenuItems.of(false, true, PlaceAddMenu.menu(3, false, PlaceAddMenu.Zone.GOAL), pin = true, keep = true)
            .filter { it != Row.HOME }
        assertEquals(listOf(Row.NEW_DESTINATION, Row.ADD_STOP, Row.FAVOURITE, Row.GOOGLE, Row.REMOVE_PIN), two)
        assertEquals(listOf(2, 4), lines(two))
        // tour running, empty plan: Als Ziel only (no Start, no Rundtour)
        val tour = PinMenuItems.of(true, MapGestures.offersStartHere(touring = true), null, pin = true, keep = true, roundTrip = true)
            .filter { it != Row.HOME }
        assertEquals(listOf(Row.GO_HERE, Row.FAVOURITE, Row.GOOGLE, Row.REMOVE_PIN), tour)
        // a map POI (no pin): no Entfernen group, so no line after Google
        val poi = PinMenuItems.of(true, true, null, pin = false)
        assertEquals(listOf(Row.GO_HERE, Row.START_HERE, Row.GOOGLE), poi)
        assertEquals(listOf(2), lines(poi))
        // the groups themselves
        assertEquals(MenuGroups.Group.ROUTE, PinMenuItems.group(Row.ROUND_TRIP))
        assertEquals(MenuGroups.Group.PLACE, PinMenuItems.group(Row.FAVOURITE))
        assertEquals(MenuGroups.Group.REMOVE, PinMenuItems.group(Row.REMOVE_PIN))
        assertEquals(emptyList<Int>(), lines(listOf(Row.ADD_STOP, Row.NEW_DESTINATION)))
    }

    /** Owner 2026-11-18: „Rundtour von hier“ only for the very first point – a plan with a point never offers it. */
    @Test fun noRoundTripOnceThePlanHasAPoint() {
        for (pin in listOf(false, true)) for (n in 1..3) assertEquals(false, Row.ROUND_TRIP in PinMenuItems.of(emptyPlan = false, startHere = true,
            menu = PlaceAddMenu.menu(n, false, null), pin = pin, roundTrip = true))
    }

    // ---- wiring ----

    private fun src(f: String) = sourceFile(f).readText()

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
