package com.onpath.app.setup

/**
 * The step-by-step guide (docs/design/onboarding-phone-check.md §4.2) as a pure state: which step is shown, what the
 * user put off or said no to, how it ends. The UI (ui/setup/SetupGuideScreen) only draws it; Android's answers come
 * back through [refresh] with a freshly read [PhoneState].
 */
data class SetupGuide(
    val purpose: Purpose,
    val steps: List<CheckId>,
    val index: Int = 0,
    /** Put off with "Later". */
    val later: Set<CheckId> = emptySet(),
    /** The system dialog came back with a no (the step then explains and offers "Try again"). */
    val denied: Set<CheckId> = emptySet(),
    /** The step that was just finished – the next one shows "✓ …" above it. */
    val justDone: CheckId? = null,
    /** Vibration test: played, and the user's answer. */
    val vibrationPlayed: Boolean = false,
    val vibrationAnswer: Felt = Felt.UNKNOWN,
) {
    val current: CheckId? get() = steps.getOrNull(index)
    val finished: Boolean get() = index >= steps.size

    fun next(done: CheckId? = current): SetupGuide = copy(index = index + 1, justDone = done, vibrationPlayed = false,
        vibrationAnswer = Felt.UNKNOWN)

    /** "Later": go on without it. */
    fun putOff(): SetupGuide = current?.let { copy(later = later + it).next(done = null) } ?: this

    fun denied(id: CheckId): SetupGuide = copy(denied = denied + id)

    fun vibrationPlayed(): SetupGuide = copy(vibrationPlayed = true)
    fun vibrationAnswer(f: Felt): SetupGuide = copy(vibrationAnswer = f)

    /**
     * Coming back from a system dialog or the phone's settings: steps that are fine now are skipped (the current one
     * counts as done), so the user never taps "Done" for something Android already shows us.
     */
    fun refresh(s: PhoneState, vibrationOn: Boolean = true): SetupGuide {
        var g = this
        while (true) {
            val id = g.current ?: return g
            // fine now, or no longer relevant (precise without any location: the location step asks for both)
            if (PhoneCheck.isOk(id, s) || !PhoneCheck.applies(id, s, vibrationOn)) g = g.next(done = id.takeIf { PhoneCheck.isOk(it, s) })
            else return g
        }
    }

    enum class End { ALL_SET, SOME_LATER, TOUR_NOT_READY }

    fun end(s: PhoneState): End = when {
        purpose == Purpose.BEFORE_TOUR && !PhoneCheck.tourReady(s) -> End.TOUR_NOT_READY
        later.isNotEmpty() || steps.any { !PhoneCheck.isOk(it, s) } -> End.SOME_LATER
        else -> End.ALL_SET
    }

    /** "Step 2 of 3" – counted over the steps the guide started with. */
    val stepNumber: Int get() = (index + 1).coerceAtMost(steps.size)

    companion object {
        /** A guide for [purpose], or null when nothing is missing. */
        fun of(purpose: Purpose, s: PhoneState, vibrationOn: Boolean = true, snoozed: Set<CheckId> = emptySet()): SetupGuide? =
            PhoneCheck.steps(purpose, s, vibrationOn, snoozed).takeIf { it.isNotEmpty() }?.let { SetupGuide(purpose, it) }

        /** One step only (Settings → Phone setup → a row's "Set up"). */
        fun single(id: CheckId): SetupGuide = SetupGuide(Purpose.SETTINGS, listOf(id))
    }
}
