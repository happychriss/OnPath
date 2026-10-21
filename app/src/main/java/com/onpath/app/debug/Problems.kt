package com.onpath.app.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The one place where every problem of the app goes – nothing may fail silently (owner, 2026-10-19: a missing glyph
 * made MapLibre drop whole map tiles for days without anybody noticing).
 *
 * - `Problems.report("map", "Glyph range missing", e)` from any thread, also in a catch block that recovers.
 * - Same area + message (numbers ignored) = one entry with a counter, first and last time: a problem that repeats
 *   every second on a 14-hour tour is one line with "× 50 400", not 50 400 lines.
 * - Kept in memory and written (debounced, atomically) to `problems.json` in the app's files folder, so problems of
 *   earlier runs – including crashes – are still there after a restart.
 * - Shown in Settings → Diagnostics, included in the diagnostics export; a new ERROR or CRASH is announced once per
 *   run in the app ([newSerious]).
 *
 * Levels: INFO = expected but worth knowing (offline, user cancelled), WARN = something did not work and the app
 * used a fallback, ERROR = a feature is broken / data may be wrong, CRASH = the app stopped.
 * Never throws; works in JVM tests (no Android Log there).
 */
object Problems {

    enum class Level { INFO, WARN, ERROR, CRASH }

    data class Entry(
        val level: Level, val area: String, val message: String, val detail: String?,
        val first: Long, val last: Long, val count: Int,
    ) {
        val key: String get() = keyOf(area, message)
    }

    /**
     * One gesture / menu event (hold, press, point menu opened / action / closed): kept with its own text and time,
     * never merged (field 2026-10-21: 21 different "Hold:" lines were stored as one entry with × 21).
     */
    data class Event(val time: Long, val area: String, val message: String)

    /** How many [trace] events are kept (the most recent). */
    const val MAX_EVENTS = 50

