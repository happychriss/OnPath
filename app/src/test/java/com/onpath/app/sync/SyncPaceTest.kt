package com.onpath.app.sync

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.SharedPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field bug 2026-11-12 (owner's Pixel, re-pairing): phone and browser each pushed ~110 plans at once from one home IP
 * against the host's 120 requests / minute / IP (SYNC_RATE) – 429 / 500, the phone said „nicht angenommen“. Both sides
 * use this round, so both pace: at most [SyncRound.WRITES_PER_ROUND] writes per round, the rest in the next round
 * [SyncRound.NEXT_BATCH_MS] later; a 429 / 5xx is a quiet retry after [SyncRound.retryMs] (docs/design/web-sync.md §8).
 */
class SyncPaceTest {
    private val keys = SyncKeys(ByteArray(32) { (it * 3 + 5).toByte() })
    private val plan = PlanCode.encode(SharedPlan(listOf(GeoPoint(46.6717, 11.15), GeoPoint(46.6879, 11.1111)), Activity.HIKE, 3, title = "Waalweg"))
    private val rnd = kotlin.random.Random(7)
    private val nonce = { rnd.nextBytes(12) }

    private class Side(val name: String, val ledger: SyncLedger) { var nextAt = 0L; var failures = 0; val outcomes = ArrayList<SyncRound.Outcome>() }

    private fun side(name: String, devByte: Int, plans: Int, role: Int): Side {
        val device = SyncFormat.newDevice(ByteArray(8) { devByte.toByte() })
        val l = SyncLedger.empty()
        l.hello(device, name, 1_000, role)
        repeat(plans) { i -> l.save(SyncFormat.newId(ByteArray(16) { j -> (devByte * 37 + i * 7 + j).toByte() }), plan, "$name $i", 2_000L + i, device) }
        return Side(name, l)
    }

    /** One device's rounds until nothing waits and it holds every plan; the clock jumps to the next round of either. */
    private fun runBoth(server: MemoryServer, a: Side, b: Side, total: Int, limitMs: Long): Long {
        var now = 0L
        server.clock = { now }
        while (now <= limitMs) {
            val s = listOf(a, b).minBy { it.nextAt }
            now = s.nextAt
            val o = SyncRound(keys, server.box(keys), nonce).run(s.ledger)
            s.outcomes += o
            val busy = o is SyncRound.Outcome.Refused && SyncRound.busy(o.status)
            s.failures = if (busy) s.failures + 1 else 0
            s.nextAt = now + (SyncRound.nextRoundMs(o, s.failures) ?: POLL_MS)
            if (listOf(a, b).all { it.ledger.outbox().isEmpty() && it.ledger.plans().size == total }) return now
        }
        return -1
    }

    @Test fun rePairingWith110PlansOnBothSidesStaysBelowTheHostLimit() {
        val server = MemoryServer().apply { ratePerMinute = 120 }
        val phone = side("Pixel", 1, 110, SyncItem.ROLE_PHONE)
        val web = side("Chrome", 2, 110, SyncItem.ROLE_BROWSER)
        val at = runBoth(server, phone, web, 220, limitMs = 15 * 60_000L)
        assertTrue("both sides hold all 220 plans within 15 min (done at $at ms)", at >= 0)
        assertEquals("the household never hit the per-IP limit", 0, server.tooMany)
        for (s in listOf(phone, web)) assertTrue("${s.name}: no refusal (${s.outcomes})", s.outcomes.none { it is SyncRound.Outcome.Refused })
    }

    @Test fun aRoundSendsAtMostOneBatchTheHelloFirst() {
        val server = MemoryServer()
        val phone = side("Pixel", 1, 110, SyncItem.ROLE_PHONE)
        val o = SyncRound(keys, server.box(keys), nonce).run(phone.ledger) as SyncRound.Outcome.Done
        assertEquals(SyncRound.WRITES_PER_ROUND, o.pushed)
        assertEquals(111 - SyncRound.WRITES_PER_ROUND, o.left)
        assertTrue("the hello went up in the first batch", phone.ledger.outbox().none { it.item.kind == SyncItem.DEVICE })
        assertEquals(SyncRound.NEXT_BATCH_MS, SyncRound.nextRoundMs(o, 0))
    }

    /** A 429 or a 5xx (the host under load) is "busy": the round stops, keeps what it sent, and waits – no red message. */
    @Test fun busyServerIsAQuietRetryWithBackOff() {
        for (status in listOf(429, 500, 502, 503)) assertTrue("$status", SyncRound.busy(status))
        for (status in listOf(400, 401, 403, 404, 410, 413, 507)) assertTrue("$status", !SyncRound.busy(status))
        val server = MemoryServer().apply { failStatus = 503; failRetryAfterS = 30 }
        val phone = side("Pixel", 1, 10, SyncItem.ROLE_PHONE)
        server.failNext = 1
        val o = SyncRound(keys, server.box(keys), nonce).run(phone.ledger)
        assertEquals(SyncRound.Outcome.Refused(503, "server busy", 30), o)
        assertEquals("retry-after honoured", 30_000L, SyncRound.nextRoundMs(o, 1))
        // no retry-after: 30 s, doubling, at most 10 min
        val bare = SyncRound.Outcome.Refused(500, null)
        assertEquals(30_000L, SyncRound.nextRoundMs(bare, 1))
        assertEquals(60_000L, SyncRound.nextRoundMs(bare, 2))
        assertEquals(600_000L, SyncRound.nextRoundMs(bare, 12))
        // a retry-after beyond the cap (the daily new-mailbox cap) – one quiet request every 10 min at most
        assertEquals(600_000L, SyncRound.nextRoundMs(SyncRound.Outcome.Refused(429, null, 86_400), 1))
        assertEquals("not busy: no automatic retry", null, SyncRound.nextRoundMs(SyncRound.Outcome.Refused(401, null), 1))
        // the next round goes on where it stopped
        val again = SyncRound(keys, server.box(keys), nonce).run(phone.ledger) as SyncRound.Outcome.Done
        assertEquals(0, again.left)
        assertEquals(11, server.itemCount(keys))
    }

    /** A 500 in the middle of a batch: what was acknowledged stays acknowledged, the rest goes next round. */
    @Test fun aRefusalMidBatchLosesNothing() {
        val server = MemoryServer()
        val phone = side("Pixel", 1, 20, SyncItem.ROLE_PHONE)
        val box = server.box(keys)
        var writes = 0
        val flaky = object : Mailbox by box {
            override fun write(id: String, base: Long, blob: ByteArray): Mailbox.Reply =
                if (++writes == 6) Mailbox.Reply(500, error = "internal") else box.write(id, base, blob)
        }
        assertEquals(500, (SyncRound(keys, flaky, nonce).run(phone.ledger) as SyncRound.Outcome.Refused).status)
        assertEquals(16, phone.ledger.outbox().size)
        assertEquals(0, (SyncRound(keys, flaky, nonce).run(phone.ledger) as SyncRound.Outcome.Done).left)
        assertEquals(21, server.itemCount(keys))
    }

    private companion object { const val POLL_MS = 60_000L }
}
