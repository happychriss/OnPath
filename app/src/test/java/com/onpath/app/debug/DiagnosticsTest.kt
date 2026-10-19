package com.onpath.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticsTest {
    @Test fun readsPmtilesHeader() {
        val h = Diagnostics.pmtilesHeader(File("src/test/resources/search/mini.pmtiles"))
        assertEquals(3, h.getInt("version"))
        assertTrue(h.getInt("maxZoom") >= h.getInt("minZoom"))
        assertTrue(h.getBoolean("complete"))
        assertEquals(4, h.getJSONArray("bounds").length())
    }

    @Test fun notAPmtilesFile() {
        val f = File.createTempFile("notpm", ".pmtiles").apply { writeBytes(ByteArray(200)) }
        assertTrue(Diagnostics.pmtilesHeader(f).has("error"))
    }
}
