package com.onpath.app.recording

/**
 * Whether a fix starts a new track piece after a GPS gap (second read of the drift fix, 2026-11-27): a long / fast gap
 * splits the track ([PointFilter.bridgesGap]); when its first fix is dropped as drift (still by the sensor, [PointFilter])
 * the split waits for the first fix that is kept – the gap is not used up, so the next fix is no straight line across it
 * (wrong join, distance it never had). Not thread-safe (the location thread only).
 */
class GapSplit {
    private var pending = false

    /** Does this fix start a new piece? [wasLost] = a gap before it, [bridged] = that gap is crossed, [hasPrev] = a last point. */
    fun split(wasLost: Boolean, bridged: Boolean, hasPrev: Boolean): Boolean = hasPrev && ((wasLost && !bridged) || pending)

    /** The fix was judged: a split whose fix was not kept stays pending. */
    fun judged(split: Boolean, kept: Boolean) { pending = split && !kept }
}
