package com.onpath.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A place shared from Google Maps without a coordinate is found by its address, else by its name IN the shared town –
 * never a namesake elsewhere. Field bug 0.13.2: "Olive Hilden" shared from Google Maps, the plan showed the "Olive" in
 * Düsseldorf (the bare name was searched offline before the address online, and its hit was not checked against the town).
 */
class SharedPlaceLookupTest {

    // Hilden and Düsseldorf: ~14 km apart
    private val hilden = SharedTown(51.1690, 6.9390, listOf("Hilden"))
    private val oliveDus = Place("p:dus", "Olive", "restaurant", 51.2277, 6.7735)
    private val oliveHilden = Place("p:hil", "Olive", "restaurant", 51.1688, 6.9372)

    /** Answers by exact text; every text asked is recorded. */
    private class Fake(
        val offline: Map<String, List<Place>> = emptyMap(),
        val online: Map<String, List<Place>> = emptyMap(),
        val towns: Map<String, SharedTown> = emptyMap(),
    ) : SharedPlaceFinder {
        val asked = ArrayList<String>()
        override fun offline(text: String): List<Place> { asked += "off:$text"; return offline[text].orEmpty() }
        override fun online(text: String): List<Place> { asked += "on:$text"; return online[text].orEmpty() }
        override fun town(town: String, postcode: String?): SharedTown? = towns[town]
    }

    /** What the short link of a Google Maps share redirects to (real form: place path, no coordinate). */
    private val redirect = "https://www.google.com/maps/place/Olive,+Mittelstra%C3%9Fe+25,+40721+Hilden,+Deutschland/" +
        "data=!4m2!3m1!1s0x47b8dc2b1c1f2d3b:0x9a1b2c3d4e5f6a7b!18m1!1e1?entry=gps&g_st=ac"

    private fun sharedFromLink(): SharedPlace {
        val share = SharedPlaceParser.fromText("https://maps.app.goo.gl/OlvHld25xyz?g_st=ac", subject = "Olive")!!
        assertEquals("https://maps.app.goo.gl/OlvHld25xyz?g_st=ac", share.link)
        val r = ShortLinkResolver({ u -> if (u.contains("goo.gl")) HttpHop(302, redirect, null) else error("no page wanted: $u") },
            stopAtAddress = true).resolve(share.link!!)
        val got = r.place!!
        return share.copy(name = share.name ?: got.name, address = got.address, query = got.query)
    }

    @Test fun oliveHildenNeverLandsInDuesseldorf() {
        // the phone offline, no Hilden addresses in the offline data, the offline index knows only Düsseldorf's Olive
        val p = sharedFromLink()
        assertEquals("Olive", p.name)
        val f = Fake(offline = mapOf("Olive" to listOf(oliveDus)), towns = mapOf("Hilden" to hilden))
        val r = SharedPlaceLookup(f).find(p.name, p.address, p.query)
        assertEquals(SharedPlaceLookup.Result.Unsure("Olive Hilden"), r)
    }

    @Test fun theNameInTheSharedTownWinsOverANearerNamesake() {
        val p = sharedFromLink()
        val f = Fake(offline = mapOf("Olive" to listOf(oliveDus, oliveHilden)), towns = mapOf("Hilden" to hilden))
        val r = SharedPlaceLookup(f).find(p.name, p.address, p.query) as SharedPlaceLookup.Result.Found
        assertEquals("p:hil", r.place.id)
    }

    @Test fun theAddressIsSearchedOnlineBeforeTheBareNameAnywhere() {
        val p = sharedFromLink()
        val addressHit = Place("o:N1", "Mittelstraße 25", "address", 51.16885, 6.93725, address = "Mittelstraße 25, 40721 Hilden",
            source = Place.Source.ONLINE, town = "Hilden")
        val f = Fake(offline = mapOf("Olive" to listOf(oliveDus)), online = mapOf("Mittelstraße 25, 40721 Hilden" to listOf(addressHit)),
            towns = mapOf("Hilden" to hilden))
        val r = SharedPlaceLookup(f).find(p.name, p.address, p.query) as SharedPlaceLookup.Result.Found
        assertEquals("o:N1", r.place.id)
        assertTrue(r.online)
        assertFalse("the bare name is never searched when a town is known: ${f.asked}", "on:Olive" in f.asked)
    }

