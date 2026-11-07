package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * After Offline maps → Clean up no "37 map areas removed. OK" box on the map: the user confirmed the removal himself,
 * so the result is a plain line on the Offline maps page until it is left; only a failure keeps a persistent message.
 */
class OfflineResultTest {
    private val mb = 1_048_576L

    @Test fun line() {
        assertEquals("37 areas removed · 450 MB freed", OfflineResult.removed(37, 450 * mb, afterTour = false))
        assertEquals("1 area removed · 4.5 MB freed · the map changes after the tour",
            OfflineResult.removed(1, (4.5 * mb).toLong(), afterTour = true))
        assertEquals("No map areas to remove", OfflineResult.removed(0, 0, afterTour = false))
    }

}
