package com.onpath.app.plansync

import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncLedger

/**
 * Is a browser at the other end of the phone's pairing (owner 2026-10-31: "why does my phone say connected when the
 * browser doesn't?")? The pairing file alone says only that the phone has a mailbox; a browser that unpaired itself
 * keeps the mailbox and writes its own hello as deleted (web `sync.ts` `unpair`, A-017 point 8 – no DELETE). So the
 * phone reads the browser hellos in its ledger:
 * - [CONNECTED]: at least one live browser hello (after a scan: one that came after it, [PairScan]);
 * - [UNPAIRED]: browser hellos seen, every one deleted – unpaired in the browser (the user's own action: a grey line,
 *   no amber; ☰ offers "Pair with browser" again, a scanned code joins this phone's mailbox, A-017);
 * - [WAITING]: no browser hello yet (just paired), or after a scan not yet the scanned browser's – ☰ / Browser sync
 *   show the grey "Waiting for the browser …", never "connected" (field bug 2026-10-31).
 *
 * A browser hello = a hello of another device that is not a phone (role 2, or none: an older page; a tombstone carries
 * no role). Pure.
 */
enum class BrowserLink {
    CONNECTED, WAITING, UNPAIRED;

    companion object {
        /** [scan] = a pairing scan (JOIN) whose browser has not been seen yet: only a hello after it counts. */
        fun of(ledger: SyncLedger, myDevice: String, scan: PairScan? = null): BrowserLink {
            val hellos = browserHellos(ledger, myDevice)
            if (scan != null) return if (hellos.any(scan::fresh)) CONNECTED else WAITING
            return when {
                hellos.any { !it.deleted } -> CONNECTED
                hellos.isNotEmpty() -> UNPAIRED
                else -> WAITING
            }
        }

        fun browserHellos(ledger: SyncLedger, myDevice: String): List<SyncItem> = ledger.entries.values.map { it.item }
            .filter { it.kind == SyncItem.DEVICE && it.device != myDevice && (it.deleted || it.role != SyncItem.ROLE_PHONE) }
    }
}

/**
 * A pairing scan that sent another browser into this phone's mailbox (JOIN, A-017) and waits for that browser's hello
 * (owner 2026-10-31: the phone said "connected" from an OLD live browser hello while the scanned browser still waited).
 * [at] = the scan (phone clock), [known] = the browser hellos in the ledger then (item id → updatedAt). A hello is
 * the scanned browser's ([fresh]) when it is live and either new since the scan (not known; written at most
 * [CLOCK_SLACK_MS] before [at] – browsers' clocks differ a little, an old hello the phone had not pulled yet is older)
 * or written again since (a browser that unpaired and pairs again writes a new hello over its tombstone). The browser
 * hello times are compared with each other where they can – the phone's clock only for a device it never saw.
 *
 * Kept in `files/sync/scan.txt` (a restart keeps waiting); cleared when the browser is seen, on a new pairing, on
 * unpair, and [expired] after [WAIT_MS] with a good pass meanwhile (a browser that was live already and scanned again
 * writes no new hello – then the plain rule again). A first pairing (PAIR) needs none: its mailbox is new, every
 * browser hello in it is the scanned browser's. Pure.
 */
data class PairScan(val at: Long, val known: Map<String, Long> = emptyMap()) {
    fun fresh(h: SyncItem): Boolean {
        if (h.deleted) return false
        val was = known[h.id] ?: return h.updatedAt >= at - CLOCK_SLACK_MS
        return h.updatedAt > was
    }

    /** Waited long enough: [passOk] = a pass reached the server just now, [now] = phone clock. */
    fun expired(now: Long, passOk: Boolean): Boolean = passOk && now - at > WAIT_MS

    fun write(): String = buildString {
        append(HEADER).append('\n').append("at ").append(at).append('\n')
        for ((id, ms) in known) append("hello ").append(id).append(' ').append(ms).append('\n')
    }

    companion object {
        private const val HEADER = "onpath-scan 1"
        const val CLOCK_SLACK_MS = 15 * 60_000L
        const val WAIT_MS = 30 * 60_000L

        /** The scan at [at] with the browser hellos [ledger] holds then. */
        fun of(ledger: SyncLedger, myDevice: String, at: Long) =
            PairScan(at, BrowserLink.browserHellos(ledger, myDevice).associate { it.id to it.updatedAt })

        /** From [write]'s text; null when there is none or it cannot be read (then: the plain rule, never a crash). */
        fun read(text: String?): PairScan? {
            val lines = text?.lines()?.filter { it.isNotBlank() } ?: return null
            if (lines.firstOrNull() != HEADER) return null
            val at = lines.firstOrNull { it.startsWith("at ") }?.removePrefix("at ")?.trim()?.toLongOrNull() ?: return null
            val known = lines.filter { it.startsWith("hello ") }.mapNotNull { l ->
                val p = l.split(' ')
                p.getOrNull(2)?.toLongOrNull()?.let { p[1] to it }
            }.toMap()
            return PairScan(at, known)
        }
    }
}
