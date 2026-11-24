package com.onpath.app.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tour suggestions read the network with the router's own parameters: RouterProfile.keyValues is a copy of
 * BRouterEngine's private keyValues (core routing is not touched for it). Same text, same snap distance – else a
 * suggestion's "allowed" could differ from what navigation accepts.
 */
class RouterProfileTest {
    private fun body(file: String, header: String): String {
        val text = File(file).readText()
        val start = text.indexOf(header)
        assertTrue("no '$header' in $file", start >= 0)
        val end = text.indexOf("\n        return kv\n", start)
        assertTrue("no end of keyValues in $file", end > start)
        return text.substring(text.indexOf('\n', start), end).lines().joinToString("\n") { it.trim() }.trim()
    }

    @Test fun keyValuesAreTheRoutersOwn() {
        val router = body("src/main/java/com/onpath/app/routing/BRouterEngine.kt",
            "private fun keyValues(activity: Activity, sacLimit: Int, official: Int = 0): MutableMap<String, String> {")
        val copy = body("../core/src/main/kotlin/com/onpath/app/suggest/RouterProfile.kt",
            "internal fun keyValues(activity: Activity, sacLimit: Int, official: Int = 0): MutableMap<String, String> {")
        assertEquals(router, copy)
        assertTrue(File("src/main/java/com/onpath/app/routing/BRouterEngine.kt").readText()
            .contains("private const val MAX_SNAP_M = 1000.0"))
    }
}
