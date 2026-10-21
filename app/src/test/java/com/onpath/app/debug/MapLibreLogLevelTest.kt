package com.onpath.app.debug

import com.onpath.app.debug.MapLibreLogLevel.Priority
import com.onpath.app.debug.Problems.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MapLibreLogLevelTest {
    private fun lvl(p: Priority, msg: String, t: Boolean = false) = MapLibreLogLevel.of(p, "Mbgl", msg, t)

    @Test fun harmlessNotesAreInfo() {
        // the note the owner saw on the map (logged by MapLibre via Logger.e)
        assertEquals(Level.INFO, lvl(Priority.ERROR, "LocationComponent#paddingWhileTracking method call is ignored " +
            "because the camera mode is transitioning"))
        assertEquals(Level.INFO, lvl(Priority.ERROR, "LocationComponent#zoomWhileTracking method can only be used " +
            "when a camera mode other than CameraMode#NONE is engaged."))
        assertEquals(Level.INFO, lvl(Priority.ERROR, "Layer planned-route already exists"))
        assertEquals(Level.INFO, lvl(Priority.WARN, "{Worker}[Resource]: Request canceled"))
        assertEquals(Level.INFO, lvl(Priority.ERROR, "Unable to resolve host \"example.org\": no network"))
        assertEquals(Level.INFO, lvl(Priority.ERROR, "Failed to load tile: Canceled", t = true))
    }

    @Test fun otherWarningsAreWarn() {
        assertEquals(Level.WARN, lvl(Priority.WARN, "Something unusual in the style"))
        assertEquals(Level.WARN, lvl(Priority.WARN, "Failed to load style", t = true)) // a warning is never announced
    }

    @Test fun nonBreakingErrorsAreWarn() {
        assertEquals(Level.WARN, lvl(Priority.ERROR, "{Worker}[Style]: Invalid geometry in line layer"))
    }

    @Test fun breakingErrorsAreError() {
        assertEquals(Level.ERROR, lvl(Priority.ERROR, "[ParseStyle]: Failed to parse style: expected value"))
        assertEquals(Level.ERROR, lvl(Priority.ERROR, "Failed to load style: file not readable"))
        assertEquals(Level.ERROR, lvl(Priority.ERROR, "Unable to open database file onpath/base.pmtiles"))
        assertEquals(Level.ERROR, lvl(Priority.ERROR, "Glyph range 8192-8447 not found"))
        assertEquals(Level.ERROR, lvl(Priority.ERROR, "Native exception in map view", t = true))
    }

    /** An entry stored as ERROR by an older version, now reported as INFO, is not announced again in a new run. */
    @Test fun storedErrorReportedAsInfoIsNotAnnounced() {
        val dir = Files.createTempDirectory("mlll").toFile()
        Problems.init(dir)
        Problems.clear()
        val msg = "MapLibre (Mbgl-LocationComponent): paddingWhileTracking ignored"
        File(dir, "problems.json").writeText(
            """{"problems":[{"level":"ERROR","area":"map","message":"$msg","first":1,"last":1,"count":1}]}""")
        Problems.init(dir) // new run: nothing announced yet
        Problems.info("map", msg)
        assertNull(Problems.newSerious.value)
        assertEquals(2, Problems.snapshot().single().count) // still recorded – nothing silent
        Problems.clear()
    }
}
