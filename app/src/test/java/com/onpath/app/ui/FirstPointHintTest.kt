package com.onpath.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The empty plan (owner 2026-11-02; 2026-11-06 the question is the title): "Wohin soll’s gehen?" as the sheet's title,
 * under it ✋ "Halte dein Ziel auf der Karte gedrückt – oder such es oben in der Suchleiste."; the owner's words, EN + DE.
 */
class FirstPointHintTest {
    private val panel = File("src/main/java/com/onpath/app/ui/PlanPanel.kt").readText()
    private fun strings(dir: String) = File("../strings/src/commonMain/composeResources/$dir/strings_plan.xml").readText()

    @Test fun emptyPlanShowsTheHandAndTheQuestion() {
        // in the summary step the sheet opens at (MINI), not one step lower (owner 2026-11-02: hidden at the start)
        val body = panel.substringAfter("private fun ColumnScope.PlanBody(")
        val hintAt = body.indexOf("else FirstPointHint()") // during a tour TourGoesOnHint() in its place (owner 2026-11-05)
        assertTrue("hint before the summary step's end", hintAt in 0 until body.indexOf("    Spacer(with(sheet) { Modifier.stepEnd(PlanSize.MINI.step) })"))
        assertTrue("bigger: bodyLarge", panel.substringAfter("private fun FirstPointHint()").substringBefore("\n}\n").contains("typography.bodyLarge"))
        val hint = panel.substringAfter("private fun FirstPointHint()").substringBefore("\n}\n")
        // owner 2026-11-06: the question is the sheet's title ("Wohin soll’s gehen?", EmptyPlanTitle); the hint keeps the way
        assertTrue(hint, hint.contains("Icons.Filled.TouchApp") && hint.contains("Res.string.plan_hint_first_point)")
            && !hint.contains("plan_hint_first_point_question"))
        assertTrue(panel.contains("Res.string.plan_hint_first_point_question") && panel.contains("str(EmptyPlanTitle.of(ui))"))
    }

    @Test fun theOwnersWords() {
        val de = strings("values-de")
        assertTrue(de.contains(">Wohin soll’s gehen?<"))
        assertTrue(de.contains(">Halte dein Ziel auf der Karte gedrückt – oder such es oben in der Suchleiste.<"))
        assertTrue(strings("values").contains(">Where to?<"))
    }
}
