package com.onpath.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    private val json = """
        {"tag_name":"v0.4.1","name":"OnPath 0.4.1","draft":false,"html_url":"https://github.com/happychriss/OnPath/releases/tag/v0.4.1",
         "body":"- Trail names\n- Update button",
         "assets":[{"name":"SHA256SUMS","browser_download_url":"https://x/SHA256SUMS","size":100},
                   {"name":"onpath-0.4.1.apk","browser_download_url":"https://x/onpath-0.4.1.apk","size":32000000}]}
    """.trimIndent()

    @Test fun parsesApkAsset() {
        val r = AppUpdater.parseRelease(json)!!
        assertEquals("0.4.1", r.version)
        assertEquals("https://x/onpath-0.4.1.apk", r.apkUrl)
        assertEquals(32000000L, r.apkSize)
        assertTrue(r.notes.startsWith("- Trail names"))
    }

    @Test fun releaseWithoutApkIsIgnored() =
        assertNull(AppUpdater.parseRelease("""{"tag_name":"v1.0.0","assets":[{"name":"notes.txt","browser_download_url":"u","size":1}]}"""))

    @Test fun draftIsIgnored() = assertNull(AppUpdater.parseRelease(json.replace("\"draft\":false", "\"draft\":true")))

    @Test fun versionComparison() {
        assertTrue(AppUpdater.isNewer("0.4.1", "0.4.0"))
        assertTrue(AppUpdater.isNewer("v0.10.0", "0.9.9"))
        assertTrue(AppUpdater.isNewer("1.0", "0.9.12"))
        assertFalse(AppUpdater.isNewer("0.4.0", "0.4.0"))
        assertFalse(AppUpdater.isNewer("0.3.9", "0.4.0"))
        assertFalse(AppUpdater.isNewer("0.4.0-beta", "0.4.0"))
    }

    @Test fun autoCheckOncePerDay() {
        val day = 24L * 3600 * 1000
        assertTrue(AppUpdater.dueForAutoCheck(0, day + 1))
        assertFalse(AppUpdater.dueForAutoCheck(day, day + 1000))
    }
}
