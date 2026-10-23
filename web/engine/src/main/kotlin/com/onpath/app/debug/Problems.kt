package com.onpath.app.debug

/**
 * Web stand-in for the app's Problems log (the real one needs android.util.Log / SharedPreferences): same calls,
 * keeps the last messages so the page can show them (and prints them to the browser console via stderr).
 */
object Problems {
    private val recent = ArrayDeque<String>()

    fun info(area: String, message: String, t: Throwable? = null) = report(area, message, t, "INFO")
    fun warn(area: String, message: String, t: Throwable? = null) = report(area, message, t, "WARN")
    fun error(area: String, message: String, t: Throwable? = null) = report(area, message, t, "ERROR")
    fun report(area: String, message: String, t: Throwable? = null, level: Any = "WARN") {
        val line = "$level $area: $message" + (t?.let { " ($it)" } ?: "")
        System.err.println("[Problems] $line")
        recent.addLast(line)
        while (recent.size > 50) recent.removeFirst()
    }

    fun drain(): List<String> = recent.toList().also { recent.clear() }
}
