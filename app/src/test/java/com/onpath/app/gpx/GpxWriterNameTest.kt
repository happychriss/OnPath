package com.onpath.app.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A plan and the ride along it, both exported: two files, never one overwriting the other (owner 2026-10-28). */
class GpxWriterNameTest {
    @Test fun planAndRideGetDifferentNames() {
        assertEquals("Hilden - planned.gpx", GpxWriter.fileName("Hilden", "planned"))
        assertEquals("Hilden - recorded.gpx", GpxWriter.fileName("Hilden", "recorded"))
        assertNotEquals(GpxWriter.fileName("Hilden", "planned"), GpxWriter.fileName("Hilden", "recorded"))
    }
    @Test fun importsKeepTheirName() = assertEquals("Komoot Tour.gpx", GpxWriter.fileName("Komoot Tour"))
    @Test fun oddCharactersAreReplaced() = assertEquals("Route 28 Oct_ 11_04 - geplant.gpx", GpxWriter.fileName("Route 28 Oct, 11:04", "geplant"))
}
