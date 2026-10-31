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

    /** Owner 2026-10-31: the far start – "Route starts 31 km away" · "south-west (direct) · recording" · Guide me / Just record. */
    @Test fun farStartTexts() {
        val p = NavPrompt.FarStart(1, 30_600.0, 225.0)
        assertEquals("Route starts 31 km away", NavText.title(p))
        assertEquals("south-west (direct) · recording", NavText.state(p))
        assertEquals("Guide me", NavText.LEAD)
        assertEquals("Just record", NavText.JUST_RECORD)
        assertEquals("Route starts 4.2 km away", NavText.title(p.copy(distanceM = 4_180.0)))
        assertEquals("Route starts 350 m away", NavText.title(p.copy(distanceM = 347.0)))
        assertEquals("Finding a way to the start…", NavText.state(p.copy(searching = true)))
        assertEquals("No way to the route found – recording continues", NavText.title(NavPrompt.LeadFailed(2)))
    }

    /** Owner 2026-10-31: "You're 120 m off the route", the new route / the way back with "350 m longer" (or shorter). */
    private val fresh = NavPrompt.Question(1, 350.0, Double.NaN, 5.0, emptyList(), 3_400.0, 0.0, offM = 118.0)
    private val back = NavPrompt.Question(1, 350.0, 600.0, 5.0, emptyList(), 900.0, 2_000.0, offM = 118.0)

    @Test fun offRouteQuestionTexts() {
        assertEquals("You're 120 m off the route", NavText.title(fresh))
        assertEquals("New route to the end: 3.4 km (350 m longer)", NavText.question(fresh))
        assertEquals("New route to the end: 3.4 km (350 m shorter)", NavText.question(fresh.copy(extraM = -350.0)))
        assertEquals("New route to the end: 3.4 km (about as long)", NavText.question(fresh.copy(extraM = 3.0)))
        assertEquals("Back on the route after 600 m · 350 m longer", NavText.question(back))
        assertEquals("Back on the route after 600 m · 800 m shorter", NavText.question(back.copy(extraM = -800.0)))
        assertEquals("You're 120 m off the route – turn round", NavText.title(back.copy(turnRound = true)))
        assertEquals("New route", NavText.TAKE)
        assertEquals("Keep original", NavText.KEEP)
    }

    @Test fun farStartAndOffRouteTextsGerman() {
        val old = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            val p = NavPrompt.FarStart(1, 30_600.0, 225.0)
            assertEquals("Route beginnt 31 km entfernt", NavText.title(p))
            assertEquals("südwestlich (Luftlinie) · Aufnahme läuft", NavText.state(p))
            assertEquals("Hinführen", NavText.LEAD)
            assertEquals("Nur aufnehmen", NavText.JUST_RECORD)
            assertEquals("Kein Weg zur Route gefunden – Aufnahme läuft weiter", NavText.title(NavPrompt.LeadFailed(2)))
            assertEquals("Du bist 120 m von der Route weg", NavText.title(fresh))
            assertEquals("Neue Route bis zum Ziel: 3,4 km (350 m länger)", NavText.question(fresh))
            assertEquals("Neue Route bis zum Ziel: 3,4 km (350 m kürzer)", NavText.question(fresh.copy(extraM = -350.0)))
            assertEquals("Zurück auf der Route nach 600 m · 350 m länger", NavText.question(back))
            assertEquals("Neue Route", NavText.TAKE)
            assertEquals("Original behalten", NavText.KEEP)
        } finally { java.util.Locale.setDefault(old) }
    }

    @Test fun positionCopyIsPlainLatLon() {
        assertEquals("46.54120, 10.90320", NavText.positionCopy(46.5412, 10.9032))
    }
}
