package com.onpath.app.data

import com.onpath.app.routing.Activity
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.activity_chip_with_level
import com.onpath.strings.activity_with_level

/**
 * Which activity and hiking level (SAC T1–T5) the app starts with (owner): always the last chosen ones, remembered
 * across sessions (keys lastActivity / lastSac); fresh install = Hike, T2.
 *
 * Upgrade from older installs: the activity / level the old app started a session with wins – the old Settings
 * default (keys defaultActivity / defaultSac), else the oldest keys activity / sac. Those oldest keys were also written
 * on each picker choice while the Settings default existed but not used at start, so they are stale and must not win
 * over the default. Pure rules, the storage is [Prefs].
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
     * "New route for …", "Activity set to … from the file").
     */
    fun name(a: Activity, sac: Int): String =
        if (a == Activity.HIKE) Txt.s(Res.string.activity_with_level, a.label, sac.coerceIn(1, 5)) else a.label

    /** The activity chip in the plan sheet: "Hike · T2", bike activities by their chip name (owner). */
    fun chipText(a: Activity, sac: Int): String =
        if (a == Activity.HIKE) Txt.s(Res.string.activity_chip_with_level, a.chip, sac.coerceIn(1, 5)) else a.chip
}
