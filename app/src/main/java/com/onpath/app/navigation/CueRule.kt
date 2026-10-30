package com.onpath.app.navigation

import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.JunctionShape
import kotlin.math.abs

/**
 * Does the bike vibrate at this junction – could one go wrong here? (owner 2026-10-30). Pure, unit-tested
 * (`CueRuleTest`). Reads the ways at the junction BRouter saw ([JunctionShape], angles in degrees, 0 = straight on):
 *
 * - Roundabouts and U-turns: always.
 * - No other way one could take (only oneways against the direction / ways without access): no – the road simply bends.
 * - Straight on along the clearly straightest way: no. The route goes within [STRAIGHT_DEG] of straight on and every
 *   other way bends at least [MARGIN_DEG] more (a crossing straight on; the road bends 30° right, the other way 70° left).
 * - Along the street one is on: no. Every other way is a smaller road class than the one the route comes on, the route
 *   does not go down a class, and it bends at most [BEND_DEG] (the main road bends, a side street goes straight on; a
 *   right-angle turn of the main road still vibrates).
 * - Everything else: yes – a clear turn (T-junction, off the main road) or a fork with a similar way (Y 20° / 25°).
 * - No shape (routes from before this rule, a hint without way data): as before – every junction but slight bends.
 */
object CueRule {
    const val STRAIGHT_DEG = 45f
    const val MARGIN_DEG = 35f
    const val BEND_DEG = 75f

    fun needed(cue: JunctionCue, shape: JunctionShape?): Boolean {
        if (cue == JunctionCue.ROUNDABOUT || cue == JunctionCue.U_TURN_LEFT || cue == JunctionCue.U_TURN_RIGHT) return true
        if (shape == null) return cue != JunctionCue.SLIGHT_LEFT && cue != JunctionCue.SLIGHT_RIGHT
        val a = abs(shape.angle)
        if (shape.others.isEmpty()) return false
        val straightestOther = shape.others.minOf { abs(it.angle) }
        if (a <= STRAIGHT_DEG && straightestOther - a >= MARGIN_DEG) return false
        val alongTheStreet = shape.outPrio >= shape.inPrio && shape.others.all { it.prio < shape.inPrio }
        if (alongTheStreet && a <= BEND_DEG) return false
        return true
    }
}
