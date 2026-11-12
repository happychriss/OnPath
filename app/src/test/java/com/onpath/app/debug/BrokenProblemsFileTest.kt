package com.onpath.app.debug

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Safety net: `problems.json` and the event log are read at every start (Problems.init). A missing, empty, cut,
 * garbage, wrong-typed or huge file never throws, the log keeps working and the file is written again valid.
 */
class BrokenProblemsFileTest {
    private fun dir() = Files.createTempDirectory("problems-bad").toFile()

    private val good = """{"problems":[{"level":"ERROR","area":"db","message":"boom","detail":null,"first":1,"last":2,"count":3}],""" +
        """"events":[{"time":5,"at":"x","area":"map","message":"hold"}]}"""

    private val cases: List<Pair<String, String>> = listOf(
        "empty" to "",
        "blank" to " \n\t",
        "garbage" to "\u0000ÿnot json at all",
        "truncated" to good.take(good.length / 2),
        "an array at the top" to "[1,2,3]",
        "problems a string" to """{"problems":"x","events":"y"}""",
        "entries are numbers" to """{"problems":[1,2],"events":[3]}""",
        "fields of the wrong type" to """{"problems":[{"level":5,"area":[],"message":{},"detail":1,"first":"a","last":null,"count":"b"}],""" +
            """"events":[{"time":"t","area":1,"message":[]}]}""",
        "very large" to buildString { append("{\"problems\":["); repeat(20_000) { append("{\"level\":\"WARN\",\"area\":\"a\",\"message\":\"m$it\",\"first\":1,\"last\":1,\"count\":1},") }; append("]}") },
    )

    @Test fun aBrokenProblemsFileStartsEmptyAndTheLogStillWorks() {
        for ((name, text) in cases) {
            val d = dir()
            File(d, "problems.json").writeText(text)
            Problems.init(d) // never throws
            Problems.warn("test", "after $name")
            assertTrue(name, Problems.snapshot().any { it.area == "test" && it.message == "after $name" })
            Problems.flushForTest()
            JSONObject(File(d, "problems.json").readText()) // written again, valid
        }
    }

    @Test fun aBrokenEventLogStartsEmptyAndTracesStillWork() {
        for ((name, text) in cases) {
            val d = dir()
            File(d, EventLog.CURRENT).writeText(text)
            File(d, EventLog.OLDER).writeBytes(ByteArray(3000) { 0xff.toByte() })
            Problems.init(d)
            Problems.trace("test", "after $name")
            assertTrue(name, Problems.events().any { it.message == "after $name" })
        }
    }

    @Test fun aMissingFolderDoesNotStopTheLog() {
        val d = File(dir(), "gone")
        d.deleteRecursively()
        Problems.init(d)
        Problems.warn("test", "no folder")
        assertTrue(Problems.snapshot().any { it.message == "no folder" })
    }
}
