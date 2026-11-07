package com.onpath.app.sync

/**
 * How many tours one paired phone keeps in sync (owner 2026-11-07: „wir beschränken es erstmal auf 250 Touren“) –
 * shared by the app and the web planner (web-sync.md "Limits"). A tour = a saved plan or an imported track that exists;
 * deleted ones (tombstones) and the history of copies do not count. Saving a NEW tour beyond [MAX_TOURS] is refused on
 * both sides; changing, renaming or deleting an existing one always works. The mail host's own cap (web/host sync.js
 * LIMITS.items) is only a safety net above this, with room for tombstones.
 */
object SyncLimits {
    /** Tours per phone. */
    const val MAX_TOURS = 250

    /** From here the fill shows amber (80 %). */
    const val WARN_TOURS = MAX_TOURS * 4 / 5

    /** True = one more NEW tour may be saved while [tours] exist. */
    fun canAdd(tours: Int): Boolean = tours < MAX_TOURS

    /** True = the fill is shown amber. */
    fun nearlyFull(tours: Int): Boolean = tours >= WARN_TOURS
}
