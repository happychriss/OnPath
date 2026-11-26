package com.onpath.app.ui

import com.onpath.app.routing.ProfileData

/**
 * What the elevation profile says under the finger (owner 2026-11-26, Daumen-Karte round 3): "1,2 km · noch 3,4 km ·
 * ↑ 200 m · 8 %" – the distance there, what is left, the climb up to there and the grade at that spot. Pure, unit-tested.
 */
object ProfileScrub {
    /** Metres climbed from the profile's start up to point [i] (only the ups add). */
    fun ascentTo(d: ProfileData, i: Int): Double {
        var up = 0.0
        for (k in 1..i.coerceIn(0, d.ele.lastIndex)) { val dh = d.ele[k] - d.ele[k - 1]; if (dh > 0) up += dh }
        return up
    }

    /** The grade in % at point [i], over [windowM] around it (a short window jitters); 0 when the line has no length there. */
    fun gradeAt(d: ProfileData, i: Int, windowM: Double = 100.0): Int {
        if (d.dist.size < 2) return 0
        val at = d.dist[i.coerceIn(0, d.dist.lastIndex)]
        var a = i.coerceIn(0, d.dist.lastIndex); var b = a
        while (a > 0 && at - d.dist[a] < windowM / 2) a--
        while (b < d.dist.lastIndex && d.dist[b] - at < windowM / 2) b++
        val run = d.dist[b] - d.dist[a]
        if (run <= 1.0) return 0
        return Math.round((d.ele[b] - d.ele[a]) / run * 100).toInt()
    }
}
