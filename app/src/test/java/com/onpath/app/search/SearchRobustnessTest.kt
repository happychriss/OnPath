package com.onpath.app.search

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/** Broken / missing address files and degenerate lines never throw (walk-readiness review 2026-10-20). */
class SearchRobustnessTest {

    private fun dataDir() = kotlin.io.path.createTempDirectory("addr-bad").toFile()

    @Test fun missingGarbageAndEmptyAddressFiles() {
        assertEquals(0, AddressIndex.open(dataDir()) { JdbcAddressDb(it) }.files.size) // no files at all
        val d = dataDir()
        File(d, AddressIndex.FILE).writeText("this is not a database")
        File(d, AddressIndex.DIR).mkdirs()
        File(File(d, AddressIndex.DIR), "empty.sqlite").writeBytes(ByteArray(0))
        // a valid SQLite file without the tables (older / half-written file)
        DriverManager.getConnection("jdbc:sqlite:${File(File(d, AddressIndex.DIR), "noschema.sqlite").path}").use {
            it.createStatement().execute("CREATE TABLE x(a)")
        }
        val idx = AddressIndex.open(d) { JdbcAddressDb(it) }
        for (q in listOf("", "7", "laurinstrasse 5 meran", "a", "italia", "￿￿ 3")) {
            assertTrue(idx.search(AddressQuery.parse(q)).isEmpty())
            idx.townsNamed(SearchText.tokens(q))
        }
        assertNull(idx.countryNear(46.68, 11.13))
        idx.close(); idx.close() // closing twice is harmless
    }

    @Test fun queriesAfterCloseDoNotThrow() {
        val src = File(SearchRobustnessTest::class.java.getResource("/search/addresses-fixture.sqlite")!!.toURI())
        val d = dataDir(); src.copyTo(File(d, AddressIndex.FILE))
        val idx = AddressIndex.open(d) { JdbcAddressDb(it) }
        idx.close() // e.g. the file changed and was re-opened while a search ran
        assertTrue(idx.search(AddressQuery.parse("laurinstrasse 5")).isEmpty())
    }

    @Test fun routeNamerDegenerateLines() {
        val p = GeoPoint(46.68, 11.13)
        assertNull(RouteNamer.name(emptyList(), emptyList()))
        assertNull(RouteNamer.name(listOf(p), emptyList()))
        assertNull(RouteNamer.name(listOf(p, p, p), emptyList()))
        val hut = Place("x", "Hütte", "hut", 46.68, 11.13)
        RouteNamer.name(listOf(p, p), listOf(hut, Place("y", "", "place", 46.68, 11.13)))
    }
}
