package com.onpath.app.sync

/**
 * An item as one device keeps it: [rev] = the mailbox revision this version is based on (0 = never uploaded),
 * [dirty] = changed here since, still to upload.
 */
data class SyncEntry(val item: SyncItem, val rev: Long = 0, val dirty: Boolean = true)

/** An item read from the mailbox at revision [rev]. */
data class RemoteItem(val item: SyncItem, val rev: Long)

/**
 * The conflict rule (owner 2026-10-26): **the newer version wins, the older stays as a copy**. Pure, shared by app and
 * web planner (docs/design/web-sync.md §6).
 *
 * - "Newer" is a total order: updatedAt, then device id, then the item bytes – so every device picks the same winner,
 *   whatever order changes arrive in ([merge] is commutative and idempotent).
 * - The older version, when it is a live plan with other content, becomes a copy: id derived from the older version
 *   (every device derives the same copy, so it never doubles), title "… (copy)". Device hellos and tombstones are
 *   never copied. A delete that is newer than an edit still keeps the edit as a copy – a plan is never lost.
 * - A remote change on an entry not edited here is a plain update (the mailbox's compare-and-set guarantees the other
 *   device saw this version before writing).
 */
object SyncMerge {
    const val COPY = " (copy)"

    data class Merged(val winner: SyncItem, val copy: SyncItem?)

    /** True when [a] wins over [b]. */
    fun newer(a: SyncItem, b: SyncItem): Boolean {
        if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt
        if (a.device != b.device) return a.device > b.device
        val x = SyncFormat.encode(a); val y = SyncFormat.encode(b)
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return (x[i].toInt() and 0xff) > (y[i].toInt() and 0xff)
        return x.size > y.size
    }

    fun merge(a: SyncItem, b: SyncItem): Merged {
        val (w, l) = if (newer(b, a)) b to a else a to b
        if (w.sameContent(l) || l.deleted || l.kind != SyncItem.PLAN) return Merged(w, null)
        return Merged(w, copyOf(l))
    }

    /** The copy that keeps a losing version. */
    fun copyOf(loser: SyncItem): SyncItem {
        val seed = "OnPath copy ".encodeToByteArray() + (B64.decode(loser.id) ?: ByteArray(0)) +
            loser.updatedAt.toString().encodeToByteArray() + loser.device.encodeToByteArray()
        val base = (loser.title ?: "Plan").take(SyncFormat.MAX_TITLE - COPY.length)
        return loser.copy(id = B64.encode(Sha256.hash(seed).copyOf(SyncFormat.ID_BYTES)), title = base + COPY)
    }

    /**
     * [local] (by id) after reading [remote] from the mailbox. New entries come in as they are; a remote revision
     * not newer than the entry's is ignored (already seen); an entry not edited here takes the remote version; an
     * entry edited here meets the remote version in [merge] – the winner stays (to upload unless it is the remote
     * one), a copy of the loser is added (to upload). Repeating the same remote list changes nothing.
     */
    fun reconcile(local: Map<String, SyncEntry>, remote: List<RemoteItem>): Map<String, SyncEntry> {
        val out = LinkedHashMap(local)
        for (r in remote.sortedBy { it.rev }) {
            val e = out[r.item.id]
            when {
                e == null -> out[r.item.id] = SyncEntry(r.item, r.rev, dirty = false)
                r.rev <= e.rev -> Unit
                !e.dirty -> out[r.item.id] = SyncEntry(r.item, r.rev, dirty = false)
                else -> {
                    val m = merge(e.item, r.item)
                    out[r.item.id] = SyncEntry(m.winner, r.rev, dirty = m.winner != r.item)
                    val c = m.copy
                    if (c != null && c.id !in out) out[c.id] = SyncEntry(c, 0, dirty = true)
                }
            }
        }
        return out
    }

    /**
     * [entry] after the mailbox stored [sent] as revision [rev]. When the entry changed again meanwhile it stays
     * dirty (the next upload is based on [rev]).
     */
    fun acked(entry: SyncEntry, sent: SyncItem, rev: Long): SyncEntry =
        if (entry.item == sent) SyncEntry(sent, rev, dirty = false) else entry.copy(rev = maxOf(entry.rev, rev))
}
