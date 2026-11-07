package com.onpath.app.ui

import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plan sheet as the owner chose it (2026-11-05, R5): the activity as a button on the map, the plan row
 * "✕ Neu planen · ⇅ Umkehren · ↻ Rundtour", the facts, the profile and ONE green button – "Los geht's" / "Weiter geht's";
 * no Save in the sheet (☰ keeps it, ✕ offers it); the rolled-up row also during a tour.
 */
class PlanSheetLayoutTest {
    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()
    private val panel = src("ui/PlanPanel.kt")

    private fun ui(points: Int, running: Boolean) = PlanUi(points, null, false, null, canUndo = points > 0, canRedo = false,
        activity = Activity.HIKE, tourRunning = running, tour = if (running) TourState(1_000.0, 600, paused = false) else null)

    @Test fun theGreenButtonsWords() {
        assertEquals("Let’s go", goLabel(ui(2, running = false)))
        assertEquals("Carry on", goLabel(ui(2, running = true)))
        // TalkBack keeps the activity
        assertEquals("Carry on · Hike", primaryLabel(ui(2, running = true)))
        // ▶ in both states (owner: forward, not ‹)
        val icon = panel.substringAfter("private fun goIcon(").substringBefore("\n}\n")
        assertFalse(icon, icon.contains("KeyboardArrowLeft"))
        assertTrue(icon, icon.contains("Icons.Filled.PlayArrow"))
    }

    /**
     * From two points on; during a tour none here – the green "Navigation ›" in the foot leads back (C). A plain recording
     * without a plan is ☰ → "Nur aufzeichnen" (owner 2026-11-06: "Los geht's ohne Route" in the sheet was confusing).
     */
    @Test fun theButtonShowsFromTwoPointsAndNeverDuringATour() {
        assertFalse(PlanBarRow.shown(ui(0, running = false)))
        assertFalse(PlanBarRow.shown(ui(1, running = false)))
        assertTrue(PlanBarRow.shown(ui(2, running = false)))
        assertFalse(PlanBarRow.shown(ui(0, running = true)))
        assertFalse(PlanBarRow.shown(ui(3, running = true)))
        // the rolled-up row: no button below two points, none during a tour
        assertTrue(panel.contains("if (ui.pointCount >= 2 && ui.tour == null && !ui.tourRunning) CompactGo(ui, cb, on)"))
    }

    /** The plan row keeps its words while they fit, else three signs (a large font) – a word is never cut. */
    @Test fun planRowWordsOrSigns() {
        assertTrue(PlanRow.wordsFit(190f, 358f))
        assertFalse(PlanRow.wordsFit(260f, 358f))
        // the facts on one line → the difficulty sits on the profile; else at the end of the facts
        assertTrue(PlanRow.factsOneLine(320f, 390f))
        assertFalse(PlanRow.factsOneLine(360f, 390f))
        val head = panel.substringAfter("private fun PlanHead(").substringBefore("\n}\n")
        // the difficulty stays while a route is calculated (faded – PlanCalculatingTest)
        assertTrue(head, head.contains("if (!oneLine) DifficultyBadge("))
        assertTrue(panel.contains("if (factsOneLine(route)) Box {"))
    }

    /** The rolled-up row: ↑ ↓ go first, then the button moves under the time – never cut. */
    @Test fun rolledUpRowDropsTheElevationFirst() {
        assertEquals("↑842 ↓310", CollapsedFacts.elevation(842.4, 309.6))
        assertEquals(CollapsedFacts.Fit.ALL, CollapsedFacts.fit(150, 80, 110, 8, 358))
        assertEquals(CollapsedFacts.Fit.NO_ELEVATION, CollapsedFacts.fit(150, 120, 110, 8, 358))
        assertEquals(CollapsedFacts.Fit.WRAP, CollapsedFacts.fit(260, 120, 150, 8, 358))
        // during a tour too – without the button (owner 2026-11-05)
        val row = panel.substringAfter("private fun CollapsedRow(").substringBefore("\n}\n")
        assertTrue(row, row.contains("if (ui.pointCount >= 2 && ui.tour == null && !ui.tourRunning) CompactGo(ui, cb, on)"))
        assertFalse("no activity sign in the row (it is on the map)", row.contains("ui.activity.icon"))
    }

    /** No Save in the sheet; ✕ never asks (owner 2026-11-06) – a plan not saved goes under My routes → Zuletzt. */
    @Test fun clearNeverAsks() {
        assertFalse(Regex("onSave\\w*").containsMatchIn(panel))
        val vm = src("ui/AppViewModel.kt")
        assertTrue(vm.contains("fun requestClear() = clearPlan()"))
        assertFalse(vm.contains("discardSaveFirst") || vm.contains("canSaveBeforeClear") || vm.contains("discardAsk"))
        assertTrue(DecisionLayout.oneRow(300f, 312f)); assertFalse(DecisionLayout.oneRow(330f, 312f))
    }

}
