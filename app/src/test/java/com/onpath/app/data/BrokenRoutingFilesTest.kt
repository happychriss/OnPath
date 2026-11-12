package com.onpath.app.data

import com.onpath.app.debug.Problems
import com.onpath.app.routing.Rd5PartsTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

/**
 * Safety net for the routing-data files the app reads by itself: a tile's parts list (`*.parts.json`, read before
 * every route) and the map package's `package.json` (the data check). Garbage, a cut file, the wrong type or a huge
 * file never throw; a broken parts list falls back to the rd5's own index and never deletes the rd5.
 */
class BrokenRoutingFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun reset() { Problems.init(tmp.newFolder("problems")); Problems.clear() }

    /** Never asked: the tests only read the local state. */
    private object NoRemote : Rd5Remote {
        override fun head(tile: String): Rd5Remote.Head = throw IllegalStateException("offline")
        override fun open(tile: String, from: Long, toExclusive: Long, etag: String?): InputStream = throw IllegalStateException("offline")
    }

    @Test fun aBrokenPartsListFallsBackToTheFilesOwnIndex() {
        val tile = "E10_N45.rd5"
        val (remoteFile, _, _) = Rd5PartsTest.fakeRemoteFile(tmp.newFolder(), Rd5PartsTest.sizes())
        val len = remoteFile.length()
        val good = """{"fileLength":$len,"parts":[1,6],"etag":"e","lastModified":1,"fetchedAt":2,"index":"QUJD","tail":"QUJD"}"""
        val cases = listOf(
            "empty" to "", "garbage" to "\u0000ÿ{{", "truncated" to good.take(good.length / 2), "array" to "[1]",
            "fileLength a word" to """{"fileLength":"x"}""",
            "parts a string" to """{"fileLength":$len,"parts":"x","fetchedAt":1,"index":"QUJD","tail":"QUJD"}""",
            "parts not numbers" to """{"fileLength":$len,"parts":["a"],"fetchedAt":1,"index":"QUJD","tail":"QUJD"}""",
            "index not base64" to """{"fileLength":$len,"parts":[1],"fetchedAt":1,"index":"@@@","tail":"!!"}""",
            "fields missing" to """{"fileLength":$len,"parts":[1]}""",
            "very large" to buildString { append("{\"fileLength\":$len,\"parts\":["); repeat(200_000) { append("1,") }; append("1]}") },
        )
        for ((name, text) in cases) {
            Problems.clear()
            val seg = tmp.newFolder()
            remoteFile.copyTo(File(seg, tile))
            File(seg, "E10_N45.parts.json").writeText(text)
            val store = Rd5PartStore(seg, NoRemote, now = { 5_000L })
            val s = store.state(tile) // never throws
            assertNotNull(name, s)
            assertTrue(name, s!!.parts.isNotEmpty()) // from the file's own index
            assertTrue(name, File(seg, tile).length() == len) // the rd5 is untouched
            assertTrue(name, Problems.snapshot().any { it.area == "routing" && it.message.contains("unreadable") })
        }
        // no list at all: a whole file (old download) counts as complete
        val seg = tmp.newFolder()
        remoteFile.copyTo(File(seg, tile))
        assertTrue(Rd5PartStore(seg, NoRemote).state(tile)!!.legacy)
        // a cut rd5 (shorter than its index): nothing on the phone, no throw
        File(seg, tile).writeBytes(ByteArray(10))
        assertNull(Rd5PartStore(seg, NoRemote).state(tile))
    }

    @Test fun packageJsonOfTheWrongTypeIsStillAPackageCheck() {
        val sizes = mapOf("base.pmtiles" to 10L)
        fun sizeOf(n: String) = sizes[n] ?: -1L
        val wrong = listOf(
            """{"files":"x","bbox":"y","region":5,"osm_date":7}""",
            """{"files":{"base.pmtiles":"x"},"bbox":["a",1],"region":[]}""",
            """{"files":[1],"bbox":{"a":1}}""",
            """{"files":{"base.pmtiles":null}}""",
            "{}",
        )
        for (json in wrong) {
            val pc = DataFiles.PackageCheck.parse(json, ::sizeOf) // never throws for an object
            assertNotNull(json, pc.area); assertNotNull(json, pc.region)
        }
        assertEquals(listOf("base.pmtiles"), DataFiles.PackageCheck.parse("""{"files":{"base.pmtiles":"x"}}""", ::sizeOf).mismatched)
        // not an object at all: parse throws by design – DataFiles.packageCheck catches and reports it, the check is skipped
        for (json in listOf("", "\u0000garbage", "[1]", "{\"files\":{")) {
            try { DataFiles.PackageCheck.parse(json, ::sizeOf) } catch (_: Exception) { continue } // expected: the guard in packageCheck
            throw AssertionError("parse must fail for '$json' so that packageCheck reports it")
        }
    }
}
