package com.onpath.app.plansync

import com.onpath.app.debug.Problems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Why a route in My routes → Planned is marked (a tag after its name: ui/RouteTag). */
enum class SyncMark {
    /** "New" (from the browser): the route / imported track arrived from the paired browser. */
    NEW,
    /** "Changed" (in the browser): the browser changed, renamed or restored it. */
    CHANGED,
}

/**
 * Routes that came from the browser and were not opened since (owner 2026-10-30): tour uuid → [SyncMark]. Only an
 * import from the browser marks ([PlanSyncPass]); a change on the phone never does. Opening the route (load as plan /
 * open the track) clears it; seeing the list does not. Gone with the route (recycle bin, deleted) and on unpair.
 *
 * Stored in `files/sync/marks.txt` (written atomically by [SyncFiles]), so marks survive a restart. Text form: header
 * `onpath-sync-marks 1`, then per mark `<tour uuid> <n|c>`; unreadable lines are dropped (a lost mark only loses the
 * tag). Thread-safe: the sync thread marks, the UI clears; [state] is what My routes shows. A write that fails
 * (storage full) is reported and the mark kept in memory for this run.
 */
class SyncMarks(private val load: () -> String?, private val save: (String) -> Unit) {
    private val lock = Any()
    private var loaded = false
    private val _state = MutableStateFlow<Map<String, SyncMark>>(emptyMap())

    /** The marks (empty until [all] or a change read the file – the view model reads it once at start). */
    val state: StateFlow<Map<String, SyncMark>> = _state

    /** The marks, read from the file the first time (background: reads a small file). */
    fun all(): Map<String, SyncMark> = synchronized(lock) { ensure(); _state.value }

    fun of(uuid: String): SyncMark? = all()[uuid]

    /** A new route / track from the browser. */
    fun arrived(uuid: String) { if (uuid.isNotEmpty()) change { it[uuid] = SyncMark.NEW } }

    /** The browser changed it: "changed" – unless it is still unopened "new" (new stays new). */
    fun changed(uuid: String) { if (uuid.isNotEmpty()) change { if (it[uuid] != SyncMark.NEW) it[uuid] = SyncMark.CHANGED } }

    /** Opened by the user, moved to the recycle bin or deleted: no mark. */
    fun remove(uuid: String) { change { it.remove(uuid) } }

    /** Only these routes (the live ones) keep a mark. */
    fun retain(uuids: Set<String>) { change { it.keys.retainAll(uuids) } }

    /** Unpaired: no marks. */
    fun clear() { change { it.clear() } }

    private fun ensure() {
        if (loaded) return
        loaded = true
        _state.value = read(load())
    }

    private inline fun change(f: (MutableMap<String, SyncMark>) -> Unit) = synchronized(lock) {
        ensure()
        val m = LinkedHashMap(_state.value)
        f(m)
        if (m == _state.value) return@synchronized
        _state.value = m
        try { save(write(m)) } catch (e: Exception) {
            Problems.warn("sync", "Sync: the marks of routes from the browser could not be stored; kept until the app closes", e)
        }
    }

    companion object {
        const val HEADER = "onpath-sync-marks 1"

        fun write(m: Map<String, SyncMark>): String = buildString {
            append(HEADER).append('\n')
            for ((uuid, mark) in m) append(uuid).append(' ').append(if (mark == SyncMark.NEW) 'n' else 'c').append('\n')
        }

        fun read(text: String?): Map<String, SyncMark> {
            val lines = text.orEmpty().split('\n').filter { it.isNotBlank() }
            if (lines.firstOrNull() != HEADER) return emptyMap()
            val out = LinkedHashMap<String, SyncMark>()
            for (l in lines.drop(1)) {
                val p = l.split(' ')
                if (p.size != 2 || p[0].isEmpty()) continue
                out[p[0]] = when (p[1]) { "n" -> SyncMark.NEW; "c" -> SyncMark.CHANGED; else -> continue }
            }
            return out
        }
    }
}
