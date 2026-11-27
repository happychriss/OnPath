package com.onpath.app.ui

/**
 * Plan sheet steps (owner 2026-10-31): COLLAPSED = rolled up, ONE row "🚲 4 h 40 min · 94.2 km ⚠2 [▶ Start]" – the map
 * almost free; MINI = the summary (activity · time + distance, the route lines, ✕ Close · ⇅ Reverse · ↻ Round trip, the
 * Start button) – where the sheet opens after planning; COMPACT = + the large elevation profile with the finger ruler;
 * FULL = + the Points list. The primary button rides on the bottom edge from MINI on (COLLAPSED has its own small
 * Start). Dragged down to COLLAPSED the sheet stays there through route updates until the user opens it again.
 * Way types & surfaces are in the route details only.
 * During a tour (owner 2026-10-31) there is no COLLAPSED step: MINI is ONE row – "● Tour running · km · time" + a
 * compact [Continue] – so about two thirds of the map are free for holds; COMPACT adds Pause / End tour (one drag + one
 * tap) and the route summary.
 */
enum class PlanSize {
    FULL, COMPACT, MINI, COLLAPSED;

    fun bigger() = when (this) { COLLAPSED -> MINI; MINI -> COMPACT; else -> FULL }
    /** Handle tap: grow one step; on FULL go back to COMPACT. */
    fun tapped() = if (this == FULL) COMPACT else bigger()
    /** Index in the sheet's steps, smallest first (StepSheet). */
    val step: Int get() = when (this) { COLLAPSED -> 0; MINI -> 1; COMPACT -> 2; FULL -> 3 }

    companion object {
        fun ofStep(i: Int): PlanSize = when { i <= 0 -> COLLAPSED; i == 1 -> MINI; i == 2 -> COMPACT; else -> FULL }

        /**
         * Coming to Plan (from Navigate, with or without a tour, and after the tour ended) the sheet starts at its summary
         * step: activity, time, ✕ Neu planen · ⇅ Umkehren · ↻ Rundtour – no elevation profile; one drag up shows it
         * (owner 2026-11-27: „default immer die Stufe zwei unten … ohne Höhenprofil, immer wenn ich nach Planen komme“).
         */
        val ON_ARRIVAL = MINI
    }
}
