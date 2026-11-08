package com.onpath.app.update

import com.onpath.app.net.HttpDownload
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Field bug 2026-11-08: GitHub answered 504 for every release's `browser_download_url`, while the API asset address
 * (`Accept: application/octet-stream`) delivered the APK. The updater must fall back between the two, retry a 5xx
 * once, and still check the sha256 before the installer gets the file.
 */
class UpdateFallbackTest {
    @get:Rule val tmp = TemporaryFolder()
    private val apk = ByteArray(400_000) { (it * 7).toByte() }
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val base = "http://127.0.0.1:${socket.localPort}"

    /** Status per path, one entry used per request (the last one repeats); 200 serves [apk]. */
    private val answers = java.util.Collections.synchronizedMap(HashMap<String, ArrayDeque<Int>>())
    /** Each request as "path accept-header". */
    private val log = java.util.Collections.synchronizedList(ArrayList<String>())

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val s = try { socket.accept() } catch (_: Exception) { break } // expected: closed at the end of the test
                s.use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    var accept: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Accept:", ignoreCase = true)) accept = line.substringAfter(":").trim()
                    }
                    log += "$path $accept"
                    val q = answers[path]
                    val code = synchronized(answers) { if (q == null) 404 else if (q.size > 1) q.removeFirst() else q.first() }
                    val out = c.getOutputStream()
                    if (code == 200) {
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${apk.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(apk)
                    } else {
                        out.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                    out.flush()
                }
            }
        }
    }

    @After fun stop() = socket.close()

    private fun release(sha: String? = HttpDownload.sha256Of(File(tmp.root, "ref").apply { writeBytes(apk) })) =
        AppUpdater.Release("9.9.9", "", "$base/browser.apk", apk.size.toLong(), "", sha, apkApiUrl = "$base/api/assets/1")

    private fun download(r: AppUpdater.Release = release()) = AppUpdater.downloadTo(tmp.newFolder("updates"), r, pauseMs = 1)

    @Test fun apiFailsWith5xxThenBrowserServesTheFile() {
        answers["/api/assets/1"] = ArrayDeque(listOf(502))
        answers["/browser.apk"] = ArrayDeque(listOf(200))
        val f = download()
        assertArrayEquals(apk, f.readBytes())
        // the 5xx is retried once, then the other address
        assertEquals(listOf("/api/assets/1", "/api/assets/1", "/browser.apk"), log.map { it.substringBefore(' ') })
    }

    @Test fun browser504ApiServesTheFileAfterTheDigestMatch() {
        // the field case, whichever address goes first: the browser address answers 504, the API address 200
        answers["/api/assets/1"] = ArrayDeque(listOf(200))
        answers["/browser.apk"] = ArrayDeque(listOf(504))
        val f = download()
        assertArrayEquals(apk, f.readBytes())
        assertTrue(f.name.endsWith("9.9.9.apk"))
        // the API address is asked for the file itself, not its JSON
        assertTrue(log.first(), log.first() == "/api/assets/1 application/octet-stream")
        assertTrue(log.none { it.startsWith("/browser.apk") })
    }

    @Test fun a5xxIsRetriedOnceOnTheSameAddress() {
        answers["/api/assets/1"] = ArrayDeque(listOf(503, 200))
        answers["/browser.apk"] = ArrayDeque(listOf(504))
        assertArrayEquals(apk, download().readBytes())
        assertEquals(listOf("/api/assets/1", "/api/assets/1"), log.map { it.substringBefore(' ') })
    }

    @Test fun bothAddressesFailWithAClearError() {
        answers["/api/assets/1"] = ArrayDeque(listOf(503))
        answers["/browser.apk"] = ArrayDeque(listOf(504))
        try {
            download(); fail("expected an error")
        } catch (e: IOException) {
            val m = e.message.orEmpty()
            assertTrue(m, m.contains("api 503") && m.contains("browser 504"))
        }
        // each address: one try + one retry, nothing more
        assertEquals(4, log.size)
    }

    @Test fun aWrongDigestNeverReachesTheInstaller() {
        answers["/api/assets/1"] = ArrayDeque(listOf(200))
        answers["/browser.apk"] = ArrayDeque(listOf(200))
        try {
            download(release(sha = "00".repeat(32))); fail("expected an error")
        } catch (e: IOException) {
            // expected: the damaged file is refused on both addresses
        }
        assertTrue(File(tmp.root, "updates").listFiles().orEmpty().none { it.name.endsWith(".apk") })
    }

    @Test fun withoutAnApiAddressOnlyTheBrowserAddressIsUsed() {
        answers["/browser.apk"] = ArrayDeque(listOf(200))
        assertArrayEquals(apk, download(release().copy(apkApiUrl = null)).readBytes())
        assertEquals(listOf("/browser.apk"), log.map { it.substringBefore(' ') })
    }
}
