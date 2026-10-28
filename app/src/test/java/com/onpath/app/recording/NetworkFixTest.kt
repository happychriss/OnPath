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

    // weak GPS under a station roof (owner 2026-10-28): a clearly better Wi-Fi fix wins, without jumping back and forth
    @Test fun weakGpsGivesWayToMuchBetterWifi() = assertTrue(useNetworkFix(100_000, 101_000, true, 20f, lastGpsAccuracyM = 60f))
    @Test fun weakGpsKeptWhenWifiIsNotClearlyBetter() = assertFalse(useNetworkFix(100_000, 101_000, true, 40f, lastGpsAccuracyM = 60f))
    @Test fun goodGpsNeverGivesWay() = assertFalse(useNetworkFix(100_000, 101_000, true, 5f, lastGpsAccuracyM = 12f))
    @Test fun cellGuessNeverBeatsWeakGps() = assertFalse(useNetworkFix(100_000, 101_000, true, 400f, lastGpsAccuracyM = 900f))
    @Test fun weakGpsFixHiddenWhileBetterWifiIsCurrent() = assertFalse(showGpsFix(60f, 100_000, 20f, 105_000))
    @Test fun weakGpsFixShownAgainOnceWifiIsOld() = assertTrue(showGpsFix(60f, 100_000, 20f, 120_000))
    @Test fun goodGpsFixAlwaysShown() = assertTrue(showGpsFix(10f, 100_000, 5f, 101_000))
    @Test fun gpsWithoutAccuracyShown() = assertTrue(showGpsFix(null, 100_000, 5f, 101_000))
    @Test fun weakGpsShownWithoutAnyWifi() = assertTrue(showGpsFix(80f, 0L, 0f, 101_000))
}
