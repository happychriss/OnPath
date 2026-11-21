package com.onpath.app.navigation

/** JVM-test stand-in for NavText (the real one pulls the nav prompts and UI tokens): only the rounding VoiceText uses. */
object NavText {
    fun round10(m: Double) = Math.round(m / 10.0).toInt() * 10.0
}
