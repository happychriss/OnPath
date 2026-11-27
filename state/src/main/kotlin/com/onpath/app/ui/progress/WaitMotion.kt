package com.onpath.app.ui.progress

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * „Nur eine Sache bewegt sich“ (owner 2026-11-27; VISION: a wait shows its steps): while a wait window is open (route, map,
 * round trips – the one WaitWindowDialog), it is the ONLY thing that moves. Every other busy sign – the plan sheet's
 * running bar, the ring at the point being routed, the small spinners, the map-load pill, the turning direction arrow at
 * the position – asks [othersMove] and stands still or hides meanwhile; when the window closes they move again.
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
