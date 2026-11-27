package com.onpath.app.recording

import com.onpath.app.navigation.NavState
import com.onpath.app.routing.GeoPoint
import com.onpath.app.time.TripTime

/** Live recording state for the UI. */
data class RecState(
    val tourId: Long,
    val paused: Boolean,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val elapsedS: Long,
    val gpsLost: Boolean,
    /** Distance to the followed line, null when nothing is followed or no fix. */
    val offRouteM: Double?,
    val offRouteAlert: Boolean,
    val followName: String?,
    /** Segments of the recorded track (new segment after pause or GPS loss). */
    val track: List<List<GeoPoint>>,
    /** Navigation (followed line, re-routing question, grey original line); null for free recording. */
    val nav: NavState? = null,
    /** Moving time, breaks, to go and arrival (docs/plan-time-estimate.md); null before the first tick. */
    val time: TripTime? = null,
    /** The last track point could not be written (storage full, database broken): the UI must say so. */
    val saveFailed: Boolean = false,
    /**
     * A usable GPS fix came since the service started (this tour or its continuation). False = started indoors: the app
     * shows its Wi-Fi / fused fix as a provisional position until then ([com.onpath.app.navigation.Provisional]).
     */
    val hadGpsFix: Boolean = true,
    /**
     * "No GPS" is a quiet grey state, not the amber warning (owner 2026-11-01): standing still ([MoveWatch]), or lost
     * for less than [com.onpath.app.navigation.VoiceGuide.GPS_LOST_MS] – the warning comes with the spoken one.
     */
    val gpsQuiet: Boolean = false,
    /**
     * No GPS and the phone lies still by its own motion sensor (owner 2026-11-02, phone on the table): nothing is shown
     * about GPS at all ([NoGpsShow]); false without a sensor or while it is not sure.
     */
    val gpsStill: Boolean = false,
    /**
     * The end of the followed line was reached (review H2: checked here, also with the phone in the pocket). Guidance
     * is quiet from then on (no off-route alarms on the way to the car park); recording goes on. The app asks once.
     */
    val arrived: Boolean = false,
    /**
     * When the pause began (wall clock, ms; null while recording): Navigate's pause band counts from here (owner
     * 2026-11-07, variant C of https://claude.ai/artifact/24XVQ4goVZixT53fnQDn7H) – the service holds it, so the band
     * keeps its time when the app is reopened.
     */
    val pausedSinceMs: Long? = null,
    /** The motion sensor says the phone lies still now (GPS or not): the dot stays on the last track point ([StillDot]). */
    val sensorStill: Boolean = false,
)
