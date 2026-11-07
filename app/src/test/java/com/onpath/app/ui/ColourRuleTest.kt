package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.luminance
import java.io.File

/**
 * Owner 2026-11-06 ("alles blau, und was zur Navigation geht, grün"; onpath.private#36 "Wir nehmen erstmal Walnuss"): the
 * theme's primary – every button without a meaning of its own – is walnut; what goes to the navigation is green, what
 * plans is blue (docs/design/flow-map.md "Colour rule audit").
 */
class ColourRuleTest {
    private fun src(path: String) = File("src/main/java/com/onpath/app/$path").readText()

    @Test fun primaryIsWalnutAndTheTwoActionColoursStay() {
        assertEquals(Color(0xFF4A3F37), OnPathColors.Walnut)
        assertEquals(Color(0xFF245F46), OnPathColors.ActionGo)
        assertEquals(Color(0xFF335A85), OnPathColors.ActionEdit) // ink blue, owner 2026-11-06 variant C, lighter 2026-11-07
        val theme = src("ui/Theme.kt")
        assertTrue(theme.contains("primary = OnPathColors.Walnut,"))
        assertTrue(theme.contains("surfaceTint = OnPathColors.Walnut,"))
    }

    /** Every button that goes to the navigation names its green – it no longer comes from the theme. */
    @Test fun buttonsToTheNavigationAreGreen() {
        val panel = src("ui/PlanPanel.kt")
        assertTrue("Los geht's (the sheet's foot, every step)", Regex("Button\\(colors = ActionButtons\\.go\\(\\), onClick = \\{ goClick\\(ui, cb\\) \\}").findAll(panel).count() == 1)
        assertTrue(src("ui/BackToNavigation.kt").contains("Button(colors = ActionButtons.go(),"))
        assertTrue("Tour nachfahren", src("ui/Panels.kt").contains("Button(colors = ActionButtons.go(), onClick = onFollow"))
        // Navigate's bottom bar: Pause / Weiter green, Beenden green outlined – no red at the stop (#27)
        val bar = src("ui/TourActionBar.kt")
        assertTrue("Pause / Weiter", bar.contains("buttonColors(containerColor = OnPathColors.ActionGo"))
        assertTrue("Beenden green, no red (#27)", bar.contains("border = BorderStroke(1.5.dp, OnPathColors.ActionGo)"))
        assertTrue("the turn card", src("ui/NavigateScreen.kt").contains("Surface(shape = MaterialTheme.shapes.large, color = OnPathColors.ActionGo,"))
    }

    /** Plan actions are blue: the search card's add / start / goal, Save's name dialog, a draft's Speichern, Plan anyway. */
    @Test fun planActionsAreBlue() {
        val card = src("ui/search/PlaceCard.kt")
        assertTrue(card.contains("Button(colors = ActionButtons.edit(),"))
        assertEquals(2, Regex("OutlinedButton\\(colors = ActionButtons\\.editOutlined\\(\\)").findAll(card).count())
        val dialogs = src("ui/RouteDialogs.kt")
        assertTrue("Save", dialogs.contains("TextButton(colors = ActionButtons.editText(), onClick = { onSave(text.text) }"))
        assertTrue("Fix the route", dialogs.contains("TextButton(colors = ActionButtons.editText(), onClick = onCancel"))
        // "Replace current plan?" is gone (owner 2026-11-06, Zuletzt); a draft's Speichern in My routes is plan blue
        assertTrue("Zuletzt → Speichern", src("ui/Screens.kt").substringAfter("private fun DraftActions(").substringBefore("\n}\n")
            .contains("filledTonalButtonColors(containerColor = OnPathColors.EditTonal, contentColor = OnPathColors.OnEditTonal)"))
        assertTrue("the routing ring", src("ui/MapOverlays.kt").contains("val color = com.onpath.app.ui.OnPathColors.PlanAccent"))
    }

    /** A box's primary takes its meaning: Navigate questions green, plan questions blue, the rest walnut. */
    @Test fun messageBoxesColourTheirPrimaryByMeaning() {
        assertEquals(ButtonTone.GO, Message(MessageKind.FRAGE, "t", buttons = emptyList(), navigate = true).tone)
        assertEquals(ButtonTone.NEUTRAL, Message(MessageKind.FRAGE, "t", buttons = emptyList()).tone)
        val card = src("ui/MessageCard.kt")
        assertTrue(card.contains("ButtonTone.GO -> OnPathColors.ActionGo; ButtonTone.EDIT -> OnPathColors.ActionEdit; ButtonTone.NEUTRAL -> OnPathColors.Walnut"))
        val screen = src("ui/MapScreen.kt")
        assertTrue("Weiter warten = plan", screen.contains("), tone = ButtonTone.EDIT) // waiting for a plan to compute"))
    }

    /** Owner 2026-11-06: paper is the ground of every surface; white only for what floats over the map. */
    @Test fun paperForSurfacesWhiteOverTheMap() {
        val theme = java.io.File("src/main/java/com/onpath/app/ui/Theme.kt").readText()
        for (role in listOf("surface = OnPathColors.Paper", "surfaceBright = OnPathColors.Paper", "surfaceContainerLow = OnPathColors.Paper",
                "surfaceContainer = OnPathColors.Paper", "surfaceContainerHigh = OnPathColors.Paper"))
            org.junit.Assert.assertTrue(role, theme.contains(role))
        org.junit.Assert.assertTrue("text on paper ≥ 4.5:1", run { val (hi, lo) = listOf(OnPathColors.Ink.luminance(), OnPathColors.Paper.luminance()).sortedDescending(); (hi + 0.05) / (lo + 0.05) } >= 4.5)
        val ui = java.io.File("src/main/java/com/onpath/app/ui")
        for (f in listOf("SettingsScreen.kt", "Screens.kt", "BackupScreens.kt"))
            org.junit.Assert.assertFalse("$f: a white card on a page", java.io.File(ui, f).readText().contains("RoundedCornerShape(16.dp), color = Color.White"))
    }
}
