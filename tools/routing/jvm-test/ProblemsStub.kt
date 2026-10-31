package com.onpath.app.debug

/** JVM-test stand-in for the app's Problems log (the real one needs android.util.Log / org.json): prints to stderr. */
object Problems {
    fun info(area: String, message: String, t: Throwable? = null) = report(area, message, t, "INFO")
    fun warn(area: String, message: String, t: Throwable? = null) = report(area, message, t, "WARN")
    fun error(area: String, message: String, t: Throwable? = null) = report(area, message, t, "ERROR")
    fun trace(area: String, message: String) { if (System.getenv("JVM_TRACE") != null) System.err.println("[trace] $area: $message") } // JVM_TRACE=1 prints the app's trace level (Planner)
    fun report(area: String, message: String, t: Throwable? = null, level: Any = "WARN") {
        System.err.println("[Problems $level] $area: $message" + (t?.let { " ($it)" } ?: ""))
    }
}

/** JVM-test stand-in for UserText (the real one names app-only exception types). */
object UserText {
    fun of(e: Throwable, plain: String): String =
        (e as? com.onpath.app.routing.RoutingException)?.message?.takeIf { it.isNotBlank() } ?: plain
}
