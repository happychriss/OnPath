package com.onpath.app.ui

import com.onpath.app.recording.GpsStatus

/**
 * Plan is quiet about GPS (owner 2026-11-06 23:35: "Beim Planen muss das Handy auch ruhig sein und keine GPS-Warnung
 * machen"): no green / amber / red dot on the locate button, no red box, no voice. What stays: the neutral position dot
 * on the map when there is a position, and the locate button – plain, greyed while there is no position to go to. Only a
 * tap of the user's says something, as a short neutral note ([Note]). Navigate keeps its GPS signs (dot, card lines,
 * voice). Pure, unit-tested (`PlanGpsTest`).
 */
object PlanGps {
    /** How long the neutral note after a tap stays (it also goes as soon as a position is there). */
    const val NOTE_MS = 4_000L

    /** The GPS dot on the locate button: Navigate only. */
    fun showDot(plan: Boolean): Boolean = !plan

    /** The locate button looks greyed: in Plan while there is no position to go to. */
    fun locateGreyed(plan: Boolean, status: GpsStatus, hasFix: Boolean): Boolean = plan && !located(status, hasFix)

    /** There is a position to show. */
    fun located(status: GpsStatus, hasFix: Boolean): Boolean =
        hasFix && (status == GpsStatus.GOOD || status == GpsStatus.WEAK)

    enum class Tap {
        /** Centre on the position (and follow it). */
        CENTRE,
        /** Location is off: the phone's location settings (the user's own tap – no warning). */
        OPEN_SETTINGS,
        /** No permission: the system asks. */
        ASK_PERMISSION,
        /** No position yet: follow it as soon as it comes, and say so neutrally ([Note.SEARCHING]). */
        WAIT_AND_NOTE,
    }

    /** What a tap on locate does (both modes – Navigate never had a note: its card says it). */
    fun tap(plan: Boolean, status: GpsStatus, hasFix: Boolean): Tap = when (status) {
        GpsStatus.OFF -> Tap.OPEN_SETTINGS
        GpsStatus.NO_PERMISSION -> Tap.ASK_PERMISSION
        else -> if (plan && !located(status, hasFix)) Tap.WAIT_AND_NOTE else Tap.CENTRE
    }

    /** The neutral notes Plan may show – only after a tap of the user's, never by itself. */
    enum class Note { SEARCHING, TURN_NEEDS_POSITION }

    /** The compass tap that needs a position (walking direction up) without one: Plan a neutral note, Navigate the error box. */
    fun compassWithoutFix(plan: Boolean): Note? = if (plan) Note.TURN_NEEDS_POSITION else null

    /** Where the note stands: under the activity button's row (and under the overview pill on it) – covers nothing. */
    fun noteTopDp(rowTopDp: Int, stripBottomDp: Int?): Int =
        OverviewPillPlace.topDp(rowTopDp, stripBottomDp) + OverviewPillPlace.HEIGHT_DP + MessagePlacement.GAP_DP

    /** The note goes as soon as there is a position. */
    fun noteGone(note: Note?, status: GpsStatus, hasFix: Boolean): Boolean = note != null && located(status, hasFix)
}
