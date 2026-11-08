package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status card's tight rule (owner 2026-11-08, v7 – large fonts: "↓…" and "km…" are not acceptable): when the widest
 * left piece does not fit beside the arrival column, the arrival goes under the numbers and nothing is cut with "…".
 * Widths in dp as measured on a 1080 px / 420 dpi phone (card ≈ 379 dp wide).
 */
class CardFitTest {
    private val sides = CardFit.sides(activity = true, chevron = true)

    @Test fun theSidesAreTheActivityBoxThePaddingAndTheChevron() {
        assertEquals(66f + 8f + 48f, sides, 0.01f)
        assertEquals(16f + 8f, CardFit.sides(activity = false, chevron = false), 0.01f)
    }

    @Test fun normalFontFitsBesideTheArrival() {
        // "2:54 h  7,4 km" ~ 130 dp, "↑ 512 m  ↓ 310 m" ~ 135 dp, "in Bewegung 1:12" ~ 110 dp, "16:40" ~ 84 dp
        assertFalse(CardFit.tight(numbers = 130f, upDown = 135f, linePiece = 110f, arrival = 84f, sides = sides, width = 379f))
    }

    @Test fun aLargeFontIsTight() {
        // at 2.0 every width doubles: ↑/↓ alone (~ 270 dp) leaves no room for "16:40" (~ 168 dp) – before: "↓…"
        assertTrue(CardFit.tight(numbers = 260f, upDown = 270f, linePiece = 220f, arrival = 168f, sides = sides, width = 379f))
    }

    @Test fun anyOnePieceTooWideMakesItTight() {
        assertTrue("a long recording-line piece alone", CardFit.tight(100f, 100f, 200f, 84f, sides, 379f))
        assertTrue("long numbers alone", CardFit.tight(200f, 100f, 100f, 84f, sides, 379f))
        assertFalse("exactly fitting is not tight", CardFit.tight(165f, 100f, 100f, 84f, sides, 379f))
    }
}
