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
        assertEquals(AddressQuery(listOf("laurinstrasse", "meran"), "5", null, null), AddressQuery.parse("Laurinstraße 5, Meran"))
        assertEquals(AddressQuery(listOf("meran", "laurinstrasse"), null, null, null), AddressQuery.parse("Meran Laurinstraße"))
        assertEquals(AddressQuery(listOf("via", "roma", "bolzano"), "3", null, "IT"), AddressQuery.parse("via Roma 3 Bolzano Italia"))
        assertEquals(AddressQuery(listOf("laurin", "str"), "7a", "39012", null), AddressQuery.parse("Laurin Str. 7 a 39012"))
        assertEquals("12b", AddressQuery.parse("Dorf 12/b").number)
        assertEquals(listOf("italia"), AddressQuery.parse("Italia").words) // a country alone is a name
    }

    @Test fun houseNumberWithTown() {
        val r = find("Laurinstraße 5, Meran")
        val first = r.first()
        assertEquals("Laurinstraße 5 · Meran", first.place.name)
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
        assertEquals("Laurinstraße · Meran", find("Meran Laurinstraße").first().place.name)
    }

    @Test fun abbreviationsAndLetterNumbers() {
        val r = find("Laurin Str. 7a")
        assertEquals("Laurinstraße 7a · Meran", r.first().place.name)
        assertEquals("Laurinstraße 7a · Meran", find("laurinstr 7").first().place.name)
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
        assertEquals("Laurinstraße 5 · Meran", hits.first().place.name)
    }

    @Test fun upperBound() { assertEquals("laus", AddressFile.upper("laur")) }
}
