package com.onpath.app.plansync

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.sync.Aead
import com.onpath.app.sync.Mailbox
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncLedger
import com.onpath.app.sync.SyncRound

/**
 * The phone's saved routes and imported tracks as the sync sees them (AppSync: the tour database; tests: a list).
 * Every call may throw (database full / gone) – [PlanSyncPass] reports it and tries again next time.
 */
interface TourStore {
    /** Planned routes and imported tracks, recycle bin included (recordings are filtered out by the caller too). */
    fun tours(): List<Tour>
    fun segments(id: Long): List<List<TrackPoint>>
    /** A new planned route from another device; returns the new tour (its line is routed later – [PlanSyncPass.Result.toRoute]). */
    fun addPlan(name: String, plan: PlanContent): Tour
    fun replacePlan(t: Tour, plan: PlanContent): Boolean
    fun addTrack(name: String, track: TrackContent): Tour
    fun replaceTrack(t: Tour, track: TrackContent): Boolean
    fun rename(t: Tour, name: String)
    /** Into the recycle bin (C11: never deleted for good from outside). */
    fun toBin(t: Tour)
    fun fromBin(t: Tour)
}

/**
 * One sync pass of the phone (docs/design/web-sync.md §7 + §11.3):
 * 1. **export** – the saved routes / imported tracks into the ledger (new → a new item, renamed, changed, moved to
 *    the recycle bin or deleted for good → a tombstone, restored → back);
 * 2. **round** – hello, pull + merge, push ([SyncRound]);
 * 3. **import** – what came from the other side into the tours: a new plan / track is added (a plan is routed
 *    afterwards, [Result.toRoute]), a change updates it, a delete moves it to the recycle bin – never deletes it.
 *    New and changed ones are marked until opened ([SyncFiles.marks], My routes: bold + "New from the browser").
 *
 * The ledger and the links are written after the round, also when it failed, and before the import starts; an import
 * that fails stays [SyncLinks.pending] and is tried again next pass. Runs on a background thread; never during a
 * recording ([recording] is asked before the import too).
 */
