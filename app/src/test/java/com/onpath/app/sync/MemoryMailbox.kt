package com.onpath.app.sync

/**
 * The mailbox's rules (web/host/src/sync.js, docs/design/web-sync.md §8) in memory, for JVM tests: claim by the first
 * token, revisions, compare-and-set on the base revision, pages, 410 after DELETE, 403 for another token. [offline]
 * makes every call answer [Mailbox.Reply.OFFLINE]. The real sync.js is tested against the app's HTTP client in
 * plansync/SyncMailboxE2ETest (Node).
 */
class MemoryServer(private val page: Int = 200) {
    private class Box(val token: String) { var rev = 0L; var ended = false; val items = LinkedHashMap<String, Pair<Long, ByteArray>>() }
    private val boxes = HashMap<String, Box>()
    var offline = false
    var calls = 0
        private set

    fun box(keys: SyncKeys): Mailbox = object : Mailbox {
        private fun gate(block: () -> Mailbox.Reply): Mailbox.Reply { calls++; return if (offline) Mailbox.Reply(Mailbox.Reply.OFFLINE) else block() }
        private fun mine(): Box? = boxes[keys.mailbox]

        override fun claim() = gate {
            val b = mine()
            when {
                b?.ended == true -> Mailbox.Reply(410)
                b == null -> { boxes[keys.mailbox] = Box(keys.token); Mailbox.Reply(201, 0) }
                b.token != keys.token -> Mailbox.Reply(403)
                else -> Mailbox.Reply(200, b.rev)
            }
        }

        override fun read(since: Long) = gate {
            val b = mine()
            when {
                b?.ended == true -> Mailbox.Reply(410)
                b == null -> Mailbox.Reply(404)
                b.token != keys.token -> Mailbox.Reply(403)
                else -> {
                    val all = b.items.filter { it.value.first > since }.entries.sortedBy { it.value.first }
                    val items = all.take(page).map { Triple(it.key, it.value.first, it.value.second) }
                    val more = all.size > page
                    val rev = if (more) items.last().second else b.rev
                    Mailbox.Reply(200, rev, Mailbox.Page(rev, more, items))
                }
            }
        }

        override fun write(id: String, base: Long, blob: ByteArray) = gate {
            val b = mine()
            when {
                b?.ended == true -> Mailbox.Reply(410)
                b == null -> Mailbox.Reply(404)
                b.token != keys.token -> Mailbox.Reply(403)
                blob.size > 32 * 1024 -> Mailbox.Reply(413)
                (b.items[id]?.first ?: 0) != base -> Mailbox.Reply(409, b.items[id]?.first ?: 0)
                else -> { b.rev++; b.items[id] = b.rev to blob; Mailbox.Reply(200, b.rev) }
            }
        }

        override fun end() = gate {
            val b = mine()
            when {
                b == null -> Mailbox.Reply(404)
                b.ended -> Mailbox.Reply(410)
                b.token != keys.token -> Mailbox.Reply(403)
                else -> { b.items.clear(); b.ended = true; Mailbox.Reply(200) }
            }
        }
    }

    /** Every stored blob as Latin-1 text (to check nothing readable is on the server). */
    fun stored(keys: SyncKeys): String = boxes[keys.mailbox]?.items?.values?.joinToString { String(it.second, Charsets.ISO_8859_1) }.orEmpty()
    fun itemCount(keys: SyncKeys): Int = boxes[keys.mailbox]?.items?.size ?: 0
    fun ended(keys: SyncKeys): Boolean = boxes[keys.mailbox]?.ended == true
}
