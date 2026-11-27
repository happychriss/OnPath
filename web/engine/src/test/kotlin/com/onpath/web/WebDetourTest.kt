package com.onpath.web

import com.onpath.app.domain.PlanGaps
import com.onpath.app.routing.PlanDetour
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * The detour hint in the planner (the app's PlanDetourCheck, owner 2026-11-21 / 2026-11-24, his words): the engine checks
 * the last plan in small steps (detourStart → detourAt until it answers → detourGaps) and the red line reads as in the app.
 * The cases of tools/routing/jvm-test/DetourHintJvmTest.kt on E10_N45.rd5 (Meran): ONPATH_SEGMENTS, else the CI job's
 * $GITHUB_WORKSPACE/segments (web.yml downloads E10_N45 before this test), else ~/Projects/work/onpath-data/segments.
 */
class WebDetourTest {
    private val repo = File("../..").canonicalFile
    private val root = Files.createTempDirectory("webdetour").toFile()
    private val segments = File(System.getenv("ONPATH_SEGMENTS")
        ?: System.getenv("GITHUB_WORKSPACE")?.let { "$it/segments" }
        ?: (System.getProperty("user.home") + "/Projects/work/onpath-data/segments"))

    @After fun tidy() { root.deleteRecursively() }

    private fun setUp() {
        assumeTrue("no E10_N45.rd5 in $segments", File(segments, "E10_N45.rd5").isFile)
        Engine.init(root.path)
        File(root, "segments").mkdirs()
        Files.createSymbolicLink(File(root, "segments/E10_N45.rd5").toPath(), File(segments, "E10_N45.rd5").toPath())
        repo.resolve("app/src/main/assets/brouter").copyRecursively(File(root, "assets/brouter"))
    }

    private fun text(json: String): String? = Regex(""""text":"([^"]*)"""").find(json)?.groupValues?.get(1)

    /** Every step of point [i] as the page asks them; its answer and how many steps it took. */
    private fun point(i: Int): Pair<Boolean, Int> {
        var steps = 0
        while (true) {
            steps++
            when (val a = Engine.detourAt(i)) {
                "more" -> check(steps < 50) { "point $i never ends" }
                else -> return (a == "1") to steps
            }
        }
    }

    /** Plans [points] at T[sac] and runs the whole check as the page does; the points found and the gaps JSON or "null". */
    private fun check(points: String, sac: Int): Pair<List<Int>, String> {
        Engine.plan(points, "HIKE", sac)
        val idx = Regex("\\d+").findAll(Engine.detourStart()).map { it.value.toInt() }.toList()
        val found = idx.filter { point(it).first }
        return found to Engine.detourGaps("")
    }

    private val a = "46.68895,11.18446"; private val b = "46.70009,11.17894"; private val c = "46.69748,11.18970"

    @Test fun theLevelSendsThePointOnADetour() {
        setUp()
        // 7.3 km via ② at T1, 4.5 km at T2, the neighbours 1.4 km at both: ② macht 2,8 km Umweg
        val (found, json) = check("$a;$b;$c", 1)
        assertEquals(json, listOf(1), found)
        val t = text(json)!!
        assertTrue(json, t.startsWith("② macht") || t.startsWith("② adds"))
        val km = Regex("(\\d+[.,]\\d) km").find(t)!!.groupValues[1].replace(',', '.').toDouble()
        assertTrue("about 2.8 km: $t", abs(km - 2.8) <= 0.15)
        // a tap shows the drawn route around the point
        assertTrue(json, Regex(""""way":\[\[""").containsMatchIn(json))
    }

    @Test fun eachStepRoutesOnceAndTheCheckEndsTheSameAsFindOnTheWholePlan() {
        setUp()
        // a there-and-back over ② and ③ (5 points, 3 candidates): the step-wise check finds what PlanDetour.find finds on the
        // whole plan, point for point and metre for metre, and the line says "(+N more)" when there are more
        val pts = "$a;$b;$c;$b;$a"
        Engine.plan(pts, "HIKE", 1)
        val res = Engine.lastResultFor(Engine.parsePoints(pts))!!
        val whole = PlanDetour.find(Engine.engine(), res)
        val idx = Regex("\\d+").findAll(Engine.detourStart()).map { it.value.toInt() }.toList()
        assertTrue("several candidates: $idx", idx.size >= 2)
        val steps = idx.associateWith { point(it) }
        // one router request per step: a candidate routes the way to and from it one level up – a way another candidate
        // already routed comes from the memo (② and ③ share their neighbour b here; since the Standard „Markierten Wegen
        // folgen“ of 2026-11-27 one candidate needs a single step), so at least one step and at least one with 2+
        assertTrue("$steps", steps.values.all { it.second >= 1 } && steps.values.any { it.second >= 2 })
        val stepwise = steps.filterValues { it.first }.keys.sorted()
        assertEquals("whole ${whole.map { it.index to it.extraM }}", whole.map { it.index }, stepwise)
        val json = Engine.detourGaps("")
        if (whole.isEmpty()) { assertEquals("null", json); return }
        val line = text(json)!!
        assertEquals(PlanGaps.detourText(whole), line)
        if (whole.size > 1) assertTrue(line, line.contains("(+${whole.size - 1}"))
    }

    @Test fun aSummitOnPurposeSaysNothing() {
        setUp()
        // Vellau → Mutkopf → Leiteralm at T2: the way via the summit is long because it is a summit
        val (found, json) = check("46.69560,11.10935;46.71077,11.13787;46.70605,11.11125", 2)
        assertTrue(json, found.isEmpty())
        assertEquals("null", json)
    }

    @Test fun aPointOnlyAHarderLevelReachesNamesItsDetour() {
        setUp()
        // his Mutkopf plan at T1: ② is not reached, T2 reaches it – „Umweg 17,4 km notwendig, ② erst ab Wandern T2 erreichbar“
        val (found, json) = check("46.69560,11.10935;46.71077,11.13787;46.70605,11.11125", 1)
        assertEquals(json, listOf(1), found)
        val t = text(json)!!
        assertTrue(json, Regex("^(Umweg|\\d).* km .*② .*T2").containsMatchIn(t))
        // the way that reaches it, ending at the point itself (as without the detour)
        assertTrue(json, json.contains("[46.710770,11.137870]]"))
    }

    @Test fun aStepAfterANewerPlanAnswersNothing() {
        setUp()
        Engine.plan("$a;$b;$c", "HIKE", 1)
        assertEquals("[1]", Engine.detourStart())
        Engine.plan("$a;$c", "HIKE", 1) // the user edits meanwhile
        assertEquals("0", Engine.detourAt(1))
        assertEquals("null", Engine.detourGaps(""))
    }

    @Test fun onlyAHikeBelowT5IsChecked() {
        assertTrue(PlanDetour.applies(com.onpath.app.routing.Activity.HIKE, 4))
        assertTrue(!PlanDetour.applies(com.onpath.app.routing.Activity.HIKE, 5))
        assertTrue(PlanGaps.detourText(emptyList()) == null)
    }
}
