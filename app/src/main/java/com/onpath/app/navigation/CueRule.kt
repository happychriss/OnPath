package com.onpath.app.navigation

import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.JunctionShape
import kotlin.math.abs

/**
 * Does the phone vibrate at this junction – could one go wrong here? (owner 2026-10-30: the bike, then the hike the same
 * way). Pure, unit-tested (`CueRuleTest`). Reads the ways at the junction BRouter saw ([JunctionShape], angles in
 * degrees, 0 = straight on):
 *
 * - Roundabouts and U-turns: always.
 * - No other way one could take (only oneways against the direction / ways without access): no – the road simply bends.
 * - Leaving the way one is on for a smaller one while that way goes on: yes, even straight on – the classic missed
 *   turn. On foot off the forest road onto a path; on the bike (owner 2026-10-30, after the hike) off the street onto a
 *   field track / path, off the cycleway onto a path, off the main road into a side street ([bikeKind]).
 * - Straight on along the clearly straightest way: no. The route goes within [STRAIGHT_DEG] of straight on and every
 *   other way bends at least [MARGIN_DEG] more (a crossing straight on; the road bends 30° right, the other way 70° left).
 * - Along the street / trail one is on: no. Every other way is a smaller class than the one the route comes on, the route
 *   does not go down a class, and it bends at most [BEND_DEG] (the main road bends, a side street goes straight on; the
 *   track bends, a path goes on; a right-angle turn of the main road still vibrates).
 * - Everything else: yes – a clear turn (T-junction, off the main road) or a fork with a similar way (Y 20° / 25°).
 * - No shape (routes from before this rule, a hint without way data): every junction but slight bends.
 *
 * Classes = the profile's priorityclassifier. The bike profiles have coarse ones (6 residential / service / cycleway,
 * 4 path / track); the bike's rules read them as they are, only "a smaller one" asks [bikeKind] (road > street / bike
 * way > track / path). hiking-mountain.brf ranks paths by surface (2–6) and tracks by grade (8 / 10) – on foot these are
 * read as one class each ([hikeClass]): two paths at a fork are "similar ways" whatever their surface tags.
 */
object CueRule {
    const val STRAIGHT_DEG = 45f
    const val MARGIN_DEG = 35f
    const val BEND_DEG = 75f

    /** [hike] = the route is from hiking-mountain.brf (its classes, the on-foot rule). */
    fun needed(cue: JunctionCue, shape: JunctionShape?, hike: Boolean = false): Boolean {
        if (cue == JunctionCue.ROUNDABOUT || cue == JunctionCue.U_TURN_LEFT || cue == JunctionCue.U_TURN_RIGHT) return true
        if (shape == null) return cue != JunctionCue.SLIGHT_LEFT && cue != JunctionCue.SLIGHT_RIGHT
        if (shape.others.isEmpty()) return false
        val cls: (Int) -> Int = if (hike) ::hikeClass else { p -> p }
        val inC = cls(shape.inPrio); val outC = cls(shape.outPrio)
        // leaving the way one is on for a smaller one while that way goes on
        val kind: (Int) -> Int = if (hike) ::hikeClass else ::bikeKind
        val inK = kind(shape.inPrio)
        if (kind(shape.outPrio) < inK && shape.others.any { kind(it.prio) >= inK }) return true
        val a = abs(shape.angle)
        val straightestOther = shape.others.minOf { abs(it.angle) }
        if (a <= STRAIGHT_DEG && straightestOther - a >= MARGIN_DEG) return false
        val alongTheStreet = outC >= inC && shape.others.all { cls(it.prio) < inC }
        if (alongTheStreet && a <= BEND_DEG) return false
        return true
    }

    /**
     * The kind of way a rider sees, from the bike profiles' classes (touring / trekking.brf, mtb.brf): tracks and paths
     * (≤ 4: track, path, footway, bridleway, steps, pedestrian) < streets and bike ways (5–19: residential, living
     * street, service, cycleway, designated bike way, paved track – touring 6, mtb.brf 14 / 16 / 6) < roads
     * (unclassified and up, 20–30). Within a kind nothing is "smaller": residential → service road are both streets
     * (touring / trekking cannot tell them apart, both 6), track → path both off-street (both 4 there), primary →
     * tertiary both roads.
     */
    fun bikeKind(prio: Int): Int = when {
        prio <= 0 -> 0
        prio <= 4 -> 1
        prio < 20 -> 2
        else -> 3
    }

    /**
     * hiking-mountain.brf's class → the class one sees on foot: path / footway (2–6) < track / bridleway (8, 10) <
     * service / cycleway (12, 14) < steps / pedestrian (16) < streets (18 residential … 30 motorway, kept as they are).
     */
    fun hikeClass(prio: Int): Int = when {
        prio <= 0 -> 0
        prio <= 6 -> 1
        prio <= 10 -> 2
        prio <= 14 -> 3
        prio <= 16 -> 4
        else -> prio
    }
}