    private const val FILE = "problems.json"
    private const val MAX_ENTRIES = 300
    private const val TAG = "OnPath"

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val events = ArrayDeque<Event>()
    private var file: File? = null
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "problems-writer").apply { isDaemon = true } }
    @Volatile private var writePending = false
    /** Keys already announced in this run (an ERROR is announced once per run). */
    private val announced = HashSet<String>()

    private val _count = MutableStateFlow(0)
    /** Number of distinct entries (UI refresh trigger). */
    val count: StateFlow<Int> = _count
    private val _newSerious = MutableStateFlow<Entry?>(null)
    /** Latest ERROR / CRASH not announced yet in this run; the app shows it once and calls [announcedSerious]. */
    val newSerious: StateFlow<Entry?> = _newSerious

    /** Loads earlier problems; call once from Application.onCreate. */
    fun init(filesDir: File) {
        val f = File(filesDir, FILE)
        synchronized(lock) {
            file = f
            try {
                if (f.isFile) {
                    val a = JSONObject(f.readText()).optJSONArray("problems") ?: JSONArray()
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        val e = Entry(runCatching { Level.valueOf(o.getString("level")) }.getOrDefault(Level.WARN),
                            o.optString("area"), o.optString("message"), o.optString("detail").ifEmpty { null },
                            o.optLong("first"), o.optLong("last"), o.optInt("count", 1))
                        entries[e.key] = e
                    }
                    val ev = JSONObject(f.readText()).optJSONArray("events") ?: JSONArray()
                    events.clear()
                    for (i in ev.length() - 1 downTo 0) { // stored newest first
                        val o = ev.getJSONObject(i)
                        events.addLast(Event(o.optLong("time"), o.optString("area"), o.optString("message")))
                    }
                    while (events.size > MAX_EVENTS) events.removeFirst()
                }
            } catch (e: Exception) {
                log(Level.WARN, "problems.json unreadable, starting empty: $e", null)
            }
            _count.value = entries.size
        }
    }

    fun info(area: String, message: String, t: Throwable? = null) = report(area, message, t, Level.INFO)
    fun warn(area: String, message: String, t: Throwable? = null) = report(area, message, t, Level.WARN)
    fun error(area: String, message: String, t: Throwable? = null) = report(area, message, t, Level.ERROR)

    /**
     * Records a problem. [area] = short component name ("map", "recording", "routing", "db", "import", …);
     * [message] = what went wrong in plain words; [t] = the exception (its class, message and a few stack lines are
     * kept). Coroutine cancellation is not a problem and is ignored.
     */
    fun report(area: String, message: String, t: Throwable? = null, level: Level = Level.WARN) {
        try {
            if (t is kotlinx.coroutines.CancellationException) return
            val now = System.currentTimeMillis()
            val detail = t?.let { describe(it) }
            val key = keyOf(area, message)
            val entry: Entry
            synchronized(lock) {
                val old = entries.remove(key)
                entry = if (old == null) Entry(level, area, message.take(500), detail, now, now, 1)
                else old.copy(level = maxOf(old.level, level), detail = detail ?: old.detail, last = now, count = old.count + 1)
                entries[key] = entry // re-insert: most recent last
                while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
                _count.value = entries.size
                // announce by the level of THIS report: an entry stored as ERROR by an earlier run / older version that
                // now comes in as INFO (e.g. a re-classified MapLibre note) is not announced again
                if (level >= Level.ERROR && announced.add(key)) _newSerious.value = entry
            }
            if (entry.count == 1 || entry.count % 100 == 0) log(level, "[$area] $message", t)
            if (level == Level.CRASH) writeNow() else scheduleWrite()
        } catch (_: Throwable) {
            // the problem log itself must never break the app
        }
    }

    /**
     * Records one event of a sequence that matters step by step (map holds, point menu opened / Delete / closed):
     * every call is its own line with its own time – never merged like [report] – the last [MAX_EVENTS] are kept,
     * written to logcat (tag OnPath) and into the diagnostics export (problems.json "events"). Never throws.
     */
    fun trace(area: String, message: String) {
        try {
            val e = Event(System.currentTimeMillis(), area, message.take(500))
            synchronized(lock) {
                events.addLast(e)
                while (events.size > MAX_EVENTS) events.removeFirst()
            }
            log(Level.INFO, "[$area] $message", null)
            scheduleWrite()
        } catch (_: Throwable) {
            // the event log itself must never break the app
        }
    }

    /** Recent [trace] events, most recent first. */
    fun events(): List<Event> = synchronized(lock) { events.reversed() }

    /** Tests: write the file now. */
    internal fun flushForTest() = writeNow()

    fun announcedSerious() { _newSerious.value = null }

    /** Most recent first. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.values.reversed() }

    fun clear() {
        synchronized(lock) { entries.clear(); events.clear(); announced.clear(); _count.value = 0; _newSerious.value = null }
        scheduleWrite()
    }

    /** Problems of the last crash recorded by an earlier run (shown once at start). */
    fun lastCrash(sinceMs: Long): Entry? = snapshot().firstOrNull { it.level == Level.CRASH && it.last >= sinceMs }

    fun toJson(): JSONObject = JSONObject().put("problems", JSONArray().apply {
        snapshot().forEach { e ->
            put(JSONObject().put("level", e.level.name).put("area", e.area).put("message", e.message)
                .put("detail", e.detail ?: JSONObject.NULL).put("first", e.first).put("last", e.last).put("count", e.count))
        }
    }).put("events", JSONArray().apply {
        events().forEach { e ->
            put(JSONObject().put("time", e.time).put("at", stamp(e.time)).put("area", e.area).put("message", e.message))
        }
    })

    private fun stamp(t: Long): String = try {
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date(t))
    } catch (_: Throwable) { "" } // expected: never fails; the time in ms is there anyway

    /** Same problem = same area + message with numbers ignored (tile ids, coordinates, sizes change). */
    fun keyOf(area: String, message: String): String =
        area + "|" + message.take(200).replace(Regex("[0-9]+(\\.[0-9]+)?"), "#")

    fun describe(t: Throwable): String = buildString {
        append(t.javaClass.name).append(": ").append(t.message ?: "")
        t.stackTrace.take(6).forEach { append("\n  at ").append(it) }
        t.cause?.let { append("\ncaused by ").append(it.javaClass.name).append(": ").append(it.message ?: "") }
    }.take(2000)

    private fun scheduleWrite() {
        if (writePending || file == null) return
        writePending = true
        try { writer.schedule({ writePending = false; writeNow() }, 2, TimeUnit.SECONDS) } catch (_: Throwable) { writePending = false }
    }

    private fun writeNow() {
        val f = file ?: return
        try {
            val text = toJson().toString()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Throwable) { }
    }

    private fun log(level: Level, msg: String, t: Throwable?) {
        try {
            when (level) {
                Level.INFO -> Log.i(TAG, msg, t)
                Level.WARN -> Log.w(TAG, msg, t)
                Level.ERROR, Level.CRASH -> Log.e(TAG, msg, t)
            }
        } catch (_: Throwable) { } // JVM tests: no android.util.Log
    }
}
