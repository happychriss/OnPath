package com.onpath.app.debug

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The long event trace on disk (owner: "no errors is the objective"); the in-memory list covers only a few hours.
 *
 * - Append-only, one line per event: `time-ms TAB local time TAB area TAB message` (tabs / line breaks escaped).
 * - Written off the calling thread by one writer thread; the caller only puts the line in a queue (cheap on the UI
 *   thread). Nothing is rewritten: lines are appended and flushed per batch.
 * - Two files: [CURRENT] and [OLDER]. When [CURRENT] passes half of [maxBytes] it becomes [OLDER] (the previous older
 *   file is dropped), so both together stay ≤ [maxBytes] (default 5 MB). Files whose newest line is older than
 *   [MAX_AGE_MS] (14 days) are dropped at start and at every rotation; export skips such lines too.
 * - Never throws; a full queue drops lines and says how many in the next line written.
 */
object EventLog {
    const val CURRENT = "events.log"
    const val OLDER = "events.1.log"
    const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024
    const val MAX_AGE_MS = 14L * 24 * 3600 * 1000
    /** Lines waiting for the writer; more are dropped (and counted) so a runaway trace cannot fill the memory. */
    private const val MAX_QUEUE = 20_000

    @Volatile var maxBytes: Long = DEFAULT_MAX_BYTES
        internal set

    @Volatile private var dir: File? = null
    private val queue = ConcurrentLinkedQueue<String>()
    private val queued = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private val drainScheduled = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "event-log-writer").apply { isDaemon = true } }
    // writer thread only
    private var out: OutputStream? = null
    private var currentBytes = 0L
    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    /** Call once at start (Problems.init). Drops files older than [MAX_AGE_MS]. */
    fun init(filesDir: File) {
        val d = filesDir
        val done = writer.submit {
            closeOut()
            dir = d
            dropOld(System.currentTimeMillis())
        }
        try { done.get(2, TimeUnit.SECONDS) } catch (_: Exception) { } // expected: a slow disk; the writer still finishes
    }

    /** Queues one event; returns at once. */
    fun append(time: Long, area: String, message: String) {
        if (dir == null) return
        if (queued.incrementAndGet() > MAX_QUEUE) { queued.decrementAndGet(); dropped.incrementAndGet(); return }
        queue.add("$time\t\u0000\t${esc(area)}\t${esc(message)}") // \u0000 = local time, filled by the writer
        if (drainScheduled.compareAndSet(false, true)) {
            try { writer.execute { drainScheduled.set(false); drain() } } catch (_: Throwable) { drainScheduled.set(false) }
        }
    }

    /** Waits (up to [timeoutMs]) until every queued line is on disk – before an export, at a crash, in tests. */
    fun flush(timeoutMs: Long = 5_000) {
        try { writer.submit { drain() }.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) { } // expected: timeout – export what is there
    }

    /** The log files, oldest first (for the export). */
    fun files(): List<File> {
        val d = dir ?: return emptyList()
        return listOf(File(d, OLDER), File(d, CURRENT)).filter { it.isFile }
    }

    /** The last [n] events (oldest first) from the end of the files – read at start for the in-memory list. */
    fun tail(n: Int): List<Problems.Event> {
        val out = ArrayList<Problems.Event>()
        for (f in files().reversed()) {
            if (out.size >= n) break
            val lines = try { tailLines(f, n - out.size) } catch (_: Exception) { emptyList() } // expected: unreadable file – start empty
            out.addAll(0, lines.mapNotNull { parse(it) })
        }
        return out.takeLast(n)
    }

    /** Writes both files into [os] without lines older than [MAX_AGE_MS]. */
    fun copyTo(os: OutputStream, now: Long = System.currentTimeMillis()) {
        flush()
        for (f in files()) {
            try {
                f.bufferedReader().useLines { lines ->
                    lines.forEach { l ->
                        val t = l.substringBefore('\t').toLongOrNull()
                        if (t == null || now - t <= MAX_AGE_MS) { os.write(l.toByteArray()); os.write('\n'.code) }
                    }
                }
            } catch (e: Exception) { os.write("(${f.name} unreadable: $e)\n".toByteArray()) }
        }
    }

    fun clear() {
        try {
            writer.submit {
                queue.clear(); queued.set(0); closeOut()
                files().forEach { it.delete() }
            }.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) { } // expected: slow disk – the files go with the next rotation
    }

    fun parse(line: String): Problems.Event? {
        val p = line.split('\t', limit = 4)
        if (p.size < 4) return null
        val t = p[0].toLongOrNull() ?: return null
        return Problems.Event(t, unesc(p[2]), unesc(p[3]))
    }

    // ---- writer thread ----

    private fun drain() {
        val d = dir ?: return
        try {
            val sb = StringBuilder()
            val lost = dropped.getAndSet(0)
            if (lost > 0) sb.append(System.currentTimeMillis()).append("\t\u0000\tlog\t").append(lost).append(" events dropped (queue full)\n")
            while (true) {
                val l = queue.poll() ?: break
                queued.decrementAndGet()
                sb.append(l).append('\n')
                if (sb.length > 64_000) { write(d, sb); sb.setLength(0) }
            }
            if (sb.isNotEmpty()) write(d, sb)
            out?.flush()
        } catch (e: Exception) {
            closeOut()
            System.err.println("OnPath event log write failed: $e") // never into Problems: that would loop
        }
    }

    private fun write(d: File, sb: StringBuilder) {
        // fill in the local time (one formatter, this thread only)
        var i = sb.indexOf("\t\u0000\t")
        while (i >= 0) {
            val start = sb.lastIndexOf("\n", i).let { if (it < 0) 0 else it + 1 }
            val t = sb.substring(start, i).toLongOrNull() ?: 0L
            val s = stampFormat.format(Date(t))
            sb.replace(i + 1, i + 2, s)
            i = sb.indexOf("\t\u0000\t", i + 1 + s.length)
        }
        val bytes = sb.toString().toByteArray()
        if (currentBytes + bytes.size > maxBytes / 2 && currentBytes > 0) rotate(d)
        val o = out ?: FileOutputStream(File(d, CURRENT), true).buffered(32 * 1024).also {
            out = it; currentBytes = File(d, CURRENT).length()
        }
        o.write(bytes)
        currentBytes += bytes.size
    }

    private fun rotate(d: File) {
        closeOut()
        val older = File(d, OLDER)
        older.delete()
        File(d, CURRENT).renameTo(older)
        currentBytes = 0
        dropOld(System.currentTimeMillis())
    }

    private fun dropOld(now: Long) {
        val d = dir ?: return
        for (name in listOf(OLDER, CURRENT)) {
            val f = File(d, name)
            if (f.isFile && now - f.lastModified() > MAX_AGE_MS) { if (name == CURRENT) closeOut(); f.delete() }
        }
    }

    private fun closeOut() {
        try { out?.close() } catch (_: Exception) { } // expected: closing a broken stream; a new one is opened
        out = null
        currentBytes = 0
    }

    private fun tailLines(f: File, n: Int): List<String> {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val from = maxOf(0L, len - n.toLong() * 300)
            raf.seek(from)
            val buf = ByteArray((len - from).toInt())
            raf.readFully(buf)
            var lines = String(buf).split('\n').filter { it.isNotEmpty() }
            if (from > 0 && lines.isNotEmpty()) lines = lines.drop(1) // first line may be cut
            return lines.takeLast(n)
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
    private fun unesc(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
