package com.onpath.app.live

/**
 * Which status line the viewer page shows (docs/design/live-tracking.md §3) – the one line that matters. One rule for
 * every page, tested here; the page only turns the [Line] into words. No texts in here.
 */
object LiveStatusLine {
    /** No upload for longer than this (and longer than two planned uploads) = "no contact". */
    const val NO_CONTACT_MS = 10 * 60_000L
    /** From here "no contact" turns from grey to amber. */
    const val NO_CONTACT_LONG_MS = 60 * 60_000L
    /** Standing still shorter than this still reads as "on the way" (owner 2026-11-26: „Macht gerade Pause“ from 10 min). */
    const val PAUSE_MS = 10 * 60_000L
    /** The phone's battery below this (per cent) is the line itself – „Akku 12 %“ (owner 2026-11-26). */
    const val LOW_BATTERY_PCT = 15

    enum class Key {
        /** "Diese Tour wird nicht mehr geteilt." – the box is gone (stopped or expired); nothing else is shown. */
        STOPPED,
        /** "Dieser Link funktioniert nicht." – no key in the link, or the data does not open with it. */
        BROKEN,
        /** The box is not there yet or holds no head: "noch keine Daten". */
        NO_DATA,
        /** "Angekommen · 16:52" when [Line.arrived], else "Tour beendet · 16:42" ([Line.atMs]). */
        ENDED,
        /** "Seit 25 Min. keine Meldung" – the fact only, no guess why ([Line.minutes], [Line.atMs]; [Line.amber] after an hour). */
        NO_CONTACT,
        /** "Akku 12 %" ([Line.percent]) – below [LOW_BATTERY_PCT]; it explains a phone that may go silent. */
        LOW_BATTERY,
        /** "Gerade etwas abseits der Route" ([Line.metres]). */
        OFF_ROUTE,
        /** "Macht gerade Pause" ([Line.minutes] = how long). */
        PAUSE,
        /** "Alles okay, unterwegs" ([Line.minutes] = since the last contact). */
        MOVING,
    }

    data class Line(
        val key: Key,
        /** Whole minutes: since the last contact (MOVING, NO_CONTACT) or of the pause (PAUSE). */
        val minutes: Int? = null,
        /** Metres off the route (OFF_ROUTE). */
        val metres: Int? = null,
        /** A clock time to show (phone clock, ms): the end (ENDED) or the last place's time (NO_CONTACT). */
        val atMs: Long? = null,
        val amber: Boolean = false,
        val arrived: Boolean = false,
        /** The phone's battery in per cent (LOW_BATTERY). */
        val percent: Int? = null,
    )

    /**
     * @param head the opened head; null = none readable
     * @param gone the server answered 410 (stopped / expired)
     * @param broken the link holds no key, or a blob did not open with it
     * @param sinceLastWriteMs the server's clock minus the time of the last upload (both from the server's answer, so
     *   neither phone's clock matters)
     * @param endedMs the server's end mark, when it has one
     */
    fun of(head: LiveHead?, gone: Boolean, broken: Boolean, sinceLastWriteMs: Long, endedMs: Long? = null): Line {
        if (gone) return Line(Key.STOPPED)
        if (broken) return Line(Key.BROKEN)
        if (head == null) return Line(Key.NO_DATA)
        val ago = (sinceLastWriteMs.coerceAtLeast(0) / 60_000L).toInt()
        if (head.state == LiveState.ENDED || endedMs != null)
            return Line(Key.ENDED, atMs = head.fix?.timeMs ?: endedMs, arrived = head.arrived)
        // the phone itself said when it comes next: standing still it reports every ten minutes, and one late upload is no alarm
        val quiet = maxOf(NO_CONTACT_MS, head.nextS.coerceIn(0, 3600) * 2000L)
        if (sinceLastWriteMs > quiet)
            return Line(Key.NO_CONTACT, minutes = ago, atMs = head.fix?.timeMs, amber = sinceLastWriteMs >= NO_CONTACT_LONG_MS)
        val battery = head.batteryPct
        if (battery != null && battery < LOW_BATTERY_PCT) return Line(Key.LOW_BATTERY, percent = battery, amber = true)
        if (!head.arrived && head.offRouteM != null && head.state == LiveState.RECORDING)
            return Line(Key.OFF_ROUTE, metres = head.offRouteM, amber = true)
        val still = head.stillS
        if (head.state == LiveState.PAUSED || still != null) {
            // how long it lasted at the last upload (the phone counted) plus the time since (the server's clock)
            val pause = (still ?: 0) * 1000L + sinceLastWriteMs.coerceAtLeast(0)
            if (head.state == LiveState.PAUSED || pause >= PAUSE_MS) return Line(Key.PAUSE, minutes = (pause / 60_000L).toInt())
        }
        return Line(Key.MOVING, minutes = ago)
    }
}
