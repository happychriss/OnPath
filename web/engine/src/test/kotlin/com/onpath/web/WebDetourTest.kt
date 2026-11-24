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
 * the last plan point by point (detourStart → detourAt → detourGaps) and the red line reads as in the app. The cases of
 * tools/routing/jvm-test/DetourHintJvmTest.kt on E10_N45.rd5 (Meran) where it is on this computer (ONPATH_SEGMENTS, else
 * ~/Projects/work/onpath-data/segments); skipped otherwise.
 */
class WebDetourTest {
    private val repo = File("../..").canonicalFile
    private val root = Files.createTempDirectory("webdetour").toFile()
    private val segments = File(System.getenv("ONPATH_SEGMENTS") ?: (System.getProperty("user.home") + "/Projects/work/onpath-data/segments"))

    @After fun tidy() { root.deleteRecursively() }

    private fun setUp() {
        assumeTrue("no E10_N45.rd5 in $segments", File(segments, "E10_N45.rd5").isFile)
        Engine.init(root.path)
        File(root, "segments").mkdirs()
        Files.createSymbolicLink(File(root, "segments/E10_N45.rd5").toPath(), File(segments, "E10_N45.rd5").toPath())
        repo.resolve("app/src/main/assets/brouter").copyRecursively(File(root, "assets/brouter"))
    }

    private fun text(json: String): String? = Regex(""""text":"([^"]*)"""").find(json)?.groupValues?.get(1)

    /** Plans [points] at T[sac] and runs the whole check as the page does; the gaps JSON or "null". */
    private fun check(points: String, sac: Int): Pair<List<Int>, String> {
        Engine.plan(points, "HIKE", sac)
        val idx = Regex("\\d+").findAll(Engine.detourStart()).map { it.value.toInt() }.toList()
        val found = idx.filter { Engine.detourAt(it) }
        return found to Engine.detourGaps("")
    }

    @Test fun theLevelSendsThePointOnADetour() {
        setUp()
        // 7.3 km via ② at T1, 4.5 km at T2, the neighbours 1.4 km at both: ② macht 2,8 km Umweg
        val (found, json) = check("46.68895,11.18446;46.70009,11.17894;46.69748,11.18970", 1)
        assertEquals(json, listOf(1), found)
        val t = text(json)!!
        assertTrue(json, t.startsWith("② macht") || t.startsWith("② adds"))
        val km = Regex("(\\d+[.,]\\d) km").find(t)!!.groupValues[1].replace(',', '.').toDouble()
        assertTrue("about 2.8 km: $t", abs(km - 2.8) <= 0.15)
        // a tap shows the drawn route around the point
        assertTrue(json, Regex(""""way":\[\[""").containsMatchIn(json))
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
        // his Mutkopf plan at T1: ② is not reached, T2 reaches it – „Umweg … km notwendig, ② erst ab Wandern T2 erreichbar“
        val (found, json) = check("46.69560,11.10935;46.71077,11.13787;46.70605,11.11125", 1)
        assertEquals(json, listOf(1), found)
        val t = text(json)!!
        assertTrue(json, Regex("^(Umweg|\\d).* km .*② .*T2").containsMatchIn(t))
        // the way that reaches it, ending at the point itself (as without the detour)
        assertTrue(json, json.contains("[46.710770,11.137870]]"))
    }

    @Test fun aStepAfterANewerPlanAnswersNothing() {
        setUp()
        Engine.plan("46.68895,11.18446;46.70009,11.17894;46.69748,11.18970", "HIKE", 1)
        assertEquals("[1]", Engine.detourStart())
        Engine.plan("46.68895,11.18446;46.69748,11.18970", "HIKE", 1) // the user edits meanwhile
        assertEquals(false, Engine.detourAt(1))
        assertEquals("null", Engine.detourGaps(""))
    }

    @Test fun onlyAHikeBelowT5IsChecked() {
        assertTrue(PlanDetour.applies(com.onpath.app.routing.Activity.HIKE, 4))
        assertTrue(!PlanDetour.applies(com.onpath.app.routing.Activity.HIKE, 5))
        assertTrue(PlanGaps.detourText(emptyList()) == null)
    }
}