class PlanSyncPass(
    private val files: SyncFiles,
    private val store: TourStore,
    private val mailbox: (SyncKeys) -> Mailbox,
    private val random: (Int) -> ByteArray,
    private val clock: () -> Long,
    private val deviceName: String,
    private val untitled: (kind: Int) -> String,
    private val aead: Aead,
) {
    data class Result(
        val outcome: SyncRound.Outcome?,
        /** The other side's name ("Chrome on Linux"), newest hello first; null = none yet. */
        val peer: String? = null,
        val added: Int = 0,
        val updated: Int = 0,
        val binned: Int = 0,
        val failed: Int = 0,
        /** Items from a newer app the phone cannot show (unknown kind or plan format). */
        val unreadable: Int = 0,
        /** Planned routes added / changed from outside: route them (AppViewModel, routing dispatcher). */
        val toRoute: List<Long> = emptyList(),
        /** True when the tours changed (refresh My routes). */
        val toursChanged: Boolean = false,
        val skippedForRecording: Boolean = false,
    )

    /** Runs one pass; null when not paired. Throws only when the sync files cannot be written (reported by the caller). */
    fun run(recording: () -> Boolean): Result? {
        val pairing = files.pairing() ?: return null
        val keys = SyncKeys(pairing.secret ?: return null)
        val device = files.device(random)
        val ledger = files.ledger()
        val links = files.links()
        val now = clock()
        export(ledger, links, now, device)
        ledger.hello(device, deviceName, now)
        val before = ledger.entries.mapValues { it.value.item }
        val outcome = SyncRound(keys, mailbox(keys), { random(12) }, aead).run(ledger)
        // what the round changed (only the other side and the merge change items): new or different items, and the
        // copies the merge kept – all to bring into the tours
        for ((id, e) in ledger.entries) if (e.item.isUserItem && e.item != before[id]) links.markPending(id)
        files.saveLinks(links)
        files.saveLedger(ledger)
        val peer = ledger.devices(device).firstOrNull()?.name
        if (outcome is SyncRound.Outcome.Ended) return Result(outcome, peer)
        if (recording()) {
            Problems.info("sync", "Sync: ${links.pending.size} changes from the browser wait until the tour has ended")
            return Result(outcome, peer, skippedForRecording = true)
        }
        val r = import(ledger, links, before)
        files.saveLinks(links)
        if (outcome is SyncRound.Outcome.Done) files.saveLastOk(now)
        return r.copy(outcome = outcome, peer = peer, unreadable = r.unreadable + ((outcome as? SyncRound.Outcome.Done)?.unreadable ?: 0) +
            ledger.entries.values.count { !it.item.deleted && it.item.kind != SyncItem.DEVICE && !it.item.isUserItem })
    }

    /** Local tours → ledger. */
    private fun export(ledger: SyncLedger, links: SyncLinks, now: Long, device: String) {
        val tours = store.tours().filter(RouteItems::synced)
        val byUuid = tours.associateBy { it.uuid }
        // a marked route that is in the recycle bin or gone: no mark (it was not opened, but it is not in the list)
        files.marks.retain(tours.filter { it.deletedMs == null }.mapTo(HashSet()) { it.uuid })
        for (t in tours) {
            if (t.uuid.isEmpty()) continue
            val id = links.itemOf(t.uuid)
            if (id != null && links.isPending(id)) continue // a change from outside is still to come in
            try {
                val live = t.deletedMs == null
                val item = id?.let { ledger.get(it) }
                when {
                    id == null -> if (live) {
                        val nid = SyncFormat.newId(random(SyncFormat.ID_BYTES))
                        if (save(ledger, nid, t, now, device)) links.link(nid, t.uuid)
                    }
                    item == null -> if (live) save(ledger, id, t, now, device)
                    !live -> if (!item.deleted) ledger.delete(id, now, device)
                    item.deleted || item.kind != RouteItems.kindOf(t) -> save(ledger, id, t, now, device)
                    item.kind == SyncItem.PLAN && !RouteItems.samePlan(RouteItems.planOf(t), item.plan) -> save(ledger, id, t, now, device)
                    item.kind == SyncItem.TRACK && !RouteItems.sameTrackHeader(t, item) -> save(ledger, id, t, now, device)
                    item.title != RouteItems.title(t) -> ledger.rename(id, RouteItems.title(t), now, device)
                }
            } catch (e: Exception) {
                Problems.warn("sync", "Sync: a saved route could not be prepared for the browser; tried again next time", e)
            }
        }
        // deleted for good on the phone (recycle bin emptied): a tombstone, then the link goes
        for ((id, uuid) in links.all.toList()) {
            if (uuid in byUuid || links.isPending(id)) continue
            val e = ledger.entries[id]
            when {
                e != null && !e.item.deleted -> ledger.delete(id, now, device)
                e == null || !e.dirty -> links.unlink(id)
            }
        }
    }

    private fun save(ledger: SyncLedger, id: String, t: Tour, now: Long, device: String): Boolean {
        val title = RouteItems.title(t)
        return if (t.kind == TourKind.PLANNED) {
            val plan = RouteItems.planOf(t) ?: return false // fewer than two points: nothing to plan with
            ledger.save(id, plan, title, now, device) != null
        } else {
            val track = RouteItems.trackOf(t, store.segments(t.id)) ?: return false
            ledger.saveTrack(id, track, title, now, device) != null
        }
    }

    /** Ledger → local tours for every pending item. */
    private fun import(ledger: SyncLedger, links: SyncLinks, before: Map<String, SyncItem>): Result {
        if (links.pending.isEmpty()) return Result(null)
        val tours = store.tours().filter(RouteItems::synced).associateBy { it.uuid }
        var added = 0; var updated = 0; var binned = 0; var failed = 0; var unreadable = 0
        val toRoute = ArrayList<Long>()
        for (id in links.pending.toList()) {
            val item = ledger.get(id)
            if (item == null || !item.isUserItem) { links.done(id); continue }
            val tour = links.tourOf(id)?.let { tours[it] }
            try {
                val name = item.title ?: untitled(item.kind)
                when {
                    item.deleted -> if (tour != null && tour.deletedMs == null) { store.toBin(tour); binned++; files.marks.remove(tour.uuid) }
                    tour == null || RouteItems.kindOf(tour) != item.kind -> {
                        val t = if (item.kind == SyncItem.PLAN) RouteItems.planContent(item)?.let { store.addPlan(name, it) }
                            else RouteItems.trackContent(item)?.let { store.addTrack(name, it) }
                        if (t == null) unreadable++
                        else {
                            links.link(id, t.uuid); added++
                            files.marks.arrived(t.uuid) // My routes: bold + "New from the browser" until opened
                            if (t.kind == TourKind.PLANNED) toRoute += t.id
                        }
                    }
                    else -> {
                        var changed = false
                        if (tour.deletedMs != null) { store.fromBin(tour); changed = true }
                        if (item.kind == SyncItem.PLAN) {
                            if (!RouteItems.samePlan(RouteItems.planOf(tour), item.plan)) {
                                val c = RouteItems.planContent(item)
                                if (c == null) unreadable++
                                else if (store.replacePlan(tour, c)) { toRoute += tour.id; changed = true }
                            }
                        } else if (before[id]?.track != item.track || !RouteItems.sameTrackHeader(tour, item)) {
                            val c = RouteItems.trackContent(item)
                            if (c == null) unreadable++ else if (store.replaceTrack(tour, c)) changed = true
                        }
                        if (tour.name != name && RouteItems.title(tour) != item.title) { store.rename(tour, name); changed = true }
                        if (changed) { updated++; files.marks.changed(tour.uuid) } // "Changed in the browser" until opened
                    }
                }
                links.done(id)
            } catch (e: Exception) {
                failed++
                Problems.error("sync", "Sync: a route from the browser could not be saved on the phone; tried again next time", e)
            }
        }
        if (added + updated + binned > 0) Problems.info("sync", "Sync from the browser: $added new, $updated changed, $binned to the recycle bin")
        return Result(null, added = added, updated = updated, binned = binned, failed = failed, unreadable = unreadable,
            toRoute = toRoute, toursChanged = added + updated + binned > 0)
    }

    /**
     * Unpair (Settings → Unpair): ends the mailbox for every device (DELETE), then forgets the pairing here. The plans
     * stay on the phone (and in the browser); the ledger keeps them, marked to upload again on a new pairing.
     * Returns the mailbox's answer ([Mailbox.Reply.OFFLINE] when it could not be reached – unpaired here anyway).
     */
    fun unpair(): Int {
        val pairing = files.pairing()
        val status = pairing?.secret?.let { mailbox(SyncKeys(it)).end().status } ?: 200
        forgetHere()
        return status
    }

    /** The pairing ended (here or in the browser): plans stay, the mailbox is forgotten. */
    fun forgetHere() {
        val l = files.ledger()
        l.forget()
        files.saveLedger(l)
        files.marks.clear()
        files.forgetPairing()
    }
}
