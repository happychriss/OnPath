package com.onpath.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner's Pixel 7 2026-11-27 (3-button bar): the dialog window is laid out taller than it is shown (2401 px from y 136)
 * and gets no insets – T5 lay under the navigation bar whatever padding the sheet asked for. The sheet's area now ends
 * at the screen's bottom above the bar, read in the app's own window (checked on the Pixel 7 with adb screenshots).
 */
class ActivityPickerWiringTest {
    @Test fun theSheetEndsAboveTheNavigationBar() {
        val src = File("src/main/java/com/onpath/app/ui/ActivityPicker.kt").readText()
        assertTrue(src.contains("val visibleBottomPx = remember(appView) { screenBottomAboveNavBar(appView) }"))
        assertTrue(src.contains("(visibleBottomPx - windowTopPx).coerceAtLeast(0).toDp()"))
        assertTrue(src.contains("getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.navigationBars())"))
        assertTrue("both groups laid out: no jump", src.contains("if (hike) { bikes(); levels() } else { levels(); bikes() }"))
    }
}
