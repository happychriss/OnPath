package com.onpath.app.domain.tour

import com.onpath.app.data.TourPlan

/**
 * Keeps the copy of the followed plan with the recorded tour (owner 2026-11-07: from Gemacht back to the plan, to ride it
 * again; the last version counts). The app tells it what the tour follows ([followed] at the start, after a replan, a new
 * goal, an activity switch, a route loaded during the tour; [notFollowed] for "Nur aufzeichnen" / a followed track) and
 * which tour records ([tour]); it writes the newest version under the tour's id through [write] (the database), never
 * twice the same. A failed write (returns false; the caller reported it, like every recording write) is tried again with
 * the next change and at the end of the tour, where the last version is written once more – the copy is final then.
 * Nothing followed → nothing stored; a track followed after a plan keeps the plan's last version. Not thread-safe: the
 * app calls it from one serial background thread. Pure (JVM tested, TourPlanCopyTest).
 */
class TourPlanCopy(
    private val write: (tourId: Long, plan: TourPlan) -> Boolean,
    /** The final write at the tour end changed what was stored (a write had failed): the tour's backup file again. */
    private val finalChanged: (tourId: Long) -> Unit = {},
) {
    private var plan: TourPlan? = null
    private var tourId = -1L
    private var written: TourPlan? = null

    /** The plan the tour follows now (newest version); before the start it waits for the tour's id. */
    fun followed(p: TourPlan) {
        if (!p.usable) return
        plan = p
        flush()
    }

    /** Nothing or a track is followed: no plan is copied any more – what is stored stays (the plan's last version). */
    fun notFollowed() { plan = null }

    /** The recording tour's id, null = none. A new tour takes the plan followed at its start; its end writes the last version. */
    fun tour(id: Long?) {
        val now = id ?: -1L
        if (now == tourId) return
        if (tourId >= 0) {
            plan?.let { p ->
                val was = written
                if (write(tourId, p) && was != p) finalChanged(tourId)
            }
            plan = null // the next tour follows only what is followed at its own start
        }
        tourId = now
        written = null
        flush()
    }

    private fun flush() {
        val p = plan ?: return
        if (tourId < 0 || p == written) return
        if (write(tourId, p)) written = p
    }
}
