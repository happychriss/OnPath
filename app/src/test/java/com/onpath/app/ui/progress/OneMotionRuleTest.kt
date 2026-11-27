package com.onpath.app.ui.progress

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * „Nur eine Sache bewegt sich“ (owner 2026-11-27, captain: decoration off, live data on): the wait window registers itself
 * ([WaitMotion]), and every decorative busy sign asks [WaitMotion.othersMove] – the plan sheet's running bar, the ring at
 * the point being routed, the small spinners, the map-load pill. Live data (position, the direction arrow) is not asked.
 */
class OneMotionRuleTest {
    private fun src(p: String) = File("src/main/java/com/onpath/app/ui/$p").readText()

    @Test fun theWaitWindowRegistersItself() {
        val w = src("progress/WaitWindow.kt").substringAfter("fun WaitWindowDialog(")
        assertTrue(w.contains("WaitMotion.opened()") && w.contains("WaitMotion.closed()"))
    }

    @Test fun everyDecorativeBusySignAsks() {
        val asks = mapOf(
            "PlanPanel.kt" to listOf("if (ui.routing && com.onpath.app.ui.progress.WaitMotion.othersMove()) RoutingBar(",
                "if (spinner && com.onpath.app.ui.progress.WaitMotion.othersMove()) CircularProgressIndicator("),
            "MapOverlays.kt" to listOf("if (!com.onpath.app.ui.progress.WaitMotion.othersMove()) return"),
            "Panels.kt" to listOf("if (planBusy && com.onpath.app.ui.progress.WaitMotion.othersMove())"),
            "NotRoutedBar.kt" to listOf("if (busy && com.onpath.app.ui.progress.WaitMotion.othersMove())"),
            "suggest/SuggestSheet.kt" to listOf("if (com.onpath.app.ui.progress.WaitMotion.othersMove()) CircularProgressIndicator(",
                "if (taking && com.onpath.app.ui.progress.WaitMotion.othersMove())"),
            "MessageCard.kt" to listOf("if (b.busy && com.onpath.app.ui.progress.WaitMotion.othersMove())"),
            "progress/BesideSign.kt" to listOf("if (!WaitMotion.othersMove()) return"),
        )
        for ((file, lines) in asks) for (l in lines) assertTrue("$file: $l", src(file).contains(l))
    }
}
