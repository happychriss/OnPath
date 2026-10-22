package com.onpath.app.data

import com.onpath.app.routing.Activity

/**
 * Which activity and hiking level (SAC T1–T5) the app starts with (owner 2026-10-22): always the last chosen ones,
 * remembered across sessions; fresh install = Hike, T2. Older installs had a "Default activity" / "Hiking difficulty"
 * in Settings (keys defaultActivity / defaultSac): those count when nothing was chosen in the picker yet, so a user
 * keeps the level he set. Pure rules, the storage is [Prefs].
 */
object LastActivity {
    val DEFAULT_ACTIVITY = Activity.HIKE
    const val DEFAULT_SAC = 2

    fun activity(hasLast: Boolean, last: Activity, hasOldDefault: Boolean, oldDefault: Activity): Activity = when {
        hasLast -> last
        hasOldDefault -> oldDefault
        else -> DEFAULT_ACTIVITY
    }

    fun sac(last: Int?, oldDefault: Int?): Int = (last ?: oldDefault ?: DEFAULT_SAC).coerceIn(1, 5)

    /** The activity chip in the plan sheet: "Hike · T2"; bike activities by name only. */
    fun chipText(a: Activity, sac: Int): String = if (a == Activity.HIKE) "${a.label} · T${sac.coerceIn(1, 5)}" else a.label
}
