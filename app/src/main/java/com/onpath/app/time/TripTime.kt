package com.onpath.app.time

/** Live times of the running tour, published by the recording service (RecState.time). */
data class TripTime(
    /** Recording time without breaks (s). */
    val movingS: Double,
    /** Breaks so far (s), including one still going on. */
    val breakS: Double,
    /** Calm moving time to the end of the followed line (s, rounded for display); null = nothing followed / not known. */
    val toGoS: Double? = null,
    /** Calm arrival clock (epoch ms) incl. the break allowance; null with [toGoS]. */
    val arrivalMs: Long? = null,
    /** Learned on this trip, relative to the plan (1 = as planned); null until ~20 min of learning. */
    val learned: Factors? = null,
)
