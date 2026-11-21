package com.onpath.app.routing

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The day row's texts (owner 2026-11-21, variant A). */
class DayRowTextTest {
    private val saved = Locale.getDefault()
    @After fun back() = Locale.setDefault(saved)

    @Test fun englishAndGermanDayNames() {
        Locale.setDefault(Locale.ENGLISH); assertEquals("Day 1", DayRowText.day(1))
        Locale.setDefault(Locale.GERMANY); assertEquals("Tag 12", DayRowText.day(12))
    }

    @Test fun placesOnlyWhenBothAreKnown() {
        assertEquals("München – Bad Tölz", DayRowText.places("München", "Bad Tölz"))
        assertNull(DayRowText.places("München", null)); assertNull(DayRowText.places(null, "Brixen"))
        assertNull(DayRowText.places("", "Brixen")); assertNull(DayRowText.places("München", "  ")); assertNull(DayRowText.places(null, null))
    }
}
