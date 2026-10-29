package com.onpath.app.sync

/**
 * One device's synced items and where it stands in the mailbox – the whole local side of the plan sync, pure and
 * shared (docs/design/web-sync.md §7). The app keeps [write]'s text in its private storage, the web planner in
 * IndexedDB; both run the same steps:
 *
 *   1. GET the mailbox since [since] → [apply] (decrypts, merges, moves [since])
 *   2. for each of [outbox]: [seal] → PUT with base revision = entry.rev → [acked]; a 409 (someone wrote first) just
 *      means: run step 1 again, the merge keeps both versions
 *
 * Text format (version 1): a header line `onpath-sync 1 <since>`, then one line per item
 * `<rev> <d|-> <item bytes as Base64url>` (d = dirty). Reading never throws and never drops what it cannot read: such
 * lines are written back unchanged ([unreadable]), so a damaged or newer store loses nothing.
 */
class SyncLedger private constructor(
    entries: Map<String, SyncEntry>,
    since: Long,
    private val unread: List<String>,
) {
    var entries: Map<String, SyncEntry> = entries
        private set

    /** The mailbox revision read up to. */
    var since: Long = since
        private set

    /** Lines that could not be read (kept as they are). */
    val unreadable: Int get() = unread.size

    /** Saved plans, newest first (no tombstones, no device hellos). */
    fun plans(): List<SyncItem> = entries.values.map { it.item }
        .filter { it.kind == SyncItem.PLAN && !it.deleted }
        .sortedWith(compareByDescending<SyncItem> { it.updatedAt }.thenBy { it.id })

    /** The other paired devices that said hello (newest first). */
    fun devices(myDevice: String): List<SyncItem> = entries.values.map { it.item }
        .filter { it.kind == SyncItem.DEVICE && !it.deleted && it.device != myDevice }
        .sortedByDescending { it.updatedAt }

    /** Items still to upload. */
    fun outbox(): List<SyncEntry> = entries.values.filter { it.dirty }

    fun get(id: String): SyncItem? = entries[id]?.item

    /**
     * Saves a plan: a new one under [id] (from [SyncFormat.newId]) or a new version of an existing one.
     * Returns the stored item, or null when [id] is not an item id.
     */
    fun save(id: String, plan: String, title: String?, now: Long, device: String): SyncItem? {
        if (!SyncFormat.isId(id)) return null
        val old = entries[id]
        return put(SyncItem(id, stamp(old, now), device, kind = SyncItem.PLAN, title = title, plan = plan), old)
    }

    /** Renames a saved plan; false when there is none under [id]. */
    fun rename(id: String, title: String?, now: Long, device: String): Boolean {
        val old = entries[id]?.takeIf { !it.item.deleted && it.item.kind == SyncItem.PLAN } ?: return false
        put(old.item.copy(title = title, updatedAt = stamp(old, now), device = device), old)
        return true
    }

    /** Deletes a saved plan (a tombstone that reaches the other devices); false when there is none. */
    fun delete(id: String, now: Long, device: String): Boolean {
        val old = entries[id]?.takeIf { !it.item.deleted } ?: return false
        put(SyncItem(id, stamp(old, now), device, deleted = true, kind = old.item.kind), old)
        return true
    }

    /** This device's hello (its name for the other side's "Paired with …"); written again only when it changes. */
    fun hello(device: String, name: String, now: Long) {
        val id = SyncFormat.deviceItemId(device)
        val old = entries[id]
        if (old != null && !old.item.deleted && old.item.name == name) return
        put(SyncItem(id, stamp(old, now), device, kind = SyncItem.DEVICE, name = name), old)
    }

    /**
     * Reads one mailbox page: [blobs] = (item id, revision, blob), [upTo] = the revision the page covers. Returns how
     * many blobs could not be opened (wrong key, damaged, a newer format) – they are skipped, the caller reports them.
     */
    fun apply(keys: SyncKeys, blobs: List<Triple<String, Long, ByteArray>>, upTo: Long, aead: Aead = AesGcm): Int {
        var bad = 0
        val remote = blobs.mapNotNull { (id, rev, blob) ->
            SyncFormat.open(keys, id, blob, aead)?.let { RemoteItem(it, rev) } ?: run { bad++; null }
        }
        entries = SyncMerge.reconcile(entries, remote)
        since = maxOf(since, upTo)
        return bad
    }

    /** The blob to upload for [entry]; [nonce] = 12 fresh random bytes. */
    fun seal(keys: SyncKeys, entry: SyncEntry, nonce: ByteArray, aead: Aead = AesGcm): ByteArray =
        SyncFormat.seal(keys, entry.item, nonce, aead)

    /** The mailbox stored [sent] (the item of an [outbox] entry) as revision [rev]. */
    fun acked(sent: SyncItem, rev: Long) {
        val e = entries[sent.id] ?: return
        entries = entries + (sent.id to SyncMerge.acked(e, sent, rev))
    }

    /**
     * Unpaired: the plans stay on this device, everything else about the mailbox is forgotten – device hellos and
     * tombstones go, every plan is marked to upload again, so a new pairing brings them all to the other side.
     */
    fun forget() {
        entries = entries.filterValues { it.item.kind == SyncItem.PLAN && !it.item.deleted }
            .mapValues { SyncEntry(it.value.item, 0, dirty = true) }
        since = 0
    }

    fun write(): String = buildString {
        append(HEADER).append(' ').append(VERSION).append(' ').append(since).append('\n')
        for (e in entries.values) {
            append(e.rev).append(' ').append(if (e.dirty) 'd' else '-').append(' ')
                .append(B64.encode(SyncFormat.encode(e.item))).append('\n')
        }
        for (l in unread) append(l).append('\n')
    }

    private fun stamp(old: SyncEntry?, now: Long) = maxOf(now, (old?.item?.updatedAt ?: 0) + 1)

    private fun put(item: SyncItem, old: SyncEntry?): SyncItem {
        entries = entries + (item.id to SyncEntry(item, old?.rev ?: 0, dirty = true))
        return item
    }

    companion object {
        const val HEADER = "onpath-sync"
        const val VERSION = 1

        fun empty() = SyncLedger(emptyMap(), 0, emptyList())

        /** The ledger in [text] (from [write]); empty or unknown text gives an empty ledger. Never throws. */
        fun read(text: String?): SyncLedger {
            val lines = text.orEmpty().split('\n').filter { it.isNotBlank() }
            val head = lines.firstOrNull()?.split(' ')
            if (head == null || head.size < 3 || head[0] != HEADER || head[1].toIntOrNull() != VERSION) {
                return SyncLedger(emptyMap(), 0, lines) // not ours, damaged or a newer store: keep it all, start empty
            }
            val entries = LinkedHashMap<String, SyncEntry>()
            val unread = ArrayList<String>()
            for (l in lines.drop(1)) {
                val p = l.split(' ')
                val rev = p.getOrNull(0)?.toLongOrNull()
                val item = p.getOrNull(2)?.let(B64::decode)?.let(SyncFormat::decode)
                if (p.size != 3 || rev == null || rev < 0 || item == null || !SyncFormat.isId(item.id)) { unread += l; continue }
                entries[item.id] = SyncEntry(item, rev, dirty = p[1] == "d")
            }
            return SyncLedger(entries, head[2].toLongOrNull()?.coerceAtLeast(0) ?: 0, unread)
        }
    }
}
