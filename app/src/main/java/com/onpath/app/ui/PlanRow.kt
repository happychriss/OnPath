package com.onpath.app.ui

/** The plan head's fit rules (pure, dp). */
object PlanRow {
    /** [count] pills with words (each 12 + 20 + 6 + word + 12 dp) fit the row – else signs only. */
    fun wordsFit(wordsWidthDp: Float, maxWidthDp: Float, count: Int = 3): Boolean = 50f * count + wordsWidthDp <= maxWidthDp

    /** The plan row's pills: ✕ Neu planen · ⇅ Umkehren · ↻ Rundtour. */
    enum class Pill { CLEAR, REVERSE, LOOP }

    /**
     * The pills that can act now – the others are not shown (owner 2026-11-27: „Was man nicht braucht, ist auch nicht
     * sichtbar“, no greyed pills): Neu planen from one point on; Umkehren from two points on and only when it changes
     * the plan ([canReverse] false for an out-and-back over one point, A → B → A); Rundtour from two points on (or while
     * on, to take the way back out) and never in a multi-day tour.
     */
    fun pills(pointCount: Int, canReverse: Boolean, roundTrip: Boolean, canRoundTrip: Boolean, multiDay: Boolean): List<Pill> {
        val two = pointCount >= 2
        return listOfNotNull(
            Pill.CLEAR.takeIf { pointCount >= 1 },
            Pill.REVERSE.takeIf { two && canReverse },
            Pill.LOOP.takeIf { !multiDay && (roundTrip || (two && canRoundTrip)) },
        )
    }

    /** Time + km + ↑ ↓ ([textWidthDp] together, 18 dp between them) fit one line of the sheet (16 dp sides). */
    fun factsOneLine(textWidthDp: Float, screenWidthDp: Float): Boolean = textWidthDp + 18f <= screenWidthDp - 32f
}
