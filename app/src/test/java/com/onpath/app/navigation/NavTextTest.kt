package com.onpath.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTextTest {
    @Test fun positionForEmergencyCall() {
        assertEquals("46.54120 N 10.90320 E · ±8 m · 1234 m", NavText.position(46.5412, 10.9032, 8.2f, 1234.4, 5))
        assertEquals("33.86000 S 151.21000 E", NavText.position(-33.86, 151.21, null, null, 0))
        assertEquals("46.54120 N 10.90320 E · position 4 min old", NavText.position(46.5412, 10.9032, null, null, 250))
    }

    /** One off-route card – "Off route", "120 m north-east (direct)", the state. */
    @Test fun offRouteCardTexts() {
        assertEquals("120 m north-east (direct)", NavText.toRoute(RouteHint(118.0, 44.0, com.onpath.app.routing.GeoPoint(46.0, 11.0))))
        assertEquals("Looking for a way back…", NavText.state(NavPrompt.Searching))
        assertEquals("No way back found", NavText.state(NavPrompt.NoWay(Rerouter.Reason.NO_WAY)))
        assertEquals("No map here to find a way back", NavText.state(NavPrompt.NoWay(Rerouter.Reason.NO_DATA)))
        assertEquals("Following the original route", NavText.state(NavPrompt.Kept))
    }

    /**
     * Owner 2026-10-30: the main question is "re-plan?", skipping is one of the answers. Line = what going via the stop
     * costs on top of leaving it out; the stop's name only when short enough for the button.
     */
    private val skipQ = NavPrompt.Question(1, 350.0, 600.0, 20.0, emptyList(), 900.0, 2_000.0, skipStop = 2,
        via = NavPrompt.Question(1, 1_750.0, 600.0, 20.0, emptyList(), 2_300.0, 2_000.0))

    @Test fun replanQuestionTexts() {
        assertEquals("Off route – re-plan?", NavText.title(skipQ))
        assertEquals("Re-plan?", NavText.state(skipQ))
        assertEquals("Via stop 3: +1.4 km · rejoins in 600 m", NavText.question(skipQ))
        assertEquals("Yes", NavText.YES)
        assertEquals("Yes, skip stop 3", NavText.skipButton(skipQ))
        assertEquals("Yes, skip it", NavText.SKIP_SHORT)
        // a name that fits; one too long for the button → "stop 3", in the line too
        val named = skipQ.copy(skipName = "Gasthof Hochmuth")
        assertEquals("Yes, skip Gasthof Hochmuth", NavText.skipButton(named))
        assertTrue(NavText.question(named).startsWith("Via Gasthof Hochmuth: +1.4 km"))
        assertEquals("Yes, skip stop 3", NavText.skipButton(named, NavText.NAME_MAX_LARGE))
        assertTrue(NavText.question(named, NavText.NAME_MAX_LARGE).startsWith("Via stop 3: "))
        val long = skipQ.copy(skipName = "Berggasthof Rifugio Hochmuth")
        assertEquals("Yes, skip stop 3", NavText.skipButton(long))
        // the rider moved on: both ways grow alike – the extra stays, "rejoins in" follows (1f66708)
        assertEquals("Via stop 3: +1.4 km · rejoins in 720 m", NavText.question(skipQ.copy(movedM = 120.0, via = skipQ.via!!.copy(movedM = 120.0))))
        // about as long either way; a fresh route (no rejoin) says its length
        assertEquals("Via stop 3: about as long · new route 900 m", NavText.question(skipQ.copy(rejoinInM = Double.NaN, via = skipQ.via!!.copy(extraM = 352.0))))
        // left out without asking: said at the end, with the full name
        assertTrue(NavText.question(long.copy(via = null)).endsWith(" · without Berggasthof Rifugio Hochmuth"))
        assertEquals("Off route – new route found", NavText.title(long.copy(via = null)))
    }

    @Test fun replanQuestionTextsGerman() {
        val old = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            assertEquals("Abseits der Route – neu planen?", NavText.title(skipQ))
            assertEquals("Über Stopp 3: +1,4 km · zurück auf der Route in 600 m", NavText.question(skipQ))
            assertEquals("Ja", NavText.YES)
            assertEquals("Ja, Stopp 3 auslassen", NavText.skipButton(skipQ))
            assertEquals("Ja, Gasthof Hochmuth auslassen", NavText.skipButton(skipQ.copy(skipName = "Gasthof Hochmuth")))
            assertEquals("Ja, auslassen", NavText.SKIP_SHORT)
            assertEquals("Original behalten", NavText.KEEP)
            assertTrue(NavText.question(skipQ.copy(via = skipQ.via!!.copy(extraM = 352.0))).startsWith("Über Stopp 3: etwa gleich lang · "))
        } finally { java.util.Locale.setDefault(old) }
    }

    @Test fun positionCopyIsPlainLatLon() {
        assertEquals("46.54120, 10.90320", NavText.positionCopy(46.5412, 10.9032))
    }
}
