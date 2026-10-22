package com.onpath.app.ui

/**
 * Counts compositions / draws of the heavy parts of the plan sheet (points list, elevation profile) for the JVM
 * smoothness test (SheetSmoothnessTest): moving the sheet must not recompose or redraw them every frame. Off in the
 * app (one boolean check).
 */
internal object RecomposeProbe {
    @Volatile var enabled = false
    private val counts = HashMap<String, Int>()

    fun hit(name: String) {
        if (!enabled) return
        synchronized(counts) { counts[name] = (counts[name] ?: 0) + 1 }
    }

    fun count(name: String): Int = synchronized(counts) { counts[name] ?: 0 }
    fun reset() = synchronized(counts) { counts.clear() }
}
