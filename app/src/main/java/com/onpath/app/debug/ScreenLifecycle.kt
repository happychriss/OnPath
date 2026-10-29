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
