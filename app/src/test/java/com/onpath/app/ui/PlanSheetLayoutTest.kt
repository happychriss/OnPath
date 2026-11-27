package com.onpath.app.ui

import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan sheet as the owner chose it (2026-11-05, R5): the activity as a button on the map, the plan row
 * "✕ Neu planen · ⇅ Umkehren · ↻ Rundtour", the facts, the profile and ONE green button – "Los geht's" / "Weiter geht's";
 * no Save in the sheet (☰ keeps it, ✕ offers it); the rolled-up row also during a tour.
 */
class PlanSheetLayoutTest {
    private fun ui(points: Int, running: Boolean) = PlanUi(points, null, false, null, canUndo = points > 0, canRedo = false,
        activity = Activity.HIKE, tourRunning = running, tour = if (running) TourState(1_000.0, 600, paused = false) else null)

    @Test fun theGreenButtonsWords() {
        assertEquals("Let’s go", goLabel(ui(2, running = false)))
        assertEquals("Carry on", goLabel(ui(2, running = true)))
        // TalkBack keeps the activity
        assertEquals("Carry on · Hike", primaryLabel(ui(2, running = true)))
    }

    /**
     * ONE full-width green button in the sheet's foot, in every step – also rolled up (owner 2026-11-07, A): "Los geht's"
     * from two points, during a tour "Weiter geht's" back to Navigate; a plain recording without a plan is ☰ → "Nur
     * aufzeichnen" (owner 2026-11-06: "Los geht's ohne Route" in the sheet was confusing).
     */
    @Test fun theFootCarriesTheOneGreenButton() {
        assertEquals(PlanFoot.Kind.NONE, PlanFoot.of(ui(0, running = false)))
        assertEquals(PlanFoot.Kind.NONE, PlanFoot.of(ui(1, running = false)))
        assertEquals(PlanFoot.Kind.GO, PlanFoot.of(ui(2, running = false)))
        assertEquals(PlanFoot.Kind.BACK, PlanFoot.of(ui(0, running = true)))
        assertEquals(PlanFoot.Kind.BACK, PlanFoot.of(ui(3, running = true)))
        assertEquals(56f, PlanFoot.HEIGHT.value)
    }

    /** The plan row keeps its words while they fit, else three signs (a large font) – a word is never cut. */
    @Test fun planRowWordsOrSigns() {
        assertTrue(PlanRow.wordsFit(190f, 358f))
        assertFalse(PlanRow.wordsFit(260f, 358f))
        // the facts on one line → the difficulty sits on the profile; else at the end of the facts
        assertTrue(PlanRow.factsOneLine(320f, 390f))
        assertFalse(PlanRow.factsOneLine(360f, 390f))
        // fewer pills leave more room for the words
        assertTrue(PlanRow.wordsFit(250f, 358f, count = 2))
    }

    /** A pill that cannot act is hidden, not greyed (owner 2026-11-27: „Was man nicht braucht, ist auch nicht sichtbar“). */
    @Test fun planRowShowsOnlyThePillsThatCanAct() {
        val all = listOf(PlanRow.Pill.CLEAR, PlanRow.Pill.REVERSE, PlanRow.Pill.LOOP)
        fun pills(n: Int, rev: Boolean = true, on: Boolean = false, canLoop: Boolean = true, days: Boolean = false) =
            PlanRow.pills(n, rev, on, canLoop, days)
        assertEquals(emptyList<PlanRow.Pill>(), pills(0))
        assertEquals("one point: only Neu planen", listOf(PlanRow.Pill.CLEAR), pills(1))
        assertEquals(all, pills(2))
        assertEquals("A → B → A: Umkehren would change nothing", listOf(PlanRow.Pill.CLEAR, PlanRow.Pill.LOOP), pills(3, rev = false))
        assertEquals("a multi-day tour: no Rundtour", listOf(PlanRow.Pill.CLEAR, PlanRow.Pill.REVERSE), pills(5, days = true))
        assertEquals("a round trip shows Rundtour on, to take the way back out", all, pills(3, on = true, canLoop = false))
        assertEquals(listOf(PlanRow.Pill.CLEAR, PlanRow.Pill.REVERSE), pills(3, canLoop = false))
    }

    /** The rolled-up row (owner 2026-11-07, A): centred facts in whole pieces – a large font wraps between them, never inside. */
    @Test fun rolledUpFactsWrapOnlyBetweenValues() {
        assertEquals("↑842\u00A0↓310", CollapsedFacts.elevation(842.4, 309.6))
        val p = CollapsedFacts.pieces(16_560.0, 12_500.0, 842.0, 310.0)
        assertEquals(3, p.size)
        // no ordinary space inside a piece: a line can only break between the pieces
        p.forEach { assertFalse(it, it.contains(' ')) }
        assertTrue(p[1], p[1].startsWith("\u00A0·\u00A0"))
        assertTrue(p[2], p[2].endsWith("↑842\u00A0↓310"))
    }

    /** The decision buttons stay on one row only while they fit. */
    @Test fun decisionButtonsOneRowOnlyWhenTheyFit() {
        assertTrue(DecisionLayout.oneRow(300f, 312f)); assertFalse(DecisionLayout.oneRow(330f, 312f))
    }
}
