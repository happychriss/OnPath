package com.onpath.app.ui

import java.io.File

/**
 * Map style reloads after new map areas, per map host.
 *
 * Two activities – each with its own ViewModel and [MapHost] – can live at the same time for a moment. With
 * process-wide state a short-lived host clears the refs after the other has set them (every later reload a silent
 * no-op), and a ViewModel judges "the map is current" from the dir the OTHER host loaded. So each host keeps its own
 * state ([MapHostView]); shared refs are cleared only by their owner ([OwnedRef]); a ViewModel asks for a reload by what
 * it asked last ([StyleReloadRule]).
 */
class MapHostView {
    /** Map dir of this host's last style load (asked); null = no style asked yet. */
    var shown: File? = null
        private set
    private var reloadFn: (() -> Unit)? = null
    private var gone = false

    /** The map is ready: [reload] loads the style again with the current data. */
    fun ready(reload: () -> Unit) { if (!gone) reloadFn = reload }

    /** A style load from [dir] was asked on this host. */
    fun loading(dir: File?) { shown = dir }

    /** This host's map shows older data than [current] (and can load it). */
    fun behind(current: File): Boolean = !gone && reloadFn != null && shown != null && shown != current

    /** Loads the style again on THIS host's map; false = not ready (its first load comes) or disposed. */
    fun reload(): Boolean {
        val r = reloadFn ?: return false
        if (gone) return false
        r()
        return true
    }

    fun disposed() { gone = true; reloadFn = null }
}

/** A process-wide "latest" reference: whoever set it last owns it; only the owner clears it. */
class OwnedRef<T : Any> {
    private var owner: Any? = null
    private var value: T? = null

    fun get(): T? = value

    fun set(owner: Any, v: T) { this.owner = owner; value = v }

    fun clear(owner: Any) { if (this.owner === owner) { this.owner = null; value = null } }
}

object StyleReloadRule {
    /**
     * A new map store generation: this ViewModel asks its map to reload when the data dir differs from the one it
     * asked for last ([lastAsked]: the dir at start, then each asked reload) – never from what another host loaded.
     */
    fun ask(current: File, lastAsked: File?): Boolean = current != lastAsked
}
