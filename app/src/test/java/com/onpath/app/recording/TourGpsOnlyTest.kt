package com.onpath.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core features C4 / C5: a tour records and navigates on GPS only. [RecordingService.onFix] returns on [tracksFix]
 * before the off-route check, the time model and the database write – so a Wi-Fi / fused fix (indoors at the start,
 * ±100 m) is never a track point and never raises "off route".
 */
class TourGpsOnlyTest {
    @Test fun gpsFixesAreRecorded() = assertTrue(tracksFix("gps"))
    @Test fun networkFixesNeverBecomeTrackPoints() = assertFalse(tracksFix("network"))
    @Test fun fusedFixesNeverBecomeTrackPoints() = assertFalse(tracksFix("fused"))
    @Test fun passiveAndUnknownFixesNeverBecomeTrackPoints() {
        assertFalse(tracksFix("passive"))
        assertFalse(tracksFix(null))
    }
}
