package com.onpath.app.gpx

import com.onpath.app.routing.Activity

/**
 * The sport in a GPX `<trk><type>` / `<rte><type>` (owner 2026-10-25, "Yes, preselect"): Komoot writes its sport there
 * (`touring_bicycle`, `mtb`, `hike`, …; its API docs spell some without the underscore, `touringbicycle`). On import the
 * activity (and the T level for Hike) is preselected from it – the user can still change it; an unknown or missing type
 * keeps the last used activity. On export OnPath writes the Komoot string for the activity, so a round trip keeps it.
 *
 * Komoot has more sports than OnPath has activities; the closest one is taken (no seventh activity, owner): road bike →
 * Touring, Gravel (`mtb_easy`) → Trekking, Enduro (`mtb_advanced`) → MTB, running (`jogging`) → Hike, Bergtour
 * (`mountaineering`) → Hike T3.
 */
object SportType {

    /** An activity and, for Hike, its T level (SAC 1..5; ignored for bikes). */
    data class Pick(val activity: Activity, val sac: Int = DEFAULT_SAC)

    const val DEFAULT_SAC = 2

    /** Normalised Komoot type (lower case, no `_` / `-` / spaces) → activity. */
    private val komoot = mapOf(
        "touringbicycle" to Pick(Activity.BIKE_TOURING),
        "racebike" to Pick(Activity.BIKE_TOURING),
        "eracebike" to Pick(Activity.BIKE_TOURING),
        "etouringbicycle" to Pick(Activity.EBIKE),
        "mtbeasy" to Pick(Activity.BIKE_TREKKING),
        "mtb" to Pick(Activity.MTB),
        "mtbadvanced" to Pick(Activity.MTB),
        "emtb" to Pick(Activity.EMTB),
        "emtbeasy" to Pick(Activity.EMTB),
        "emtbadvanced" to Pick(Activity.EMTB),
        "hike" to Pick(Activity.HIKE, 2),
        "jogging" to Pick(Activity.HIKE, 2),
        "mountaineering" to Pick(Activity.HIKE, 3),
    )

    /** The activity a GPX `<type>` stands for; null = unknown / missing (then the last used activity stays). */
    fun activityOf(type: String?): Pick? {
        val key = type?.trim()?.lowercase()?.filter { it != '_' && it != '-' && !it.isWhitespace() }
        return if (key.isNullOrEmpty()) null else komoot[key]
    }

    /** What an import with [type] uses: the type's activity, or the [current] one ([currentSac]) when the type is unknown. */
    fun forImport(type: String?, current: Activity, currentSac: Int): Pick = activityOf(type) ?: Pick(current, currentSac)

    /**
     * The `<type>` written on export: Komoot's string for [activity] (as Komoot writes it in its own GPX exports), so
     * Komoot and OnPath read the sport back. Hike T3 and harder = `mountaineering` (Komoot's Bergtour), T1–T2 = `hike`.
     */
    fun typeOf(activity: Activity, sac: Int): String = when (activity) {
        Activity.BIKE_TOURING -> "touring_bicycle"
        Activity.EBIKE -> "e_touring_bicycle"
        Activity.BIKE_TREKKING -> "mtb_easy"
        Activity.MTB -> "mtb"
        Activity.EMTB -> "e_mtb"
        Activity.HIKE -> if (sac >= 3) "mountaineering" else "hike"
    }

    /** [typeOf] for a stored activity name (Tour.activity); null when it is missing or unknown (no `<type>` written). */
    fun typeOf(activityName: String?, sac: Int): String? =
        // expected: an unknown / old activity name writes no type
        activityName?.let { n -> Activity.entries.firstOrNull { it.name == n } }?.let { typeOf(it, sac) }
}
