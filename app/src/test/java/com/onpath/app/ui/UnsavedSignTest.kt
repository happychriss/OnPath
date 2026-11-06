package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-06 (onpath.private#41, B): the plan's saved state stands beside the blue mode label instead of a green dot
 * on ☰ – nothing while saved, an amber "Nicht gespeichert ›" while it has unsaved changes; a tap is Save route.
 */
class UnsavedSignTest {
    private val bar = File("src/main/java/com/onpath/app/ui/PlanTopBar.kt").readText()

    @Test fun theSignShowsOnlyForUnsavedChangesWithSaveAtHand() {
        assertTrue(PlanMenu(onSaveRoute = {}, unsaved = true).showUnsaved)
        assertFalse("saved", PlanMenu(onSaveRoute = {}, unsaved = false).showUnsaved)
        assertFalse("no plan (Save not offered)", PlanMenu(onSaveRoute = null, unsaved = true).showUnsaved)
    }

    @Test fun theDotOnTheMenuIsOnlyTheSyncWarning() {
        assertFalse(PlanMenu(onSaveRoute = {}, unsaved = true).dotWarn)
        assertTrue(bar.contains("val dot = if (menu.dotWarn) OnPathColors.Warn else null"))
        assertFalse("no green dot any more", bar.contains("OnPathColors.Green else null"))
    }

    @Test fun aTapOnTheSignIsSaveRouteWithA48dpTouchArea() {
        val sign = bar.substringAfter("private fun UnsavedSign(").substringBefore("\n}\n")
        assertTrue(sign.contains("heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onSave)"))
        assertTrue(bar.contains("UnsavedSign(menu.onSaveRoute!!, wordless = "))
        assertTrue(sign.contains("plan_unsaved_chip_desc"))
        assertTrue("large font: 💾 › only (the label line is full)", bar.contains("wordless = PlanTopBand.largeLabel(fontScale)"))
    }

    /** Owner 2026-11-06: the colour standard – plan blue in the plan pills' style, no new amber pair; the menu's Save sign. */
    @Test fun theSignIsInThePlanPillsBlue() {
        val sign = bar.substringAfter("private fun UnsavedSign(").substringBefore("\n}\n")
        assertTrue(sign.contains("background(PillBlueLight, CircleShape)"))
        assertTrue(sign.contains("Icons.Outlined.Save"))
        assertFalse(sign, sign.contains("WarnContainer") || sign.contains("OnPathColors.Warn"))
        val menu = bar.substringAfter("internal fun PlanMenuItems(").substringBefore("\n}\n")
        assertTrue("the same sign as ☰ → Plan speichern", menu.contains("MenuItem(Icons.Outlined.Save, str(Res.string.plan_save_route)"))
    }
}
