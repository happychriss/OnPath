package com.onpath.app.routing

import com.onpath.strings.*

/**
 * The texts of a multi-day overview's day row (owner 2026-11-21, variant A): "Tag 1", and "München – Bad Tölz" in grey
 * under it when both places are known. With only one end known, a hotel still shows: "ab Gasthof Krone" where the day
 * starts there, "bis Hotel Post" where it ends there (owner 2026-11-21). Pure; shared with the web planner.
 */
object DayRowText {
    /** "Tag 1" / "Day 1". */
    fun day(n: Int): String = Txt.s(Res.string.multiday_day_n, n.toString())

    /**
     * "von – nach" when both places are known (not blank); with one end known, "ab …" / "bis …" only when that end is a
     * hotel ([fromIsHotel], [toIsHotel]); null otherwise (no second line, never a km position).
     */
    fun places(from: String?, to: String?, fromIsHotel: Boolean = false, toIsHotel: Boolean = false): String? {
        val a = from?.trim().orEmpty(); val b = to?.trim().orEmpty()
        return when {
            a.isNotEmpty() && b.isNotEmpty() -> Txt.s(Res.string.days_from_to, a, b)
            a.isNotEmpty() && fromIsHotel -> Txt.s(Res.string.days_from_hotel, a)
            b.isNotEmpty() && toIsHotel -> Txt.s(Res.string.days_to_hotel, b)
            else -> null
        }
    }
}
