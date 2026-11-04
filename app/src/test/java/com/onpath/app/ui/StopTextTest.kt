package com.onpath.app.ui

import com.onpath.app.navigation.NavPrompt
import com.onpath.app.navigation.NavText
import com.onpath.app.navigation.Rerouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-03: the map's numbered circle instead of the word "Stopp" in buttons and the turn card; a re-route that
 * would leave out a stop asks "Über ③" (primary) / "③ auslassen" – never "Yes" alone.
 */
class StopTextTest {
    @Test fun tokensBecomeCirclesAndTalkBackHearsTheWord() {
        val parts = StopTokens.parts("Über {3} und {g5} ab {s1}")
        assertEquals(listOf("Über ", StopTokens.Token(StopTokens.Kind.STOP, 3), " und ",
            StopTokens.Token(StopTokens.Kind.GOAL, 5), " ab ", StopTokens.Token(StopTokens.Kind.START, 1)), parts)
        assertEquals("Via stop 3", StopTokens.plain("Via {3}"))
        assertEquals("plain text stays plain", listOf("Neue Route"), StopTokens.parts("Neue Route"))
        assertFalse(StopTokens.has("Neue Route {x}"))
        assertTrue(StopTokens.has(StopTokens.stop(12)))
    }

    @Test fun aSkippingRerouteAlwaysAsksWithBothAnswers() {
        val q = NavPrompt.Question(1, 350.0, 600.0, 20.0, emptyList(), 900.0, 2_000.0, skipStop = 2, skipNumber = 7,
            via = NavPrompt.Question(1, 1_750.0, 600.0, 20.0, emptyList(), 2_300.0, 2_000.0))
        assertEquals("the map's number (after a plan edit), not the index", "Via {7}", NavText.viaButton(q))
        assertEquals("Skip {7}", NavText.skipButton(q))
        val nav = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
        val msg = nav.substringAfter("internal fun rerouteMessage(").substringBefore("\n}\n")
        assertTrue("via first-class, primary last", msg.contains("MessageButton(NavText.skipButton(q), onTake),\n            MessageButton(NavText.viaButton(q), onVia)"))
        assertTrue("a skip without a way via still says it on the button", msg.contains("q.skipStop != null -> listOf(MessageButton(NavText.KEEP, onKeep), MessageButton(NavText.skipButton(q), onTake))"))
        val rr = File("src/main/java/com/onpath/app/navigation/Rerouter.kt").readText()
        assertFalse("no silent skip left", rr.contains("StopSkip.Decision.SKIP -> return build(past, n)\n") || rr.contains("StopSkip.Decision.SKIP -> found(past, n)\n"))
        assertTrue(rr.contains("StopSkip.Decision.SKIP, StopSkip.Decision.ASK -> return build(past, n, ask = via?.let { build(it, null) })"))
        assertTrue(rr.contains("StopSkip.Decision.SKIP, StopSkip.Decision.ASK -> found(past, n, ask = found(r, null))"))
    }

    @Test fun theTurnCardShowsTheCircle() {
        assertEquals("{2} Leiteralm", StopAhead(2, "Leiteralm", goal = false, distanceM = 1200.0).label)
        assertEquals("{2}", StopAhead(2, null, goal = false, distanceM = 1200.0).label)
        assertEquals("Destination · Hütte", StopAhead(5, "Hütte", goal = true, distanceM = 650.0).label)
        val nav = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
        assertTrue(nav.substringAfter("private fun TurnCard(").substringBefore("\n}\n").contains("StopText(t.label"))
        // the circle only in buttons and the turn card – the box's buttons draw it
        assertTrue(File("src/main/java/com/onpath/app/ui/MessageCard.kt").readText().contains("StopText(b.label"))
        assertTrue(Rerouter::class.simpleName == "Rerouter")
    }
}
