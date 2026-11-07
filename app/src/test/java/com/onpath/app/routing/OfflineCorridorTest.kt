package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Test

/** The offline corridor beside a route per activity (owner 2026-11-07, F-OFF-02): 10 km on foot, 20 km by bike. */
class OfflineCorridorTest {
    @Test fun hikeIsTenKilometres() {
        assertEquals(10_000.0, OfflineCorridor.marginM(Activity.HIKE), 0.0)
    }

    @Test fun everyBikeIsTwentyKilometres() {
        for (a in Activity.entries.filter { it != Activity.HIKE }) assertEquals(a.name, 20_000.0, OfflineCorridor.marginM(a), 0.0)
    }

    /** Robust first: an unknown activity gets the larger corridor – nothing a bike plan needs is missing or cleaned up. */
    @Test fun unknownActivityGetsTheLargest() {
        assertEquals(20_000.0, OfflineCorridor.marginM(null), 0.0)
        assertEquals(Activity.entries.maxOf { OfflineCorridor.marginM(it) }, OfflineCorridor.MAX_M, 0.0)
    }
}
