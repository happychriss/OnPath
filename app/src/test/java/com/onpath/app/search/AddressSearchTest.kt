package com.onpath.app.search

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/** JDBC-backed [AddressDb] for the JVM. */
class JdbcAddressDb(file: File) : AddressDb {
    private val c = DriverManager.getConnection("jdbc:sqlite:${file.path}")
    override fun rows(sql: String, vararg args: String): List<Array<Any?>> = c.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setString(i + 1, a) }
        st.executeQuery().use { rs ->
            val n = rs.metaData.columnCount
            val out = ArrayList<Array<Any?>>()
            while (rs.next()) out += Array(n) { i ->
                when (val v = rs.getObject(i + 1)) { is Int -> v.toLong(); else -> v }
            }
            out
        }
    }
    override fun close() = c.close()
}

/** Offline address lookup on the fixture built by tools/extract_addresses.py (see the .osm header). */
class AddressSearchTest {

    companion object {
        private lateinit var index: AddressIndex

        @BeforeClass @JvmStatic fun open() {
            val dir = File(AddressSearchTest::class.java.getResource("/search/addresses-fixture.sqlite")!!.toURI()).parentFile
            val data = kotlin.io.path.createTempDirectory("addr").toFile()
            File(dir, "addresses-fixture.sqlite").copyTo(File(data, AddressIndex.FILE))
            index = AddressIndex.open(data) { JdbcAddressDb(it) }
        }

        @AfterClass @JvmStatic fun close() = index.close()
    }

    private fun find(q: String) = index.search(AddressQuery.parse(q))

    @Test fun parsesAddressQueries() {
        assertEquals(AddressQuery(listOf("laurin", "strasse", "meran"), "5", null, null), AddressQuery.parse("Laurinstraße 5, Meran"))
        assertEquals(AddressQuery(listOf("meran", "laurin", "strasse"), null, null, null), AddressQuery.parse("Meran Laurinstraße"))
        assertEquals(AddressQuery(listOf("via", "roma", "bolzano"), "3", null, "IT"), AddressQuery.parse("via Roma 3 Bolzano Italia"))
        assertEquals(AddressQuery(listOf("laurin", "str"), "7a", "39012", null), AddressQuery.parse("Laurin Str. 7 a 39012"))
        assertEquals("12b", AddressQuery.parse("Dorf 12/b").number)
        assertEquals(listOf("italia"), AddressQuery.parse("Italia").words) // a country alone is a name
    }

    @Test fun houseNumberWithTown() {
        val r = find("Laurinstraße 5, Meran")
        val first = r.first()
        assertEquals("Laurinstraße 5 · Gratsch", first.place.name)
        assertEquals("address", first.place.kind)
        assertEquals(46.6842, first.place.lat, 1e-5)
        assertEquals("IT", first.place.cc)
        assertTrue(first.place.alt.contains("Via Laurin 5"))
        assertEquals(3, first.tier)
    }

    @Test fun italianNamesAndTownFirst() {
        val r = find("via Roma 3 Bolzano")
        assertEquals("Romstraße 3 · Bozen", r.first().place.name)
        assertTrue(r.none { it.place.town == "Meran" })
        // German town word, other order
        assertEquals("Laurinstraße · Gratsch", find("Meran Laurinstraße").first().place.name)
    }

    @Test fun abbreviationsAndLetterNumbers() {
        val r = find("Laurin Str. 7a")
        assertEquals("Laurinstraße 7a · Gratsch", r.first().place.name)
        assertEquals("Laurinstraße 7a · Gratsch", find("laurinstr 7").first().place.name)
    }

    @Test fun addrPlaceAndNoMatch() {
        assertEquals("Pawigl 12 · Meran", find("Pawigl 12").first().place.name)
        assertTrue(find("Rennweg 1").isEmpty())
        assertTrue(find("Laurinstraße Bozen").isEmpty())
    }

