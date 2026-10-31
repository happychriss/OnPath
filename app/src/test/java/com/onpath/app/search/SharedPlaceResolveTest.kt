package com.onpath.app.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A place shared into OnPath, end to end without a network (owner 2026-10-31, A-020): a coordinate in the link →
 * offline index / Photon → the phone's address service → "Place not clear". The Google Maps page is never loaded.
 */
class SharedPlaceResolveTest {

    private val requested = ArrayList<String>()
    private val asked = ArrayList<Triple<String?, String?, String?>>()

    /** Short links redirect as given; any other URL (a Google Maps page) fails the test. */
    private fun net(vararg redirects: Pair<String, String>): (String) -> HttpHop {
        val m = redirects.toMap()
        return { u -> requested += u; HttpHop(302, m[u] ?: throw AssertionError("page requested: $u")) }
    }

    private fun resolve(shared: SharedPlace, fetch: (String) -> HttpHop = net(), result: SharedPlaceLookup.Result = SharedPlaceLookup.Result.NotFound) =
        runBlocking { SharedPlaceResolve(fetch) { n, a, q -> asked += Triple(n, a, q); result }.resolve(shared) }

    @Test fun aCoordinateInTheLinkIsTakenWithoutAnyRequest() {
        val p = SharedPlaceParser.fromText("Olive\nMittelstraße 25, 40721 Hilden\nhttps://maps.google.com/?q=51.1688,6.9372")!!
        val o = resolve(p) as SharedOutcome.Show
        assertEquals(51.1688, o.place.lat, 1e-6)
        assertEquals("Olive", o.place.name)
        assertNull(o.place.note)
        assertTrue(requested.isEmpty()); assertTrue(asked.isEmpty())
    }

    @Test fun aCoordinateInTheRedirectIsTaken() {
        val p = SharedPlaceParser.fromText("https://maps.app.goo.gl/AbC")!!
        val o = resolve(p, net("https://maps.app.goo.gl/AbC" to
            "https://www.google.com/maps/place/Leiter+Alm/@46.1,11.1,17z/data=!3d46.6995!4d11.0958")) as SharedOutcome.Show
        assertEquals(46.6995, o.place.lat, 1e-6); assertEquals(11.0958, o.place.lon, 1e-6)
        assertEquals("Leiter Alm", o.place.name)
        assertEquals(listOf("https://maps.app.goo.gl/AbC"), requested)
        assertTrue(asked.isEmpty())
    }

    private val oliveRedirect = "https://www.google.com/maps/place/Olive,+Mittelstra%C3%9Fe+25,+40721+Hilden,+Deutschland/" +
        "data=!4m2!3m1!1s0x47b8dc2b1c1f2d3b:0x9a1b2c3d4e5f6a7b!18m1!1e1?entry=gps&g_st=ac"

    @Test fun aShortLinkWithoutCoordinateIsFoundByItsAddressNotByThePage() {
        val p = SharedPlaceParser.fromText("https://maps.app.goo.gl/OlvHld25xyz?g_st=ac", subject = "Olive")!!
        val hit = Place("g:0", "Mittelstraße 25", "address", 51.16884, 6.93718, town = "Hilden")
        val o = resolve(p, net("https://maps.app.goo.gl/OlvHld25xyz?g_st=ac" to oliveRedirect),
            SharedPlaceLookup.Result.Found(hit, true, "phone address service, address")) as SharedOutcome.Show
        assertEquals(listOf("https://maps.app.goo.gl/OlvHld25xyz?g_st=ac"), requested) // the Google page: never
        assertEquals("Olive", o.place.name)
        assertEquals(SharedPosition.NOTE, o.place.note)
        assertEquals(Triple("Olive", "Mittelstraße 25, 40721 Hilden, Deutschland", null), asked.single())
    }

    @Test fun noSurePlaceOffersTheSearch() {
        val p = SharedPlaceParser.fromText("https://maps.app.goo.gl/OlvHld25xyz?g_st=ac", subject = "Olive")!!
        val o = resolve(p, net("https://maps.app.goo.gl/OlvHld25xyz?g_st=ac" to oliveRedirect), SharedPlaceLookup.Result.Unsure("Olive Hilden"))
        assertEquals(SharedOutcome.Unsure("Olive Hilden"), o)
    }

    @Test fun offlineShortLinkWithANameStillSearches() {
        val p = SharedPlaceParser.fromText("Hotel Aurora\nVia Roma 3, 39012 Meran\nhttps://maps.app.goo.gl/x")!!
        val o = resolve(p, { throw java.net.UnknownHostException("maps.app.goo.gl") })
        assertEquals(SharedOutcome.NotFound, o)
        assertEquals("Via Roma 3, 39012 Meran", asked.single().second)
    }
}
