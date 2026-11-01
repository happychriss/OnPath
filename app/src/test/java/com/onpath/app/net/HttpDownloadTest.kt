package com.onpath.app.net

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.net.URL
import java.nio.file.Files

/** Review Arch 6: the one resumable download – resume, restart, 416, redirects, checksum, cancel. */
class HttpDownloadTest {
    private val body = ByteArray(1_000_000) { (it % 251).toByte() }
    private val sha = java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    private var ignoreRange = false
    private var cutAt = -1
    private var ranges = mutableListOf<String?>()

    /** A tiny HTTP/1.0 server: /file (ranges, 416, cut off at [cutAt]), /moved (302 → /file), /gone (404). */
    @Before fun start() {
        dir = Files.createTempDirectory("dl").toFile()
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: java.io.IOException) { break } // expected: closed in stop()
                thread(isDaemon = true) {
                    s.use { sock ->
                        val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val path = inp.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                        var range: String? = null
                        while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break
                            if (l.startsWith("Range:", ignoreCase = true)) range = l.substringAfter(':').trim() }
                        val out = sock.getOutputStream()
                        fun head(code: Int, extra: String = "", len: Int = 0) =
                            out.write("HTTP/1.0 $code X\r\nContent-Length: $len\r\n$extra\r\n".toByteArray())
                        when (path) {
                            "/moved" -> head(302, "Location: /file\r\n")
                            "/gone" -> head(404)
                            else -> {
                                synchronized(ranges) { ranges += range }
                                val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
                                if (from != null && from >= body.size) head(416)
                                else {
                                    val start = if (from != null && !ignoreRange) from else 0
                                    val end = if (cutAt in (start + 1) until body.size) cutAt else body.size
                                    head(if (start > 0) 206 else 200, len = body.size - start)
                                    out.write(body, start, end - start)
                                }
                            }
                        }
                        out.flush()
                    }
                }
            }
        }
    }

    @After fun stop() { server.close(); dir.deleteRecursively() }

    private fun url(p: String) = "http://127.0.0.1:${server.localPort}$p"
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
    private fun get(p: String, part: File, size: Long? = body.size.toLong(), sha256: String? = sha, cancelled: () -> Boolean = { false }) =
        HttpDownload.resume(url(p), part, size, sha256, open, cancelled = cancelled, allowHttp = true)

    @Test fun aCutDownloadResumesWhereItStopped() {
        val part = File(dir, "a.part")
        cutAt = 400_000
        try { get("/file", part); fail() } catch (e: Exception) { assertTrue("$e", e is HttpDownload.Incomplete || e is java.io.IOException) }
        assertTrue(part.length() in 1..400_000)
        cutAt = -1
        get("/file", part)
        assertArrayEquals(body, part.readBytes())
        assertTrue("a range was asked: $ranges", ranges.last()?.startsWith("bytes=") == true)
    }

    @Test fun aServerIgnoringTheRangeStartsOver() {
        val part = File(dir, "b.part").apply { writeBytes(body.copyOf(300_000)) }
        ignoreRange = true
        get("/file", part)
        assertArrayEquals(body, part.readBytes())
    }

    @Test fun aCompletePartIsNotFetchedAgain() {
        val part = File(dir, "c.part").apply { writeBytes(body) }
        get("/file", part)
        assertTrue(ranges.isEmpty())
    }

    @Test fun aWrongChecksumDeletesThePart() {
        val part = File(dir, "d.part")
        try { get("/file", part, sha256 = "00".repeat(32)); fail() } catch (e: HttpDownload.ChecksumMismatch) { /* expected */ }
        assertFalse(part.exists())
    }

    @Test fun redirectsAreFollowedButNeverToHttpUnlessAllowed() {
        val part = File(dir, "e.part")
        get("/moved", part)
        assertArrayEquals(body, part.readBytes())
        try { HttpDownload.resume(url("/moved"), File(dir, "f.part"), null, null, open); fail() } catch (e: HttpDownload.InsecureRedirect) { /* expected */ }
    }

    @Test fun aFailedStatusGoesToTheCaller() {
        try {
            HttpDownload.resume(url("/gone"), File(dir, "g.part"), null, null, open,
                failure = { code, _ -> IllegalStateException("status $code") }, allowHttp = true)
            fail()
        } catch (e: IllegalStateException) { assertEquals("status 404", e.message) }
    }

    @Test fun cancelKeepsThePart() {
        val part = File(dir, "h.part")
        var n = 0
        try { get("/file", part, cancelled = { n++ > 2 }); fail() } catch (e: java.io.IOException) { assertEquals("Download cancelled", e.message) }
        assertTrue(part.exists())
        get("/file", part)
        assertArrayEquals(body, part.readBytes())
    }

    @Test fun unknownSizeTakesTheContentLength() {
        val part = File(dir, "i.part")
        HttpDownload.resume(url("/file"), part, null, null, open, allowHttp = true)
        assertArrayEquals(body, part.readBytes())
    }
}
