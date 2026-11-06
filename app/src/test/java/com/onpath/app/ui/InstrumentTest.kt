package com.onpath.app.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Numbers like an instrument (owner 2026-11-06, polish 3): big digits, small units, equal-width digits. */
class InstrumentTest {
    private fun parts(s: String) = Instrument.runs(s).map { s.substring(it.start, it.end) to it.number }

    @Test fun durationSplitsIntoNumbersAndUnits() =
        assertEquals(listOf("4" to true, " h " to false, "36" to true, " min" to false), parts("4 h 36 min"))

    @Test fun aDecimalCommaOrPointStaysInTheNumber() {
        assertEquals(listOf("12,5" to true, " km" to false), parts("12,5 km"))
        assertEquals(listOf("12.5" to true, " km" to false), parts("12.5 km"))
    }

    @Test fun theShortNavigateTimeKeepsItsColon() =
        assertEquals(listOf("2:50" to true, " h" to false), parts("2:50 h"))

    @Test fun anArrowBelongsToItsNumber() =
        assertEquals(listOf("↑ 842" to true, " m  " to false, "↓ 310" to true, " m" to false), parts("↑ 842 m  ↓ 310 m"))

    @Test fun aSeparatorBetweenValuesIsNoNumber() =
        assertEquals(listOf("2,9" to true, " km · " to false, "↑ 82" to true, " m · " to false, "50" to true, " min" to false),
            parts("2,9 km · ↑ 82 m · 50 min"))

    @Test fun aWordWithoutDigitsIsAllUnit() = assertEquals(listOf("jetzt" to false), parts("jetzt"))

    @Test fun theRunsCoverTheTextWhole() {
        val s = "21:05 · 1 h 05 min"
        val r = Instrument.runs(s)
        assertEquals(0, r.first().start); assertEquals(s.length, r.last().end)
        r.zipWithNext().forEach { (x, y) -> assertEquals(x.end, y.start) }
    }

    @Test fun theUnitIsOneStepDownTheScale() {
        assertEquals(22f, Instrument.unitSize(30.sp).value)
        assertEquals(16f, Instrument.unitSize(22.sp).value)
        assertEquals(14f, Instrument.unitSize(16.sp).value)
        // nothing a hiker reads goes below 14
        assertEquals(14f, Instrument.unitSize(14.sp).value)
    }

    @Test fun theTextStaysTheSameAndDigitsGetEqualWidth() {
        val st = OnPathType.titleLarge
        val a = Instrument.styled("4 h 36 min", st)
        assertEquals("4 h 36 min", a.text)
        val num = a.spanStyles.filter { it.item.fontFeatureSettings == "tnum" }
        assertEquals(2, num.size)
        assertTrue(num.all { it.item.fontWeight == FontWeight.SemiBold })
        val unit = a.spanStyles.filter { it.item.fontSize == 16.sp }
        assertEquals(2, unit.size)
    }

    @Test fun aBoldDisplayStyleKeepsItsWeight() {
        val a = Instrument.styled("250 m", OnPathType.headlineMedium)
        assertEquals(FontWeight.Bold, a.spanStyles.first { it.item.fontFeatureSettings == "tnum" }.item.fontWeight)
    }
}
