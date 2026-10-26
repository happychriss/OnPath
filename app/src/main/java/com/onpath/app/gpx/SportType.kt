package com.onpath.app.gpx

import com.onpath.app.data.LastActivity
import com.onpath.app.routing.Activity

/**
 * The sport in a GPX `<trk><type>` / `<rte><type>` (owner 2026-10-25, "Yes, preselect"): Komoot writes its sport there
 * (`touring_bicycle`, `mtb`, `hike`, …; its API docs spell some without the underscore, `touringbicycle`). On import the
 * activity (and the T level for Hike) is preselected from it – the user can still change it; an unknown or missing type
 * keeps the last used activity. On export OnPath writes the Komoot string for the activity, so a round trip keeps it.
 *
 * Komoot has more sports than OnPath has activities; the closest one is taken (no seventh activity, owner): road bike →
 * Bike, Gravel (`mtb_easy`) → Gravel bike, Enduro (`mtb_advanced`) → MTB, running (`jogging`) → Hike, Bergtour
 * (`mountaineering`) → Hike T3.
 *
 * No surprises (captain 2026-10-26): a hiking type covers a band of T levels (`hike` / `jogging` T1–T2,
 * `mountaineering` T3–T5) – the user's own T level is kept when it is inside the band, else the band's default (T2 / T3).
 * When the import changes the activity or T level, the track panel says so in one grey line ([importChoice]).
 */
object SportType {

    /** An activity and, for Hike, its T level (SAC 1..5; ignored for bikes). */
    data class Pick(val activity: Activity, val sac: Int = LastActivity.DEFAULT_SAC)

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

    /** T levels a hiking type covers (normalised key → band); the default inside it is the [komoot] pick's level. */
    private val bands = mapOf(
        "hike" to 1..2,
        "jogging" to 1..2,
        "mountaineering" to 3..5,
    )

    private fun key(type: String?): String? =
        type?.trim()?.lowercase()?.filter { it != '_' && it != '-' && !it.isWhitespace() }?.takeIf { it.isNotEmpty() }

    /** The activity a GPX `<type>` stands for; null = unknown / missing (then the last used activity stays). */
    fun activityOf(type: String?): Pick? = key(type)?.let { komoot[it] }

    /**
     * What an import with [type] uses: the type's activity, or the [current] one ([currentSac]) when the type is unknown.
     * For a hiking type the user's remembered T level [currentSac] is kept when it is inside the type's band.
     */
    fun forImport(type: String?, current: Activity, currentSac: Int): Pick {
        val k = key(type)
        val pick = k?.let { komoot[it] } ?: return Pick(current, currentSac)
        val band = bands[k] ?: return pick
        return if (currentSac in band) pick.copy(sac = currentSac) else pick
    }

    /**
     * What an import does to the chosen activity: [pick] is stored with the imported tour; [switch] = the app's activity
     * (and T level) becomes [pick] – not during a tour ([touring]: the followed route keeps its own); [note] = the grey
     * line in the track panel when it switched ("Activity set to Hike T3 from the file"), null when nothing changed.
     */
    data class ImportChoice(val pick: Pick, val switch: Boolean, val note: String?)

    fun importChoice(type: String?, current: Activity, currentSac: Int, touring: Boolean): ImportChoice {
        val pick = forImport(type, current, currentSac)
        val switch = !touring && (pick.activity != current || (pick.activity == Activity.HIKE && pick.sac != currentSac))
        val note = if (!switch) null
            else "Activity set to ${LastActivity.name(pick.activity, pick.sac)} from the file"
        return ImportChoice(pick, switch, note)
    }

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
        activityName?.let { n -> Activity.entries.firstOrNull { it.name == n } }?.let { typeOf(it, sac) }
}
