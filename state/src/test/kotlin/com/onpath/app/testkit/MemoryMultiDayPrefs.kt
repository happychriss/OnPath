package com.onpath.app.testkit

import com.onpath.app.ui.MultiDayPrefs

/** [MultiDayPrefs] in memory: what survives "the process" in a test – a second object over the same instance reads what the first wrote. */
class MemoryMultiDayPrefs : MultiDayPrefs {
    override var planMultiDay: String? = null
    override var planDayFollow: String? = null
    override var multiDayMapCells: Set<String> = emptySet()
}
