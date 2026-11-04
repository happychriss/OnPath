package com.onpath.app.ui

/**
 * A planned route opened from My routes while a tour runs (owner 2026-11-03, was the dead end "End the running tour
 * first"): the question "Route „‹name›“ laden? Du bist gerade unterwegs." with three answers. Pure, unit-tested
 * (`TourLoadTest`); AppViewModel.answerTourLoad does the steps.
 */
object TourLoad {
    enum class Answer {
        /** "Mit dieser Route weiter" – the primary. */
        CONTINUE,
        /** "Tour beenden und laden". */
        END_AND_LOAD,
        /** "Abbrechen". */
        CANCEL,
    }

    enum class Step {
        /**
         * The recording goes on and the tour follows the loaded route as a new plan from its point ① (owner 2026-11-03,
         * "every tour starts at point 1"): the way there, or "Du stehst mitten auf der Route" when beside a later part.
         */
        FOLLOW_NOW,
        /** The usual end question; only after the tour has ended (saved or deleted) the route loads – Cancel there loads nothing. */
        END_THEN_LOAD,
        /** Nothing changes. */
        NOTHING,
    }

    fun step(a: Answer): Step = when (a) {
        Answer.CONTINUE -> Step.FOLLOW_NOW
        Answer.END_AND_LOAD -> Step.END_THEN_LOAD
        Answer.CANCEL -> Step.NOTHING
    }
}
