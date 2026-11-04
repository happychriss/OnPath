package com.onpath.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-01: "Navigation Stopp" (restaurant) = the phone is quiet. Owner 2026-11-03: Plan stays quiet – walking
 * or riding on with the app in Plan never brings the guidance back by itself; only "Navigation fortsetzen" does.
 */
class StopQuietTest {
    @Test fun planIsQuietUntilContinue() {
        val q = StopQuiet()
        assertFalse(q.quiet)
        q.set(stopped = true)
        assertTrue(q.quiet)
        q.set(stopped = false)
        assertFalse(q.quiet)
    }

    @Test fun noWalkOffBackToNavigate() {
        val svc = File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
        assertFalse("no auto resume by walking off", svc.contains("stopQuiet.onFix") || svc.contains("stopQuiet.resumed"))
        assertFalse(svc.contains("guidanceBack"))
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        assertFalse("the app never switches Plan → Navigate by itself", vm.contains("guidanceBack"))
    }
}
