package com.onpath.app.routing

/**
 * Settings → Plan → „Markierten Wegen folgen“ (owner 2026-11-27): how strongly a route follows marked hiking and
 * cycling trails – [LESS], [STANDARD] (default) or [MORE] (`AppSettings.markedTrails`). It reaches BRouter as profile
 * parameters ([keyValues]), next to the SAC limit, in every engine that routes (planning, the tour's re-routing, the
 * round-trip suggestions, the web planner) and in the routing fingerprint ([RoutingFingerprint.of]), so legs routed with
 * another level route again.
 *
 * Values (owner-approved measurements 2026-11-27: Standard makes hikes ~3 % longer on average, worst +10 %):
 * - Hike (hiking-mountain.brf `hiking_routes_preference`, profile 0.20): 0.2 · 0.5 · 1.0
 * - MTB + E-MTB (mtb.brf `cycleroutes_pref`, profile 0.2): 0.2 · 0.4 · 1.0
 * - Bike, E-bike, Gravel (touring.brf / trekking.brf `stick_to_cycleroutes`, profile false): false · false · true (as 0 / 1)
 *
 * Pure (the web planner compiles it).
 */
object MarkedTrails {
    const val LESS = 0
    const val STANDARD = 1
    const val MORE = 2

    /** A stored value outside 0..2 (a newer or broken store) counts as [STANDARD]. */
    fun of(level: Int): Int = if (level in LESS..MORE) level else STANDARD

    /** The BRouter profile parameters of [level] for [activity]. */
    fun keyValues(activity: Activity, level: Int): Map<String, String> {
        val l = of(level)
        return when (RouterRules.profileFile(activity)) {
            "hiking-mountain.brf" -> mapOf("hiking_routes_preference" to listOf("0.2", "0.5", "1.0")[l])
            "mtb.brf" -> mapOf("cycleroutes_pref" to listOf("0.2", "0.4", "1.0")[l])
            // a boolean in the profile; BRouter reads every key-value as a number (Float.parseFloat): 1 = true, 0 = false
            else -> mapOf("stick_to_cycleroutes" to if (l == MORE) "1" else "0")
        }
    }
}
