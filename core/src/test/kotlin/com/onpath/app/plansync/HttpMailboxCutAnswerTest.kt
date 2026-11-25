package com.onpath.app.plansync

import com.onpath.app.debug.Problems
import com.onpath.app.sync.Mailbox
import com.onpath.app.sync.SyncKeys
import com.onpath.app.testkit.ProblemsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * An answer cut in the middle of its body (the network went while a page came in, the socket closed cleanly): the
 * phone takes it as "offline" (a quiet retry, INFO), not as a broken server answer (502 + a WARN in the problem log).
 * Pins the JDK's premature-EOF check HttpMailbox relies on (PlanSyncCutFlowTest's cut step, 2026-11-25).
 */
class HttpMailboxCutAnswerTest {
    @get:Rule val problems = ProblemsRule()

    private fun serveOnce(answer: (ServerSocket) -> Unit): Int {
        val s = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) { s.use { answer(it) } }
        return s.localPort
    }

    /** Reads the request head, then sends [head] + [body] and closes cleanly (output shut first: FIN, no RST). */
    private fun answer(head: String, body: ByteArray) = serveOnce { s ->
        s.accept().use { c ->
            val inp = c.getInputStream()
            var tail = 0 // the head ends at "\r\n\r\n" (a GET has no body)
            while (tail != 0x0d0a0d0a) { val ch = inp.read(); if (ch < 0) break; tail = (tail shl 8) or ch }
            c.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
            c.shutdownOutput()
            while (inp.read() >= 0) { /* expected: drain until the client closes */ }
        }
    }

    private val keys = SyncKeys(ByteArray(32) { 3 })
    private val page = """{"rev":7,"items":[{"id":"x","rev":7,"blob":"${"A".repeat(200)}"}]}""".toByteArray()

    @Test fun aPageCutMidBodyIsOfflineNotABrokenServer() {
        val half = page.copyOf(page.size / 2)
        val port = answer("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${page.size}\r\nConnection: close\r\n\r\n", half)
        val r = HttpMailbox("http://127.0.0.1:$port/", keys).read(0)
        assertEquals("a cut answer is the network gone", Mailbox.Reply.OFFLINE, r.status)
        assertTrue(problems.said("sync").toString(), problems.said("sync").any { it.startsWith("Sync server not reached (GET)") })
        assertTrue("no WARN for a network cut: ${problems.said("sync", Problems.Level.WARN)}", problems.said("sync", Problems.Level.WARN).isEmpty())
    }

    @Test fun aWholePageStillReads() {
        val port = answer("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${page.size}\r\nConnection: close\r\n\r\n", page)
        val r = HttpMailbox("http://127.0.0.1:$port/", keys).read(0)
        assertEquals(200, r.status)
    }
}
