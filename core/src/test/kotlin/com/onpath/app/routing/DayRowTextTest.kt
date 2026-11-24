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

    @Test fun aHotelAtTheOnlyKnownEndShowsWhereTheDayStartsOrEnds() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("ab Gasthof Krone", DayRowText.places("Gasthof Krone", null, fromIsHotel = true))
        assertEquals("bis Hotel Post", DayRowText.places(" ", "Hotel Post", toIsHotel = true))
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("from Gasthof Krone", DayRowText.places("Gasthof Krone", null, fromIsHotel = true))
        assertEquals("to Hotel Post", DayRowText.places(null, "Hotel Post", toIsHotel = true))
    }

    @Test fun theHotelRuleChangesNothingElse() {
        assertEquals("both known: von – nach, hotel or not", "Krone – Post", DayRowText.places("Krone", "Post", fromIsHotel = true, toIsHotel = true))
        assertNull("the known end is no hotel, the other end is", DayRowText.places("München", null, toIsHotel = true))
        assertNull("the known end is no hotel, the other end is", DayRowText.places(null, "Brixen", fromIsHotel = true))
        assertNull("a hotel without a name", DayRowText.places("  ", null, fromIsHotel = true, toIsHotel = true))
    }
}
