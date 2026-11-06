package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 2026-11-06: a plan made on the phone was gone after the tour – "Plan auch speichern" was off. */
class EndPlanSaveTest {
    @Test fun aPlanNeverSavedIsSavedByDefault() = assertTrue(EndPlanSave.preset(offered = true, savedBefore = false))
    @Test fun changesToASavedPlanAreTheUsersChoice() = assertFalse(EndPlanSave.preset(offered = true, savedBefore = true))
    @Test fun nothingToSave() = assertFalse(EndPlanSave.preset(offered = false, savedBefore = false))

    /** The sheet opens with the preset (end tour, arrival) – not in the direct save without a sheet. */
    @Test fun theSheetsOpenWithThePreset() {
        val vm = java.io.File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("{ presetPlanSave(); endAsk = EndAsk.Plain }"))
        assertTrue(vm.contains("presetPlanSave(); arrivalAsk = true"))
        assertFalse(vm.contains("if (saveIfNoRides) endTour() else endAsk"))
    }
}
