package com.onpath.app.routing

import com.onpath.strings.*

/**
 * The texts of a multi-day overview's day row (owner 2026-11-21, variant A): "Tag 1", and "München – Bad Tölz" in grey
 * under it only when both places are known. Pure; shared with the web planner.
 */
object DayRowText {
    /** "Tag 1" / "Day 1". */
    fun day(n: Int): String = Txt.s(Res.string.multiday_day_n, n.toString())

    /** "von – nach" when both places are known (not blank); null otherwise (no second line, never a km position). */
    fun places(from: String?, to: String?): String? {
        val a = from?.trim().orEmpty(); val b = to?.trim().orEmpty()
        return if (a.isEmpty() || b.isEmpty()) null else Txt.s(Res.string.days_from_to, a, b)
    }
}