    @Test fun onlineNameAndTownSkipsAHitInAnotherTown() {
        val p = sharedFromLink()
        val dus = oliveDus.copy(id = "o:dus", address = "Königsallee 1, 40212 Düsseldorf", town = "Düsseldorf", source = Place.Source.ONLINE)
        val hil = oliveHilden.copy(id = "o:hil", address = "Mittelstraße 25, 40721 Hilden", town = "Hilden", source = Place.Source.ONLINE)
        val f = Fake(online = mapOf("Olive Hilden" to listOf(dus, hil)), towns = mapOf("Hilden" to hilden))
        val r = SharedPlaceLookup(f).find(p.name, p.address, p.query) as SharedPlaceLookup.Result.Found
        assertEquals("o:hil", r.place.id)
    }

    @Test fun anOfflineAddressInTheTownIsTaken() {
        val p = sharedFromLink()
        val a = Place("a:1", "Mittelstraße 25 · Hilden", "address", 51.16885, 6.93725, address = "40721", town = "Hilden", village = "Hilden")
        val f = Fake(offline = mapOf("Mittelstraße 25, 40721 Hilden" to listOf(a), "Olive" to listOf(oliveDus)))
        val r = SharedPlaceLookup(f).find(p.name, p.address, p.query) as SharedPlaceLookup.Result.Found
        assertEquals("a:1", r.place.id)
        assertFalse(r.online)
    }

    @Test fun anAddressInAnotherTownIsNotTaken() {
        // the same street name in Düsseldorf (another postcode, another town) is not the shared address
        val a = Place("a:2", "Mittelstraße 25 · Düsseldorf", "address", 51.2200, 6.7700, address = "40213", town = "Düsseldorf")
        val f = Fake(offline = mapOf("Mittelstraße 25, 40721 Hilden" to listOf(a)), towns = mapOf("Hilden" to hilden))
        assertEquals(SharedPlaceLookup.Result.Unsure("Olive Hilden"), SharedPlaceLookup(f).find("Olive", "Mittelstraße 25, 40721 Hilden, Deutschland", null))
    }

    @Test fun longLinkWithAQueryText() {
        // maps.google.com/?q=Name,+Street,+Postcode+Town – no coordinate: the q text says where
        val p = SharedPlaceParser.fromText("Olive\nhttps://maps.google.com/?q=Olive,+Mittelstra%C3%9Fe+25,+40721+Hilden")!!
        assertFalse(p.hasPoint)
        assertEquals("Olive, Mittelstraße 25, 40721 Hilden", p.query)
        val f = Fake(offline = mapOf("Olive" to listOf(oliveDus, oliveHilden)), towns = mapOf("Hilden" to hilden))
        val r = SharedPlaceLookup(f).find(p.name, p.address, p.query) as SharedPlaceLookup.Result.Found
        assertEquals("p:hil", r.place.id)
        // without a name line the q text gives it
        val noName = SharedPlaceParser.fromUrl("https://maps.google.com/?q=Olive,+Mittelstra%C3%9Fe+25,+40721+Hilden")!!
        assertEquals(SharedPlaceLookup.Result.Unsure("Olive Hilden"),
            SharedPlaceLookup(Fake(offline = mapOf("Olive" to listOf(oliveDus)), towns = mapOf("Hilden" to hilden))).find(noName.name, noName.address, noName.query))
    }

    @Test fun aLinkWithACoordinateNeedsNoSearch() {
        val p = SharedPlaceParser.fromText("Olive\nMittelstraße 25, 40721 Hilden\nhttps://maps.google.com/?q=51.1688,6.9372")!!
        assertTrue(p.hasPoint)
        assertEquals(51.1688, p.lat!!, 1e-6); assertEquals(6.9372, p.lon!!, 1e-6)
    }

