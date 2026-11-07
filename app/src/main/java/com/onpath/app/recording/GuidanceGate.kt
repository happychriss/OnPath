package com.onpath.app.recording

/**
 * What the recording service lets through on each GPS fix and each cue – the one place for "Plan is silent" ([ModeQuiet],
 * owner 2026-11-03) and "after the arrival the guidance is quiet, the recording goes on" (review H2: arrival checked in
 * the service on every fix, also with the app in the pocket). Pure; RecordingService asks it at each gate.
 */
object GuidanceGate {
    /** Off-route check on a fix: only while moving and not arrived; otherwise the navigator is told "no fix" (it waits). */
    fun offRouteCheck(moving: Boolean, arrived: Boolean): Boolean = moving && !arrived

    /** Junction cues stop with the arrival. */
    fun junctionCue(arrived: Boolean): Boolean = !arrived

    enum class Junction { CUE, FOLLOW_SILENTLY }

    /** In Plan the voice follows without a word (stops passed, no backlog "Stopp 3 erreicht" later) and nothing buzzes. */
    fun junction(planQuiet: Boolean): Junction =
        if (ModeQuiet.allows(planQuiet, ModeQuiet.Event.TURN_VIBRATION)) Junction.CUE else Junction.FOLLOW_SILENTLY

    /** The voice takes a step at all: voice on and not arrived. */
    fun voiceSteps(voiceOn: Boolean, arrived: Boolean): Boolean = voiceOn && !arrived

    /** The voice speaks its step only outside Plan. */
    fun voiceSpeaks(planQuiet: Boolean): Boolean = ModeQuiet.allows(planQuiet, ModeQuiet.Event.VOICE)

    /** Arrival is checked once per line, not while paused, never in Plan (the next fix after Continue checks again). */
    fun arrivalCheck(arrived: Boolean, paused: Boolean, planQuiet: Boolean): Boolean =
        !arrived && !paused && ModeQuiet.allows(planQuiet, ModeQuiet.Event.ARRIVAL)

    /** An app event ("Kein Ziel mehr", arrival) is spoken only outside Plan, with the voice on. */
    fun appEventVoice(hasPhrase: Boolean, voiceOn: Boolean, planQuiet: Boolean): Boolean =
        hasPhrase && voiceOn && ModeQuiet.allows(planQuiet, ModeQuiet.Event.APP_EVENT_VOICE)

    /** A re-route step that wants to vibrate does so only outside Plan. */
    fun rerouteVibrates(stepVibrates: Boolean, planQuiet: Boolean): Boolean =
        stepVibrates && ModeQuiet.allows(planQuiet, ModeQuiet.Event.OFF_ROUTE_QUESTION)

    enum class OffRouteCue { SAY, VIBRATE, NONE }

    /**
     * How a re-route step that wants attention (leaving the route confirmed – step 1 –, a reminder, a way found after
     * none) is told: outside Plan, said with the voice on, else vibrated (the voice off, or the phone has no voice –
     * never a silent off-route, 2d06b5e6), nothing when the off-route vibration is switched off in Settings.
     */
    fun offRouteCue(stepVibrates: Boolean, planQuiet: Boolean, voiceOn: Boolean, vibrateSetting: Boolean): OffRouteCue = when {
        !rerouteVibrates(stepVibrates, planQuiet) -> OffRouteCue.NONE
        voiceOn -> OffRouteCue.SAY
        vibrateSetting -> OffRouteCue.VIBRATE
        else -> OffRouteCue.NONE
    }

    /** The event-log line of an off-route vibration ([OffRouteCue.VIBRATE]); the E2E tests read it. */
    const val OFF_ROUTE_VIBRATED = "Off route told by vibration"

    /**
     * A pending navigation question (off route, far start, mid route, lead failed) gets no notification in Plan and none
     * during a pause (owner 2026-11-07 "Pause heißt Ruhe"): it stays in the navigator, unanswered, and is asked again
     * after Weiter / in Navigate.
     */
    fun offRouteNotification(planQuiet: Boolean, paused: Boolean = false): Boolean =
        !paused && ModeQuiet.allows(planQuiet, ModeQuiet.Event.OFF_ROUTE_NOTIFICATION)
}
