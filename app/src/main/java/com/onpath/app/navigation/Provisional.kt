package com.onpath.app.navigation

import com.onpath.app.recording.NET_MAX_ACCURACY_M
import com.onpath.app.recording.PointFilter

/**
 * A tour started indoors (owner: "at least take that as the start point – and still warn"). Until the recording
 * service's first usable GPS fix, Navigate stands on the app's own Wi-Fi / fused fix: the dot with its accuracy
 * circle, what is left, the turn card and the arrival clock count from it, and an amber line says it is approximate.
 *
 * What it never does (robust first): the fix never reaches the recording service ([com.onpath.app.recording.tracksFix]
 * – no track point, no off-route check, no re-route, no learned pace), and the arrival question is not asked on it.
 * Only until the first GPS fix: a later GPS gap (tunnel) is the normal "No GPS" line again. Pure.
 */
object Provisional {
    /** A fix older than this is no position to start from (the seed from an hour ago at home). */
    const val MAX_AGE_MS = 120_000L
    /** Wi-Fi accurate at least; a cell guess (km) is never a start point. */
    const val MAX_ACCURACY_M = NET_MAX_ACCURACY_M
    /** Off the line by more than the accuracy plus this: "not on route", no "to go" from a far point of the line. */
    const val ON_ROUTE_SLACK_M = 50.0

    enum class Source { WIFI, WEAK_GPS }

    data class Fix(val source: Source, val accuracyM: Int)

    /**
     * The provisional position, or null (normal rules). [provider] / [hasAccuracy] / [accuracyM] / [ageMs] describe the
     * app's current fix. A GPS fix the service takes anyway (≤ [PointFilter.MAX_ACCURACY_M]) is never provisional –
     * it is the real thing a second later.
     */
    fun of(tourRunning: Boolean, paused: Boolean, serviceHadGps: Boolean,
           provider: String?, hasAccuracy: Boolean, accuracyM: Float, ageMs: Long): Fix? {
        if (!asksPosition(tourRunning, paused, serviceHadGps)) return null
        if (!hasAccuracy || accuracyM > MAX_ACCURACY_M || ageMs > MAX_AGE_MS) return null
        val gps = provider == "gps"
        if (gps && accuracyM <= PointFilter.MAX_ACCURACY_M) return null
        return Fix(if (gps) Source.WEAK_GPS else Source.WIFI, Math.round(accuracyM))
    }

    /**
     * Whether the app asks for a provisional (fused) position now: a running, not paused tour without a GPS fix yet.
     * Ends with the first GPS fix, a pause and the end of the tour (battery: never for the rest of the tour).
     */
    fun asksPosition(tourRunning: Boolean, paused: Boolean, serviceHadGps: Boolean): Boolean =
        tourRunning && !paused && !serviceHadGps

    /** Near enough to the line ([offM] from it) to count "to go" from the nearest point of it. */
    fun onRoute(offM: Double, fix: Fix): Boolean = offM <= fix.accuracyM + ON_ROUTE_SLACK_M

    /** The arrival question is never asked on a provisional position (a ±40 m Wi-Fi fix passes the arrival gate). */
    fun arrivalCheck(fix: Fix?): Boolean = fix == null
}
