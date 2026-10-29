package com.onpath.app.map

import com.onpath.app.debug.Problems

/**
 * Expected camera notes – not problems: hundreds of them would bury the real problems in Settings → Diagnostics.
 * They go to the event trace only (step by step, with time, in the diagnostics export).
 */
object MapEvents {
    fun followingStopped(why: String) = Problems.trace("map", "Following stopped: $why")

    fun cameraModeChanged(current: Int, wanted: Int) =
        Problems.trace("map", "Camera mode changed by the map to $current (wanted $wanted)")
}
