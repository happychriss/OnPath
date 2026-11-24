package com.onpath.app.testkit

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The network between the app's HTTP client and a local test server (FakeDoorman, a mailbox), as the test breaks it
 * (hard cases, docs/team/items/2026-11-24-hard-case-test-coverage.md #5 / #8). The client talks to [port]; every
 * connection goes on to the server at [targetPort] unchanged, until the test says otherwise:
 * - [breakNext]: one answer is cut – [Cut.HALF] = status, headers and half of the body, then the connection closes
 *   ([hold] keeps it open, silent, after the half until the test lets go: a link that just stops); [Cut.DROP] = the
 *   server got and did the request, the answer never comes. With `thenDown` the link is gone right after it.
 * - [down]: nothing listens any more (connection refused); [up]: back on the same port.
 * One request per connection (the test servers answer with `Connection: close`).
 */
class FlakyLink(private val targetPort: Int) : AutoCloseable {
    enum class Cut { HALF, DROP }

    private class Rule(val match: (String) -> Boolean, val nth: Int, val how: Cut, val thenDown: Boolean) {
        val seen = AtomicInteger()
    }

    @Volatile private var listener: ServerSocket = open(0)
    /** The port the client uses – the same after [down] / [up]. */
    val port: Int = listener.localPort
    @Volatile var isUp = true
        private set
    /** Request lines ("GET /v1/… HTTP/1.1") that reached the link, in order (refused ones never do). */
    val requests = CopyOnWriteArrayList<String>()
    /** Answers cut so far. */
    val cuts = AtomicInteger()
    @Volatile private var rule: Rule? = null
    /** With [Cut.HALF]: after the half, the connection waits here (silent) before it closes; null = closes at once. */
    @Volatile var hold: CountDownLatch? = null
    /** Counted down when a [Cut.HALF] answer has sent its half (and waits on [hold]). */
    @Volatile var halfSent = CountDownLatch(1)

    init { serve(listener) }

    /** Cuts the [nth] answer (1 = the next) whose request line matches [match]; [thenDown] = the link goes with it. */
    fun breakNext(how: Cut, nth: Int = 1, thenDown: Boolean = true, match: (String) -> Boolean) {
        halfSent = CountDownLatch(1)
        rule = Rule(match, nth, how, thenDown)
    }

    /** Nothing listens any more: the next connection is refused. Connections already open go on. */
    fun down() {
        if (!isUp) return
        isUp = false
        listener.close()
    }

    /** Listening again, on the same port. */
    fun up() {
        if (isUp) return
        listener = open(port)
        isUp = true
        serve(listener)
    }

    override fun close() { hold?.countDown(); down() }

    private fun open(p: Int) = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 50)
    }

    private fun serve(s: ServerSocket) = Thread {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: java.io.IOException) { break } // expected: down() closed the listener
            Thread { relay(c) }.apply { isDaemon = true }.start()
        }
    }.apply { isDaemon = true }.start()

    private fun relay(client: Socket) {
        client.use { c ->
            Socket("127.0.0.1", targetPort).use { server ->
                val line = readLine(c.getInputStream())
                requests += line
                val out = server.getOutputStream()
                out.write((line + "\r\n").toByteArray()); out.flush()
                // the rest of the request (headers, body) as it comes; ends when either side closes
                Thread { pump(c.getInputStream(), out) }.apply { isDaemon = true }.start()
                val answer = server.getInputStream().readBytes() // the test servers close after answering
                val r = rule?.takeIf { it.match(line) && it.seen.incrementAndGet() == it.nth }
                if (r == null) { c.getOutputStream().apply { write(answer); flush() }; return }
                rule = null
                cuts.incrementAndGet()
                if (r.thenDown) down()
                if (r.how == Cut.HALF) {
                    val head = headerLength(answer)
                    val body = answer.size - head
                    c.getOutputStream().apply { write(answer, 0, head + body / 2); flush() }
                    halfSent.countDown()
                    hold?.await(60, TimeUnit.SECONDS)
                }
                // DROP: the server did it, the client hears nothing; HALF: the rest never comes – closed here
            }
        }
    }

    private fun readLine(inp: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) { val ch = inp.read(); if (ch < 0 || ch == '\n'.code) break; if (ch != '\r'.code) b.write(ch) }
        return b.toString("UTF-8")
    }

    private fun pump(inp: InputStream, out: OutputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); out.flush() }
        } catch (e: java.io.IOException) {
            // expected: the relay closed both sockets after the answer (or the cut)
        }
    }

    private fun headerLength(a: ByteArray): Int {
        for (i in 0 until a.size - 3) if (a[i] == '\r'.code.toByte() && a[i + 1] == '\n'.code.toByte() &&
            a[i + 2] == '\r'.code.toByte() && a[i + 3] == '\n'.code.toByte()) return i + 4
        return a.size
    }
}
