package com.onpath.app.ui

/** In the pocket this long: let the phone go to sleep normally (screen stays off until the power button). */
const val POCKET_SLEEP_MS = 60_000L

/**
 * When the proximity sensor switches the screen off while navigating with "keep screen on" (owner 2026-10-30). Pure,
 * unit-tested (`PocketLogicTest`); ui/PocketScreen.kt's guard feeds it the sensor and the clock and applies [Out].
 *
 * - Covered [armMs] without a break: the screen goes off ([Out.lock] = the proximity wake lock is held; while it is held
 *   the system turns the screen off whenever the sensor is covered and on when uncovered). Hike: 0 = at once, the lock
 *   is held all the time (as before). Bike: [BIKE_ARM_MS] – a hand reaching for the handlebar phone does not count; a
 *   phone put in a pocket / bag does.
 * - Uncovered: the screen is on again; on the bike the lock is let go until the next long cover.
 * - Covered [sleepMs] (counted from the start of the cover): keep-on is dropped ([Out.keepOn] = false) and the phone
 *   sleeps as usual; the lock is let go "when uncovered". Uncovered later: keep-on (and on foot the lock) again.
 * Clock = any monotonic milliseconds.
 */
class PocketLogic(private val armMs: Long, private val sleepMs: Long = POCKET_SLEEP_MS) {
    /** [lock] = hold the proximity wake lock; [keepOn] = keep the screen on. */
    data class Out(val lock: Boolean, val keepOn: Boolean)

    private var coveredSince: Long? = null
    private var asleep = false

    /** The sensor reports [covered] (near) or not. */
    fun sensor(covered: Boolean, nowMs: Long): Out {
        if (covered) { if (coveredSince == null) coveredSince = nowMs } else { coveredSince = null; asleep = false }
        return at(nowMs)
    }

    /** What to do now (also on a timer: [nextChangeInMs]). */
    fun at(nowMs: Long): Out {
        val since = coveredSince
        if (since != null && nowMs - since >= sleepMs) asleep = true
        val armed = armMs <= 0 || (since != null && nowMs - since >= armMs)
        return Out(lock = armed && !asleep, keepOn = !asleep)
    }

    /** When [at] changes by time alone: ms from [nowMs]; null = nothing pending (uncovered, or already asleep). */
    fun nextChangeInMs(nowMs: Long): Long? {
        val since = coveredSince ?: return null
        if (asleep) return null
        val t = if (armMs > 0 && nowMs < since + armMs) since + armMs else since + sleepMs
        return (t - nowMs).coerceAtLeast(0)
    }

    companion object {
        /** Bike: covered this long before the sensor counts (owner 2026-10-30: 5 s). */
        const val BIKE_ARM_MS = 5_000L
    }
}
