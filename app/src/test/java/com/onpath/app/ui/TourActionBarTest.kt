package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The tour's bottom bar (owner 2026-11-06): readable colours, light ones too (the locked look of the hold lock). */
class TourActionBarTest {
    private val bar = File("src/main/java/com/onpath/app/ui/TourActionBar.kt").readText()

    private fun contrast(a: Color, b: Color): Double {
        val (l1, l2) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
        return l1 / l2
    }

    @Test fun readableColours() {
        assertTrue(contrast(TourActions.OnGoTonal, TourActions.GoTonal) >= 7.0)
        assertTrue(contrast(TourActions.OnEditTonal, TourActions.EditTonal) >= 7.0)
        assertTrue(contrast(OnPathColors.CardWhite, OnPathColors.ActionEdit) >= 4.5)
        assertTrue(contrast(OnPathColors.ActionGo, OnPathColors.CardWhite) >= 4.5)
    }

    /** The plan sheet during a tour has no tile / pill bar (owner 2026-11-05, R5): one green "Weiter geht's". */
    @Test fun thePlanHasNoTourBar() {
        assertTrue(!bar.contains("fun TourEditBar(") && !bar.contains("fun ActionPills("))
    }

}
