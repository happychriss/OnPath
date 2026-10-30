package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

/**
 * The note under every "Delete …?" (tour, track, route, a named entry) is one shared text: in German it must not use a
 * gendered pronoun ("Diese Tour löschen? Er bleibt …" – A-015), because "Tour" / "Route" are feminine, "Track" masculine.
 */
class RecycleBinNoteTest {
    private fun <T> inLocale(l: Locale, block: () -> T): T {
        val before = Locale.getDefault()
        Locale.setDefault(l)
        return try { block() } finally { Locale.setDefault(before) }
    }

    @Test fun germanNoteHasNoGenderedPronoun() = inLocale(Locale.GERMANY) {
        val note = RECYCLE_BIN_NOTE
        assertEquals("Kommt für 30 Tage in den Papierkorb.", note)
        assertFalse(note, note.substringBefore(' ') in setOf("Er", "Sie", "Es"))
    }

    @Test fun englishNoteIsUnchanged() = inLocale(Locale.ENGLISH) {
        assertEquals("It stays in the recycle bin for 30 days.", RECYCLE_BIN_NOTE)
    }
}
