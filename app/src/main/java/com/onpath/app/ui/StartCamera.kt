package com.onpath.app.ui

/**
 * Every app start / return to the app in Plan: centre the map on the own position (owner: "always at my current
 * location"). Pure, unit tested.
 *
 * A fresh fix centres at once; if none comes within [FRESH_WAIT_MS], the last known position is used (even if
 * old); without any position it keeps waiting – e.g. for the permission answer or a cold GPS start – and centres
 * when the first position arrives. Never against the user: once they moved the map, or when a tour is running /
 * the app is in Navigate, nothing happens. A hold on the map counts as moving it (MapHost).
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
        else -> Step.WAIT // no position yet: keep waiting (no time limit)
    }
}
