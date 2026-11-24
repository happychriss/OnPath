package com.onpath.app.debug

/**
 * Web stand-in for core's Problems log – it stays because the real one cannot run in the browser build: it starts a
 * scheduled executor and writes problems.json with java.nio / FileDescriptor (TeaVM has none of them; checked 2026-11-19:
 * the Wasm build fails on Executors, ConcurrentLinkedQueue (EventLog), File.toPath, FileOutputStream.getFD). Same calls,
 * keeps the last messages so the page can show them (and prints them to the browser console via stderr).
 * Level and Event are here only so the core files that name them (EventLog, MainStall, MapLibreLogLevel) compile.
 */
object Problems {
    enum class Level { INFO, WARN, ERROR, CRASH }
    data class Event(val time: Long, val area: String, val message: String)

    private val recent = ArrayDeque<String>()

    fun info(area: String, message: String, t: Throwable? = null) = report(area, message, t, Level.INFO)
    fun warn(area: String, message: String, t: Throwable? = null) = report(area, message, t, Level.WARN)
    fun error(area: String, message: String, t: Throwable? = null) = report(area, message, t, Level.ERROR)
    fun trace(area: String, message: String) = add("TRACE", area, message, null)
    fun report(area: String, message: String, t: Throwable? = null, level: Level = Level.WARN) = add(level.name, area, message, t)

    /** Web only: every message also goes here while set (WebPlaceAdd hands a shared decision's traces back as its „why“). */
    var tap: ((area: String, message: String) -> Unit)? = null

    private fun add(level: String, area: String, message: String, t: Throwable?) {
        tap?.invoke(area, message)
        val line = "$level $area: $message" + (t?.let { " ($it)" } ?: "")
        System.err.println("[Problems] $line")
        recent.addLast(line)
        while (recent.size > 50) recent.removeFirst()
    }

    fun drain(): List<String> = recent.toList().also { recent.clear() }
}
