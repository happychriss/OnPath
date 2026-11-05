package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Core feature (owner 2026-11-05): from Navigate "Plan" → the plan → "‹ Zur Tour" (top left in the light blue band) back
 * to Navigate; Android's Back does the same while a tour runs. The tour goes on, the plan's points stay.
 */
class ZurTourTest {
    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()

    @Test fun zurTourLeadsBackToNavigate() {
        val map = src("ui/MapScreen.kt")
        assertTrue(map.contains("onBackToTour = if (rec != null) ({ vm.continueNavigation() }) else null"))
        val bar = src("ui/PlanTopBar.kt")
        val back = bar.substringAfter("if (editing && onBackToTour != null) {").substringBefore("\n        return\n")
        assertTrue(back, back.contains("TextButton(onClick = onBackToTour") && back.contains("Icons.AutoMirrored.Filled.ArrowBack") &&
            back.contains("Res.string.plan_back_to_tour"))
        // continueNavigation enters Navigate and never starts a new recording
        val cont = src("ui/AppViewModel.kt").substringAfter("fun continueNavigation(force: Boolean = false) {").substringBefore("\n    }\n")
        assertTrue(cont, cont.contains("enter(AppMode.NAVIGATE)") && !cont.contains("startRecording"))
    }

    @Test fun backKeyDoesTheSameDuringATour() {
        val main = src("MainActivity.kt")
        val h = main.substringAfter("val touring = vm.recState.collectAsState().value != null").substringBefore("// Back in Plan closes")
        assertTrue(h, h.contains("BackHandler(enabled = touring && vm.mode == AppMode.PLAN") && h.contains("{ vm.continueNavigation() }"))
        // an open screen or overlay still closes first: they are excluded here, the overlay handler comes after this one
        listOf("!vm.routesOpen", "!vm.showSettings", "vm.shown == null", "!vm.mapOverlayOpen", "!vm.searchOpen").forEach {
            assertTrue(it, h.contains(it))
        }
        assertTrue(main.indexOf("val touring = vm.recState") < main.indexOf("Back in Plan closes a map overlay"))
    }

    /** The band grows by the back row during a tour; at a large font the mode label gets its own line (no overlap). */
    @Test fun bandHeights() {
        assertEquals(76f, PlanTopBand.bottom(backRow = false, fontScale = 1f).value)
        assertEquals(128f, PlanTopBand.bottom(backRow = true, fontScale = 1f).value)
        assertEquals(116f, PlanTopBand.bottom(backRow = false, fontScale = 2f).value)
        assertEquals(128f, PlanTopBand.bottom(backRow = true, fontScale = 2f).value)
    }
}
