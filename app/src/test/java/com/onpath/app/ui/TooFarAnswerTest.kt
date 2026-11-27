package com.onpath.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-27 (Hilden → München by bike on the Pixel 7: two points 475 km apart, no line, only the overview map):
 * „Zu weit für das Handy“ has one OK – the far point is taken back and the map goes back to the plan / the position.
 */
class TooFarAnswerTest {
    @Test fun okTakesTheFarPointBack() {
        val ask = File("src/main/java/com/onpath/app/ui/LongLegAsk.kt").readText()
        assertTrue(ask.contains("val beyond = planRun.routeRun.longAskLeg?.beyond == true"))
        assertTrue(ask.contains("if (touring || beyond) planRun.perform(effects) // [PlanEditState.cancelRun]"))
        val dialog = File("src/main/java/com/onpath/app/ui/TourBar.kt").readText()
        assertTrue("one OK, no stop button", dialog.contains("if (shown.ask.beyond) TextButton(onClick = onCancel) { Text(str(Res.string.common_ok)) }"))
    }
}
