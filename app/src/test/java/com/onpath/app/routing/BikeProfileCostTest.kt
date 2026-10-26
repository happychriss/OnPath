package com.onpath.app.routing

import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Way costs of the shipped bike profiles (trekking.brf; touring.brf = Touring bike + E-bike), evaluated per tag set
 * with BRouter's own expression engine – no routing, no map data. Pins the footway / surface rules of
 * docs/design/bike-routing.md (decisions 6 and 11).
 *
 * Field case (owner 2026-10-26, Hilden, Trekking bike): the shared foot / cycle path along the Itter
 * (highway=path, bicycle=designated, foot=designated, surface=compacted, sign DE:240) cost 5.15 per metre – more than
 * a primary road – because "compacted" counted as loose ground; the route zig-zagged through side streets even with
 * plan points on the path. Route check: tools/routing/jvm-test/ItterPathTest.kt.
 */
class BikeProfileCostTest {
    private val dir = File("src/main/assets/brouter")

    private fun ctx(profile: String): BExpressionContextWay {
        val meta = BExpressionMetaData()
        val way = BExpressionContextWay(meta)
        meta.readMetaData(File(dir, "lookups.dat"))
        way.parseFile(File(dir, profile), "global", HashMap())
        return way
    }

    /** Costfactor of the better direction for a way with these tags ("key=value"). */
    private fun cost(way: BExpressionContextWay, vararg tags: String): Float {
        val ld = way.createNewLookupData()
        for (t in tags) {
            val (k, v) = t.split('=', limit = 2)
            way.addLookupValue(k, v, ld)
        }
        val data = way.encode(ld)
        way.evaluate(false, data); val f = way.costfactor
        way.evaluate(true, data); val r = way.costfactor
        return minOf(f, r)
    }

    private val profiles = listOf("trekking.brf", "touring.brf")

    @Test fun compactedBikePathCostsLikeAPavedOne() {
        for (p in profiles) {
            val w = ctx(p)
            val paved = cost(w, "highway=path", "bicycle=designated", "foot=designated", "surface=asphalt")
            for (tags in listOf(
                arrayOf("highway=path", "bicycle=designated", "foot=designated", "surface=compacted", "segregated=no"),
                arrayOf("highway=path", "bicycle=yes", "surface=compacted"),
                arrayOf("highway=footway", "bicycle=designated", "surface=compacted"),
                arrayOf("highway=track", "bicycle=designated", "surface=compacted"),
            )) assertEquals("$p ${tags.joinToString(" ")}", paved, cost(w, *tags), 0.001f)
            // and cheaper than a residential street (1.25), as a cycleway is
            assertTrue(p, paved < cost(w, "highway=residential"))
        }
    }

    @Test fun looseSurfacesStayDearer() {
        for (p in profiles) {
            val w = ctx(p)
            val firm = cost(w, "highway=path", "bicycle=designated", "surface=compacted")
            for (s in listOf("gravel", "ground", "dirt", "grass", "sand"))
                assertTrue("$p surface=$s", cost(w, "highway=path", "bicycle=designated", "surface=$s") >= firm + 3f)
        }
    }

    @Test fun footwayWithoutBikeTagIsDearerButNotTheSurface() {
        for (p in profiles) {
            val w = ctx(p)
            val cycleway = cost(w, "highway=cycleway")
            val paved = cost(w, "highway=footway", "surface=asphalt")
            assertEquals("$p footway untagged surface", paved, cost(w, "highway=footway"), 0.001f)
            assertEquals("$p footway compacted", paved, cost(w, "highway=footway", "surface=compacted"), 0.001f)
            assertEquals("$p footway: +1 (pushing, shown in the plan)", cycleway + 1f, paved, 0.001f)
        }
    }

    /** Sidewalks, crossings and footways signed no cycling / dismount keep the full push penalty (+4). */
    @Test fun sidewalksAndNoCyclingFootwaysStayPushing() {
        for (p in profiles) {
            val w = ctx(p)
            val push = cost(w, "highway=cycleway") + 4f
            for (tags in listOf(
                arrayOf("highway=footway", "footway=sidewalk", "surface=paving_stones"),
                arrayOf("highway=footway", "footway=crossing"),
                arrayOf("highway=footway", "bicycle=no", "surface=asphalt"),
                arrayOf("highway=footway", "bicycle=no", "surface=compacted"),
                arrayOf("highway=footway", "bicycle=dismount", "surface=asphalt"),
                arrayOf("highway=footway", "bicycle=no"),
            )) assertTrue("$p ${tags.joinToString(" ")}", cost(w, *tags) >= push - 0.001f)
        }
    }
}
