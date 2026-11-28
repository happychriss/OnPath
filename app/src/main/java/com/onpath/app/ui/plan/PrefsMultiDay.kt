package com.onpath.app.ui.plan

import com.onpath.app.data.Prefs
import com.onpath.app.ui.MultiDayPrefs

/** [MultiDayPrefs] in the app's preferences (`planMultiDay`, `planDayFollow`, `multiDayMapCells` – [Prefs]). */
class PrefsMultiDay(private val prefs: Prefs) : MultiDayPrefs {
    override var planMultiDay: String?
        get() = prefs.planMultiDay
        set(v) { prefs.planMultiDay = v }
    override var planDayFollow: String?
        get() = prefs.planDayFollow
        set(v) { prefs.planDayFollow = v }
    override var multiDayMapCells: Set<String>
        get() = prefs.multiDayMapCells
        set(v) { prefs.multiDayMapCells = v }
}
