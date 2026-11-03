package com.onpath.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The empty plan (owner 2026-11-02): ✋ + "Wohin soll’s gehen?" bold + "Halte dein Ziel auf der Karte gedrückt – oder
 * such es oben in der Suchleiste." in one block, as high as the old line; the owner's words, EN + DE.
 */
class FirstPointHintTest {
    private val panel = File("src/main/java/com/onpath/app/ui/PlanPanel.kt").readText()
    private fun strings(dir: String) = File("../strings/src/commonMain/composeResources/$dir/strings_plan.xml").readText()

    @Test fun emptyPlanShowsTheHandAndTheQuestion() {
        // in the summary step the sheet opens at (MINI), not one step lower (owner 2026-11-02: hidden at the start)
        val body = panel.substringAfter("private fun ColumnScope.PlanBody(")
        val hintAt = body.indexOf("{ FirstPointHint() }")
        assertTrue("hint before the summary step's end", hintAt in 0 until body.indexOf("if (ui.tour == null) Spacer(with(sheet) { Modifier.stepEnd(PlanSize.MINI.step) })"))
        assertTrue("bigger: bodyLarge", panel.substringAfter("private fun FirstPointHint()").substringBefore("\n}\n").contains("typography.bodyLarge"))
        val hint = panel.substringAfter("private fun FirstPointHint()").substringBefore("\n}\n")
        assertTrue(hint, hint.contains("Icons.Filled.TouchApp") && hint.contains("FontWeight.Bold")
            && hint.contains("Res.string.plan_hint_first_point_question") && hint.contains("Res.string.plan_hint_first_point)"))
    }

    @Test fun theOwnersWords() {
        val de = strings("values-de")
        assertTrue(de.contains(">Wohin soll’s gehen?<"))
        assertTrue(de.contains(">Halte dein Ziel auf der Karte gedrückt – oder such es oben in der Suchleiste.<"))
        assertTrue(strings("values").contains(">Where to?<"))
    }
}
