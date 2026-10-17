package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BRouterWayStatsTest {
    private fun t(s: String) = BRouterWayStats.parseTags(s)
    private fun way(s: String) = BRouterWayStats.wayType(t(s))
    private fun surf(s: String) = BRouterWayStats.surface(t(s))

    /** BRouter message line: lon lat ele dist cost elev turn node init wayTags nodeTags time energy. */
    private fun msg(dist: Int, tags: String) = "11300000\t46600000\t300\t$dist\t1000\t0\t0\t0\t0\t$tags\t\t10\t100"

    @Test fun wayTypes() {
        assertEquals("cycleway", way("highway=cycleway surface=asphalt"))
        assertEquals("cycleway", way("highway=path bicycle=designated"))
        assertEquals("path", way("highway=path sac_scale=hiking"))
        assertEquals("path", way("highway=bridleway"))
        assertEquals("footway", way("highway=footway"))
        assertEquals("footway", way("highway=pedestrian bicycle=designated"))
        assertEquals("track", way("highway=track tracktype=grade2"))
        assertEquals("quiet_road", way("highway=residential"))
        assertEquals("quiet_road", way("highway=tertiary estimated_traffic_class=2"))
        assertEquals("road", way("highway=tertiary estimated_traffic_class=5"))
        assertEquals("road", way("highway=secondary"))
        assertEquals("busy_road", way("highway=primary"))
        assertEquals("busy_road", way("highway=trunk_link"))
        assertEquals("steps", way("highway=steps"))
        assertEquals("ferry", way("route=ferry"))
        assertEquals("other", way(""))
        assertEquals("other", way("highway=platform"))
    }

    @Test fun surfaces() {
        assertEquals("asphalt", surf("highway=cycleway surface=asphalt"))
        assertEquals("paved", surf("highway=residential surface=paving_stones"))
        assertEquals("paved", surf("highway=residential surface=sett"))
        assertEquals("gravel", surf("highway=track surface=compacted"))
        assertEquals("ground", surf("highway=path surface=dirt"))
        assertEquals("unpaved", surf("highway=path surface=grass"))
        assertEquals("alpine", surf("highway=path sac_scale=alpine_hiking surface=ground"))
        assertEquals("alpine", surf("highway=path surface=rock"))
        assertEquals("natural_trail", surf("highway=path sac_scale=mountain_hiking"))
        assertEquals("gravel", surf("highway=track tracktype=grade2"))
        assertEquals("paved", surf("highway=track tracktype=grade1"))
        assertEquals("ground", surf("highway=track tracktype=grade4"))
        assertEquals("asphalt", surf("highway=secondary"))
        assertEquals("unknown", surf("highway=residential"))
        assertEquals("paved", surf("highway=footway footway=sidewalk"))
        assertEquals("unknown", surf("reversedirection=yes highway=track estimated_forest_class=2"))
        assertEquals("unknown", surf(""))
    }

    @Test fun breakdownIsScaledAndSorted() {
        val b = BRouterWayStats.fromMessages(
            listOf(
                msg(100, "highway=track tracktype=grade2"),
                msg(300, "highway=cycleway surface=asphalt"),
                msg(100, "highway=cycleway surface=asphalt lit=yes"),
                "garbage", null, msg(-5, "highway=path"),
            ),
            totalM = 1000.0,
        )
        assertEquals(listOf("cycleway", "track"), b.wayTypes.map { it.key })
        assertEquals(800.0, b.wayTypes[0].meters, 1e-6)
        assertEquals(200.0, b.wayTypes[1].meters, 1e-6)
        assertEquals(listOf("asphalt", "gravel"), b.surfaces.map { it.key })
        assertEquals(1000.0, b.surfaces.sumOf { it.meters }, 1e-6)
    }

    @Test fun emptyInputIsEmpty() {
        assertTrue(BRouterWayStats.fromMessages(null, 100.0).wayTypes.isEmpty())
        assertTrue(BRouterWayStats.fromMessages(listOf("a\tb"), 100.0).surfaces.isEmpty())
    }
}
