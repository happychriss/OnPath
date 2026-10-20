package com.onpath.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFixTest {
    @Test fun beforeTheFirstGpsFixAnyNetworkFixSeedsThePosition() = assertTrue(useNetworkFix(0L, 5_000, true, 2_000f))
    @Test fun freshGpsWins() = assertFalse(useNetworkFix(100_000, 110_000, true, 20f))
    @Test fun indoorsWifiStandsInForStaleGps() = assertTrue(useNetworkFix(100_000, 140_000, true, 40f))
    @Test fun cellGuessNeverOnceGpsWasSeen() = assertFalse(useNetworkFix(100_000, 400_000, true, 1_500f))
    @Test fun noAccuracyNoTrust() = assertFalse(useNetworkFix(100_000, 400_000, false, 0f))
}
