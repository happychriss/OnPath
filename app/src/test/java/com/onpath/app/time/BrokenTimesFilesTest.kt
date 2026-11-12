package com.onpath.app.time

import com.onpath.app.debug.Problems
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Safety net for the time files the app reads by itself: `tour-times.json` (tour summary), `plan-times.json`
 * (route list) and the trip state inside `trip-pace.json` (RecordingService.loadTrip → TripPace.fromJson).
 * Missing, empty, cut, garbage, wrong-typed or huge: never a throw, never a stale value, a warning in Problems.
 */
class BrokenTimesFilesTest {
    private fun dir() = Files.createTempDirectory("times-bad").toFile()

    @Before fun reset() { Problems.init(dir()); Problems.clear() }

    private val goodTour = """{"8":{"moving":10.0,"breaks":2.0,"planned":12.0,"needed":12.0,"start":1,"end":2}}"""
    private val goodPlan = """{"8":{"d":1000.0,"g":[1.0,2.0,3.0]}}"""

    private fun cases(good: String) = listOf(
        "empty" to "",
        "blank" to "\n",
        "garbage" to "\u0000ÿ{{garbage",
        "truncated" to good.take(good.length / 2),
        "an array at the top" to "[1,2]",
        "entry a string" to """{"8":"x"}""",
        "entry a number" to """{"8":5}""",
        "fields of the wrong type" to """{"8":{"moving":"x","breaks":[],"planned":"p","needed":{},"start":"s","end":"e","d":"x","g":"y"}}""",
        "groups too short / not numbers" to """{"8":{"d":1000.0,"g":["a","b","c"]},"9":{"d":1000.0,"g":[1]}}""",
        "very large" to buildString { append('{'); repeat(50_000) { append("\"$it\":{\"d\":1,\"g\":[1,1,1],\"moving\":1,\"breaks\":0},") }; append("\"8\":{\"d\":1000.0,\"g\":[1.0,2.0,3.0],\"moving\":10.0,\"breaks\":2.0}}") },
    )

    @Test fun tourTimesNeverThrowAndStillSaveAfterwards() {
        for ((name, text) in cases(goodTour)) {
            val f = File(dir(), TourTimesStore.FILE).apply { writeText(text) }
            val store = TourTimesStore(f)
            val got = store.get(8) // null or a value – but never a throw
            if (name != "very large") assertTrue(name, got == null || got.movingS >= 0.0)
            store.put(9, TourTimes(1.0, 2.0))
            assertEquals(name, TourTimes(1.0, 2.0), TourTimesStore(f).get(9))
        }
        assertNull(TourTimesStore(File(dir(), "missing.json")).get(8))
    }

    @Test fun planTimesNeverThrowAndStillSaveAfterwards() {
        for ((name, text) in cases(goodPlan)) {
            val f = File(dir(), PlanTimesStore.FILE).apply { writeText(text) }
            val store = PlanTimesStore(f)
            val got = store.get(8, 1000.0)
            if (got != null) assertTrue(name, got.all { it.isFinite() && it >= 0 })
            store.put(9, 500.0, DoubleArray(PaceModel.GROUPS) { 7.0 })
            assertEquals(name, 7.0, PlanTimesStore(f).get(9, 500.0)!![0], 0.0)
        }
        assertNull(PlanTimesStore(File(dir(), "missing.json")).get(8, 1000.0))
    }

    @Test fun brokenTimeFilesAreReportedNotHidden() {
        val f = File(dir(), TourTimesStore.FILE).apply { writeText("{garbage") }
        TourTimesStore(f).get(8)
        assertTrue(Problems.snapshot().any { it.area == "time" && it.message.contains("unreadable") })
    }

    @Test fun tripStateOfTheWrongTypeStartsFresh() {
        val bad = listOf(
            """{"prior":"x","a":"y","p":5,"m":"z","warmEnd":[],"active":{},"along":null,"breaks":"q"}""",
            """{"prior":[1,"x",null],"a":[1],"p":[1,2,3,4,5,6,7],"m":-5,"warmEnd":-1,"active":"NaN","along":"-Infinity"}""",
            """{"breaks":{"aLat":"x","aLon":null,"on":"yes","count":"n","closed":-5,"inS":"i","lS":[]}}""",
            """{"breaks":5,"prior":{}}""",
        )
        for (json in bad) {
            val t = TripPace.fromJson(JSONObject(json))
            assertTrue(json, t.movingS() >= 0.0)
            assertNotNull(TripPace.fromJson(JSONObject(json), Factors.ONE))
            assertTrue(json, BreakDetector.fromJson(JSONObject(json).optJSONObject("breaks")).breakS() >= 0.0)
        }
        val t = TourTimes.fromJson(JSONObject("""{"moving":"x","breaks":null,"planned":"p","needed":[],"start":"s","end":{}}"""))
        assertEquals(0.0, t.movingS, 0.0)
        assertEquals(0.0, t.breakS, 0.0)
    }
}
