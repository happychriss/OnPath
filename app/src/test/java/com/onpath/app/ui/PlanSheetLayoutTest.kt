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
     * Without a tour the green button is always there (flow map H1, 2026-11-06: with 0 or 1 point a tour could not be
     * started at all) – below two points it only records and says so; during a tour none here (C: "Navigation ›" in the foot).
     */
    @Test fun theButtonIsAlwaysThereWithoutATourAndNeverDuringOne() {
        assertTrue(PlanBarRow.shown(ui(0, running = false)))
        assertTrue(PlanBarRow.shown(ui(1, running = false)))
        assertTrue(PlanBarRow.shown(ui(2, running = false)))
        assertFalse(PlanBarRow.shown(ui(0, running = true)))
        assertFalse(PlanBarRow.shown(ui(3, running = true)))
        assertEquals(PlanStart.RECORD_ONLY, ui(1, running = false).start)
        assertEquals("Let’s go · no route", goLabel(ui(0, running = false)))
        assertEquals("Let’s go · no route", primaryLabel(ui(1, running = false)))
        // the rolled-up row: no button during a tour, else always
        assertTrue(panel.contains("if (ui.tour == null && !ui.tourRunning) CompactGo(ui, cb, on)"))
    }

    /** The plan row keeps its words while they fit, else three signs (a large font) – a word is never cut. */
    @Test fun planRowWordsOrSigns() {
        assertTrue(PlanRow.wordsFit(190f, 358f))
        assertFalse(PlanRow.wordsFit(260f, 358f))
        // the facts on one line → the difficulty sits on the profile; else at the end of the facts
        assertTrue(PlanRow.factsOneLine(320f, 390f))
        assertFalse(PlanRow.factsOneLine(360f, 390f))
        val head = panel.substringAfter("private fun PlanHead(").substringBefore("\n}\n")
        assertTrue(head, head.contains("if (!oneLine && !ui.routing) DifficultyBadge("))
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
        assertTrue(row, row.contains("if (ui.tour == null && !ui.tourRunning) CompactGo(ui, cb, on)"))
        assertFalse("no activity sign in the row (it is on the map)", row.contains("ui.activity.icon"))
    }

    /** No Save in the sheet; ✕ with unsaved changes asks Cancel · Discard · Save (Save's dialog, then the new plan). */
    @Test fun clearAsksToSaveAnUnsavedPlan() {
        assertFalse(Regex("onSave\\w*").containsMatchIn(panel))
        val vm = src("ui/AppViewModel.kt")
        assertTrue(vm.contains("fun requestClear() { if (ClearAsk.asks(planUnsaved, recState.value != null, waypoints.size)) discardAsk = true else clearPlan() }"))
        assertTrue(vm.contains("fun discardSaveFirst() { discardAsk = false; askSave { clearPlan() } }"))
        assertTrue(vm.contains("val canSaveBeforeClear: Boolean get() = RouteRules.saveInMenu(waypoints.size)"))
        val dialogs = src("ui/RouteDialogs.kt")
        assertTrue(dialogs.contains("onSave = if (vm.planUnsaved && vm.canSaveBeforeClear) vm::discardSaveFirst else null"))
        // M3 basic dialog (owner 2026-11-05): Verwerfen apart on the left, Abbrechen + filled Speichern on the right
        val dialog = dialogs.substringAfter("fun DiscardPlanDialog(").substringBefore("\n}\n")
        assertTrue(dialog, dialog.contains("destructive = str(Res.string.plan_discard) to onDiscard") &&
            dialog.contains("confirm = onSave?.let { str(Res.string.plan_bar_save) to it }"))
        assertTrue(DecisionLayout.oneRow(300f, 312f)); assertFalse(DecisionLayout.oneRow(330f, 312f))
    }

    /**
     * Core feature (owner 2026-11-05, field bug on the test build: Hike needed a confirm, the T level could not be chosen):
     * ONE tap chooses an activity and closes; Hike's T1–T5 chips choose Hike + that level in one tap and close.
     */
    @Test fun activityAndLevelInOneTap() {
        val dialog = src("ui/Screens.kt").substringAfter("fun ActivityDialog(").substringBefore("\n}\n")
        assertTrue(dialog, dialog.contains(".clickable { onSelect(a); onDismiss() }"))
        assertTrue(dialog, dialog.contains("if (a == Activity.HIKE) SacChips(if (sel) sac else 0) { level -> onSelect(Activity.HIKE); onSac(level); onDismiss() }"))
        assertFalse("no Done / confirm", dialog.contains("common_done"))
        val chips = src("ui/Screens.kt").substringAfter("private fun SacChips(").substringBefore("\n}\n")
        assertTrue(chips, chips.contains("SacScale.levels.forEach") && chips.contains("onClick = { onPick(l.level) }"))
        assertTrue(chips, chips.contains("Res.string.routes_sac_button_desc"))
    }

    /** The activity button on the map opens the activity picker (the same path as the old chip). */
    @Test fun theActivityButtonOpensThePicker() {
        val map = src("ui/MapScreen.kt")
        assertTrue(map.contains("if (vm.shown == null) ActivityMapButton(vm.activity, vm.sac, onActivity,"))
        assertTrue(map.contains("onActivity = { if (rec != null) vm.openActivityCheck() else showActivity = true }"))
        val button = panel.substringAfter("fun ActivityMapButton(").substringBefore("\n}\n")
        assertTrue(button, button.contains("Surface(onClick = onClick, shape = CircleShape"))
        assertTrue(button, button.contains("Res.string.plan_activity_chip_desc"))
        assertTrue(button, button.contains("modifier = Modifier.size(48.dp)"))
        // the sheet itself has no activity chip any more
        assertFalse(panel.contains("fun ActivityButton("))
    }
}
