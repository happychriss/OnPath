package com.onpath.app.data

import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Safety net for `follow.json` (the followed line; the recording service reloads it after a restart) and the
 * parsed preferences (hidden POI kinds, voice setup): missing, empty, cut, garbage, wrong-typed or huge files
 * never throw and never stop a later save.
 */
class BrokenFollowFileTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("follow-bad").toFile()
        Problems.init(dir); Problems.clear()
    }

    private fun prefs(sp: MapPrefs = MapPrefs()) = Prefs(sp, dir)

    private val good = """{"name":"Alm","pts":[46.5,11.2,46.6,11.3],"ele":[1200.0,null]}"""

    private val cases = listOf(
        "empty" to "",
        "blank" to "\n",
        "garbage" to "\u0000ÿ{{garbage",
        "truncated" to good.take(good.length / 2),
        "an array at the top" to "[46.5,11.2]",
        "pts a string" to """{"name":"x","pts":"y"}""",
        "name a number" to """{"name":5,"pts":[46.5,11.2]}""",
        "a coordinate a word" to """{"name":"x","pts":[46.5,"a"]}""",
        "a null coordinate" to """{"name":"x","pts":[46.5,null]}""",
        "no name" to """{"pts":[46.5,11.2]}""",
    )

    @Test fun aBrokenFollowFileIsNotRestoredAndIsReported() {
        for ((name, text) in cases) {
            Problems.clear()
            val p = prefs()
            File(dir, "follow.json").writeText(text)
            assertNull(name, p.loadFollow())
            assertTrue(name, Problems.snapshot().any { it.area == "data" && it.message.contains("unreadable") })
            // the next save works and reloads
            p.saveFollow("Alm", listOf(GeoPoint(46.5, 11.2, 1200.0), GeoPoint(46.6, 11.3)))
            val back = p.loadFollow()
            assertEquals(name, "Alm", back!!.first)
            assertEquals(name, listOf(GeoPoint(46.5, 11.2, 1200.0), GeoPoint(46.6, 11.3)), back.second)
        }
    }

    @Test fun oddOrOversizedFilesAreTolerated() {
        val p = prefs()
        File(dir, "follow.json").writeText("""{"name":"x","pts":[46.5,11.2,46.6]}""") // an odd number: the last half point is dropped
        assertEquals(1, p.loadFollow()!!.second.size)
        File(dir, "follow.json").writeText("""{"name":"x","pts":[46.5,11.2],"ele":"z"}""") // heights of the wrong type: ignored
        assertNull(p.loadFollow()!!.second.single().ele)
        File(dir, "follow.json").writeText("""{"name":"x","pts":[46.5,11.2],"ele":["q"]}""")
        assertNull(p.loadFollow()!!.second.single().ele)
        // a very long line (a 300 km track): still loads
        val big = buildString { append("{\"name\":\"long\",\"pts\":["); repeat(100_000) { append("46.5,11.2,") }; append("46.6,11.3]}") }
        File(dir, "follow.json").writeText(big)
        assertEquals(100_001, p.loadFollow()!!.second.size)
        // missing: nothing to restore, no report
        Problems.clear()
        File(dir, "follow.json").delete()
        assertNull(p.loadFollow())
        assertTrue(Problems.snapshot().isEmpty())
        p.saveFollow("x", null) // deleting what is not there is fine
        assertNull(p.loadFollow())
    }

    @Test fun brokenPreferenceValuesFallBackToDefaults() {
        val sp = MapPrefs(mutableMapOf<String, Any?>("poiHidden" to mutableSetOf("no-such-kind", "", "\u0000"), "voiceFix" to "\u0000garbage"))
        val p = prefs(sp)
        assertNotNull(p.settings.hiddenPoiKinds) // unknown names are simply unknown, never a throw
        assertNull(p.voiceFix)
        // "Recent" search places (one line per place): a cut or garbage line is skipped, never a throw
        for (l in listOf("", "\t\t\t", "\u0000garbage", "a\tb\tc\td\te\tf\tg\th\ti", "a\tb\tc\td\t46.5\t11.2\tx\th\tNOPE",
                         "a\tb\tc\td\t46.5\t11.2\t\th\tPOI\tt\tv\ts\textra"))
            com.onpath.app.search.SearchStore.decode(l)
        assertNull(com.onpath.app.search.SearchStore.decode("a\tb\tc\td\te\tf\tg\th\ti"))
        assertNotNull(com.onpath.app.search.SearchStore.decode("a\tb\tc\td\t46.5\t11.2\tx\th\tNOPE"))
    }
}
