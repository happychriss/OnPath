package com.onpath.app.recording

/**
 * Plan mode is silent (owner 2026-11-03: "wenn ich im Planmodus bin, soll die Stimme auf jeden Fall schweigen zu allem …
 * keine Meldungen, die von Bewegung, von der Navigation kommen … Plan only"). While the app is in Plan during a tour
 * ([StopQuiet.quiet] – also with the screen off) nothing that comes from movement or navigation is said, vibrated or
 * shown; the recording goes on and still tells about itself. Back in Navigate the state is checked afresh – nothing
 * from the time in Plan is said late. Pure, unit-tested (`ModeQuietTest`).
 */
object ModeQuiet {
    enum class Event {
        /** Spoken directions: turns, start sentence, new route, back on the route. */
        VOICE,
        /** The junction vibration. */
        TURN_VIBRATION,
        /** A new off-route question (box + vibration). */
        OFF_ROUTE_QUESTION,
        /** The notification of an open off-route question. */
        OFF_ROUTE_NOTIFICATION,
        /** The re-route proposal (dashed new line). */
        REROUTE_PROPOSAL,
        /** "Stopp N erreicht". */
        STOP_REACHED,
        /** "Du bist angekommen" – voice and the arrival box. */
        ARRIVAL,
        /** "Kein GPS-Signal". */
        NO_GPS,
        /** A sentence the app asks for (arrival, "kein Ziel mehr" after an edit). */
        APP_EVENT_VOICE,
        /** The recording cannot be saved / stopped / restarted without GPS – never silent (R-07). */
        RECORDING_PROBLEM,
        /** Paused, but walked off: "resume?" – about the recording, not the guidance. */
        PAUSE_WALK_OFF,
    }

    /** What the recording says about itself, also in Plan. */
    private val RECORDING = setOf(Event.RECORDING_PROBLEM, Event.PAUSE_WALK_OFF)

    /** May [e] reach the user now? [planQuiet] = the app is in Plan during the tour. */
    fun allows(planQuiet: Boolean, e: Event): Boolean = !planQuiet || e in RECORDING
}
