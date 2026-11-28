package com.onpath.app.domain.tour

import com.onpath.app.data.Geo
import com.onpath.app.navigation.JunctionCues
import com.onpath.app.navigation.VoiceGuide
import com.onpath.app.routing.GeoPoint

/**
 * Where „Akku sparen“ must measure every second again (Chriss 2026-11-28 10:55, captain: arriving first, VISION): near a
 * junction of the followed route – from far enough ahead that the slow fix before it still lands before the first cue or
 * sentence, until a little past it (a wrong branch is seen at once) – and whenever the position is off the route or not on
 * it at all. Without a followed route there is no junction: the slow rate holds throughout. Pure; [junctionsAlong] keeps
 * the last line's junction positions.
 */
object SaveRate {
    /** Off the route this far (a fix beside the line): every second, so leaving is confirmed as fast as without saving. */
    const val OFF_M = 20.0
    /** Past a junction this far it stays fast: a wrong branch shows within a few fixes. */
    const val HIKE_BEHIND_M = 80.0
    const val BIKE_BEHIND_M = 150.0
    /** Room for the GPS's own spread and the speed changing between two slow fixes. */
    const val MARGIN_M = 20.0
    /** Speed not known yet: plan for the fastest the guidance expects (VoiceGuide's caps). */
    private fun speedOr(speedMps: Double?, bike: Boolean) = speedMps?.takeIf { it > 0 } ?: if (bike) VoiceGuide.BIKE_MAX_MPS else VoiceGuide.HIKE_MAX_MPS

    /**
     * How far before a junction the first thing is told: by bike the early sentence (25 s, 100–400 m) – the farthest; on
     * foot the junction cue (25 s, 25–60 m).
     */
    fun firstToldM(speedMps: Double, bike: Boolean): Double =
        if (bike) (speedMps * VoiceGuide.BIKE_EARLY_S).coerceIn(VoiceGuide.BIKE_EARLY_MIN_M, VoiceGuide.BIKE_EARLY_MAX_M)
        else JunctionCues.hikeLeadM(speedMps)

    /** The distance before a junction from which it is fast: what is told first + one slow interval + [MARGIN_M]. */
    fun aheadM(speedMps: Double?, bike: Boolean): Double {
        val v = speedOr(speedMps, bike)
        val slowS = (if (bike) BatterySave.BIKE_MS else BatterySave.HIKE_MS) / 1000.0
        return firstToldM(v, bike) + v * slowS + MARGIN_M
    }

    /**
     * Fast now? [alongM] = progress on the followed line (null = not on it), [offM] = distance to it, [junctionsAlongM] =
     * where its junctions are (metres from its start), [speedMps] = the speed (null = not known).
     */
    fun fast(alongM: Double?, offM: Double?, junctionsAlongM: DoubleArray, speedMps: Double?, bike: Boolean): Boolean {
        if (alongM == null || (offM ?: 0.0) > OFF_M) return true
        val ahead = aheadM(speedMps, bike)
        val behind = if (bike) BIKE_BEHIND_M else HIKE_BEHIND_M
        return junctionsAlongM.any { j -> j - alongM in -behind..ahead }
    }

    private var lineKey: Any? = null
    private var junctions = DoubleArray(0)

    /** The junction positions of [line] (points with a turn), cached per line object. */
    fun junctionsAlong(line: List<GeoPoint>): DoubleArray {
        if (lineKey !== line) {
            val cum = Geo.cumulative(line)
            junctions = line.indices.filter { line[it].turn != null }.map { cum[it] }.toDoubleArray()
            lineKey = line
        }
        return junctions
    }
}
