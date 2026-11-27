package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The empty plan (owner 2026-11-02; 2026-11-06 the question is the title): "Wohin soll’s gehen?" as the sheet's title.
 * Owner 2026-11-27: the hints under it („Halte dein Ziel auf der Karte gedrückt …“ / „Halte die Karte gedrückt, um das
 * Ziel zu setzen.“) are gone, EN + DE.
 */
class FirstPointHintTest {
    private fun strings(dir: String) = File("../strings/src/commonMain/composeResources/$dir/strings_plan.xml").readText()

    @Test fun theOwnersWords() {
        val de = strings("values-de")
        assertTrue(de.contains(">Wohin soll’s gehen?<"))
        assertFalse(de.contains("Halte dein Ziel auf der Karte gedrückt"))
        assertFalse(de.contains("um das Ziel zu setzen"))
        assertTrue(strings("values").contains(">Where to?<"))
    }
}
