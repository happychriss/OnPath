package com.onpath.app.navigation

import com.onpath.app.data.Format
import kotlin.math.abs
import kotlin.math.roundToInt

/** Plain-language texts for the off-route question / banner and its notification (English for now). Pure. */
object NavText {
    const val TAKE = "Take new route"
    const val KEEP = "Keep original"

    fun title(p: NavPrompt): String = when (p) {
        NavPrompt.Searching -> "Off route – finding a way back…"
        is NavPrompt.Question -> "Off route – new route found"
        is NavPrompt.NoWay -> when (p.reason) {
            Rerouter.Reason.NO_DATA -> "Off route – no routing data here"
            Rerouter.Reason.UNAVAILABLE -> "Off route – routing not available"
            else -> "Off route – no way back found"
        }
        NavPrompt.Kept -> "Off route – original route kept"
    }

    /** "+350 m · rejoins in 600 m" / "350 m shorter · rejoins in 600 m" (+ "nearest way 300 m away"). */
    fun question(q: NavPrompt.Question): String = buildString {
        append(extra(q.extraM))
        append(" · rejoins in ").append(Format.distance(round10(q.rejoinInM)))
        if (q.startOffM > FAR_START_M) append(" · nearest way ").append(Format.distance(round10(q.startOffM))).append(" away")
    }

    fun extra(m: Double): String {
        val r = round10(abs(m))
        return when {
            r < 10 -> "About as long"
            m > 0 -> "+${Format.distance(r)}"
            else -> "${Format.distance(r)} shorter"
        }
    }

    /** "120 m to the route · north-east". */
    fun toRoute(h: RouteHint?): String? = h?.let { "${Format.distance(round10(it.distanceM))} to the route · ${compass(it.bearingDeg)}" }

    fun compass(deg: Double): String {
        val names = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
        return names[(((deg % 360 + 360) % 360) / 45.0).roundToInt() % 8]
    }

    private fun round10(m: Double) = (m / 10).roundToInt() * 10.0

    /** A new route that starts this far away is announced with the distance to the nearest way. */
    const val FAR_START_M = 150.0
}
