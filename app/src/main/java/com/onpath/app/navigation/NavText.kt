package com.onpath.app.navigation

import com.onpath.app.data.Format
import com.onpath.app.routing.Activity
import kotlin.math.abs
import kotlin.math.roundToInt

/** Plain-language texts for the off-route question / banner and its notification (English for now). Pure. */
object NavText {
    const val TAKE = "Take new route"
    const val KEEP = "Keep original"

    fun title(p: NavPrompt): String = when (p) {
        NavPrompt.Searching -> "Off route – finding a way back…"
        is NavPrompt.Question -> p.switch?.let { switchTitle(it) } ?: "Off route – new route found"
        is NavPrompt.NoWay -> when (p.reason) {
            Rerouter.Reason.NO_DATA -> "Off route – no routing data here"
            Rerouter.Reason.UNAVAILABLE -> "Off route – routing not available"
            else -> "Off route – no way back found"
        }
        NavPrompt.Kept -> "Off route – original route kept"
    }

    /** "+350 m · rejoins in 600 m" / "350 m shorter · rejoins in 600 m" (+ "nearest way 300 m away"). */
    /** "New route for Bike – trekking" / "New route for Hike (T3)". */
    fun switchTitle(sw: ActivitySwitch): String = "New route for " + activityName(sw.activity, sw.sac)

    /** "Hike (T3)" / "MTB". */
    fun activityName(a: Activity, sac: Int): String = if (a == Activity.HIKE) "${a.label} (T${sac.coerceIn(1, 5)})" else a.label

    fun question(q: NavPrompt.Question): String = buildString {
        q.switch?.let { sw ->
            // "Rest of the tour 9.3 km (+1.2 km) · 3 h 10 min"
            append("Rest of the tour ").append(Format.distance(round10(q.legM)))
            val e = round10(abs(q.extraM))
            if (e >= 100) append(" (").append(if (q.extraM > 0) "+" else "−").append(Format.distance(e)).append(")")
            append(" · ").append(Format.duration(sw.timeS))
            if (q.startOffM > FAR_START_M) append(" · nearest way ").append(Format.distance(round10(q.startOffM))).append(" away")
            return@buildString
        }
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

    /** "120 m to the route · north-east · straight line" – a bearing, not a path: it may cross cliffs or water. */
    fun toRoute(h: RouteHint?): String? = h?.let { "${Format.distance(round10(it.distanceM))} to the route · ${compass(it.bearingDeg)} · straight line" }

    /**
     * "46.54120 N 10.90320 E · ±8 m · 1234 m" for an emergency call; "· fix 4 min old" when the fix is old.
     * Five decimals ≈ 1 m. Pure.
     */
    fun position(lat: Double, lon: Double, accuracyM: Float?, altitudeM: Double?, ageS: Long): String = buildString {
        append("%.5f %s %.5f %s".format(java.util.Locale.ROOT, abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W"))
        accuracyM?.let { append(" · ±").append(it.roundToInt()).append(" m") }
        altitudeM?.let { append(" · ").append(it.roundToInt()).append(" m") }
        if (ageS >= 60) append(" · fix ").append(ageS / 60).append(" min old")
    }

    /** "46.54120, 10.90320" – what is copied (pastes into any map app or message). */
    fun positionCopy(lat: Double, lon: Double): String = "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon)

    fun compass(deg: Double): String {
        val names = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
        return names[(((deg % 360 + 360) % 360) / 45.0).roundToInt() % 8]
    }

    private fun round10(m: Double) = (m / 10).roundToInt() * 10.0

    /** A new route that starts this far away is announced with the distance to the nearest way. */
    const val FAR_START_M = 150.0
}
