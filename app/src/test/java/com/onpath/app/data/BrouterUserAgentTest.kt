package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review Arch 7 / owner 2026-11-02 (onpath.private#5 A): every request to brouter.de says who asks –
 * `OnPath/<versionName> (+https://onpath.earth)`.
 */
class BrouterUserAgentTest {
    @Test fun theExactString() {
        assertEquals("OnPath/0.14.0 (+https://onpath.earth)", SegmentDownloader.userAgent("0.14.0"))
        assertEquals(SegmentDownloader.userAgent(com.onpath.app.BuildConfig.VERSION_NAME), SegmentDownloader.USER_AGENT)
    }

    @Test fun everyBrouterRequestSendsIt() {
        val seg = File("src/main/java/com/onpath/app/data/SegmentDownloader.kt").readText()
        val parts = File("src/main/java/com/onpath/app/data/Rd5PartStore.kt").readText()
        // SegmentDownloader: the resumable download + the HEAD of the refresh – each connection sets it
        assertEquals(2, Regex("openConnection").findAll(seg).count())
        assertEquals(2, Regex("\"User-Agent\", USER_AGENT").findAll(seg).count())
        // Rd5PartStore: the one connection factory of the 1° parts
        assertEquals(1, Regex("openConnection").findAll(parts).count())
        assertTrue(parts.contains("\"User-Agent\", SegmentDownloader.USER_AGENT"))
        // no other app file talks to brouter.de's segments directly
        val others = File("src/main/java/com/onpath/app").walk().filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "SegmentDownloader.kt" && it.readText().contains("brouter.de/brouter") }.map { it.name }.toList()
        assertEquals(emptyList<String>(), others)
    }
}
