package com.onpath.app.ui

/**
 * App start in Plan: centre the map once on the own position (owner request). Pure, unit tested.
 *
 * A fresh fix centres at once; if none comes within [FRESH_WAIT_MS], the last known position is used
 * (even if old); without any position the map keeps its saved camera, and a fix that arrives later
 * (e.g. after the permission was granted) still centres until [ANY_WAIT_MS]. Never against the user:
 * once they moved the map, or when a tour is running / the app is in Navigate, nothing happens.
 */
object StartCamera {
    const val FRESH_WAIT_MS = 4_000L
    const val ANY_WAIT_MS = 60_000L
    /** A position at most this old counts as "the first fix" (seconds). */
    const val FRESH_AGE_S = 60L

    enum class Step { WAIT, CENTRE, GIVE_UP }

    /**
     * @param modeKnown the start mode (Plan / Navigate) has been decided (database check done)
     * @param plan the app is in Plan with no tour running
     * @param fixAgeS age of the newest known position in seconds, null = none
     */
    fun decide(elapsedMs: Long, modeKnown: Boolean, plan: Boolean, userMoved: Boolean, fixAgeS: Long?): Step = when {
        userMoved -> Step.GIVE_UP
        modeKnown && !plan -> Step.GIVE_UP
        !modeKnown -> if (elapsedMs >= ANY_WAIT_MS) Step.GIVE_UP else Step.WAIT
        fixAgeS != null && (fixAgeS <= FRESH_AGE_S || elapsedMs >= FRESH_WAIT_MS) -> Step.CENTRE
        elapsedMs >= ANY_WAIT_MS -> Step.GIVE_UP
        else -> Step.WAIT
    }
}
