package com.onpath.app.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A plan and the ride along it, both exported: two files, never one overwriting the other (owner). */
class GpxWriterNameTest {
    @Test fun planAndRideGetDifferentNames() {
        val plan = GpxWriter.fileName("Hilden", GpxWriter.kindSuffix(planned = true, recorded = false))
        val rec = GpxWriter.fileName("Hilden", GpxWriter.kindSuffix(planned = false, recorded = true))
        assertEquals("Hilden - plan.gpx", plan)
        assertEquals("Hilden - rec.gpx", rec)
        assertNotEquals(plan, rec)
        assertEquals("imports: no suffix", null, GpxWriter.kindSuffix(planned = false, recorded = false))
    }
    @Test fun importsKeepTheirName() = assertEquals("Komoot Tour.gpx", GpxWriter.fileName("Komoot Tour"))
    @Test fun oddCharactersAreReplaced() = assertEquals("Route 28 Oct_ 11_04 - plan.gpx", GpxWriter.fileName("Route 28 Oct, 11:04", "plan"))
}
