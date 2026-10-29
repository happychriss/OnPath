package com.onpath.app.update

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** An update download broken on mobile data ("connection abort") must resume where it stopped. */
class UpdateDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val apk = ByteArray(1_000_000) { (it * 31).toByte() }
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val calls = AtomicInteger()
    private val ranges = java.util.Collections.synchronizedList(ArrayList<String?>())

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val s = try { socket.accept() } catch (_: Exception) { break } // expected: closed at the end of the test
                s.use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    var range: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(":").trim()
                    }
                    val n = calls.incrementAndGet(); ranges += range
                    val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                    val body = apk.copyOfRange(from, apk.size)
                    val out = c.getOutputStream()
                    out.write(("HTTP/1.1 ${if (from > 0) "206 Partial Content" else "200 OK"}\r\nContent-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray())
                    // the first two connections break after 300 kB
                    out.write(body, 0, if (n <= 2) minOf(300_000, body.size) else body.size)
                    out.flush()
                }
            }
        }
    }

    @After fun stop() = socket.close()

    private fun release() = AppUpdater.Release("9.9.9", "", "http://127.0.0.1:${socket.localPort}/onpath.apk", apk.size.toLong(), "")

    @Test fun brokenConnectionResumesWithRange() {
        val f = AppUpdater.downloadTo(tmp.newFolder("updates"), release(), pauseMs = 1)
        assertArrayEquals(apk, f.readBytes())
        assertEquals(3, calls.get())
        assertEquals(null, ranges[0])
        assertEquals(listOf("bytes=300000-", "bytes=600000-"), ranges.drop(1))
        assertTrue(f.name.endsWith("9.9.9.apk"))
    }
}
