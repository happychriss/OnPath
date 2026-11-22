package com.onpath.app.navigation

import kotlin.math.roundToInt

/** JVM-test stand-in for NavText (the real one needs Navigator's prompts): only the rounding VoiceText uses. */
object NavText {
    fun round10(m: Double) = (m / 10).roundToInt() * 10.0
}
