package com.onpath.app.ui

import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Owner 2026-10-26: buttons say "Navigation starten · MTB" / "… · E-MTB" in German; the picker keeps the full names. */
class ButtonActivityNameTest {
    private fun <T> inGerman(block: () -> T): T {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        return try { block() } finally { Locale.setDefault(before) }
    }

    @Test fun germanButtonsUseShortMtbNames() = inGerman {
        assertEquals("MTB", Activity.MTB.navName)
        assertEquals("E-MTB", Activity.EMTB.navName)
        assertEquals("Gravel-Rad", Activity.BIKE_TREKKING.navName)
        assertEquals("Mountainbike", Activity.MTB.label)
        assertEquals("E-Mountainbike", Activity.EMTB.label)
    }

    @Test fun englishButtonsUseTheLabel() {
        Activity.entries.forEach { assertEquals(it.label, it.navName) }
    }
}
