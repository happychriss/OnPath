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

    /**
     * A mailbox answer: status, the revision (claim, write, 409) and a page of items (read); [retryAfterS] = the
     * server's retry-after header in seconds (429 / 503; 0 = none).
     */
    data class Reply(val status: Int, val rev: Long = 0, val page: Page? = null, val error: String? = null, val retryAfterS: Long = 0) {
        companion object { const val OFFLINE = -1 }
    }

    /** One page of items: (item id, revision, blob); [rev] = the revision the page covers, [more] = read again. */
    data class Page(val rev: Long, val more: Boolean, val items: List<Triple<String, Long, ByteArray>>)
}

/**
 * One sync round of a device (docs/design/web-sync.md §7), the same on every platform: hello, pull + merge, push;
 * a 409 (someone wrote first) means read again and push once more ([MAX_PASSES]). At most [WRITES_PER_ROUND] writes
 * per round, the rest goes in the next ([nextRoundMs], §8 pacing). Pure: no clock, no random, no
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
        /**
         * Done: [pulled] items read, [pushed] written, [unreadable] could not be opened (newer app, damage), [left] still
         * to upload; [restarted] = the mailbox had lost its history and this device started over (review sync M4 – say
         * it in the problem log); [tooLarge] = items the server refused as too large (they stay in the outbox).
         */
        data class Done(val pulled: Int, val pushed: Int, val unreadable: Int, val left: Int = 0,
                        val restarted: Boolean = false, val tooLarge: Int = 0) : Outcome
        /** No answer from the server (no network, timeout). The ledger keeps what is still to upload. */
        data object Offline : Outcome
        /** 410 / 403: the pairing was ended (unpaired in the browser, or the mailbox belongs to another code). */
        data object Ended : Outcome
        /**
         * The server refused ([status]: 401, 413, 429, 507, 5xx …); [error] = its short reason, [retryAfterS] = its
         * retry-after (s, 0 = none). [busy] statuses are a quiet retry ([nextRoundMs]), the others are said.
         */
        data class Refused(val status: Int, val error: String?, val retryAfterS: Long = 0) : Outcome
    }

    /**
     * Runs a round on [ledger] (changed in place; the caller stores it afterwards, also after a failure). [push] = which
     * changed items go up in this round (default all; the phone's pairing handshake during a tour sends only its hello –
     * the rest stays in the outbox for a later round).
     */
    fun run(ledger: SyncLedger, push: (SyncItem) -> Boolean = { true }): Outcome {
        var pulled = 0; var pushed = 0; var bad = 0; var restarted = false; var tooLarge = 0; var writes = 0
        fun done() = Outcome.Done(pulled, pushed, bad, ledger.outbox().size, restarted, tooLarge)
        repeat(MAX_PASSES) { pass ->
            // pull: every page since the last revision read (a mailbox nobody claimed yet is claimed first)
            var claimed = false
            while (true) {
                val r = box.read(ledger.since)
                if (r.status == 404 && !claimed) {
                    val c = box.claim()
                    if (c.status != 200 && c.status != 201) return failed(c)
                    claimed = true
                    // claimed again although this device had read from it: the mailbox lost its history (M4)
                    if (ledger.since > 0 && !restarted) { ledger.restart(); restarted = true }
                    continue
                }
                val page = r.page
                if (r.status != 200 || page == null) return failed(r)
                // a revision below the one read up to: the mailbox started again from 0 (M4) – read it from the start
                if (page.rev < ledger.since && !restarted) { ledger.restart(); restarted = true; continue }
                bad += ledger.apply(keys, page.items, page.rev, aead)
                pulled += page.items.size
                if (!page.more || page.items.isEmpty()) break
            }
            // push: what changed here, at most [WRITES_PER_ROUND] per round (the hello first – the other side waits for
            // it); a 409 → read again (the merge keeps both versions)
            var conflict = false
            for (e in ledger.outbox().filter { push(it.item) }.sortedBy { it.item.kind != SyncItem.DEVICE }) {
                if (writes >= WRITES_PER_ROUND) return done()
                writes++
                val w = box.write(e.item.id, e.rev, ledger.seal(keys, e, nonce(), aead))
                when (w.status) {
                    200 -> { ledger.acked(e.item, w.rev); pushed++ }
                    409 -> conflict = true
                    413 -> tooLarge++ // one item too large (a server limit change) – it stays, the others go on
                    else -> return failed(w)
                }
            }
            if (!conflict) return done()
            if (pass == MAX_PASSES - 1) return done()
        }
        return done()
    }

    private fun failed(r: Mailbox.Reply): Outcome = outcomeOf(r)

    companion object {
        /** A failed answer as an [Outcome]: no answer → Offline, 410 / 403 → Ended, else Refused. */
        fun outcomeOf(r: Mailbox.Reply): Outcome = when (r.status) {
            Mailbox.Reply.OFFLINE -> Outcome.Offline
            410, 403 -> Outcome.Ended
            else -> Outcome.Refused(r.status, r.error, r.retryAfterS)
        }

        /** Pull + push, and once more after a 409 (owner order A-007: retry once). */
        const val MAX_PASSES = 2

        /**
         * Pacing (field bug 2026-11-12: phone and browser pushed ~110 plans each at once from one home IP into the host's
         * per-IP limit): at most this many writes per round – the hello first – the rest in the next round
         * [NEXT_BATCH_MS] later. A round is then ≤ ~33 requests a minute per device, phone + browser well below the limit.
         */
        const val WRITES_PER_ROUND = 30
        const val NEXT_BATCH_MS = 60_000L
        /** Back-off after a busy answer ([busy]) without retry-after: this, doubled per failure, at most [MAX_RETRY_MS]. */
        const val RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 10 * 60_000L

        /**
         * The server is busy, not refusing: 429 (rate limit) or a 5xx (the host under load, a broken answer) except 507
         * (mailbox full). A quiet retry with back-off ([nextRoundMs]) – nothing red for the user.
         */
        fun busy(status: Int): Boolean = status == 429 || (status in 500..599 && status != 507)

        /**
         * When the next round should run by itself after [o] ([failures] = busy rounds in a row, this one included):
         * the next batch while items wait ([NEXT_BATCH_MS]); after a busy answer the server's retry-after, else
         * [RETRY_MS] doubled per failure – both at most [MAX_RETRY_MS] (a quiet retry costs one request); null = no
         * extra round (the regular triggers are enough).
         */
        fun nextRoundMs(o: Outcome, failures: Int): Long? = when (o) {
            is Outcome.Done -> if (o.left > 0 && o.pushed > 0) NEXT_BATCH_MS else null
            is Outcome.Refused -> if (!busy(o.status)) null
                else if (o.retryAfterS > 0) (o.retryAfterS * 1000).coerceAtMost(MAX_RETRY_MS)
                else (RETRY_MS shl (failures - 1).coerceIn(0, 10)).coerceAtMost(MAX_RETRY_MS)
            else -> null
        }
    }
}

/**
 * The phone's side of [SyncJoin] (A-017, docs/design/web-sync.md §5.1): writes the JOIN [item] into another browser's
 * mailbox [box] (opened with that browser's [keys]) – claims it when nobody has yet, PUTs the item, and after a 409 (a
 * JOIN from an earlier scan is there) once more on the current revision. Reads nothing, writes nothing else. Pure like
 * [SyncRound]; [SyncRound.Outcome.Done] = (0 read, 1 written).
 */
class JoinRound(
    private val keys: SyncKeys,
    private val box: Mailbox,
    private val nonce: () -> ByteArray,
    private val aead: Aead = AesGcm,
) {
    fun run(item: SyncItem): SyncRound.Outcome {
        val c = box.claim()
        if (c.status != 200 && c.status != 201) return SyncRound.outcomeOf(c)
        var base = 0L
        repeat(2) {
            val w = box.write(item.id, base, SyncFormat.seal(keys, item, nonce(), aead))
            when (w.status) {
                200 -> return SyncRound.Outcome.Done(0, 1, 0)
                409 -> base = w.rev
                else -> return SyncRound.outcomeOf(w)
            }
        }
        return SyncRound.Outcome.Refused(409, "conflict")
    }
}
