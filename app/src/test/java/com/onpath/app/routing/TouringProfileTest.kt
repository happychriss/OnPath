package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * touring.brf (Touring bike, E-bike) is OnPath's copy of trekking.brf for a normal bike
 * (owner 2026-10-25, docs/design/bike-routing.md). It must stay a copy: a change to trekking.brf has to be
 * made in both, and only the values below may differ.
 */
class TouringProfileTest {
    private val dir = File("src/main/assets/brouter")

    /** Code lines only: comments and blank lines dropped (the header and notes may differ). */
    private fun code(name: String): List<String> = File(dir, name).readLines()
        .map { it.substringBefore('#').trimEnd() }
        .filter { it.isNotBlank() }

    @Test fun touringDiffersFromTrekkingOnlyInTheIntendedValues() {
        val trek = code("trekking.brf")
        val all = code("touring.brf")
        for (line in TOURING_ONLY) assertTrue("touring.brf has: $line", all.any { it.trim() == line })
        val tour = all.filter { it.trim() !in TOURING_ONLY }
        assertEquals("same number of code lines", trek.size, tour.size)
        val diff = trek.indices.filter { trek[it] != tour[it] }.map { trek[it].trim() to tour[it].trim() }
        assertEquals(EXPECTED, diff)
    }

    @Test fun touringAndEbikeRouteWithTouringBrf() {
        assertEquals("touring.brf", BRouterEngine.profileFile(Activity.BIKE_TOURING))
        assertEquals("touring.brf", BRouterEngine.profileFile(Activity.EBIKE))
        assertEquals("trekking.brf", BRouterEngine.profileFile(Activity.BIKE_TREKKING))
        for (a in Activity.values()) {
            val f = BRouterEngine.profileFile(a)
            assertTrue("$f shipped", f in BRouterEngine.PROFILE_ASSETS && File(dir, f).isFile)
        }
        // the road-bike profile is gone (owner 2026-10-25: exactly six activities, no road bike)
        assertFalse(File(dir, "fastbike-verylowtraffic.brf").exists())
    }

    companion object {
        /** trekking.brf line → touring.brf line, in file order. */
        val EXPECTED = listOf(
            "assign   consider_traffic         = false" to "assign   consider_traffic         = true",
            "assign unpavedPenalty = 1.0" to "assign unpavedPenalty = 2.5",
            // traffic penalty only on busy classes (upstream also +0.2 / +0.4 on quiet classes 1–3)
            "switch estimated_traffic_class=1|2    0.2" to "switch estimated_traffic_class=1|2    0",
            "switch estimated_traffic_class=3      0.4" to "switch estimated_traffic_class=3      0",
            "switch estimated_traffic_class=4      0.6" to "switch estimated_traffic_class=4      0.2",
            "switch estimated_traffic_class=5      0.8" to "switch estimated_traffic_class=5      0.4",
            "switch estimated_traffic_class=6|7    1 99 0" to "switch estimated_traffic_class=6|7    0.8 99 0",
            // v_rough (captain 2026-10-26): a rough track on a cycle route is not "perfect", the unpaved penalty applies
            "else if ( and is_ldcr not ldcr_busy ) then 1" to "else if ( and is_ldcr not or ldcr_busy ldcr_rough ) then 1",
        )

        /** Code lines only touring.brf has (left out before the line-by-line comparison). */
        val TOURING_ONLY = setOf(
            "assign ldcr_rough = or tracktype=grade3|grade4|grade5 or smoothness=bad|very_bad|horrible|very_horrible|" +
                "impassable surface=ground|dirt|sand|grass|mud|earth|rock",
        )
    }
}
