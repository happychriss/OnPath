package com.onpath.app.debug

/**
 * What a main-thread stall seen by the watchdog ([Health]) means, and how it is written down. Pure – MainStallTest.
 *
 * Pixel 2026-11-07: 11 of 13 "Main thread blocked" warnings were the first frame right after a screen was created, on
 * a debuggable, uncompiled build – start-up noise, not field bugs – and the one sampled frame (`Matrix.translate`)
 * did not show where the time went. So: a stall that starts within [FIRST_FRAME_MS] after a screen was created is an
 * INFO "first frame"; one while the app is being left (onStop) says so and stays a WARN; every other one is a WARN.
 * Two samples (at [EARLY_SAMPLE_MS] and [Health.STALL_MS]) and the top frames of the last one go into the message.
 */
object MainStall {
    /** A stall that starts this soon after a screen was created is its first frame. */
    const val FIRST_FRAME_MS = 3_000L
    /** The earlier of the two stack samples (the later one is at [Health.STALL_MS]). */
    const val EARLY_SAMPLE_MS = 250L
    /** Frames of the last sample kept in the message. */
    const val MAX_FRAMES = 5

    enum class Kind { FIRST_FRAME, LEAVING, OTHER }

    /**
     * [sinceCreatedMs] = from the newest screen's creation to the start of the stall (null = no screen created yet or
     * unknown; negative = clocks crossed, treated as unknown); [leaving] = the app was being left (onStop) during the
     * stall. Leaving wins: work in onStop on the main thread is worth a look even right after a start.
     */
    fun kind(sinceCreatedMs: Long?, leaving: Boolean): Kind = when {
        leaving -> Kind.LEAVING
        sinceCreatedMs != null && sinceCreatedMs in 0..FIRST_FRAME_MS -> Kind.FIRST_FRAME
        else -> Kind.OTHER
    }

    /** First frame = INFO (start-up noise), everything else = WARN. */
    fun level(kind: Kind): Problems.Level = if (kind == Kind.FIRST_FRAME) Problems.Level.INFO else Problems.Level.WARN

    /**
     * "Main thread blocked at the first frame after the screen was created (in: Matrix.translate ← View.draw ←
     * MapView.onDraw:120; at 250 ms in: Style.load:88)". [early] / [last] = the stacks at [EARLY_SAMPLE_MS] and
     * [Health.STALL_MS] (empty = not readable); the early one is named only where it differs.
     */
    fun message(kind: Kind, early: List<StackTraceElement>, last: List<StackTraceElement>): String {
        val lastText = frames(last).ifEmpty { listOf("?") }.joinToString(" ← ")
        return "${head(kind, null)} (in: $lastText${earlyPart(early, last)})"
    }

    /**
     * The event-log line (the full evidence; Problems keeps the short [message]): "Main thread blocked 812 ms while
     * leaving the app – stack: Object.wait ← GLThread.onPause ← MapView.onStop … (first [STACK_FRAMES] frames of the
     * last sample); at 250 ms in: …".
     */
    fun traceLine(kind: Kind, blockedMs: Long, early: List<StackTraceElement>, last: List<StackTraceElement>): String =
        "${head(kind, blockedMs)} – stack: ${stackLine(last)}${earlyPart(early, last)}"

    /**
     * The first [max] frames, top first ("Object.wait ← GLThread.onPause ← MapView.onStop …"): the frame that waits is
     * often Android's or MapLibre's, below our own code (2026-11-07: stalls "in Object.wait" / "Matrix.translate" at
     * onStop and on return to the screen without an app frame to blame). Empty stack = "?".
     */
    fun stackLine(stack: List<StackTraceElement>, max: Int = STACK_FRAMES): String =
        stack.take(max).joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }.ifEmpty { "?" }

    /** Frames of the last sample in the event-log line. */
    const val STACK_FRAMES = 12

    private fun head(kind: Kind, blockedMs: Long?): String {
        val base = if (blockedMs == null) "Main thread blocked" else "Main thread blocked $blockedMs ms"
        return when (kind) {
            Kind.FIRST_FRAME -> "$base at the first frame after the screen was created"
            Kind.LEAVING -> "$base while leaving the app"
            Kind.OTHER -> base
        }
    }

    /** "; at 250 ms in: Style.load:88 ← MapStyle.apply:12" – only where the early sample differs from the last. */
    private fun earlyPart(early: List<StackTraceElement>, last: List<StackTraceElement>): String {
        val earlyText = frames(early, max = 2).joinToString(" ← ")
        return if (earlyText.isEmpty() || earlyText == frames(last, max = 2).joinToString(" ← ")) ""
        else "; at $EARLY_SAMPLE_MS ms in: $earlyText"
    }

    /**
     * The frames worth naming, top first: down to the first OnPath frame with the framework frames above it, filled up
     * with the frames below to [max]; an OnPath frame deeper than [max] keeps the top frames, "…" and itself. No OnPath
     * frame: the top [max] frames.
     */
    fun frames(stack: List<StackTraceElement>, max: Int = MAX_FRAMES): List<String> {
        if (stack.isEmpty() || max <= 0) return emptyList()
        val app = stack.indexOfFirst { it.className.startsWith("com.onpath.") }
        return when {
            app < max -> stack.take(max).map(::short) // no OnPath frame (-1) or one within the top frames
            max == 1 -> listOf(short(stack[app]))
            else -> stack.take(max - 2).map(::short) + "…" + short(stack[app])
        }
    }

    /** "MapView.onDraw:120" (class without package; line only when known). */
    fun short(e: StackTraceElement): String =
        e.className.substringAfterLast('.') + "." + e.methodName + if (e.lineNumber > 0) ":${e.lineNumber}" else ""
}