    @Test fun countryAndTowns() {
        assertEquals("IT", index.countryNear(46.67, 11.16))
        assertNull(index.countryNear(48.1, 11.5))
        assertEquals(setOf("Bozen"), index.townsNamed(listOf("via", "roma", "bolzano")).map { it.name }.toSet())
    }

    @Test fun mergedIntoOfflineRanking() {
        val q = SearchQuery("Laurinstraße 5", refLat = 46.67, refLon = 11.16)
        val hits = SearchRanking.rank(emptyList(), q, find(q.text))
        assertEquals("Laurinstraße 5 · Gratsch", hits.first().place.name)
    }

    @Test fun joinedStreetWordAndTypo() {
        // owner, 2026-10-20: "garibalistrasse 13, Merano" found nothing (typo + joined word)
        assertEquals(listOf("garibali", "strasse", "merano"), AddressQuery.parse("garibalistrasse 13, Merano").words)
        assertEquals("Laurinstraße 5 · Gratsch", find("Laurnstrasse 5, Meran").first().place.name)
        assertEquals("Laurinstraße 5 · Gratsch", find("Laurinstrase 5 Meran").first().place.name)
        assertEquals(1, AddressFile.editDistance("garibali", "garibald"))
        assertTrue(find("Xyzqw 5").isEmpty())
    }

    @Test fun villageInNameMunicipalityOnLineTwo() {
        // owner 2026-10-22: the village / fraction after the street, the municipality small on line 2
        val p = find("Laurinstraße 5, Meran").first().place
        assertEquals("Gratsch", p.village)
        assertEquals("Meran", p.town)
        assertEquals("Meran", p.municipality)
        assertTrue(com.onpath.app.ui.search.resultSubtitle(SearchHit(p, 0.0, 3, 1200.0, false))
            .startsWith("Address · Meran · "))
        // no village near: the municipality is the name, nothing repeated on line 2
        val bz = find("via Roma 3 Bolzano").first().place
        assertEquals("Bozen", bz.village)
        assertNull(bz.municipality)
        // the fraction's name finds the street too
        assertEquals("Laurinstraße · Gratsch", find("Laurinstraße Gratsch").first().place.name)
    }

    @Test fun villageBeatsNearbyHamletAndOldFilesWork() {
        // A file like the extractor writes (also before 2026-10-22: every place is stored with kind and point).
        val towns = listOf(
            arrayOf<Any?>(1L, "m", "Lana", null, "IT", 4660000L, 1115000L),
            arrayOf<Any?>(2L, "village", "Völlan", "Foiana", "IT", 4661000L, 1112000L),
            arrayOf<Any?>(3L, "hamlet", "Mayrhof", null, "IT", 4661500L, 1112600L),
            arrayOf<Any?>(4L, "village", "Lana", null, "IT", 4660100L, 1115100L),
        )
        val db = object : AddressDb {
            override fun rows(sql: String, vararg args: String): List<Array<Any?>> = if (sql.contains("FROM town")) towns else emptyList()
            override fun close() {}
        }
        val f = AddressFile(db, "t")
        // ~280 m to the hamlet (counts double), ~440 m to the village: the village wins
        assertEquals("Völlan", f.villageAt(46.613, 11.1237)?.name)
        // right next to the hamlet it wins
        assertEquals("Mayrhof", f.villageAt(46.61505, 11.12605)?.name)
        // nothing within 2 km
        assertNull(f.villageAt(46.70, 11.30))
        // the municipality's own village: same name → no line 2
        assertNull(Place("a", "Hauptstraße 1 · Lana", "address", 46.601, 11.151, village = "Lana", town = "Lana").municipality)
        assertEquals("Lana", Place("b", "Hauptstraße 5 · Völlan", "address", 46.61, 11.12, village = "Völlan", town = "Lana").municipality)
        // not an offline address (no village): never a municipality line
        assertNull(Place("c", "Hotel", "other", 46.61, 11.12, town = "Lana").municipality)
    }

    @Test fun upperBound() { assertEquals("laus", AddressFile.upper("laur")) }
}
