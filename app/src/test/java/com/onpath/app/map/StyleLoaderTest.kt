package com.onpath.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StyleLoaderTest {
    @Test fun classicUsesPlainFileName() = assertEquals("onpath.json", StyleLoader.fileName("classic"))
    @Test fun otherThemesUseSuffix() = assertEquals("onpath-high-contrast.json", StyleLoader.fileName("high-contrast"))
    @Test fun themeFromFileName() {
        assertEquals("classic", StyleLoader.themeOf("onpath.json"))
        assertEquals("high-contrast", StyleLoader.themeOf("onpath-high-contrast.json"))
        assertNull(StyleLoader.themeOf("notes.txt"))
        assertNull(StyleLoader.themeOf("onpath-.json"))
    }
    @Test fun labelIsReadable() = assertEquals("High contrast", StyleLoader.label("high-contrast"))
}
