package com.onpath.app.data

import com.onpath.app.routing.Activity
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.activity_chip_with_level
import com.onpath.strings.activity_with_level

/**
 * Which activity and hiking level (SAC T1–T5) the app starts with (owner 2026-10-22): always the last chosen ones,
 * remembered across sessions (keys lastActivity / lastSac); fresh install = Hike, T2.
 *
 * Upgrade from older installs: the activity / level the old app started a session with wins. Those had a
 * "Default activity" / "Hiking difficulty" in Settings (keys defaultActivity / defaultSac) and started every session
 * with it; their keys activity / sac were written on each picker choice but not used at start, so they are stale
 * (field bug 2026-10-22: a stale activity = BIKE_TREKKING won over the owner's Hike default). Only installs older
 * than the Settings default started with activity / sac. Pure rules, the storage is [Prefs].
 */
object LastActivity {
    val DEFAULT_ACTIVITY = Activity.HIKE
    const val DEFAULT_SAC = 2

    /** First stored value in order (last chosen, old Settings default, oldest key), else Hike. */
    fun activity(vararg stored: Activity?): Activity = stored.firstOrNull { it != null } ?: DEFAULT_ACTIVITY

    /** Same order as [activity]; the result is kept in T1–T5. */
    fun sac(vararg stored: Int?): Int = (stored.firstOrNull { it != null } ?: DEFAULT_SAC).coerceIn(1, 5)

    /**
     * The activity in a sentence: "Hike T3" / "Gravel bike" – one form for every message (Navigate's switch sheet,
     * "New route for …", "Activity set to … from the file"; review 2026-10-26: they said "Hike (T3)" and "Hike T3").
     */
    fun name(a: Activity, sac: Int): String =
        if (a == Activity.HIKE) Txt.s(Res.string.activity_with_level, a.label, sac.coerceIn(1, 5)) else a.label

    /** The activity chip in the plan sheet: "Hike · T2", bike activities by their chip name (owner 2026-10-22). */
    fun chipText(a: Activity, sac: Int): String =
        if (a == Activity.HIKE) Txt.s(Res.string.activity_chip_with_level, a.chip, sac.coerceIn(1, 5)) else a.chip
}
