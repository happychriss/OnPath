package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Field case 2026-10-26 (core feature C3): Köln Dom → Neumarkt found no route for any activity – the Dom point snapped
 * onto a footway inside the cathedral that connects to nothing. Which point BRouter put on such an "island" decides
 * which one the engine snaps again (real data: tools/routing/jvm-test/IslandTest.kt).
 */
class IslandPointTest {
    @Test fun destinationOnAnIsland() {
        assertEquals(1, BRouterEngine.islandPoint("target island detected for section 0", 0, 2))
        assertEquals(2, BRouterEngine.islandPoint("target island detected for section 1", 0, 3))
    }

    @Test fun startOnAnIslandAfterBRoutersOwnRetries() {
        assertEquals(0, BRouterEngine.islandPoint("no track found at pass=0", 5, 2))
        assertEquals(0, BRouterEngine.islandPoint("start island detected for section 0", 0, 2))
    }

    @Test fun otherFailuresAreNotIslands() {
        // no island retries: a real "no way" (e.g. forbidden ways only), not a snapping problem
        assertNull(BRouterEngine.islandPoint("no track found at pass=0", 0, 2))
        assertNull(BRouterEngine.islandPoint("no track found at pass=0", -1, 2))
        // which section of a multi-point route failed is unknown – the Planner routes leg by leg
        assertNull(BRouterEngine.islandPoint("no track found at pass=0", 5, 3))
        assertNull(BRouterEngine.islandPoint("operation killed by thread-priority-watchdog after 30 seconds", 5, 2))
        assertNull(BRouterEngine.islandPoint("from-position not mapped in existing datafile", 0, 2))
        assertNull(BRouterEngine.islandPoint("target island detected for section 4", 0, 3))
    }
}
