package com.onpath.web

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The found place's menu on the page (WebRules.pinMenu* = the app's PinMenuPlace / PinMenuItems / PlaceAddMenu.Menu,
 * search.ts / main.ts via pinplace.ts) – the cases test/pinmenu-check.mjs ran against the TypeScript copy, same numbers.
 */
class WebPinMenuTest {
    // the free map 300..1500 px, gap 58, tip room 63 (2.625 px per dp)
    private fun decide(tip: Int, menuH: Int, mayPan: Boolean = true) = WebRules.pinMenuDecide(tip - 105.0, tip.toDouble(), menuH, 300, 1500, 58, 63, mayPan)
    private fun kind(json: String) = Regex("\"kind\":\"(\\w+)\"").find(json)!!.groupValues[1]

    @Test fun placement() {
        assertEquals("{\"kind\":\"ABOVE\",\"panPx\":0}", decide(1200, 700))
        assertEquals("{\"kind\":\"ABOVE\",\"panPx\":263}", decide(900, 700))
        assertEquals("CARD", kind(decide(900, 1000)))
        assertEquals("CARD", kind(decide(1100, 1000)))
        assertEquals("{\"kind\":\"BELOW\",\"panPx\":0}", decide(400, 700, false))
        assertEquals("ABOVE", kind(decide(400, 700)))
        assertEquals("{\"kind\":\"CARD\",\"panPx\":0}", decide(900, 1300))
        assertEquals("{\"kind\":\"CARD\",\"panPx\":0}", decide(900, 1300, false))
        assertEquals("ABOVE", kind(decide(900, 400)))
        assertEquals("-163", WebRules.pinMenuCardPan(1295.0, 1400.0, 1300, 300, 63))
        assertEquals("0", WebRules.pinMenuCardPan(795.0, 900.0, 1300, 300, 63))
        assertEquals("-50", WebRules.pinMenuCardPan(350.0, 455.0, 400, 300, 63))
        assertEquals("{\"gap\":22,\"tipRoom\":24}", WebRules.pinMenuSizes())
    }

    @Test fun rowsAndTheTintedRow() {
        // an empty plan, the likeliest on top (owner 2026-11-26 night): „Als Ziel“ from my position when it can, else „Als Start“
        assertEquals("[\"GO_HERE\",\"START_HERE\",\"GOOGLE\",\"REMOVE_PIN\"]", WebRules.pinMenuRows("START_HERE,GO_HERE", true, false))
        assertEquals("[\"START_HERE\",\"GO_HERE\",\"GOOGLE\",\"REMOVE_PIN\"]", WebRules.pinMenuRows("START_HERE,GO_HERE", true, false, false))
        assertEquals("[\"ADD\",\"GOOGLE\",\"REMOVE_PIN\"]", WebRules.pinMenuRows("SET_DESTINATION", true, false))
        assertEquals("[\"ADD\",\"GOOGLE\",\"REMOVE_PIN\"]", WebRules.pinMenuRows("ADD", true, false))
        assertEquals("[\"ADD_STOP\",\"NEW_DESTINATION\",\"GOOGLE\",\"REMOVE_PIN\"]", WebRules.pinMenuRows("ADD_STOP,NEW_DESTINATION", true, false))
        // the likelier on top and tinted (owner 2026-11-26 night, addMenuEmphasised)
        assertEquals("[\"NEW_DESTINATION\",\"ADD_STOP\",\"GOOGLE\",\"REMOVE_PIN\"]", WebRules.pinMenuRows("NEW_DESTINATION,ADD_STOP", true, false))
        assertEquals("[\"ADD_STOP\",\"NEW_DESTINATION\",\"GOOGLE\"]", WebRules.pinMenuRows("ADD_STOP,NEW_DESTINATION", false, false))
        assertEquals("[\"START_HERE\",\"GO_HERE\",\"FAVOURITE\",\"HOME\",\"GOOGLE\",\"REMOVE_PIN\"]", WebRules.pinMenuRows("START_HERE,GO_HERE", true, true, false))
        // the page's „Rundtour ab hier“ last in the route group (owner 2026-11-26, the app's Row.ROUND_TRIP), under the page's key
        assertEquals("[\"START_HERE\",\"GO_HERE\",\"ROUND_TRIP_HERE\",\"FAVOURITE\",\"HOME\",\"GOOGLE\",\"REMOVE_PIN\"]",
            WebRules.pinMenuRows("START_HERE,ROUND_TRIP_HERE,GO_HERE", true, true, false))
        assertEquals("[\"GO_HERE\",\"START_HERE\",\"ROUND_TRIP_HERE\",\"GOOGLE\"]", WebRules.pinMenuRows("START_HERE,ROUND_TRIP_HERE,GO_HERE", false, false))
        // a multi-day tour: „Als Übernachtung“ after the add rows (the shared PinMenuItems overnight, owner 2026-11-26 night)
        assertEquals("[\"NEW_DESTINATION\",\"OVERNIGHT\",\"GOOGLE\",\"REMOVE_PIN\"]", WebRules.pinMenuRows("NEW_DESTINATION,OVERNIGHT", true, false))
        assertEquals("null", WebRules.addMenuEmphasised("START_HERE,ROUND_TRIP_HERE,GO_HERE"))
        assertEquals("\"ADD_STOP\"", WebRules.addMenuEmphasised("ADD_STOP,NEW_DESTINATION"))
        assertEquals("\"NEW_DESTINATION\"", WebRules.addMenuEmphasised("NEW_DESTINATION,ADD_STOP"))
        assertEquals("null", WebRules.addMenuEmphasised("SET_DESTINATION"))
        assertEquals("null", WebRules.addMenuEmphasised("START_HERE,GO_HERE"))
    }
}
