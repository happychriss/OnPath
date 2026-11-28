package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-28 10:52 (docs/team/items/2026-11-28-long-route-question.md): a long route either fits – „Dauert etwa …“
 * [Abbrechen] [Berechnen] – or does not („Zu weit für das Handy“, OK). No „Trotzdem planen“ anywhere in the app: not in the
 * long-route question (also its fallback without a leg), not on the not-routed line. (The planner's own words are HP's.)
 */
class NoPlanAnywayTest {
    private fun src(p: String) = File("src/main/java/com/onpath/app/ui/$p").readText()

    @Test fun noTrotzdemPlanenInTheAppsLongRouteQuestion() {
        val dialog = src("TourBar.kt").substringAfter("fun LongRouteDialog(").substringBefore("\n}\n")
        assertFalse("no „Trotzdem planen“ in the question", dialog.contains("plan_anyway"))
        assertEquals("both forms: Berechnen", 2, Regex("Res\\.string\\.plan_calculate").findAll(dialog).count())
    }

    @Test fun noTrotzdemPlanenOnTheNotRoutedLine() {
        assertFalse(src("NotRoutedBar.kt").contains("plan_anyway"))
        // the line's button for a long plan is „Berechnen“; PlanStatus.notRoutedAction (shared) is not asked for LONG here
        assert(src("NotRoutedBar.kt").contains("if (why == NotRouted.LONG) str(Res.string.plan_calculate) else PlanStatus.notRoutedAction(why)"))
    }
}