    @Test fun theShortLinksPageMarkerIsTheFallback() {
        // the address gave no sure place: the full resolve reads the Google page's place marker (in Hilden)
        val r = ShortLinkResolver({ u ->
            when {
                u.contains("goo.gl") -> HttpHop(302, redirect, null)
                else -> HttpHop(200, null, "…[\"Olive\",null,[null,null,51.1688,6.9372]]…!3d51.1688!4d6.9372…")
            }
        }).resolve("https://maps.app.goo.gl/OlvHld25xyz?g_st=ac")
        assertTrue(r.fromPage)
        assertEquals(51.1688, r.place!!.lat!!, 1e-6); assertEquals(6.9372, r.place!!.lon!!, 1e-6)
    }

    @Test fun bilingualTownNamesAgree() {
        // Google writes "Merano", the offline data "Meran" – the postcode or the town's other name tells it is the same town
        val viaPc = Place("a:3", "Via Goethe 28 · Meran", "address", 46.6700, 11.1600, address = "Goethestraße 28 · 39012", town = "Meran")
        val noPc = viaPc.copy(id = "a:4", address = null)
        val meran = SharedTown(46.6710, 11.1590, listOf("Meran", "Merano"))
        for (hit in listOf(viaPc, noPc)) {
            val f = Fake(offline = mapOf("Via Goethe, 28, 39012 Merano" to listOf(hit)), towns = mapOf("Merano" to meran))
            val r = SharedPlaceLookup(f).find("PRIMUS Garden Bistrot", "Via Goethe, 28, 39012 Merano BZ, Italien", null)
            assertEquals(hit.id, (r as SharedPlaceLookup.Result.Found).place.id)
        }
    }

    @Test fun aBareNameCountsOnlyWhenItIsOnePlace() {
        val alm = Place("p:alm", "Leiter Alm", "hut", 46.6995, 11.0958)
        val one = SharedPlaceLookup(Fake(offline = mapOf("Leiter Alm" to listOf(alm, alm.copy(id = "m:alm", lat = 46.7000)))))
        assertEquals("p:alm", (one.find("Leiter Alm", null, null) as SharedPlaceLookup.Result.Found).place.id)
        val twoTowns = SharedPlaceLookup(Fake(offline = mapOf("Leiter Alm" to listOf(alm, alm.copy(id = "p:far", lat = 47.2, lon = 11.4)))))
        assertEquals(SharedPlaceLookup.Result.Unsure("Leiter Alm"), twoTowns.find("Leiter Alm", null, null))
        // other names among the hits are not namesakes
        val other = SharedPlaceLookup(Fake(offline = mapOf("Leiter Alm" to listOf(alm, Place("p:x", "Leiter Alm Hütte", "hut", 47.2, 11.4)))))
        assertTrue(other.find("Leiter Alm", null, null) is SharedPlaceLookup.Result.Found)
    }

    @Test fun nothingAnywhereIsNotFound() {
        assertEquals(SharedPlaceLookup.Result.NotFound, SharedPlaceLookup(Fake()).find("Olive", "Mittelstraße 25, 40721 Hilden", null))
        assertEquals(SharedPlaceLookup.Result.NotFound, SharedPlaceLookup(Fake()).find(null, null, null))
    }

    @Test fun areaFromGoogleAddresses() {
        assertEquals(SharedArea("40721", "Hilden"), SharedArea.of("Mittelstraße 25, 40721 Hilden, Deutschland"))
        assertEquals(SharedArea("39012", "Merano"), SharedArea.of("Via Goethe, 28, 39012 Merano BZ, Italien"))
        assertEquals(SharedArea("6020", "Innsbruck"), SharedArea.of("Maria-Theresien-Straße 1, 6020 Innsbruck, Österreich"))
        assertEquals(SharedArea(null, "Meran"), SharedArea.of(null, "Hotel Aurora, Meran"))
        assertTrue(SharedArea.of("Via Roma 3").isEmpty)
        assertTrue(SharedArea.of(null).isEmpty)
    }
}
