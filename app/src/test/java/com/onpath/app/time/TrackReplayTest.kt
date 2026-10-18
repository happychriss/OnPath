package com.onpath.app.time

import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Replays every fixture in src/test/resources/tracks/ (planned line + recorded track, see [TripReplay]) and prints the
 * accuracy table of docs/plan-time-estimate.md §6. Until the owner's tours are added, the folder holds synthetic
 * walkers on the reference routes (generated below, deterministic; a changed generator rewrites them).
 */
class TrackReplayTest {

    private data class Synthetic(val folder: String, val route: String, val note: String, val walker: TripReplay.Walker)

    private val synthetic = listOf(
        Synthetic("synthetic-h5-strong-up-bad-knees", "H5", "Leiteralm round trip; climbs x0.8, descents x1.35, lunch 35 min at the hut",
            TripReplay.Walker(Factors(down = 1.35, flat = 1.0, up = 0.8), fatigue = 0.05, noise = 0.15, seed = 5,
                breaks = listOf(0.5 to 35.0, 0.8 to 6.0))),
        Synthetic("synthetic-h7-relaxed", "H7", "Vigiljoch round trip; relaxed walker x1.3 with fatigue, three breaks",
            TripReplay.Walker(Factors(1.3, 1.3, 1.3), fatigue = 0.10, noise = 0.15, seed = 7,
                breaks = listOf(0.3 to 10.0, 0.55 to 40.0, 0.85 to 5.0))),
        Synthetic("synthetic-h9-fit", "H9", "Ifinger summit; fit walker x0.8, one short break",
            TripReplay.Walker(Factors(0.8, 0.8, 0.8), fatigue = 0.0, noise = 0.15, seed = 9, breaks = listOf(0.45 to 15.0))),
    )

    private fun tracksDir(): File = listOf(File("src/test/resources/tracks"), File("app/src/test/resources/tracks"))
        .firstOrNull { it.parentFile.isDirectory } ?: File("src/test/resources/tracks")

    @Test fun syntheticFixturesAreUpToDate() {
        for (s in synthetic) {
            val ref = ReferenceRoutesTest.load(s.route)
            val dir = File(tracksDir(), s.folder).apply { mkdirs() }
            val files = mapOf(
                "plan.gpx" to TripReplay.gpx("${ref.id} ${ref.name}", listOf(ref.points.map { com.onpath.app.gpx.TrackPoint(it.lat, it.lon, it.ele) }), false),
                "track.gpx" to TripReplay.gpx("${s.folder} (synthetic)", listOf(TripReplay.walk(ref.points, ref.activity, s.walker)), true),
                "info.txt" to "activity=${ref.activity.name}\npace=NORMAL\nnote=${s.note}\nsynthetic=true\n",
            )
            for ((name, text) in files) {
                val f = File(dir, name)
                if (!f.isFile || f.readText() != text) { f.writeText(text); println("wrote $f") }
                assertEquals(text, f.readText())
            }
        }
    }

    @Test fun replayAllFixtures() {
        val folders = TripReplay.fixtures()
        assertTrue("no fixtures found", folders.isNotEmpty())
        println("== Replay of src/test/resources/tracks (to go: |shown − real remaining moving time| / real) ==")
        val results = folders.map { TripReplay.replay(it) }
        results.forEach { println(it.line()) }
        for (r in results) {
            // never far off (docs §6: p90 ≤ 25 %)
            for (i in 0 until 3) if (!r.errors[i].isNaN()) assertTrue("${r.name} at ${(i + 1) * 25} %: ${r.errors[i]}", r.errors[i] <= 0.30)
            // calm: docs §6 aims at p90 ≤ 2 min / max ≤ 10 min per 100 m; the rule allows 1 min + 2 % of a long day per update
            assertTrue("${r.name} jump p90 ${r.p90JumpMin}", r.p90JumpMin <= 5.0)
            assertTrue("${r.name} jump ${r.maxJumpMin}", r.maxJumpMin <= 15.0)
            assertTrue(r.movingS > 0)
        }
        // on average learning beats the plain model (the plan sheet's number from there)
        val learned = results.flatMap { it.errors.filter { e -> !e.isNaN() } }
        val static = results.flatMap { it.staticErrors.filter { e -> !e.isNaN() } }
        println("mean |error|: learned %.1f %% · plain model %.1f %%".format(learned.average() * 100, static.average() * 100))
        assertTrue(learned.average() < static.average())
        for (r in results.filter { it.name.startsWith("synthetic") }) assertTrue("${r.name} breaks ${r.breakS}", r.breakS >= 10 * 60)
    }

    @Test fun learnedFactorsGoTheRightWay() {
        val ref = ReferenceRoutesTest.load("H5")
        val w = TripReplay.Walker(Factors(down = 1.4, flat = 1.0, up = 0.8), 0.0, 0.1, seed = 11)
        val r = TripReplay.replay("h5", ref.points, listOf(TripReplay.walk(ref.points, Activity.HIKE, w)), Activity.HIKE, Factors.ONE)
        assertTrue(r.line(), r.learned.up < 0.92)
        assertTrue(r.line(), r.learned.down > 1.15)
    }
}
