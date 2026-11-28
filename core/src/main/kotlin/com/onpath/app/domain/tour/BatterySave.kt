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

    /** [mark] before a start: the next tour takes it. */
    const val NEXT = -2L

    /**
     * For which tour „Akku sparen“ is on (Chriss 2026-11-28 12:55: „gilt immer nur für die aktuelle Route“): null = off,
     * [NEXT] = set before a start, else the tour's id. Stored by the app; [afterTourChange] binds and ends it.
     */
    @Volatile var mark: Long? = null
    /** The tour recording now (null = none / not known yet after a start). */
    @Volatile var runningTour: Long? = null

    /** Saving now. Writing it is for tests and start-up: on = [NEXT]. */
    var on: Boolean
        get() = effective(mark, runningTour)
        set(v) { mark = if (v) NEXT else null }

    /** On for the running tour, or set and waiting for the next one (shown „an“ before the start). */
    fun effective(mark: Long?, runningTour: Long?): Boolean = mark != null && (mark == NEXT || mark == runningTour)

    /** The switch: on = for the running tour, or the next one when none runs; off = off. */
    fun switchTo(on: Boolean, runningTour: Long?): Long? = if (on) runningTour ?: NEXT else null

    /**
     * The running tour changed ([prevTour] → [nowTour], null = none): a waiting [NEXT] is taken by the tour that starts;
     * the tour it was for ended or another began → off. The app's first, empty state is no end (a restart mid-tour, R-07).
     */
    fun afterTourChange(mark: Long?, prevTour: Long?, nowTour: Long?): Long? = when {
        mark == null -> null
        mark == NEXT -> nowTour ?: NEXT
        nowTour == mark -> mark
        prevTour == mark -> null
        nowTour != null -> null
        else -> mark
    }

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
