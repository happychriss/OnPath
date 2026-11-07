package com.onpath.app.debug

import com.onpath.app.map.ReliefAuto
import org.json.JSONObject

/**
 * Frame times of the map gestures since the app started, per gesture kind (pan / zoom) and relief on / off – for the
 * problem report ("mapJank" in diagnostics.json) – and the recent gestures the relief rule ([ReliefAuto]) decides on.
 * Pure apart from the lock; unit tested (MapJankTallyTest).
 */
class MapJankTally {
    private data class Sum(var gestures: Int = 0, var frames: Int = 0, var janky: Int = 0)

    private val sums = LinkedHashMap<String, Sum>()
    private val recent = ArrayDeque<ReliefAuto.Gesture>()

    @Synchronized fun add(g: ReliefAuto.Gesture) {
        val s = sums.getOrPut(key(g.kind, g.reliefOn)) { Sum() }
        s.gestures++; s.frames += g.frames; s.janky += g.janky
        recent.addLast(g)
        while (recent.size > KEEP) recent.removeFirst()
    }

    /** The last gestures, oldest first. */
    @Synchronized fun recent(): List<ReliefAuto.Gesture> = recent.toList()

    /**
     * Per kind and relief state: `{"pan, relief on": {"gestures": 3, "frames": 412, "janky": 190, "jankyPercent": 46}, …}`
     * (janky = frames over 16 ms); empty without a measured gesture.
     */
    @Synchronized fun json(): JSONObject = JSONObject().also { o ->
        for ((k, s) in sums) o.put(k, JSONObject().put("gestures", s.gestures).put("frames", s.frames).put("janky", s.janky)
            .put("jankyPercent", if (s.frames == 0) 0 else (100.0 * s.janky / s.frames).let(Math::round).toInt()))
    }

    private fun key(kind: ReliefAuto.Kind, reliefOn: Boolean) = "${kind.label}, relief ${if (reliefOn) "on" else "off"}"

    private companion object {
        /** Enough for [ReliefAuto.WINDOW] counted gestures between short ones. */
        const val KEEP = 30
    }
}

/** The app-wide tally (the map host adds, the view model decides, Diagnostics reads). */
object MapJank {
    val tally = MapJankTally()
}
