package com.onpath.app.debug

/**
 * Which OnPath screens (MainActivity instances) live, and why each came and went – in the event log.
 *
 * Two activities (each its own AppViewModel + map) have lived at once at a cold start right after an install, although
 * the manifest says singleTask and the code never finishes or restarts the activity at start. This trace names each
 * instance (intent, flags, task, restored state, finishing vs. config change) so an occurrence shows where it came
 * from. Pure (no Android types) – unit-tested in ScreenLifecycleTest.
 */
object ScreenLifecycle {
    private val alive = LinkedHashSet<Int>()
    private var next = 0

    /** A new instance: its number and how many others were alive already. */
    @Synchronized
    fun created(): Pair<Int, Int> {
        val n = ++next
        val others = alive.size
        alive += n
        return n to others
    }

    /** Instance [n] is gone; returns how many others are still alive. */
    @Synchronized
    fun destroyed(n: Int): Int { alive -= n; return alive.size }

    /**
     * A launcher start that lands as a non-root screen in OnPath's task (Pixel 2026-11-06/07, after each install: the
     * shell's launch was created on top of the task's old root, then Android re-created that root too – two screens,
     * two view models, two plan-sync workers at once, which raced on the sync files). The screen is singleTask, so it
     * is always the task root; a non-root MAIN/LAUNCHER start adds nothing – it finishes at once (before any view
     * model) and the task's root screen comes up as the one screen. A start that carries something (a link, a GPX, a
     * share) is never dropped here.
     */
    fun standsDown(taskRoot: Boolean, action: String?, categories: Collection<String>?): Boolean =
        !taskRoot && action == "android.intent.action.MAIN" &&
            categories?.contains("android.intent.category.LAUNCHER") == true

    fun stoodDownLine(n: Int) =
        "Screen #$n finished at once: a launcher start that is not the task root – the task's screen comes up instead"

    fun createdLine(n: Int, others: Int, task: Int, root: Boolean, restored: Boolean, intent: String) =
        "Screen #$n created (task $task${if (root) "" else ", not task root"}${if (restored) ", restored state" else ""}): " +
            intent + if (others > 0) " – $others other screen(s) still alive" else ""

    fun newIntentLine(n: Int, intent: String) = "Screen #$n got a new intent: $intent"

    fun destroyedLine(n: Int, finishing: Boolean, configChange: Boolean, others: Int) =
        "Screen #$n destroyed (" + when {
            configChange -> "configuration change"
            finishing -> "finished"
            else -> "by the system"
        } + ")" + if (others > 0) ", $others other screen(s) alive" else ""

    /** "VIEW content://… text/xml [LAUNCHER] flags 0x10200000" – no extras, no shared text (privacy). */
    fun intentText(action: String?, scheme: String?, type: String?, categories: Collection<String>?, flags: Int,
                   fromHistory: Boolean, sender: String?): String = buildString {
        append(action?.removePrefix("android.intent.action.") ?: "no action")
        scheme?.let { append(" $it:") }
        type?.let { append(" $it") }
        categories?.takeIf { it.isNotEmpty() }?.let { c ->
            append(c.joinToString(",", " [", "]") { it.removePrefix("android.intent.category.") })
        }
        append(" flags 0x").append(Integer.toHexString(flags))
        if (fromHistory) append(" from recents")
        sender?.let { append(" from $it") }
    }
}
