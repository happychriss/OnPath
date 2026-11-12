package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Full pages fade in and out quickly (owner 2026-11-12, "ganz schnell, aber smooth"). */
class PageMotionTest {
    @Test fun quickFadeWithAnimationsOn() {
        assertEquals(PageMotion.ENTER_MS to PageMotion.EXIT_MS, PageMotion.durations(1f))
        assertTrue(PageMotion.ENTER_MS <= 200 && PageMotion.EXIT_MS <= PageMotion.ENTER_MS)
    }

    @Test fun instantWithAnimationsOff() {
        assertEquals(0 to 0, PageMotion.durations(0f))
        assertEquals(0 to 0, PageMotion.durations(Float.NaN))
    }

    @Test fun everyFullPageFades() {
        val main = File("src/main/java/com/onpath/app/MainActivity.kt").readText()
        for (page in listOf("PageFade(vm.routesOpen)", "PageFadeOf(vm.recycleBin)", "PageFadeOf(detailsRoute)",
            "PageFade(vm.showSettings)", "PageFadeOf(vm.timeDetails)", "PageFade(vm.setupOpen)"))
            assertTrue(page, main.contains(page))
        assertTrue(File("src/main/java/com/onpath/app/ui/search/SearchHost.kt").readText().contains("PageFade(vm.searchOpen)"))
    }
}
