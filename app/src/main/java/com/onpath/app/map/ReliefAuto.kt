package com.onpath.app.map

/**
 * "App merkt es selbst" (owner 2026-11-07): on a slow phone the live hill shading from the DEM loads the GPU so much
 * that the map – and the Compose UI over it – stutters while it moves. The app measures the frames of every map gesture
 * (FrameJankMeter, the window's FrameMetrics) and switches Relief off **once**, by itself, and says so on the map. The
 * user can switch it back on in Settings → Map; after that the app never overrides the user's choice again. Pure, unit
 * tested (ReliefAutoTest).
 *
 * The thresholds, from the owner's Galaxy S10 (release 0.17.3, `dumpsys gfxinfo`, ~20 s of pan / zoom):
 * relief + contours 46 % janky (median 19 ms, p90 31 ms) · contours only 14 % (14 / 28 ms) · both off 8 % (12 / 18 ms).
 * - A frame counts as janky when it takes more than 16 ms ([com.onpath.app.debug.FrameStats.over16]) – the same mark on
 *   every display: on 60 Hz (the S10) it is a missed frame, on 90 / 120 Hz (Pixel 8) already two. A per-refresh-rate
 *   budget (8.3 ms at 120 Hz) would count the Pixel 8's ordinary busy frames and switch its relief off for nothing.
 * - [JANK_SHARE] 30 %: the S10 with relief sits well above (median 19 ms means more than half the frames > 16 ms;
 *   gfxinfo's stricter count still says 46 %), and its no-relief figures well below (14 % / 8 %). A Pixel 8 draws the
 *   same map in a few ms per frame, its share > 16 ms stays near zero.
 * - Enough to be sure, not one bad moment: a gesture counts only with ≥ [MIN_GESTURE_FRAMES] frames (a nudge or a double
 *   tap tells nothing), and the share is taken over ≥ [MIN_GESTURES] such gestures with ≥ [MIN_FRAMES] frames together
 *   (~2 s at 60 Hz), from the last [WINDOW] gestures (an early stall while the first tiles load is outweighed later).
 * - Only gestures with relief on count: the question is whether relief makes this phone stutter.
 */
object ReliefAuto {
    /** A gesture with fewer frames is not counted (a nudge, a double tap). */
    const val MIN_GESTURE_FRAMES = 20
    /** At least this many counted gestures … */
    const val MIN_GESTURES = 2
    /** … with at least this many frames together. */
    const val MIN_FRAMES = 120
    /** Share of janky frames (> 16 ms) from which relief goes off. */
    const val JANK_SHARE = 0.30
    /** The last this-many counted gestures decide. */
    const val WINDOW = 6

    enum class Kind(val label: String) { PAN("pan"), ZOOM("zoom") }

    /** One map gesture (finger down → the map at rest again, fling included): its frames and those over 16 ms. */
    data class Gesture(val kind: Kind, val frames: Int, val janky: Int, val reliefOn: Boolean)

    /**
     * What is remembered beside the setting itself: [autoOff] = the app switched relief off once (never twice);
     * [byUser] = the user set relief by hand in Settings → Map (the user's choice always wins).
     */
    data class State(val autoOff: Boolean = false, val byUser: Boolean = false)

    /** The numbers behind a switch-off, for the events log. */
    data class Verdict(val gestures: Int, val frames: Int, val janky: Int) {
        val share: Double get() = if (frames == 0) 0.0 else janky.toDouble() / frames
        val percent: Int get() = Math.round(share * 100).toInt()
    }

    /** The gesture tells something about relief: relief on and enough frames. */
    fun counts(g: Gesture): Boolean = g.reliefOn && g.frames >= MIN_GESTURE_FRAMES

    /** The app may still decide: relief is on, the user never set it by hand, the app did not switch it off before. */
    fun mayDecide(reliefOn: Boolean, state: State): Boolean = reliefOn && !state.byUser && !state.autoOff

    /**
     * Switch relief off now? [recent] = the map gestures so far, oldest first. Returns the numbers when yes, null when
     * not (not allowed any more, too few gestures / frames, or smooth enough).
     */
    fun decide(recent: List<Gesture>, reliefOn: Boolean, state: State): Verdict? {
        if (!mayDecide(reliefOn, state)) return null
        val counted = recent.filter(::counts).takeLast(WINDOW)
        val v = Verdict(counted.size, counted.sumOf { it.frames }, counted.sumOf { it.janky })
        return v.takeIf { it.gestures >= MIN_GESTURES && it.frames >= MIN_FRAMES && it.share >= JANK_SHARE }
    }

    /** The user set relief by hand (Settings → Map): from now on the app never decides again. */
    fun userSet(state: State): State = state.copy(byUser = true)

    /** The app switched relief off. */
    fun switchedOff(state: State): State = state.copy(autoOff = true)
}
