package com.onpath.web

import com.onpath.app.sync.PairCode
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncLedger

/**
 * The plan sync for the page (docs/design/web-sync.md): the app's own sync code (com.onpath.app.sync) behind small
 * string calls. Stateless: the page keeps the ledger text and the pairing code (IndexedDB) and passes them in.
 * Random bytes come from the page (crypto.getRandomValues) as Base64url text; TeaVM has no SecureRandom.
 * The exports (syncPairNew, …) sit in WebApi.kt – TeaVM exports only from the main class.
 */
object WebSync {
    private fun b64(s: String): ByteArray = SyncFormat.fromText(s) ?: ByteArray(0)

    /** A new pairing code from 32 random bytes, or "" when [random] is too short. */
    fun pairNew(random: String): String = b64(random).takeIf { it.size >= 32 }?.let { PairCode.encode(it.copyOf(32)) } ?: ""

    /** The pairing code in a link or text (normalised), or "". */
    fun pairParse(text: String): String = PairCode.parse(text)?.let { PairCode.encode(it) } ?: ""

    /** {mailbox, token, check} for a pairing code, or "null". */
    fun keys(pair: String): String {
        val k = SyncKeys.of("pair=$pair") ?: return "null"
        return "{\"mailbox\":" + str(k.mailbox) + ",\"token\":" + str(k.token) + ",\"check\":" + str(k.check) + "}"
    }

    fun newId(random: String): String = b64(random).takeIf { it.size >= SyncFormat.ID_BYTES }?.let(SyncFormat::newId) ?: ""
    fun newDevice(random: String): String = b64(random).takeIf { it.size >= SyncFormat.DEVICE_BYTES }?.let(SyncFormat::newDevice) ?: ""

    /** Saved plans, newest first: [{id, title, plan, updatedAt, mine}] ([mine] = last changed on this device). */
    fun list(ledger: String, device: String): String = SyncLedger.read(ledger).plans().joinToString(",", "[", "]") {
        "{\"id\":" + str(it.id) + ",\"title\":" + (it.title?.let(::str) ?: "null") + ",\"plan\":" + str(it.plan ?: "") +
            ",\"updatedAt\":" + it.updatedAt + ",\"mine\":" + (it.device == device) + "}"
    }

    /** The other devices: [{name, updatedAt}]. */
    fun devices(ledger: String, device: String): String = SyncLedger.read(ledger).devices(device).joinToString(",", "[", "]") {
        "{\"name\":" + str(it.name ?: "") + ",\"updatedAt\":" + it.updatedAt + "}"
    }

    /** {pending, since, unreadable} – how many items wait for upload. */
    fun state(ledger: String): String = SyncLedger.read(ledger).let {
        "{\"pending\":" + it.outbox().size + ",\"since\":" + it.since + ",\"unreadable\":" + it.unreadable + "}"
    }

    fun save(ledger: String, id: String, plan: String, title: String, now: Double, device: String): String =
        SyncLedger.read(ledger).also { it.save(id, plan, title.ifBlank { null }, now.toLong(), device) }.write()

    fun rename(ledger: String, id: String, title: String, now: Double, device: String): String =
        SyncLedger.read(ledger).also { it.rename(id, title.ifBlank { null }, now.toLong(), device) }.write()

    fun delete(ledger: String, id: String, now: Double, device: String): String =
        SyncLedger.read(ledger).also { it.delete(id, now.toLong(), device) }.write()

    fun hello(ledger: String, device: String, name: String, now: Double): String =
        SyncLedger.read(ledger).also { it.hello(device, name, now.toLong()) }.write()

    fun forget(ledger: String): String = SyncLedger.read(ledger).also { it.forget() }.write()

    /**
     * One mailbox page: [page] = lines "id rev blob", [upTo] its revision. Returns the new ledger text, then a line
     * with the number of blobs that could not be opened.
     */
    fun apply(ledger: String, pair: String, page: String, upTo: Double): String {
        val keys = SyncKeys.of("pair=$pair") ?: return ledger + "\n#bad -1"
        val l = SyncLedger.read(ledger)
        val blobs = page.split('\n').mapNotNull { line ->
            val p = line.trim().split(' ')
            if (p.size != 3) return@mapNotNull null
            val rev = p[1].toLongOrNull() ?: return@mapNotNull null
            Triple(p[0], rev, b64(p[2]))
        }
        val bad = l.apply(keys, blobs, upTo.toLong())
        return l.write() + "#bad $bad"
    }

    /**
     * What to upload: lines "id baseRev blob sent" ([sent] = the item bytes, handed back to [acked]). [random] =
     * 12 random bytes per item (nonces); at most as many items as it has nonces.
     */
    fun outbox(ledger: String, pair: String, random: String): String {
        val keys = SyncKeys.of("pair=$pair") ?: return ""
        val rnd = b64(random)
        val l = SyncLedger.read(ledger)
        return l.outbox().take(rnd.size / 12).mapIndexed { i, e ->
            val blob = l.seal(keys, e, rnd.copyOfRange(12 * i, 12 * i + 12))
            e.item.id + " " + e.rev + " " + SyncFormat.toText(blob) + " " + SyncFormat.toText(SyncFormat.encode(e.item))
        }.joinToString("\n")
    }

    fun acked(ledger: String, sent: String, rev: Double): String {
        val item: SyncItem = SyncFormat.fromText(sent)?.let(SyncFormat::decode) ?: return ledger
        return SyncLedger.read(ledger).also { it.acked(item, rev.toLong()) }.write()
    }

    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
        append('"')
    }
}
