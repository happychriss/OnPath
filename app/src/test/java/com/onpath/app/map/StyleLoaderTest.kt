package com.onpath.app.map

import com.onpath.app.debug.Problems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    // developer test style (tools/dev/push_style.sh): only with developer tools, only when the file is there
    @Test fun testStyleOnlyWithDeveloperTools() {
        val dir = Files.createTempDirectory("onpath-style").toFile()
        try {
            assertNull("no file", StyleLoader.devStyleFile(dir, devTools = true))
            assertNull("no status without a file", StyleLoader.devStyleStatus(dir, devTools = true))
            val f = File(dir, StyleLoader.DEV_STYLE).apply { parentFile!!.mkdirs(); writeText("""{"version":8}""") }
            assertEquals(f, StyleLoader.devStyleFile(dir, devTools = true))
            assertNull("never for normal users", StyleLoader.devStyleFile(dir, devTools = false))
            assertNull("no status for normal users", StyleLoader.devStyleStatus(dir, devTools = false))
            assertTrue(StyleLoader.devStyleStatus(dir, devTools = true)!!.contains(StyleLoader.DEV_STYLE))
            assertTrue(StyleLoader.removeDevStyle(dir))
            assertFalse(f.exists())
            assertNull(StyleLoader.devStyleFile(dir, devTools = true))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun testStyleRejectedByTheMapIsSkippedUntilANewPush() {
        val dir = Files.createTempDirectory("onpath-style").toFile()
        try {
            val f = File(dir, StyleLoader.DEV_STYLE).apply { parentFile!!.mkdirs(); writeText("""{"version":8}""") }
            // a normal style failing is no test style failure: the caller falls back as before
            assertFalse(StyleLoader.noteFailure("other style broken"))
            assertEquals(f, StyleLoader.devStyleFile(dir, devTools = true))
            // the map rejects the test style: reported as a warning, skipped, the caller loads the normal style
            Problems.clear()
            StyleLoader.noteTestStyleLoadedForTest(f)
            assertTrue(StyleLoader.devStyleStatus(dir, devTools = true)!!.startsWith("Test style active"))
            assertTrue(StyleLoader.noteFailure("layer xyz: bad expression"))
            assertTrue(Problems.snapshot().any { it.message.contains("rejected") })
            assertNull("skipped until a new push", StyleLoader.devStyleFile(dir, devTools = true))
            assertTrue(StyleLoader.devStyleStatus(dir, devTools = true)!!.contains("not used"))
            // a new push (other content) is tried again
            f.writeText("""{"version":8,"name":"next try"}""")
            assertEquals(f, StyleLoader.devStyleFile(dir, devTools = true))
        } finally {
            dir.deleteRecursively()
        }
    }
}
