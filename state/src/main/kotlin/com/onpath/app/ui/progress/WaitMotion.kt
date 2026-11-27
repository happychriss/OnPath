package com.onpath.app.ui.progress

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * „Nur eine Sache bewegt sich“ (owner 2026-11-27; VISION: a wait shows its steps): while a wait window is open (route, map,
 * round trips – the one WaitWindowDialog), it is the only DECORATION that moves. Every other busy sign – the plan sheet's
 * running bar, the ring at the point being routed, the small spinners, the map-load pill – asks [othersMove] and is hidden
 * meanwhile (hidden, not frozen: a frozen spinner reads as a hang); when the window closes they come back. Live data is
 * never asked: the position dot and the direction arrow at it keep moving (captain 2026-11-27: the hiker needs the
 * direction while re-planning on the way).
 * Counted (two windows may overlap for a frame). Main thread; Compose state, so the signs follow at once.
 */
object WaitMotion {
    private var open by mutableIntStateOf(0)

    /** A wait window is on screen. */
    val windowOpen: Boolean get() = open > 0

    /** May a busy sign other than the wait window move now? */
    fun othersMove(): Boolean = open == 0

    /** The wait window appeared / went (WaitWindowDialog's enter / leave). */
    fun opened() { open++ }
    fun closed() { open = (open - 1).coerceAtLeast(0) }
}
