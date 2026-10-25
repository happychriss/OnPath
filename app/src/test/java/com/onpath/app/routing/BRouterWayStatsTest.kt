package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BRouterWayStatsTest {
    private fun t(s: String) = BRouterWayStats.parseTags(s)
    private fun way(s: String) = BRouterWayStats.wayType(t(s))
    private fun surf(s: String) = BRouterWayStats.surface(t(s))

    /** BRouter message line: lon lat ele dist cost elev turn node init wayTags nodeTags time energy. */
    private fun msg(dist: Int, tags: String) = "11300000\t46600000\t300\t$dist\t1000\t0\t0\t0\t0\t$tags\t\t10\t100"

    @Test fun bikeAccessFromWayTags() {
        assertEquals("yes", BRouterWayStats.attrs(BRouterWayStats.parseTags("highway=footway bicycle=yes surface=paved")).bicycle)
        assertEquals("dismount", BRouterWayStats.attrs(BRouterWayStats.parseTags("highway=path bicycle=dismount")).bicycle)
        assertEquals(null, BRouterWayStats.attrs(BRouterWayStats.parseTags("highway=path sac_scale=hiking")).bicycle)
    }

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

    @Test fun perNodeMatchesRunsToNodes() {
        fun m(lon: Int, lat: Int, tags: String) = "$lon\t$lat\t300\t10\t1000\t0\t0\t0\t0\t$tags\t\t10\t100"
        val lon = intArrayOf(11000000, 11000100, 11000200, 11000300, 11000400)
        val lat = intArrayOf(46000000, 46000000, 46000000, 46000000, 46000000)
        val msgs = listOf(
            m(11000200, 46000000, "highway=track surface=gravel"),
            m(11000400, 46000000, "highway=path sac_scale=alpine_hiking trail_visibility=bad surface=rock mtb:scale=3+"),
        )
        val w = BRouterWayStats.perNode(msgs, lon, lat)
        assertEquals(5, w.size)
        assertEquals("track", w[0]!!.wayType); assertEquals("gravel", w[1]!!.surface)
        val p = w[2]!!
        assertEquals(4, p.sac); assertEquals(3, p.mtb); assertEquals("bad", p.visibility); assertEquals("rock", p.rough)
        assertEquals("alpine", p.surface)
        assertTrue(w[4] === w[3]) // the last node repeats the last way
        // a run that matches no node: nothing (times as without attributes)
        assertTrue(BRouterWayStats.perNode(listOf(m(1, 2, "highway=path")), lon, lat).isEmpty())
        assertTrue(BRouterWayStats.perNode(null, lon, lat).isEmpty())
    }

    @Test fun cycleRouteFromRouteTags() {
        for (n in listOf("icn", "ncn", "rcn", "lcn")) {
            assertTrue(n, BRouterWayStats.attrs(t("highway=footway route_bicycle_$n=yes")).cycleRoute)
        }
        assertFalse(BRouterWayStats.attrs(t("highway=footway route_bicycle_mtb=yes route_hiking_lwn=yes")).cycleRoute)
        assertFalse(BRouterWayStats.attrs(t("highway=footway")).cycleRoute)
    }

    /**
     * Field data 2026-10-25 (22 % of 296 planned test routes): BRouter can open the message list with a
     * zero-length run reported AT the first node. The matcher searched from the next node, found nothing and dropped
     * all way attributes – no "pushing" hint, times without surfaces on that whole route.
     */
    @Test fun perNodeSkipsAZeroLengthRunAtTheCurrentNode() {
        fun m(lon: Int, lat: Int, d: Int, tags: String) = "$lon\t$lat\t300\t$d\t1000\t0\t0\t0\t0\t$tags\t\t10\t100"
        val lon = intArrayOf(11000000, 11000100, 11000200, 11000300)
        val lat = intArrayOf(46000000, 46000000, 46000000, 46000000)
        val msgs = listOf(
            m(11000000, 46000000, 0, "highway=cycleway"), // 0 m at the start node itself
            m(11000200, 46000000, 15, "highway=footway"),
            m(11000300, 46000000, 8, "highway=residential"),
        )
        val w = BRouterWayStats.perNode(msgs, lon, lat)
        assertEquals(4, w.size)
        assertEquals("footway", w[0]!!.wayType); assertEquals("footway", w[1]!!.wayType)
        assertEquals("quiet_road", w[2]!!.wayType)
    }
}
