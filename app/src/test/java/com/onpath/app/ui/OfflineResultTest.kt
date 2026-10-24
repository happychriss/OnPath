package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-10-22 (screenshot): after Offline maps → Clean up a dark "37 map areas removed. OK" box sat on the map until
 * OK. The user confirmed the removal himself: the result is a plain line on the Offline maps page until it is left;
 * only a failure keeps a persistent message.
 */
class OfflineResultTest {
    private val mb = 1_048_576L

    @Test fun line() {
        assertEquals("37 areas removed · 450 MB freed", OfflineResult.removed(37, 450 * mb, afterTour = false))
        assertEquals("1 area removed · 4.5 MB freed · the map changes after the tour",
            OfflineResult.removed(1, (4.5 * mb).toLong(), afterTour = true))
        assertEquals("No map areas to remove", OfflineResult.removed(0, 0, afterTour = false))
    }

    /** No message box on the map for a successful Clean up / Clear all; the result line is cleared when the page is left. */
    @Test fun successGoesToThePageNotTheMap() {
        val src = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        fun body(fn: String): String {
            val start = src.indexOf("fun $fn(")
            assertTrue(fn, start >= 0)
            return src.substring(start, src.indexOf("\n    }\n", start))
        }
        for (fn in listOf("clearAllMaps", "confirmCleanup")) {
            val b = body(fn)
            assertTrue("$fn: no map message on success", "message =" !in b)
            assertTrue("$fn: result line", "offlineResult = OfflineResult.removed(" in b)
            assertTrue("$fn: failures stay visible (removeAreas)", "removeAreas(" in b)
        }
        assertTrue("a failure keeps a persistent message", "message = " in body("removeAreas"))
        assertTrue("leaving the page clears the line", Regex("""if \(v != SettingsPage.OFFLINE_MAPS\) offlineResult = null""").containsMatchIn(src))
    }
}
