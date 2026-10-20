package com.onpath.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPlaceTest {

    private fun assertAt(lat: Double, lon: Double, p: SharedPlace?) {
        assertTrue("no coordinate in $p", p != null && p.hasPoint)
        assertEquals(lat, p!!.lat!!, 1e-6); assertEquals(lon, p.lon!!, 1e-6)
    }

    // ---- share text from Google Maps ----

    @Test fun nameAddressShortLink() {
        val p = SharedPlaceParser.fromText("Hotel Aurora\nPiazza della Rena 13, 39012 Meran BZ, Italien\nhttps://maps.app.goo.gl/AbC123xyz")!!
        assertEquals("Hotel Aurora", p.name)
        assertEquals("Piazza della Rena 13, 39012 Meran BZ, Italien", p.address)
        assertEquals("https://maps.app.goo.gl/AbC123xyz", p.link)
        assertFalse(p.hasPoint)
    }

    @Test fun onlyShortLink() {
        val p = SharedPlaceParser.fromText("https://maps.app.goo.gl/mWtb4a1cUE9zMWya7?g_st=ac")!!
        assertEquals("https://maps.app.goo.gl/mWtb4a1cUE9zMWya7?g_st=ac", p.link)
        assertNull(p.name)
    }

    @Test fun oldGooGlLink() {
        val p = SharedPlaceParser.fromText("Leiter Alm https://goo.gl/maps/xYz12345")!!
        assertEquals("https://goo.gl/maps/xYz12345", p.link)
        assertEquals("Leiter Alm", p.name)
        assertTrue(SharedPlaceParser.isShortLink("goo.gl/maps/xYz12345"))
        assertFalse(SharedPlaceParser.isShortLink("https://goo.gl/abc")) // not a maps link
    }

    @Test fun subjectGivesNameWhenTextHasOnlyLink() {
        val p = SharedPlaceParser.fromText("https://maps.app.goo.gl/AbC", subject = "Leiter Alm")!!
        assertEquals("Leiter Alm", p.name)
    }

    @Test fun fullPlaceLinkPrefersPlaceOverCamera() {
        val url = "https://www.google.com/maps/place/Moonraft+Innovation+Labs/@12.9140000,77.6200000,17z/data=!3m1!4b1!4m6!3m5!1s0x3bae148db555a135:0xdf658e2653660ee6!8m2!3d12.9140057!4d77.6281936!16s%2Fg%2F1tg66jp9?entry=tts"
        val p = SharedPlaceParser.fromText(url)!!
        assertAt(12.9140057, 77.6281936, p)
        assertEquals("Moonraft Innovation Labs", p.name)
        assertNull(p.link)
    }

    @Test fun cameraOnlyWhenNoPlace() {
        assertAt(46.67, 11.15, SharedPlaceParser.fromUrl("https://www.google.com/maps/@46.67,11.15,15z"))
    }

    @Test fun qLlAndSearchPath() {
        assertAt(46.67, 11.15, SharedPlaceParser.fromUrl("https://www.google.com/maps?q=46.67,11.15"))
        assertAt(46.67, 11.15, SharedPlaceParser.fromUrl("https://maps.google.com/?q=46.67,+11.15"))
        assertAt(46.67, 11.15, SharedPlaceParser.fromUrl("maps.google.com/?q=46.67%2C11.15&z=14"))
        assertAt(46.6, 11.1, SharedPlaceParser.fromUrl("https://maps.google.com/maps?ll=46.6,11.1&z=12"))
        assertAt(46.67, 11.15, SharedPlaceParser.fromUrl("https://www.google.com/maps/search/46.67,+11.15"))
        assertAt(-33.8568, 151.2153, SharedPlaceParser.fromUrl("https://www.google.com/maps/search/?api=1&query=-33.8568%2C151.2153"))
        assertAt(46.5, 11.2, SharedPlaceParser.fromUrl("https://www.google.de/maps/place/46.5,11.2"))
    }

    @Test fun qTextIsQueryNotCoordinate() {
        val p = SharedPlaceParser.fromUrl("https://www.google.com/maps?q=Hotel+Aurora,+Meran&ftid=0x47829:0x1")!!
        assertFalse(p.hasPoint)
        assertEquals("Hotel Aurora, Meran", p.query)
    }

    @Test fun consentPageCarriesTheLink() {
        val u = "https://consent.google.com/ml?continue=https://www.google.com/maps/place/X/@46.1,11.1,17z/data%3D!3d46.6712!4d11.1581&gl=DE"
        assertAt(46.6712, 11.1581, SharedPlaceParser.fromUrl(u))
    }

    @Test fun textWithLinkInsideLine() {
        val p = SharedPlaceParser.fromText("Look here: https://www.google.com/maps?q=46.6712,11.1581 – nice")!!
        assertAt(46.6712, 11.1581, p)
    }

    @Test fun droppedPinWithCoordinates() {
        val p = SharedPlaceParser.fromText("Dropped pin\n46.671234, 11.158123\nhttps://maps.app.goo.gl/Qq")!!
        assertAt(46.671234, 11.158123, p)
        assertNull(p.name); assertNull(p.link)
        val dms = SharedPlaceParser.fromText("46°40'16.4\"N 11°09'29.2\"E")!!
        assertEquals(46.6712, dms.lat!!, 1e-4); assertEquals(11.1581, dms.lon!!, 1e-4)
    }

    @Test fun plainAddressText() {
        val p = SharedPlaceParser.fromText("Laurinstraße 5, Meran")!!
        assertFalse(p.hasPoint)
        assertEquals("Laurinstraße 5, Meran", p.name)
        assertTrue("Laurinstraße 5, Meran" in p.searchTexts())
    }

    @Test fun nothingUseful() {
        assertNull(SharedPlaceParser.fromText(""))
        assertNull(SharedPlaceParser.fromText(null, null))
        assertNull(SharedPlaceParser.fromUrl("https://example.com/foo?q=1,2"))
    }

    @Test fun osmLinks() {
        assertAt(46.6, 11.1, SharedPlaceParser.fromUrl("https://www.openstreetmap.org/?mlat=46.6&mlon=11.1#map=15/46.5/11.0"))
        assertAt(46.5, 11.0, SharedPlaceParser.fromUrl("https://www.openstreetmap.org/#map=15/46.5/11.0"))
    }

    @Test fun invalidCoordinatesAreIgnored() {
        assertNull(SharedPlaceParser.fromUrl("https://www.google.com/maps?q=0,0"))
        assertNull(SharedPlaceParser.fromUrl("https://www.google.com/maps?q=95.0,11.0"))
    }

    // ---- geo: URIs ----

    @Test fun geoUris() {
        assertAt(46.6, 11.1, SharedPlaceParser.fromGeoUri("geo:46.6,11.1"))
        assertAt(46.6, 11.1, SharedPlaceParser.fromGeoUri("geo:46.6,11.1;u=35?z=15"))
        val named = SharedPlaceParser.fromGeoUri("geo:0,0?q=46.6,11.1(Leiter Alm)")!!
        assertAt(46.6, 11.1, named); assertEquals("Leiter Alm", named.name)
        val encoded = SharedPlaceParser.fromGeoUri("geo:0,0?q=46.6%2C11.1%28Leiter%20Alm%29")!!
        assertAt(46.6, 11.1, encoded); assertEquals("Leiter Alm", encoded.name)
        val addr = SharedPlaceParser.fromGeoUri("geo:0,0?q=Via+Roma+3,+Meran")!!
        assertFalse(addr.hasPoint); assertEquals("Via Roma 3, Meran", addr.query)
        val near = SharedPlaceParser.fromGeoUri("geo:46.6,11.1?q=Pizzeria")!!
        assertAt(46.6, 11.1, near); assertEquals("Pizzeria", near.name)
        assertNull(SharedPlaceParser.fromGeoUri("geo:0,0"))
        assertNull(SharedPlaceParser.fromGeoUri("http://x"))
    }

    // ---- fallback search texts ----

    @Test fun searchTextsDropCountryAndProvince() {
        val t = SharedPlaceParser.searchTexts("Hotel Aurora", "Piazza della Rena 13, 39012 Meran BZ, Italien", null)
        assertEquals("Piazza della Rena 13, 39012 Meran", t[0])
        assertEquals("Hotel Aurora, Piazza della Rena 13, 39012 Meran", t[1])
        assertEquals("Hotel Aurora", t[2])
        assertEquals("Via Roma 3, Meran", SharedPlaceParser.cleanAddress("MRV7+2Q Via Roma 3, Meran"))
    }

    // ---- HTML ----

    @Test fun htmlOnlyTrustsThePlaceMarker() {
        // The page's start view is the requester's IP location – never used.
        assertNull(SharedPlaceParser.coordsInHtml("""APP_INITIALIZATION_STATE=[[[19877.4,7.3898,51.4823]] staticmap?center=51.48%2C7.38"""))
        val p = SharedPlaceParser.coordsInHtml("""<link href="https://www.google.com/maps/place/X/data=!4m2!3m1!1s0x0:0x0!3d46.6712!4d11.1581">""")!!
        assertEquals(46.6712, p.first, 1e-6); assertEquals(11.1581, p.second, 1e-6)
    }

    // ---- short link resolver (fake network) ----

    private fun fake(vararg hops: Pair<String, HttpHop>): (String) -> HttpHop {
        val map = hops.toMap()
        return { url -> map[url] ?: throw java.io.IOException("unexpected $url") }
    }

    @Test fun resolvesRedirectToPlaceLink() {
        val r = ShortLinkResolver(fake(
            "https://maps.app.goo.gl/AbC" to HttpHop(302, "https://www.google.com/maps/place/Leiter+Alm/@46.1,11.1,17z/data=!3d46.6995!4d11.0958", null),
        )).resolve("https://maps.app.goo.gl/AbC")
        assertAt(46.6995, 11.0958, r.place)
        assertEquals("Leiter Alm", r.place!!.name)
        assertNull(r.place!!.link)
    }

    @Test fun followsSeveralHopsAndRelativeLocation() {
        val r = ShortLinkResolver(fake(
            "https://goo.gl/maps/x" to HttpHop(301, "https://maps.app.goo.gl/y", null),
            "https://maps.app.goo.gl/y" to HttpHop(302, "https://www.google.com/r1", null),
            "https://www.google.com/r1" to HttpHop(302, "/maps?q=46.5,11.3", null),
        )).resolve("https://goo.gl/maps/x")
        assertAt(46.5, 11.3, r.place)
    }

    @Test fun consentRedirectIsReadNotFollowed() {
        val r = ShortLinkResolver(fake(
            "https://maps.app.goo.gl/c" to HttpHop(302, "https://consent.google.com/ml?continue=https://www.google.com/maps/place/X/data%3D!3d46.67!4d11.15&gl=DE", null),
        )).resolve("https://maps.app.goo.gl/c")
        assertAt(46.67, 11.15, r.place)
    }

    @Test fun bodyWithPlaceMarker() {
        val r = ShortLinkResolver(fake(
            "https://maps.app.goo.gl/b" to HttpHop(302, "https://www.google.com/maps?q=Hotel+Aurora&ftid=1", null),
            "https://www.google.com/maps?q=Hotel+Aurora&ftid=1" to HttpHop(200, null, "...!3d46.6712!4d11.1581..."),
        )).resolve("https://maps.app.goo.gl/b")
        assertAt(46.6712, 11.1581, r.place)
        assertEquals("Hotel Aurora", r.place!!.query)
    }

    @Test fun noCoordinateKeepsQueryForFallback() {
        val r = ShortLinkResolver(fake(
            "https://maps.app.goo.gl/q" to HttpHop(302, "https://www.google.com/maps?q=Hotel+Aurora,+Meran&ftid=1", null),
            "https://www.google.com/maps?q=Hotel+Aurora,+Meran&ftid=1" to HttpHop(200, null, "APP_INITIALIZATION_STATE=[[[1,7.38,51.48]]"),
        )).resolve("https://maps.app.goo.gl/q")
        assertFalse(r.place!!.hasPoint)
        assertEquals("Hotel Aurora, Meran", r.place!!.query)
    }

    @Test fun offlineAndLoopsGiveNull() {
        val off = ShortLinkResolver({ throw java.net.UnknownHostException("maps.app.goo.gl") }).resolve("https://maps.app.goo.gl/z")
        assertNull(off.place)
        val loop = ShortLinkResolver({ HttpHop(302, "https://maps.app.goo.gl/loop", null) }, maxHops = 3).resolve("https://maps.app.goo.gl/loop")
        assertNull(loop.place)
        assertEquals("too many redirects", loop.reason)
        val notFound = ShortLinkResolver({ HttpHop(404, null, null) }).resolve("https://maps.app.goo.gl/nope")
        assertNull(notFound.place)
    }
}
