package com.onpath.app.navigation

import com.onpath.app.data.Geo

/**
 * Re-routing and the next route point (owner 2026-10-30; docs/plan-auto-reroute.md §5.4). When one is off the plan,
 * the next route point N may now lie behind: going there costs a detour, while the point after it (N+1) is on the way.
 * Pure: the caller routes both ways – via N and straight to N+1 – and asks here.
 *
 * Measure: seconds from the position to N+1 via N ([viaS]), straight ([skipS]), and along the plan from where one is
 * abeam of it ([planS]) – same time model as the plan sheet. N is "behind" only when going there now costs clearly more
 * than **both** the straight way and the plan itself: a detour the plan makes on purpose (a loop, a route dragged over
 * a nicer path) is no reason to leave the point out – the rider is merely beside the plan.
 *
 * | Case | Decision |
 * | --- | --- |
 * | via ≤ [VIA_RATIO] × straight or × plan, or less than [MIN_DETOUR_S] more than either | **via** N, nothing said |
 * | N chosen on purpose ([deliberate]) and more than that | **ask** "Off route – re-plan?" (Yes = via N / Yes, skip stop N) |
 * | via ≥ [SKIP_RATIO] × straight and × plan | **skip** N (the question card says so) |
 * | in between | **ask** |
 *
 * Never skipped: the destination, a stop whose skip would cut more than half of the plan up to N+1
 * ([MIN_KEEP_SHARE] – the end of a round trip next to you, a loop), and – without asking – a deliberate stop.
 */
object StopSkip {
    enum class Decision { VIA, SKIP, ASK }

    /**
     * A detour below this is never worth a word: via. 90 s ≈ 450 m by bike, ≈ 110 m on foot. Owner's Hilden ride
     * (2026-10-28, rebuilt in StopSkipJvmTest): when the off-route was confirmed (98 m off) the next point cost +77 s
     * (×1.43) – via, no question.
     */
    const val MIN_DETOUR_S = 90.0
    /** Via N at most this times the straight way or the plan (+20 %): N is on the way – via. */
    const val VIA_RATIO = 1.2
    /**
     * Via N at least this times the straight way and the plan (+50 %): an ordinary route point is left out unasked.
     * Below it the owner is asked (Hilden, 376 m off: ×1.53 straight but ×1.44 the plan – asked).
     */
    const val SKIP_RATIO = 1.5
    /**
     * The straight way to N+1 must be at least this share of the plan from here to N+1: a skip that would cut more is
     * not "a point aside" but a different tour (round trip end next to you, the far end of a loop) – via, never asked.
     */
    const val MIN_KEEP_SHARE = 0.5

    /** Along-distances for the turning-point check: the plan goes there and comes back the same way. */
    private val TURN_PROBE_M = listOf(40.0, 100.0, 200.0)
    private const val TURN_GAP_SHARE = 0.25
    /** … and never more apart than this (a way back on the same way; switchback legs lie further apart). */
    private const val TURN_GAP_MAX_M = 15.0

    fun decide(viaS: Double, skipS: Double, planS: Double, deliberate: Boolean): Decision {
        // N is behind only when it costs clearly more than both "straight on" and "as planned": on the way of either = via
        val ref = maxOf(skipS, planS)
        if (viaS - ref < MIN_DETOUR_S || viaS <= VIA_RATIO * ref) return Decision.VIA
        if (deliberate) return Decision.ASK
        return if (viaS >= SKIP_RATIO * ref) Decision.SKIP else Decision.ASK
    }

    /** A skip of N that leaves at least [MIN_KEEP_SHARE] of the plan from [baseM] to the stop after ([nextAlongM]). */
    fun keepsTheTour(skipLegM: Double, baseM: Double, nextAlongM: Double): Boolean =
        skipLegM >= MIN_KEEP_SHARE * (nextAlongM - baseM)

    /**
     * A stop the user chose on purpose: a POI pinned to the plan, a named place (search result, shared place, a plan
     * link with names) or a turning point (the plan goes there and back the same way – a viewpoint, a hut).
     * An ordinary route point (tapped to shape the line) is none of these.
     */
    fun deliberate(stop: NavStop, line: NavLine): Boolean =
        stop.pinned || !stop.name.isNullOrBlank() || turningPoint(line, stop.alongM)

    /**
     * The line comes back the way it went: points the same distance before and after [alongM] lie close together
     * (two of the three probes – one alone is also a hairpin bend).
     */
    fun turningPoint(line: NavLine, alongM: Double): Boolean {
        if (!line.isUsable) return false
        return TURN_PROBE_M.count { d ->
            alongM - d >= 0 && alongM + d <= line.lengthM &&
                Geo.distance(line.pointAt(alongM - d), line.pointAt(alongM + d)) < minOf(TURN_GAP_SHARE * d, TURN_GAP_MAX_M)
        } >= 2
    }
}
