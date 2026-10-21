package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellJob
import com.onpath.app.cells.CellState
import com.onpath.app.cells.CellWhy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner rule "no self-dismissing messages": nothing appears and vanishes or changes faster than it can be read. */
class NoFlashTest {
    private fun gate() = NoFlash<String>(showDelayMs = 1000, minShowMs = 2500, stepKey = { it.substringBefore(' ') },
        urgent = { it.startsWith("Question") })

    @Test fun aFastStepIsNeverShown() {
        val g = gate()
        assertNull(g.update("Preparing", 0).shown)
        assertEquals(1000L, g.update("Preparing", 0).recheckAt)
        assertNull(g.update("Preparing", 400).shown)
        assertNull(g.update(null, 600).shown)
        assertNull(g.update(null, 5000).shown)
    }

    @Test fun aStepThatLastsShowsAndStaysReadable() {
        val g = gate()
        g.update("Downloading 1", 0)
        assertEquals("Downloading 1", g.update("Downloading 1", 1000).shown)
        // gone after 200 ms on screen: held until 2.5 s are over
        val held = g.update(null, 1200)
        assertEquals("Downloading 1", held.shown)
        assertEquals(3500L, held.recheckAt)
        assertNull(g.update(null, 3500).shown)
    }

    @Test fun updatesOfTheSameStepShowAtOnce() {
        val g = gate()
        g.update("Downloading 1 MB", 0); g.update("Downloading 1 MB", 1000)
        assertEquals("Downloading 5 MB", g.update("Downloading 5 MB", 1100).shown)
    }

    @Test fun aNewStepWaitsUntilTheShownOneWasReadable() {
        val g = gate()
        g.update("Preparing", 0); g.update("Preparing", 1000)
        assertEquals("Preparing", g.update("Downloading", 1300).shown)
        assertEquals("Downloading", g.update("Downloading", 3500).shown)
    }

    @Test fun whatWaitsForTheUserShowsAtOnce() {
        val g = gate()
        assertEquals("Question yes/no", g.update("Question yes/no", 0).shown)
    }

    @Test fun aTapAnswersAtOnce() {
        val g = gate()
        g.update("Question yes/no", 0)
        g.userActed(300)
        assertNull(g.update(null, 300).shown)
        // a tap long ago does not count
        g.update("Question again", 400)
        g.userActed(500)
        assertEquals("Question again", g.update(null, 2500).shown)
    }

    @Test fun nothingGoesByItselfWhileTheStateStays() {
        val g = gate()
        g.update("Map ready", 0); g.update("Map ready", 1000)
        assertEquals("Map ready", g.update("Map ready", 3_600_000).shown)
    }

    /** First start (field test 0.8.7): "Preparing…" for 300 ms, then "Downloading" – only "Downloading" appears. */
    @Test fun firstStartCardSkipsTheShortPreparingStep() {
        val own = CellId(543, 361)
        val job = CellJob(listOf(own), own, CellWhy.HERE)
        fun card(s: CellState) = CellCards.build(job, mapOf(own to s), emptyMap(), available = true, wifiOnly = false, mobileDeclined = false)!!
        val g = NoFlash(stepKey = CellCards::stepKey, urgent = CellCards::waitsForUser)
        assertNull(g.update(card(CellState.Requested), 0).shown)
        assertNull(g.update(card(CellState.Downloading(0, 9_000_000)), 300).shown)
        val shown = g.update(card(CellState.Downloading(3_000_000, 9_000_000)), 1300).shown
        assertTrue(shown!!.title, shown.title.startsWith("Downloading"))
        // done 0.5 s later: "Downloading" stays readable, then "Map ready"
        val done = card(CellState.Ready("v"))
        assertTrue(g.update(done, 1800).shown!!.title.startsWith("Downloading"))
        assertTrue(g.update(done, 1300 + NoFlash.MIN_SHOW_MS).shown is CellCardUi.Done)
    }

    @Test fun stepKeysOfTheCard() {
        val w1 = CellCardUi.Working("Downloading 1 of 3 · 2 MB", null, emptyList(), null, 0.1f, null, null)
        val w2 = w1.copy(title = "Downloading 2 of 3 · 9 MB", progress = 0.5f)
        val p = w1.copy(title = "Preparing map for this area", progress = null)
        assertEquals(CellCards.stepKey(w1), CellCards.stepKey(w2))
        assertTrue(CellCards.stepKey(w1) != CellCards.stepKey(p))
        assertTrue(CellCards.waitsForUser(w1.copy(question = MobileQuestion(2, null))))
        assertTrue(!CellCards.waitsForUser(w1))
        assertTrue(CellCards.waitsForUser(CellCardUi.Done(1, 1)))
    }
}
