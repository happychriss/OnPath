package com.onpath.app.plansync

/**
 * Which pass a sync run makes ([PlanSync]): no tour → the full pass; during a tour nothing (the routes sync after it),
 * except the pairing handshake right after a scan (field bug 2026-10-31: paired during a tour, the browser waited for
 * the phone's hello forever). A scan [arm]s the gate once; the next run takes it – so a tour never loops on handshakes:
 * one per scan, then only after the tour. Thread-safe, pure.
 */
class PassGate {
    enum class Step { FULL, HANDSHAKE, SKIP }

    @Volatile private var armed = false

    /** A pairing was made / a browser joined: the next run may say hello also during a tour. */
    fun arm() { armed = true }

    /** The next run's pass; takes the armed handshake (also when no tour runs – the full pass says hello too). */
    @Synchronized fun next(touring: Boolean): Step {
        val handshake = armed
        armed = false
        return when {
            !touring -> Step.FULL
            handshake -> Step.HANDSHAKE
            else -> Step.SKIP
        }
    }
}
