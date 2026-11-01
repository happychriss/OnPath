package com.onpath.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Review Arch 12: "open with" never makes OnPath read its own private files. */
class IncomingUriTest {
    private val private = listOf(File("/data/user/0/com.onpath.app"), File("/storage/emulated/0/Android/data/com.onpath.app"))

    @Test fun ownFilesAreRefused() {
        assertFalse(IncomingUri.importable("file", "/data/user/0/com.onpath.app/databases/onpath.db", private))
        assertFalse(IncomingUri.importable("file", "/data/user/0/com.onpath.app/files/sync/pair.txt", private))
        assertFalse("no way out with ..", IncomingUri.importable("file", "/sdcard/../data/user/0/com.onpath.app/files/x.gpx", private))
        assertFalse(IncomingUri.importable("https", "/", private))
        assertFalse(IncomingUri.importable(null, null, private))
    }

    @Test fun normalFilesAreImported() {
        assertTrue(IncomingUri.importable("content", "/document/primary:Download/tour.gpx", private))
        assertTrue(IncomingUri.importable("file", "/storage/emulated/0/Download/tour.gpx", private))
        assertTrue("a sibling app's folder is not ours", IncomingUri.importable("file", "/data/user/0/com.onpath.app2/x.gpx", private))
    }
}
