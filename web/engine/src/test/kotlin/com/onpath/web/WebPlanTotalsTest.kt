package com.onpath.web

import com.onpath.app.routing.DayLine
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.Planner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The plan panel's top line and the multi-day box count the same climb (field bug 2026-11-18, the Meraner Höhenweg as a
 * ring, Wandern T3, ~94 km – the B11–B14 tour of web/app/test/flows-shots.mjs): the top read ↑ 5891 m ↓ 5420 m (BRouter's
 * per-leg ascent, its 5 m filter on South Tyrol's fine elevation data), the days ↑ 5422 m. A real run on E10_N45.rd5 where
 * it is on this computer (ONPATH_SEGMENTS, else ~/Projects/work/onpath-data/segments); skipped otherwise.
 */
class WebPlanTotalsTest {
    private val repo = File("../..").canonicalFile
    private val root = Files.createTempDirectory("webtotals").toFile()
    private val segments = File(System.getenv("ONPATH_SEGMENTS") ?: (System.getProperty("user.home") + "/Projects/work/onpath-data/segments"))
    private val hoehenweg = "AQUDDPiLugSE8YcBogbZH94K8yedLO898Q7VHK0ZuUWwL99M6GK6N7AHsFyWPLJgDOyUAauSAdlUAAABEU1lcmFuZXIgSMO2aGVud2VnAhEAR2FzdGhvZiBIb2NobXV0aAIKAUxlaXRlcmFsbQINAkhvY2hnYW5naGF1cwILA0dpZ2dlbGJlcmcCCgRIb2NoZm9yY2gCCwVVbnRlcnN0ZWxsAg4GS2F0aGFyaW5hYmVyZwIHB0Vpc2hvZgIRCFN0ZXR0aW5lciBIw7x0dGUCCQlQZmVsZGVycwIGClVsZmFzAhELR2FzdGhvZiBIb2NobXV0aA"

    @After fun tidy() { root.deleteRecursively() }

    private fun num(json: String, key: String) = Regex("\"$key\":(-?[0-9.]+)").find(json)!!.groupValues[1].toDouble()

    @Test fun theTopLineAndTheDaysCountTheSameClimb() {
        assumeTrue("no E10_N45.rd5 in $segments", File(segments, "E10_N45.rd5").isFile)
        Engine.init(root.path)
        File(root, "segments").mkdirs()
        Files.createSymbolicLink(File(root, "segments/E10_N45.rd5").toPath(), File(segments, "E10_N45.rd5").toPath())
        repo.resolve("app/src/main/assets/brouter").copyRecursively(File(root, "assets/brouter"))
        val plan = PlanCode.decode(hoehenweg)!!
        val json = Engine.plan(plan.points.joinToString(";") { "${it.lat},${it.lon}" }, plan.activity.name, plan.sac)
        val line = Engine.lastPlanPoints(plan.points, plan.activity, plan.sac)!!
        val days = DayLine.of(line, plan.activity)
        val up = num(json, "ascentM"); val down = num(json, "descentM")
        assertEquals(json.take(200), days.upM.last(), up, 0.01)
        assertEquals(days.downM.last(), down, 0.01)
        assertEquals("a ring: up ≈ down", up, down, Planner.ELE_FILTER_M)
    }
}
