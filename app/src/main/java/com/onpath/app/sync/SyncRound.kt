package com.onpath.app.sync

/**
 * The mailbox as a device talks to it (docs/design/web-sync.md §8) – the app's HTTP client, a test's fake or the
 * local Node mailbox implement it. Every call answers with the HTTP status ([Reply.status]; [Reply.OFFLINE] = no
 * answer at all: no network, timeout, DNS) and never throws.
 */
interface Mailbox {
    /** POST /sync/v1/<mailbox>: 201 claimed, 200 same token, 403 other token, 410 ended. */
    fun claim(): Reply

    /** GET /sync/v1/<mailbox>?since=N: 200 with [Reply.page]; 404 = not claimed yet. */
    fun read(since: Long): Reply

    /** PUT /sync/v1/<mailbox>/<id> with base revision [base]: 200 with [Reply.rev]; 409 = someone wrote first. */
    fun write(id: String, base: Long, blob: ByteArray): Reply

    /** DELETE /sync/v1/<mailbox>: 200 – every item gone, the mailbox answers 410 from then on. */
    fun end(): Reply

    /** A mailbox answer: status, the revision (claim, write, 409) and a page of items (read). */
    data class Reply(val status: Int, val rev: Long = 0, val page: Page? = null, val error: String? = null) {
        companion object { const val OFFLINE = -1 }
    }

    /** One page of items: (item id, revision, blob); [rev] = the revision the page covers, [more] = read again. */
    data class Page(val rev: Long, val more: Boolean, val items: List<Triple<String, Long, ByteArray>>)
}

/**
 * One sync round of a device (docs/design/web-sync.md §7), the same on every platform: hello, pull + merge, push;
 * a 409 (someone wrote first) means read again and push once more ([MAX_PASSES]). Pure: no clock, no random, no
 * network of its own – [nonce] gives 12 fresh random bytes per blob.
 */
class SyncRound(
    private val keys: SyncKeys,
    private val box: Mailbox,
    private val nonce: () -> ByteArray,
    private val aead: Aead = AesGcm,
) {
    /** How a round ended. */
    sealed interface Outcome {
        /** Done: [pulled] items read, [pushed] written, [unreadable] could not be opened (newer app, damage). */
        data class Done(val pulled: Int, val pushed: Int, val unreadable: Int, val left: Int = 0) : Outcome
        /** No answer from the server (no network, timeout). The ledger keeps what is still to upload. */
        data object Offline : Outcome
        /** 410 / 403: the pairing was ended (unpaired in the browser, or the mailbox belongs to another code). */
        data object Ended : Outcome
        /** The server refused ([status]: 401, 413, 429, 507, 5xx …); [error] = its short reason. */
        data class Refused(val status: Int, val error: String?) : Outcome
    }

    /** Runs a round on [ledger] (changed in place; the caller stores it afterwards, also after a failure). */
    fun run(ledger: SyncLedger): Outcome {
        var pulled = 0; var pushed = 0; var bad = 0
        repeat(MAX_PASSES) { pass ->
            // pull: every page since the last revision read (a mailbox nobody claimed yet is claimed first)
            var claimed = false
            while (true) {
                val r = box.read(ledger.since)
                if (r.status == 404 && !claimed) {
                    val c = box.claim()
                    if (c.status != 200 && c.status != 201) return failed(c)
                    claimed = true
                    continue
                }
                val page = r.page
                if (r.status != 200 || page == null) return failed(r)
                bad += ledger.apply(keys, page.items, page.rev, aead)
                pulled += page.items.size
                if (!page.more || page.items.isEmpty()) break
            }
            // push: everything changed here; a 409 → read again (the merge keeps both versions)
            var conflict = false
            for (e in ledger.outbox()) {
                val w = box.write(e.item.id, e.rev, ledger.seal(keys, e, nonce(), aead))
                when (w.status) {
                    200 -> { ledger.acked(e.item, w.rev); pushed++ }
                    409 -> conflict = true
                    413 -> Unit // one item too large (cannot happen below SyncFormat.MAX_BLOB) – the others go on
                    else -> return failed(w)
                }
            }
            if (!conflict) return Outcome.Done(pulled, pushed, bad, ledger.outbox().size)
            if (pass == MAX_PASSES - 1) return Outcome.Done(pulled, pushed, bad, ledger.outbox().size)
        }
        return Outcome.Done(pulled, pushed, bad, ledger.outbox().size)
    }

    private fun failed(r: Mailbox.Reply): Outcome = when (r.status) {
        Mailbox.Reply.OFFLINE -> Outcome.Offline
        410, 403 -> Outcome.Ended
        else -> Outcome.Refused(r.status, r.error)
    }

    companion object {
        /** Pull + push, and once more after a 409 (owner order A-007: retry once). */
        const val MAX_PASSES = 2
    }
}
