package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Review Arch 7: every request to brouter.de says who asks (app, version, contact). */
class BrouterUserAgentTest {
    @Test fun everyBrouterRequestSaysWhoAsks() {
        assertTrue(SegmentDownloader.USER_AGENT.startsWith("OnPath-Android/"))
        assertTrue(SegmentDownloader.USER_AGENT.contains("info@onpath.earth"))
        val seg = File("src/main/java/com/onpath/app/data/SegmentDownloader.kt").readText()
        val parts = File("src/main/java/com/onpath/app/data/Rd5PartStore.kt").readText()
        assertEquals(Regex("openConnection").findAll(seg).count(), Regex("\"User-Agent\", USER_AGENT").findAll(seg).count())
        assertTrue(parts.contains("\"User-Agent\", SegmentDownloader.USER_AGENT"))
    }
}
