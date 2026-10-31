package com.onpath.app.e2e

import com.onpath.app.sync.Mailbox
import com.onpath.app.sync.SyncKeys

/**
 * The sync mailbox in memory for the Release-Rundgang (flow 8): the rules of web/host/src/sync.js that a pass needs –
 * claim by the first token, revisions, compare-and-set on the base revision, pages, 410 after DELETE, 403 for another
 * token. The same rules as the JVM tests' MemoryServer; the real sync.js is tested against the app's HTTP client in
 * plansync/SyncMailboxE2ETest (Node). Installed through PlanSync.mailboxForTests, so the flow needs no network.
 */
class RundgangMailbox {
    private class Box(val token: String) { var rev = 0L; var ended = false; val items = LinkedHashMap<String, Pair<Long, ByteArray>>() }
    private val boxes = HashMap<String, Box>()

    fun box(keys: SyncKeys): Mailbox = object : Mailbox {
        private fun mine(): Box? = boxes[keys.mailbox]
        private fun gate(block: (Box) -> Mailbox.Reply): Mailbox.Reply = synchronized(boxes) {
            val b = mine()
            when {
                b == null -> Mailbox.Reply(404)
                b.ended -> Mailbox.Reply(410)
                b.token != keys.token -> Mailbox.Reply(403)
                else -> block(b)
            }
        }

        override fun claim(): Mailbox.Reply = synchronized(boxes) {
            val b = mine()
            when {
                b?.ended == true -> Mailbox.Reply(410)
                b == null -> { boxes[keys.mailbox] = Box(keys.token); Mailbox.Reply(201, 0) }
                b.token != keys.token -> Mailbox.Reply(403)
                else -> Mailbox.Reply(200, b.rev)
            }
        }

        override fun read(since: Long) = gate { b ->
            val items = b.items.filter { it.value.first > since }.entries.sortedBy { it.value.first }
                .map { Triple(it.key, it.value.first, it.value.second) }
            Mailbox.Reply(200, b.rev, Mailbox.Page(b.rev, false, items))
        }

        override fun write(id: String, base: Long, blob: ByteArray) = gate { b ->
            if ((b.items[id]?.first ?: 0) != base) Mailbox.Reply(409, b.items[id]?.first ?: 0)
            else { b.rev++; b.items[id] = b.rev to blob; Mailbox.Reply(200, b.rev) }
        }

        override fun end() = gate { b -> b.items.clear(); b.ended = true; Mailbox.Reply(200) }
    }
}
