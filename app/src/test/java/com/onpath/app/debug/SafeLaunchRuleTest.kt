package com.onpath.app.debug

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Architecture rule (like SharedCoreRuleTest): no bare `viewModelScope.launch` in the app – a failure there used to
 * end the app after the crash log (audit 2026-11-07). Use [launchSafe] (own words in the problem log) or a scope made
 * with [reported].
 */
class SafeLaunchRuleTest {
    @Test fun noBareViewModelScopeLaunch() {
        val root = File("src/main/java/com/onpath/app")
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if ("viewModelScope.launch(" in code || "viewModelScope.launch {" in code) "${f.relativeTo(root)}:${i + 1}" else null
            }
        }.toList()
        assertEquals("use launchSafe or a reported scope: $hits", emptyList<String>(), hits)
    }
}
