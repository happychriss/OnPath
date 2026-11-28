package com.onpath.app.domain.tour

/**
 * „Akku sparen“ (Chriss 2026-11-28 10:32 / 10:38 / 10:55; R-10): a setting the user sets any time (⚙ → Akku) or with
 * „Sparen“ in the question ([BatteryEstimate.asks]); it stays set. While on, GPS runs slow – a fix every [HIKE_MS] on foot,
 * [BIKE_MS] by bike – except where it matters (Chriss: „Ankommen zuerst“): near a junction of the followed route and off
 * it ([SaveRate] sets [fast]) it is every second again. Live sharing uploads at its slow rate and stays on. Nothing else
 * is dropped: screen, guidance and recording stay. The app writes [on] from the stored setting; the recording writes
 * [bike] and [fast]; `RestWatch.normalMs` reads [rateMs].
 */
object BatterySave {
    /** On foot while saving: a fix every 30 s (Chriss 10:55: „reichen da nicht alle 30 s?“). */
    const val HIKE_MS = 30_000L
    /** By bike while saving: every 10 s (captain 2026-11-28) – 70 m at 25 km/h. */
    const val BIKE_MS = 10_000L
    /** The rate where it matters, and without saving. */
    const val FAST_MS = 1_000L

    @Volatile var on: Boolean = false
    /** The tour is ridden (any bike activity), not walked. */
    @Volatile var bike: Boolean = false
    /** Near a junction or off the route ([SaveRate.fast]): every second even while saving. */
    @Volatile var fast: Boolean = false

    /** The moving GPS rate now. */
    fun rateMs(): Long = rateMs(on, bike, fast)

    fun rateMs(on: Boolean, bike: Boolean, fast: Boolean): Long = when {
        !on || fast -> FAST_MS
        bike -> BIKE_MS
        else -> HIKE_MS
    }
}
