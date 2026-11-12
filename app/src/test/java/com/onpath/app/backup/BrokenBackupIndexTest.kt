package com.onpath.app.backup

import com.onpath.app.debug.Problems
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Safety net for `backup-index.json` (which backup file belongs to which tour; read at start). A missing, empty,
 * cut, garbage, wrong-typed or huge index never throws, is reported, never touches the backup files themselves,
 * and the next save writes the index again – a tour written twice at worst, never one lost.
 */
class BrokenBackupIndexTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("backup-index").toFile()
        Problems.init(dir); Problems.clear()
    }

    private val good = """{"u-1":{"store":"documents","handle":"h1","file":"2026-11-01 Alm.gpx","deleted":false}}"""

    private val cases = listOf(
        "empty" to "",
        "blank" to "\n",
        "garbage" to "\u0000ÿ{{garbage",
        "truncated" to good.take(good.length / 2),
        "an array at the top" to "[1]",
        "entry a string" to """{"u-1":"x"}""",
        "entry fields of the wrong type" to """{"u-1":{"store":1,"handle":2,"file":3,"deleted":"x"}}""",
        "entry without a handle" to """{"u-1":{"store":"documents"}}""",
        "a good entry then a broken one" to """{"u-1":{"store":"documents","handle":"h1","file":"a.gpx"},"u-2":5}""",
        "very large" to buildString { append('{'); repeat(30_000) { append("\"u-$it\":{\"store\":\"documents\",\"handle\":\"h$it\",\"file\":\"f$it.gpx\"},") }; append("\"last\":{\"store\":\"documents\",\"handle\":\"h\",\"file\":\"f.gpx\"}}") },
    )

    @Test fun aBrokenIndexStartsEmptyIsReportedAndIsWrittenAgain() {
        for ((name, text) in cases) {
            Problems.clear()
            val f = File(dir, "idx-${name.hashCode()}.json").apply { writeText(text) }
            val idx = BackupIndex(f) // never throws
            if (name != "very large") {
                assertNull(name, idx.get("u-1"))
                assertEquals(name, 0, idx.count("documents"))
                assertEquals(name, text, f.readText()) // reading never rewrites or deletes
                assertTrue(name, Problems.snapshot().any { it.area == "backup" && it.message.contains("unreadable") })
            } else {
                assertEquals(30_001, idx.count("documents"))
            }
            idx.put("u-9", BackupIndex.Entry("documents", "h9", "2026-11-02 Tour.gpx", false))
            JSONObject(f.readText()) // valid again
            assertEquals(name, "h9", BackupIndex(f).get("u-9")!!.handle)
        }
    }

    @Test fun aMissingIndexOrNoneAtAllIsFine() {
        val idx = BackupIndex(File(dir, "not-there.json"))
        assertNull(idx.get("u-1"))
        idx.put("u-1", BackupIndex.Entry("documents", "h", "f.gpx", false))
        assertEquals("h", BackupIndex(File(dir, "not-there.json")).get("u-1")!!.handle)
        val none = BackupIndex(null)
        none.put("u-1", BackupIndex.Entry("documents", "h", "f.gpx", false))
        assertEquals("h", none.get("u-1")!!.handle)
        assertTrue(Problems.snapshot().none { it.area == "backup" })
    }
}
