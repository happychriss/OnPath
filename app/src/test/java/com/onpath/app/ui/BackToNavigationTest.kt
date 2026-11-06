package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Core feature (owner 2026-11-06, C – replaces the round "←" of 2026-11-05, R1): during a tour the way from the plan back
 * to Navigate is a green full-width button "● 1,7 km · 0:14  ▲ Navigation ›" at the bottom, where "▶ Los geht's" is
 * without a tour – in every step of the plan sheet (its foot), under a shown tour and under a place card; Android's Back
 * does the same. The tour goes on, the plan's points stay, a paused recording goes on.
 */
class BackToNavigationTest {
    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()

    @Test fun theGreenButtonSitsInThePlanSheetsFootDuringATour() {
        val panel = src("ui/PlanPanel.kt")
        assertTrue(panel.contains("foot = if (ui.tour != null || ui.tourRunning) ({ BackToNavigationButton(ui.tour, ui.activity, { goClick(ui, cb) }) }) else null"))
        // the foot shows in every step (also rolled up): StepSheet places it with the navigation bar's padding
        val sheet = src("ui/StepSheet.kt")
        assertTrue(sheet.contains("if (foot != null) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), content = foot)"))
        // goClick during a tour: a paused recording goes on, then back to Navigate
        val go = panel.substringAfter("private fun goClick(").substringBefore("\n}\n")
        assertTrue(go, go.contains("if (tour != null) { if (tour.paused) cb.onPause(false); cb.onContinue() }"))
    }

    @Test fun noRoundArrowInTheBandAnyMore() {
        assertFalse(src("ui/MapScreen.kt").contains("onBackToTour"))
        val bar = src("ui/PlanTopBar.kt")
        assertFalse(bar.contains("onBackToTour") || bar.contains("ArrowBack"))
    }

    /** F1 (onpath.private#33): over a shown tour / a place card no status card with Weiter / Pause / Beenden – the button. */
    @Test fun underAShownTourAndAPlaceCardToo() {
        val map = src("ui/MapScreen.kt")
        assertFalse(map.contains("TourRunningBar("))
        assertTrue(map.contains("backToNavigation = rec?.let { r -> { BackToNavigationButton(tourStateOf(r, vm), vm.activity, { backToNavigation(vm, r) }) } }"))
        assertTrue(map.contains("com.onpath.app.ui.search.PlaceCardHost(vm)\n                    rec?.let { r ->\n                        BackToNavigationButton("))
        val back = map.substringAfter("private fun backToNavigation(").substringBefore("\n}\n")
        assertTrue(back, back.contains("if (r.paused) vm.pauseRecording(false)") && back.contains("vm.continueNavigation()"))
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
    }

    /** Green = it goes to the navigation (owner's colour rule): the same filled Button as "Los geht's", no own colour. */
    @Test fun sameFormAsLosGehts() {
        val b = src("ui/BackToNavigation.kt").substringAfter("fun BackToNavigationButton(").substringBefore("\n}\n")
        assertTrue(b, b.contains("Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)"))
        val go = src("ui/PlanPanel.kt").substringAfter("private fun GoButton(").substringBefore("\n}\n")
        assertTrue(go, go.contains("modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)"))
        assertFalse(b, b.contains("ButtonDefaults.buttonColors"))
    }

    /** The small numbers before "Navigation": gone at a large font, "Navigation ›" stays whole. */
    @Test fun numbers() {
        val running = TourState(1_700.0, 878, paused = false)
        val n = BackToNavigation.numbers(running, 1f)
        assertNotNull(n); assertTrue(n!!, n.contains("km") && n.contains("0:14"))
        assertNull(BackToNavigation.numbers(running, BackToNavigation.LARGE_FONT))
        assertNull(BackToNavigation.numbers(null, 1f))
        val paused = BackToNavigation.numbers(running.copy(paused = true), 1f)!!
        assertFalse(paused, paused.contains("0:14"))
        val free = BackToNavigation.numbers(running.copy(recordOnly = true), 1f)!!
        assertFalse(free, free.contains("0:14"))
    }

    /** The band is one row; at a large font the mode label gets its own line (no overlap). */
    @Test fun bandHeights() {
        assertEquals(76f, PlanTopBand.bottom(fontScale = 1f).value)
        assertEquals(116f, PlanTopBand.bottom(fontScale = 2f).value)
        assertEquals(28f, PlanTopBand.LABEL_START.value)
    }
}
