package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Core feature (owner 2026-11-05, R1): from Navigate "Plan" → the plan → the round "←" left of the search pill (TalkBack
 * "Zur Tour") back to Navigate; Android's Back does the same while a tour runs. The tour goes on, the plan's points stay.
 */
class ZurTourTest {
    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()

    @Test fun zurTourLeadsBackToNavigate() {
        val map = src("ui/MapScreen.kt")
        assertTrue(map.contains("onBackToTour = if (rec != null) ({ vm.continueNavigation() }) else null"))
        val bar = src("ui/PlanTopBar.kt")
        val top = bar.substringAfter("fun PlanTopBar(").substringBefore("\n}\n")
        assertTrue(top, top.contains("FilledTonalIconButton(onClick = onBackToTour!!") && top.contains("Icons.AutoMirrored.Filled.ArrowBack") &&
            top.contains("contentDescription = Txt.s(Res.string.plan_back_to_tour)"))
        // the "←" comes before the search pill in the one row
        assertTrue(top.indexOf("FilledTonalIconButton(") < top.indexOf("SearchBarPill(onSearch"))
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

    /** The band is one row, also during a tour; at a large font the mode label gets its own line (no overlap). */
    @Test fun bandHeights() {
        assertEquals(76f, PlanTopBand.bottom(fontScale = 1f).value)
        assertEquals(116f, PlanTopBand.bottom(fontScale = 2f).value)
        assertEquals(84f, PlanTopBand.labelStart(back = true).value)
        assertEquals(28f, PlanTopBand.labelStart(back = false).value)
    }
}
