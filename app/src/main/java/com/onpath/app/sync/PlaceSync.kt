package com.onpath.app.sync

import com.onpath.app.debug.Problems
import com.onpath.app.search.Place
import com.onpath.app.search.SavedPlaces

/**
 * Kept places ⇄ the sync ledger (owner 2026-11-15 „Ja, mit Abgleich“; docs/design/web-sync.md §5.4) – the same two
 * steps on the phone (plansync/PlanSyncPass) and in the planner (web engine): [export] before the round, [import] after
 * it. Pure, shared by app and web planner. [encode] / [decode] = the device's place line codec (the app's SearchStore,
 * the planner's WebKept – the same line format).
 *
 * Places are matched by the app's same-place rule ([SavedPlaces.same]): a place kept on both sides is one place on each
 * side, also when its two lines differ a little (two items in the mailbox, one place shown). A line this device cannot
 * read (a newer app's) is never touched: not shown, not deleted.
 */
class PlaceSync(private val encode: (Place) -> String, private val decode: (String) -> Place?,
                /**
                 * This device keeps a Zuhause (the planner); false = it has none (the app since 2026-11-28): the home item is
                 * read and left alone – never sent, never cleared, never taken in. The format stays the same.
                 */
                private val withHome: Boolean = true) {

    private fun placeOf(item: SyncItem?): Place? {
        val line = item?.place ?: return null
        return try { decode(line) } catch (e: Exception) {
            Problems.warn("sync", "A kept place from the other side could not be read – left alone", e); null
        }
    }

    private fun lineOf(p: Place): String? = try { SyncPlaces.clean(encode(p)) } catch (e: Exception) {
        Problems.warn("sync", "A kept place could not be written for the sync – it stays on this device only", e); null
    }

    /**
     * Before the round: what the device keeps now ([kept]) into [ledger]. A favourite without a live item of the same
     * place → kept (a new item, or a tombstoned one live again); a live item whose place the device no longer keeps → its
     * tombstone; Zuhause → a new version of the home item when it is another place, its tombstone when there is none.
     * Items in [waiting] (their change from the other side has not come in yet) and items whose line this device cannot
     * read are left alone. Returns the number of changes.
     */
    fun export(ledger: SyncLedger, kept: SavedPlaces, now: Long, device: String, waiting: Set<String> = emptySet()): Int {
        var n = 0
        if (withHome && SyncPlaces.HOME_ID !in waiting) {
            val h = ledger.home()
            val hp = placeOf(h)
            val mine = kept.home
            if (mine != null) {
                if (h == null || (hp != null && !SavedPlaces.same(hp, mine))) lineOf(mine)?.let { if (ledger.setHome(it, now, device)) n++ }
            } else if (hp != null) {
                if (ledger.setHome(null, now, device)) n++
            }
        }
        val items = ledger.places().filter { it.id !in waiting }.mapNotNull { i -> placeOf(i)?.let { i to it } }
        for (f in kept.favourites) {
            if (items.any { SavedPlaces.same(it.second, f) }) continue
            val line = lineOf(f) ?: continue
            if (SyncPlaces.itemId(line) in waiting) continue
            val before = ledger.get(SyncPlaces.itemId(line))
            val after = ledger.keepPlace(line, now, device)
            if (after != null && after != before) n++
        }
        for ((item, p) in items) {
            if (kept.favourites.none { SavedPlaces.same(it, p) } && ledger.unkeepPlace(item.id, now, device)) n++
        }
        return n
    }

    /**
     * After the round: the place items [ids] (those the round changed, and any still waiting from an earlier pass) into
     * [current] – what the device keeps now. A live favourite comes in first unless the same place is kept already (one
     * place, never two); a tombstone takes every copy of that place away; the home item sets / clears Zuhause. [seen] =
     * what the device kept at [export] (null = not known): a home the user changed since, or a favourite kept again since,
     * stays as the user has it – the next export sends it. Returns the new places ([current] itself when nothing changed).
     */
    fun import(ledger: SyncLedger, ids: Collection<String>, current: SavedPlaces, seen: SavedPlaces?): SavedPlaces {
        var s = current
        val items = ids.toSet().mapNotNull { ledger.get(it) }.filter { it.kind == SyncItem.PLACE }
            .sortedWith(compareBy<SyncItem> { it.updatedAt }.thenBy { it.id })
        for (item in items) {
            val p = placeOf(item)
            if (item.id == SyncPlaces.HOME_ID) {
                if (!withHome) continue // no Zuhause on this device: the planner's home item is left alone
                val home = s.home
                val untouched = seen == null || (home == null && seen.home == null) ||
                    (home != null && seen.home?.let { SavedPlaces.same(home, it) } == true)
                if (!untouched) continue
                if (!item.deleted) {
                    if (p != null && (home == null || !SavedPlaces.same(home, p))) s = s.withHome(p)
                } else if (home != null && (p == null || SavedPlaces.same(home, p))) s = s.withHome(null)
            } else if (!item.deleted) {
                if (p != null && !s.isFavourite(p)) s = s.copy(favourites = listOf(p) + s.favourites)
            } else {
                val gone = s.favourites.filter { f ->
                    ((p != null && SavedPlaces.same(f, p)) || lineOf(f)?.let(SyncPlaces::itemId) == item.id) &&
                        (seen == null || seen.isFavourite(f))
                }
                if (gone.isNotEmpty()) s = s.copy(favourites = s.favourites - gone.toSet())
            }
        }
        return if (s == current) current else s
    }

    companion object {
        /** The kept-place items the round changed: in [after] and not the same as [before] had them. */
        fun changed(before: Map<String, SyncItem>, after: SyncLedger): List<String> =
            after.entries.filter { (id, e) -> e.item.kind == SyncItem.PLACE && e.item != before[id] }.map { it.key }
    }
}
