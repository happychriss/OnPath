package com.onpath.app.domain.tour

/**
 * Does the battery last to the goal? (Chriss 2026-11-28 10:38, „A und die Frage“; R-10, item 2026-11-28-battery-critical.)
 * Fed with every battery reading of a navigated tour ([level]); the use per hour comes from the battery's own 1 % steps –
 * the rate between the first and the last step down, so a start just above a step does not count – and only once the tour
 * ran [MIN_MS] and the level fell [MIN_DROP] since the start. Charging, or a level that goes up, starts the count over.
 * Rather not ask than ask wrongly: no estimate → no question. Pure; not thread-safe (one owner).
 */
class BatteryEstimate {
    private var startMs = -1L
    private var startLevel = -1
    private var nowMs = -1L
    private var nowLevel = -1
    private var firstStepMs = -1L
    private var firstStepLevel = -1
    private var lastStepMs = -1L
    private var lastStepLevel = -1

    /** The level in percent now (null = no reading yet). */
    val level: Int? get() = nowLevel.takeIf { it >= 0 }

    /** One reading: [timeMs] on any steady clock, [pct] 0–100. */
    fun level(timeMs: Long, pct: Int, charging: Boolean) {
        if (charging || (nowLevel >= 0 && pct > nowLevel)) { reset(); if (charging) return }
        if (startMs < 0) { startMs = timeMs; startLevel = pct; nowMs = timeMs; nowLevel = pct; return }
        if (pct < nowLevel) {
            if (firstStepMs < 0) { firstStepMs = timeMs; firstStepLevel = pct }
            lastStepMs = timeMs; lastStepLevel = pct
        }
        nowMs = timeMs; nowLevel = pct
    }

    private fun reset() { startMs = -1; startLevel = -1; nowMs = -1; nowLevel = -1; firstStepMs = -1; firstStepLevel = -1; lastStepMs = -1; lastStepLevel = -1 }

    /** Percent per hour, null until the tour ran [MIN_MS] and fell [MIN_DROP] since its start (two steps at least). */
    fun usePerHour(): Double? {
        if (startMs < 0 || nowMs - startMs < MIN_MS || startLevel - nowLevel < MIN_DROP) return null
        val ms = lastStepMs - firstStepMs
        if (firstStepMs < 0 || ms <= 0) return null
        return (firstStepLevel - lastStepLevel) * 3_600_000.0 / ms
    }

    /** The level expected at the goal, [toGoS] seconds from now; null = no estimate. */
    fun levelAtGoal(toGoS: Double): Double? = usePerHour()?.let { nowLevel - it * toGoS / 3600.0 }

    companion object {
        /** The tour runs this long before anything is estimated (Chriss: „nach 30 min Wanderung“). */
        const val MIN_MS = 30 * 60_000L
        /** … and the level fell this much since the start (captain's reading 2026-11-28). */
        const val MIN_DROP = 3
        /** Ask when the goal would be reached with less than this (captain's reading 2026-11-28). */
        const val ASK_BELOW_PCT = 10.0

        /** The question „Akku reicht wohl nicht bis zum Ziel“: once per tour, not while saving already, only with an estimate. */
        fun asks(e: BatteryEstimate, toGoS: Double?, saving: Boolean, askedThisTour: Boolean): Boolean =
            asks(e.level, e.usePerHour(), toGoS, saving, askedThisTour)

        /** The same from the numbers alone ([level] now, [usePerHour]) – what the app's watcher publishes. */
        fun asks(level: Int?, usePerHour: Double?, toGoS: Double?, saving: Boolean, askedThisTour: Boolean): Boolean {
            if (saving || askedThisTour || toGoS == null || toGoS <= 0 || level == null || usePerHour == null) return false
            return level - usePerHour * toGoS / 3600.0 < ASK_BELOW_PCT
        }

        /** How long [level] lasts at [usePerHour], seconds; null = no estimate. */
        fun lastsS(level: Int?, usePerHour: Double?): Double? = if (level == null || usePerHour == null || usePerHour <= 0) null else level / usePerHour * 3600.0
    }
}
