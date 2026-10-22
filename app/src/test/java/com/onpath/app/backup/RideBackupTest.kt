package com.onpath.app.backup

import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.rides.Ride
import com.onpath.app.rides.RideChoice
import com.onpath.app.rides.RideKind
import com.onpath.app.rides.RideRecord
import com.onpath.app.rides.TourStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CORE FEATURES C4 + C11: the backup file of a tour with a bus ride follows the choice made at End tour (other apps see
 * the tour as chosen) and still carries every recorded point (a restore gets them all back, with the choice).
 */
class RideBackupTest {
    private val t0 = 1_792_656_000_000L
    // 7 decimals: what GPX keeps
    private fun r7(v: Double) = Math.round(v * 1e7) / 1e7
    private val pts = (0..30).map { i -> TrackPoint(r7(46.6 + i * 0.002), r7(11.1 + (i % 3) * 0.0001), 600.0 + i, t0 + i * 60_000L) }
    private val segs = listOf(pts.subList(0, 15), pts.subList(15, 31))
    private val rides = listOf(Ride(8, 20, RideKind.VEHICLE))
    private fun meta(c: RideChoice?) = BackupMeta("u-1", TourKind.RECORDED, "Algund → Meran", t0, "HIKE", 3, 1000.0, 10.0, 5.0, 600.0,
        emptyList(), startMs = t0, endMs = t0 + 1_800_000L, savedMs = t0 + 2_000_000L,
        rides = c?.let { RideRecord(rides, it, TourStats(5000.0, 30.0, 0.0, 1800.0), t0 + 1_900_000L) })

    @Test fun everyChoiceRestoresEveryPointAndTheChoice() {
        for (c in RideChoice.values()) {
            val text = BackupCodec.encode(meta(c), segs)
            val e = BackupCodec.decode(text, "x.gpx")!!
            assertEquals("$c", pts, e.segments.flatten())
            assertEquals("$c", meta(c).rides, e.meta.rides)
        }
    }

    @Test fun saveOnlyUntilLeavesTheRideOutOfTheTrack() {
        val text = BackupCodec.encode(meta(RideChoice.UNTIL), segs)
        // what another app reads: only the hike until getting on
        assertEquals(pts.subList(0, 9), GpxParser.parse(text).allSegments.flatten())
        assertTrue(text.contains("<onpath:dropped>"))
    }

    @Test fun ridesNotCountedAreMarkedSegments() {
        val text = BackupCodec.encode(meta(RideChoice.NOT_COUNTED), segs)
        assertTrue(text.contains("<onpath:ride kind=\"VEHICLE\"/>"))
        assertFalse(text.contains("<onpath:dropped>"))
        assertEquals(pts, GpxParser.parse(text).allSegments.flatten())
    }

    @Test fun tourWithoutRidesIsWrittenAsBefore() {
        val text = BackupCodec.encode(meta(null), segs)
        assertFalse(text.contains("onpath:ride")); assertFalse(text.contains("onpath:dropped"))
        assertEquals(segs, BackupCodec.decode(text)!!.segments)
        assertNull(BackupCodec.decode(text)!!.meta.rides)
    }
}
