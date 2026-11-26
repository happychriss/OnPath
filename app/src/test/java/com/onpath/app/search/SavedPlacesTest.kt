package com.onpath.app.search

import com.onpath.app.backup.BackupCodec
import com.onpath.app.backup.BackupRestore
import com.onpath.app.backup.PlacesBackup
import com.onpath.app.domain.PinMenuItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** „Orte merken“ (owner 2026-11-15): Zuhause + favourite places – the rules, the search's lists, the backup file. */
class SavedPlacesTest {
    private fun place(id: String, name: String?, lat: Double = 51.0, lon: Double = 7.0, kind: String = "cafe") =
        Place(id, name, kind, lat, lon)

    private val cafe = place("n1", "Waldcafé")
    private val ruin = place("n2", "Burgruine", 51.1, 7.1, "ruins")
    private val hut = place("n3", "Meraner Hütte", 46.7, 11.1, "hut")

    @Test fun merkenSetsAndRemovesWithOneTap() {
        val s = SavedPlaces().toggleFavourite(cafe)
        assertTrue(s.isFavourite(cafe))
        assertFalse(s.toggleFavourite(cafe).isFavourite(cafe))
        // the newest favourite comes first
        assertEquals(listOf("n2", "n1"), s.toggleFavourite(ruin).favourites.map { it.id })
    }

    @Test fun theSamePlaceFromTheMapAndFromTheSearchIsOneFavourite() {
        // the POI held on the map (its OSM id) and the search's copy of it (another id, same name, a few metres away)
        val fromSearch = Place("s:42", "Waldcafé", "cafe", 51.00002, 7.00001, address = "Waldweg 1")
        val s = SavedPlaces().toggleFavourite(cafe)
        assertTrue(s.isFavourite(fromSearch))
        assertTrue(s.toggleFavourite(fromSearch).favourites.isEmpty())
        // two unnamed places: the same spot is the same place, another spot is not
        val bench = place("x1", null, kind = "bench")
        assertTrue(SavedPlaces().toggleFavourite(bench).isFavourite(place("x2", null, 51.00001, 7.0, "bench")))
        assertFalse(SavedPlaces().toggleFavourite(bench).isFavourite(place("x3", null, 51.001, 7.0, "bench")))
    }

    @Test fun oneHomeReplacedOrRemoved() {
        val s = SavedPlaces().withHome(cafe)
        assertTrue(s.isHome(cafe))
        assertTrue(s.withHome(ruin).isHome(ruin))
        assertFalse(s.withHome(ruin).isHome(cafe))
        assertNull(s.withHome(null).home)
    }

    @Test fun theSearchShowsHomeFiveFavouritesThenMoreAndRecentWithoutRepeats() {
        val favs = (1..7).map { place("f$it", "Fav $it", 51.0 + it, 7.0) }
        val s = SavedPlaces(home = hut, favourites = favs)
        val recent = listOf(place("r1", "Algund", 46.6, 11.1), favs[6], hut, place("r2", "Zons", 51.1, 6.8))
        val start = s.start(recent)
        assertEquals(hut, start.home)
        assertEquals(SavedPlaces.SHOWN_FAVOURITES, start.favourites.size)
        assertEquals(2, start.moreFavourites)
        // a place shown above (home, any favourite – shown or behind „Alle“) is not repeated under Zuletzt
        assertEquals(listOf("r1", "r2"), start.recent.map { it.id })
        val all = s.start(recent, all = true)
        assertEquals(7, all.favourites.size)
        assertEquals(0, all.moreFavourites)
        // nothing kept: the search as before
        assertEquals(recent, SavedPlaces().start(recent).recent)
        assertNull(SavedPlaces().start(recent).home)
    }

    @Test fun aRestoreJoinsThePhonesPlaces() {
        val phone = SavedPlaces(home = cafe, favourites = listOf(ruin))
        val backup = SavedPlaces(home = hut, favourites = listOf(hut, ruin, hut))
        val merged = phone.merge(backup)
        assertEquals(cafe, merged.home) // the phone's home wins
        assertEquals(listOf("n2", "n3"), merged.favourites.map { it.id }) // no doubles
        assertEquals(hut, SavedPlaces().merge(backup).home)
        assertEquals(1, BackupRestore.placesAdded(phone, merged))
        assertEquals(2, BackupRestore.placesAdded(SavedPlaces(), SavedPlaces().merge(SavedPlaces(hut, listOf(ruin)))))
    }

    @Test fun theBackupFileRoundTripsAndIsNeverATour() {
        val s = SavedPlaces(home = Place("a:1", "Mittelstraße 12 · Hilden", "address", 51.17, 6.93, address = "40721 Hilden",
            source = Place.Source.MAP, town = "Hilden"), favourites = listOf(cafe, hut.copy(ele = 1960), place("x", "Tab\tand <&> \"q\"")))
        val text = PlacesBackup.encode(s, timeMs = 0L)
        assertTrue(PlacesBackup.isPlaces(text))
        assertFalse("never read as a tour", BackupCodec.hasRecord(text))
        // other apps see the places as waypoints
        assertTrue(text.contains("<wpt lat=\"51.1700000\" lon=\"6.9300000\">"))
        val back = PlacesBackup.decode(text)!!
        assertEquals(s.home!!.copy(alt = emptyList()), back.home)
        assertEquals(listOf("n1", "n3", "x"), back.favourites.map { it.id })
        assertEquals(1960, back.favourites[1].ele)
        assertEquals("Tab and <&> \"q\"", back.favourites[2].name) // a tab in a name becomes a space (as on the phone)
        // empty places: a valid file, nothing in it
        assertEquals(SavedPlaces(), PlacesBackup.decode(PlacesBackup.encode(SavedPlaces(), 0L)))
    }

    @Test fun aBrokenOrForeignFileRestoresNoPlaces() {
        assertNull(PlacesBackup.decode("<gpx><trk/></gpx>"))
        assertFalse(PlacesBackup.isPlaces("<gpx><metadata><extensions><onpath:backup>{}</onpath:backup></extensions></metadata></gpx>"))
        // a broken line is skipped, the rest comes back
        val text = PlacesBackup.encode(SavedPlaces(favourites = listOf(cafe, ruin)), 0L).replace("fav\tn1\t", "fav\t")
        assertEquals(listOf("n2"), PlacesBackup.decode(text)!!.favourites.map { it.id })
    }

    @Test fun theHeldPlacesMenuOffersMerkenAndZuhauseAfterTheRouteRows() {
        assertEquals(listOf(PinMenuItems.Row.GO_HERE, PinMenuItems.Row.START_HERE, PinMenuItems.Row.FAVOURITE,
            PinMenuItems.Row.HOME, PinMenuItems.Row.GOOGLE, PinMenuItems.Row.REMOVE_PIN),
            PinMenuItems.of(emptyPlan = true, startHere = true, menu = null, pin = true, keep = true))
        assertFalse(PinMenuItems.Row.FAVOURITE in PinMenuItems.of(emptyPlan = true, startHere = true, menu = null, pin = false))
    }
}
