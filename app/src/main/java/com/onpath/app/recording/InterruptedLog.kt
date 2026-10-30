package com.onpath.app.recording

import com.onpath.app.debug.Problems

/**
 * Problem-log lines for a recording that was interrupted (phone restart, app update, app killed). The restart line
 * only says what happened – the user has not chosen anything yet; the choice in the "Unfinished tour" dialog
 * (Continue · Save · Delete) gets its own line, so the log never claims "continue" when the user deleted (A-015).
 */
object InterruptedLog {
    enum class Choice(val word: String) { CONTINUE("Continue"), SAVE("Save"), DELETE("Delete (recycle bin)") }

    fun interruptedLine(tourName: String, boot: Boolean): String =
        "Recording of '$tourName' was interrupted by ${if (boot) "a restart" else "an update"}; " +
            "notification asks to open OnPath (Continue / Save / Delete there)"

    fun choiceLine(tourName: String, choice: Choice): String =
        "Interrupted recording of '$tourName': user chose ${choice.word}"

    /** The user's answer in the "Unfinished tour" dialog. INFO: an expected situation, not a fault. */
    fun chose(tourName: String, choice: Choice) = Problems.info("recording", choiceLine(tourName, choice))
}
