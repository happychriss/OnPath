package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** „Markierten Wegen folgen“ (owner 2026-11-27): the BRouter parameters per activity and level, and the fingerprint. */
class MarkedTrailsTest {
    private fun kv(a: Activity, l: Int) = MarkedTrails.keyValues(a, l)

    @Test fun hikePrefersHikingRoutes() {
        assertEquals(mapOf("hiking_routes_preference" to "0.2"), kv(Activity.HIKE, MarkedTrails.LESS))
        assertEquals(mapOf("hiking_routes_preference" to "0.5"), kv(Activity.HIKE, MarkedTrails.STANDARD))
        assertEquals(mapOf("hiking_routes_preference" to "1.0"), kv(Activity.HIKE, MarkedTrails.MORE))
    }

    @Test fun mtbAndEmtbPreferCycleRoutes() {
        for (a in listOf(Activity.MTB, Activity.EMTB)) {
            assertEquals(listOf("0.2", "0.4", "1.0"), (0..2).map { kv(a, it)["cycleroutes_pref"] })
            assertEquals(1, kv(a, MarkedTrails.MORE).size)
        }
    }

    /** touring.brf / trekking.brf: a boolean, passed as a number (BRouter parses every key-value with Float.parseFloat). */
    @Test fun touringAndTrekkingStickToCycleRoutesOnlyAtMore() {
        for (a in listOf(Activity.BIKE_TOURING, Activity.EBIKE, Activity.BIKE_TREKKING)) {
            assertEquals(listOf("0", "0", "1"), (0..2).map { kv(a, it)["stick_to_cycleroutes"] })
        }
        Activity.entries.forEach { a -> (0..2).forEach { l -> kv(a, l).values.forEach { it.toFloat() } } }
    }

    @Test fun anUnknownLevelIsStandard() {
        assertEquals(kv(Activity.HIKE, MarkedTrails.STANDARD), kv(Activity.HIKE, 7))
        assertEquals(MarkedTrails.STANDARD, MarkedTrails.of(-1))
    }

    @Test fun theFingerprintChangesWithTheLevel() {
        val read: (String) -> ByteArray = { n -> "profile $n".encodeToByteArray() }
        val fps = (0..2).map { RoutingFingerprint.of(read, trails = it) }
        assertEquals("three levels, three fingerprints", 3, fps.toSet().size)
        assertEquals("the default is Standard", RoutingFingerprint.of(read), fps[MarkedTrails.STANDARD])
        assertNotEquals(fps[0], fps[2])
        assertEquals("a broken level fingerprints as Standard", fps[1], RoutingFingerprint.of(read, trails = 9))
    }
}
