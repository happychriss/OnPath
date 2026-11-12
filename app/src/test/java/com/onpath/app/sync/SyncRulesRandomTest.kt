package com.onpath.app.sync

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.SharedPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * CORE FEATURE (sync, field bug 2026-11-12 „Bad Honnef → Bonn“; docs/design/sync-research-2026-11-12.md): many random
 * sequences over a phone, a browser and the mailbox – edits, deletes, clocks that run ahead or behind, devices offline
 * for days, late pushes, a browser read again from the start (restart / re-pair) and tours taken over from an old
 * folder ([SyncLedger.take]). After everything is online again both sides must hold the same tours, and no version a
 * user saved last is lost – unless a user replaced or deleted it knowingly (while holding that very version).
 */
class SyncRulesRandomTest {
    private val keys = SyncKeys(ByteArray(32) { (it * 11 + 3).toByte() })
    private val plan = PlanCode.encode(SharedPlan(listOf(GeoPoint(46.6717, 11.15), GeoPoint(46.6879, 11.1111)), Activity.HIKE, 3))
    private val days = 24 * 3600_000L

    private class Side(val name: String, val device: String, var skew: Long, val box: Mailbox) {
        var ledger = SyncLedger.empty()
        var offline = false
        /** Item id → the title this side's user saved last there (null = deleted it last). */
        val last = HashMap<String, String?>()
    }

    /** A title without its " (copy)" markers – a copy keeps the user's version. */
    private fun core(t: String?) = t?.let { var s = it; while (s.endsWith(SyncMerge.COPY)) s = s.dropLast(SyncMerge.COPY.length); s }

    @Test fun noSavedVersionIsLostAndBothSidesAgree() {
        var failures = 0
        val first = StringBuilder()
        for (seed in 1..400) {
            val problem = runSeed(seed)
            if (problem != null) { failures++; if (first.isEmpty()) first.append("seed $seed: ").append(problem) }
        }
        if (failures > 0) fail("$failures of 400 sequences broke a rule – first: $first")
    }

    private fun runSeed(seed: Int): String? {
        val rnd = Random(seed)
        val server = MemoryServer()
        var clock = 1_793_000_000_000L
        val phone = Side("phone", SyncFormat.newDevice(ByteArray(8) { 1 }), 0, server.box(keys))
        val browser = Side("browser", SyncFormat.newDevice(ByteArray(8) { 2 }), rnd.nextLong(-3 * days, 10 * days), server.box(keys))
        val sides = listOf(phone, browser)
        val ids = (1..4).map { n -> SyncFormat.newId(ByteArray(16) { (n * 13 + seed).toByte() }) }
        /** Titles a user replaced or deleted while holding them – allowed to go. */
        val knowinglyGone = HashSet<String>()
        var token = 0
        var oldFolder: String? = null // the browser's ledger text of earlier – a closed folder
        val log = ArrayList<String>()

        fun round(s: Side) {
            if (s.offline) return
            SyncRound(keys, s.box, { rnd.nextBytes(12) }).run(s.ledger)
        }

        repeat(rnd.nextInt(20, 60)) {
            clock += rnd.nextLong(1, 3 * days / 2)
            val s = sides[rnd.nextInt(2)]
            val now = clock + s.skew
            when (rnd.nextInt(10)) {
                0, 1, 2 -> { // the user saves a new version (or a new tour, or edits a copy)
                    val live = s.ledger.plans().map { it.id }
                    val id = if (live.isNotEmpty() && rnd.nextInt(3) == 0) live.random(rnd) else ids.random(rnd)
                    core(s.ledger.get(id)?.takeIf { !it.deleted }?.title)?.let { knowinglyGone += it }
                    val title = "T${token++}"
                    s.ledger.save(id, plan, title, now, s.device)
                    s.last[id] = title
                    log += "${s.name} saves $title"
                }
                3 -> { // the user deletes a tour shown there
                    val live = s.ledger.plans()
                    if (live.isNotEmpty()) {
                        val it = live.random(rnd)
                        core(it.title)?.let { t -> knowinglyGone += t }
                        s.ledger.delete(it.id, now, s.device)
                        s.last[it.id] = null
                        log += "${s.name} deletes ${it.title}"
                    }
                }
                4, 5, 6 -> { round(s); log += "${s.name} syncs" }
                7 -> { s.offline = !s.offline; log += "${s.name} offline=${s.offline}" }
                8 -> { // the browser is read again from the start (re-pair, a mailbox that lost its history)
                    oldFolder = browser.ledger.write()
                    browser.ledger.restart()
                    log += "browser restarts"
                }
                9 -> { // tours of an old folder of this browser are taken over (Neu verbinden, Without phone)
                    val old = oldFolder
                    if (old != null) {
                        val src = SyncLedger.read(old)
                        for (p in src.plans()) browser.ledger.take(p)
                        log += "browser takes ${src.plans().map { it.title }}"
                    }
                }
            }
        }
        // everything online again, both sync until nothing waits
        for (s in sides) s.offline = false
        repeat(8) { for (s in sides) round(s) }
        if (sides.any { it.ledger.outbox().isNotEmpty() }) return "still waiting after 8 rounds; log ${log.takeLast(20)}"

        val p = phone.ledger.plans().associate { it.id to it.title }
        val b = browser.ledger.plans().associate { it.id to it.title }
        if (p != b) return "phone $p ≠ browser $b; log ${log.takeLast(20)}"
        val present = p.values.mapNotNull(::core).toSet()
        for (s in sides) for ((_, t) in s.last) {
            if (t != null && t !in knowinglyGone && t !in present) return "${s.name}'s last save $t lost; now $p; log ${log.takeLast(25)}"
        }
        return null
    }

    @Test fun theRandomRunIsDeterministic() {
        assertEquals(runSeed(7), runSeed(7))
        assertTrue(true)
    }
}
