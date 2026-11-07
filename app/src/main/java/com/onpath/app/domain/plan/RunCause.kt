package com.onpath.app.domain.plan

import com.onpath.app.ui.PlanStatus.Cause

/**
 * What started the routing run (problem 9 / owner 2026-11-01): Cancel of "This route is very long" or of a running
 * calculation takes back exactly that – a point edit is undone, Undo / Redo goes the other way, an activity / T level
 * change gets the activity from before; anything else (newer routing data, a plan restored or loaded) only stops.
 * [next] is set at each trigger, [start] hands it to the run, [takeForCancel] gives it to the one Cancel path. Pure.
 */
class RunCause {
    /** What the next run is for (set just before it). */
    var next: Cause = Cause.Other
        private set
    /** What the current / last run (or long-route question) was started by. */
    var run: Cause = Cause.Other
        private set

    fun pointEdit() { next = Cause.PointEdit }
    fun step(undo: Boolean) { next = if (undo) Cause.Undo else Cause.Redo }
    fun activity(c: Cause) { next = c }
    fun other() { next = Cause.Other }
    /** The same run once more ("Plan anyway", "Weiter warten"): a later Cancel still takes back what started it. */
    fun again() { next = run }

    /** A run starts: it is for [next]; the following run is "other" unless a trigger says otherwise. */
    fun start() { run = next; next = Cause.Other }

    /** Cancel: the cause of the run being cancelled, once. */
    fun takeForCancel(): Cause = run.also { run = Cause.Other }

    /**
     * Cause of an activity / T level change: while the previous change is still calculating (or asked as very long),
     * Cancel must bring back the activity from BEFORE that one, so its cause stays.
     */
    fun activityChange(now: Cause.ActivityChange, previousStillOpen: Boolean): Cause =
        (run as? Cause.ActivityChange)?.takeIf { previousStillOpen } ?: now
}
