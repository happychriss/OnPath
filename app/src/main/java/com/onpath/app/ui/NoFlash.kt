package com.onpath.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Owner rule "no self-dismissing messages" (field test 0.8.7: "a very short message I couldn't read, then another
 * one: downloading"): nothing may appear and vanish – or turn into something else – faster than it can be read.
 *
 * A gate between what the app state says ([update]'s target) and what is on screen:
 * - a passing step ([urgent] false: "Preparing…", "Downloading…", a hint) appears only after it lasted [showDelayMs] –
 *   fast background steps are never shown at all;
 * - something that waits for the user ([urgent]: a question, a failure, "Map ready") appears at once;
 * - whatever is shown stays at least [minShowMs] before it goes or turns into a different step ([stepKey]); updates of
 *   the same step (progress, MB count) show at once;
 * - after the user acted ([userActed]) the next change shows at once (a tap must answer immediately).
 * Pure (time passed in), unit-tested; [rememberNoFlash] runs it in Compose.
 */
class NoFlash<T : Any>(
    val showDelayMs: Long = SHOW_DELAY_MS,
    val minShowMs: Long = MIN_SHOW_MS,
    private val stepKey: (T) -> Any? = { it },
    private val urgent: (T) -> Boolean = { false },
) {
    /** What to show now and when to ask again (null = nothing pending). */
    data class Decision<T>(val shown: T?, val recheckAt: Long?)

    private var shown: T? = null
    private var shownSince = 0L
    private var pendingKey: Any? = null
    private var pendingSince: Long? = null
    private var actedAt: Long? = null

    /** The user tapped something in what is shown: the next change answers at once. */
    fun userActed(now: Long) { actedAt = now }

    fun update(target: T?, now: Long): Decision<T> {
        val acted = actedAt?.let { now - it <= ACT_WINDOW_MS } == true
        val cur = shown
        if (cur == null) {
            if (target == null) { pendingKey = null; pendingSince = null; return Decision(null, null) }
            val key = stepKey(target)
            if (urgent(target) || acted || showDelayMs <= 0) return show(target, now)
            val since = pendingSince?.takeIf { pendingKey == key } ?: now
            pendingKey = key; pendingSince = since
            val due = since + showDelayMs
            return if (now >= due) show(target, now) else Decision(null, due)
        }
        pendingKey = null; pendingSince = null
        val held = now - shownSince < minShowMs && !acted
        if (target == null) {
            if (held) return Decision(cur, shownSince + minShowMs)
            shown = null; actedAt = null
            return Decision(null, null)
        }
        if (stepKey(target) == stepKey(cur)) { shown = target; return Decision(target, null) }
        if (held) return Decision(cur, shownSince + minShowMs)
        // a passing step that follows another one is shown directly (it already is "the same message, next state")
        return show(target, now)
    }

    private fun show(t: T, now: Long): Decision<T> {
        shown = t; shownSince = now; pendingKey = null; pendingSince = null; actedAt = null
        return Decision(t, null)
    }

    companion object {
        /** A passing step must last this long before it is shown at all. */
        const val SHOW_DELAY_MS = 1_000L
        /** Shown things stay at least this long (readable). */
        const val MIN_SHOW_MS = 2_500L
        /** How long after a tap a change counts as the answer to it. */
        const val ACT_WINDOW_MS = 1_500L
    }
}

/** [NoFlash] in Compose: what to show for [target] now; re-checks by itself while something is held or pending. */
@Composable
fun <T : Any> rememberNoFlash(target: T?, gate: NoFlash<T>, now: () -> Long = { android.os.SystemClock.uptimeMillis() }): T? {
    var shown by remember(gate) { mutableStateOf(gate.update(target, now()).shown) }
    LaunchedEffect(gate, target) {
        while (true) {
            val d = gate.update(target, now())
            shown = d.shown
            val at = d.recheckAt ?: break
            delay((at - now()).coerceAtLeast(16))
        }
    }
    return shown
}
